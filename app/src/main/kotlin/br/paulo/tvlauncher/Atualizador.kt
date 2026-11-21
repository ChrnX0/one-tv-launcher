package br.paulo.tvlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import br.paulo.tvlauncher.panel.confirmar
import br.paulo.tvlauncher.player.CanalAtualizacao
import br.paulo.tvlauncher.privilege.Confirmacoes
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Atualização automática pelo GitHub Releases (05/11/2026).
 *
 * O GitHub publica um Release quando o dono manda publicar (workflow_dispatch na main, .github/workflows/release.yml), com o APK e o SHA-256 dele.
 * O launcher, a cada [INTERVALO_MS], pergunta qual é o último Release e, se o versionCode for maior que o instalado:
 *  1. baixa o APK e o .sha256 e confere um contra o outro (download corrompido = não instala);
 *  2. confere que o APK é do MESMO pacote e assinado com a MESMA chave do launcher instalado. Isso é o que protege a
 *     TV: o Android já recusaria chave diferente, mas assim nem tentamos. (Desde o reset de 10/11/2026 a Sala também
 *     usa a chave pública e se atualiza por aqui, como as outras TVs.)
 *  3. instala sozinho pelo PackageInstaller. Como o launcher é Device Owner, o Android instala sem perguntar.
 *     Só instala com a tela APAGADA, para não reiniciar o launcher na frente de quem está usando a TV.
 *
 * Atualização vigiada (10/11/2026, VigiaRegras.kt/VigiaAtualizacao.kt): se a versão nova cai 2 vezes em 10 min, ela é
 * marcada ruim, a automática pausa (até sair um Release maior) e a TV avisa. Voltar à versão anterior não dá: o
 * Android não deixa app comum (nem Device Owner) instalar versão menor por cima (ver VigiaRegras).
 *
 * Não usa token nem servidor próprio: só a API pública do GitHub (60 pedidos/hora por IP; usamos 1 a cada 6 h).
 * Quem fizer um fork troca [Config.ATUALIZACAO_REPO].
 */
@android.annotation.TargetApi(28)   // longVersionCode e signingInfo são do Android 9; as entradas conferem o SDK_INT
object Atualizador {
    private const val TAG = "TvLauncher.Atualiza"
    private const val PREFS = "atualizador"
    private const val ULTIMA = "ultima_verificacao"
    private const val ESTADO = "estado"
    private const val ESTADO_VERSAO = "estado_versao"   // versão instalada quando o ESTADO foi escrito
    private const val PENDENTE = "pendente"             // Release conferido (sha, pacote, chave) e ainda não instalado
    private const val RECUSADA = "recusada"             // Release recusado (chave, pacote, versão, ilegível): não baixa de novo
    private const val RECUSADA_TEXTO = "recusada_texto" // o motivo, para mostrar sem baixar de novo
    private const val INSTALANDO = "instalando"         // código entregue ao PackageInstaller (o Resultado não sabe qual é)
    private const val FALHOU_CODIGO = "falhou_codigo"   // instalação que falhou: qual código e quantas vezes
    private const val FALHOU_N = "falhou_n"
    private const val MAX_FALHAS = 3
    private const val NOVA_TENTATIVA_MS = 30 * 60_000L  // erro de rede: tenta de novo em 30 min, não em 6 h
    private const val INTERVALO_MS = 6 * 60 * 60_000L
    // Um APK por arquitetura (12/11/2026, ver splits em app/build.gradle.kts). Nome novo (13/11/2026, pedido do dono):
    // one-tv-launcher*.apk. Os nomes antigos (tv-launcher*.apk) ficam aceitos: o Release de transição sai com eles para a
    // Sala se atualizar sozinha, e esta versão já procura o nome novo primeiro.
    private const val NOME_APK_32 = "one-tv-launcher.apk"
    private const val NOME_APK_64 = "one-tv-launcher-arm64.apk"
    private const val NOME_ANTIGO_32 = "tv-launcher.apk"
    private const val NOME_ANTIGO_64 = "tv-launcher-arm64.apk"
    private const val ACAO_RESULTADO = "br.paulo.tvlauncher.ATUALIZACAO_RESULTADO"

    /** Uma verificação por vez. AtomicBoolean: o botão das Configurações e o relógio de 10 min podiam passar juntos
     *  pelo antigo `if (ocupado)` + `ocupado = true` e baixar o mesmo APK no mesmo arquivo ao mesmo tempo. */
    private val ocupado = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Texto curto para a tela de Configurações: o que aconteceu na última verificação. */
    fun estado(ctx: Context): String {
        val p = prefs(ctx)
        val t = p.getString(ESTADO, null) ?: return txt(R.string.atu_nunca)
        // 06/11/2026: o texto velho ("Release X assinado com outra chave") ficava na tela depois de o PC instalar uma
        // versão mais nova, e parecia que havia atualização. Se a versão instalada mudou, o texto não vale mais.
        val v = versaoAtual(ctx)
        // Pausa é o que mais importa mostrar. Só com a pausa valendo: depois que um Release maior a tirou, o modo seguro
        // continua até instalar, mas aí o que vale mostrar é "Release X disponível".
        if (VigiaAtualizacao.ler(ctx).pausada) VigiaAtualizacao.textoPausa(ctx)?.let { return it }
        return if (v != 0L && p.getLong(ESTADO_VERSAO, v) != v) txt(R.string.atu_trocou) else t
    }

    /** versionCode do Release já conferido e pronto para instalar, ou null. Só vale se for maior que o instalado. */
    fun pendente(ctx: Context): Long? {
        val c = prefs(ctx).getLong(PENDENTE, 0L)
        return if (c > versaoAtual(ctx) && VigiaAtualizacao.podeInstalar(ctx, c)) c else null
    }

    private const val CANAL_TESTE = "canal_teste"

    /** Canal de teste (62c): esta TV instala também os pré-lançamentos. Só pelo adb (EXTRAS "canal"). */
    fun canalTeste(ctx: Context): Boolean = prefs(ctx).getBoolean(CANAL_TESTE, false)

    fun definirCanalTeste(ctx: Context, ligado: Boolean) {
        // Troca de canal muda o que "o último Release" quer dizer: a próxima volta confere de novo, sem esperar 6 h.
        prefs(ctx).edit().putBoolean(CANAL_TESTE, ligado).remove(ULTIMA).remove(PENDENTE).apply()
    }

    private fun versaoAtual(ctx: Context): Long = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) instalado(ctx.applicationContext).longVersionCode else 0L
    } catch (e: Exception) { 0L }

    private fun anotar(ctx: Context, t: String) {
        Log.i(TAG, t)
        val hora = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        prefs(ctx).edit().putString(ESTADO, "$hora · $t").putLong(ESTADO_VERSAO, versaoAtual(ctx)).apply()
    }

    /** Chamado pelo relógio de 10 min do GuardiaoShizuku. Só faz algo a cada 6 h e com a tela apagada. */
    fun talvezVerificar(ctx: Context) {
        val app = ctx.applicationContext
        // Aviso de "versão ruim" que não pôde aparecer na abertura (acessibilidade ainda desligada): tenta de novo aqui.
        VigiaAtualizacao.avisarSePendente(app)
        // Build da Play: quem atualiza é a própria loja (a política proíbe app que se atualiza por fora). Ver AtualizacaoPlay.kt.
        if (BuildConfig.PLAY || !Config.ATUALIZACAO_AUTOMATICA || android.os.Build.VERSION.SDK_INT < 28) return
        if (System.currentTimeMillis() - prefs(app).getLong(ULTIMA, 0) < INTERVALO_MS) return
        if (telaAcesa(app)) return   // espera a tela apagar: instalar reinicia o launcher
        // Confirmações: "Bloqueado" = só confere e avisa (fica pendente para o "Instalar agora" das Configurações).
        val sozinho = ConfirmacoesTv.modo(app, Confirmacoes.Acao.ATUALIZACAO) != Confirmacoes.Modo.BLOQUEAR
        verificar(app, instalar = sozinho, soComTelaApagada = true)
    }

    /**
     * Verifica agora (botão das Configurações, com [instalar] = false só mostra o que acharia). Roda numa thread própria;
     * [fim] é chamado nela com o texto do resultado. [soComTelaApagada] (o automático): confere a tela de novo logo antes
     * de instalar, porque alguém pode ter ligado a TV durante o download.
     */
    fun verificar(ctx: Context, instalar: Boolean, soComTelaApagada: Boolean = false, fim: ((String) -> Unit)? = null) {
        if (BuildConfig.PLAY) { fim?.invoke(txt(R.string.atu_pela_play)); return }
        val app = ctx.applicationContext
        if (android.os.Build.VERSION.SDK_INT < 28) { fim?.invoke(txt(R.string.atu_android9)); return }
        if (!ocupado.compareAndSet(false, true)) { fim?.invoke(txt(R.string.atu_ocupado)); return }
        Fundo.rodar {
            var erro = false
            val r = try { verificarAgora(app, instalar, soComTelaApagada) }
                catch (e: Throwable) { erro = true; txt(R.string.atu_erro, e.javaClass.simpleName, e.message.orEmpty()) }
                finally { ocupado.set(false) }   // finally: um Error (falta de memória) deixava "ocupado" até reiniciar
            // Erro (Wi-Fi caiu, 5xx, limite da API): a próxima tentativa é em 30 min, não em 6 h. Na TV que só fica
            // apagada de madrugada, 6 h perdiam o dia.
            val agora = System.currentTimeMillis()
            prefs(app).edit().putLong(ULTIMA, if (erro) agora - INTERVALO_MS + NOVA_TENTATIVA_MS else agora).apply()
            anotar(app, r)
            fim?.invoke(r)
        }
    }

    private fun verificarAgora(app: Context, instalar: Boolean, soComTelaApagada: Boolean): String {
        // Canal de teste (62c): lê a lista e aceita pré-lançamento; o normal pede só o /latest (CanalAtualizacao).
        val json = CanalAtualizacao.escolher(baixarTexto(CanalAtualizacao.url(Config.ATUALIZACAO_REPO, canalTeste(app))))
            ?: return "Release sem versão na tag"
        // A tag é "v<versionCode>" (o workflow cria assim).
        val codigo = CanalAtualizacao.codigo(json.optString("tag_name")) ?: return "Release sem versão na tag"
        val meu = instalado(app).longVersionCode
        // Troca de pacote (19/11/2026, PackageMigration.kt): no app ANTIGO, um Release com o APK do app novo
        // (sofaly-launcher*.apk) não é atualização, é a mudança para tv.sofaly.launcher. Vem ANTES do "em dia" (na Sala, 20/11/2026, vinha depois e a troca nunca começava pelo MCP): a ponte
        // tem o mesmo versionCode do app novo do mesmo Release.
        if (PackageMigration.allowed(app, sozinho = soComTelaApagada)) {
            val nomesRelease = HashSet<String>()
            val lista = json.optJSONArray("assets")
            for (i in 0 until (lista?.length() ?: 0)) nomesRelease += lista!!.getJSONObject(i).optString("name")
            PackageMigrationRules.pickAsset(nomesRelease, android.os.Process.is64Bit())?.let { nome ->
                return migrar(app, json, codigo, nome, instalar, soComTelaApagada)
            }
        }
        if (codigo <= meu) { prefs(app).edit().remove(PENDENTE).apply(); return txt(R.string.atu_em_dia, meu, codigo) }
        // Este mesmo Release já foi recusado pela chave: o APK não muda, então não baixa 12 MB de novo a cada 6 h.
        if (prefs(app).getLong(RECUSADA, 0L) == codigo) return prefs(app).getString(RECUSADA_TEXTO, null) ?: txt(R.string.atu_chave, codigo)
        // Instalação que já falhou [MAX_FALHAS] vezes com este código (disco cheio, dex): não baixa 12 MB a cada 6 h para
        // falhar de novo. Um Release maior tenta do zero.
        val falhas = if (prefs(app).getLong(FALHOU_CODIGO, 0L) == codigo) prefs(app).getInt(FALHOU_N, 0) else 0
        if (falhas >= MAX_FALHAS) return txt(R.string.atu_falhou, falhas, "v$codigo")
        // Atualização vigiada: um Release maior que a versão que caiu tira a pausa; uma versão que já caiu nesta TV
        // nunca é instalada de novo (nem baixa: não adianta).
        VigiaAtualizacao.aoVerRelease(app, codigo)
        if (!VigiaAtualizacao.podeInstalar(app, codigo)) { prefs(app).edit().remove(PENDENTE).apply(); return txt(R.string.atu_ruim, codigo) }

        // Baixa o APK da MESMA arquitetura em que este launcher está rodando: o processo de um app de 32 bits é de 32 bits
        // mesmo num aparelho de 64 (o Android escolhe pela lib nativa do APK instalado). Assim a Sala (32) segue no de 32,
        // uma TV que instalou o de 64 segue no de 64, e ninguém troca de arquitetura numa atualização automática.
        // O app novo (tv.sofaly.launcher) só procura os APKs dele; o antigo, os nomes antigos.
        val nomes = when {
            app.packageName != AppIdentity.LEGACY_PACKAGE ->
                listOf(if (android.os.Process.is64Bit()) PackageMigrationRules.ASSET_64 else PackageMigrationRules.ASSET_32)
            android.os.Process.is64Bit() -> listOf(NOME_APK_64, NOME_ANTIGO_64)
            else -> listOf(NOME_APK_32, NOME_ANTIGO_32)
        }
        // Mapa nome do arquivo -> link de download, para escolher o primeiro nome que tiver o APK e o .sha256 juntos.
        val links = HashMap<String, String>()
        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            links[a.optString("name")] = a.optString("browser_download_url")
        }
        val nomeApk = nomes.firstOrNull { links[it] != null && links["$it.sha256"] != null }
            ?: return txt(R.string.atu_sem_apk, codigo)
        val urlApk = links.getValue(nomeApk)
        val urlSha = links.getValue("$nomeApk.sha256")

        val esperado = baixarTexto(urlSha).trim().split(Regex("\\s+")).first().lowercase()
        val arquivo = File(app.cacheDir, "atualizacao.apk")
        try {
            // Dentro do try (auditoria 10/11/2026): se a rede cair no meio, o finally apaga o APK pela metade.
            baixarArquivo(urlApk, arquivo)
            if (sha256(arquivo) != esperado) return txt(R.string.atu_sha, codigo)

            // Confere o APK baixado (ConferenciaApk, com teste JVM): mesmo pacote, versão igual à da tag e MAIOR que a
            // instalada (lida de novo: o PC pode ter instalado outra durante o download), mesma chave, não marcada ruim.
            val novo = app.packageManager.getPackageArchiveInfo(arquivo.path, PackageManager.GET_SIGNING_CERTIFICATES)
            val atual = instalado(app)
            val conferido = ConferenciaApk.conferir(
                legivel = novo != null, pacoteApk = novo?.packageName, pacoteMeu = app.packageName,
                codigoApk = novo?.longVersionCode ?: 0L, codigoTag = codigo, codigoInstalado = atual.longVersionCode,
                mesmaChave = novo != null && mesmaChave(atual, novo), podeInstalar = VigiaAtualizacao.podeInstalar(app, codigo),
            )
            when (conferido) {
                ConferenciaApk.Resultado.OK -> {}
                // O SHA conferiu: o arquivo é o publicado. Estes não mudam baixando de novo, então ficam recusados.
                ConferenciaApk.Resultado.ILEGIVEL -> return recusar(app, codigo, txt(R.string.atu_ilegivel, codigo))
                ConferenciaApk.Resultado.PACOTE -> return recusar(app, codigo, txt(R.string.atu_pacote, codigo, novo?.packageName.toString()))
                ConferenciaApk.Resultado.VERSAO_TAG -> return recusar(app, codigo, txt(R.string.atu_versao, codigo, novo?.longVersionCode ?: 0L))
                ConferenciaApk.Resultado.NAO_E_MAIOR -> { prefs(app).edit().remove(PENDENTE).apply(); return txt(R.string.atu_em_dia, atual.longVersionCode, codigo) }
                ConferenciaApk.Resultado.RUIM -> { prefs(app).edit().remove(PENDENTE).apply(); return txt(R.string.atu_ruim, codigo) }
                ConferenciaApk.Resultado.CHAVE -> return recusar(app, codigo, txt(R.string.atu_chave, codigo))
            }

            if (!instalar) { prefs(app).edit().putLong(PENDENTE, codigo).apply(); return txt(R.string.atu_disponivel, codigo, meu) }
            // A tela foi conferida só ANTES do download; se alguém ligou a TV nesse meio-tempo, não reinicia o launcher na
            // frente da pessoa: fica pendente (Configurações > Instalar agora, ou a próxima volta com a tela apagada).
            if (soComTelaApagada && telaAcesa(app)) { prefs(app).edit().putLong(PENDENTE, codigo).apply(); return txt(R.string.atu_disponivel, codigo, meu) }
            prefs(app).edit().putLong(INSTALANDO, codigo).apply()
            instalarApk(app, arquivo)
            return txt(R.string.atu_instalando, codigo, meu)
        } finally {
            // Sempre: a sessão já copiou os bytes antes do commit. Antes ficava no cache depois do sucesso (o processo
            // morre antes do Resultado), e o Resultado podia apagar o download de uma verificação seguinte no meio.
            arquivo.delete()
        }
    }

    /**
     * App antigo: baixa e confere o APK do app NOVO (sha, pacote tv.sofaly.launcher, versão da tag, MESMA chave) e o
     * instala em silêncio. Quando o PackageInstaller responde "instalado", o [Resultado] chama PackageMigration.start.
     * Se o app novo já estiver instalado nesta versão (uma tentativa que parou depois da instalação), vai direto.
     */
    private fun migrar(app: Context, json: JSONObject, codigo: Long, nomeApk: String, instalar: Boolean, soComTelaApagada: Boolean): String {
        val novoPacote = AppIdentity.NEW_PACKAGE
        val meu = instalado(app)
        val jaTem = runCatching { app.packageManager.getPackageInfo(novoPacote, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        if (jaTem != null && jaTem.longVersionCode >= codigo && mesmaChave(meu, jaTem)) {
            if (!instalar || (soComTelaApagada && telaAcesa(app))) return txt(R.string.atu_migracao_disponivel, codigo)
            return PackageMigration.start(app)
        }
        val links = HashMap<String, String>()
        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) assets!!.getJSONObject(i).let { links[it.optString("name")] = it.optString("browser_download_url") }
        val esperado = baixarTexto(links.getValue("$nomeApk.sha256")).trim().split(Regex("\\s+")).first().lowercase()
        val arquivo = File(app.cacheDir, "sofaly.apk")
        try {
            baixarArquivo(links.getValue(nomeApk), arquivo)
            if (sha256(arquivo) != esperado) return txt(R.string.atu_sha, codigo)
            val novo = app.packageManager.getPackageArchiveInfo(arquivo.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return recusar(app, codigo, txt(R.string.atu_ilegivel, codigo))
            if (novo.packageName != novoPacote) return recusar(app, codigo, txt(R.string.atu_pacote, codigo, novo.packageName))
            if (novo.longVersionCode != codigo) return recusar(app, codigo, txt(R.string.atu_versao, codigo, novo.longVersionCode))
            // A mesma chave do app instalado: sem isto o app novo não recebe o dono nem os dados (e não deve).
            if (!mesmaChave(meu, novo)) return recusar(app, codigo, txt(R.string.atu_chave, codigo))
            if (!instalar || (soComTelaApagada && telaAcesa(app))) return txt(R.string.atu_migracao_disponivel, codigo)
            prefs(app).edit().putLong(INSTALANDO, codigo).apply()
            val aviso = Intent(app, Resultado::class.java).setAction(ACAO_RESULTADO).putExtra(EXTRA_MIGRACAO, true)
            val pi = PendingIntent.getBroadcast(app, 0x5AF, aviso, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            PackageMigration.installNewApk(app, arquivo, pi.intentSender)
            return txt(R.string.atu_migracao_instalando, codigo)
        } finally { arquivo.delete() }
    }

    private const val EXTRA_MIGRACAO = "migracao"

    private fun recusar(app: Context, codigo: Long, texto: String): String {
        prefs(app).edit().putLong(RECUSADA, codigo).putString(RECUSADA_TEXTO, texto).remove(PENDENTE).apply()
        return texto
    }

    private fun instalado(app: Context): PackageInfo =
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Mesma chave = o conjunto de certificados de quem assinou é igual. Sem signingInfo, recusa. */
    private fun mesmaChave(a: PackageInfo, b: PackageInfo): Boolean {
        val sa = a.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet() ?: return false
        val sb = b.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet() ?: return false
        return sa.isNotEmpty() && sa == sb
    }

    private fun telaAcesa(app: Context) = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun instalarApk(app: Context, arquivo: File) {
        val instalador = app.packageManager.packageInstaller
        // Sessões nossas que sobraram (processo morto no meio da cópia, openSession que lançou): o Android só as expira
        // depois de dias, com os bytes ocupando espaço.
        for (velha in instalador.mySessions) runCatching { instalador.abandonSession(velha.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(arquivo.length())
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            try {
                sessao.openWrite("launcher", 0, arquivo.length()).use { saida ->
                    arquivo.inputStream().use { it.copyTo(saida) }
                    sessao.fsync(saida)
                }
            } catch (e: Exception) {
                // Erro ao copiar (ex.: disco cheio): o close() do use não desfaz a sessão; sem o abandon() ela ficava
                // órfã, com os bytes já gravados, até o Android expirar. Uma a cada tentativa que falhasse.
                try { sessao.abandon() } catch (_: Exception) {}
                throw e
            }
            val aviso = Intent(app, Resultado::class.java).setAction(ACAO_RESULTADO)
            val pi = PendingIntent.getBroadcast(app, id, aviso,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // MUTABLE: o sistema preenche o status
            // Device Owner: o Android instala sem perguntar. Se o processo morrer aqui, é a atualização em si.
            sessao.commit(pi.intentSender)
        }
    }

    private fun conexao(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", Http.USER_AGENT)
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun baixarTexto(url: String): String {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} em $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun baixarArquivo(url: String, destino: File) {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} em $url")
            c.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e -> val b = ByteArray(64 * 1024); while (true) { val n = e.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Resposta do PackageInstaller. Sucesso numa autoatualização quase nunca chega aqui: o processo é trocado antes. */
    class Resultado : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Sem Device Owner o Android pede confirmação: mostra a tela dele.
                @Suppress("DEPRECATION")
                val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            File(ctx.cacheDir, "atualizacao.apk").delete()
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            if (status != PackageInstaller.STATUS_SUCCESS) {
                // Conta as falhas do código que estava instalando ([MAX_FALHAS] = para de tentar este código).
                val p = prefs(ctx); val c = p.getLong(INSTALANDO, 0L)
                if (c > 0) p.edit().putLong(FALHOU_CODIGO, c).putInt(FALHOU_N, if (p.getLong(FALHOU_CODIGO, 0L) == c) p.getInt(FALHOU_N, 0) + 1 else 1).apply()
            }
            anotar(ctx, if (status == PackageInstaller.STATUS_SUCCESS) txt(R.string.atu_ok) else txt(R.string.atu_falhou, status, msg.toString()))
            // Troca de pacote: o app novo acabou de entrar (este processo não foi trocado: é outro pacote). Agora a
            // migração (fora da thread da tela; bloqueia até a transferência do dono).
            if (status == PackageInstaller.STATUS_SUCCESS && intent.getBooleanExtra(EXTRA_MIGRACAO, false)) {
                val app = ctx.applicationContext
                // Sem goAsync: a migração leva mais que o prazo de um broadcast; o processo segue vivo pela acessibilidade.
                Fundo.rodar("migracao") { anotar(app, PackageMigration.start(app)) }
            }
        }
    }
}

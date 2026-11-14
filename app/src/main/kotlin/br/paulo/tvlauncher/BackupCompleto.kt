package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Backup COMPLETO e cifrado (10/11/2026, pedido do dono: "não tem como TUDO isso ser salvo junto do backup?").
 *
 * O backup comum (BackupConfig) leva só grade, despertador, DNS e rotinas, em JSON aberto. Este leva também o que é
 * segredo, por isso sai trancado com uma senha do dono (CofreBackup) e nunca em claro:
 *  - preferências: as do backup comum + "player" + "painel_seguro" (o PIN, só o resumo PBKDF2) + "mcp_seguro" (clientes
 *    e logins do MCP: o conector do claude.ai continua valendo) + "unifi_seguro" + "tunel_seguro";
 *  - segredos que o Keystore cifra (a chave do Keystore morre no reset, então vão em claro DENTRO do cofre e são
 *    cifrados de novo pelo Keystore da instalação nova): contas de TV ao vivo, chave do UniFi, chave do Tailscale;
 *  - arquivos: os pequenos do player (favoritos, controle parental, posições...) e o "crachá" do Tailscale
 *    (tunel/tailscaled.state): a TV volta com o MESMO endereço .ts.net, sem QR de login.
 * O que o Android não deixa nenhum app copiar (contas Google, Wi-Fi, logins de outros apps) fica de fora.
 * Auditoria de 10/11/2026: entraram também "extras", "cenas", "casa_ha" (+ o token do Home Assistant em "segredos"),
 * "modo_visita", "aviso_chuva" e "clima" ("timer" é passageiro). Ficam de fora DE PROPÓSITO: ver [FORA_DE_PROPOSITO].
 */
object BackupCompleto {
    private const val TAG = "TvLauncher.BackupCompleto"
    const val LIMITE_BYTES = 3 * 1024 * 1024        // o texto aberto; um backup real tem dezenas de KB

    /** Preferências que vão inteiras, além das do backup comum (BackupConfig.ARQUIVOS). */
    internal val PREFS_EXTRAS = listOf("player", "painel_seguro", "mcp_seguro", "unifi_seguro", "tunel_seguro",
        "extras", "cenas", "casa_ha", "modo_visita", "aviso_chuva", "clima")
    /**
     * Preferências que NÃO vão, de propósito (o teste BackupCompletoTest confere que nenhuma outra ficou esquecida):
     * "confirmacoes" (restaurar um backup não pode liberar nada sem o PIN, ver ConfirmacoesTv), "painel_log" (registro),
     * "atualizador", "aviso_tv" e "loja_curada" (estado passageiro ou cache baixado de novo).
     */
    internal val FORA_DE_PROPOSITO = setOf("confirmacoes", "painel_log", "atualizador", "aviso_tv", "loja_curada",
        "aot",   // versão já compilada (CompilarAot): é do APK instalado, não da configuração
        "roteiro",   // respostas do "Conferir na TV" (ConferirTvActivity): valem só para a versão em que foram dadas
        "timer",   // timer é passageiro: restaurar um backup feito com o timer correndo o armaria de novo sem ninguém pedir
        "player_pin", "config_pin",   // erros do PIN no player e nas Configurações (TentativasPin): passageiro, e restaurar não pode zerar nem prender ninguém
        "despertador_estado",   // próximo toque anotado (AlarmeGarantia): um antigo restaurado daria aviso falso de alarme perdido
        "backup_noturno",   // a senha do próprio cofre (cifrada pelo Keystore: não abre em outra instalação); redefinir na TV
        "saude_acesso", "manutencao",   // estado passageiro da saúde (SaudeAcesso) e o relatório da última noite
        "trava_relogio",   // como a hora automática estava ANTES da trava: vale só para este aparelho (ver TravaRelogio.kt)
        "foto_perfil",   // só marca "esta foto foi enviada à mão" (FotoPerfil); a foto em si não vai no backup e a chave leva o e-mail
        // Ideias de 12/11/2026. "avisos_celular": as inscrições valem só com a chave VAPID do Keystore desta instalação
        // (inscrever de novo pelo painel). "desempenho" é medida por versão; "retrospectiva" se refaz no domingo; "presenca"
        // e "ideias_12_11" são estado passageiro. A escolha dos celulares, o Meu time e o mural moram em "extras", que vai.
        "avisos_celular", "desempenho", "retrospectiva", "presenca", "ideias_12_11",
        // "painel_tls" (#34): só o nome do certificado https desta TV (ex.: tv-sala). O certificado não vai no backup, e
        // restaurar em outra TV daria a ela o nome da primeira; cada TV escolhe o seu no painel.
        "painel_tls",
        // "volume_lg" (ideia 49): IP e código de pareamento da LG desta casa. Em outra TV/casa apontaria para a LG errada;
        // parear de novo leva um minuto (ComandosExtras lg_*).
        "volume_lg")
    /** Chaves que o Keystore cifrou: não servem em outra instalação. O valor em claro vai em "segredos". */
    private val CHAVES_DO_KEYSTORE = setOf("chave_cifrada", "chave_iv") + CasaHA.CHAVES_DO_KEYSTORE
    /** Arquivos pequenos do player (files/player). O contas.json fica de fora: vai decifrado em "segredos". */
    val ARQUIVOS_PLAYER = listOf("favoritos.json", "recentes.json", "ocultas.json", "liberadas.json", "posicoes.json",
        "interesses_series.json", "interesses_filmes.json", "series_ultimos.json", "series_progresso.json",
        "parental.json", "ultimo.json")
    private const val ESTADO_TUNEL = "tailscaled.state"
    private const val LIMITE_ARQUIVO = 512 * 1024

    /** Monta o backup e tranca com [senha]. Lento (PBKDF2): fora da thread da tela. */
    fun exportar(ctx: Context, senha: String): String {
        val app = ctx.applicationContext
        val prefs = JSONObject()
        for (nome in BackupConfig.ARQUIVOS + PREFS_EXTRAS) {
            val p = BackupConfig.exportarArquivo(app.getSharedPreferences(nome, Context.MODE_PRIVATE))
            if (nome in PREFS_EXTRAS) for (k in CHAVES_DO_KEYSTORE) p.remove(k)
            prefs.put(nome, p)
        }
        val segredos = JSONObject()
            .put("iptv", JSONArray(PlayerDados.fontesParaJson(PlayerArmazem.fontes(app))))
        UnifiArmazem.chave(app)?.let { segredos.put("unifi", it) }
        TunelTailscale.chave(app)?.let { segredos.put("tailscale", it) }
        CasaHA.tokenParaBackup(app)?.let { segredos.put("casa_ha", it) }
        val arquivos = JSONObject()
        val pastaPlayer = PlayerArmazem.pasta(app)
        for (n in ARQUIVOS_PLAYER) lerPequeno(File(pastaPlayer, n))?.let { arquivos.put("player/$n", it) }
        lerPequeno(File(TunelTailscale.pastaEstado(app), ESTADO_TUNEL))?.let { arquivos.put("tunel/$ESTADO_TUNEL", it) }
        val claro = JSONObject().put("versao", 3).put("pacote", app.packageName).put("criadoEm", System.currentTimeMillis())
            .put("preferencias", prefs).put("segredos", segredos).put("arquivos", arquivos).toString()
        conferirTamanho(claro)
        return CofreBackup.trancar(claro, senha)
    }

    /**
     * Falha ALTO se o backup passar do que a restauração aceita ([LIMITE_BYTES]). Antes ele saía normalmente e só era
     * recusado na hora de restaurar, quando já é tarde (depois de um reset, por exemplo).
     */
    internal fun conferirTamanho(claro: String) {
        if (claro.length > LIMITE_BYTES) throw IllegalStateException("backup grande demais (${claro.length / 1024} KB; o limite é ${LIMITE_BYTES / 1024} KB)")
    }

    /** Conteúdo em Base64 (o crachá do Tailscale é binário), ou null se não existe / grande demais. */
    private fun lerPequeno(f: File): String? {
        if (f.isFile && f.length() > LIMITE_ARQUIVO) Log.w(TAG, "${f.name} ficou fora do backup: ${f.length() / 1024} KB")   // não some calado
        return if (f.isFile && f.length() in 1..LIMITE_ARQUIVO.toLong()) Base64.getEncoder().encodeToString(f.readBytes()) else null
    }

    /**
     * Abre o cofre, confere TUDO e só então restaura. Lança CofreBackup.SenhaErrada ou IllegalArgumentException (nada
     * mudou). Devolve quantas preferências restaurou. Quem chama reinicia o launcher depois.
     */
    fun importar(ctx: Context, envelope: String, senha: String): Int {
        val app = ctx.applicationContext
        if (envelope.length > LIMITE_BYTES * 2) throw IllegalArgumentException("arquivo grande demais")
        val claro = CofreBackup.abrir(envelope, senha)
        if (claro.length > LIMITE_BYTES || !ChecagensPainel.jsonRaso(claro)) throw IllegalArgumentException("conteúdo grande ou aninhado demais")
        val o = runCatching { JSONObject(claro) }.getOrNull() ?: throw IllegalArgumentException("conteúdo ilegível")
        if (o.optInt("versao") != 3) throw IllegalArgumentException("versão de backup desconhecida")
        if (o.optString("pacote") != app.packageName) throw IllegalArgumentException("backup de outro app")
        val prefs = o.optJSONObject("preferencias") ?: throw IllegalArgumentException("sem preferências")

        // 1. Confere. As do backup comum passam pela validação dele (tipos conhecidos, alarmes); as extras, pela de tipo.
        val comuns = JSONObject(); val extras = JSONObject()
        for (nome in prefs.keys()) when (nome) {
            in BackupConfig.ARQUIVOS -> comuns.put(nome, prefs.getJSONObject(nome))
            in PREFS_EXTRAS -> extras.put(nome, conferirTipos(nome, prefs.optJSONObject(nome)))
            else -> throw IllegalArgumentException("preferência desconhecida no backup: $nome")
        }
        BackupConfig.validar(app, JSONObject().put("versao", 2).put("pacote", app.packageName).put("arquivos", comuns))
        val segredos = o.optJSONObject("segredos") ?: JSONObject()
        val iptv = PlayerDados.fontesDeJson(segredos.optJSONArray("iptv")?.toString())
        val unifi = segredos.optString("unifi").ifEmpty { null }?.let {
            Unifi.chaveValida(it) ?: throw IllegalArgumentException("chave do UniFi inválida no backup")
        }
        val tailscale = segredos.optString("tailscale").ifEmpty { null }
        if (tailscale != null && !TunelTailscale.chaveValida(tailscale)) throw IllegalArgumentException("chave do Tailscale inválida no backup")
        val tokenCasa = segredos.optString("casa_ha").ifEmpty { null }
        if (tokenCasa != null && !CasaHA.tokenValido(tokenCasa)) throw IllegalArgumentException("token do Home Assistant inválido no backup")
        val arquivos = o.optJSONObject("arquivos") ?: JSONObject()
        val bytes = mutableMapOf<String, ByteArray>()
        for (caminho in arquivos.keys()) {
            val permitido = caminho == "tunel/$ESTADO_TUNEL" || (caminho.startsWith("player/") && caminho.removePrefix("player/") in ARQUIVOS_PLAYER)
            if (!permitido) throw IllegalArgumentException("arquivo desconhecido no backup: $caminho")
            val b = runCatching { Base64.getDecoder().decode(arquivos.getString(caminho)) }.getOrNull()
            if (b == null || b.size > LIMITE_ARQUIVO) throw IllegalArgumentException("arquivo estragado no backup: $caminho")
            bytes[caminho] = b
        }

        // 2. Restaura. Auditoria de 10/11/2026: antes o túnel era APAGADO logo no começo e, se algo falhasse depois, o
        // painel dizia "as configurações de antes foram mantidas" sem ser verdade. Agora: só mexe no túnel se o backup
        // trouxer o túnel (crachá ou chave), guarda um retrato de tudo o que vai mudar e, se falhar, volta ao retrato.
        BackupConfig.arquivoAnterior(app).writeText(BackupConfig.exportar(app))   // o comum de agora, para desfazer
        val trocaTunel = bytes.containsKey("tunel/$ESTADO_TUNEL") || tailscale != null
        if (trocaTunel) extras.put("tunel_seguro", extras.optJSONObject("tunel_seguro") ?: JSONObject())   // vazio = limpa, como o esquecer
        else extras.remove("tunel_seguro")   // o backup não traz o túnel: o de agora continua como está

        // Retrato: as preferências (com as chaves do Keystore, que nesta mesma instalação continuam valendo) e os arquivos.
        val prefsAntes = JSONObject()
        for (nome in BackupConfig.ARQUIVOS + PREFS_EXTRAS) prefsAntes.put(nome, BackupConfig.exportarArquivo(app.getSharedPreferences(nome, Context.MODE_PRIVATE)))
        val destinos = bytes.keys.associateWith { caminho ->
            if (caminho.startsWith("tunel/")) File(TunelTailscale.pastaEstado(app), ESTADO_TUNEL)
            else File(PlayerArmazem.pasta(app), caminho.removePrefix("player/"))
        }
        val arquivosAntes = (destinos.filterKeys { it.startsWith("player/") }.values + PlayerArmazem.arquivoContas(app))
            .associateWith { if (it.isFile) it.readBytes() else null }   // null = não existia (desfazer = apagar)
        val pastaTunel = TunelTailscale.pastaEstado(app)
        val tunelAntigo = File(pastaTunel.parentFile, "tunel.antigo")

        var n = 0
        try {
            if (trocaTunel) {
                // Para o túnel (senão o processo dele regrava o crachá velho por cima) e guarda a pasta em vez de apagar.
                TunelTailscale.pararSemApagar()
                tunelAntigo.deleteRecursively()
                if (pastaTunel.exists() && !pastaTunel.renameTo(tunelAntigo)) throw IllegalStateException("não consegui guardar o túnel de antes")
            }
            n = BackupConfig.aplicar(app, comuns) + BackupConfig.aplicar(app, extras)
            if (!PlayerArmazem.substituirContas(app, iptv)) throw IllegalStateException("não consegui gravar as contas de TV ao vivo")
            if (unifi != null && !UnifiArmazem.definirChave(app, unifi)) throw IllegalStateException("não consegui gravar a chave do UniFi")
            if (tailscale != null && !TunelTailscale.definirChave(app, tailscale)) throw IllegalStateException("não consegui gravar a chave do Tailscale")
            if (tokenCasa != null && !CasaHA.definirToken(app, tokenCasa)) throw IllegalStateException("não consegui gravar o token do Home Assistant")
            for ((caminho, b) in bytes) {
                val destino = destinos.getValue(caminho)
                destino.parentFile?.mkdirs()
                val tmp = File(destino.parentFile, destino.name + ".tmp")
                tmp.writeBytes(b)
                if (!tmp.renameTo(destino)) throw IllegalStateException("não consegui gravar $caminho")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restaurar falhou no meio, voltando ao retrato de antes: $e")
            desfazer(app, prefsAntes, arquivosAntes, if (trocaTunel) tunelAntigo else null)
            throw e
        }
        // Daqui em diante a restauração já valeu: o túnel de antes só é apagado agora, e os agendamentos ficam FORA do
        // try (cada um no seu), para um erro neles não desfazer as preferências com o túnel antigo já apagado.
        tunelAntigo.deleteRecursively()
        runCatching { Despertador.agendar(app) }.onFailure { Log.w(TAG, "reagendar despertador: $it") }
        runCatching { Rotinas.agendar(app) }.onFailure { Log.w(TAG, "reagendar rotinas: $it") }   // antes só o despertador
        runCatching { Timer.reagendar(app) }.onFailure { Log.w(TAG, "reagendar timer: $it") }
        Log.i(TAG, "backup completo restaurado: $n preferências, ${iptv.size} contas de TV, ${bytes.size} arquivos")
        return n
    }

    /** Volta preferências, arquivos e (se foi mexido) a pasta do túnel ao retrato tirado antes de restaurar. */
    private fun desfazer(app: Context, prefsAntes: JSONObject, arquivosAntes: Map<File, ByteArray?>, tunelAntigo: File?) {
        try { BackupConfig.aplicar(app, prefsAntes) } catch (e: Exception) { Log.w(TAG, "desfazer preferências: $e") }
        for ((f, b) in arquivosAntes) try { if (b == null) f.delete() else f.writeBytes(b) } catch (e: Exception) { Log.w(TAG, "desfazer ${f.name}: $e") }
        if (tunelAntigo != null) {
            val pasta = TunelTailscale.pastaEstado(app)
            if (tunelAntigo.exists()) { pasta.deleteRecursively(); if (!tunelAntigo.renameTo(pasta)) Log.w(TAG, "desfazer: o túnel de antes não voltou") }
            TunelTailscale.ajustar(app)   // religa o túnel (se o MCP estiver ligado), como estava
        }
    }

    /** Só confere que cada item tem um tipo conhecido e um valor daquele tipo (mesmo formato do BackupConfig). */
    private fun conferirTipos(nome: String, p: JSONObject?): JSONObject {
        p ?: throw IllegalArgumentException("\"$nome\" não é um objeto")
        for (chave in p.keys()) {
            if (chave in CHAVES_DO_KEYSTORE) throw IllegalArgumentException("$nome/$chave não pode vir no backup")
            val item = p.optJSONObject(chave) ?: throw IllegalArgumentException("$nome/$chave não é um objeto")
            try {
                when (item.optString("t")) {
                    "b" -> item.getBoolean("v"); "i" -> item.getInt("v"); "l" -> item.getLong("v")
                    "f" -> item.getDouble("v"); "s" -> item.getString("v")
                    "ss" -> { val a = item.getJSONArray("v"); for (i in 0 until a.length()) a.getString(i) }
                    else -> throw IllegalArgumentException("$nome/$chave: tipo desconhecido")
                }
            } catch (e: org.json.JSONException) { throw IllegalArgumentException("$nome/$chave: valor do tipo errado") }
            // Voltas do PBKDF2 do PIN (auditoria 10/11): um número absurdo deixaria cada login preso por horas, e 0 ou negativo
            // quebraria a conferência; nos dois casos o PIN do painel deixaria de funcionar até ser refeito na TV.
            if (nome == "painel_seguro" && (chave == "pin_iteracoes" || chave == "fam_iteracoes") && item.optInt("v") !in PinSeguro.ITERACOES_ACEITAS)
                throw IllegalArgumentException("$nome/$chave fora da faixa")
        }
        return p
    }
}

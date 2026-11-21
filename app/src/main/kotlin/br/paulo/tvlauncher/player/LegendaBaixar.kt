package br.paulo.tvlauncher.player

import android.content.Context
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.panel.entrar
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * A parte de rede e disco da legenda baixada (LegendaExterna, 11/11/2026). Tudo aqui bloqueia: chamar fora da thread da tela.
 *
 * 14/11/2026: a chave de API é a do APLICATIVO (BuildConfig.OPENSUBTITLES_CHAVE, secret do GitHub): o OpenSubtitles proíbe
 * app que pede a chave ao usuário. A conta (usuário e senha, opcional: sobe o limite diário) fica em [ServicosContas].
 * Quem já tinha cadastrado a chave própria (legendas_conta.json) continua usando-a enquanto o build não tiver a do app. A legenda baixada fica em files/player/legendas/, uma por vídeo: abrir de novo o
 * mesmo filme não gasta outro download do limite diário.
 */
object LegendaBaixar {
    /** Falha com frase para a tela (no idioma da TV, ou o motivo que o OpenSubtitles mandou). As outras (rede, etc.)
     *  viram a frase genérica legenda_erro: "Unable to resolve host…" não é para quem está no sofá. */
    class Falha(mensagem: String) : Exception(mensagem)

    private const val CONTA = "legendas_conta.json"
    private const val MAX_GUARDADAS = 60

    data class Conta(val chaveApi: String, val usuario: String = "", val senha: String = "")

    @Volatile private var token: String? = null

    // ---------------------------------------------------------------- conta do dono

    /** A conta em uso: chave do app + usuário/senha de ServicosContas; sem chave do app, a antiga cadastrada pelo dono. */
    fun contaEmUso(ctx: Context): Conta? {
        val chaveApp = ServicosContas.chavesApp().openSubtitles
        val antiga = conta(ctx)
        if (chaveApp.isBlank()) return antiga
        val c = ServicosContas.conta(ctx, ServicosFilmes.Servico.OPENSUBTITLES)
        return when {
            c != null && c.usuario.isNotBlank() -> Conta(chaveApp, c.usuario, c.senha)
            antiga != null -> Conta(chaveApp, antiga.usuario, antiga.senha)   // migração: a conta de antes continua valendo
            else -> Conta(chaveApp)
        }
    }

    fun esquecerToken() { token = null }

    /** A chave antiga, cadastrada pelo dono antes de 14/11/2026. */
    fun conta(ctx: Context): Conta? = runCatching {
        val o = JSONObject(File(PlayerArmazem.pasta(ctx), CONTA).readText())
        val claro = JSONObject(PlayerArmazem.decifrar(o.getString("dados"), o.getString("iv")))
        Conta(claro.getString("chave"), claro.optString("usuario"), claro.optString("senha"))
    }.getOrNull()?.takeIf { it.chaveApi.isNotBlank() }

    fun temConta(ctx: Context) = File(PlayerArmazem.pasta(ctx), CONTA).exists()

    /** Grava (chave vazia = apaga). A chave da API só tem letras e números; usuário e senha são opcionais. */
    fun definirConta(ctx: Context, c: Conta?): Result<Unit> = runCatching {
        token = null
        val arq = File(PlayerArmazem.pasta(ctx), CONTA)
        if (c == null || c.chaveApi.isBlank()) { arq.delete(); return@runCatching }
        require(c.chaveApi.trim().matches(Regex("[A-Za-z0-9]{16,64}"))) { "a chave da API tem só letras e números (16 a 64)" }
        val claro = JSONObject().put("chave", c.chaveApi.trim()).put("usuario", c.usuario.trim()).put("senha", c.senha).toString()
        val (dados, iv) = PlayerArmazem.cifrar(claro)
        val tmp = File(PlayerArmazem.pasta(ctx), "$CONTA.tmp")
        tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
        check(tmp.renameTo(arq)) { "não consegui gravar" }
    }

    // ---------------------------------------------------------------- rede

    private class Resposta(val codigo: Int, val corpo: String)

    private fun pedir(url: String, conta: Conta, corpo: String? = null, comToken: Boolean = false, saltos: Int = 1): Resposta {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Api-Key", conta.chaveApi)
            c.setRequestProperty("User-Agent", LegendaExterna.AGENTE)
            c.setRequestProperty("Accept", "application/json")
            if (comToken) token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (corpo != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(corpo.toByteArray()) }
            }
            val codigo = c.responseCode
            // A API redireciona (301) quando não gosta da ordem/forma dos parâmetros, e o login pode mandar para o
            // servidor VIP: seguimos UMA vez, só para o próprio OpenSubtitles em https (antes virava "erro" na tela).
            if (codigo in 300..399 && saltos > 0) {
                LegendaExterna.redirecionamentoSeguro(c.getHeaderField("Location"))?.let { return pedir(it, conta, corpo, comToken, saltos - 1) }
            }
            val fluxo = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = fluxo?.use { String(ler(it, 1_000_000)) }.orEmpty()
            return Resposta(codigo, texto)
        } finally { c.disconnect() }
    }

    /** Entra com a conta (se houver usuário): com conta o limite diário de downloads é maior. Falha aqui não impede baixar. */
    private fun entrar(conta: Conta) {
        if (token != null || conta.usuario.isBlank() || conta.senha.isEmpty()) return
        runCatching {
            val r = pedir("${LegendaExterna.BASE}/login", conta, LegendaExterna.corpoLogin(conta.usuario, conta.senha))
            if (r.codigo in 200..299) token = LegendaExterna.lerToken(r.corpo)
        }
    }

    /** [idioma]: o da TV (ideia 25); a ideia 90 pede a inglesa para traduzir. */
    fun buscar(ctx: Context, b: LegendaExterna.Busca, idioma: String = IdiomaPlayer.idiomaDaTv()): Result<List<LegendaExterna.Candidata>> = runCatching {
        val conta = contaEmUso(ctx) ?: throw Falha(ctx.getString(R.string.legenda_sem_conta))
        // 18/11/2026: id TMDB primeiro, depois nome + ano, depois só o nome (LegendaExterna.buscas). Para na primeira
        // que acha; erro numa delas não esconde o resultado das outras, só aparece se nenhuma der certo.
        var erro: Falha? = null
        for (url in LegendaExterna.buscas(b, idioma)) {
            val r = runCatching { pedir(url, conta) }.getOrElse { erro = erro ?: Falha(ctx.getString(R.string.legenda_erro)); continue }
            if (r.codigo !in 200..299) { erro = erro ?: Falha(LegendaExterna.lerErro(r.corpo) ?: ctx.getString(R.string.legenda_erro)); continue }
            val lista = LegendaExterna.lerBusca(r.corpo, idioma)
            if (lista.isNotEmpty()) return@runCatching lista
        }
        erro?.let { throw it }
        emptyList()
    }

    /** Baixa a escolhida e guarda como a legenda deste vídeo. Devolve o arquivo .srt. */
    fun baixar(ctx: Context, chaveVideo: String, c: LegendaExterna.Candidata): Result<File> = runCatching {
        val conta = contaEmUso(ctx) ?: throw Falha(ctx.getString(R.string.legenda_sem_conta))
        entrar(conta)
        val r = pedir("${LegendaExterna.BASE}/download", conta, LegendaExterna.corpoDownload(c.arquivo), comToken = true)
        if (r.codigo == 401) token = null
        // Limite do dia (406 na doc; a mensagem fala em "allowed ... subtitles"): frase clara no idioma da TV.
        if (LegendaExterna.acabouLimite(r.codigo, r.corpo)) throw Falha(ctx.getString(R.string.sv_limite_dia))
        if (r.codigo !in 200..299) throw Falha(LegendaExterna.lerErro(r.corpo) ?: ctx.getString(R.string.legenda_erro))
        val link = LegendaExterna.lerDownload(r.corpo) ?: error("resposta sem link")
        val conexao = URL(link).openConnection() as HttpURLConnection
        val bytes = try {
            conexao.connectTimeout = 10_000; conexao.readTimeout = 20_000
            conexao.setRequestProperty("User-Agent", LegendaExterna.AGENTE)
            if (conexao.responseCode !in 200..299) error("erro ${conexao.responseCode}")
            conexao.inputStream.use { ler(it, LegendaExterna.MAX_SRT + 1) }
        } finally { conexao.disconnect() }
        val texto = LegendaExterna.decodificar(bytes)
        if (!LegendaExterna.pareceSrt(texto)) throw Falha(ctx.getString(R.string.legenda_invalida))
        gravar(ctx, chaveVideo, texto, original = true)
    }

    /** Lê até [max] bytes (o servidor podia mandar um arquivo enorme; legenda de verdade é pequena). */
    private fun ler(e: java.io.InputStream, max: Int): ByteArray {
        val saida = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
        while (saida.size() < max) { val n = e.read(buf, 0, minOf(buf.size, max - saida.size())); if (n < 0) break; saida.write(buf, 0, n) }
        return saida.toByteArray()
    }

    // ---------------------------------------------------------------- disco

    private fun pasta(ctx: Context) = File(PlayerArmazem.pasta(ctx), "legendas").apply { mkdirs() }
    private fun nome(chaveVideo: String) = chaveVideo.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    /** A original (como veio) e a que toca (com o atraso aplicado). O atraso é sempre calculado a partir da original. */
    private fun arquivo(ctx: Context, chaveVideo: String, original: Boolean) =
        File(pasta(ctx), nome(chaveVideo) + if (original) ".orig.srt" else ".srt")

    fun guardada(ctx: Context, chaveVideo: String): File? = arquivo(ctx, chaveVideo, false).takeIf { it.exists() && it.length() > 0 }

    private fun gravar(ctx: Context, chaveVideo: String, texto: String, original: Boolean): File {
        if (original) arquivo(ctx, chaveVideo, true).writeText(texto)
        val destino = arquivo(ctx, chaveVideo, false)
        val tmp = File(destino.path + ".tmp"); tmp.writeText(texto); tmp.renameTo(destino)
        limpar(ctx)
        return destino
    }

    /** Ideia 90: a tradução feita na TV vira a legenda deste vídeo (e a "original" do ajuste de atraso). */
    fun gravarTraduzida(ctx: Context, chaveVideo: String, texto: String): File = gravar(ctx, chaveVideo, texto, original = true)

    /** Refaz a legenda que toca com [ms] de atraso (positivo = a fala aparece depois) em relação à original. */
    fun ajustar(ctx: Context, chaveVideo: String, ms: Long): File? = runCatching {
        val orig = arquivo(ctx, chaveVideo, true).takeIf { it.exists() } ?: return null
        gravar(ctx, chaveVideo, LegendaExterna.deslocar(orig.readText(), ms), original = false)
    }.getOrNull()

    fun apagar(ctx: Context, chaveVideo: String) {
        arquivo(ctx, chaveVideo, true).delete(); arquivo(ctx, chaveVideo, false).delete()
    }

    /** Guarda só as mais recentes (cada uma ~100 KB; duas cópias por vídeo). */
    private fun limpar(ctx: Context) {
        val todos = pasta(ctx).listFiles()?.filter { it.name.endsWith(".srt") }?.sortedByDescending { it.lastModified() } ?: return
        todos.drop(MAX_GUARDADAS * 2).forEach { it.delete() }
    }

    // ---------------------------------------------------------------- ideia 89: legenda automática

    /** Um pedaço do vídeo com Range: (bytes, tamanho total). Nulo se o servidor não aceita Range (respondeu 200 ou erro). */
    private fun pedaco(url: String, de: Long, ate: Long): Pair<ByteArray, Long?>? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8_000; c.readTimeout = 12_000
            c.setRequestProperty("Range", "bytes=$de-$ate")
            c.setRequestProperty("User-Agent", LegendaExterna.AGENTE)
            if (c.responseCode != 206) null
            else c.inputStream.use { ler(it, LegendaExterna.BLOCO_HASH) } to LegendaExterna.tamanhoDoRange(c.getHeaderField("Content-Range"))
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    /**
     * O moviehash do vídeo, lendo só 64 KB do começo e 64 KB do fim (Range). Nulo se o servidor não aceita Range: aí a
     * busca vai só pelo nome. O endereço (que tem a conta do IPTV) nunca vai para o registro.
     */
    fun hashDoVideo(url: String): String? {
        if (!url.startsWith("http")) return null
        val (inicio, tamanho) = pedaco(url, 0, LegendaExterna.BLOCO_HASH - 1L) ?: return null
        val t = tamanho ?: return null
        if (t < LegendaExterna.BLOCO_HASH) return null
        val (fim, _) = pedaco(url, t - LegendaExterna.BLOCO_HASH, t - 1) ?: return null
        return LegendaExterna.hash(t, inicio, fim)
    }

    /**
     * Já tentou a automática para este vídeo nos últimos 7 dias? (Falhar de novo a cada abertura gastaria o limite.)
     * Chave "legauto2:" desde 18/11/2026: até ali TODA busca com nome de 2+ palavras falhava (o "%20"), e a marca antiga
     * travaria por 7 dias justamente os filmes que já tinham sido tentados.
     */
    fun tentouAuto(ctx: Context, chaveVideo: String): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences(Prefs.PLAYER, Context.MODE_PRIVATE).getLong("legauto2:$chaveVideo", 0) < 7L * 24 * 3_600_000

    /**
     * Ideia 89: busca (pelo hash do arquivo, se der, e pelo nome) e baixa a melhor legenda no idioma da TV. Gasta um
     * download do limite diário. Bloqueia (rede).
     */
    fun automatica(ctx: Context, chaveVideo: String, url: String?, b: LegendaExterna.Busca): Result<Pair<File, Boolean>> = runCatching {
        ctx.getSharedPreferences(Prefs.PLAYER, Context.MODE_PRIVATE).edit().putLong("legauto2:$chaveVideo", System.currentTimeMillis()).apply()
        val hash = url?.let { runCatching { hashDoVideo(it) }.getOrNull() }
        val lista = buscar(ctx, b.copy(hash = hash)).getOrThrow()
        val c = LegendaExterna.escolherAuto(lista) ?: throw Falha(ctx.getString(R.string.legenda_nenhuma))
        baixar(ctx, chaveVideo, c).getOrThrow() to c.bateHash
    }
}

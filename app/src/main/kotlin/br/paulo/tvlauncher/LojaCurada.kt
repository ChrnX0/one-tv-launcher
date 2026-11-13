package br.paulo.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loja curada (10/11/2026, ideia G; li no Projectivy e no FLauncher, que listam apps "de confiança" para TV). Uma lista
 * FIXA, escrita aqui no código, de apps abertos que publicam o APK no GitHub Releases. Nada de busca livre: o painel só
 * oferece estes, e o launcher baixa direto do Release oficial de cada projeto.
 *
 * Como confere que o APK é de quem diz ser:
 *  1. só baixa de `github.com/<repo>` da lista, por HTTPS (verificação de TLS normal, nunca desligada);
 *  2. lê o APK baixado e tira o SHA-256 do certificado de quem assinou;
 *  3. na PRIMEIRA instalação de cada app da lista, guarda pacote + assinatura ("confiar no primeiro uso"); daí em diante,
 *     um APK com outro pacote ou outra assinatura é recusado. O próprio Android também recusa atualizar com outra chave.
 *
 * Não verificado: as assinaturas de cada projeto não estão fixadas no código porque a nuvem onde isto foi escrito não
 * alcança o GitHub desses repositórios; os nomes dos arquivos dos Releases também não foram conferidos (o [escolherApk]
 * aceita os padrões mais comuns: armeabi-v7a, universal, ou um APK só). A TV é 32 bits: APK só arm64 é recusado.
 */
object LojaCurada {
    private const val TAG = "TvLauncher.Loja"
    private const val PREFS = "loja_curada"
    const val LIMITE = 300L shl 20   // 300 MB

    class Item(val id: String, val nome: String, val repo: String, val descricao: String)

    val CATALOGO = listOf(
        Item("smarttube", "SmartTube", "yuliskov/SmartTube", "YouTube para TV, sem anúncios"),
        Item("jellyfin", "Jellyfin", "jellyfin/jellyfin-androidtv", "Cliente do servidor de mídia Jellyfin"),
        Item("justplayer", "Just Player", "moneytoo/Player", "Player de vídeo leve (ExoPlayer)"),
        Item("newpipe", "NewPipe", "TeamNewPipe/NewPipe", "YouTube e outros, sem conta"),
    )

    // ---------------------------------------------------------------- puro

    /**
     * Qual arquivo do Release instalar numa TV 32 bits. Ordem: o que diz armeabi-v7a; o universal; se só existe UM .apk
     * e ele não diz arm64/x86, esse. Ignora .apk de debug. Null = nenhum serve.
     */
    fun escolherApk(nomes: List<String>): String? {
        val apks = nomes.filter { it.endsWith(".apk", ignoreCase = true) && !it.contains("debug", ignoreCase = true) }
        apks.firstOrNull { it.contains("armeabi-v7a", true) || it.contains("armv7", true) }?.let { return it }
        apks.firstOrNull { it.contains("universal", true) }?.let { return it }
        val semOutraArq = apks.filter { n -> listOf("arm64", "x86", "aarch64").none { n.contains(it, true) } }
        return semOutraArq.singleOrNull()
    }

    /** Confere o APK baixado contra o que foi guardado na 1ª instalação. Null = pode; senão, o motivo. Puro. */
    fun conferir(pacote: String, assinaturas: Set<String>, guardado: Pair<String, Set<String>>?): String? {
        if (assinaturas.isEmpty()) return "APK sem assinatura legível"
        if (guardado == null) return null   // primeira vez: confiar e guardar
        if (guardado.first != pacote) return "o pacote mudou (${guardado.first} -> $pacote)"
        if (guardado.second != assinaturas) return "a assinatura mudou: não é de quem publicou antes"
        return null
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- na TV

    private val ocupada = AtomicBoolean(false)

    /** Lista para o painel: nome, descrição e o que foi guardado (se já instalou). */
    fun json(ctx: Context): JSONArray = JSONArray().apply {
        CATALOGO.forEach { i ->
            val g = guardado(ctx, i.id)
            put(JSONObject().put("id", i.id).put("nome", i.nome).put("descricao", i.descricao).put("repo", i.repo)
                .put("pacote", g?.first ?: JSONObject.NULL).put("instalado", g?.first?.let { instalado(ctx, it) } ?: false))
        }
    }

    private fun instalado(ctx: Context, pacote: String) = try { ctx.packageManager.getPackageInfo(pacote, 0); true } catch (e: Exception) { false }

    private fun guardado(ctx: Context, id: String): Pair<String, Set<String>>? {
        val o = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null) ?: return null
        return try {
            val j = JSONObject(o); val a = j.getJSONArray("assinaturas")
            j.getString("pacote") to (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (e: Exception) { null }
    }

    private fun guardar(ctx: Context, id: String, pacote: String, ass: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(id, JSONObject().put("pacote", pacote).put("assinaturas", JSONArray(ass.toList())).toString()).apply()
    }

    /** Baixa e instala o item [id]. Bloqueia (minutos): fora da thread da tela. Devolve um texto para o painel. */
    fun instalar(ctx: Context, id: String): String {
        val item = CATALOGO.firstOrNull { it.id == id } ?: return "erro: app fora da lista"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "erro: precisa do Android 9"
        if (!ocupada.compareAndSet(false, true)) return "erro: já tem uma instalação em andamento"
        // O arquivo temporário é criado DENTRO do try: se falhar (disco cheio), o finally ainda solta o "ocupada".
        // Antes ficava fora, e uma falha aqui deixava a loja presa em "já tem uma instalação em andamento" até reiniciar.
        var criado: File? = null
        try {
            val tmp = File.createTempFile("loja", ".apk", ctx.cacheDir).also { criado = it }
            val rel = JSONObject(baixarTexto("https://api.github.com/repos/${item.repo}/releases/latest"))
            val assets = rel.optJSONArray("assets") ?: JSONArray()
            val nomes = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
            val nome = escolherApk(nomes) ?: return "erro: o Release ${rel.optString("tag_name")} não tem APK para esta TV"
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name") == nome }
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://github.com/${item.repo}/releases/download/")) return "erro: endereço do APK fora do projeto"
            if (asset.optLong("size") > LIMITE) return "erro: APK maior que 300 MB"
            baixar(url, tmp)
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(tmp.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return "erro: o arquivo baixado não é um APK válido"
            val ass = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
            conferir(info.packageName, ass, guardado(ctx, item.id))?.let { return "erro: $it" }
            val r = tmp.inputStream().use { PainelApps.instalar(ctx, it, tmp.length()) }
            if (r.motivo != null) return "erro: ${r.motivo}"
            guardar(ctx, item.id, info.packageName, ass)
            Log.i(TAG, "${item.nome} ${r.versao} instalado (${info.packageName})")
            return "instalado: ${item.nome} ${r.versao ?: ""}".trim()
        } catch (e: Exception) {
            Log.w(TAG, "instalar $id falhou: $e")
            return "erro: ${e.javaClass.simpleName}"
        } finally {
            criado?.delete(); ocupada.set(false)
        }
    }

    private fun conexao(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000
        setRequestProperty("User-Agent", "tv-launcher"); setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun baixarTexto(url: String): String {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Baixa com teto de [LIMITE]: um servidor que mande mais que isso é cortado. */
    private fun baixar(url: String, destino: File) {
        val c = conexao(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { e ->
                destino.outputStream().use { s ->
                    val b = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = e.read(b); if (n < 0) break
                        total += n; if (total > LIMITE) throw java.io.IOException("maior que o limite")
                        s.write(b, 0, n)
                    }
                }
            }
        } finally { c.disconnect() }
    }
}

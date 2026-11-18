package br.paulo.tvlauncher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Buscar podcasts e "Em alta no meu país" ("Ouvir" parte 2, 17/11/2026): PREPARADA E DESLIGADA.
 *
 * O catálogo é o Podcast Index (podcastindex.org). Ele exige chave + segredo assinando cada pedido, e o segredo não pode
 * ir dentro do APK. Então a TV pergunta ao servidor nosso (o "carteiro"), que guarda o segredo e repassa:
 *   GET {SERVIDOR}/podcasts/buscar?q=<termo>&lang=<pt>   →  {"feeds":[{url,title,author,image,explicit,language}]}
 *   GET {SERVIDOR}/podcasts/em_alta?lang=<pt>&max=30     →  o mesmo formato
 * (é o formato do /search/byterm e do /podcasts/trending do Podcast Index, só com esses campos).
 *
 * Liga quando: o carteiro publicar essas duas rotas E o Podcast Index confirmar por escrito o uso em app pago. Até lá
 * [SERVIDOR] fica vazio e a tela não mostra as linhas. Atribuição pedida pelos termos: "Busca: Podcast Index" na tela.
 * NÃO usar a API de busca da Apple: os termos dela só permitem promover a loja da Apple.
 * Lógica pura: PodcastBuscaTest.
 */
object PodcastBusca {
    /** Endereço do carteiro (https://...). Vazio = desligada. */
    const val SERVIDOR = ""
    const val MAX = 30

    data class Resultado(val url: String, val titulo: String, val autor: String, val capa: String, val explicito: Boolean)

    fun disponivel(servidor: String = SERVIDOR) = servidor.startsWith("https://")

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    fun urlBuscar(servidor: String, termo: String, idioma: String) =
        "${servidor.trimEnd('/')}/podcasts/buscar?q=${q(termo.trim().take(100))}&lang=${q(idioma)}&max=$MAX"
    fun urlEmAlta(servidor: String, idioma: String) = "${servidor.trimEnd('/')}/podcasts/em_alta?lang=${q(idioma)}&max=$MAX"

    /** Lê a resposta; ignora feed sem endereço http(s) ou sem nome; sem repetir. */
    fun ler(json: String?): List<Resultado> = runCatching {
        val a = JSONObject(json ?: return emptyList()).optJSONArray("feeds") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            val t = PodcastFeed.limpar(o.optString("title"), 200)
            if (!PodcastFeed.urlValida(url) || t.isEmpty()) null
            else Resultado(url, t, PodcastFeed.limpar(o.optString("author"), 200), o.optString("image").takeIf { PodcastFeed.urlValida(it) }.orEmpty(),
                o.optBoolean("explicit"))
        }.distinctBy { it.url }.take(MAX)
    }.getOrDefault(emptyList())

    /** Fora da thread da tela. Desligada ou sem resposta: lista vazia. */
    fun buscar(termo: String, idioma: String): List<Resultado> =
        if (!disponivel() || termo.isBlank()) emptyList() else ler(get(urlBuscar(SERVIDOR, termo, idioma)))

    fun emAlta(idioma: String): List<Resultado> = if (!disponivel()) emptyList() else ler(get(urlEmAlta(SERVIDOR, idioma)))

    private fun get(u: String): String? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 12_000
            c.setRequestProperty("User-Agent", "Sofaly/1 (TV podcast player)")
            if (c.responseCode != 200) null else c.inputStream.use { e ->
                val saida = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
                while (saida.size() < 1_000_000) { val n = e.read(buf); if (n < 0) break; saida.write(buf, 0, n) }
                saida.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }.getOrNull()
}

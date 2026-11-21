package br.paulo.tvlauncher.listen

import br.paulo.tvlauncher.Http
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Buscar podcasts e "Em alta no meu país" ("Ouvir" parte 2, 17/11/2026; ligada em 19/11/2026).
 *
 * O catálogo é o Podcast Index (podcastindex.org). Ele exige chave + segredo assinando cada pedido, e o segredo não pode
 * ir dentro do APK. Então a TV pergunta ao nosso servidor (api.sofaly.tv, server/api/src/podcasts.js), que guarda a
 * chave nos secrets do Worker (PODCASTINDEX_API_KEY, PODCASTINDEX_API_SECRET) e repassa:
 *   GET {SERVIDOR}/v1/podcasts/search?q=<termo>&lang=<pt>&max=30  →  {"feeds":[{url,title,author,image,explicit,language}]}
 *   GET {SERVIDOR}/v1/podcasts/trending?lang=<pt>&max=30          →  o mesmo formato
 * Servidor sem a chave responde 503: a lista vem vazia e a tela diz "nada encontrado".
 * Atribuição pedida pelos termos: "Busca: Podcast Index" na tela.
 * NÃO usar a API de busca da Apple: os termos dela só permitem promover a loja da Apple.
 * Lógica pura: PodcastBuscaTest.
 */
object PodcastBusca {
    /** Endereço do nosso servidor (https://...). Vazio = desligada. */
    const val SERVIDOR = "https://api.sofaly.tv"
    const val MAX = 30

    data class Resultado(val url: String, val titulo: String, val autor: String, val capa: String, val explicito: Boolean)

    fun disponivel(servidor: String = SERVIDOR) = servidor.startsWith("https://")

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    fun urlBuscar(servidor: String, termo: String, idioma: String) =
        "${servidor.trimEnd('/')}/v1/podcasts/search?q=${q(termo.trim().take(100))}&lang=${q(idioma)}&max=$MAX"
    fun urlEmAlta(servidor: String, idioma: String) = "${servidor.trimEnd('/')}/v1/podcasts/trending?lang=${q(idioma)}&max=$MAX"

    /** Lê a resposta; ignora feed sem endereço http(s) ou sem nome; sem repetir. */
    fun ler(json: String?): List<Resultado> = runCatching {
        val a = JSONObject(json ?: return emptyList()).optJSONArray("feeds") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            val t = PodcastFeed.limpar(o.optString("title"), 200)
            if (!PodcastFeed.urlValida(url) || t.isEmpty()) null
            else Resultado(url, t, PodcastFeed.limpar(o.optString("author"), 200), o.optString("image").takeIf { PodcastFeed.urlValida(it) }.orEmpty(),
                // Sem o campo (o "Em alta" do Podcast Index não informa): conta como explícito. Só pesa com o modo
                // protegido ligado, que antes deixava passar tudo do Em alta (debug 20/11/2026).
                if (o.has("explicit") && !o.isNull("explicit")) o.optBoolean("explicit") else true)
        }.distinctBy { it.url }.take(MAX)
    }.getOrDefault(emptyList())

    /** Fora da thread da tela. Desligada ou sem resposta: lista vazia. */
    fun buscar(termo: String, idioma: String): List<Resultado> =
        if (!disponivel() || termo.isBlank()) emptyList() else ler(get(urlBuscar(SERVIDOR, termo, idioma)))

    fun emAlta(idioma: String): List<Resultado> = if (!disponivel()) emptyList() else ler(get(urlEmAlta(SERVIDOR, idioma)))

    private fun get(u: String): String? = Http.getTextOrNull(u, readMs = 12_000, maxBytes = 1_000_000, cut = true)
}

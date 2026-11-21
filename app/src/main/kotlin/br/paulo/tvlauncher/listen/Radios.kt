package br.paulo.tvlauncher.listen

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.weather.Localizacao
import java.io.File
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rádios (ideia 87, 14/11/2026; dono: "acho que agrega... Mas só brasil?"). Catálogo MUNDIAL do radio-browser.info:
 * grátis, sem chave, mantido pela comunidade, com vários servidores espelho. A lista de espelhos vem de
 * all.api.radio-browser.info/json/servers; se um cair, tenta o próximo (e há uma lista de reserva no código).
 *
 * O que a TV mostra (RadioActivity, categoria "Rádios" dentro da TV ao vivo): favoritas (números do controle 1–9),
 * perto de você (pela localização da TV, [Localizacao]), as mais ouvidas do país da TV (região do Android), e buscar por
 * nome, gênero ou qualquer país. Pelo painel dá para colar o endereço de qualquer rádio ([colar]); ela vira favorita.
 *
 * Favoritas em files/player/radios.json (vai no backup completo) (arquivo: a tela da rádio e o painel podem estar em processos diferentes).
 * Lógica pura (endereços, leitura do JSON, playlists): RadiosTest.
 */
object Radios {
    private const val TAG = "TvLauncher.Radio"
    const val MAX_FAVORITAS = 40
    const val LIMITE = 60
    const val RAIO_PERTO_M = 100_000
    /** Reserva se a lista de espelhos não vier. Nomes, não IPs: o certificado é do nome. */
    val RESERVA = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")
    /** Gêneros mais comuns no catálogo (tags), na ordem em que aparecem na tela. */
    val GENEROS = listOf("sertanejo", "mpb", "gospel", "news", "talk", "pop", "rock", "jazz", "classical", "samba", "pagode",
        "forro", "funk", "electronic", "80s", "oldies", "country", "latin", "reggae", "sports", "kids")

    /** [logo]: o "favicon" do catálogo (endereço da imagem da rádio, às vezes vazio); a tela Rádio mostra no lugar da capa. */
    data class Radio(val id: String, val nome: String, val url: String, val pais: String = "", val tags: String = "",
                     val bitrate: Int = 0, val codec: String = "", val logo: String = "")

    // ---------------------------------------------------------------- puro: o que está tocando

    // Textos que algumas rádios mandam no lugar da música (visto na Sala 16/11/2026: "Now Playing info goes here").
    private val SEM_MUSICA = Regex("""(?i)^\s*(now playing( info)?( goes here)?|unknown( artist| title)?|untitled|no title|""" +
        """advert(isement)?|sem t[ií]tulo|desconhecido|[-–—·.:|\s]*)\s*$""")

    /** O texto do ICY serve para "tocando agora"? Não vale vazio, o próprio nome da rádio nem texto de espera. */
    fun musicaUtil(t: String?, nomeRadio: String): Boolean =
        !t.isNullOrBlank() && !t.trim().equals(nomeRadio.trim(), ignoreCase = true) && !SEM_MUSICA.matches(t)

    // ---------------------------------------------------------------- puro: endereços

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val COMUM = "hidebroken=true&order=clickcount&reverse=true"

    fun urlPerto(servidor: String, lat: Double, lon: Double, limite: Int = LIMITE) =
        "https://$servidor/json/stations/search?geo_lat=$lat&geo_long=$lon&geo_distance=$RAIO_PERTO_M&$COMUM&limit=$limite"
    fun urlPais(servidor: String, codigo: String, limite: Int = LIMITE) =
        "https://$servidor/json/stations/search?countrycode=${q(codigo.uppercase())}&$COMUM&limit=$limite"
    fun urlNome(servidor: String, nome: String, limite: Int = LIMITE) =
        "https://$servidor/json/stations/search?name=${q(nome.trim())}&$COMUM&limit=$limite"
    fun urlGenero(servidor: String, tag: String, pais: String? = null, limite: Int = LIMITE) =
        "https://$servidor/json/stations/search?tag=${q(tag)}&tagExact=true" + (pais?.let { "&countrycode=${q(it.uppercase())}" } ?: "") + "&$COMUM&limit=$limite"
    fun urlPaises(servidor: String) = "https://$servidor/json/countries?order=stationcount&reverse=true&hidebroken=true"
    fun urlClique(servidor: String, id: String) = "https://$servidor/json/url/${q(id)}"

    // ---------------------------------------------------------------- puro: leitura

    /** Nomes dos espelhos (sem repetir) de /json/servers; vazio se não der. */
    fun servidores(json: String?): List<String> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.endsWith(".radio-browser.info") } }.distinct()
    }.getOrDefault(emptyList())

    /**
     * As estações de /json/stations/search: só as que funcionam, com endereço http(s), sem repetir nome+país (a mesma
     * rádio cadastrada com 2 endereços aparecia 2 vezes seguidas, visto na Sala 17/11/2026; fica a 1ª, a mais ouvida).
     */
    fun estacoes(json: String?): List<Radio> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        val vistos = HashSet<String>()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) != 1) return@mapNotNull null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val nome = o.optString("name").replace(Regex("\\s+"), " ").trim()
            if (nome.isEmpty() || !enderecoValido(url)) return@mapNotNull null
            val n = nome.lowercase()
            if (!vistos.add("$n|$url") or !vistos.add("$n|pais:" + o.optString("countrycode").lowercase())) return@mapNotNull null
            Radio(o.optString("stationuuid").ifBlank { "url:" + url.hashCode() }, nome.take(80), url, o.optString("countrycode"),
                o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", "),
                o.optInt("bitrate"), o.optString("codec"), logoValido(o.optString("favicon")))
        }
    }.getOrDefault(emptyList())

    /** O logo só serve em https/http com cara de endereço (o catálogo tem "null", caminhos soltos e data:). */
    fun logoValido(u: String?): String = u?.trim()?.takeIf { enderecoValido(it) }.orEmpty()

    /** Países de /json/countries: (código, nome, quantas rádios), dos que têm mais rádios. */
    fun paises(json: String?): List<Triple<String, String, Int>> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optString("iso_3166_1").ifBlank { o.optString("countrycode") }
            if (c.length != 2) null else Triple(c.uppercase(), o.optString("name"), o.optInt("stationcount"))
        }.filter { it.third > 0 }
    }.getOrDefault(emptyList())

    fun enderecoValido(url: String): Boolean =
        (url.startsWith("http://", true) || url.startsWith("https://", true)) && url.length in 12..500 && url.none { it.isWhitespace() }

    /** Endereço colado que é uma playlist (.pls / .m3u): devolve a 1ª URL de dentro; senão null. */
    fun daPlaylist(texto: String): String? = texto.lineSequence().map { it.trim() }.mapNotNull { l ->
        when {
            l.startsWith("File", true) && '=' in l -> l.substringAfter('=').trim()
            l.startsWith("http", true) -> l
            else -> null
        }
    }.firstOrNull { enderecoValido(it) }

    /** Destino que o painel não busca: a própria TV (loopback, 0.0.0.0, IPs dela), link-local e multicast. */
    fun destinoProibido(ip: java.net.InetAddress, daTv: Set<java.net.InetAddress> = ipsDaTv()): Boolean =
        ip.isLoopbackAddress || ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isMulticastAddress || ip in daTv

    /**
     * Mais estrito, para endereços que vêm de FEED (conteúdo de terceiros, achado na busca): além de [destinoProibido],
     * a rede da casa (10/8, 172.16/12, 192.168/16, fc00::/7) e a do Tailscale (100.64/10). Um enclosure de episódio
     * apontando para o roteador fazia a TV mandar um pedido para dentro da casa. Rádio colada pela pessoa segue com
     * [destinoProibido] (um Icecast em casa é legítimo).
     */
    fun destinoDeFeedProibido(ip: java.net.InetAddress, daTv: Set<java.net.InetAddress> = ipsDaTv()): Boolean {
        if (destinoProibido(ip, daTv) || ip.isSiteLocalAddress) return true
        val b = ip.address
        return (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) ||
            (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64)
    }

    private fun ipsDaTv(): Set<java.net.InetAddress> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }.getOrDefault(emptySet())

    fun pareceplaylist(url: String): Boolean = url.substringBefore('?').lowercase().let { it.endsWith(".pls") || it.endsWith(".m3u") }

    // ---------------------------------------------------------------- puro: favoritas

    fun paraJson(l: List<Radio>): JSONArray = JSONArray().apply {
        l.forEach { r -> put(JSONObject().put("id", r.id).put("nome", r.nome).put("url", r.url).put("pais", r.pais).put("tags", r.tags)
            .put("bitrate", r.bitrate).put("codec", r.codec).put("logo", r.logo)) }
    }

    fun deJson(t: String?): List<Radio> = runCatching {
        val a = JSONArray(t ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!enderecoValido(url) || o.optString("nome").isBlank()) null
            else Radio(o.optString("id").ifBlank { "url:" + url.hashCode() }, o.optString("nome").take(80), url, o.optString("pais"),
                o.optString("tags"), o.optInt("bitrate"), o.optString("codec"), logoValido(o.optString("logo")))
        }.distinctBy { it.id }.take(MAX_FAVORITAS)
    }.getOrDefault(emptyList())

    /** Liga/desliga a favorita (pelo id). Nova entra no fim (o número do controle das outras não muda). */
    fun alternar(l: List<Radio>, r: Radio): List<Radio> =
        if (l.any { it.id == r.id }) l.filterNot { it.id == r.id } else (l + r).take(MAX_FAVORITAS)

    /** Rádio colada pelo painel: nome limpo, endereço http(s). null = inválida. */
    fun criarColada(nome: String, url: String): Radio? {
        val u = url.trim()
        val n = nome.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(80)
        if (n.isEmpty() || !enderecoValido(u)) return null
        return Radio("url:" + u.hashCode().toUInt().toString(16), n, u)
    }

    // ---------------------------------------------------------------- na TV (rede: fora da thread da tela)

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), "radios.json")   // em files/player: vai no backup completo

    fun favoritas(ctx: Context): List<Radio> = deJson(runCatching { arquivo(ctx).readText() }.getOrNull())

    @Synchronized
    fun editarFavoritas(ctx: Context, mudar: (List<Radio>) -> List<Radio>): List<Radio> {
        val nova = mudar(favoritas(ctx))
        runCatching { File(PlayerArmazem.pasta(ctx), "radios.json.tmp").apply { writeText(paraJson(nova).toString()) }.renameTo(arquivo(ctx)) }
        return nova
    }

    // A última rádio que tocou ("Continuar" da cruz, 18/11/2026). Arquivo em files/player: o ":player" grava, a HOME lê.
    private fun arqUltima(ctx: Context) = File(PlayerArmazem.pasta(ctx), "ultima_radio.json")

    fun anotarUltima(ctx: Context, r: Radio) = runCatching {
        val o = JSONObject().put("r", paraJson(listOf(r))).put("q", System.currentTimeMillis())
        File(PlayerArmazem.pasta(ctx), "ultima_radio.json.tmp").apply { writeText(o.toString()) }.renameTo(arqUltima(ctx))
    }

    // Recentes (redesenho do Ouvir, 18/11/2026): as últimas [MAX_RECENTES] rádios que tocaram, a mais nova primeiro.
    // Mesmo formato das favoritas, em files/player (vai no backup completo); quem grava é o ":player" (OuvirServico).
    const val MAX_RECENTES = 12
    private fun arqRecentes(ctx: Context) = File(PlayerArmazem.pasta(ctx), "radios_recentes.json")

    /** A lista de recentes com [r] na frente (sem repetir, pelo id), no máximo [MAX_RECENTES]. Puro. */
    fun comRecente(l: List<Radio>, r: Radio): List<Radio> = (listOf(r) + l.filterNot { it.id == r.id }).take(MAX_RECENTES)

    fun recentes(ctx: Context): List<Radio> = deJson(runCatching { arqRecentes(ctx).readText() }.getOrNull()).take(MAX_RECENTES)

    @Synchronized
    fun anotarRecente(ctx: Context, r: Radio) = runCatching {
        val nova = comRecente(recentes(ctx), r)
        File(PlayerArmazem.pasta(ctx), "radios_recentes.json.tmp").apply { writeText(paraJson(nova).toString()) }.renameTo(arqRecentes(ctx))
    }

    /** A última rádio e quando tocou (ms), ou null. */
    fun ultima(ctx: Context): Pair<Radio, Long>? = runCatching {
        val o = JSONObject(arqUltima(ctx).readText())
        deJson(o.getJSONArray("r").toString()).firstOrNull()?.let { it to o.optLong("q") }
    }.getOrNull()

    /** Colar pelo painel: se for playlist, abre e pega o 1º endereço. Devolve a lista nova ou o erro. */
    fun colar(ctx: Context, nome: String, url: String): Result<List<Radio>> = runCatching {
        var u = url.trim()
        if (pareceplaylist(u)) {
            // Alerta de SSRF no PR #102: o painel só busca a playlist em endereço fora da própria TV (loopback,
            // link-local, "qualquer" e multicast bloqueados). A rede de casa continua valendo (rádio do NAS).
            val host = runCatching { URL(u).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: error("endereço inválido")
            val ips = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrElse { error("endereço não existe") }
            if (ips.any { destinoProibido(it) }) error("endereço da própria TV não é aceito")
            u = daPlaylist(httpGet(u) ?: error("playlist não abriu")) ?: error("playlist sem endereço")
        }
        val r = criarColada(nome, u) ?: error("nome ou endereço inválidos")
        editarFavoritas(ctx) { l -> if (l.any { it.id == r.id }) l else (l + r).take(MAX_FAVORITAS) }
    }

    @Volatile private var espelhos: List<String>? = null

    private fun espelhos(): List<String> {
        espelhos?.let { return it }
        val lista = runCatching { servidores(httpGet("https://all.api.radio-browser.info/json/servers")) }.getOrDefault(emptyList())
        return (lista.shuffled() + RESERVA.shuffled()).distinct().also { espelhos = it }
    }

    /** Pede ao primeiro espelho que responder. [montar] recebe o nome do servidor e devolve o endereço. */
    fun pedir(montar: (String) -> String): String? {
        for (s in espelhos()) {
            val r = runCatching { httpGet(montar(s)) }.onFailure { Log.w(TAG, "$s: ${it.javaClass.simpleName}") }.getOrNull()
            if (r != null) return r
        }
        espelhos = null   // todos falharam: na próxima, busca a lista de novo
        return null
    }

    /** Conta um clique (etiqueta do radio-browser: ajuda a ordenar as mais ouvidas). Rádio colada não conta. */
    fun contarClique(r: Radio) {
        if (r.id.startsWith("url:")) return
        runCatching { pedir { urlClique(it, r.id) } }
    }

    // Sem seguir redirecionamento: a checagem de destino (colar) olhou só o primeiro host; um 302 para 127.0.0.1:8091
    // (MCP) ou :8080 (painel) passava por cima dela. No máximo 2 MB: um link que é o próprio áudio (não a lista
    // .m3u/.pls) nunca acaba, e ler tudo antes de cortar enchia a memória até o processo cair.
    private fun httpGet(endereco: String): String? =
        Http.getText(endereco, readMs = 10_000, maxBytes = 2_000_000, cut = true, followRedirects = false)
}

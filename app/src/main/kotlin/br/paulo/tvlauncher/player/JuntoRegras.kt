package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.SessaoJunto
import br.paulo.tvlauncher.network.EntreTvs
import org.json.JSONObject

/**
 * Regras puras de "entre TVs" (degrau 4, 17/11/2026): Sessão junto e "Continuar em outra TV". Sem Android, testadas em
 * JuntoRegrasTest. Quem fala com a rede é o [EntreTvs]; quem cuida da sessão é o [SessaoJunto].
 *
 * Como as TVs se acham: pela lista de aparelhos da conta Tailscale (o túnel já a publica, TunelTailscale.pares) e
 * conversam pelo endereço público do túnel de cada uma (https://onetv-<nome>.<rede>.ts.net, o mesmo da família
 * conectada). Só comandos passam (título, posição, tocando/pausado, reação): NENHUM vídeo trafega, cada casa toca da
 * própria fonte. Liberado pelo Paulo em 17/11 ("nao vale" a regra de P2P aqui).
 */
object JuntoRegras {
    /** Diferença de posição a partir da qual a TV de cá pula para acompanhar a de lá. */
    const val LIMIAR_MS = 1_500L
    /** Convite sem resposta vence. */
    const val CONVITE_MS = 10 * 60_000L
    /** Sessão parada (sem evento) termina sozinha. */
    const val SESSAO_PARADA_MS = 30 * 60_000L
    /** De quanto em quanto tempo quem está tocando manda a posição (corrige o desvio aos poucos). */
    const val PULSO_MS = 5_000L
    /** Convites recebidos por hora (contra quem descobrir o endereço e ficar mandando). */
    const val MAX_CONVITES_HORA = 10
    /** As 5 reações. */
    val REACOES = listOf("❤️", "😂", "😮", "👏", "😢")

    enum class Tipo { FILME, SERIE, CANAL, ARQUIVO }

    // ---------------------------------------------------------------- endereços

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** "onetv-quarto" + o sufixo do meu nome ("onetv-sala.tail1234.ts.net") = "onetv-quarto.tail1234.ts.net". */
    fun enderecoDoPar(meuNome: String?, parHost: String): String? {
        val sufixo = meuNome?.substringAfter('.', "")?.takeIf { it.endsWith(".ts.net") } ?: return null
        val h = parHost.lowercase()
        return if (HOST.matches(h)) "$h.$sufixo" else null
    }

    /** O nome curto ("onetv-quarto") de um endereço completo. */
    fun hostCurto(nome: String?): String? = nome?.substringBefore('.')?.lowercase()?.takeIf { HOST.matches(it) }

    data class Tv(val host: String, val online: Boolean)

    /**
     * As outras TVs da conta: aparelhos cujo nome começa com "onetv-" (TunelTailscale.nomeNo), menos esta. Lê o JSON
     * da linha PARES do túnel ([{"nome","so","online","ip"}]); qualquer coisa estranha é ignorada. Online primeiro.
     */
    fun outrasTvs(paresJson: String?, meuNome: String?): List<Tv> {
        if (paresJson.isNullOrBlank()) return emptyList()
        val eu = hostCurto(meuNome)
        val a = try { org.json.JSONArray(paresJson) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val h = o.optString("nome").lowercase()
            if (!h.startsWith("onetv-") || !HOST.matches(h) || h == eu) null else Tv(h, o.optBoolean("online"))
        }.distinctBy { it.host }.sortedWith(compareBy({ !it.online }, { it.host }))
    }

    /** Nome para mostrar: "onetv-quarto-dos-pais" -> "Quarto dos pais". */
    fun nomeBonito(host: String): String =
        host.removePrefix("onetv-").replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { host }

    // ---------------------------------------------------------------- o que está tocando

    /**
     * A chave que casa o mesmo título nas duas casas: "tmdb:filme:603", "tmdb:serie:1399:s1e2", "canal:globo" (nome do
     * canal normalizado: o id do canal muda de lista para lista) ou "arquivo:<nome do arquivo>". Null = não dá para casar.
     */
    fun chave(tipo: Tipo, tmdb: Int?, temporada: Int? = null, episodio: Int? = null, nome: String? = null): String? = when (tipo) {
        Tipo.FILME -> tmdb?.takeIf { it > 0 }?.let { "tmdb:filme:$it" }
        Tipo.SERIE -> tmdb?.takeIf { it > 0 }?.let { id ->
            if (temporada != null && episodio != null) "tmdb:serie:$id:s${temporada}e$episodio" else "tmdb:serie:$id"
        }
        Tipo.CANAL -> nome?.let(::normalizarCanal)?.takeIf { it.isNotEmpty() }?.let { "canal:$it" }
        Tipo.ARQUIVO -> nome?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }?.let { "arquivo:${it.take(200)}" }
    }

    /** "Globo HD", "GLOBO FHD ²", "[BR] Globo" -> "globo". Tira qualidade, país e símbolos. */
    /** A chave do que o player de filmes/séries toca: TMDB quando tem (episódio com temporada e número), senão o arquivo. */
    fun chaveVod(tmdb: Int?, temporada: Int?, episodio: Int?, arquivo: String?): String? = when {
        tmdb != null && tmdb > 0 && temporada != null && episodio != null && temporada > 0 && episodio > 0 -> chave(Tipo.SERIE, tmdb, temporada, episodio)
        tmdb != null && tmdb > 0 -> chave(Tipo.FILME, tmdb)
        else -> chave(Tipo.ARQUIVO, null, nome = arquivo?.substringBefore('?'))
    }

    fun normalizarCanal(nome: String): String {
        var n = java.text.Normalizer.normalize(nome.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        n = n.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
        n = n.replace(Regex("\\b(fhd|uhd|hd|sd|4k|h265|hevc|br|pt|1080p|720p)\\b"), " ")
        return n.replace(Regex("[^a-z0-9]+"), " ").trim().replace(' ', '-')
    }

    fun tipoDaChave(chave: String?): Tipo? = when {
        chave == null -> null
        chave.startsWith("tmdb:filme:") -> Tipo.FILME
        chave.startsWith("tmdb:serie:") -> Tipo.SERIE
        chave.startsWith("canal:") -> Tipo.CANAL
        chave.startsWith("arquivo:") -> Tipo.ARQUIVO
        else -> null
    }

    /** "tmdb:serie:1399:s1e3" -> (1, 3); outra coisa -> null. */
    fun episodio(chave: String?): Pair<Int, Int>? {
        // \d{1,4} + toIntOrNull: a chave vem da outra TV; "s99999999999" derrubava o player (NumberFormatException).
        val m = Regex("^tmdb:serie:\\d+:s(\\d{1,4})e(\\d{1,4})$").find(chave ?: return null) ?: return null
        val t = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return t to e
    }

    /** Mesma série (episódios podem ser outros): a sessão segue para o episódio seguinte (4c, 17/11/2026). */
    fun mesmaSerie(a: String?, b: String?): Boolean {
        if (tipoDaChave(a) != Tipo.SERIE || tipoDaChave(b) != Tipo.SERIE) return false
        fun serie(c: String) = c.split(':').getOrNull(2)
        return serie(a!!) != null && serie(a) == serie(b!!)
    }

    /** A chave vinda da outra TV vale para esta sessão? Mesmo título, qualquer canal (sessão de canal) ou a mesma série. */
    fun valeNaSessao(chaveSessao: String, recebida: String?): Boolean = when (tipoDaChave(chaveSessao)) {
        Tipo.CANAL -> tipoDaChave(recebida) == Tipo.CANAL
        Tipo.SERIE -> recebida == chaveSessao || mesmaSerie(chaveSessao, recebida)
        else -> recebida == chaveSessao
    }

    /** Ao vivo não tem posição para acertar: só "mesmo canal" e as reações. */
    fun acertaPosicao(chave: String?) = tipoDaChave(chave).let { it != null && it != Tipo.CANAL }

    // ---------------------------------------------------------------- sincronia

    /**
     * Onde a TV de cá deveria estar, dado o que a de lá disse: a posição de lá mais o tempo que a mensagem levou
     * ([atrasoMs], metade da ida e volta medida por quem mandou) e o tempo desde que chegou ([desdeMs]), se lá está
     * tocando. Null = não pular (diferença menor que [LIMIAR_MS], ou ao vivo).
     */
    fun alvo(localMs: Long, remotoMs: Long, remotoTocando: Boolean, atrasoMs: Long, desdeMs: Long = 0, chave: String? = "tmdb:filme:1"): Long? {
        if (!acertaPosicao(chave)) return null
        val esperado = remotoMs + if (remotoTocando) atrasoMs.coerceIn(0, 5_000) + desdeMs.coerceAtLeast(0) else 0
        return if (kotlin.math.abs(esperado - localMs) > LIMIAR_MS) esperado.coerceAtLeast(0) else null
    }

    /** Metade da ida e volta, com teto (uma resposta muito lenta não deve empurrar a outra TV para a frente). */
    fun atraso(idaEVoltaMs: Long): Long = (idaEVoltaMs / 2).coerceIn(0, 2_500)

    // ---------------------------------------------------------------- mensagens

    /** Um evento de uma TV para a outra. [seq] cresce: evento atrasado (seq menor) é ignorado. */
    data class Evento(val seq: Long, val tocando: Boolean, val posMs: Long, val atrasoMs: Long, val chave: String?,
                      val reacao: String? = null, val sair: Boolean = false)

    fun eventoParaJson(e: Evento): JSONObject = JSONObject().put("seq", e.seq).put("tocando", e.tocando).put("pos", e.posMs)
        .put("atraso", e.atrasoMs).put("chave", e.chave ?: "").put("reacao", e.reacao ?: "").put("sair", e.sair)

    /** Lê e confere (reação só das 5; chave só nos formatos conhecidos; números no intervalo). Null = descarta. */
    fun eventoDeJson(o: JSONObject?): Evento? {
        o ?: return null
        val seq = o.optLong("seq", -1).takeIf { it >= 0 } ?: return null
        val pos = o.optLong("pos", -1).takeIf { it in 0..(48L * 3_600_000) } ?: return null
        val chave = o.optString("chave").takeIf { it.isNotEmpty() }
        if (chave != null && (chave.length > 220 || tipoDaChave(chave) == null)) return null
        val reacao = o.optString("reacao").takeIf { it in REACOES }
        return Evento(seq, o.optBoolean("tocando"), pos, o.optLong("atraso", 0).coerceIn(0, 5_000), chave, reacao, o.optBoolean("sair"))
    }

    fun novoEvento(ultimoSeq: Long, recebido: Long) = recebido > ultimoSeq

    // ---------------------------------------------------------------- convite

    data class Convite(val token: String, val de: String, val titulo: String, val chave: String, val quando: Long)

    private val TOKEN = Regex("^[0-9a-f]{32}$")
    fun tokenValido(t: String?) = t != null && TOKEN.matches(t)

    fun conviteDeJson(o: JSONObject?, agora: Long): Convite? {
        o ?: return null
        val t = o.optString("token").takeIf(::tokenValido) ?: return null
        val de = hostCurto(o.optString("de")) ?: return null
        val chave = o.optString("chave").takeIf { it.length <= 220 && tipoDaChave(it) != null } ?: return null
        val titulo = o.optString("titulo").filter { it.isLetterOrDigit() || it in " .,:;!?'&()-·" }.replace(Regex("\\s+"), " ").trim().take(80)
        return Convite(t, de, titulo.ifEmpty { "?" }, chave, agora)
    }

    fun conviteParaJson(c: Convite): JSONObject = JSONObject().put("token", c.token).put("de", c.de).put("titulo", c.titulo).put("chave", c.chave)

    /** O convite só vale se vier de uma TV da MESMA conta (lista do túnel) e ainda não venceu. */
    fun aceitarConvite(c: Convite, tvsDaConta: List<Tv>, agora: Long): Boolean =
        tvsDaConta.any { it.host == c.de } && agora - c.quando < CONVITE_MS

    /** Janela de 1 h para o limite de convites: devolve (pode, lista nova de horários). */
    fun contarConvite(horarios: List<Long>, agora: Long): Pair<Boolean, List<Long>> {
        val recentes = horarios.filter { agora - it < 3_600_000L }
        return if (recentes.size >= MAX_CONVITES_HORA) false to recentes else true to recentes + agora
    }

    /** "/junto/convite", "/junto/<token>/evento|estado|sair", "/continuar". Null = não é nosso. */
    fun rota(caminho: String): Pair<String, String>? {
        if (caminho == "/junto/convite") return "" to "convite"
        if (caminho == "/continuar") return "" to "continuar"
        val p = caminho.removePrefix("/junto/").takeIf { it != caminho } ?: return null
        val partes = p.trimEnd('/').split('/')
        return if (partes.size == 2 && tokenValido(partes[0]) && partes[1] in setOf("evento", "estado", "sair")) partes[0] to partes[1] else null
    }

    // ---------------------------------------------------------------- continuar em outra TV

    /**
     * O que vai para a outra TV no "Enviar para outra TV": o título, a chave para casar e a posição; [url] só para
     * arquivo da rede da casa (smb://, http do DLNA, jellyfin://, plex://, webdav:// — a outra TV da mesma casa alcança)
     * e para canal (o nome; a outra TV procura na lista dela).
     */
    data class Continuar(val titulo: String, val chave: String, val posMs: Long, val url: String?, val de: String)

    private val ESQUEMAS_CASA = listOf("smb://", "http://", "jellyfin://", "plex://", "webdav://", "webdavs://")

    fun continuarParaJson(c: Continuar): JSONObject = JSONObject().put("titulo", c.titulo).put("chave", c.chave).put("pos", c.posMs)
        .put("url", c.url ?: "").put("de", c.de)

    fun continuarDeJson(o: JSONObject?): Continuar? {
        o ?: return null
        val de = hostCurto(o.optString("de")) ?: return null
        val chave = o.optString("chave").takeIf { it.length <= 220 && tipoDaChave(it) != null } ?: return null
        val pos = o.optLong("pos", -1).takeIf { it in 0..(48L * 3_600_000) } ?: return null
        val url = o.optString("url").takeIf { u -> u.length <= 2_000 && ESQUEMAS_CASA.any { u.startsWith(it) } && u.none { it.code < 32 } }
        val titulo = o.optString("titulo").filter { it.isLetterOrDigit() || it in " .,:;!?'&()-·" }.replace(Regex("\\s+"), " ").trim().take(80)
        return Continuar(titulo.ifEmpty { "?" }, chave, pos, url, de)
    }

    /** "1:02:03" / "12:34" para o cartão "Continuar de onde parou?". */
    fun tempo(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
}

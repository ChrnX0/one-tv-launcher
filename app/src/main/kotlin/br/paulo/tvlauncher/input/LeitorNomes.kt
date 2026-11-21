package br.paulo.tvlauncher.input

/**
 * Elevação 8.2 (17/11/2026): "a pasta vira catálogo". Lê o nome de um arquivo de vídeo (e o caminho das pastas acima
 * dele) e devolve o título, o ano e, se for série, a temporada e o episódio. Lógica pura, sem Android: testada com
 * mais de 200 nomes no LeitorNomesTest.
 *
 * Como lê (do jeito que as pessoas e os grupos de "release" escrevem):
 *   Duna.Parte.Dois.2024.1080p.WEB-DL.x264-GRUPO.mkv     -> "Duna Parte Dois", 2024
 *   Breaking.Bad.S02E05.720p.HDTV.mkv                    -> "Breaking Bad", T2 E5
 *   [Grupo] Frieren - 07 [1080p][ABCD1234].mkv           -> "Frieren", episódio 7
 *   Séries/Dark/Temporada 2/03.mkv                       -> "Dark", T2 E3 (nome e temporada vêm das pastas)
 *
 * Regra de ouro: na dúvida, o título fica mais longo (sobra palavra) em vez de cortar no lugar errado; a busca no TMDB
 * tolera palavra a mais pior que palavra a menos, e a pessoa pode corrigir na TV (CatalogoPasta.corrigir).
 */
object LeitorNomes {

    data class Lido(
        val nome: String,
        val ano: Int? = null,
        val temporada: Int? = null,
        val episodios: List<Int> = emptyList(),
        /** Amostra, trailer, making of: não entra no catálogo. */
        val extra: Boolean = false,
        /** Programa diário ("Jornal 2024.05.12"): a data no lugar do episódio, "AAAA-MM-DD". */
        val data: String? = null,
    ) {
        val serie: Boolean get() = temporada != null || episodios.isNotEmpty() || data != null
        val episodio: Int? get() = episodios.firstOrNull()
    }

    private val IC = setOf(RegexOption.IGNORE_CASE)

    // Rodada 15 da depuração: as regex de lerSo/temporadaDaPasta compiladas uma vez (antes, a cada arquivo e a cada
    // palavra: uma pasta DLNA com milhares de vídeos travava a tela).
    private val reL1 = Regex("""^(?:season|temporada|saison|staffel|stagione|seizoen|sezon|series)[ ._]?(\d{1,2})$""", IC)
    private val reL2 = Regex("""^[ST](\d{1,2})$""", IC)
    private val reL3 = Regex("""^(\d{1,2})(?:ª|a|º)? ?(?:temporada|season)$""", IC)
    private val reL4 = Regex("""^\s*(?:\[[^\]]*]|【[^】]*】|\{[^}]*})\s*""")
    private val reL5 = Regex("""(?i)\b([hx])\.(26[45])\b""")
    private val reL6 = Regex("""(?i)\b(ddp?|aac|dts|ac3|eac3|atmos|truehd|opus|flac)\.?([257])[.]([01])\b""")
    private val reL7 = Regex("""(?<![0-9])([257])\.([01])(?![0-9])""")
    private val reL8 = Regex("""(?<![A-Za-z0-9])((?:[A-Za-z]\.){2,})""")
    private val reL9 = Regex("""\b(Mr|Mrs|Ms|Dr|St|Sr|Sra|Jr|Vol|Pt)\.(?=\S)""", IC)
    private val reL10 = Regex("""[\[\](){}【】]""")
    private val reL11 = Regex("""\s+""")
    private val reL12 = Regex("""\d{1,4}""")
    private val reL13 = Regex("""^\d{3,4}[pi]$""")
    private val reL14 = Regex("""\s+-\s*$""")
    private val reL15 = Regex("""\s+(complete|completa|season|temporada|saison|staffel)$""", IC)

    /** O ano mais alto aceito como ano de lançamento: o ano corrente + 1 (antes fixo em 2027). */
    fun anoMaximoAgora(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

    private val reExtensao = Regex("""\.(mkv|mp4|m4v|avi|mov|wmv|mpe?g|ts|m2ts|mts|webm|flv|vob|iso|3gp|ogv|divx|rmvb|srt)$""", IC)

    /** Tags que sempre marcam o fim do título (resolução, origem, codec, áudio). */
    private val FORTES = setOf(
        "2160p", "1440p", "1080p", "1080i", "720p", "576p", "480p", "360p", "4k", "uhd", "8k",
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "bd25", "bd50", "web-dl", "webdl", "webrip", "web-rip",
        "hdtv", "pdtv", "sdtv", "hdrip", "dvdrip", "dvdscr", "dvd5", "dvd9", "hdcam", "telesync", "hdts", "tvrip",
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "av1", "vp9",
        "aac", "aac2", "aac51", "ac3", "dts", "dts-hd", "dd51", "ddp51", "dd2", "ddp2", "eac3", "atmos", "truehd", "flac", "mp3", "opus",
        "10bit", "8bit", "hdr", "hdr10", "hdr10+", "dovi", "sdr",
        "dublado", "legendado", "dual-audio", "dubbed", "subbed", "vostfr", "multisubs",
        "amzn", "dsnp", "hmax", "atvp", "pcok", "nflx", "hulu", "gplay", "globoplay",
    )

    /** Tags que também são palavras de título ("The Web", "Limited"): só cortam quando outra tag vem logo depois. */
    private val FRACAS = setOf(
        "web", "dvd", "cam", "ts", "dv", "proper", "repack", "rerip", "extended", "unrated", "uncut", "directors", "dc",
        "remastered", "imax", "internal", "limited", "multi", "dual", "nf", "complete", "completa", "criterion",
        "theatrical", "edition", "cut", "hybrid", "real", "readnfo", "festival", "dubbed", "sub", "subs", "ita", "eng",
        "pt-br", "ptbr", "pt", "br", "fr", "es", "jp", "kor", "max", "nacional",
    )

    /** Pastas que não dizem o nome do título. */
    private val PASTAS_GENERICAS = setOf(
        "filmes", "filme", "movies", "movie", "films", "series", "séries", "serie", "série", "tv", "tv shows", "shows",
        "videos", "vídeos", "video", "vídeo", "downloads", "download", "media", "mídia", "midia", "torrents", "completed",
        "anime", "animes", "desenhos", "documentarios", "documentários", "kids", "infantil", "novos", "new", "public",
        "multimedia", "multimídia", "share", "compartilhado", "hd", "4k", "uhd", "volume", "usb", "disco", "nas", "dlna",
        "video_ts", "bdmv", "stream", "extras", "featurettes",
    )

    private val NOMES_GENERICOS = setOf("movie", "filme", "video", "vídeo", "video_ts", "video ts", "vts_01_1", "main", "feature", "index")

    private val reExtra = Regex("""(^|[ ._\-\[(])(sample|trailer|teaser|featurette|behind[ ._]the[ ._]scenes|deleted[ ._]scenes|making[ ._]of|bloopers?|extras?|bonus|interview|entrevista|bastidores|opening|ending|ncop|nced)([ ._\-\])]|\d|$)""", IC)

    private val reSxE = Regex("""(?<![A-Za-z0-9])[Ss](\d{1,3}) ?[EeXx](\d{1,4})((?:(?:-[Ee]?|[Ee])\d{1,4})*)(?![0-9])""")
    private val reNxN = Regex("""(?<![A-Za-z0-9])(\d{1,2})[xX](\d{2,3})(?![0-9pP])""")
    private val reTxE = Regex("""(?<![A-Za-z0-9])[Tt](\d{1,2}) ?[Ee][Pp]? ?(\d{1,3})(?![0-9])""")
    private val reTemporadaEp = Regex(
        """(?<![A-Za-z])(?:season|temporada|saison|staffel|stagione|seizoen|sezon)\.? ?(\d{1,2})\b.{0,12}?\b(?:episode|episódio|episodio|épisode|episodi|folge|capitulo|capítulo|cap|ep|e)\.? ?(\d{1,4})(?![0-9])""", IC)
    private val reSoTemporada = Regex("""(?<![A-Za-z0-9])(?:(?:season|temporada|saison|staffel|stagione|seizoen|sezon)\.? ?(\d{1,2})|[Ss](\d{1,2})|[Tt](\d{1,2}))(?![0-9A-Za-z])""", IC)
    private val reSoEpisodio = Regex("""(?<![A-Za-z0-9])(?:episode|episódio|episodio|épisode|folge|capitulo|capítulo|cap|ep|e)\.? ?(\d{1,4})(?![0-9])""", IC)
    private val reAnime = Regex("""(?<=\S) - #?(\d{1,4})(?:v\d)?(?: |$|\()""")
    private val reData = Regex("""(?<![0-9])((?:19|20)\d{2})[ .\-](0[1-9]|1[0-2])[ .\-](0[1-9]|[12]\d|3[01])(?![0-9])""")
    private val reAnoParenteses = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val reAno = Regex("""(?<![0-9A-Za-z])((?:19|20)\d{2})(?![0-9A-Za-z])""")
    private val reNumeroInicio = Regex("""^(\d{1,3})(?: |$|-|\.)""")
    private val reSoNumeroNome = Regex("""^[\d \-]*$""")

    /**
     * Lê [arquivo] (só o nome, com ou sem extensão). [pastas] são as pastas acima, da mais de fora para a mais de dentro
     * ("Séries", "Dark", "Temporada 2"): dão o nome e a temporada quando o arquivo não diz.
     */
    fun ler(arquivo: String, pastas: List<String> = emptyList(), anoMaximo: Int = anoMaximoAgora()): Lido {
        val base = lerSo(arquivo, anoMaximo)
        val extra = base.extra || pastas.lastOrNull()?.let { reExtra.containsMatchIn(" $it ") && limparPasta(it) in setOf("extras", "featurettes", "trailers", "samples", "sample", "bonus") } == true
        // Temporada pela pasta ("Season 2", "Temporada 02", "S02"), quando o arquivo não tem.
        var temporada = base.temporada
        var nome = base.nome
        var ano = base.ano
        var episodios = base.episodios
        val pastaTemporada = pastas.lastOrNull()?.let { temporadaDaPasta(it) }
        if (temporada == null && pastaTemporada != null) {
            temporada = pastaTemporada
            if (episodios.isEmpty()) {
                // "03.mkv" ou "03 - Piloto.mkv" dentro de "Temporada 1": o número é o episódio.
                reNumeroInicio.find(arquivo.replace(reExtensao, "").trim())?.let { m ->
                    episodios = listOf(m.groupValues[1].toInt()); if (reSoNumeroNome.matches(nome) || nome == m.groupValues[1]) nome = ""
                    else nome = nome.removePrefix(m.groupValues[1]).trim(' ', '-', '.')
                }
            }
        }
        // Nome genérico ou vazio ("03", "Episódio 3", "movie", "VIDEO_TS"), ou episódio de série com nome tirado do
        // título do episódio: o nome do título vem da pasta mais próxima que não seja de temporada nem genérica.
        val dePasta = pastas.asReversed().firstOrNull { p -> temporadaDaPasta(p) == null && limparPasta(p) !in PASTAS_GENERICAS && lerSo(p, anoMaximo).nome.isNotBlank() }
        val nomeGenerico = nome.isBlank() || reSoNumeroNome.matches(nome) || nome.lowercase() in NOMES_GENERICOS
        val serieSemNomeProprio = pastaTemporada != null && base.temporada == null && dePasta != null
        if ((nomeGenerico || serieSemNomeProprio) && dePasta != null) {
            val p = lerSo(dePasta, anoMaximo)
            nome = p.nome
            if (ano == null || serieSemNomeProprio) ano = p.ano
            if (temporada == null && p.temporada != null) temporada = p.temporada
        }
        return Lido(nome, ano, temporada, episodios, extra, base.data)
    }

    /** A temporada que o nome da pasta diz ("Season 2", "Temporada 02", "S02", "T2"), ou null. */
    fun temporadaDaPasta(pasta: String): Int? {
        val p = pasta.trim()
        reL1.find(p)?.let { return it.groupValues[1].toInt() }
        reL2.find(p)?.let { return it.groupValues[1].toInt() }
        reL3.find(p)?.let { return it.groupValues[1].toInt() }
        if (p.equals("specials", true) || p.equals("especiais", true)) return 0
        return null
    }

    private fun limparPasta(p: String) = p.trim().lowercase().replace('_', ' ').replace('.', ' ').trim()

    /** Lê só o nome (sem olhar as pastas). */
    private fun lerSo(arquivoBruto: String, anoMaximo: Int): Lido {
        var s = arquivoBruto.trim().replace(reExtensao, "")
        val extra = reExtra.containsMatchIn(s)
        // 1. Grupo no começo ("[SubsPlease] ", "【Grupo】") sai; ano entre parênteses fica guardado.
        s = s.replace(reL4, "")
        val anoParenteses = reAnoParenteses.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull { it <= anoMaximo }
        // 2. Separadores viram espaço, sem quebrar o que tem ponto de propósito.
        s = s.replace(reL5, "$1$2")
            .replace(reL6, "$1$2$3")
            .replace(reL7, "$1$2")
            .replace(reL8) { m -> m.value.replace(".", "") + " " }
            .replace(reL9) { m -> m.groupValues[1] + " " }
            .replace('_', ' ').replace('.', ' ')
            .replace(reL10, " ")
            .replace(reL11, " ").trim()

        // 3. Marcadores de episódio / data: o título é o que vem antes do primeiro.
        var temporada: Int? = null
        var episodios: List<Int> = emptyList()
        var data: String? = null
        var corte = s.length
        fun marca(m: MatchResult?, f: (MatchResult) -> Unit) {
            if (m != null && m.range.first < corte) { corte = m.range.first; f(m) }
        }
        marca(reSxE.find(s)) { m ->
            temporada = m.groupValues[1].toInt()
            val inicio = m.groupValues[2].toInt()
            val resto = reL12.findAll(m.groupValues[3]).map { it.value.toInt() }.toList()
            // "S01E01-E03" / "S01E01-03": faixa; "S01E01E02": lista.
            episodios = if (resto.size == 1 && m.groupValues[3].contains('-') && resto[0] > inicio && resto[0] - inicio <= 30) (inicio..resto[0]).toList()
            else listOf(inicio) + resto.filter { it > inicio }
        }
        if (temporada == null) marca(reTemporadaEp.find(s)) { m -> temporada = m.groupValues[1].toInt(); episodios = listOf(m.groupValues[2].toInt()) }
        if (temporada == null) marca(reTxE.find(s)) { m -> temporada = m.groupValues[1].toInt(); episodios = listOf(m.groupValues[2].toInt()) }
        if (temporada == null) marca(reNxN.find(s)?.takeIf { (it.groupValues[1].toInt() in 1..40) }) { m -> temporada = m.groupValues[1].toInt(); episodios = listOf(m.groupValues[2].toInt()) }
        if (temporada == null) marca(reData.find(s)) { m -> data = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}" }
        if (temporada == null && data == null) marca(reAnime.find(s)) { m -> episodios = listOf(m.groupValues[1].toInt()) }
        if (temporada == null && data == null && episodios.isEmpty()) {
            // "E05" só grudado no número ("WALL-E 2008" não é episódio 2008).
            marca(reSoEpisodio.findAll(s).firstOrNull { m -> val letras = m.value.takeWhile { it.isLetter() }
                (!letras.equals("e", true) || m.value.getOrNull(1)?.isDigit() == true) && m.groupValues[1].length <= 3 }) { m ->
                episodios = listOf(m.groupValues[1].toInt())
            }
            // "Temporada 2", "Season 1 Complete", "S03" (pacote de temporada, sem episódio).
            marca(reSoTemporada.find(s)) { m ->
                temporada = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toInt()
            }
        }
        // 4. Tags: o título acaba na primeira forte (ou fraca seguida de outra tag).
        val palavras = s.substring(0, corte).split(' ').filter { it.isNotEmpty() }
        var fim = palavras.size
        for (i in 1 until palavras.size) {
            val w = palavras[i].lowercase().trim('-', ',')
            val prox = palavras.getOrNull(i + 1)?.lowercase()?.trim('-', ',')
            val forte = w in FORTES || reL13.matches(w) || w.startsWith("web-dl") || w.startsWith("x26") || w.startsWith("h26")
            val fraca = w in FRACAS && prox != null && (prox in FORTES || prox in FRACAS || reL13.matches(prox) || reAno.matches(prox))
            if (forte || fraca) { fim = i; break }
        }
        var titulo = palavras.take(fim)
        // Ano depois de uma tag ("Alien Directors Cut 1979 1080p"): vale como ano, fora do título.
        val anoDepois = palavras.drop(fim).firstOrNull { reAno.matches(it) && it.toInt() <= anoMaximo }?.toInt()
        // 5. Ano: o último ano solto do título que não seja o título inteiro ("1917", "2012") vira o ano.
        var ano = anoParenteses
        val idxAno = titulo.indices.lastOrNull { i -> i > 0 && reAno.matches(titulo[i]) && titulo[i].toInt() <= anoMaximo }
        if (idxAno != null) {
            if (ano == null) ano = titulo[idxAno].toInt()
            if (titulo[idxAno].toInt() == ano) titulo = titulo.take(idxAno)
        }
        if (ano == null) ano = anoDepois
        if (ano == null && data != null) ano = data!!.take(4).toInt()
        // Ano entre parênteses que ficou no meio ("Filme (2019) Dublado") já saiu com as tags; tira sobras de pontuação.
        var nome = titulo.joinToString(" ").trim(' ', '-', '.', ',', ':', ';', '+', '~', '–')
        nome = nome.replace(reL14, "").replace(reL11, " ").trim()
        // Sufixo de grupo grudado ("Filme-GRUPO") só some depois de tag; aqui só limpa "Complete"/"Season" sobrando.
        nome = nome.replace(reL15, "").trim()
        return Lido(nome, ano, temporada, episodios, extra, data)
    }
}

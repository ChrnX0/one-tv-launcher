package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Prefs
import java.text.Normalizer
import org.json.JSONObject

/**
 * Áudio original ou dublado em filmes e séries (11/11/2026, dono: "prefiro áudio original com legenda... o dublado é mal
 * feito muitas vezes"). Só lógica pura (sem Android), testada em IdiomaTest. Três partes:
 *
 *  1. A preferência: uma geral (Configurações) e uma por título, lembrada quando a pessoa troca o áudio no meio do filme ou
 *     da série (o próximo episódio segue a escolha). Fica em files/player/idioma.json, que os dois processos leem (as
 *     Configurações rodam no processo da HOME; o player, no ":player").
 *  2. A faixa: dentro de um arquivo com várias faixas, qual áudio e qual legenda ligar (escolherAudio / escolherLegenda).
 *  3. A versão do catálogo: muitas listas têm o mesmo filme duas vezes, "Dublado" e "Legendado" (pelo nome ou pela
 *     categoria). versao() lê a marca; outraVersao() acha o par.
 */
object Idioma {
    enum class Pref { ORIGINAL, DUBLADO }
    enum class Versao { DUBLADA, LEGENDADA, DESCONHECIDA }

    const val MAX_TITULOS = 500

    // ---------------------------------------------------------------- 1. preferência

    /**
     * Elevação 16.2 (17/11/2026): escolhidas uma vez e respeitadas em toda seleção de faixa. [audiodescricao]: preferir a
     * faixa de áudio que descreve a cena (e, desligada, nunca cair nela sem querer). [surdos]: legenda para surdos (com os
     * sons, "[porta bate]") sempre ligada no idioma da TV, mesmo com o áudio dublado; desligada, a legenda comum vem antes.
     */
    data class Prefs(val padrao: Pref = Pref.DUBLADO, val titulos: Map<String, Pref> = emptyMap(),
                     val audiodescricao: Boolean = false, val surdos: Boolean = false) {
        /** O que vale para este título: o que a pessoa escolheu nele, senão a geral. */
        fun para(chave: String): Pref = titulos[chave] ?: padrao
    }

    /** Lembra a escolha de um título (o mais recente fica; os mais antigos saem acima do limite). */
    fun lembrar(p: Prefs, chave: String, pref: Pref): Prefs {
        if (chave.isEmpty()) return p
        val m = LinkedHashMap(p.titulos); m.remove(chave); m[chave] = pref
        while (m.size > MAX_TITULOS) m.remove(m.keys.first())
        return p.copy(titulos = m)
    }

    fun paraJson(p: Prefs): String = JSONObject().apply {
        put("padrao", p.padrao.name)
        put("titulos", JSONObject().apply { p.titulos.forEach { (k, v) -> put(k, v.name) } })
        if (p.audiodescricao) put("audiodescricao", true)
        if (p.surdos) put("surdos", true)
    }.toString()

    fun deJson(texto: String?): Prefs {
        val o = runCatching { JSONObject(texto ?: return Prefs()) }.getOrNull() ?: return Prefs()
        fun pref(s: String?) = Pref.entries.firstOrNull { it.name == s }
        val t = o.optJSONObject("titulos")
        val titulos = LinkedHashMap<String, Pref>()
        t?.keys()?.forEach { k -> pref(t.optString(k))?.let { titulos[k] = it } }
        return Prefs(pref(o.optString("padrao")) ?: Pref.DUBLADO, titulos, o.optBoolean("audiodescricao"), o.optBoolean("surdos"))
    }

    // ---------------------------------------------------------------- 2. faixas do arquivo

    /** Uma faixa como o player a vê: idioma (ISO, pode faltar), se é a padrão do arquivo, se é legenda "forçada". */
    data class Faixa(val idioma: String?, val padrao: Boolean = false, val forcada: Boolean = false, val rotulo: String? = null,
                     val externa: Boolean = false,   // externa = a legenda baixada da internet (LegendaExterna)
                     /** 16.2: marcada pelo arquivo (papel "describes video" / "describes music and sound"). */
                     val marcaDescreve: Boolean = false, val marcaSurdos: Boolean = false) {
        /** Áudio com descrição da cena: pela marca do arquivo ou pelo nome ("AD", "Audio Description", "Audiodescrição"). */
        val descreve: Boolean get() = marcaDescreve || rotulo?.let { AUDIODESCRICAO.containsMatchIn(it) } == true
        /** Legenda para surdos: pela marca ou pelo nome ("SDH", "CC", "Hearing impaired", "para surdos"). */
        val paraSurdos: Boolean get() = marcaSurdos || rotulo?.let { SURDOS.containsMatchIn(it) } == true
    }

    private val AUDIODESCRICAO = Regex("""(?i)\b(ad|dvs)\b|audio ?descri|audiodescri|descriptive|descri[cç][aã]o de [aá]udio|h[oö]rfilm|audiodescripci|audiodescription""")
    private val SURDOS = Regex("""(?i)\b(sdh|cc|hoh)\b|hearing|surdo|sordo|sourds|malentendant|geh[oö]rlos|non udenti|closed caption""")

    // Ideia 25 (14/11/2026): "dublado" quer dizer o idioma da TV (Locale.getDefault().language), não mais só português.
    // As funções recebem o idioma (código de 2 letras: "pt", "en", "es"); o padrão "pt" mantém o comportamento antigo.

    /** O idioma da TV como as funções abaixo esperam: 2 letras minúsculas (códigos antigos do Java convertidos). */
    fun idiomaDaTv(lingua: String?): String = when (val l = lingua?.trim()?.lowercase().orEmpty()) {
        "" -> "pt"
        "iw" -> "he"; "in" -> "id"; "ji" -> "yi"
        else -> normalizar(l) ?: l
    }

    // Códigos de 3 letras (como vêm nos arquivos: "eng", "spa", "por") para os de 2. O Java dá os "terminológicos" (fra,
    // deu); os "bibliográficos" (fre, ger), também comuns em mkv, vão à parte.
    private val tres: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in java.util.Locale.getISOLanguages()) runCatching { m[java.util.Locale.forLanguageTag(c).isO3Language] = c }
        m += mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "cze" to "cs", "gre" to "el", "per" to "fa",
            "rum" to "ro", "slo" to "sk", "ice" to "is", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
            "geo" to "ka", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy", "pob" to "pt")
        m
    }

    /** "pt-BR" → "pt", "eng" → "en", "pob" → "pt". Null = sem idioma ("und", vazio). */
    fun normalizar(l: String?): String? {
        val x = l?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val base = x.substringBefore('-').substringBefore('_')
        return if (base.length == 3) tres[base] ?: base else base
    }

    /** Português: pela língua ("pt", "pt-BR", "por") ou, sem língua, pelo rótulo ("Dublado", "Português"). */
    fun ePortugues(f: Faixa): Boolean = eDoIdioma(f, "pt")

    /**
     * A faixa está no idioma da TV: pela língua ("en", "en-US", "eng") ou, só em português e sem língua, pelo rótulo
     * ("Dublado", "Português"): rótulos assim só existem nas listas brasileiras.
     */
    /**
     * Ideia 89: falta legenda? O áudio tocando NÃO é do idioma da TV e não há legenda completa (não forçada) nele. Sem áudio
     * identificado, não decide (false): baixar às cegas gastaria o limite diário em filme dublado.
     */
    // Áudio SEM idioma marcado ("und"/vazio, comum nas listas IPTV) não é "de fora": a Duna dublada da Sala (17/11/2026)
    // ganhava legenda baixada por cima da dublagem, que não batia com a fala. Sem idioma, só a marca da lista decide
    // ([versao]: "LEG"/"Legendado" = original com legenda a baixar); sem marca, não baixa (a busca manual continua no ▼).
    fun faltaLegenda(audio: Faixa?, textos: List<Faixa>, idioma: String = "pt", versao: Versao = Versao.DESCONHECIDA): Boolean {
        if (audio == null || eDoIdioma(audio, idioma)) return false
        if (!eOutraLingua(audio, idioma) && versao != Versao.LEGENDADA) return false
        return textos.none { eDoIdioma(it, idioma) && !it.forcada }
    }

    fun eDoIdioma(f: Faixa, idioma: String = "pt"): Boolean {
        val l = f.idioma?.lowercase()
        if (l != null && l != "und") {
            if (idioma == "pt") return l == "pt" || l.startsWith("pt-") || l == "por" || l == "pob"
            return normalizar(l) == idioma
        }
        if (idioma != "pt") return false
        val r = f.rotulo?.let { semAcento(it).lowercase() } ?: return false
        return "portug" in r || "dublad" in r || r.contains(Regex("""\b(pt|ptbr|pt-br)\b"""))
    }

    /** A faixa tem idioma conhecido e não é o da TV (é a original, num filme estrangeiro). */
    private fun eOutraLingua(f: Faixa, idioma: String): Boolean {
        val l = f.idioma?.lowercase()
        return l != null && l != "und" && !eDoIdioma(f, idioma)
    }

    /**
     * Qual áudio tocar. Dublado: o primeiro no idioma da TV. Original: a faixa padrão do arquivo se não for o idioma da TV;
     * senão a primeira de outra língua. Null = deixar o que o player escolheu (só tem uma faixa, ou nada bate).
     */
    fun escolherAudio(faixas: List<Faixa>, pref: Pref, idioma: String = "pt", segundo: String? = null,
                      audiodescricao: Boolean = false): Int? {
        if (faixas.size < 2) return null
        val base = escolherAudioBase(faixas, pref, idioma, segundo)
        // 16.2: a faixa irmã (mesmo idioma) com ou sem descrição, conforme a preferência. Sem irmã, fica a base.
        val ref = base?.let { faixas[it] } ?: faixas.firstOrNull { it.padrao } ?: faixas.first()
        val irma = faixas.indexOfFirst { it.descreve == audiodescricao && mesmoIdioma(it, ref) }
        return if (irma >= 0 && ref.descreve != audiodescricao) irma else base
    }

    private fun mesmoIdioma(a: Faixa, b: Faixa): Boolean {
        val x = normalizar(a.idioma ?: return b.idioma == null) ?: a.idioma
        val y = normalizar(b.idioma ?: return false) ?: b.idioma
        return x == y
    }

    private fun escolherAudioBase(faixas: List<Faixa>, pref: Pref, idioma: String, segundo: String?): Int? {
        return when (pref) {
            // Segundo idioma (16/11/2026, [Conforto]): sem áudio no idioma da TV, o do segundo idioma.
            Pref.DUBLADO -> faixas.indexOfFirst { eDoIdioma(it, idioma) }.takeIf { it >= 0 }
                ?: segundo?.let { s -> faixas.indexOfFirst { eDoIdioma(it, s) }.takeIf { it >= 0 } }
            Pref.ORIGINAL -> faixas.indexOfFirst { it.padrao && eOutraLingua(it, idioma) }.takeIf { it >= 0 }
                ?: faixas.indexOfFirst { eOutraLingua(it, idioma) }.takeIf { it >= 0 }
        }
    }

    /**
     * Qual legenda ligar. Legenda baixada: sempre ela. Áudio no idioma da TV ([audioPortugues], nome antigo): nenhuma (só a
     * "forçada", que traduz placa e fala em outra língua, se houver). Áudio em outra língua: a legenda completa no idioma
     * da TV; sem ela, a forçada nesse idioma. Null = sem legenda.
     */
    fun escolherLegenda(faixas: List<Faixa>, audioPortugues: Boolean, idioma: String = "pt", segundo: String? = null,
                        audioNoSegundo: Boolean = false, surdos: Boolean = false): Int? {
        // Quem baixou uma legenda quer vê-la, mesmo com o áudio dublado (ex.: o arquivo só tem a faixa dublada).
        faixas.indexOfFirst { it.externa }.takeIf { it >= 0 }?.let { return it }
        val daTv = faixas.withIndex().filter { eDoIdioma(it.value, idioma) }
        // 16.2: quem pediu legenda para surdos quer legenda sempre, mesmo dublado: a para surdos, senão a completa.
        if (surdos) (daTv.firstOrNull { it.value.paraSurdos && !it.value.forcada } ?: daTv.firstOrNull { !it.value.forcada })?.let { return it.index }
        if (audioPortugues) return daTv.firstOrNull { it.value.forcada }?.index
        // Sem a preferência, a completa comum vem antes da para surdos (antes, a primeira da lista ganhava).
        (daTv.firstOrNull { !it.value.forcada && !it.value.paraSurdos } ?: daTv.firstOrNull { !it.value.forcada }
            ?: daTv.firstOrNull())?.let { return it.index }
        // Segundo idioma (16/11/2026): sem legenda no idioma da TV, a completa no segundo idioma. Não liga se o áudio já
        // está nele (seria legenda da mesma fala).
        if (segundo == null || audioNoSegundo) return null
        val doSegundo = faixas.withIndex().filter { eDoIdioma(it.value, segundo) && !it.value.forcada }
        return (doSegundo.firstOrNull { it.value.paraSurdos == surdos } ?: doSegundo.firstOrNull())?.index
    }

    /** A escolha manual de áudio (menu ▼) vira a preferência do título: idioma da TV = dublado, outra língua = original. */
    fun prefDaEscolha(f: Faixa, idioma: String = "pt"): Pref? = when {
        eDoIdioma(f, idioma) -> Pref.DUBLADO
        eOutraLingua(f, idioma) -> Pref.ORIGINAL
        else -> null
    }

    /** Idioma que o player marca na legenda baixada (o Media3 mostra o nome no ▼): "pt-BR" para português, como antes. */
    fun tagLegendaBaixada(idioma: String = "pt"): String = if (idioma == "pt") "pt-BR" else idioma

    // ---------------------------------------------------------------- 3. versão no catálogo

    // Marcas comuns nas listas: "[L]", "(LEG)", "- Legendado", "[D]", "(Dub)", "Dublado". DUAL (duas faixas) não é marca:
    // o arquivo tem as duas, e quem resolve é a escolha de faixa.
    private val reLeg = Regex("""[\[(]\s*(l|leg|legendado)\s*[\])]|(^|[\s\-|:])(legendado|leg)\s*$""", RegexOption.IGNORE_CASE)
    private val reDub = Regex("""[\[(]\s*(d|dub|dublado)\s*[\])]|(^|[\s\-|:])(dublado|dub)\s*$""", RegexOption.IGNORE_CASE)
    private val reMarcas = Regex("""[\[(]\s*(l|leg|legendado|d|dub|dublado|dual|dual audio|nacional)\s*[\])]|[\s\-|:]+(legendado|leg|dublado|dub)\s*$""",
        RegexOption.IGNORE_CASE)

    /** Dublado ou legendado, pelo nome; sem marca no nome, pela categoria ("Filmes Legendados", "Séries | Dubladas"). */
    fun versao(nome: String, grupo: String): Versao = versaoDoNome(nome) ?: versaoDoGrupo(grupo)

    // As duas metades do [versao], separadas (20/11/2026) para a conta do catálogo inteiro olhar cada categoria uma vez só:
    // são poucas centenas de categorias para 21 mil filmes.
    private fun versaoDoNome(nome: String): Versao? {
        val n = nome.trim()
        // Pré-checagem: as marcas só existem entre colchetes/parênteses ou como última palavra (ver [podeTerMarca]).
        val entre = n.indexOf('[') >= 0 || n.indexOf('(') >= 0
        if ((entre || terminaCom(n, "leg", "legendado")) && reLeg.containsMatchIn(n)) return Versao.LEGENDADA
        if ((entre || terminaCom(n, "dub", "dublado")) && reDub.containsMatchIn(n)) return Versao.DUBLADA
        return null
    }

    private fun versaoDoGrupo(grupo: String): Versao {
        val g = semAcento(grupo).lowercase()
        return when {
            "legendad" in g -> Versao.LEGENDADA
            "dublad" in g -> Versao.DUBLADA
            else -> Versao.DESCONHECIDA
        }
    }

    /**
     * Chave e versão de cada item calculadas UMA vez por conta (20/11/2026). Medido na Sala: juntar as versões de 21 mil
     * filmes levou 75 s de CPU, porque cada título passava até 6 vezes pela [chaveTitulo] e pela [versao] (cada uma com
     * várias expressões regulares). Vale só durante uma conta: guarda pela identidade do item, não pelo conteúdo.
     */
    private class Contas<T>(private val nome: (T) -> String, private val grupo: (T) -> String, tamanho: Int = 16) {
        private val chaves = java.util.IdentityHashMap<T, String>(tamanho)
        private val versoes = java.util.IdentityHashMap<T, Versao>(tamanho)
        private val porGrupo = HashMap<String, Versao>()

        fun chave(x: T): String = chaves[x] ?: chaveTitulo(nome(x)).also { chaves[x] = it }

        fun versao(x: T): Versao = versoes[x]
            ?: (versaoDoNome(nome(x)) ?: grupo(x).let { g -> porGrupo[g] ?: versaoDoGrupo(g).also { porGrupo[g] = it } })
                .also { versoes[x] = it }
    }

    /** O nome como veio, sem a marca de versão: "O Sonho da Liberdade Leg" -> "O Sonho da Liberdade" (o selo LEG vai no pôster). */
    fun semMarca(nome: String): String = tirarMarcas(nome).let { if (espacosSeguidos(it)) it.replace(reEspacos, " ") else it }
        .trim().ifEmpty { nome }
    private val reEspacos = Regex("""\s{2,}""")

    // ---- pré-checagens (20/11/2026). No Android cada replace/find cria um "matcher" nativo (ICU) que a coleta de lixo
    // solta depois; com 21 mil filmes por conta, isso dava dezenas de segundos de CPU no :player da Sala (medido: 34 s
    // só na thread da conta, mais 15 s de coleta). Cada checagem abaixo é uma condição NECESSÁRIA para a expressão casar:
    // se ela diz "não", a expressão não mudaria nada e não precisa rodar. O resultado é sempre o mesmo de antes
    // (IdiomaRapidoTest compara com as expressões em milhares de nomes).

    /** Tudo o que o \s das expressões pode ser (no Android, o \s é o White_Space do Unicode, que inclui o U+0085). */
    internal fun espaco(c: Char) = c.isWhitespace() || c == '\u0085'

    internal fun espacosSeguidos(s: String): Boolean {
        for (i in 1 until s.length) if (espaco(s[i]) && espaco(s[i - 1])) return true
        return false
    }

    /** [s], sem os espaços do fim, termina com um dos [sufixos] (maiúscula ou minúscula)? */
    private fun terminaCom(s: String, vararg sufixos: String): Boolean {
        var fim = s.length
        while (fim > 0 && espaco(s[fim - 1])) fim--
        return sufixos.any { suf -> fim >= suf.length && s.regionMatches(fim - suf.length, suf, 0, suf.length, ignoreCase = true) }
    }

    /** [reMarcas] só casa com "[...]"/"(...)" ou com leg/legendado/dub/dublado como última palavra. */
    private fun podeTerMarca(s: String) =
        s.indexOf('[') >= 0 || s.indexOf('(') >= 0 || terminaCom(s, "leg", "legendado", "dub", "dublado")

    /** O mesmo que replace(reMarcas, " "), sem rodar a expressão quando ela não tem como casar. */
    private fun tirarMarcas(s: String) = if (podeTerMarca(s)) s.replace(reMarcas, " ") else s

    /** O mesmo que replace(Regex("[^a-z0-9]+"), " "): cada trecho de outros caracteres vira UM espaço. */
    private fun soLetrasENumeros(s: String): String {
        if (s.all { it in 'a'..'z' || it in '0'..'9' }) return s
        val b = StringBuilder(s.length)
        var noVao = false
        for (c in s) {
            if (c in 'a'..'z' || c in '0'..'9') { b.append(c); noVao = false }
            else if (!noVao) { b.append(' '); noVao = true }
        }
        return b.toString()
    }

    /**
     * Título curto para a linha de cima de Filmes e Séries (19/11/2026): "Lei e Ordem: Unidade de Vítimas Especiais - Law &
     * Order" ocupava a linha toda e escondia nota, ano e gênero. Corta na última palavra que cabe em [max] e põe "…";
     * o título inteiro continua na ficha.
     */
    fun tituloCurto(nome: String, max: Int = 34): String {
        if (nome.length <= max) return nome
        val corte = nome.lastIndexOf(' ', max - 1).takeIf { it >= max / 2 } ?: (max - 1)
        return nome.substring(0, corte).trimEnd(' ', '-', ':', '·', ',', '–') + "…"
    }

    /** O título sem marca, ano, acento nem pontuação: "Duna: Parte 2 (2024) [L]" e "Duna - Parte 2 (2024)" dão o mesmo. */
    fun chaveTitulo(nome: String): String = chavesProntas[nome] ?: calcularChave(nome).also {
        if (chavesProntas.size >= MAX_CHAVES_PRONTAS) chavesProntas.clear()
        chavesProntas[nome] = it
    }

    // A conta de sempre (marcas, ano, prefixo de país, acento, minúsculas, só letras e números), com as pré-checagens.
    private fun calcularChave(nome: String): String =
        soLetrasENumeros(semAcento(tirarMarcas(Vod.nomeLimpo(tirarMarcas(nome)))).lowercase()).trim()

    /**
     * Chaves já calculadas, pelo nome (20/11/2026): o mesmo título passa pela [chaveTitulo] no índice das versões, na
     * grade, no cruzamento com as listas de fora e de novo a cada vez que Filmes abre. Uma por processo; esvazia se
     * passar de [MAX_CHAVES_PRONTAS] (~100 bytes por título: 21 mil filmes + 7 mil séries ~ 3 MB).
     */
    private val chavesProntas = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val MAX_CHAVES_PRONTAS = 60_000

    /**
     * Índice do catálogo por título: só os títulos em que pelo menos um item tem marca de versão (dois sem marca nunca
     * são par). Montado uma vez, fora da thread da tela: com 30 mil filmes, procurar o par na hora seria lento demais
     * para a ficha abrir sem piscar.
     */
    fun <T> indice(lista: List<T>, nome: (T) -> String, grupo: (T) -> String): Map<String, List<T>> {
        val m = HashMap<String, MutableList<T>>()
        val comMarca = HashSet<String>()
        val porGrupo = HashMap<String, Versao>()   // a categoria só é lida uma vez (ver [Contas])
        for (x in lista) {
            val n = nome(x)
            val k = chaveTitulo(n)
            if (k.isEmpty()) continue
            m.getOrPut(k) { ArrayList(2) } += x
            val v = versaoDoNome(n) ?: grupo(x).let { g -> porGrupo[g] ?: versaoDoGrupo(g).also { porGrupo[g] = it } }
            if (v != Versao.DESCONHECIDA) comMarca += k
        }
        m.keys.retainAll(comMarca)
        return m
    }

    /**
     * O mesmo título na outra versão: mesma chave, ano igual (ou um dos dois sem ano) e versão oposta. Visto na Sala
     * (18/11/2026): as listas marcam só um lado ("Custe o que Custar" e "Custe o que Custar Leg"), então um item sem
     * marca vira par de um com marca e fica com a versão oposta ([versaoComPar]). Par com marca oposta ganha do sem
     * marca; dois sem marca nunca são par.
     */
    fun <T> outraVersao(alvo: T, indice: Map<String, List<T>>, nome: (T) -> String, grupo: (T) -> String, ano: (T) -> Int?): T? =
        outraVersao(alvo, indice, Contas(nome, grupo), ano)

    private fun <T> outraVersao(alvo: T, indice: Map<String, List<T>>, c: Contas<T>, ano: (T) -> Int?): T? {
        // Título sem par no índice (a grande maioria): sai antes de ler a versão.
        val candidatos = indice[c.chave(alvo)] ?: return null
        val va = c.versao(alvo)
        val anoA = ano(alvo)
        val mesmoTitulo = candidatos.filter { o -> o !== alvo && (anoA == null || ano(o) == null || ano(o) == anoA) }
        val oposta = mesmoTitulo.firstOrNull { o -> c.versao(o).let { it != Versao.DESCONHECIDA && it != va } &&
            (va != Versao.DESCONHECIDA || ano(o) == anoA) }   // o par é recíproco: a mesma regra do ano vale dos dois lados
        if (oposta != null || va == Versao.DESCONHECIDA) return oposta
        // Sem marca de um lado, o ano é a única prova de ser o MESMO filme: tem de ser igual nos dois (ou faltar nos
        // dois). Um lado com ano e o outro sem juntava "O Rei Leão" e "O Rei Leão (2019) [L]" (debug 20/11/2026).
        return mesmoTitulo.firstOrNull { o -> c.versao(o) == Versao.DESCONHECIDA && ano(o) == anoA }
    }

    /**
     * A grade com um item por filme: "Else" e "Else LEG" viram um só (pedido do Paulo, 19/11/2026); a ficha já abre na
     * versão preferida e tem o botão da outra. Conservador: só some um item quando os dois apontam um para o outro
     * ([outraVersao] dos dois lados) e ambos estão na [lista]; título com 3 ou mais versões fica como está. Fica o que
     * tem algo guardado ([comecado]: posição, visto, Interesses); com algo nos dois, nenhum some; sem nada, o primeiro da lista.
     */
    fun <T> semPares(lista: List<T>, indice: Map<String, List<T>>, nome: (T) -> String, grupo: (T) -> String,
                     ano: (T) -> Int?, comecado: (T) -> Boolean, motivo: ((String) -> Unit)? = null): List<T> {
        if (indice.isEmpty()) { motivo?.invoke("indice vazio"); return lista }
        val posicao = java.util.IdentityHashMap<T, Int>(lista.size)
        lista.forEachIndexed { i, x -> posicao[x] = i }
        // O índice vem do catálogo inteiro (categorias ocultas e cópias em outros grupos incluídas): aqui só vale o que
        // está na grade. Visto na Sala (19/11/2026): a cópia oculta virava "3 versões" e nenhum par juntava.
        val naGrade = HashMap<String, List<T>>()
        for ((k, v) in indice) { val f = v.filter { posicao.containsKey(it) }; if (f.size >= 2) naGrade[k] = f }
        motivo?.invoke("indice=${indice.size} lista=${lista.size} titulos com 2+ na grade=${naGrade.size}")
        if (naGrade.isEmpty()) return lista
        var avisos = 0
        fun pula(x: T, por: String) { if (motivo != null && avisos++ < 8) motivo("nao juntou '${nome(x)}' (${grupo(x)}, ${ano(x)}): $por") }
        val sai = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
        val c = Contas(nome, grupo, lista.size)
        for (x in lista) {
            if (x in sai) continue
            val k0 = c.chave(x)
            val o = outraVersao(x, naGrade, c, ano) ?: run { if (naGrade.containsKey(k0)) pula(x, "sem par"); null } ?: continue
            if (o === x || !posicao.containsKey(o) || o in sai) continue
            if (outraVersao(o, naGrade, c, ano) !== x) { pula(x, "par nao reciproco: '${nome(o)}'"); continue }
            // 3 ou mais itens na grade com o mesmo título e ano: não dá para ter certeza de qual é par de qual.
            val k = k0; val anoX = ano(x) ?: ano(o)
            if ((naGrade[k] ?: emptyList()).count { anoX == null || ano(it) == null || ano(it) == anoX } > 2) { pula(x, "3+ versoes"); continue }
            val cx = comecado(x); val co = comecado(o)
            if (cx && co) { pula(x, "os dois com algo guardado"); continue }   // os dois têm algo guardado: juntar esconderia um deles
            val ficaX = if (cx != co) cx else posicao.getValue(x) < posicao.getValue(o)
            sai += if (ficaX) o else x
        }
        motivo?.invoke("juntou ${sai.size}")
        return if (sai.isEmpty()) lista else lista.filter { it !in sai }
    }

    /** A versão de um item sabendo o par dele: com marca, a marca; sem marca, o oposto da do par. */
    fun versaoComPar(nome: String, grupo: String, nomePar: String, grupoPar: String): Versao {
        val v = versao(nome, grupo)
        if (v != Versao.DESCONHECIDA) return v
        return when (versao(nomePar, grupoPar)) {
            Versao.LEGENDADA -> Versao.DUBLADA
            Versao.DUBLADA -> Versao.LEGENDADA
            Versao.DESCONHECIDA -> Versao.DESCONHECIDA
        }
    }

    /** A versão que a preferência pede: original = legendada; dublado = dublada. */
    fun versaoDesejada(p: Pref) = if (p == Pref.ORIGINAL) Versao.LEGENDADA else Versao.DUBLADA

    /**
     * Sem acento. Texto só ASCII (a maioria dos nomes de filme) volta igual sem passar pelo Normalizer: o NFD de um texto
     * ASCII é ele mesmo (20/11/2026; antes, cada chamada também compilava a expressão regular de novo).
     */
    fun semAcento(s: String): String {
        var i = 0
        while (i < s.length && s[i].code < 0x80) i++
        if (i == s.length) return s
        // Latim (até U+024F, sem marca solta): letra por letra pela tabela. Outra escrita: o caminho completo.
        for (j in i until s.length) if (s[j].code >= LATIM_FIM) return semAcentoCompleto(s)
        val b = StringBuilder(s.length).append(s, 0, i)
        for (j in i until s.length) { val c = s[j]; if (c.code < 0x80) b.append(c) else b.append(latimSemAcento[c.code - 0x80]) }
        return b.toString()
    }

    private fun semAcentoCompleto(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(reDiacriticos, "")
    private val reDiacriticos = Regex("""\p{Mn}+""")
    private const val LATIM_FIM = 0x250

    /**
     * Cada letra de U+0080 a U+024F já sem acento, calculada uma vez pelo próprio caminho completo (o mesmo Normalizer e
     * a mesma expressão): dá exatamente o mesmo texto. Abaixo de U+0300 não há marca solta, então decompor letra por
     * letra é o mesmo que decompor o texto inteiro.
     */
    private val latimSemAcento: Array<String> by lazy { Array(LATIM_FIM - 0x80) { semAcentoCompleto((0x80 + it).toChar().toString()) } }
}

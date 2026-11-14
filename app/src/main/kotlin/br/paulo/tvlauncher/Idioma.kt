package br.paulo.tvlauncher

import org.json.JSONObject
import java.text.Normalizer

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

    data class Prefs(val padrao: Pref = Pref.DUBLADO, val titulos: Map<String, Pref> = emptyMap()) {
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
    }.toString()

    fun deJson(texto: String?): Prefs {
        val o = runCatching { JSONObject(texto ?: return Prefs()) }.getOrNull() ?: return Prefs()
        fun pref(s: String?) = Pref.entries.firstOrNull { it.name == s }
        val t = o.optJSONObject("titulos")
        val titulos = LinkedHashMap<String, Pref>()
        t?.keys()?.forEach { k -> pref(t.optString(k))?.let { titulos[k] = it } }
        return Prefs(pref(o.optString("padrao")) ?: Pref.DUBLADO, titulos)
    }

    // ---------------------------------------------------------------- 2. faixas do arquivo

    /** Uma faixa como o player a vê: idioma (ISO, pode faltar), se é a padrão do arquivo, se é legenda "forçada". */
    data class Faixa(val idioma: String?, val padrao: Boolean = false, val forcada: Boolean = false, val rotulo: String? = null,
                     val externa: Boolean = false)   // externa = a legenda baixada da internet (LegendaExterna)

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
    private fun normalizar(l: String?): String? {
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
    fun escolherAudio(faixas: List<Faixa>, pref: Pref, idioma: String = "pt"): Int? {
        if (faixas.size < 2) return null
        return when (pref) {
            Pref.DUBLADO -> faixas.indexOfFirst { eDoIdioma(it, idioma) }.takeIf { it >= 0 }
            Pref.ORIGINAL -> faixas.indexOfFirst { it.padrao && eOutraLingua(it, idioma) }.takeIf { it >= 0 }
                ?: faixas.indexOfFirst { eOutraLingua(it, idioma) }.takeIf { it >= 0 }
        }
    }

    /**
     * Qual legenda ligar. Legenda baixada: sempre ela. Áudio no idioma da TV ([audioPortugues], nome antigo): nenhuma (só a
     * "forçada", que traduz placa e fala em outra língua, se houver). Áudio em outra língua: a legenda completa no idioma
     * da TV; sem ela, a forçada nesse idioma. Null = sem legenda.
     */
    fun escolherLegenda(faixas: List<Faixa>, audioPortugues: Boolean, idioma: String = "pt"): Int? {
        // Quem baixou uma legenda quer vê-la, mesmo com o áudio dublado (ex.: o arquivo só tem a faixa dublada).
        faixas.indexOfFirst { it.externa }.takeIf { it >= 0 }?.let { return it }
        val daTv = faixas.withIndex().filter { eDoIdioma(it.value, idioma) }
        if (audioPortugues) return daTv.firstOrNull { it.value.forcada }?.index
        return (daTv.firstOrNull { !it.value.forcada } ?: daTv.firstOrNull())?.index
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
    fun versao(nome: String, grupo: String): Versao {
        val n = nome.trim()
        if (reLeg.containsMatchIn(n)) return Versao.LEGENDADA
        if (reDub.containsMatchIn(n)) return Versao.DUBLADA
        val g = semAcento(grupo).lowercase()
        return when {
            "legendad" in g -> Versao.LEGENDADA
            "dublad" in g -> Versao.DUBLADA
            else -> Versao.DESCONHECIDA
        }
    }

    /** O título sem marca, ano, acento nem pontuação: "Duna: Parte 2 (2024) [L]" e "Duna - Parte 2 (2024)" dão o mesmo. */
    fun chaveTitulo(nome: String): String =
        semAcento(Vod.nomeLimpo(nome.replace(reMarcas, " ")).replace(reMarcas, " "))
            .lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /**
     * Índice do catálogo por título (só os itens com marca de versão; os outros nunca têm par). Montado uma vez, fora da
     * thread da tela: com 30 mil filmes, procurar o par na hora seria lento demais para a ficha abrir sem piscar.
     */
    fun <T> indice(lista: List<T>, nome: (T) -> String, grupo: (T) -> String): Map<String, List<T>> {
        val m = HashMap<String, MutableList<T>>()
        for (x in lista) {
            if (versao(nome(x), grupo(x)) == Versao.DESCONHECIDA) continue
            val k = chaveTitulo(nome(x))
            if (k.isEmpty()) continue
            m.getOrPut(k) { ArrayList(2) } += x
        }
        return m
    }

    /**
     * O mesmo título na outra versão: mesma chave, ano igual (ou um dos dois sem ano) e marca oposta à do alvo. O alvo sem
     * marca não tem par (não dá para saber o que ele é). Primeiro da lista ganha.
     */
    fun <T> outraVersao(alvo: T, indice: Map<String, List<T>>, nome: (T) -> String, grupo: (T) -> String, ano: (T) -> Int?): T? {
        val va = versao(nome(alvo), grupo(alvo))
        if (va == Versao.DESCONHECIDA) return null
        val candidatos = indice[chaveTitulo(nome(alvo))] ?: return null
        val anoA = ano(alvo)
        return candidatos.firstOrNull { o ->
            o !== alvo && (anoA == null || ano(o) == null || ano(o) == anoA) &&
                versao(nome(o), grupo(o)).let { it != Versao.DESCONHECIDA && it != va }
        }
    }

    /** A versão que a preferência pede: original = legendada; dublado = dublada. */
    fun versaoDesejada(p: Pref) = if (p == Pref.ORIGINAL) Versao.LEGENDADA else Versao.DUBLADA

    fun semAcento(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
}

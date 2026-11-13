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

    /** Português: pela língua ("pt", "pt-BR", "por") ou, sem língua, pelo rótulo ("Dublado", "Português"). */
    fun ePortugues(f: Faixa): Boolean {
        val l = f.idioma?.lowercase()
        if (l != null && l != "und") return l == "pt" || l.startsWith("pt-") || l == "por" || l == "pob"
        val r = f.rotulo?.let { semAcento(it).lowercase() } ?: return false
        return "portug" in r || "dublad" in r || r.contains(Regex("""\b(pt|ptbr|pt-br)\b"""))
    }

    /** A faixa tem idioma conhecido e não é português (é a original, num filme estrangeiro). */
    private fun eOutraLingua(f: Faixa): Boolean {
        val l = f.idioma?.lowercase()
        return l != null && l != "und" && !ePortugues(f)
    }

    /**
     * Qual áudio tocar. Dublado: o primeiro em português. Original: a faixa padrão do arquivo se não for português; senão
     * a primeira de outra língua. Null = deixar o que o player escolheu (só tem uma faixa, ou nada bate).
     */
    fun escolherAudio(faixas: List<Faixa>, pref: Pref): Int? {
        if (faixas.size < 2) return null
        return when (pref) {
            Pref.DUBLADO -> faixas.indexOfFirst { ePortugues(it) }.takeIf { it >= 0 }
            Pref.ORIGINAL -> faixas.indexOfFirst { it.padrao && eOutraLingua(it) }.takeIf { it >= 0 }
                ?: faixas.indexOfFirst { eOutraLingua(it) }.takeIf { it >= 0 }
        }
    }

    /**
     * Qual legenda ligar. Legenda baixada: sempre ela. Áudio em português: nenhuma (só a "forçada", que traduz placa e fala em outra língua, se houver).
     * Áudio em outra língua: a legenda em português completa; sem ela, a forçada em português. Null = sem legenda.
     */
    fun escolherLegenda(faixas: List<Faixa>, audioPortugues: Boolean): Int? {
        // Quem baixou uma legenda quer vê-la, mesmo com o áudio dublado (ex.: o arquivo só tem a faixa dublada).
        faixas.indexOfFirst { it.externa }.takeIf { it >= 0 }?.let { return it }
        val pt = faixas.withIndex().filter { ePortugues(it.value) }
        if (audioPortugues) return pt.firstOrNull { it.value.forcada }?.index
        return (pt.firstOrNull { !it.value.forcada } ?: pt.firstOrNull())?.index
    }

    /** A escolha manual de áudio (menu ▼) vira a preferência do título: português = dublado, outra língua = original. */
    fun prefDaEscolha(f: Faixa): Pref? = when {
        ePortugues(f) -> Pref.DUBLADO
        eOutraLingua(f) -> Pref.ORIGINAL
        else -> null
    }

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

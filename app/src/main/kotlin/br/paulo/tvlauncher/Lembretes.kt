package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Lembretes da grade (12/11/2026, ideia 1 do brainstorm): "me avisa quando começar".
 *
 * Dois jeitos de nascer um lembrete:
 *  - à mão: OK num programa FUTURO no guia em grade (OK num programa que já está passando continua sendo "assistir");
 *  - sozinho ("Meu time"): palavras salvas no painel ("Palmeiras", "Fórmula 1"). Cada vez que o guia é baixado de novo,
 *    os programas das próximas [HORIZONTE_MS] cujo título tem uma das palavras viram lembretes.
 *
 * [ANTECEDENCIA_MS] antes do início a TV mostra "começa em 2 min" com o botão Assistir (LembretesTv). Só a lógica pura
 * mora aqui (sem Android), testada em LembretesTest.
 */
object Lembretes {
    data class Lembrete(
        val canalId: String,
        val canalNome: String,
        val titulo: String,
        val inicio: Long,
        val fim: Long,
        /** Palavra que gerou o lembrete; vazio = marcado à mão. */
        val palavra: String = "",
        /** Já avisou (o aviso não se repete). */
        val avisado: Boolean = false,
    ) {
        val manual: Boolean get() = palavra.isEmpty()
        val chave: String get() = "$canalId|$inicio"
    }

    /** Um programa do guia com o canal onde passa: a entrada de [gerar]. */
    data class Candidato(val canalId: String, val canalNome: String, val numero: Int, val programa: Programa)

    const val MAX = 60
    const val MAX_PALAVRAS = 20
    const val ANTECEDENCIA_MS = 2 * 60_000L
    /** Até onde olhar à frente ao gerar pelas palavras: o guia guarda 14 h; 24 h cobre o caso de ele ter mais. */
    const val HORIZONTE_MS = 24 * 3_600_000L
    /** Por palavra, no máximo isto de lembretes (um time com 6 reprises no dia não pode lotar a lista). */
    const val MAX_POR_PALAVRA = 4

    /** Minúsculas e sem acento: "São dono" e "sao ana" casam. */
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /** "Palmeiras, Fórmula 1\nCorinthians" -> lista limpa (sem repetidas, 3 a 40 letras, no máximo [MAX_PALAVRAS]). */
    fun lerPalavras(texto: String): List<String> =
        texto.split(',', ';', '\n').map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 3..40 }.distinctBy { normalizar(it) }.take(MAX_PALAVRAS)

    /**
     * O título tem a palavra inteira? "Palmeiras x Santos" casa com "palmeiras"; "Palmeirense" não. A palavra pode ter
     * espaço ("formula 1"): vale como trecho, com borda de palavra nas duas pontas.
     */
    fun casa(titulo: String, palavra: String): Boolean {
        val p = normalizar(palavra)
        if (p.length < 3) return false
        val t = normalizar(titulo)
        var i = t.indexOf(p)
        while (i >= 0) {
            val antes = i == 0 || !t[i - 1].isLetterOrDigit()
            val depois = i + p.length >= t.length || !t[i + p.length].isLetterOrDigit()
            if (antes && depois) return true
            i = t.indexOf(p, i + 1)
        }
        return false
    }

    /** Marca ou desmarca à mão o programa (mesmo canal e início). Devolve a lista nova e se ficou marcado. */
    fun alternar(lista: List<Lembrete>, novo: Lembrete): Pair<List<Lembrete>, Boolean> {
        val existe = lista.any { it.chave == novo.chave }
        if (existe) return lista.filterNot { it.chave == novo.chave } to false
        return (lista + novo.copy(palavra = "", avisado = false)).sortedBy { it.inicio }.takeLast(MAX) to true
    }

    /** Marcado? (para desenhar o sininho no guia) */
    fun marcados(lista: List<Lembrete>): Set<String> = lista.mapTo(HashSet()) { it.chave }

    /**
     * Os lembretes automáticos para as [palavras], a partir dos candidatos do guia. Regras:
     *  - só programa que começa depois de agora + antecedência e antes de agora + [HORIZONTE_MS];
     *  - o mesmo jogo em dois canais (mesmo título e mesmo início) vira UM lembrete, no canal de número menor;
     *  - no máximo [MAX_POR_PALAVRA] por palavra, os mais cedo primeiro.
     * Os automáticos antigos saem e entram os novos; os manuais ficam como estão (e um automático que repete um manual
     * não entra). Um automático que já tinha avisado continua avisado.
     */
    fun gerar(atuais: List<Lembrete>, candidatos: Sequence<Candidato>, palavras: List<String>, agora: Long): List<Lembrete> {
        val manuais = atuais.filter { it.manual }
        val avisados = atuais.filter { !it.manual && it.avisado }.mapTo(HashSet()) { it.chave }
        if (palavras.isEmpty()) return limpar(manuais, agora)
        val ocupados = manuais.mapTo(HashSet()) { it.chave }
        val porJogo = HashMap<String, Pair<Candidato, String>>()   // título normalizado + início -> melhor canal
        for (c in candidatos) {
            val p = c.programa
            if (p.inicio <= agora + ANTECEDENCIA_MS || p.inicio > agora + HORIZONTE_MS) continue
            val palavra = palavras.firstOrNull { casa(p.titulo, it) } ?: continue
            val k = normalizar(p.titulo) + "|" + p.inicio
            val ja = porJogo[k]
            if (ja == null || c.numero < ja.first.numero) porJogo[k] = c to palavra
        }
        val novos = porJogo.values.groupBy { it.second }.flatMap { (_, l) ->
            l.sortedBy { it.first.programa.inicio }.take(MAX_POR_PALAVRA)
        }.map { (c, palavra) ->
            val l = Lembrete(c.canalId, c.canalNome, c.programa.titulo, c.programa.inicio, c.programa.fim, palavra)
            if (l.chave in avisados) l.copy(avisado = true) else l
        }.filterNot { it.chave in ocupados }
        return limpar(manuais + novos, agora).sortedBy { it.inicio }.take(MAX)
    }

    /** Tira os que já acabaram. */
    fun limpar(lista: List<Lembrete>, agora: Long): List<Lembrete> = lista.filter { it.fim > agora }

    /** Os que devem avisar agora: não avisados, cujo instante de aviso já chegou, e que ainda não acabaram. */
    fun devidos(lista: List<Lembrete>, agora: Long): List<Lembrete> =
        lista.filter { !it.avisado && it.inicio - ANTECEDENCIA_MS <= agora + 5_000L && it.fim > agora }

    /** Quando acordar para o próximo aviso (ms), ou null se não há nenhum por avisar. */
    fun proximoAviso(lista: List<Lembrete>, agora: Long): Long? =
        lista.filter { !it.avisado && it.fim > agora }.minOfOrNull { maxOf(it.inicio - ANTECEDENCIA_MS, agora + 1_000L) }

    fun marcarAvisados(lista: List<Lembrete>, chaves: Set<String>): List<Lembrete> =
        lista.map { if (it.chave in chaves) it.copy(avisado = true) else it }

    fun paraJson(lista: List<Lembrete>): String = JSONArray().apply {
        for (l in lista) put(JSONObject().put("canal", l.canalId).put("nome", l.canalNome).put("titulo", l.titulo)
            .put("inicio", l.inicio).put("fim", l.fim).put("palavra", l.palavra).put("avisado", l.avisado))
    }.toString()

    /** Lê o JSON salvo; item estragado sai sozinho, texto inválido vira lista vazia. */
    fun deJson(texto: String?): List<Lembrete> {
        val a = try { JSONArray(texto ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val saida = ArrayList<Lembrete>()
        for (i in 0 until minOf(a.length(), MAX)) {
            val o = a.optJSONObject(i) ?: continue
            val canal = o.optString("canal"); val titulo = o.optString("titulo")
            val inicio = o.optLong("inicio"); val fim = o.optLong("fim")
            if (canal.isEmpty() || titulo.isEmpty() || inicio <= 0 || fim <= inicio) continue
            saida += Lembrete(canal, o.optString("nome"), titulo, inicio, fim, o.optString("palavra"), o.optBoolean("avisado"))
        }
        return saida.sortedBy { it.inicio }
    }
}

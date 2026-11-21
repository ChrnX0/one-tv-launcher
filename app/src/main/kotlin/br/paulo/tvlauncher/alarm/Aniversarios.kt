package br.paulo.tvlauncher.alarm

import android.content.Context
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.settings.Extras
import java.util.Calendar
import java.util.GregorianCalendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aniversários da família (ideia 93, 14/11/2026). Lista própria da TV, editada pelo painel (aba Extras, cartão
 * "Aniversários") ou pelo Claude; não depende da agenda do Google (achado 05a.5: a agenda não tinha aniversários
 * próprios). Na véspera e no dia, a HOME mostra uma pílula ("Hoje é aniversário de tia Rosa · 80 anos") e o protetor um
 * cartão. O modo visita esconde.
 *
 * Guardado nas prefs "extras" (chave [CHAVE], JSON), que já vão no backup completo. Lógica pura: AniversariosTest.
 */
object Aniversarios {
    const val CHAVE = "aniversarios"
    const val MAX = 60
    const val MAX_NOME = 40

    /** [ano] opcional: com ele, mostra a idade que a pessoa faz. */
    data class Pessoa(val nome: String, val dia: Int, val mes: Int, val ano: Int? = null)

    enum class Quando { HOJE, AMANHA }

    data class Lembrete(val pessoa: Pessoa, val quando: Quando, val idade: Int?)

    fun limparNome(t: String): String = t.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(MAX_NOME)

    /** Pessoa válida ou null (nome vazio, data que não existe: 31/04, 30/02). 29/02 vale. */
    fun criar(nome: String, dia: Int, mes: Int, ano: Int?): Pessoa? {
        val n = limparNome(nome)
        if (n.isEmpty() || mes !in 1..12 || dia < 1) return null
        val maxDia = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[mes - 1]
        if (dia > maxDia) return null
        val a = ano?.takeIf { it in 1900..2100 }
        return Pessoa(n, dia, mes, a)
    }

    /** Põe (ou troca, se o nome já existe) e devolve a lista em ordem de data. */
    fun adicionar(lista: List<Pessoa>, p: Pessoa): List<Pessoa> =
        (lista.filterNot { Lembretes.normalizar(it.nome) == Lembretes.normalizar(p.nome) } + p)
            .sortedWith(compareBy({ it.mes }, { it.dia }, { it.nome })).take(MAX)

    fun remover(lista: List<Pessoa>, nome: String): List<Pessoa> =
        lista.filterNot { Lembretes.normalizar(it.nome) == Lembretes.normalizar(limparNome(nome)) }

    /** O dia em que o aniversário cai no [ano]: 29/02 vira 28/02 em ano que não é bissexto. */
    private fun diaNoAno(p: Pessoa, ano: Int): Pair<Int, Int> =
        if (p.mes == 2 && p.dia == 29 && !GregorianCalendar().isLeapYear(ano)) 28 to 2 else p.dia to p.mes

    /** Quem faz aniversário hoje ou amanhã, em relação a [hoje] (só ano/mês/dia importam). Hoje vem antes. */
    fun proximos(lista: List<Pessoa>, hoje: Calendar): List<Lembrete> {
        val amanha = (hoje.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        fun bate(p: Pessoa, c: Calendar): Boolean {
            val (d, m) = diaNoAno(p, c.get(Calendar.YEAR))
            return d == c.get(Calendar.DAY_OF_MONTH) && m == c.get(Calendar.MONTH) + 1
        }
        fun idade(p: Pessoa, c: Calendar) = p.ano?.let { c.get(Calendar.YEAR) - it }?.takeIf { it in 1..130 }
        return lista.filter { bate(it, hoje) }.map { Lembrete(it, Quando.HOJE, idade(it, hoje)) } +
            lista.filter { bate(it, amanha) }.map { Lembrete(it, Quando.AMANHA, idade(it, amanha)) }
    }

    fun paraJson(lista: List<Pessoa>): JSONArray = JSONArray().apply {
        lista.forEach { p -> put(JSONObject().put("nome", p.nome).put("dia", p.dia).put("mes", p.mes).apply { p.ano?.let { put("ano", it) } }) }
    }

    fun deJson(texto: String?): List<Pessoa> {
        val a = try { JSONArray(texto ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            criar(o.optString("nome"), o.optInt("dia"), o.optInt("mes"), if (o.has("ano")) o.optInt("ano") else null)
        }.take(MAX)
    }

    // ---- na TV

    fun ler(ctx: Context): List<Pessoa> = deJson(Extras.texto(ctx, CHAVE))

    @Synchronized
    fun editar(ctx: Context, mudar: (List<Pessoa>) -> List<Pessoa>): List<Pessoa> {
        val nova = mudar(ler(ctx))
        Extras.definirTexto(ctx, CHAVE, paraJson(nova).toString())
        return nova
    }

    /** Os textos de hoje/amanhã para a HOME e o protetor (vazio no modo visita). */
    fun textos(ctx: Context, agora: Long = System.currentTimeMillis()): List<String> {
        if (ModoVisita.ativo(ctx)) return emptyList()
        val hoje = Calendar.getInstance().apply { timeInMillis = agora }
        return proximos(ler(ctx), hoje).map { texto(ctx, it) }
    }

    fun texto(ctx: Context, l: Lembrete): String {
        val base = ctx.getString(if (l.quando == Quando.HOJE) R.string.an_hoje else R.string.an_amanha, l.pessoa.nome)
        return if (l.idade != null) base + " · " + ctx.resources.getQuantityString(R.plurals.an_anos, l.idade, l.idade) else base
    }
}

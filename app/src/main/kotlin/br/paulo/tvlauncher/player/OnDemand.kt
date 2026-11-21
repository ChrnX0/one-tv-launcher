package br.paulo.tvlauncher.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Congelar sob demanda (Paulo, 20/11/2026: "se formos precisar do serviço ele descongela na hora ... e depois de usado
 * ele congela novamente").
 *
 * Porquê: um app congelado não roda nada em segundo plano (nem serviço, nem alarme, nem atualização de catálogo), então
 * não come memória nem rede enquanto ninguém usa. O preço era sumir da tela inicial; aqui ele continua lá, com o ícone e o
 * nome guardados, e o OK descongela e abre.
 *
 * Só vale para app que ABRE uma tela ([eligible]): serviço de sistema (voz, Cast, setupwraith) é chamado por outro app,
 * a chamada falha antes de dar tempo de descongelar, então nunca entra aqui. App de sistema só com ficha "seguro".
 *
 * Como volta a congelar: quando ele sai da frente, anota a hora ([onWindow]); passados [GRACE_MS] longe da frente e sem
 * tocar mídia, [due] o devolve para congelar. Os 2 min cobrem o vai e volta (abrir a loja para atualizar e voltar,
 * escolher conta). Pura (sem Android): OnDemandTest. Quem age é o OnDemandTv.
 */
object OnDemand {

    data class Entry(
        val pkg: String,
        val label: String,
        /** Quando o descongelamos para abrir (0 = congelado por nós). */
        val thawedAt: Long = 0L,
        /** Quando saiu da frente depois de aberto (0 = está na frente, ou nem abriu). */
        val leftAt: Long = 0L,
    ) {
        val thawed get() = thawedAt > 0L
    }

    const val GRACE_MS = 2 * 60_000L

    /**
     * Pode ser sob demanda: tem tela para abrir, não é protegido nem o launcher, e, se for de sistema, a ficha diz
     * "seguro" (app de sistema sem ficha ou "cuidado" pode ter serviço que outro app chama sem abrir a tela).
     */
    fun eligible(hasScreen: Boolean, protected: Boolean, ours: Boolean, system: Boolean, risk: String?): Boolean =
        hasScreen && !protected && !ours && risk != "essencial" && (!system || risk == "seguro")

    /** Uma tela apareceu: o app da frente zera a saída; os outros descongelados ganham a hora em que saíram. */
    fun onWindow(entries: List<Entry>, foreground: String?, now: Long): List<Entry> {
        // Diálogo do sistema e o nosso protetor não mudam quem está na frente (JanelaNaFrente já filtra; aqui é só cinto).
        if (foreground == null || foreground == "android" || foreground == "com.android.systemui") return entries
        return entries.map { e ->
            when {
                !e.thawed -> e
                e.pkg == foreground -> if (e.leftAt == 0L) e else e.copy(leftAt = 0L)
                e.leftAt == 0L -> e.copy(leftAt = now)
                else -> e
            }
        }
    }

    /** Os que já podem voltar a congelar: descongelados, fora da frente há [GRACE_MS] e sem tocar mídia. */
    fun due(entries: List<Entry>, foreground: String?, playing: Set<String>, now: Long): List<String> =
        entries.filter { it.thawed && it.pkg != foreground && it.pkg !in playing && it.leftAt > 0L && now - it.leftAt >= GRACE_MS }
            .map { it.pkg }

    fun toJson(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("thawed_at", it.thawedAt).put("left_at", it.leftAt)) }
    }.toString()

    fun fromJson(s: String?): List<Entry> {
        val a = try { JSONArray(s ?: "[]") } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optString("pkg").takeIf { it.contains('.') } ?: return@mapNotNull null
            Entry(p, o.optString("label").ifEmpty { p }, o.optLong("thawed_at"), o.optLong("left_at"))
        }.distinctBy { it.pkg }
    }

    /** Põe ou troca a entrada do pacote. */
    fun put(entries: List<Entry>, e: Entry): List<Entry> = entries.filter { it.pkg != e.pkg } + e

    fun update(entries: List<Entry>, pkg: String, f: (Entry) -> Entry): List<Entry> = entries.map { if (it.pkg == pkg) f(it) else it }
}

package br.paulo.tvlauncher.observer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Observador, fase 3 ("mãos e prova", 20/11/2026): congelar com prova antes e depois, e desfazer sozinho.
 *
 * Porquê: congelar o pacote errado quebra coisa longe dele. Exemplos reais desta casa: o setupwraith congelado tirou o
 * Cast (06/11); o launcherx congelado levou o Gemini e o seletor de contas (04/11). A ficha avisa, mas a ficha pode errar
 * e o Android muda a cada atualização. Para quê: quem congela (a pessoa no painel, ou o Observador no futuro) não precisa
 * acertar de primeira; se algo que funcionava parou, a TV volta sozinha e diz o que quebrou.
 * Como: na hora de congelar, [start] guarda as conferências de antes ([Check]: voz, busca, loja, Configurações, Cast,
 * internet). Depois de [WAIT_MS], [decide] confere de novo. Uma conferência que passava e agora falha = [Verdict.Undo]
 * (descongela). Conferência que é DO PRÓPRIO pacote congelado não conta: quem congela a loja quer a loja fora.
 * Pura (sem Android): ObserverTrialTest. As conferências reais moram em ObserverTv.
 */
object ObserverTrial {

    /** Cada conferência e o pacote "dono" dela (congelar o dono desliga a conferência de propósito). */
    enum class Check(val owner: String?) {
        VOICE("com.google.android.katniss"),        // há quem atenda o assistente / busca por voz
        PLAY_STORE("com.android.vending"),           // a loja abre
        SETTINGS("com.android.tv.settings"),         // as Configurações abrem
        CAST("com.google.android.apps.mediashell"),  // o Cast está ligado e com as credenciais (setupwraith)
        INTERNET(null),                              // a última amostra de rede tinha internet
    }

    data class Trial(val pkg: String, val at: Long, val before: Map<Check, Boolean>)

    sealed class Verdict {
        object Wait : Verdict()
        object Keep : Verdict()
        data class Undo(val broke: List<Check>) : Verdict()
    }

    const val WAIT_MS = 10 * 60_000L
    /** Prova que ficou sem decidir (TV desligada, processo morto) perde a validade: congelado fica, com registro. */
    const val EXPIRE_MS = 24 * 3_600_000L

    fun start(pkg: String, now: Long, before: Map<Check, Boolean>) = Trial(pkg, now, before)

    fun decide(t: Trial, now: Long, after: Map<Check, Boolean>): Verdict {
        if (now - t.at < WAIT_MS) return Verdict.Wait
        val broke = Check.entries.filter { c ->
            c.owner != t.pkg && t.before[c] == true && after[c] == false
        }
        // Internet sozinha cai por mil motivos (roteador, operadora): só conta junto de outra quebra, ou se nada mais é medido.
        val real = if (broke == listOf(Check.INTERNET) && after.size > 1) emptyList() else broke
        return if (real.isEmpty()) Verdict.Keep else Verdict.Undo(real)
    }

    // ---------------------------------------------------------------- guardar (prefs "observer", chave "trials")

    fun toJson(list: List<Trial>): String = JSONArray().apply {
        list.forEach { t ->
            put(JSONObject().put("pkg", t.pkg).put("at", t.at)
                .put("before", JSONObject().apply { t.before.forEach { (c, v) -> put(c.name, v) } }))
        }
    }.toString()

    fun fromJson(s: String?): List<Trial> {
        val a = try { JSONArray(s ?: "[]") } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val b = o.optJSONObject("before") ?: JSONObject()
            Trial(o.optString("pkg").takeIf { it.isNotEmpty() } ?: return@mapNotNull null, o.optLong("at"),
                Check.entries.filter { b.has(it.name) }.associateWith { b.getBoolean(it.name) })
        }
    }

    /** Uma prova por pacote: congelar de novo o mesmo pacote recomeça a dele. */
    fun add(list: List<Trial>, t: Trial) = list.filter { it.pkg != t.pkg } + t
}

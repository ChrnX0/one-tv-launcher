package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "❤ Vi" (revisão dos degraus, 3a, 17/11/2026): a família conectada era de mão única. Agora quem está na TV dá OK no
 * recado que acabou de chegar, e quem mandou vê na página do link, na próxima visita, que o recado foi visto.
 *
 * Sem conta: a página guarda no celular um número aleatório ("remetente", 16 a 32 letras hex) e manda junto de cada
 * recado. A TV guarda só (id, remetente, hora, começo do texto, visto) dos últimos [MAX] recados, e a página pergunta
 * pelos do PRÓPRIO remetente. Quem não tem o número de outra pessoa não vê os recados dela. Nada sai da casa além do
 * que a página já mandou. Regras puras em [Regras] (FamiliaVistosTest).
 */
object FamiliaVistos {
    const val MAX = 60
    const val POR_REMETENTE = 10
    private const val PREFS = "familia_vistos"

    data class Item(val id: String, val rid: String, val quem: String, val quando: Long, val resumo: String, val visto: Boolean)

    object Regras {
        private val RID = Regex("^[0-9a-f]{16,32}$")
        fun ridValido(r: String?) = r != null && RID.matches(r)

        /** O começo do recado que a página mostra de volta (o próprio texto de quem mandou, curto). */
        fun resumo(texto: String?, temFoto: Boolean): String {
            val t = (texto ?: "").replace(Regex("\\s+"), " ").trim()
            return when {
                t.isEmpty() && temFoto -> "📷"
                t.length > 40 -> t.take(39).trimEnd() + "…"
                else -> t
            }
        }

        /** Acrescenta no começo e corta: no máximo [POR_REMETENTE] de cada remetente e [MAX] no total. */
        fun registrar(lista: List<Item>, novo: Item): List<Item> {
            val out = ArrayList<Item>(); val conta = HashMap<String, Int>()
            for (i in listOf(novo) + lista) {
                val n = conta.getOrDefault(i.rid, 0)
                if (n >= POR_REMETENTE || out.size >= MAX) continue
                conta[i.rid] = n + 1; out += i
            }
            return out
        }

        fun marcar(lista: List<Item>, id: String): List<Item> = lista.map { if (it.id == id) it.copy(visto = true) else it }

        fun doRemetente(lista: List<Item>, rid: String): List<Item> = lista.filter { it.rid == rid }

        fun paraJson(l: List<Item>): String = JSONArray().apply {
            l.forEach { put(JSONObject().put("id", it.id).put("r", it.rid).put("q", it.quem).put("t", it.quando).put("s", it.resumo).put("v", it.visto)) }
        }.toString()

        fun deJson(s: String?): List<Item> = runCatching {
            val a = JSONArray(s ?: return emptyList())
            (0 until a.length()).mapNotNull { k ->
                val o = a.optJSONObject(k) ?: return@mapNotNull null
                Item(o.optString("id"), o.optString("r"), o.optString("q"), o.optLong("t"), o.optString("s"), o.optBoolean("v"))
                    .takeIf { it.id.isNotEmpty() && ridValido(it.rid) }
            }
        }.getOrDefault(emptyList())

        /** O que a página recebe: só hora, resumo e visto (sem id nem nome). */
        fun resposta(l: List<Item>): JSONArray = JSONArray().apply {
            l.forEach { put(JSONObject().put("quando", it.quando).put("resumo", it.resumo).put("visto", it.visto)) }
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lista(ctx: Context) = Regras.deJson(prefs(ctx).getString("lista", null))
    @Synchronized private fun mudar(ctx: Context, f: (List<Item>) -> List<Item>) =
        prefs(ctx).edit().putString("lista", Regras.paraJson(f(lista(ctx)))).apply()

    /** Registra um recado que chegou; devolve o id para o aviso (OK = visto), ou null sem remetente válido. */
    fun registrar(ctx: Context, rid: String?, quem: String, texto: String?, temFoto: Boolean, idFixo: String? = null): String? {
        if (!Regras.ridValido(rid)) return null
        val id = idFixo ?: java.util.UUID.randomUUID().toString()   // vídeo: o nome do arquivo (marcado ao assistir)
        mudar(ctx) { Regras.registrar(it, Item(id, rid!!, quem, System.currentTimeMillis(), Regras.resumo(texto, temFoto), false)) }
        return id
    }

    /** OK no aviso: marca como visto. Devolve o nome de quem mandou (para a confirmação), ou null. */
    fun marcar(ctx: Context, id: String): String? {
        val quem = lista(ctx).firstOrNull { it.id == id }?.quem
        mudar(ctx) { Regras.marcar(it, id) }
        return quem
    }

    fun doRemetente(ctx: Context, rid: String?): JSONArray =
        if (!Regras.ridValido(rid)) JSONArray() else Regras.resposta(Regras.doRemetente(lista(ctx), rid!!))
}

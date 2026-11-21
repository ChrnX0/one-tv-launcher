package br.paulo.tvlauncher.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cenas prontas, sem voz (degrau 3, 16/11/2026): "Dormir", "Novela" e "Jogo" com um OK, na tela Cenas e no painel
 * rápido. São cenas comuns (Cenas.kt), montadas com ações do catálogo fechado do ComandosIa; depois de criadas, a
 * pessoa edita ou apaga no painel como qualquer outra. Aqui só os modelos (CenasProntasTest).
 *  - Dormir: filtro noturno, volume 3 passos abaixo, a TV desliga em 30 min;
 *  - Novela: o canal que a pessoa disser, filtro noturno desligado (cores normais);
 *  - Jogo: o canal que a pessoa disser, volume 3 passos acima.
 */
object CenasProntas {
    enum class Modelo(val pedeCanal: Boolean) { DORMIR(false), NOVELA(true), JOGO(true) }

    fun acoes(m: Modelo, canal: String? = null): String {
        val a = JSONArray()
        fun acao(tipo: String, vararg campos: Pair<String, Any>) = a.put(JSONObject().put("tipo", tipo).apply { campos.forEach { put(it.first, it.second) } })
        when (m) {
            Modelo.DORMIR -> { acao("filtro_noturno", "ligado" to true); acao("volume", "acao" to "descer", "vezes" to 3); acao("desligar_em", "minutos" to 30) }
            Modelo.NOVELA -> { acao("tocar_canal", "canal" to canal!!.trim()); acao("filtro_noturno", "ligado" to false) }
            Modelo.JOGO -> { acao("tocar_canal", "canal" to canal!!.trim()); acao("volume", "acao" to "subir", "vezes" to 3) }
        }
        return JSONObject().put("acoes", a).toString()
    }

    /** Os modelos que ainda não viraram cena (pelo nome no idioma da TV). */
    fun faltando(nomes: Map<Modelo, String>, existentes: Collection<String>): List<Modelo> =
        Modelo.entries.filter { m -> existentes.none { it.equals(nomes.getValue(m), ignoreCase = true) } }
}

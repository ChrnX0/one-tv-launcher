package br.paulo.tvlauncher.observer

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Vitals" locais (degrau 2, item 8, 16/11/2026). O Android Vitals da Play só existe com o app publicado e o Play
 * Console; até lá a própria TV mede o que ele mede: quantas vezes o launcher CAIU (crash) e TRAVOU (ANR, "o app não
 * responde"), por dia, contra quantas vezes o processo abriu. Fonte: ApplicationExitInfo do próprio pacote (Android
 * 11+, sem permissão especial), lido a cada processo novo (VitaisTv.kt). Aqui só as REGRAS (VitaisLocaisTest).
 *
 * "Percebida" = a queda/travada aconteceu com o launcher na tela (é a que a Play conta como "percebida pelo usuário");
 * em segundo plano ninguém viu, mas também entra no total.
 */
object VitaisLocais {
    const val DIAS = 30

    data class Dia(val data: String, val aberturas: Int = 0, val quedas: Int = 0, val travadas: Int = 0, val percebidas: Int = 0)

    data class Estado(val dias: List<Dia> = emptyList(), val ultimaSaida: Long = 0L)

    enum class Tipo { QUEDA, TRAVADA }

    /** Uma saída do processo já classificada pelo Android ([quando] é o timestamp do ApplicationExitInfo). */
    data class Saida(val quando: Long, val data: String, val tipo: Tipo, val naTela: Boolean)

    private fun mudarDia(e: Estado, data: String, f: (Dia) -> Dia): Estado {
        val atual = e.dias.firstOrNull { it.data == data } ?: Dia(data)
        val novos = (e.dias.filter { it.data != data } + f(atual)).sortedBy { it.data }.takeLast(DIAS)
        return e.copy(dias = novos)
    }

    fun abriu(e: Estado, data: String) = mudarDia(e, data) { it.copy(aberturas = it.aberturas + 1) }

    /** Soma as saídas que ainda não vimos (timestamp depois de [Estado.ultimaSaida]); a mesma lista lida duas vezes não conta em dobro. */
    fun somarSaidas(e: Estado, saidas: List<Saida>): Estado {
        var r = e
        for (s in saidas.filter { it.quando > e.ultimaSaida }.sortedBy { it.quando }) {
            r = mudarDia(r, s.data) { d ->
                val p = if (s.naTela) d.percebidas + 1 else d.percebidas
                if (s.tipo == Tipo.QUEDA) d.copy(quedas = d.quedas + 1, percebidas = p) else d.copy(travadas = d.travadas + 1, percebidas = p)
            }
            r = r.copy(ultimaSaida = maxOf(r.ultimaSaida, s.quando))
        }
        return r
    }

    data class Resumo(val diasComUso: Int, val aberturas: Int, val quedas: Int, val travadas: Int, val percebidas: Int, val diasComProblema: Int) {
        /** 0 = ok, 1 = atenção (algo em segundo plano), 2 = problema (alguém viu cair ou travar). */
        val nivel get() = when { percebidas > 0 -> 2; quedas + travadas > 0 -> 1; else -> 0 }
    }

    /** Os últimos [n] dias até [hoje] (datas "aaaa-mm-dd", que comparam em ordem de texto). */
    fun resumo(e: Estado, hoje: String, desde: String): Resumo {
        val l = e.dias.filter { it.data in desde..hoje }
        return Resumo(l.count { it.aberturas > 0 || it.quedas + it.travadas > 0 }, l.sumOf { it.aberturas }, l.sumOf { it.quedas },
            l.sumOf { it.travadas }, l.sumOf { it.percebidas }, l.count { it.quedas + it.travadas > 0 })
    }

    fun texto(e: Estado): String = JSONObject().put("ultima", e.ultimaSaida).put("dias", JSONArray().apply {
        e.dias.forEach { put(JSONObject().put("d", it.data).put("a", it.aberturas).put("q", it.quedas).put("t", it.travadas).put("p", it.percebidas)) }
    }).toString()

    fun ler(t: String?): Estado = try {
        val o = JSONObject(t ?: "{}")
        val a = o.optJSONArray("dias") ?: JSONArray()
        Estado((0 until a.length()).mapNotNull { i ->
            val d = a.optJSONObject(i) ?: return@mapNotNull null
            val data = d.optString("d").takeIf { Regex("""\d{4}-\d\d-\d\d""").matches(it) } ?: return@mapNotNull null
            Dia(data, d.optInt("a"), d.optInt("q"), d.optInt("t"), d.optInt("p"))
        }.sortedBy { it.data }.takeLast(DIAS), o.optLong("ultima"))
    } catch (_: Exception) { Estado() }
}

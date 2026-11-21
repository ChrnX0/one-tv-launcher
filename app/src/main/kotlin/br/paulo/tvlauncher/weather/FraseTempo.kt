package br.paulo.tvlauncher.weather

import br.paulo.tvlauncher.player.LinhasTempo
import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.screensaver.WidgetsTempo
import java.util.Calendar
import java.util.TimeZone

/**
 * A frase do tempo do protetor (caminho 2, 17/11/2026): UMA frase útil, em amarelo, embaixo da data. Decide o que dizer;
 * o texto em cada língua sai em [LinhasTempo]. Puro, testado em FraseTempoTest.
 *
 * Ordem (a primeira que valer ganha): chuva nos próximos minutos/horas > trovoada > geada > calor forte > neblina >
 * noite fria > bom para secar roupa > amanhã chove > céu limpo de noite. Sem nada: null (a tela mostra o céu de agora).
 */
object FraseTempo {
    sealed class Frase {
        /** Não chove; começa em uns [minutos] (bloco de 15 min, arredondado a 5). */
        data class ChuvaEmMinutos(val minutos: Int) : Frase()
        /** Chove agora e para em uns [minutos]. */
        data class ParaEmMinutos(val minutos: Int) : Frase()
        /** Não chove; começa lá pelas [ms] (hora a hora). */
        data class ChuvaPorVolta(val ms: Long) : Frase()
        /** Chove agora e para lá pelas [ms]. */
        data class ParaPorVolta(val ms: Long) : Frase()
        object ChuvaSegue : Frase()
        /** Trovoada prevista para lá das [ms] (até 6 h). */
        data class Trovoada(val ms: Long) : Frase()
        /** Geada: mínima de até 3 °C. [hoje] = esta noite; false = amanhã cedo. */
        data class Geada(val hoje: Boolean) : Frase()
        data class CalorForte(val max: Int) : Frase()
        object Neblina : Frase()
        data class NoiteFria(val min: Int) : Frase()
        object SecarRoupa : Frase()
        data class AmanhaChove(val chance: Int) : Frase()
        object CeuLimpo : Frase()
    }

    /** Chuva que conta (mm em 15 min): 0,2 mm já molha quem está na rua. */
    const val MM_CONTA = 0.2
    /** Até onde a frase de minutos olha (2 h); depois disso, vale a hora a hora. */
    const val MINUTOS_MAX = 120

    fun escolher(t: TempoAgora, fuso: TimeZone = TimeZone.getDefault()): Frase? {
        chuvaMinutos(t)?.let { return it }
        WidgetsTempo.frase(t.chovendo, t.horas, t.agora)?.let { f ->
            return when (f) {
                is WidgetsTempo.Frase.ChegaPorVolta -> Frase.ChuvaPorVolta(f.ms)
                is WidgetsTempo.Frase.ParaPorVolta -> Frase.ParaPorVolta(f.ms)
                WidgetsTempo.Frase.ChuvaSegue -> Frase.ChuvaSegue
            }
        }
        if (t.chovendo) return Frase.ChuvaSegue
        t.horas.firstOrNull { it.ms > t.agora && it.ms <= t.agora + 6 * 3_600_000L && it.codigo >= 95 }
            ?.let { return Frase.Trovoada(it.ms) }
        val hora = Calendar.getInstance(fuso).apply { timeInMillis = t.agora }.get(Calendar.HOUR_OF_DAY)
        // Geada: de tarde/noite olha a mínima de amanhã cedo (a mínima de "hoje" já passou de madrugada).
        val minNoite = if (hora >= 12) t.amanhaMin else t.min
        if (minNoite != null && minNoite <= c(t, 3.0)) return Frase.Geada(hoje = hora >= 12)
        if (hora in 6..15 && t.max != null && t.max >= c(t, 34.0)) return Frase.CalorForte(t.max)
        if (t.codigo == 45 || t.codigo == 48) return Frase.Neblina
        if (hora >= 16 && minNoite != null && minNoite <= c(t, 10.0)) return Frase.NoiteFria(minNoite)
        if (hora in 6..13 && secarRoupa(t)) return Frase.SecarRoupa
        if (hora >= 17 && (t.amanhaChuva ?: 0) >= 70) return Frase.AmanhaChove(t.amanhaChuva!!)
        if ((hora >= 19 || hora < 5) && t.codigo == 0) return Frase.CeuLimpo
        return null
    }

    /** Limite em Celsius convertido para a unidade da fonte. */
    private fun c(t: TempoAgora, celsius: Double): Double = if (t.celsius) celsius else celsius * 9 / 5 + 32

    private fun chuvaMinutos(t: TempoAgora): Frase? {
        val blocos = t.chuva15.filter { it.first + 15 * 60_000L > t.agora && it.first <= t.agora + MINUTOS_MAX * 60_000L }
        if (blocos.isEmpty()) return null
        fun minutos(ms: Long) = (((ms - t.agora).coerceAtLeast(0) / 60_000.0 / 5).let { Math.round(it) * 5 }).toInt().coerceAtLeast(5)
        return if (t.chovendo) {
            // Para quando dois blocos seguidos ficam secos (um bloco seco no meio da chuva é só uma trégua).
            blocos.indices.firstOrNull { i -> blocos[i].second < MM_CONTA && (blocos.getOrNull(i + 1)?.second ?: 0.0) < MM_CONTA }
                ?.let { Frase.ParaEmMinutos(minutos(blocos[it].first)) }
        } else blocos.firstOrNull { it.second >= MM_CONTA }?.let { Frase.ChuvaEmMinutos(minutos(it.first)) }
    }

    /** Sol ou poucas nuvens, ar não muito úmido e nada de chuva nas próximas 8 h. */
    private fun secarRoupa(t: TempoAgora): Boolean {
        if (t.codigo > 2 || t.chovendo) return false
        if ((t.umidade ?: 100) > 70) return false
        val proximas = t.horas.filter { it.ms > t.agora && it.ms <= t.agora + 8 * 3_600_000L }
        return proximas.isNotEmpty() && proximas.all { it.chuva in 0..20 && it.codigo <= 3 }
    }
}

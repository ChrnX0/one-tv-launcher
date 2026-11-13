package br.paulo.tvlauncher

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sol no céu, calculado no aparelho (fórmulas simplificadas do NOAA / Almanaque Náutico, erro de ~1°).
 * Usado pelos fundos "Céu ao vivo" e "Astrolábio": cor do céu, posição do sol, nascer e pôr.
 */
object Astronomia {
    private const val RAD = PI / 180

    /** Altura do sol acima do horizonte, em graus (negativa = abaixo), no instante [ms] e no lugar [lat]/[lon]. */
    fun alturaSol(ms: Long, lat: Double, lon: Double): Double {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0              // dias desde 1/1/2000 12h UTC (J2000)
        val g = (357.529 + 0.98560028 * d) * RAD                         // anomalia média
        val q = 280.459 + 0.98564736 * d                                 // longitude média
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD          // longitude eclíptica
        val e = (23.439 - 0.00000036 * d) * RAD                          // inclinação do eixo da Terra
        val ra = atan2(cos(e) * sin(l), cos(l))                          // ascensão reta
        val dec = asin(sin(e) * sin(l))                                  // declinação
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24          // hora sideral de Greenwich
        val h = (gmst * 15 + lon) * RAD - ra                             // ângulo horário
        return asin(sin(lat * RAD) * sin(dec) + cos(lat * RAD) * cos(dec) * cos(h)) / RAD
    }

    /**
     * Nascer e pôr do sol do dia de [ms] (horário local), em ms. Procura de 2 em 2 minutos quando a altura cruza
     * -0,833° (o disco inteiro + a refração da atmosfera) e afina o cruzamento por bisseção até ~1 s (10/11/2026: antes
     * o resultado era o fim da janela de 2 min, até 2 min atrasado). null onde não cruza (perto dos polos).
     *
     * Cache de 1 resultado (10/11/2026): a busca faz ~720 contas de seno/cosseno e é chamada no desenho do fundo, no
     * protetor de tela e a cada clima novo, sempre para o mesmo dia e lugar. O par (dia, lugar) é a chave; mudou o dia
     * ou o lugar, calcula de novo. [fuso]: o da TV; o teste passa um fixo.
     */
    fun nascerEPor(ms: Long, lat: Double, lon: Double, fuso: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
        val c = Calendar.getInstance(fuso).apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val meiaNoite = c.timeInMillis
        ultimo?.let { if (it.meiaNoite == meiaNoite && it.lat == lat && it.lon == lon) return it.resultado }
        val resultado = calcularNascerEPor(meiaNoite, lat, lon)
        ultimo = Guardado(meiaNoite, lat, lon, resultado)   // um objeto só, trocado inteiro: seguro entre threads
        return resultado
    }

    private class Guardado(val meiaNoite: Long, val lat: Double, val lon: Double, val resultado: Pair<Long, Long>?)
    @Volatile private var ultimo: Guardado? = null

    /** Altura do disco + refração no nascer e no pôr (convenção do Almanaque Náutico). */
    private const val HORIZONTE = -0.833

    private fun calcularNascerEPor(meiaNoite: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val passo = 2 * 60_000L
        var nascer: Long? = null
        var por: Long? = null
        var anterior = alturaSol(meiaNoite, lat, lon)
        var t = meiaNoite + passo
        while (t <= meiaNoite + 86_400_000L) {
            val alt = alturaSol(t, lat, lon)
            if (anterior < HORIZONTE && alt >= HORIZONTE && nascer == null) nascer = afinar(t - passo, t, lat, lon, subindo = true)
            if (anterior >= HORIZONTE && alt < HORIZONTE) por = afinar(t - passo, t, lat, lon, subindo = false)
            anterior = alt
            t += passo
        }
        return if (nascer != null && por != null) nascer to por else null
    }

    /** Bisseção entre [a] e [b] (o sol cruza o horizonte entre os dois) até sobrar menos de 1 s. */
    private fun afinar(a0: Long, b0: Long, lat: Double, lon: Double, subindo: Boolean): Long {
        var a = a0; var b = b0
        while (b - a > 1000) {
            val m = (a + b) / 2
            val acima = alturaSol(m, lat, lon) >= HORIZONTE
            if (acima == subindo) b = m else a = m   // subindo: o cruzamento fica antes do 1º ponto acima
        }
        return b
    }

    /**
     * "É dia?" para o ÍCONE do clima — a regra única das telas (04/11/2026). A Open-Meteo marca noite no instante exato
     * do pôr do sol, sem o crepúsculo civil (fix de 03/11, ver CLAUDE.md); aqui é o nascer/pôr real do lugar com
     * [Config.MARGEM_CREPUSCULO_MIN] de margem só no pôr. Antes só a HOME usava isto e a Previsão ainda usava o is_day
     * da API: as duas telas podiam discordar por até 20 min. [isDayDaApi] é o fallback (perto dos polos).
     */
    fun eDia(ms: Long, lat: Double, lon: Double, isDayDaApi: Boolean): Boolean {
        val (nascer, por) = nascerEPor(ms, lat, lon) ?: return isDayDaApi
        return ms in nascer until (por + Config.MARGEM_CREPUSCULO_MIN * 60_000L)
    }
}

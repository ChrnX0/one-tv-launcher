package br.paulo.tvlauncher.screensaver

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * O que o protetor que reage ao tempo (caminho 2, escolhido por Paulo em 17/11/2026) precisa saber, SEM dizer de onde
 * veio. A fonte do clima do produto ainda não está decidida (Open-Meteo hoje; MET Norway em estudo até 24/11; nunca a
 * Weather API do Google): quem fornece é uma [FonteTempo]. Trocar de fonte = escrever outra implementação; a frase, as
 * curiosidades e as telas não mudam. Campo que a fonte não tem fica nulo ou vazio e a linha correspondente some.
 */
data class TempoAgora(
    val agora: Long,
    val lat: Double, val lon: Double,
    /** false = a fonte manda Fahrenheit (Config.USAR_FAHRENHEIT): os limites de calor/frio/geada se convertem. */
    val celsius: Boolean = true,
    val temp: Int? = null,
    /** Código WMO do céu de agora (0 limpo ... 99 trovoada com granizo). */
    val codigo: Int = 0,
    val chovendo: Boolean = false,
    val umidade: Int? = null,
    val max: Int? = null, val min: Int? = null,
    /** Hora a hora (temperatura, céu, chance de chuva), a partir da hora cheia de agora. */
    val horas: List<WidgetsTempo.Marca> = emptyList(),
    val amanhaMax: Int? = null, val amanhaMin: Int? = null, val amanhaChuva: Int? = null, val amanhaCodigo: Int? = null,
    /** Índice UV hora a hora (instante, índice). Vazio = a fonte não tem. */
    val uv: List<Pair<Long, Double>> = emptyList(),
    /** Qualidade do ar (índice americano, 0-500). null = a fonte não tem. */
    val aqi: Int? = null,
    /** Chuva em mm por bloco de 15 min (instante do começo do bloco, mm). Vazio = a fonte não tem (aí vale a hora a hora). */
    val chuva15: List<Pair<Long, Double>> = emptyList(),
)

/** Quem fornece o [TempoAgora]. Chamada fora da thread da tela; null = nada recente (o protetor cala a frase). */
fun interface FonteTempo {
    fun ler(agora: Long): TempoAgora?
}

/** Guardar e ler as séries extras do clima ("ms|valor;ms|valor") nas prefs, sem Android: testável. */
object SeriesTempo {
    fun escrever(pares: List<Pair<Long, Double>>): String =
        pares.joinToString(";") { (ms, v) -> "$ms|${(v * 100).roundToInt() / 100.0}" }

    fun ler(texto: String?): List<Pair<Long, Double>> = texto.orEmpty().split(';').mapNotNull { p ->
        val i = p.indexOf('|'); if (i <= 0) return@mapNotNull null
        val ms = p.substring(0, i).trim().toLongOrNull() ?: return@mapNotNull null
        val v = p.substring(i + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
        ms to v
    }.sortedBy { it.first }

    /** "2026-11-17T14:15" no fuso [fuso] -> ms. null se o texto vier quebrado. */
    fun msLocal(iso: String, fuso: TimeZone = TimeZone.getDefault()): Long? = try {
        val c = Calendar.getInstance(fuso).apply {
            clear()
            set(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt() - 1, iso.substring(8, 10).toInt(),
                iso.substring(11, 13).toInt(), iso.substring(14, 16).toInt())
        }
        c.timeInMillis
    } catch (e: Exception) { null }
}

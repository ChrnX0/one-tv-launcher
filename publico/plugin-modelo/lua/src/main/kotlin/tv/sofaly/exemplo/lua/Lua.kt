package tv.sofaly.exemplo.lua

import java.time.Instant
import kotlin.math.floor

/**
 * Fase da Lua calculada na TV, sem internet: conta o tempo desde uma Lua nova conhecida (6/1/2000 18:14 UTC) e divide
 * pelo mês sinódico (29,53 dias). Erro de algumas horas, suficiente para um cartão. Sem Android: fácil de testar.
 */
object Lua {
    private const val SINODICO = 29.530588853
    private val LUA_NOVA_REF: Instant = Instant.parse("2000-01-06T18:14:00Z")

    /** Idade da Lua em dias (0 = nova, ~14,8 = cheia). */
    fun idade(agora: Instant): Double {
        val dias = (agora.epochSecond - LUA_NOVA_REF.epochSecond) / 86400.0
        return dias - floor(dias / SINODICO) * SINODICO
    }

    enum class Fase(val emoji: String) { NOVA("🌑"), CRESCENTE("🌒"), QUARTO_CRESCENTE("🌓"), GIBOSA_CRESCENTE("🌔"),
        CHEIA("🌕"), GIBOSA_MINGUANTE("🌖"), QUARTO_MINGUANTE("🌗"), MINGUANTE("🌘") }

    /** Oito fases de ~3,7 dias, centradas nos pontos exatos (nova, quartos, cheia). */
    fun fase(idade: Double): Fase = Fase.values()[(floor(idade / SINODICO * 8 + 0.5).toInt()) % 8]

    /** Dias inteiros até a próxima Lua cheia (0 = hoje). */
    fun diasAteCheia(idade: Double): Int {
        val cheia = SINODICO / 2
        val falta = if (idade <= cheia) cheia - idade else SINODICO - idade + cheia
        return floor(falta).toInt()
    }
}

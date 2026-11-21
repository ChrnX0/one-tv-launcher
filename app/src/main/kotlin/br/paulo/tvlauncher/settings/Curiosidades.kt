package br.paulo.tvlauncher.settings

import br.paulo.tvlauncher.player.LinhasTempo
import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.weather.CeuNoturno
import br.paulo.tvlauncher.weather.Lua
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * A segunda linha do protetor (caminho 2, 17/11/2026): UMA curiosidade por vez, que troca sozinha. Só entra o que vale a
 * pena hoje (sol forte, ar ruim, Estação Espacial passando, chuva de meteoros, superlua, planeta alto). Decide o quê;
 * o texto em cada língua sai em [LinhasTempo]. Puro, testado em CuriosidadesTest.
 */
object Curiosidades {
    sealed class Item {
        /** Sol de agora: índice [valor] e o [nivel] da OMS (1 baixo .. 5 extremo). */
        data class UvAgora(val valor: Int, val nivel: Int) : Item()
        /** Sol de amanhã: pico [valor] e a faixa em que pede proteção (UV 3 ou mais), em ms. */
        data class UvAmanha(val valor: Int, val nivel: Int, val de: Long, val ate: Long) : Item()
        /** Qualidade do ar: índice e [nivel] (2 moderado .. 6 perigoso). */
        data class Ar(val aqi: Int, val nivel: Int) : Item()
        data class Estacao(val passagem: CeuNoturno.Passagem) : Item()
        data class Meteoros(val chuva: CeuNoturno.ChuvaMeteoros) : Item()
        data class EclipseSol(val ms: Long) : Item()
        /** Lua cheia esta noite; [superlua] = perto do perigeu. */
        data class LuaCheia(val superlua: Boolean) : Item()
        object LuaNova : Item()
        data class Planeta(val visivel: CeuNoturno.PlanetaVisivel) : Item()
    }

    /** Nível da OMS para o índice UV: 1 baixo (0-2), 2 moderado (3-5), 3 alto (6-7), 4 muito alto (8-10), 5 extremo (11+). */
    fun nivelUv(uv: Double): Int = when {
        uv < 2.5 -> 1; uv < 5.5 -> 2; uv < 7.5 -> 3; uv < 10.5 -> 4; else -> 5
    }

    /** Faixa do índice americano de qualidade do ar: 1 bom ... 6 perigoso. */
    fun nivelAr(aqi: Int): Int = when {
        aqi <= 50 -> 1; aqi <= 100 -> 2; aqi <= 150 -> 3; aqi <= 200 -> 4; aqi <= 300 -> 5; else -> 6
    }

    /**
     * A lista do dia, na ordem em que vai passar na tela (o que é raro ou pede ação primeiro).
     * [estacao]: a próxima passagem visível (já calculada fora, com o TLE); [eclipses]: começos dos eclipses do Sol.
     */
    fun montar(t: TempoAgora, estacao: CeuNoturno.Passagem? = null, eclipses: List<Long> = emptyList(),
        fuso: TimeZone = TimeZone.getDefault()): List<Item> {
        val itens = ArrayList<Item>()
        val hora = Calendar.getInstance(fuso).apply { timeInMillis = t.agora }.get(Calendar.HOUR_OF_DAY)
        // Estação nas próximas 12 h (mais longe que isso a pessoa esquece; e o TLE envelhece).
        estacao?.takeIf { it.inicio > t.agora && it.inicio - t.agora <= 12 * 3_600_000L }?.let { itens += Item.Estacao(it) }
        eclipses.firstOrNull { it > t.agora && it - t.agora <= 7 * 86_400_000L }?.let { itens += Item.EclipseSol(it) }
        CeuNoturno.meteoros(t.agora, t.lat, fuso)?.let { itens += Item.Meteoros(it) }
        uv(t, hora, fuso)?.let { itens += it }
        t.aqi?.takeIf { it > 50 }?.let { itens += Item.Ar(it, nivelAr(it)) }
        lua(t)?.let { itens += it }
        // Planetas só a partir da tarde (de manhã a pessoa não vai lembrar de olhar à noite) e com céu sem muita nuvem.
        if ((hora >= 15 || hora < 5) && t.codigo <= 2 && !t.chovendo) {
            CeuNoturno.planetas(t.agora, t.lat, t.lon, fuso).take(2).forEach { itens += Item.Planeta(it) }
        }
        return itens
    }

    private fun uv(t: TempoAgora, hora: Int, fuso: TimeZone): Item? {
        if (t.uv.isEmpty()) return null
        if (hora in 8..15) {
            // Agora: o valor da hora cheia em que estamos.
            val agora = t.uv.lastOrNull { it.first <= t.agora } ?: return null
            if (agora.second < 2.5) return null
            return Item.UvAgora(agora.second.roundToInt(), nivelUv(agora.second))
        }
        if (hora < 15) return null
        // Tarde e noite: o de amanhã, se pede cuidado (alto ou mais).
        val amanha = Calendar.getInstance(fuso).apply {
            timeInMillis = t.agora; add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dia = t.uv.filter { it.first >= amanha && it.first < amanha + 86_400_000L }
        val pico = dia.maxByOrNull { it.second } ?: return null
        if (pico.second < 5.5) return null
        val faixa = dia.filter { it.second >= 2.5 }
        return Item.UvAmanha(pico.second.roundToInt(), nivelUv(pico.second), faixa.first().first, faixa.last().first + 3_600_000L)
    }

    private fun lua(t: TempoAgora): Item? {
        val idade = Lua.idade(t.agora)
        val metade = 29.530588853 / 2
        return when {
            kotlin.math.abs(idade - metade) <= 1.0 -> Item.LuaCheia(CeuNoturno.distanciaLua(t.agora) < CeuNoturno.SUPERLUA_KM)
            idade <= 1.0 || idade >= 28.5 -> Item.LuaNova
            else -> null
        }
    }
}

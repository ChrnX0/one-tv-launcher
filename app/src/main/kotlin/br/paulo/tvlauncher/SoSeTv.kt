package br.paulo.tvlauncher

import android.content.Context
import br.paulo.tvlauncher.alarm.FeriadosTv
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.weather.Astronomia
import br.paulo.tvlauncher.weather.Localizacao

/** O estado da TV para o "só se" (elevação 10.5; regra em SoSe.kt). Pode ler disco: fora da thread da tela. */
object SoSeTv {
    /** A presença vista há mais que isso já não diz se tem alguém agora. */
    private const val PRESENCA_VALE_MS = 30 * 60_000L

    fun estado(ctx: Context): SoSe.Estado {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val sol = sol(app, agora)
        return SoSe.Estado(agora, emCasa(app, agora), sol?.first, sol?.second, SoSe.diaDaSemana(agora),
            feriado = runCatching { FeriadosTv.hoje(app, agora) }.getOrDefault(false),
            perfil = runCatching { Perfil.ativa(app) }.getOrNull())
    }

    /** Alguém em casa, pela última conferência da presença (Presenca.kt). null = não sei. */
    fun emCasa(ctx: Context, agora: Long): Boolean? {
        val p = ctx.getSharedPreferences(Prefs.PRESENCA, Context.MODE_PRIVATE)
        if (!p.contains("em_casa") || agora - p.getLong("em_casa_em", 0) > PRESENCA_VALE_MS) return null
        return p.getBoolean("em_casa", false)
    }

    /** Nascer e pôr do sol de hoje, pela localização que a TV já tem (sem rede). */
    fun sol(ctx: Context, agora: Long): Pair<Long, Long>? = runCatching {
        val pt = Localizacao.atual(ctx) { null } ?: return null
        Astronomia.nascerEPor(agora, pt.lat, pt.lon)
    }.getOrNull()
}

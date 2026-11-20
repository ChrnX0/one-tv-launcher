package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * Modo túnel dos players (proposta C, item 12, 20/11/2026), com volta automática: se o túnel falha neste aparelho, a
 * marca fica guardada e os próximos vídeos já abrem no modo comum. Uma atualização do launcher dá nova chance (o motivo
 * pode ter sido corrigido), porque a marca guarda o versionCode em que falhou.
 */
object Tunel {
    private const val PREFS = "player"
    private const val CHAVE = "tunel_falhou_em"

    fun permitido(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CHAVE, 0L) != BuildConfig.VERSION_CODE.toLong()

    fun desistir(ctx: Context) {
        Log.w("TvLauncher.Vod", "túnel desligado neste aparelho até a próxima versão")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CHAVE, BuildConfig.VERSION_CODE.toLong()).apply()
    }
}

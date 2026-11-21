package br.paulo.tvlauncher.mcp

import android.content.Context
import android.os.Build
import android.util.Log
import br.paulo.tvlauncher.Prefs

/**
 * Modo túnel dos players (proposta C, item 12, 20/11/2026), com volta automática: se o túnel falha neste aparelho, a
 * marca fica guardada e os próximos vídeos já abrem no modo comum.
 *
 * A marca guarda a versão do SISTEMA da TV (Build.FINGERPRINT), não a do launcher: na Sala o túnel prendeu no primeiro
 * pulo (defeito do decodificador do aparelho), e tentar de novo a cada versão do launcher seria uma travada de 8 s por
 * versão. Só uma atualização do sistema (que pode trazer o decodificador consertado) dá nova chance.
 */
object Tunel {
    private const val PREFS = Prefs.PLAYER
    private const val CHAVE = "tunel_falhou_sistema"

    fun permitido(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE, null) != Build.FINGERPRINT

    fun desistir(ctx: Context) {
        Log.w("TvLauncher.Vod", "túnel desligado neste aparelho até o sistema da TV atualizar")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE, Build.FINGERPRINT).apply()
    }
}

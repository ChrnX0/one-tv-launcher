package br.paulo.tvlauncher

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Limpeza que sobrevive à atualização do sistema (10/11/2026, ideia H; li no tv-remote: "After a firmware update: turns
 * off again what the update turned back on").
 *
 * No boot, se a impressão digital do sistema (Build.FINGERPRINT) mudou desde o último boot, a TV foi atualizada. Aí,
 * para cada app que a limpeza ([Debloat]) ocultou, confere se continua oculto e oculta de novo o que voltou. Só mexe
 * no que está em [Debloat.ocultosPorAqui] (o que o dono escolheu) e respeita o [Debloat.NUNCA] via [Debloat.definir].
 *
 * Não verificado: se uma OTA do Google TV desfaz o setApplicationHidden. Se não desfizer, isto roda e não acha nada.
 */
object DebloatPosOta {
    private const val TAG = "TvLauncher.DebloatOta"
    private const val CHAVE = "ultima_impressao_sistema"

    /** Regra pura: precisa conferir? (primeiro boot só grava a impressão, não reaplica nada). */
    fun sistemaMudou(anterior: String, atual: String): Boolean = anterior.isNotEmpty() && anterior != atual

    /** Chamado pelo BootReceiver, fora da thread da tela. Devolve quantos apps ocultou de novo. */
    fun talvezReaplicar(ctx: Context): Int {
        val anterior = Extras.texto(ctx, CHAVE)
        val atual = Build.FINGERPRINT ?: ""
        Extras.definirTexto(ctx, CHAVE, atual)
        if (!sistemaMudou(anterior, atual) || !Extras.ligado(ctx, Extras.Chave.DEBLOAT_POS_OTA)) return 0
        var n = 0
        for (p in Debloat.ocultosPorAqui(ctx)) {
            if (!PrivilegioDO.estaOculto(ctx, p) && Debloat.definir(ctx, p, true)) n++
        }
        Log.i(TAG, "sistema atualizado ($anterior -> $atual): $n app(s) ocultado(s) de novo")
        return n
    }
}

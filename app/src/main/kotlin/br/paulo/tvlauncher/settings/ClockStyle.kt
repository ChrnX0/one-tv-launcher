package br.paulo.tvlauncher.settings

import android.content.Context
import br.paulo.tvlauncher.DesignTokens

/**
 * Formato e cor da hora (21/11/2026, item 1 do plano para passar o Projectivy: "Ajuste fino").
 *
 * Porquê: a hora estava fixa em "HH:mm" branca em 4 lugares (barra, ilha, relógio grande, moldura das listas).
 * Para quê: quem lê melhor "3:40" que "15:40" escolhe; quem quer a hora na cor da marca, também.
 * Como: uma escolha só, lida pelo próprio RelogioTela (todas as horas do launcher passam por ele), então nenhuma tela
 * precisa saber disto. "Automático" segue o 12/24 h do Android. Liga com: o protetor de tela (usa o RelogioTela da
 * barra) e o QR do visual.
 */
object ClockStyle {
    const val KEY_FORMAT = "clock_format"
    const val KEY_COLOR = "clock_color"

    enum class Format { AUTO, H24, H12, H12_AMPM, SECONDS }
    enum class Color(val argb: Int?) {
        DEFAULT(null), ORANGE(DesignTokens.ORANGE), CREAM(DesignTokens.CREAM), AMBER(DesignTokens.ATTENTION), TEAL(DesignTokens.OK)
    }

    fun format(ctx: Context) = Format.entries.getOrElse(Extras.numero(ctx, KEY_FORMAT, 0)) { Format.AUTO }
    fun color(ctx: Context) = Color.entries.getOrElse(Extras.numero(ctx, KEY_COLOR, 0)) { Color.DEFAULT }
    fun nextFormat(ctx: Context) = Format.entries[(format(ctx).ordinal + 1) % Format.entries.size].also {
        Extras.definirNumero(ctx, KEY_FORMAT, it.ordinal)
    }
    fun nextColor(ctx: Context) = Color.entries[(color(ctx).ordinal + 1) % Color.entries.size].also {
        Extras.definirNumero(ctx, KEY_COLOR, it.ordinal)
    }

    /** O padrão do TextClock para [f]; [sistema24] = o Android está em 24 h. Puro (ClockStyleTest). */
    fun pattern(f: Format, sistema24: Boolean): String = when (f) {
        Format.AUTO -> if (sistema24) "HH:mm" else "h:mm"
        Format.H24 -> "HH:mm"
        Format.H12 -> "h:mm"
        Format.H12_AMPM -> "h:mm a"
        Format.SECONDS -> if (sistema24) "HH:mm:ss" else "h:mm:ss"
    }

    fun pattern(ctx: Context) = pattern(format(ctx), android.text.format.DateFormat.is24HourFormat(ctx))
}

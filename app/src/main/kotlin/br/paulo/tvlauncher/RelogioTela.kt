package br.paulo.tvlauncher

import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.widget.TextClock

/**
 * Relógio e data do sistema de design (10/11/2026), iguais aos do protetor: a hora com a hora cheia em Manrope 600, os
 * dois-pontos apagados e o minuto fino (200); a data com a primeira letra maiúscula ("Terça-feira, 10 de novembro").
 *
 * O TextClock do Android atualiza sozinho a cada minuto chamando setText; aqui a gente só "veste" o texto que ele manda.
 */
class RelogioTela(ctx: Context, private val data: Boolean) : TextClock(ctx) {
    override fun setText(texto: CharSequence?, tipo: BufferType?) {
        if (texto == null || texto.isEmpty()) return super.setText(texto, tipo)
        if (data) return super.setText(texto.toString().replaceFirstChar { it.titlecase() }, tipo)
        val s = texto.toString()
        val i = s.indexOf(':')
        if (i < 0 || Build.VERSION.SDK_INT < 28) return super.setText(texto, tipo)
        val sb = SpannableStringBuilder()
        sb.append(s.substring(0, i)); sb.setSpan(TypefaceSpan(Fontes.manrope(600)), 0, sb.length, 0)
        val ini = sb.length
        sb.append(" : "); sb.setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), ini, sb.length, 0)
        val iniMin = sb.length
        sb.append(s.substring(i + 1)); sb.setSpan(TypefaceSpan(Fontes.manrope(200)), iniMin, sb.length, 0)
        super.setText(sb, BufferType.SPANNABLE)
    }
}

package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.FonteVidro
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.screensaver.WidgetsProtetor
import br.paulo.tvlauncher.screensaver.WidgetsTempo
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Os três estilos dos widgets de baixo do protetor (15/11/2026) desenhados de verdade, numa TV de 960x540 dp, em
 * inglês e em português, com o tempo mais comprido que cabe (chuva, 5 horas, amanhã). Salva a foto em
 * app/build/telas/widget-<língua>-<estilo>.png e falha se alguma letra ficar abaixo de 12 sp, se um texto sair cortado
 * (reticências) ou por cima de outro. Roda com -Ptelas, como o [TelasTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class WidgetsProtetorTelaTest {
    private val saida = File("build/telas")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun dados(): WidgetsProtetor.Dados {
        val hora = 60 * 60_000L
        val agora = 1_763_168_000_000L   // um sábado à noite; o valor exato não importa
        val chuva = listOf(80, 70, 60, 40, 20, 10, 5, 0, 0, 0, 0, 0)
        val horas = chuva.mapIndexed { i, c -> WidgetsTempo.Marca(agora + (i + 1) * hora, 21 - i % 3, if (c >= 60) 61 else 3, c) }
        return WidgetsProtetor.Dados(
            agora = agora, temClima = true, temp = 22, codigo = 61, chovendo = true, eDia = false, max = 27, min = 19,
            horas = horas, marcas = WidgetsTempo.deDuasEmDuas(horas, agora), eDiaEm = { false },
            nascerPor = (agora - 16 * hora) to (agora - 3 * hora), solNaCurva = (agora + 7 * hora + 20 * 60_000L) to true,
            luaFase = "Waxing crescent", luaFracao = 0.2, sul = true, umidade = 91, chuvaHoje = 80,
            amanha = WidgetsProtetor.Amanha(29, 18, 30, 2),
        )
    }

    private fun conferir(idioma: String) {
        saida.mkdirs()
        val problemas = mutableListOf<String>()
        for (estilo in Protetor.WIDGETS) {
            val a = Robolectric.buildActivity(Activity::class.java).setup().get()
            val raiz = FrameLayout(a).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF05070B.toInt(), 0xFF0B1220.toInt()))
            }
            val d = a.resources.displayMetrics.density
            val w = WidgetsProtetor(a, FonteVidro()).montar(estilo, dados())
            raiz.addView(w, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = (Estilo.MARGEM_LATERAL * d).toInt(); rightMargin = leftMargin; bottomMargin = (Estilo.MARGEM_VERTICAL * d).toInt()
            })
            a.setContentView(raiz)
            val decor = a.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1920, 1080)
            val foto = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(foto))
            File(saida, "widget-$idioma-$estilo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
            val textos = mutableListOf<Pair<TextView, Rect>>()
            fun andar(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v is TextView && v.text.isNotBlank() && v.width > 0) {
                    val r = Rect(); if (v.getGlobalVisibleRect(r)) textos += v to r
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
            }
            andar(w)
            for ((t, _) in textos) {
                val sp = t.textSize / escala
                if (sp < 11.95f) problemas += "$idioma/$estilo: \"${t.text}\" com ${"%.1f".format(sp)} sp"
                val l = t.layout
                if (l != null && (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) problemas += "$idioma/$estilo: cortado \"${t.text}\""
            }
            for (i in textos.indices) for (j in i + 1 until textos.size) {
                val x = Rect()
                if (x.setIntersect(textos[i].second, textos[j].second) && x.width() > 3 && x.height() > 3)
                    problemas += "$idioma/$estilo: \"${textos[i].first.text}\" por cima de \"${textos[j].first.text}\""
            }
            if (w.height > 300 * d) problemas += "$idioma/$estilo: alto demais (${(w.height / d).toInt()} dp)"
            a.finish()
        }
        if (problemas.isNotEmpty()) fail("Widgets do protetor ($idioma):\n" + problemas.joinToString("\n"))
    }
}

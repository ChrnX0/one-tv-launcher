package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import br.paulo.tvlauncher.home.EstadoCeu
import br.paulo.tvlauncher.player.LinhasTempo
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.settings.Curiosidades
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.weather.CeuNoturno
import br.paulo.tvlauncher.weather.DesenhoCeu
import br.paulo.tvlauncher.weather.FraseTempo
import java.io.File
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Protetor que reage ao tempo (caminho 2, 17/11/2026): a frase e CADA curiosidade desenhadas de verdade sobre o fundo
 * "Céu de verdade" (mapa embutido, nuvens desenhadas: o teste não tem rede), numa TV de 960x540 dp, nas línguas de
 * texto mais comprido. Salva app/build/telas/ceu-<língua>-<n>.png e falha se uma letra ficar abaixo de 14 sp, se um
 * texto sair cortado ou se a linha invadir a coluna das notificações (a partir de 678 dp). Roda com -Ptelas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ProtetorTempoTelaTest {
    private val saida = File("build/telas")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi") @Test fun ingles() = conferir("en")
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi") @Test fun portugues() = conferir("pt")
    @Config(qualifiers = "de-w960dp-h540dp-land-television-xhdpi") @Test fun alemao() = conferir("de")
    @Config(qualifiers = "fr-w960dp-h540dp-land-television-xhdpi") @Test fun frances() = conferir("fr")
    @Config(qualifiers = "ru-w960dp-h540dp-land-television-xhdpi") @Test fun russo() = conferir("ru")

    private val h = 3_600_000L
    private val agora = 1794949200000L   // 17/11/2026 21:00 em São Paulo

    /** As frases mais compridas e todas as curiosidades, cada uma numa foto. */
    private fun casos(): List<Pair<FraseTempo.Frase, Curiosidades.Item>> {
        val frases = listOf(FraseTempo.Frase.ParaEmMinutos(25), FraseTempo.Frase.CalorForte(36), FraseTempo.Frase.Geada(true),
            FraseTempo.Frase.CeuLimpo, FraseTempo.Frase.AmanhaChove(80), FraseTempo.Frase.NoiteFria(8))
        val itens = listOf(
            Curiosidades.Item.UvAmanha(10, 4, agora + 13 * h, agora + 19 * h),
            Curiosidades.Item.Ar(130, 3), Curiosidades.Item.Ar(250, 5),
            Curiosidades.Item.Estacao(CeuNoturno.Passagem(agora + 7 * h, agora + 7 * h + 300_000, 6, 2, 60)),
            Curiosidades.Item.Meteoros(CeuNoturno.ChuvaMeteoros.DELTA_AQUARIDAS),
            Curiosidades.Item.LuaCheia(true), Curiosidades.Item.LuaNova,
            Curiosidades.Item.Planeta(CeuNoturno.PlanetaVisivel(CeuNoturno.Planeta.JUPITER, agora + 2 * h, 5, 60)),
            Curiosidades.Item.UvAgora(11, 5), Curiosidades.Item.EclipseSol(agora + 3 * 86_400_000L))
        return itens.mapIndexed { i, it -> frases[i % frases.size] to it }
    }

    private fun conferir(idioma: String) {
        saida.mkdirs()
        val problemas = mutableListOf<String>()
        val a0 = Robolectric.buildActivity(Activity::class.java).setup().get()
        val fundo = DesenhoCeu.imagem(a0, 960, 540, EstadoCeu(agora, -23.55, -46.63, 2, false), comSatelite = false)
        casos().forEachIndexed { n, (frase, item) ->
            val a = Robolectric.buildActivity(Activity::class.java).setup().get()
            val d = a.resources.displayMetrics.density
            val raiz = FrameLayout(a)
            raiz.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(fundo) }, FrameLayout.LayoutParams(-1, -1))
            val linhas = LinhasTempo(a, Fontes.nunito(a, 400), Fontes.nunito(a, 600))
            // Mesmo lugar do protetor: 36 + 6 dp da esquerda, embaixo da data (~230 dp do topo).
            raiz.addView(linhas, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = (42 * d).toInt(); topMargin = (230 * d).toInt()
            })
            linhas.mostrar(TempoAgora(agora, -23.55, -46.63), frase, listOf(item, Curiosidades.Item.LuaNova), null)
            a.setContentView(raiz)
            val decor = a.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1920, 1080)
            val foto = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(foto))
            File(saida, "ceu-$idioma-$n.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
            fun andar(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v is TextView && v.text.isNotBlank()) {
                    val sp = v.textSize / escala
                    if (sp < 13.95f) problemas += "$idioma/$n: \"${v.text}\" com ${"%.1f".format(sp)} sp"
                    val l = v.layout
                    if (l != null && (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) problemas += "$idioma/$n: cortado \"${v.text}\""
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
            }
            andar(linhas)
            val r = Rect(); linhas.getGlobalVisibleRect(r)
            if (r.right > 678 * d) problemas += "$idioma/$n: linha vai até ${(r.right / d).toInt()} dp (notificações começam em 678)"
            a.finish()
        }
        if (problemas.isNotEmpty()) fail("Protetor do tempo ($idioma):\n" + problemas.joinToString("\n"))
    }
}

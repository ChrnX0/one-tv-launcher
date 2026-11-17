package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.CruzLogica
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.HomeCruz
import br.paulo.tvlauncher.IconeCruz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tela inicial em cruz (17/11/2026): desenha a [HomeCruz] com os itens da amostra aprovada, anda com as setas como o
 * controle e tira foto de cada parada (app/build/telas/cruz-*.png), nos dois estilos de ícone. Confere o que a foto não
 * mostra sozinha: o foco fica na cruz, o item em foco é o esperado depois das teclas da tabela, e o OK abre o item certo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class CruzTelaTest {
    private val saida = File("build/telas")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun objeto() = conferir(IconeCruz.Estilo.OBJETO, "objeto")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun simbolo() = conferir(IconeCruz.Estilo.SIMBOLO, "simbolo")

    private fun conferir(estilo: IconeCruz.Estilo, nome: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = FrameLayout(a).apply { setBackgroundColor(Estilo.FUNDO) }
        a.setContentView(raiz)
        val cruz = HomeCruz(a, Typeface.DEFAULT, Typeface.DEFAULT_BOLD)
        raiz.addView(cruz, FrameLayout.LayoutParams(-1, -1))
        val abertos = mutableListOf<String>()
        fun ic(t: IconeCruz.Tipo) = IconeCruz(t, estilo)
        fun it(id: String, n: String, sub: String, t: IconeCruz.Tipo) = HomeCruz.Item(id, n, sub, ic(t), { abertos += id })
        cruz.definir(listOf(
            HomeCruz.Categoria(CruzLogica.Categoria.CONTINUAR, "Continuar", ic(IconeCruz.Tipo.CONTINUAR), listOf(
                it("c:serie", "O Último Farol", "T1 E4 · faltam 18 min", IconeCruz.Tipo.SERIES),
                it("c:filme", "Maré Alta", "Filme · faltam 52 min", IconeCruz.Tipo.FILMES))),
            HomeCruz.Categoria(CruzLogica.Categoria.ASSISTIR, "Assistir", ic(IconeCruz.Tipo.ASSISTIR), listOf(
                it("tv", "TV", "Canal 5 · Jornal da Noite", IconeCruz.Tipo.TV),
                it("series", "Séries", "O Último Farol · T1 E4", IconeCruz.Tipo.SERIES),
                it("filmes", "Filmes", "Maré Alta · faltam 52 min", IconeCruz.Tipo.FILMES),
                it("rede", "Na rede", "Vídeos, músicas e fotos da rede da casa", IconeCruz.Tipo.REDE),
                it("addons", "Addons", "Filmes e séries dos seus addons", IconeCruz.Tipo.ADDONS))),
            HomeCruz.Categoria(CruzLogica.Categoria.OUVIR, "Ouvir", ic(IconeCruz.Tipo.OUVIR), listOf(
                it("radio", "Rádio", "Rádios do mundo inteiro", IconeCruz.Tipo.RADIO),
                it("podcasts", "Podcasts", "Café com Ciência", IconeCruz.Tipo.PODCASTS))),
            HomeCruz.Categoria(CruzLogica.Categoria.JOGAR, "Jogar", ic(IconeCruz.Tipo.JOGAR), listOf(
                it("jogos", "Jogos", "3 jogos · 1 controle ligado", IconeCruz.Tipo.JOGOS),
                it("sofa", "Jogos de sofá", "2 a 4 pessoas", IconeCruz.Tipo.SOFA))),
            HomeCruz.Categoria(CruzLogica.Categoria.APLICATIVOS, "Aplicativos", ic(IconeCruz.Tipo.APLICATIVOS), listOf(
                it(CruzLogica.ID_TODOS_APPS, "Todos os apps", "Ver, mover e esconder os apps", IconeCruz.Tipo.TODOS_APPS))),
        ))
        cruz.requestFocus()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue("a cruz tem o foco", cruz.isFocused)
        foto(a, "cruz-$nome-1-assistir-tv")

        fun tecla(k: Int) {
            cruz.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); cruz.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        }
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("series", cruz.itemAtual()?.id)
        foto(a, "cruz-$nome-2-assistir-series")
        tecla(KeyEvent.KEYCODE_DPAD_DOWN); tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        foto(a, "cruz-$nome-3-assistir-na-rede")
        tecla(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("radio", cruz.itemAtual()?.id)
        foto(a, "cruz-$nome-4-ouvir")
        tecla(KeyEvent.KEYCODE_DPAD_RIGHT); tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("sofa", cruz.itemAtual()?.id)
        foto(a, "cruz-$nome-5-jogar-sofa")
        tecla(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("sofa"), abertos)
        tecla(KeyEvent.KEYCODE_DPAD_LEFT); tecla(KeyEvent.KEYCODE_DPAD_LEFT); tecla(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(CruzLogica.Categoria.CONTINUAR, cruz.categoriaAtual()?.cat)
        foto(a, "cruz-$nome-6-continuar")
        // ▲ no primeiro item devolve a tecla (o Android leva o foco para a barra de cima)
        assertTrue(!cruz.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)))
        cruz.home()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals(CruzLogica.Categoria.ASSISTIR, cruz.categoriaAtual()?.cat)
        a.finish()
    }

    private fun foto(a: Activity, arquivo: String) {
        val raiz: View = a.window.decorView
        val bmp = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(bmp))
        File(saida, "$arquivo.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

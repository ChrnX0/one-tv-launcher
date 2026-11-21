package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.HomeCruz
import br.paulo.tvlauncher.home.IconeCruz
import java.io.File
import java.util.concurrent.TimeUnit
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

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun duotom() = conferir(IconeCruz.Estilo.DUOTOM, "duotom")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun linha() = conferir(IconeCruz.Estilo.LINHA, "linha")

    /**
     * Uma linha embaixo do nome e o resto no canto de baixo à direita (Paulo, 18/11/2026 12:15 local). A situação do
     * item ([HomeCruz.Item.situacao], o programa no ar) abre os detalhes e não aparece de novo embaixo.
     */
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun umaLinhaEDetalhesNoCanto() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = FrameLayout(a).apply { setBackgroundColor(Estilo.FUNDO) }
        a.setContentView(raiz)
        val cruz = HomeCruz(a, Typeface.DEFAULT, Typeface.DEFAULT_BOLD)
        raiz.addView(cruz, FrameLayout.LayoutParams(-1, -1))
        fun ic(t: IconeCruz.Tipo) = IconeCruz(t, IconeCruz.Estilo.LINHA)
        val comprida = "Por último: Coração Selvagem | Trailer Oficial | LEG | Paramount Pictures Brasil · Paramount Brasil"
        val tv = "Canal 2 · Globo SP\nMil Lugares para Conhecer Antes de Morrer · faltam 25 min\nDepois, 09:30: Encontro"
        cruz.definir(listOf(HomeCruz.Categoria(CruzLogica.Categoria.ASSISTIR, "Assistir", ic(IconeCruz.Tipo.ASSISTIR), listOf(
            HomeCruz.Item("app:yt", "YouTube", "$comprida\nVídeos e canais\nAberto há 12 min", ic(IconeCruz.Tipo.APLICATIVOS), {}),
            HomeCruz.Item("tv", "TV", tv, ic(IconeCruz.Tipo.TV), {},
                situacao = CruzLogica.Situacao("No ar", "Mil Lugares para Conhecer Antes de Morrer · faltam 25 min", "tv"))))))
        cruz.requestFocus()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        val sub = cruz.subAtual()!!
        assertEquals("uma informação em até 2 linhas", HomeCruz.ITEM_LINHAS, sub.lineCount)
        assertEquals(comprida, sub.text.toString())   // o "…" é do TextView (ellipsize), o texto fica inteiro
        // a principal não coube: vai inteira para o canto, seguida das outras
        assertEquals("$comprida\nVídeos e canais\nAberto há 12 min", cruz.detalheAtual().text.toString())
        assertEquals(1f, cruz.detalheAtual().alpha)
        foto(a, "cruz-linha-comprida")
        cruz.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)); cruz.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("some na hora ao andar", 0f, cruz.detalheAtual().alpha)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals("Canal 2 · Globo SP", cruz.subAtual()!!.text.toString())
        assertEquals("a situação abre os detalhes, sem repetir", "No ar  Mil Lugares para Conhecer Antes de Morrer · faltam 25 min\nDepois, 09:30: Encontro", cruz.detalheAtual().text.toString())
        foto(a, "cruz-tv-detalhes")
    }

    /** Nome que não cabe ("What universal fre…", Continuar na Sala, 18/11/2026): aparece inteiro no topo do canto. */
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun nomeCortadoVaiInteiroParaOCanto() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = FrameLayout(a).apply { setBackgroundColor(Estilo.FUNDO) }
        a.setContentView(raiz)
        val cruz = HomeCruz(a, Typeface.DEFAULT, Typeface.DEFAULT_BOLD)
        raiz.addView(cruz, FrameLayout.LayoutParams(-1, -1))
        val nome = "What universal free childcare is costing New Mexico"
        cruz.definir(listOf(HomeCruz.Categoria(CruzLogica.Categoria.CONTINUAR, "Continuar", IconeCruz(IconeCruz.Tipo.ASSISTIR, IconeCruz.Estilo.LINHA), listOf(
            HomeCruz.Item("c:podcast", nome, "Up First from NPR\nTocou há 1 h", IconeCruz(IconeCruz.Tipo.PODCASTS, IconeCruz.Estilo.LINHA), {},
                situacao = CruzLogica.Situacao("Parou em", "3% · faltam 28 min", "c:podcast"))))))
        cruz.requestFocus()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals("$nome\nParou em  3% · faltam 28 min\nTocou há 1 h", cruz.detalheAtual().text.toString())
    }

    /** Prévia da animação de cada ícone Linha (18/11/2026): uma linha por ícone, o estático e 8 quadros da passada. */
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun animacaoDosIcones() {
        saida.mkdirs()
        val tipos = IconeCruz.Tipo.entries
        val lado = 96; val fases = listOf(-1f, 0.12f, 0.25f, 0.37f, 0.5f, 0.62f, 0.75f, 0.87f)
        val bmp = Bitmap.createBitmap(lado * fases.size, lado * tipos.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Estilo.FUNDO)
        for ((i, t) in tipos.withIndex()) for ((j, f) in fases.withIndex()) {
            val ic = IconeCruz(t, IconeCruz.Estilo.LINHA).apply { faseFixa = f; setBounds(j * lado + 12, i * lado + 12, (j + 1) * lado - 12, (i + 1) * lado - 12) }
            ic.draw(c)
        }
        File(saida, "cruz-icones-animacao.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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
        fun it(id: String, n: String, sub: String, t: IconeCruz.Tipo, visto: Float? = null) =
            HomeCruz.Item(id, n, sub, ic(t), { abertos += id }, progresso = visto)
        cruz.definir(listOf(
            HomeCruz.Categoria(CruzLogica.Categoria.CONTINUAR, "Continuar", ic(IconeCruz.Tipo.CONTINUAR), listOf(
                it("c:serie", "Série", "O Último Farol\nT1 E4 · faltam 18 min", IconeCruz.Tipo.SERIES, visto = 0.6f),
                it("c:filme", "Filme", "Maré Alta\nfaltam 52 min", IconeCruz.Tipo.FILMES, visto = 0.45f),
                it("c:midia:netflix", "Netflix", "Cidade Submersa\nT2 E3", IconeCruz.Tipo.CONTINUAR, visto = 0.3f),
                it("c:podcast", "Podcast", "Os vulcões do Pacífico\nCafé com Ciência\nfaltam 21 min", IconeCruz.Tipo.PODCASTS, visto = 0.5f),
                it("c:radio", "Rádio", "Rádio Mar Azul FM", IconeCruz.Tipo.RADIO),
                it("c:dormir", "Dormir em 30 min", "A tela escurece devagar e a TV desliga", IconeCruz.Tipo.DORMIR))),
            HomeCruz.Categoria(CruzLogica.Categoria.ASSISTIR, "Assistir", ic(IconeCruz.Tipo.ASSISTIR), listOf(
                it("tv", "TV", "Canal 156 · Discovery Channel\nMil Lugares para Conhecer Antes de Morrer · faltam 25 min\nDepois, 21:30: Aventura Selvagem", IconeCruz.Tipo.TV, visto = 0.7f),
                it("series", "Séries", "O Último Farol\nT1 E4 · faltam 18 min\n2023 · ★ 8,1\nDrama", IconeCruz.Tipo.SERIES),
                it("filmes", "Filmes", "Maré Alta\n2021 · 2 h 35 min · ★ 8,0\nFicção científica\nfaltam 52 min", IconeCruz.Tipo.FILMES),
                it("rede", "Do computador", "Seus vídeos, músicas e fotos", IconeCruz.Tipo.REDE),
                it("addons", "Addons", "Filmes e séries dos seus addons", IconeCruz.Tipo.ADDONS))),
            HomeCruz.Categoria(CruzLogica.Categoria.OUVIR, "Ouvir", ic(IconeCruz.Tipo.OUVIR), listOf(
                it("radio", "Rádio", "Rádios do mundo inteiro", IconeCruz.Tipo.RADIO),
                it("podcasts", "Podcasts", "Café com Ciência", IconeCruz.Tipo.PODCASTS),
                it("app:musica", "Música do Sofá", "Movido para Ouvir", IconeCruz.Tipo.APLICATIVOS))),
            HomeCruz.Categoria(CruzLogica.Categoria.JOGAR, "Jogar", ic(IconeCruz.Tipo.JOGAR), listOf(
                it("central", "Central de jogos", "3 na voz · Geral", IconeCruz.Tipo.CENTRAL),
                it("app:corrida", "Asfalto Veloz", "Corrida", IconeCruz.Tipo.JOGOS),
                it("app:xadrez", "Xadrez", "Tabuleiro", IconeCruz.Tipo.JOGOS))),
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
        assertEquals("app:corrida", cruz.itemAtual()?.id)
        foto(a, "cruz-$nome-5-jogar")
        tecla(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("app:corrida"), abertos)
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

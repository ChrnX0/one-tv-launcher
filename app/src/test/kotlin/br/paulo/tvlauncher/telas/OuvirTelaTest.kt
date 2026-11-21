package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.CruzOuvir
import br.paulo.tvlauncher.listen.IconeOuvir
import br.paulo.tvlauncher.player.BarraTempo
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
 * Telas Rádio e Podcasts em cruz (18/11/2026): desenha a [CruzOuvir] com rádios e episódios de exemplo (sem rede: as
 * capas ficam nas iniciais), anda com as setas como o controle e tira foto de cada parada (app/build/telas/ouvir-*.png).
 * Confere: o foco fica na cruz, o item em foco é o esperado depois das teclas, OK toca o item certo, OK segurado abre as
 * opções, e nenhuma letra pequena, texto cortado ou texto por cima de outro ([MedirTela]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class OuvirTelaTest {
    private val saida = File("build/telas")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun radioPt() = radio("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun radioEn() = radio("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun podcastsPt() = podcasts("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun podcastsEn() = podcasts("en")

    /** Prévia do gesto de cada ícone (uma linha por ícone: o estático e 7 quadros da passada). */
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun icones() {
        saida.mkdirs()
        val tipos = IconeOuvir.Tipo.entries
        val lado = 96; val fases = listOf(-1f, 0.12f, 0.25f, 0.37f, 0.5f, 0.62f, 0.75f, 0.87f)
        val bmp = Bitmap.createBitmap(lado * fases.size, lado * tipos.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Estilo.FUNDO)
        for ((i, t) in tipos.withIndex()) for ((j, f) in fases.withIndex()) {
            IconeOuvir(t).apply { faseFixa = f; setBounds(j * lado + 12, i * lado + 12, (j + 1) * lado - 12, (i + 1) * lado - 12) }.draw(c)
        }
        File(saida, "ouvir-icones.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Na TV, uma view com clipToOutline e contorno vazio não desenha nada: os ícones de ação sumiram na Sala (18/11/2026)
     * e o Robolectric, que não recorta pelo contorno, desenhava normal. A capa recorta à mão; isto garante que continue.
     */
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun capaNaoRecortaPeloContorno() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val capa = CapaOuvir(a).apply { icone = IconeOuvir(IconeOuvir.Tipo.FAVORITAS) }
        assertTrue("clipToOutline com ícone apaga o ícone na TV", !capa.clipToOutline)
    }

    private fun montar(): Pair<Activity, CruzOuvir> {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = FrameLayout(a).apply { background = Estilo.fundoTela(a) }
        a.setContentView(raiz)
        val cruz = CruzOuvir(a, Typeface.DEFAULT, Typeface.DEFAULT_BOLD)
        raiz.addView(cruz, FrameLayout.LayoutParams(-1, -1))
        return a to cruz
    }

    private fun tecla(cruz: CruzOuvir, k: Int, segurar: Boolean = false) {
        val desce = KeyEvent(KeyEvent.ACTION_DOWN, k)
        cruz.dispatchKeyEvent(desce)
        if (segurar) {
            // o Android manda a repetição com FLAG_LONG_PRESS; o dispatcher chama onKeyLongPress
            cruz.dispatchKeyEvent(KeyEvent.changeTimeRepeat(desce, desce.eventTime + 600, 1, KeyEvent.FLAG_LONG_PRESS))
            cruz.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, k), KeyEvent.FLAG_CANCELED_LONG_PRESS))
        } else cruz.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
    }

    private fun radio(lingua: String) {
        val (a, cruz) = montar()
        val tocados = mutableListOf<String>(); val favoritados = mutableListOf<String>()
        fun r(id: String, nome: String, sub: String, cor: Int = 0xFF45C2B1.toInt()) =
            CruzOuvir.Item(id, nome, sub, cor = cor, monograma = CapaOuvir.iniciais(nome), abrir = { tocados += id }, opcoes = { favoritados += id })
        cruz.definirTitulo(a.getString(R.string.ra_coluna))
        cruz.definirDicas(a.getString(R.string.grv_dica_ok) to a.getString(R.string.ra_dica_ouvir),
            a.getString(R.string.grv_dica_segurar) to a.getString(R.string.ra_dica_favoritar), "▶❙❙" to a.getString(R.string.ra_pausar))
        cruz.definir(listOf(
            CruzOuvir.Prateleira("tocando", a.getString(R.string.ouv_prat_tocando), IconeOuvir.Tipo.TOCANDO, listOf(
                CruzOuvir.Item("pausar", a.getString(R.string.ouv_pausar), CruzLogica.linhas("Rádio Mar Azul FM", "Tom Jobim - Wave", "Brasil · 128 kbps"), IconeOuvir.Tipo.PAUSAR),
                CruzOuvir.Item("favoritar", a.getString(R.string.ouv_favoritar), a.getString(R.string.ouv_favoritar_det), IconeOuvir.Tipo.FAVORITAS),
                CruzOuvir.Item("parar", a.getString(R.string.ra_parar), a.getString(R.string.ra_parar_det), IconeOuvir.Tipo.PARAR),
                CruzOuvir.Item("ao_sair", a.getString(R.string.ouv_ao_sair), a.getString(R.string.ouv_ao_sair_continua), IconeOuvir.Tipo.SAIR))),
            CruzOuvir.Prateleira("fav", a.getString(R.string.ra_favoritas), IconeOuvir.Tipo.FAVORITAS, listOf(
                r("fav:1", "Rádio Mar Azul FM", CruzLogica.linhas(a.getString(R.string.ouv_tecla, 1), "mpb, bossa nova", "Brasil · 128 kbps")),
                r("fav:2", "CBN São Paulo", CruzLogica.linhas(a.getString(R.string.ouv_tecla, 2), "news, sports", "Brasil · 128 kbps"), 0xFF7FB2FF.toInt()),
                r("fav:3", "Antena 1", CruzLogica.linhas(a.getString(R.string.ouv_tecla, 3), "adult contemporary", "Brasil · 96 kbps"), 0xFFF2B84B.toInt()),
                r("fav:4", "Kiss FM 92.5", CruzLogica.linhas(a.getString(R.string.ouv_tecla, 4), "rock", "Brasil · 128 kbps"), 0xFF8E92FA.toInt()))),
            CruzOuvir.Prateleira("rec", a.getString(R.string.ouv_recentes), IconeOuvir.Tipo.RECENTES, listOf(
                CruzOuvir.Item("rec:vazio", a.getString(R.string.ouv_sem_recentes), a.getString(R.string.ouv_sem_recentes_det), IconeOuvir.Tipo.RECENTES))),
            CruzOuvir.Prateleira("pais", "Brasil", IconeOuvir.Tipo.PAIS, (1..12).map { r("pais:$it", "Rádio $it", "Brasil · 128 kbps") }),
            CruzOuvir.Prateleira("generos", a.getString(R.string.ra_generos), IconeOuvir.Tipo.GENEROS, listOf("Sertanejo", "Mpb", "Jazz").map {
                CruzOuvir.Item("gen:$it", it, "", cor = 0xFF8E92FA.toInt(), monograma = CapaOuvir.iniciais(it)) }),
            CruzOuvir.Prateleira("paises", a.getString(R.string.ouv_paises), IconeOuvir.Tipo.PAIS, listOf(
                CruzOuvir.Item("pa:BR", "Brasil", a.getString(R.string.ouv_n_radios, 2104), monograma = CapaOuvir.bandeira("BR")),
                CruzOuvir.Item("pa:PT", "Portugal", a.getString(R.string.ouv_n_radios, 512), monograma = CapaOuvir.bandeira("PT")))),
            CruzOuvir.Prateleira("buscar", a.getString(R.string.ra_procurar), IconeOuvir.Tipo.BUSCAR, listOf(
                CruzOuvir.Item("buscar", a.getString(R.string.ra_buscar), a.getString(R.string.ra_buscar_det), IconeOuvir.Tipo.BUSCAR))),
        ), "tocando" to null)
        cruz.tocando(CruzOuvir.Tocando("Rádio Mar Azul FM", CruzLogica.linhas("Tom Jobim - Wave", "Brasil · 128 kbps"), "", 0xFF45C2B1.toInt(),
            CapaOuvir.iniciais("Rádio Mar Azul FM"), cobrir = false, progresso = null, tempo = null, pausado = false,
            aoVivo = a.getString(R.string.ouv_ao_vivo).uppercase()))
        cruz.requestFocus()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertTrue("a cruz tem o foco", cruz.isFocused)
        val problemas = mutableListOf<String>()
        problemas += MedirTela.medir(a, "radio-tocando", "ouvir-radio-$lingua-1-tocando", 14f, cartao = true)

        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("fav:1", cruz.itemAtual()?.id)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("fav:2", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "radio-favoritas", "ouvir-radio-$lingua-2-favoritas", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("fav:2"), tocados)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_CENTER, segurar = true)
        assertEquals(listOf("fav:2"), favoritados)
        assertEquals("segurar não toca", listOf("fav:2"), tocados)

        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        problemas += MedirTela.medir(a, "radio-recentes-vazio", "ouvir-radio-$lingua-3-recentes", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(4) { tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("pais:5", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "radio-pais", "ouvir-radio-$lingua-4-pais", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT); tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("pa:BR", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "radio-paises", "ouvir-radio-$lingua-5-paises", 14f, cartao = true)
        // ◀ volta e lembra o item de cada prateleira
        tecla(cruz, KeyEvent.KEYCODE_DPAD_LEFT); tecla(cruz, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("pais:5", cruz.itemAtual()?.id)
        // ▶ na última não faz nada; a lista nova com os mesmos ids não mexe no foco
        repeat(6) { tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("buscar", cruz.itemAtual()?.id)
        assertTrue("sem problema de tela: $problemas", problemas.isEmpty())
        a.finish()
    }

    private fun podcasts(lingua: String) {
        val (a, cruz) = montar()
        val azul = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST)
        fun ep(id: String, titulo: String, sub: String, prog: Float? = null) =
            CruzOuvir.Item(id, titulo, sub, cor = azul, monograma = CapaOuvir.iniciais("Café com Ciência"), cobrir = true, progresso = prog)
        cruz.definirTitulo(a.getString(R.string.pod_titulo))
        cruz.definirDicas(a.getString(R.string.grv_dica_ok) to a.getString(R.string.ra_dica_ouvir),
            a.getString(R.string.grv_dica_segurar) to a.getString(R.string.pod_dica_mais), "◀◀ ▶▶" to a.getString(R.string.ouv_dica_pular))
        cruz.definir(listOf(
            CruzOuvir.Prateleira("tocando", a.getString(R.string.ouv_prat_tocando), IconeOuvir.Tipo.TOCANDO, listOf(
                CruzOuvir.Item("abrir", a.getString(R.string.pl_abrir), CruzLogica.linhas("Os vulcões do Pacífico", a.getString(R.string.pl_abrir_det)),
                    cor = azul, monograma = CapaOuvir.iniciais("Café com Ciência"), cobrir = true),
                CruzOuvir.Item("pausar", a.getString(R.string.ouv_pausar), CruzLogica.linhas("Os vulcões do Pacífico", "Café com Ciência"), IconeOuvir.Tipo.PAUSAR),
                CruzOuvir.Item("voltar", a.getString(R.string.ouv_voltar), a.getString(R.string.ouv_voltar_det), IconeOuvir.Tipo.VOLTAR),
                CruzOuvir.Item("avancar", a.getString(R.string.ouv_avancar), a.getString(R.string.ouv_avancar_det), IconeOuvir.Tipo.AVANCAR),
                CruzOuvir.Item("velocidade", a.getString(R.string.pod_velocidade), a.getString(R.string.ouv_velocidade_det, "1,2x"), IconeOuvir.Tipo.VELOCIDADE),
                CruzOuvir.Item("parar", a.getString(R.string.ra_parar), a.getString(R.string.ouv_parar_pod_det), IconeOuvir.Tipo.PARAR))),
            CruzOuvir.Prateleira("continuar", a.getString(R.string.pod_continuar), IconeOuvir.Tipo.CONTINUAR, listOf(
                ep("c1", "Os vulcões do Pacífico e o anel de fogo", CruzLogica.linhas("Café com Ciência", "ontem · faltam 21 min",
                    "Por que a Terra treme tanto em volta do Pacífico, e o que os vulcões contam sobre o fundo do mar."), 0.55f),
                ep("c2", "Entrevista: o futuro das cidades", CruzLogica.linhas("Conversa Longa", "12 de nov. · faltam 48 min"), 0.2f))),
            CruzOuvir.Prateleira("novos", a.getString(R.string.pod_novos), IconeOuvir.Tipo.NOVOS, listOf(
                ep("n1", "Como nasce uma estrela", CruzLogica.linhas("Café com Ciência", "hoje · 42 min")))),
            CruzOuvir.Prateleira("meus", a.getString(R.string.pod_meus), IconeOuvir.Tipo.MEUS, listOf(
                ep("pod:1", "Café com Ciência", CruzLogica.linhas("Rádio Universitária", a.getString(R.string.ouv_n_episodios, 128))))),
            CruzOuvir.Prateleira("adicionar", a.getString(R.string.pod_adicionar), IconeOuvir.Tipo.ADICIONAR, listOf(
                CruzOuvir.Item("colar", a.getString(R.string.ouv_colar), a.getString(R.string.pod_colar_det), IconeOuvir.Tipo.ADICIONAR),
                CruzOuvir.Item("celular", a.getString(R.string.pod_pelo_celular), a.getString(R.string.pod_pelo_celular_det), IconeOuvir.Tipo.CELULAR))),
        ), "continuar" to null)
        cruz.tocando(CruzOuvir.Tocando("Os vulcões do Pacífico e o anel de fogo", "Café com Ciência", "", azul, CapaOuvir.iniciais("Café com Ciência"),
            cobrir = true, progresso = 0.55f, tempo = a.getString(R.string.pod_de, "25:10", "46:00") + " · " + br.paulo.tvlauncher.player.BarraTempo.faltam(a, "21 min"),
            pausado = false))
        cruz.requestFocus()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals("c1", cruz.itemAtual()?.id)
        val problemas = mutableListOf<String>()
        problemas += MedirTela.medir(a, "podcasts-continuar", "ouvir-podcasts-$lingua-1-continuar", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("abrir", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "podcasts-abrir", "ouvir-podcasts-$lingua-2-abrir", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("pausar", cruz.itemAtual()?.id)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN); tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN); tecla(cruz, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("velocidade", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "podcasts-tocando", "ouvir-podcasts-$lingua-2-tocando", 14f, cartao = true)
        // o tempo muda a cada segundo sem refazer a cruz
        cruz.tempo(0.6f, a.getString(R.string.pod_de, "27:40", "46:00"))
        assertEquals("velocidade", cruz.itemAtual()?.id)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT); tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT); tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("pod:1", cruz.itemAtual()?.id)
        problemas += MedirTela.medir(a, "podcasts-meus", "ouvir-podcasts-$lingua-3-meus", 14f, cartao = true)
        tecla(cruz, KeyEvent.KEYCODE_DPAD_RIGHT)
        problemas += MedirTela.medir(a, "podcasts-adicionar", "ouvir-podcasts-$lingua-4-adicionar", 14f, cartao = true)
        // sem nada tocando, o cartão some
        cruz.tocando(null)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        problemas += MedirTela.medir(a, "podcasts-sem-som", "ouvir-podcasts-$lingua-5-sem-som", 14f, cartao = true)
        assertTrue("sem problema de tela: $problemas", problemas.isEmpty())
        a.finish()
    }
}

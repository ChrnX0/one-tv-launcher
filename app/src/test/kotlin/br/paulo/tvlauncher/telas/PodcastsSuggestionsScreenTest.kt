package br.paulo.tvlauncher.telas

import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.PodcastsActivity
import br.paulo.tvlauncher.listen.PodcastStarter
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * A tela de Podcasts de quem ainda não segue nada (18/11/2026): abre na prateleira "Sugestões", com os podcasts que
 * vêm no app no idioma da TV ([PodcastStarter]). Foto em app/build/telas/podcasts-sugestoes-*.png; confere letra
 * mínima, nada cortado, nada por cima e o foco ([MedirTela]). O "seguir sozinho" fica marcado como feito: teste sem rede.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class PodcastsSuggestionsScreenTest {

    @Config(qualifiers = "pt-rBR-w960dp-h540dp-land-television-xhdpi")
    @Test fun sugestoesPt() = sugestoes("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun sugestoesEn() = sugestoes("en")

    private fun sugestoes(lingua: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = RuntimeEnvironment.getApplication()
        PodcastStarter.marcarFeito(app)
        assertTrue("o idioma $lingua tem sugestões", PodcastStarter.lista(app).size >= 7)
        val a = Robolectric.buildActivity(PodcastsActivity::class.java).setup().get()
        Thread.sleep(400)   // a fila de disco dos podcasts lê as assinaturas (nenhuma)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertFalse("a tela não pode sair sozinha", a.isFinishing)
        val problemas = MedirTela.medir(a, "podcasts-sugestoes", "podcasts-sugestoes-$lingua", 14f, cartao = true)
        assertTrue("sem problema de tela: $problemas", problemas.isEmpty())
        a.finish()
    }
}

package br.paulo.tvlauncher.telas

import android.content.Intent
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.OuvirServico
import br.paulo.tvlauncher.PodcastPlayerActivity
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.Posicao
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
 * O player de podcast em tela cheia (18/11/2026): abre com um episódio pela metade (a posição guardada já aparece na
 * barra), anda pelos três andares com as setas e tira foto de cada um (app/build/telas/podcast-player-*.png).
 * Confere letra mínima, nada cortado, nada por cima de outro texto e o foco na tela ([MedirTela]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class PodcastPlayerScreenTest {

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun playerPt() = player("pt", "Os vulcões do Pacífico e o anel de fogo: o que o fundo do mar conta sobre a Terra", "Café com Ciência")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun playerEn() = player("en", "Pacific volcanoes and the ring of fire: what the sea floor tells us about Earth", "Science with Coffee")

    private fun ep(guid: String, titulo: String, dias: Int, min: Int) = PodcastFeed.Episodio(guid, titulo, "",
        1_763_000_000_000L - dias * 86_400_000L, min * 60, "https://exemplo.invalid/$guid.mp3", "audio/mpeg", 0, "", false, 0, 0, emptyList(), "")

    private fun tecla(a: PodcastPlayerActivity, k: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
    }

    private fun player(lingua: String, titulo: String, podcast: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = RuntimeEnvironment.getApplication()
        val feed = "https://exemplo.invalid/feed.xml"
        val atual = ep("a", titulo, 1, 46)
        // Parou na metade da última vez: a barra mostra isso antes do som começar.
        Podcasts.guardarPosicao(app, Podcasts.Posicao(feed, "a", 23 * 60_000L, 46 * 60_000L, false, System.currentTimeMillis(), titulo, podcast))
        val e = OuvirServico.EpisodioTocando(feed, podcast, "", atual)
        val i = Intent(app, PodcastPlayerActivity::class.java).putExtra("ep", e.paraJson())
        val a = Robolectric.buildActivity(PodcastPlayerActivity::class.java, i).setup().get()
        Thread.sleep(400)   // a fila de disco dos podcasts lê a posição guardada
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        a.mostrarFila(listOf(
            ep("b", if (lingua == "pt") "Como nasce uma estrela" else "How a star is born", 0, 42),
            ep("c", if (lingua == "pt") "Entrevista: o futuro das cidades e do transporte" else "Interview: the future of cities and transport", 3, 58),
            ep("d", if (lingua == "pt") "Por que o céu é azul?" else "Why is the sky blue?", 8, 21),
        ))
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertFalse("a tela não pode sair sozinha", a.isFinishing)

        val problemas = mutableListOf<String>()
        problemas += MedirTela.medir(a, "player-tocando", "podcast-player-$lingua-1-tocando", 14f, cartao = true)
        repeat(4) { tecla(a, KeyEvent.KEYCODE_DPAD_RIGHT) }   // −15 s, ⏯, +30 s, velocidade, silêncio → timer
        problemas += MedirTela.medir(a, "player-timer", "podcast-player-$lingua-2-timer", 14f, cartao = true)
        tecla(a, KeyEvent.KEYCODE_DPAD_UP)
        problemas += MedirTela.medir(a, "player-barra", "podcast-player-$lingua-3-barra", 14f, cartao = true)
        tecla(a, KeyEvent.KEYCODE_DPAD_DOWN); tecla(a, KeyEvent.KEYCODE_DPAD_DOWN)
        tecla(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        problemas += MedirTela.medir(a, "player-a-seguir", "podcast-player-$lingua-4-a-seguir", 14f, cartao = true)
        // VOLTAR fecha a fila sem sair do player
        tecla(a, KeyEvent.KEYCODE_BACK)
        assertFalse("VOLTAR com a fila aberta só fecha a fila", a.isFinishing)
        assertTrue("sem problema de tela: $problemas", problemas.isEmpty())
        a.finish()
    }
}

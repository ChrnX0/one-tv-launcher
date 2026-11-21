package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.World
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldTintTest {
    @Test fun cadaTelaNoSeuMundo() {
        assertEquals(World.WATCH, World.ofScreen("FilmesActivity"))
        assertEquals(World.WATCH, World.ofScreen("SeriesActivity"))
        assertEquals(World.LISTEN, World.ofScreen("RadioActivity"))
        assertEquals(World.LISTEN, World.ofScreen("PodcastsActivity"))
        assertEquals(World.PLAY, World.ofScreen("JogosActivity"))
        assertEquals(World.NEUTRAL, World.ofScreen("ConfigLauncherActivity"))
        assertEquals(World.NEUTRAL, World.ofScreen(""))
    }

    /** Toda tela listada existe de verdade (um nome errado deixaria a tela no neutro sem ninguém ver). */
    @Test fun nomesDasTelasExistem() {
        for (n in listOf("FilmesActivity", "SeriesActivity", "VodActivity", "PlayerActivity", "MidiaRedeActivity",
            "ServicosFilmesActivity", "BloqueioCanaisActivity", "AddonsActivity", "GravacoesActivity", "ElencoActivity",
            "RadioActivity", "PodcastsActivity", "PodcastPlayerActivity", "JogosActivity")) {
            Class.forName("br.paulo.tvlauncher.$n")
        }
    }
}

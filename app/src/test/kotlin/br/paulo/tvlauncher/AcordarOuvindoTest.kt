package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.AcordarOuvindo
import br.paulo.tvlauncher.alarm.SomAlarme
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acordar com rádio ou podcast: qual rádio e qual episódio o despertador escolhe. */
class AcordarOuvindoTest {
    private fun ep(guid: String, data: Long, explicito: Boolean = false, url: String = "https://x/$guid.mp3") =
        PodcastFeed.Episodio(guid, guid, "", data, 0, url, "audio/mpeg", 0, "", explicito, 0, 0, emptyList(), "")

    @Test fun radio() {
        val a = Radios.Radio("a", "A", "http://a"); val b = Radios.Radio("b", "B", "http://b")
        assertEquals("a", AcordarOuvindo.radio(a, listOf(b))?.id)
        assertEquals("b", AcordarOuvindo.radio(null, listOf(b, a))?.id)
        assertNull(AcordarOuvindo.radio(null, emptyList()))
    }

    @Test fun episodio() {
        val l = listOf("f" to ep("velho", 1), "f" to ep("novo", 3, explicito = true), "g" to ep("meio", 2), "g" to ep("sem", 9, url = ""))
        assertEquals("novo", AcordarOuvindo.episodio(l, protegido = false)?.guid)
        assertEquals("meio", AcordarOuvindo.episodio(l, protegido = true)?.guid)
        assertNull(AcordarOuvindo.episodio(emptyList(), false))
    }

    @Test fun sons() {
        assertTrue(SomAlarme.deFora(SomAlarme.CANAL) && SomAlarme.deFora(SomAlarme.RADIO) && SomAlarme.deFora(SomAlarme.PODCAST))
        assertFalse(SomAlarme.deFora(0))
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.FotosTerra
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fotos da Terra do protetor v2 (17/11/2026): a foto da hora e do tempo, e que cada uma existe no APK. */
class FotosTerraTest {
    private val h = 3_600_000L
    private val nasce = 100 * h + 5 * h + 23 * 60_000L   // 05:23 de um dia qualquer
    private val poe = 100 * h + 18 * h + 31 * 60_000L    // 18:31

    @Test fun grupoPelaHora() {
        val np = nasce to poe
        assertEquals(FotosTerra.Grupo.NOITE, FotosTerra.grupo(nasce - 2 * h, np, false, 3))
        assertEquals(FotosTerra.Grupo.CREPUSCULO, FotosTerra.grupo(nasce + 20 * 60_000L, np, false, 5))
        assertEquals(FotosTerra.Grupo.DIA, FotosTerra.grupo(nasce + 5 * h, np, false, 10))
        assertEquals(FotosTerra.Grupo.CREPUSCULO, FotosTerra.grupo(poe - 30 * 60_000L, np, false, 18))
        assertEquals(FotosTerra.Grupo.NOITE, FotosTerra.grupo(poe + 2 * h, np, false, 20))
    }

    @Test fun nuvensSoDeDia() {
        val np = nasce to poe
        assertEquals(FotosTerra.Grupo.NUVENS, FotosTerra.grupo(nasce + 5 * h, np, FotosTerra.nublado(61, true), 10))
        assertEquals(FotosTerra.Grupo.NOITE, FotosTerra.grupo(poe + 2 * h, np, true, 20))
        assertEquals(false, FotosTerra.nublado(1, false))
        assertEquals(FotosTerra.Grupo.DIA, FotosTerra.grupo(0, null, false, 12))   // sem nascer/pôr: vale o relógio
    }

    @Test fun proximaDaAVoltaNoGrupo() {
        val noite = FotosTerra.TODAS.filter { it.grupo == FotosTerra.Grupo.NOITE }
        assertEquals(noite[0].id, FotosTerra.proxima(FotosTerra.Grupo.NOITE, null).id)
        assertEquals(noite[1].id, FotosTerra.proxima(FotosTerra.Grupo.NOITE, noite[0].id).id)
        assertEquals(noite[0].id, FotosTerra.proxima(FotosTerra.Grupo.NOITE, noite.last().id).id)
    }

    @Test fun cadaFotoEstaNoApk() {
        FotosTerra.Grupo.entries.forEach { g -> assertTrue("grupo $g vazio", FotosTerra.TODAS.any { it.grupo == g }) }
        FotosTerra.TODAS.forEach { assertTrue(it.id, File("src/main/assets/terra/${it.id}.jpg").isFile) }
    }
}

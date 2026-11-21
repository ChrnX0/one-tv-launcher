package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Capitulos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapitulosTest {
    private val caps = Capitulos.montar(longArrayOf(0, 90_000, 90_000, -1, 600_000, 0), listOf("Prólogo", " Opening ", ""), 1_200_000)

    @Test fun montaComFimInvalido() {
        assertEquals(3, caps.size)
        assertEquals(600_000L, caps[1].fimMs)        // fim inválido = começo do próximo
        assertEquals(1_200_000L, caps[2].fimMs)      // último: duração
        assertEquals("Opening", caps[1].titulo)
        assertNull(caps[2].titulo)
    }

    @Test fun aberturaPeloTitulo() {
        // "Opening" de 8:30 é longo demais para ser abertura.
        assertNull(Capitulos.abertura(caps))
        val c = Capitulos.montar(longArrayOf(0, 60_000, 60_000, 150_000, 150_000, 900_000), listOf("A", "OP", "B"), 900_000)
        assertEquals(60_000L, Capitulos.abertura(c)!!.inicioMs)
        assertEquals("Abertura", Capitulos.abertura(Capitulos.montar(longArrayOf(0, 30_000), listOf("Abertura"), 30_000))!!.titulo)
    }

    @Test fun proximoEAnterior() {
        assertEquals(90_000L, Capitulos.proximo(caps, 10_000))
        assertEquals(-1L, Capitulos.proximo(caps, 700_000))
        assertEquals(90_000L, Capitulos.anterior(caps, 100_000))    // passou 10 s: volta ao começo deste
        assertEquals(0L, Capitulos.anterior(caps, 91_000))          // acabou de começar: o anterior
        assertEquals(0L, Capitulos.anterior(caps, 1_000))
    }

    @Test fun fontesEMiniatura() {
        assertTrue(Capitulos.ehFonte("application/x-truetype-font", null))
        assertTrue(Capitulos.ehFonte(null, "Arial.TTF"))
        assertFalse(Capitulos.ehFonte("image/jpeg", "capa.jpg"))
        assertEquals(240 to 134, Capitulos.tamanhoMiniatura(1920, 1080))
        assertEquals(240 to 134, Capitulos.tamanhoMiniatura(0, 0))
        assertEquals(240 to 180, Capitulos.tamanhoMiniatura(640, 480))
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.AberturaSerie.Marca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AberturaSerieTest {
    private val boa = Marca(60_000, 150_000)   // 1:00 a 2:30

    @Test fun validaDuracaoEInicio() {
        assertTrue(AberturaSerie.valida(boa))
        assertTrue(AberturaSerie.valida(Marca(0, 10_000)))              // 10 s exatos
        assertTrue(AberturaSerie.valida(Marca(0, 300_000)))             // 5 min exatos
        assertFalse(AberturaSerie.valida(Marca(0, 9_999)))              // curta demais
        assertFalse(AberturaSerie.valida(Marca(0, 300_001)))            // longa demais
        assertFalse(AberturaSerie.valida(Marca(150_000, 60_000)))       // fim antes do início
        assertFalse(AberturaSerie.valida(Marca(900_000, 960_000)))      // começa aos 15 min: tarde demais
        assertTrue(AberturaSerie.valida(Marca(899_999, 960_000)))
        assertFalse(AberturaSerie.valida(Marca(60_000)))                // falta o fim
        assertFalse(AberturaSerie.valida(null))
    }

    @Test fun marcarInicioEFim() {
        val ini = AberturaSerie.marcarInicio(null, 60_000)
        assertEquals(Marca(60_000), ini)
        val pronta = AberturaSerie.marcarFim(ini, 150_000)
        assertEquals(boa, pronta)
        assertNull(AberturaSerie.marcarFim(null, 150_000))              // sem início não há fim
        // Remarcar o início mantém o fim que continua valendo; o que não vale mais é descartado.
        assertEquals(Marca(70_000, 150_000), AberturaSerie.marcarInicio(boa, 70_000))
        assertEquals(Marca(200_000), AberturaSerie.marcarInicio(boa, 200_000))
        assertEquals(Marca(0), AberturaSerie.marcarInicio(null, -5))
    }

    @Test fun pilulaSoDentroDoTrecho() {
        assertFalse(AberturaSerie.mostrarPulo(boa, 59_999))
        assertTrue(AberturaSerie.mostrarPulo(boa, 60_000))
        assertTrue(AberturaSerie.mostrarPulo(boa, 120_000))
        assertFalse(AberturaSerie.mostrarPulo(boa, 149_500))            // colado no fim: não vale a pena
        assertFalse(AberturaSerie.mostrarPulo(boa, 150_000))
        assertFalse(AberturaSerie.mostrarPulo(Marca(60_000), 70_000))   // marcação incompleta
        assertFalse(AberturaSerie.mostrarPulo(null, 70_000))
    }

    @Test fun alvoDoPulo() {
        assertEquals(150_000L, AberturaSerie.alvo(boa))
        assertNull(AberturaSerie.alvo(Marca(0, 5_000)))
        assertNull(AberturaSerie.alvo(null))
    }

    @Test fun textoDasPrefs() {
        assertEquals("abertura:fonte1:s42", AberturaSerie.chave("fonte1:s42"))
        assertEquals(boa, AberturaSerie.deTexto(AberturaSerie.paraTexto(boa)))
        assertEquals(Marca(60_000), AberturaSerie.deTexto(AberturaSerie.paraTexto(Marca(60_000))))
        assertNull(AberturaSerie.deTexto(null))
        assertNull(AberturaSerie.deTexto("lixo"))
        assertNull(AberturaSerie.deTexto("1,2,3"))
        assertNull(AberturaSerie.deTexto("-5,10"))
    }
}

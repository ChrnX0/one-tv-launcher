package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeclaFalsaTest {
    @Test fun apertoCurtoEDescerESubir() {
        val p = TeclaFalsa.plano(100)
        assertEquals(listOf(TeclaFalsa.Evento(true, 0, 0), TeclaFalsa.Evento(false, 0, 100)), p)
    }

    @Test fun seguradoRepeteComoOAndroid() {
        val p = TeclaFalsa.plano(1000)
        assertEquals(TeclaFalsa.Evento(true, 0, 0), p.first())
        assertEquals(TeclaFalsa.Evento(false, 0, 1000), p.last())
        val rep = p.filter { it.desce && it.repeticao > 0 }
        assertEquals(TeclaFalsa.REPETIR_APOS_MS, rep.first().emMs)
        assertEquals((1..rep.size).toList(), rep.map { it.repeticao })
        assertTrue(rep.all { it.emMs < 1000 })
        assertEquals(10, rep.size)   // 500, 550 ... 950
    }

    @Test fun duracaoPresaNosLimites() {
        assertEquals(TeclaFalsa.PADRAO_MS, TeclaFalsa.duracao(null))
        assertEquals(0L, TeclaFalsa.duracao(-5))
        assertEquals(TeclaFalsa.MAX_MS, TeclaFalsa.duracao(999_999))
        assertEquals(TeclaFalsa.MAX_MS, TeclaFalsa.plano(999_999).last().emMs)
    }

    @Test fun zeroMsAindaDesceAntesDeSubir() {
        val p = TeclaFalsa.plano(0)
        assertTrue(p[0].desce); assertFalse(p[1].desce); assertEquals(2, p.size)
    }
}

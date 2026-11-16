package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Modo hóspede (degrau 2, 16/11/2026). */
class HospedeRegrasTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")

    private fun em(dia: Int, hora: Int, min: Int = 0) = Calendar.getInstance(sp).apply {
        clear(); set(2026, Calendar.NOVEMBER, dia, hora, min)
    }.timeInMillis

    @Test fun limpaSoInstaladosForaDoSistema() {
        val inst = setOf("com.netflix.ninja", "com.disney.disneyplus", "com.google.android.youtube.tvkids", "br.paulo.tvlauncher")
        val sistema = setOf("com.google.android.youtube.tvkids")
        assertEquals(listOf("com.disney.disneyplus", "com.netflix.ninja"), HospedeRegras.paraLimpar(null, inst, sistema))
        // A lista escolhida pelo dono vale no lugar da conhecida.
        assertEquals(listOf("br.paulo.tvlauncher"), HospedeRegras.paraLimpar(setOf("br.paulo.tvlauncher", "x.nao.instalado"), inst, sistema))
        assertTrue(HospedeRegras.paraLimpar(emptySet(), inst, sistema).isEmpty())
    }

    @Test fun saidaNaHoraCerta() {
        assertEquals(em(18, 11), HospedeRegras.saidaEm(em(16, 15, 30), 2, 11, sp))
        assertEquals(0, HospedeRegras.diasAte(em(16, 1), em(16, 23), sp))
        assertEquals(1, HospedeRegras.diasAte(em(16, 23), em(17, 1), sp))
    }

    @Test fun ciclaDiasEPulaHojeQueJaPassou() {
        val agora = em(16, 15)   // 15 h: a saída de hoje às 11 h já passou
        val s1 = HospedeRegras.proximaSaida(0L, agora, 11, sp)
        assertEquals(em(17, 11), s1)
        assertEquals(em(18, 11), HospedeRegras.proximaSaida(s1, agora, 11, sp))
        // Antes das 11 h, a primeira é hoje.
        assertEquals(em(16, 11), HospedeRegras.proximaSaida(0L, em(16, 9), 11, sp))
        // Depois do último dia, volta a "desligada".
        val ultimo = HospedeRegras.saidaEm(agora, HospedeRegras.MAX_DIAS, 11, sp)
        assertEquals(0L, HospedeRegras.proximaSaida(ultimo, agora, 11, sp))
    }

    @Test fun horaDaVolta() {
        assertEquals(9, HospedeRegras.proximaHora(8))
        assertEquals(8, HospedeRegras.proximaHora(14))
        assertEquals(8, HospedeRegras.proximaHora(3))
    }

    @Test fun venceu() {
        assertFalse(HospedeRegras.venceu(0L, em(16, 12)))
        assertTrue(HospedeRegras.venceu(em(16, 11), em(16, 12)))
        assertFalse(HospedeRegras.venceu(em(17, 11), em(16, 12)))
    }
}

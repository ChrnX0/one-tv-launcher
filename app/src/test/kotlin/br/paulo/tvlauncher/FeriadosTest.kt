package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Feriados
import java.io.File
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feriados nacionais do país da TV: o "dia útil" das automações não vale neles (pedido do Paulo, 18/11/2026). */
class FeriadosTest {
    private val json = File("src/main/assets/feriados/feriados.json").readText()

    @Test fun brasilEOutrosPaises() {
        val br = Feriados.linhaDoPais(json, "br")
        assertEquals(true, Feriados.eFeriado(br, 2026, 4, 21))    // Tiradentes
        assertEquals(true, Feriados.eFeriado(br, 2026, 11, 20))   // Consciência Negra (nacional desde 2024)
        assertEquals(false, Feriados.eFeriado(br, 2026, 4, 22))
        val us = Feriados.linhaDoPais(json, "US")
        assertEquals(true, Feriados.eFeriado(us, 2026, 7, 4))
        assertEquals(false, Feriados.eFeriado(us, 2026, 4, 21))
        val pt = Feriados.linhaDoPais(json, "PT")
        assertEquals(true, Feriados.eFeriado(pt, 2026, 6, 10))    // Dia de Portugal
    }

    @Test fun foraDaTabelaENaoSei() {
        assertNull(Feriados.linhaDoPais(json, "ZZ"))
        assertNull(Feriados.linhaDoPais(json, "001"))
        assertNull(Feriados.eFeriado(null, 2026, 1, 1))
        assertNull(Feriados.eFeriado(Feriados.linhaDoPais(json, "BR"), 1990, 1, 1))
    }

    @Test fun tabelaCobreOsProximosAnos() {
        val ano = Calendar.getInstance().get(Calendar.YEAR)
        val br = Feriados.linhaDoPais(json, "BR")!!
        for (a in ano..ano + 5) assertTrue("tabela sem $a: rodar scripts/gerar-feriados.py", Feriados.doAno(br, a) != null)
    }

    @Test fun paisPeloFusoDepoisPeloIdioma() {
        assertEquals("BR", Feriados.pais("BR", "US"))
        assertEquals("PT", Feriados.pais("001", "pt"))
        assertEquals("PT", Feriados.pais(null, "PT"))
        assertNull(Feriados.pais(null, ""))
    }

    @Test fun diaUtilNaoValeEmFeriado() {
        val seg = SoSe.Estado(0L, null, null, null, Calendar.MONDAY, feriado = true)
        assertFalse(SoSe.todas(listOf(SoSe.Condicao.DiaUtil), seg))
        assertTrue(SoSe.todas(listOf(SoSe.Condicao.DiaUtil), seg.copy(feriado = false)))
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.NaoPerturbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaoPerturbeTest {
    private val meioDia = 12 * 60

    @Test fun desligado_nunca_segura() { assertNull(NaoPerturbe.motivo(false, true, 2 * 60, true)) }

    @Test fun filme_madrugada_visita() {
        assertEquals(NaoPerturbe.Motivo.FILME, NaoPerturbe.motivo(true, true, meioDia, false))
        assertEquals(NaoPerturbe.Motivo.VISITA, NaoPerturbe.motivo(true, false, meioDia, true))
        assertEquals(NaoPerturbe.Motivo.MADRUGADA, NaoPerturbe.motivo(true, false, 2 * 60, false))
        assertEquals(NaoPerturbe.Motivo.MADRUGADA, NaoPerturbe.motivo(true, false, 23 * 60 + 30, false))
        assertNull(NaoPerturbe.motivo(true, false, 7 * 60, false))          // 7h em ponto: já é dia
        assertNull(NaoPerturbe.motivo(true, false, meioDia, false))
        assertEquals(NaoPerturbe.Motivo.FILME, NaoPerturbe.motivo(true, true, 2 * 60, true))   // o filme é o que a pessoa reconhece
    }

    @Test fun o_que_passa_sempre() {
        assertFalse(NaoPerturbe.passa(NaoPerturbe.Tipo.COMUM))
        for (t in listOf(NaoPerturbe.Tipo.ALARME, NaoPerturbe.Tipo.CAMPAINHA, NaoPerturbe.Tipo.ALERTA_OFICIAL, NaoPerturbe.Tipo.REGRA))
            assertTrue(t.name, NaoPerturbe.passa(t))
    }

    @Test fun fila_e_resumo() {
        var f = emptyList<NaoPerturbe.Guardado>()
        for (i in 1..5) f = NaoPerturbe.guardar(f, NaoPerturbe.Guardado("Aviso $i", "x", i.toLong()))
        f = NaoPerturbe.guardar(f, NaoPerturbe.Guardado("Aviso 2", "x", 9))   // repetido: vai para o fim, uma vez
        assertEquals(5, f.size)
        assertEquals("Aviso 2 · Aviso 5 · Aviso 4 · mais 2", NaoPerturbe.textoResumo(f) { "mais $it" })
        assertEquals(f, NaoPerturbe.ler(NaoPerturbe.texto(f)))
        for (i in 1..30) f = NaoPerturbe.guardar(f, NaoPerturbe.Guardado("n$i", "", i.toLong()))
        assertEquals(NaoPerturbe.MAX_FILA, f.size)
    }

    @Test fun recado_velho_sai_do_resumo() {
        val agora = 100 * 3_600_000L
        val f = listOf(NaoPerturbe.Guardado("velho", "", agora - 13 * 3_600_000L), NaoPerturbe.Guardado("novo", "", agora - 60_000))
        assertEquals(listOf("novo"), NaoPerturbe.validos(f, agora).map { it.titulo })
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.TravaRelogioRegra.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regra da trava do relógio na hora de dormir (TravaRelogio.kt): quando trava, quando destrava, o que volta. */
class TravaRelogioTest {
    private fun acao(donos: Boolean = true, ligada: Boolean, restricao: Boolean, auto: Boolean) =
        TravaRelogioRegra.acao(donos, ligada, restricao, auto)

    @Test fun ligadaSemTravaTrava() {
        assertEquals(Acao.TRAVAR, acao(ligada = true, restricao = false, auto = true))
        assertEquals(Acao.TRAVAR, acao(ligada = true, restricao = false, auto = false))
    }

    @Test fun ligadaComHoraAutomaticaDesligadaReforca() {
        // A restrição está lá, mas alguém (shell, adb) desligou a hora automática: liga de novo.
        assertEquals(Acao.TRAVAR, acao(ligada = true, restricao = true, auto = false))
    }

    @Test fun ligadaETudoCertoNaoMexe() {
        assertEquals(Acao.NADA, acao(ligada = true, restricao = true, auto = true))
    }

    @Test fun desligadaDestravaSoOQueNosPusemos() {
        assertEquals(Acao.DESTRAVAR, acao(ligada = false, restricao = true, auto = true))
        assertEquals(Acao.DESTRAVAR, acao(ligada = false, restricao = true, auto = false))
    }

    @Test fun desligadaSemTravaNaoTocaEmNada() {
        // Hora de dormir desligada nunca trava, nem mexe na hora automática do dono.
        assertEquals(Acao.NADA, acao(ligada = false, restricao = false, auto = false))
        assertEquals(Acao.NADA, acao(ligada = false, restricao = false, auto = true))
    }

    @Test fun semDeviceOwnerNaoFazNada() {
        for (ligada in listOf(true, false)) for (r in listOf(true, false)) for (a in listOf(true, false))
            assertEquals(Acao.NADA, acao(donos = false, ligada = ligada, restricao = r, auto = a))
    }

    @Test fun aoDestravarVoltaAHoraAutomaticaComoEstava() {
        assertTrue(TravaRelogioRegra.desligarHoraAutomaticaAoDestravar(false))   // estava desligada: volta a desligar
        assertFalse(TravaRelogioRegra.desligarHoraAutomaticaAoDestravar(true))   // já estava ligada: fica
        assertFalse(TravaRelogioRegra.desligarHoraAutomaticaAoDestravar(null))   // não sabemos: fica ligada (o normal)
    }
}

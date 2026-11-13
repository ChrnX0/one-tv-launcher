package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaudeAcessoTest {
    private val min = 60_000L
    private val limite = 30 * min
    private val intervalo = 6 * 60 * min

    @Test fun quedaComecaNaPrimeiraFalhaENaoReiniciaNasSeguintes() {
        var i = SaudeRegras.observar(SaudeRegras.Item(), true, 0)
        i = SaudeRegras.observar(i, false, 10 * min)
        assertEquals(10 * min, i.foraDesde)
        i = SaudeRegras.observar(i, false, 20 * min)
        assertEquals(10 * min, i.foraDesde)   // continua a mesma queda
        i = SaudeRegras.observar(i, true, 25 * min)
        assertEquals(true, i.ok)
        assertEquals(SaudeRegras.Item(), SaudeRegras.observar(i, null, 30 * min))   // desligado = neutro
    }

    @Test fun avisaSoDepoisDoLimiteEUmaVezPorQueda() {
        val fora = SaudeRegras.Item(ok = false, foraDesde = 0)
        assertFalse(SaudeRegras.precisaAvisar(fora, limite - 1, limite, 0, 1_000, intervalo))
        assertTrue(SaudeRegras.precisaAvisar(fora, limite, limite, 0, 1_000, intervalo))
        assertFalse(SaudeRegras.precisaAvisar(fora.copy(avisado = true), limite * 3, limite, 0, 1_000, intervalo))
        // No ar ou desligado: nunca avisa.
        assertFalse(SaudeRegras.precisaAvisar(SaudeRegras.Item(ok = true), limite * 3, limite, 0, 1_000, intervalo))
        assertFalse(SaudeRegras.precisaAvisar(SaudeRegras.Item(), limite * 3, limite, 0, 1_000, intervalo))
    }

    @Test fun quedaDeAntesDeDormirNaoAvisaAoLigarATv() {
        // Caiu às 23h (foraDesde = 0); a TV dormiu 8 h e acordou agora: o tempo fora só conta desde que a tela acendeu.
        val fora = SaudeRegras.Item(ok = false, foraDesde = 0)
        val acordou = 8 * 60 * min
        assertFalse(SaudeRegras.precisaAvisar(fora, acordou + 2_000, limite, 0, 1_000, intervalo, acordou))
        assertFalse(SaudeRegras.precisaAvisar(fora, acordou + limite - 1, limite, 0, 1_000, intervalo, acordou))
        // Continua fora depois de [limite] com a tela acesa: aí sim.
        assertTrue(SaudeRegras.precisaAvisar(fora, acordou + limite, limite, 0, 1_000, intervalo, acordou))
        // Tela apagada (Long.MAX_VALUE): nunca.
        assertFalse(SaudeRegras.precisaAvisar(fora, acordou + limite, limite, 0, 1_000, intervalo, Long.MAX_VALUE))
    }

    @Test fun quedaNovaDepoisDeVoltarAvisaDeNovoMasRespeitaOIntervalo() {
        var i = SaudeRegras.Item(ok = false, foraDesde = 0, avisado = true)
        i = SaudeRegras.observar(i, true, 40 * min)
        i = SaudeRegras.observar(i, false, 50 * min)
        assertFalse(i.avisado)
        val ultimoAviso = 1_000_000L
        // 1 h depois do último aviso (mesmo com o launcher reiniciado): ainda não.
        assertFalse(SaudeRegras.precisaAvisar(i, 50 * min + limite, limite, ultimoAviso, ultimoAviso + 60 * min, intervalo))
        assertTrue(SaudeRegras.precisaAvisar(i, 50 * min + limite, limite, ultimoAviso, ultimoAviso + intervalo, intervalo))
        // Relógio voltou para trás (acerto de hora): não trava o aviso para sempre.
        assertTrue(SaudeRegras.precisaAvisar(i, 50 * min + limite, limite, ultimoAviso, ultimoAviso - 5_000, intervalo))
    }

    @Test fun soAvisaComANossaHomeNaFrente() {
        val p = "br.paulo.tvlauncher"
        assertTrue(SaudeRegras.lugarSeguro(p, "$p.MainActivity", p))
        assertFalse(SaudeRegras.lugarSeguro(p, "$p.PlayerActivity", p))   // o nosso player é vídeo
        assertFalse(SaudeRegras.lugarSeguro("com.netflix.ninja", "com.netflix.ninja.MainActivity", p))
        assertFalse(SaudeRegras.lugarSeguro(Home.LAUNCHERX, "x", p))
        assertFalse(SaudeRegras.lugarSeguro(null, null, p))
    }

    @Test fun jsonDoItem() {
        val o = SaudeRegras.jsonItem(SaudeRegras.Item(ok = false, foraDesde = 0), 45 * min)
        assertEquals("fora", o.getString("estado"))
        assertEquals(45, o.getLong("foraHaMin"))
        assertEquals("ok", SaudeRegras.jsonItem(SaudeRegras.Item(ok = true), 0).getString("estado"))
        assertFalse(SaudeRegras.jsonItem(SaudeRegras.Item(), 0).has("foraHaMin"))
    }
}

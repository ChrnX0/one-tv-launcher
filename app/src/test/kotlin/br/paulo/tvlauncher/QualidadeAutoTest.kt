package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.QualidadeAuto
import br.paulo.tvlauncher.player.VariantesCanal.Nivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualidadeAutoTest {
    private val todas = listOf(Nivel.UHD, Nivel.FHD, Nivel.HD, Nivel.SD)

    @Test fun telaFullHdNuncaPega4K() {
        assertEquals(1, QualidadeAuto.escolher(todas, tela = Nivel.FHD, banda = 100_000_000))
    }

    @Test fun quatroKSoComTela4KEBandaMedida() {
        assertEquals(1, QualidadeAuto.escolher(todas, tela = Nivel.UHD, banda = null))
        assertEquals(1, QualidadeAuto.escolher(todas, tela = Nivel.UHD, banda = 10_000_000))
        assertEquals(0, QualidadeAuto.escolher(todas, tela = Nivel.UHD, banda = 40_000_000))
    }

    @Test fun tetoDasTravadasManda() {
        assertEquals(2, QualidadeAuto.escolher(todas, teto = Nivel.HD))
        assertEquals(3, QualidadeAuto.escolher(todas, teto = Nivel.SEM_MARCA))
    }

    @Test fun manualVenceTudoEDescePeloMaisPerto() {
        assertEquals(0, QualidadeAuto.escolher(todas, manual = Nivel.UHD, tela = Nivel.HD))
        assertEquals(1, QualidadeAuto.escolher(listOf(Nivel.UHD, Nivel.HD, Nivel.SD), manual = Nivel.FHD))
        assertEquals(1, QualidadeAuto.escolher(listOf(Nivel.UHD, Nivel.SD), manual = Nivel.SEM_MARCA))
    }

    @Test fun soTem4KNumaTelaHdTocaO4K() {
        assertEquals(0, QualidadeAuto.escolher(listOf(Nivel.UHD), tela = Nivel.HD))
    }

    @Test fun listaForaDeOrdemPelaResolucaoReal() {
        // O "4K" do nome era 1080 de verdade: empata com o FHD e fica o 1º (o principal da lista).
        assertEquals(0, QualidadeAuto.escolher(listOf(Nivel.FHD, Nivel.FHD, Nivel.HD)))
        assertEquals(1, QualidadeAuto.escolher(listOf(Nivel.SD, Nivel.FHD, Nivel.HD)))
    }

    @Test fun alturas() {
        assertEquals(Nivel.UHD, QualidadeAuto.nivelDaTela(2160))
        assertEquals(Nivel.FHD, QualidadeAuto.nivelDaTela(1080))
        assertEquals(Nivel.FHD, QualidadeAuto.nivelDaTela(0))
        assertEquals(Nivel.HD, QualidadeAuto.nivelPelaAltura(720))
        assertEquals(Nivel.SD, QualidadeAuto.nivelPelaAltura(576))
        assertNull(QualidadeAuto.nivelPelaAltura(0))
    }

    @Test fun tetoSobeSozinhoComOTempo() {
        assertEquals(Nivel.HD, QualidadeAuto.tetoAgora(Nivel.HD, 0, QualidadeAuto.TETO_MS - 1))
        assertEquals(Nivel.FHD, QualidadeAuto.tetoAgora(Nivel.HD, 0, QualidadeAuto.TETO_MS))
        assertNull(QualidadeAuto.tetoAgora(Nivel.HD, 0, 2 * QualidadeAuto.TETO_MS))
        assertEquals(Nivel.SEM_MARCA, QualidadeAuto.abaixo(Nivel.HD))
        assertEquals(Nivel.SD, QualidadeAuto.abaixo(Nivel.SD))
    }

    @Test fun travadas() {
        val t = QualidadeAuto.Travadas()
        assertTrue(t.registrar(0, QualidadeAuto.TRAVADA_LONGA_MS))
        assertFalse(t.registrar(1_000, 800))
        assertFalse(t.registrar(1_000 + QualidadeAuto.JANELA_TRAVADAS_MS + 1, 800))   // a 1ª curta já venceu
        assertTrue(t.registrar(2_000 + QualidadeAuto.JANELA_TRAVADAS_MS, 800))
    }
}

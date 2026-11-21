package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.LayoutHome
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutHomeTest {
    @Test fun opcoesDaCruzTemPadraoEValorEstranhoVoltaAoPadrao() {
        assertEquals(LayoutHome.BarraCruz.RECUA, LayoutHome.barraCruz(0))
        assertEquals(LayoutHome.BarraCruz.HOJE, LayoutHome.barraCruz(3))
        assertEquals(LayoutHome.BarraCruz.VIDRO, LayoutHome.barraCruz(4))   // onda 3: entrou no fim, os números antigos valem
        assertEquals(LayoutHome.BarraCruz.ILHA, LayoutHome.barraCruz(5))    // a mistura A+B+D (18/11/2026), também no fim
        assertEquals(LayoutHome.BarraCruz.ILHA, LayoutHome.barraCruz(42))   // valor estranho: o padrão (Ilha, desde 18/11 tarde)
        assertEquals(IconeCruz.Estilo.OBJETO, LayoutHome.iconesCruz(0))
        assertEquals(IconeCruz.Estilo.SIMBOLO, LayoutHome.iconesCruz(1))   // o 1 já gravado antes do Duotom continua Símbolo
        assertEquals(IconeCruz.Estilo.DUOTOM, LayoutHome.iconesCruz(2))
        assertEquals(IconeCruz.Estilo.LINHA, LayoutHome.iconesCruz(3))     // 18/11/2026: Linha entrou no fim
        assertEquals(IconeCruz.Estilo.LINHA, LayoutHome.iconesCruz(-1))    // valor estranho: o padrão (Linha)
    }
}

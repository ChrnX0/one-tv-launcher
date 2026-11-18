package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutHomeTest {
    @Test fun padraoNaoMudaNada() {
        assertEquals(112, LayoutHome.largura(100)); assertEquals(63, LayoutHome.altura(100)); assertEquals(0, LayoutHome.acrescimo(100))
    }

    @Test fun grandeCabeNaTela() {
        val a = LayoutHome.altura(115)
        assertEquals(128, LayoutHome.largura(115)); assertEquals(72, a)
        // linha de apps (429) + acréscimo + cartão + anel de foco (2 x 6) + folga de crescer: dentro dos 540 dp
        assertTrue(429 + LayoutHome.acrescimo(115) + a + 12 <= 540)
    }

    @Test fun gradeCabe() {
        // passo de um cartão normal: 112 + 2 x 6 de anel + 4 = 128 dp; entre as margens de 48 cabem 6
        assertEquals(6, LayoutHome.colunas(128, 48)); assertEquals(3, LayoutHome.colunas(900, 48))
    }

    @Test fun estranhoVoltaAoNormal() {
        assertEquals(112, LayoutHome.largura(300)); assertEquals(100, LayoutHome.proximo(115)); assertEquals(100, LayoutHome.proximo(7))
    }

    @Test fun cruzESempreAMenosQueOAdbPecaAAntiga() {
        assertTrue(LayoutHome.cruz(0)); assertTrue(LayoutHome.cruz(1)); assertTrue(LayoutHome.cruz(99))
        assertTrue(!LayoutHome.cruz(LayoutHome.JEITO_ANTIGA))
    }

    @Test fun opcoesDaCruzTemPadraoEValorEstranhoVoltaAoPadrao() {
        assertEquals(LayoutHome.BarraCruz.RECUA, LayoutHome.barraCruz(0))
        assertEquals(LayoutHome.BarraCruz.HOJE, LayoutHome.barraCruz(3))
        assertEquals(LayoutHome.BarraCruz.VIDRO, LayoutHome.barraCruz(4))   // onda 3: entrou no fim, os números antigos valem
        assertEquals(LayoutHome.BarraCruz.VIDRO, LayoutHome.barraCruz(42))  // valor estranho: o padrão (Vidro)
        assertEquals(IconeCruz.Estilo.OBJETO, LayoutHome.iconesCruz(0))
        assertEquals(IconeCruz.Estilo.SIMBOLO, LayoutHome.iconesCruz(1))   // o 1 já gravado antes do Duotom continua Símbolo
        assertEquals(IconeCruz.Estilo.DUOTOM, LayoutHome.iconesCruz(2))
        assertEquals(IconeCruz.Estilo.OBJETO, LayoutHome.iconesCruz(-1))
    }
}

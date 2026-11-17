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
}

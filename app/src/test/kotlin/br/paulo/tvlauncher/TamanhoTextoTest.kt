package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class TamanhoTextoTest {
    @Test fun ciclaEDaAVolta() {
        assertEquals(110, TamanhoTexto.proximo(100))
        assertEquals(120, TamanhoTexto.proximo(110))
        assertEquals(100, TamanhoTexto.proximo(120))
    }

    @Test fun valorEstranhoVoltaAoNormal() {
        assertEquals(100, TamanhoTexto.valido(250))
        assertEquals(100, TamanhoTexto.proximo(37))   // fora da lista: o próximo é o normal
        assertEquals(120, TamanhoTexto.valido(120))
    }

    /** Teto de 120%: acima disso o texto não cabe nos blocos de altura fixa (painel rápido, pôsteres). */
    @Test fun maximoEh120() = assertEquals(120, TamanhoTexto.OPCOES.max())
}

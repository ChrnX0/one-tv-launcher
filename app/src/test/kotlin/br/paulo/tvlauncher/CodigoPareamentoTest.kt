package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodigoPareamentoTest {
    @Test fun achaOCodigoEntreOsTextosDaTela() {
        val tela = listOf("Parear com o dispositivo", "Código de pareamento Wi-Fi", "482 913", "Endereço IP e porta", "192.168.0.10:37123")
        assertEquals("482913", CodigoPareamento.achar(tela))
    }

    @Test fun naoAdivinhaComDoisCandidatosNemSemNenhum() {
        assertNull(CodigoPareamento.achar(listOf("111111", "222222")))
        assertNull(CodigoPareamento.achar(listOf("Parear", "37123", "1234567")))
        assertEquals("111111", CodigoPareamento.achar(listOf("111111", "111 111")))   // o mesmo, repetido
    }
}

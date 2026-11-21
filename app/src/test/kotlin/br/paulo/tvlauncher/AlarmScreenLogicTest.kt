package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.AlarmScreenLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contas da tela do despertador no jeito da cruz (18/11/2026): roletes, "Repetir" e quando o VOLTAR salva. */
class AlarmScreenLogicTest {
    @Test fun horaDaAVolta() {
        assertEquals(0, AlarmScreenLogic.girarHora(23, 1))
        assertEquals(23, AlarmScreenLogic.girarHora(0, -1))
        assertEquals(8, AlarmScreenLogic.girarHora(7, 1))
    }

    @Test fun minutoUmAUmEDe5Em5Segurando() {
        assertEquals(31, AlarmScreenLogic.girarMinuto(30, sobe = true, rapido = false))
        assertEquals(59, AlarmScreenLogic.girarMinuto(0, sobe = false, rapido = false))
        assertEquals(0, AlarmScreenLogic.girarMinuto(59, sobe = true, rapido = false))
        // segurando: vai ao próximo múltiplo de 5, sem pular o ponto
        assertEquals(10, AlarmScreenLogic.girarMinuto(7, sobe = true, rapido = true))
        assertEquals(15, AlarmScreenLogic.girarMinuto(10, sobe = true, rapido = true))
        assertEquals(0, AlarmScreenLogic.girarMinuto(55, sobe = true, rapido = true))
        assertEquals(5, AlarmScreenLogic.girarMinuto(7, sobe = false, rapido = true))
        assertEquals(5, AlarmScreenLogic.girarMinuto(10, sobe = false, rapido = true))
        assertEquals(55, AlarmScreenLogic.girarMinuto(0, sobe = false, rapido = true))
        assertEquals(0, AlarmScreenLogic.girarMinuto(3, sobe = false, rapido = true))
    }

    @Test fun repetirProntosEDiasAvulsos() {
        assertEquals(2, AlarmScreenLogic.pronto(0b0111110))
        assertEquals(-1, AlarmScreenLogic.pronto(0b0010100))
        // pronto fecha a lista; dia avulso liga/desliga e reabre
        assertEquals(0b1111111 to false, AlarmScreenLogic.escolherRepetir(0, 1))
        assertEquals(0b0000010 to true, AlarmScreenLogic.escolherRepetir(0, 4 + 1))       // segunda
        assertEquals(0b0111100 to true, AlarmScreenLogic.escolherRepetir(0b0111110, 4 + 1))
        // marcadas: o pronto e os dias (o ✓ aparece nos dois lugares)
        assertEquals(listOf(2, 5, 6, 7, 8, 9), AlarmScreenLogic.marcadasRepetir(0b0111110))
        assertEquals(listOf(0), AlarmScreenLogic.marcadasRepetir(0))
        assertEquals(listOf(6, 8), AlarmScreenLogic.marcadasRepetir(0b0010100))
    }

    @Test fun voltarSoSalvaSeMudou() {
        val v = AlarmScreenLogic.Valores(7, 0, 0b0111110, "", true, 0, 0, true, "")
        assertFalse(AlarmScreenLogic.mudou(v, v))
        assertFalse(AlarmScreenLogic.mudou(v, v.copy(nome = "  ")))   // espaço no nome não conta
        assertTrue(AlarmScreenLogic.mudou(v, v.copy(minuto = 30)))
        assertTrue(AlarmScreenLogic.mudou(v, v.copy(bomDia = false)))
    }

    @Test fun seguraraSetaAndaDevagar() {
        org.junit.Assert.assertTrue(AlarmScreenLogic.passaRepeticao(0, 1000, 1001))      // 1º aperto sempre
        org.junit.Assert.assertFalse(AlarmScreenLogic.passaRepeticao(3, 1000, 1050))     // repetição de 50 ms: segura
        org.junit.Assert.assertTrue(AlarmScreenLogic.passaRepeticao(3, 1000, 1000 + AlarmScreenLogic.RITMO_SEGURANDO_MS))
        // 1 segundo segurando = 4 passos, não 20
        var ultima = 0L; var passos = 0
        for (t in 50L..1000L step 50) if (AlarmScreenLogic.passaRepeticao(1, ultima, t)) { ultima = t; passos++ }
        org.junit.Assert.assertEquals(4, passos)
    }
}

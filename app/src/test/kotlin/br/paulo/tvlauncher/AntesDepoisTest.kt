package br.paulo.tvlauncher

import br.paulo.tvlauncher.AntesDepois.Medida
import br.paulo.tvlauncher.observer.Placar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Elevação 14.3: memória livre e tempo de abrir os 3 apps mais usados, antes e depois da limpeza, em uma linha. */
class AntesDepoisTest {
    private fun frase(a: Medida, d: Medida) = AntesDepois.frase(AntesDepois.comparar(a, d), mapOf("nf" to "Netflix", "yt" to "YouTube"),
        "%1\$d MB livres a mais", "%1\$d MB livres a menos", "%1\$s abre %2\$s mais rápido", "%1\$s abre %2\$s mais devagar",
        "sem diferença") { Placar.tempoTexto(it) }

    @Test fun fraseDeUmaLinha() {
        val a = Medida(500, mapOf("nf" to 3_000L, "yt" to 2_000L), 1)
        val d = Medida(810, mapOf("nf" to 1_800L, "yt" to 1_950L), 2)
        assertEquals("310 MB livres a mais · Netflix abre 1,2 s mais rápido", frase(a, d))   // YouTube: 50 ms é ruído
    }

    @Test fun ruidoNaoViraPromessa() {
        assertEquals("sem diferença", frase(Medida(500, mapOf("nf" to 1_000L), 1), Medida(520, mapOf("nf" to 1_100L), 2)))
    }

    @Test fun piorTambemAparece() {
        assertEquals("40 MB livres a menos · YouTube abre 0,5 s mais devagar",
            frase(Medida(500, mapOf("yt" to 1_000L), 1), Medida(460, mapOf("yt" to 1_500L), 2)))
    }

    @Test fun appQueSoTemUmaMedidaFicaFora() {
        val d = AntesDepois.comparar(Medida(1, mapOf("a" to 1L, "b" to 2L), 1), Medida(1, mapOf("b" to 1L), 2))
        assertEquals(listOf("b" to 1L), d.apps)
    }

    @Test fun leTotalTimeDoAmStart() {
        val s = "Starting: Intent { cmp=x/.Main }\r\nStatus: ok\r\nLaunchState: COLD\r\nActivity: x/.Main\r\nTotalTime: 1234\r\nWaitTime: 1250\r\nComplete\r\n"
        assertEquals(1_234L, AntesDepois.tempoDoAmStart(s))
        assertNull(AntesDepois.tempoDoAmStart("Error: Activity class {x/.Main} does not exist."))
        assertNull(AntesDepois.tempoDoAmStart(null))
        assertNull(AntesDepois.tempoDoAmStart("TotalTime: 0"))
    }

    @Test fun idaEVolta() {
        val m = Medida(321, mapOf("nf" to 1_500L), 99)
        assertEquals(m, AntesDepois.ler(AntesDepois.texto(m)))
        assertNull(AntesDepois.ler("x"))
    }
}

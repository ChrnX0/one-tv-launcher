package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.settings.CorSegura
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 16.3: com o alto contraste ligado, todo texto da paleta passa de 7:1 onde ele aparece. */
class AltoContrasteTest {
    @After fun desligar() { Estilo.altoContraste = false }

    private fun conferir(nome: String, frente: Int, fundo: Int, minimo: Double = AltoContraste.MINIMO) {
        val c = CorSegura.contraste(CorSegura.compor(frente, fundo), fundo)
        assertTrue("$nome: ${"%.2f".format(c)}:1 (mínimo $minimo)", c >= minimo)
    }

    @Test fun textoSobreFundoESuperficie() {
        Estilo.altoContraste = true
        val superficie = CorSegura.compor(Estilo.SUPERFICIE, Estilo.FUNDO)
        for ((n, c) in listOf("TEXTO" to Estilo.TEXTO, "TEXTO_2" to Estilo.TEXTO_2, "TEXTO_3" to Estilo.TEXTO_3,
                "ACENTO" to Estilo.ACENTO, "ATENCAO" to Estilo.ATENCAO, "ERRO" to Estilo.ERRO)) {
            conferir("$n/fundo", c, Estilo.FUNDO)
            conferir("$n/superfície", c, superficie)
        }
    }

    @Test fun textoSobreOFoco() {
        Estilo.altoContraste = true
        conferir("TEXTO_NO_FOCO", Estilo.TEXTO_NO_FOCO, Estilo.FOCO)
        conferir("TEXTO_NO_FOCO_2", Estilo.TEXTO_NO_FOCO_2, Estilo.FOCO)
        conferir("ACENTO_NO_FOCO", Estilo.ACENTO_NO_FOCO, Estilo.FOCO)
    }

    /** A borda é elemento gráfico: o mínimo da WCAG para isso é 3:1 (antes ficava abaixo disso). */
    @Test fun bordaVisivel() {
        Estilo.altoContraste = true
        conferir("BORDA", Estilo.BORDA, Estilo.FUNDO, 3.0)
    }

    @Test fun desligadoNadaMuda() {
        Estilo.altoContraste = false
        assertTrue(Estilo.TEXTO_3 == 0x86FFF3E6.toInt() && Estilo.ACENTO == 0xFFFF6A2B.toInt() && Estilo.ERRO == 0xFFFF5252.toInt())
    }

    @Test fun papelSegueOTema() {
        Estilo.altoContraste = true
        assertTrue(Estilo.Papel.LEGENDA.cor == Estilo.TEXTO_2 && Estilo.TEXTO_2 == Estilo.TEXTO)
    }
}

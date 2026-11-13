package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BordaEscuraTest {
    private val claro = 0xFF9A8F70.toInt()
    private val preto = 0xFF050505.toInt()

    /** Imagem [w]x[h] clara, com [faixa] colunas pretas à esquerda e à direita. */
    private fun comFaixas(w: Int, h: Int, faixa: Int) = IntArray(w * h) { i -> val x = i % w; if (x < faixa || x >= w - faixa) preto else claro }

    @Test fun cortaFaixasLaterais() {
        assertArrayEquals(intArrayOf(3, 0, 97, 50), BordaEscura.limites(comFaixas(100, 50, 3), 100, 50))
    }

    @Test fun semMolduraFicaInteira() {
        assertArrayEquals(intArrayOf(0, 0, 100, 50), BordaEscura.limites(IntArray(100 * 50) { claro }, 100, 50))
    }

    @Test fun pinturaTodaEscuraPerdeNoMaximo6PorCento() {
        // 200x100 toda preta: no máximo 12 colunas e 6 linhas de cada lado.
        assertArrayEquals(intArrayOf(12, 6, 188, 94), BordaEscura.limites(IntArray(200 * 100) { preto }, 200, 100))
    }
}

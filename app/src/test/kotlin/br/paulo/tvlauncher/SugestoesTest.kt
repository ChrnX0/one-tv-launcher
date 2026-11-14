package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SugestoesTest {
    private val fuso = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun em(dia: Int, hora: Int, min: Int = 0): Long = Calendar.getInstance(fuso).apply {
        clear(); set(2026, Calendar.NOVEMBER, dia, hora, min)
    }.timeInMillis

    private fun sinais(madrugada: Int = 0, tarde: Int = 0, desligar: Int = 0, filtro: Boolean = false,
                       dispensadas: Set<String> = emptySet(), ultima: Long = 0) =
        Sugestoes.Sinais(madrugada, tarde, desligar, filtro, dispensadas, ultima, agora = em(14, 12))

    @Test fun madrugadaContaComoANoiteAnterior() {
        // sexta 23h e sábado 1h = a mesma noite (de sexta); domingo 2h = outra
        val m = listOf(em(13, 23), em(14, 1), em(16, 2), em(14, 15))
        assertEquals(2, Sugestoes.noites(m, fuso, 22))
        assertEquals(2, Sugestoes.noites(m, fuso, 0))   // 1h de sábado e 2h de domingo
        assertEquals(0, Sugestoes.noites(listOf(em(14, 5), em(14, 21, 59)), fuso, 22))
    }

    @Test fun desligarSozinhaComTresNoites() {
        assertEquals(Sugestoes.Tipo.DESLIGAR_SOZINHA, Sugestoes.escolher(sinais(madrugada = 3, tarde = 3)))
        assertNull(Sugestoes.escolher(sinais(madrugada = 2)))
        assertNull(Sugestoes.escolher(sinais(madrugada = 5, desligar = 120)))   // já ligado
    }

    @Test fun filtroQuandoNaoHaDesligarParaSugerir() {
        assertEquals(Sugestoes.Tipo.FILTRO_NOTURNO, Sugestoes.escolher(sinais(madrugada = 5, tarde = 4, desligar = 60)))
        assertNull(Sugestoes.escolher(sinais(tarde = 6, filtro = true)))
    }

    @Test fun naoSugerirMaisEIntervalo() {
        assertEquals(Sugestoes.Tipo.FILTRO_NOTURNO,
            Sugestoes.escolher(sinais(madrugada = 5, tarde = 5, dispensadas = setOf("DESLIGAR_SOZINHA"))))
        assertNull(Sugestoes.escolher(sinais(madrugada = 5, ultima = em(13, 12))))   // mostrou ontem
        assertEquals(Sugestoes.Tipo.DESLIGAR_SOZINHA, Sugestoes.escolher(sinais(madrugada = 5, ultima = em(10, 12))))
    }
}

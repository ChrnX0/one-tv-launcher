package br.paulo.tvlauncher

import br.paulo.tvlauncher.DicasClima.Dica
import br.paulo.tvlauncher.DicasClima.Entrada
import br.paulo.tvlauncher.DicasClima.Frase
import br.paulo.tvlauncher.DicasClima.Hora
import br.paulo.tvlauncher.DicasClima.Resposta
import br.paulo.tvlauncher.DicasClima.TipoFrase
import org.junit.Assert.assertEquals
import org.junit.Test

class DicasClimaTest {
    /** 24 horas a partir de [inicio]; [chance]/[temp]/[uv] por hora do relógio; dia = 6h às 18h59. */
    private fun horas(inicio: Int, chance: (Int) -> Int = { 0 }, temp: (Int) -> Double = { 24.0 }, uv: (Int) -> Double = { 0.0 }) =
        (0 until 24).map { i -> val h = (inicio + i) % 24; Hora(h, temp(h), chance(h), uv(h), h in 6..18, inicio + i < 24) }

    private fun e(h: List<Hora>, chovendo: Boolean = false, max: Int = 28, amanha: Int? = 10) = Entrada(chovendo, h, max, amanha)

    @Test fun chovendoDizQuandoPara() {
        val h = horas(14, chance = { if (it < 17) 80 else 10 })
        assertEquals(Frase(TipoFrase.PARA_AS, 17), DicasClima.frase(e(h, chovendo = true)))
        assertEquals(Dica(Resposta.AGORA), DicasClima.guardaChuva(e(h, chovendo = true)))
        assertEquals(Dica(Resposta.MELHOR_NAO), DicasClima.varal(e(h, chovendo = true)))
    }

    @Test fun chovendoSemFimEmDozeHoras() {
        assertEquals(Frase(TipoFrase.CHUVA_SEGUE), DicasClima.frase(e(horas(14, chance = { 90 }), chovendo = true)))
    }

    @Test fun chuvaChegandoUsaOPicoDasTresHoras() {
        val h = horas(9, chance = { when (it) { 15 -> 55; 16 -> 80; 17 -> 60; else -> 10 } })
        assertEquals(Frase(TipoFrase.CHUVA_AS, 15, 80), DicasClima.frase(e(h)))
        assertEquals(Dica(Resposta.SIM_AS, 15), DicasClima.guardaChuva(e(h)))
        assertEquals(Dica(Resposta.ATE, 15), DicasClima.varal(e(h)))   // recolher antes das 15h
    }

    @Test fun chuvaEmMenosDeDuasHorasNaoEstendeRoupa() {
        val h = horas(13, chance = { if (it == 14) 45 else 5 })
        assertEquals(Dica(Resposta.MELHOR_NAO), DicasClima.varal(e(h)))
        assertEquals(Dica(Resposta.TALVEZ), DicasClima.guardaChuva(e(h)))   // 45% não passa de 50: talvez
    }

    @Test fun diaSecoSemNadaDeMais() {
        val h = horas(8)
        assertEquals(Frase(TipoFrase.CALMO, 28), DicasClima.frase(e(h)))
        assertEquals(Dica(Resposta.NAO), DicasClima.guardaChuva(e(h)))
        assertEquals(Dica(Resposta.SECA_HOJE), DicasClima.varal(e(h)))
        assertEquals(Dica(Resposta.AGORA), DicasClima.sair(e(h)))
    }

    @Test fun calorComAHoraDoPico() {
        val h = horas(9, temp = { if (it == 15) 34.2 else 28.0 })
        assertEquals(Frase(TipoFrase.CALOR, 34, 15), DicasClima.frase(e(h, max = 34)))
        // De 9h a 18h passa de 29° só às 15h: 9h já serve para sair.
        assertEquals(Dica(Resposta.AGORA), DicasClima.sair(e(h, max = 34)))
    }

    @Test fun frioNaMadrugada() {
        assertEquals(Frase(TipoFrase.FRIO, 9), DicasClima.frase(e(horas(18, temp = { if (it == 4) 9.0 else 14.0 }))))
    }

    @Test fun solForteDizOIntervalo() {
        val h = horas(8, uv = { if (it in 10..14) 9.0 else 2.0 })
        assertEquals(Frase(TipoFrase.SOL_FORTE, 10, 15), DicasClima.frase(e(h)))
    }

    @Test fun deNoiteOVaralEOPasseioFicamParaAmanha() {
        val h = horas(21)
        assertEquals(Dica(Resposta.AMANHA_SIM), DicasClima.varal(e(h, amanha = 20)))
        assertEquals(Dica(Resposta.AMANHA_NAO), DicasClima.varal(e(h, amanha = 70)))
        assertEquals(Dica(Resposta.AMANHA_AS, 6), DicasClima.sair(e(h)))
        assertEquals(Frase(TipoFrase.AMANHA_CHUVA, 70), DicasClima.frase(e(h, amanha = 70)))
    }

    @Test fun semHoraBoaParaSair() {
        assertEquals(Dica(Resposta.HOJE_NAO), DicasClima.sair(e(horas(8, temp = { 35.0 }))))
    }

    @Test fun mesmaEntradaMesmaFrase() {
        val h = horas(10, chance = { (it * 7) % 100 })
        assertEquals(DicasClima.frase(e(h)), DicasClima.frase(e(h)))
    }
}

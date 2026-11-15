package br.paulo.tvlauncher

import br.paulo.tvlauncher.Compatibilidade.Veredito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompatibilidadeTest {
    private fun fatos(
        homePreferida: Boolean = false, acessibilidade: Boolean = true, acordar: List<Int> = listOf(800, 900, 1000),
        quedas: Int = 0, horas: Long = 30,
    ) = Compatibilidade.Fatos("Google", "Chromecast", "sabrina", "12", 31, listOf("armeabi-v7a"), 2000, false, true, false,
        "1.0", 458000, true, acessibilidade, homePreferida, 400, acordar, quedas, horas)

    private fun v(f: Compatibilidade.Fatos, id: String) = Compatibilidade.testes(f).first { it.id == id }.veredito

    @Test fun homePelaAcessibilidadeOuPreferencia() {
        assertEquals(Veredito.PASSOU, v(fatos(acessibilidade = true), "home"))
        assertEquals(Veredito.PASSOU, v(fatos(homePreferida = true, acessibilidade = false), "home"))
        assertEquals(Veredito.FALHOU, v(fatos(acessibilidade = false), "home"))
    }

    @Test fun acordarPrecisaDe3MedidasEMenosDe2s() {
        assertEquals(Veredito.MEDINDO, v(fatos(acordar = listOf(500, 600)), "acordar"))
        assertEquals(Veredito.PASSOU, v(fatos(acordar = listOf(500, 1999, 3000)), "acordar"))   // mediana 1999
        assertEquals(Veredito.FALHOU, v(fatos(acordar = listOf(2000, 2500, 900)), "acordar"))   // mediana 2000
    }

    @Test fun estavelPrecisaDe24hSemQueda() {
        assertEquals(Veredito.MEDINDO, v(fatos(horas = 23), "estavel"))
        assertEquals(Veredito.PASSOU, v(fatos(horas = 24), "estavel"))
        assertEquals(Veredito.FALHOU, v(fatos(quedas = 1, horas = 2), "estavel"))
    }

    @Test fun geralIgnoraTelasEFalhaVence() {
        assertEquals(Veredito.PASSOU, Compatibilidade.geral(Compatibilidade.testes(fatos())))
        assertEquals(Veredito.MEDINDO, Compatibilidade.geral(Compatibilidade.testes(fatos(horas = 1))))
        assertEquals(Veredito.FALHOU, Compatibilidade.geral(Compatibilidade.testes(fatos(horas = 1, acessibilidade = false))))
    }

    @Test fun anotarGuardaAs20UltimasEDescartaAbsurdos() {
        var l = emptyList<Int>()
        repeat(25) { l = Compatibilidade.anotarAcordar(l, it) }
        assertEquals((5 until 25).toList(), l)
        assertEquals(l, Compatibilidade.anotarAcordar(l, -1))
        assertEquals(l, Compatibilidade.anotarAcordar(l, 61_000))
    }

    @Test fun jsonSemDadoPessoal() {
        val j = Compatibilidade.json(fatos(), 1L)
        assertEquals("PASSOU", j.getString("geral"))
        assertEquals(4, j.getJSONArray("testes").length())
        val s = j.toString()
        listOf("serial", "conta", "ip", "mac", "nome").forEach { assertFalse(it, Regex("\"$it\"").containsMatchIn(s)) }
    }
}

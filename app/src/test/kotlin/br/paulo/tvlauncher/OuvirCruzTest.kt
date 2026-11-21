package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertEquals
import org.junit.Test

/** As partes puras do redesenho do Ouvir (18/11/2026): recentes, iniciais do quadro sem imagem, bandeira, logo. */
class OuvirCruzTest {
    private fun r(id: String) = Radios.Radio(id, "Rádio $id", "https://x/$id")

    @Test
    fun recenteVaiParaFrenteSemRepetir() {
        val l = listOf(r("a"), r("b"), r("c"))
        assertEquals(listOf("b", "a", "c"), Radios.comRecente(l, r("b")).map { it.id })
        assertEquals(listOf("n", "a", "b", "c"), Radios.comRecente(l, r("n")).map { it.id })
    }

    @Test
    fun recentesTemLimite() {
        var l = emptyList<Radios.Radio>()
        for (i in 1..30) l = Radios.comRecente(l, r("$i"))
        assertEquals(Radios.MAX_RECENTES, l.size)
        assertEquals("30", l.first().id)
    }

    @Test
    fun iniciaisPulamPalavrasDeRadio() {
        assertEquals("MA", CapaOuvir.iniciais("Rádio Mar Azul FM"))
        assertEquals("CB", CapaOuvir.iniciais("CBN"))
        assertEquals("99", CapaOuvir.iniciais("99"))
        assertEquals("FM", CapaOuvir.iniciais("FM"))          // só palavra "vazia": usa ela mesma
        assertEquals("JP", CapaOuvir.iniciais("Jovem Pan - News"))
        assertEquals("", CapaOuvir.iniciais("  ·  "))
    }

    @Test
    fun bandeiraSoComCodigoValido() {
        assertEquals("🇧🇷", CapaOuvir.bandeira("br"))
        assertEquals("", CapaOuvir.bandeira("BRA"))
        assertEquals("", CapaOuvir.bandeira("1A"))
        assertEquals("", CapaOuvir.bandeira(""))
    }

    @Test
    fun logoSoComEnderecoValido() {
        assertEquals("https://x/logo.png", Radios.logoValido(" https://x/logo.png "))
        assertEquals("", Radios.logoValido(null))
        assertEquals("", Radios.logoValido(""))
        assertEquals("", Radios.logoValido("javascript:alert(1)"))
    }
}

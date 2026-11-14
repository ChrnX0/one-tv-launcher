package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfil de criança (ideia 11): quando pede o PIN, quando liga e quando volta o modo infantil e a hora de dormir. */
class PerfilCriancaTest {
    private val criancas = setOf("ana")

    @Test fun adultoParaCriancaLigaSemPin() {
        val t = PerfilCrianca.decidir("paulo", "ana", criancas, temPin = true)
        assertFalse(t.pedePin); assertTrue(t.ligar); assertFalse(t.restaurar)
    }

    @Test fun criancaParaAdultoPedePinERestaura() {
        val t = PerfilCrianca.decidir("ana", "paulo", criancas, temPin = true)
        assertTrue(t.pedePin); assertFalse(t.ligar); assertTrue(t.restaurar)
    }

    @Test fun criancaParaCriancaPedePinEMantem() {
        val t = PerfilCrianca.decidir("ana", "bia", criancas + "bia", temPin = true)
        assertTrue(t.pedePin); assertFalse(t.ligar); assertFalse(t.restaurar)
    }

    @Test fun adultoParaAdultoENadaMudaNoMesmo() {
        assertTrue(PerfilCrianca.decidir("paulo", "mae", criancas, temPin = true).nada)
        assertTrue(PerfilCrianca.decidir("ana", "ana", criancas, temPin = true).nada)
        // Sem perfil ativo antes (primeira escolha): só liga se o destino for de criança.
        assertTrue(PerfilCrianca.decidir(null, "ana", criancas, temPin = true).ligar)
    }

    @Test fun semPinDefinidoNaoPedeMasNaoDeixaMarcar() {
        assertFalse(PerfilCrianca.decidir("ana", "paulo", criancas, temPin = false).pedePin)
        assertFalse(PerfilCrianca.podeMarcar(temPin = false))
        assertTrue(PerfilCrianca.podeMarcar(temPin = true))
    }

    @Test fun guardaOEstadoDeAntesUmaVezSo() {
        val antes = PerfilCrianca.Estado(infantil = false, dormir = true)
        val guardado = PerfilCrianca.guardar(null, antes)
        assertEquals("i0d1", guardado)
        // Entrar de novo (já com tudo ligado) não troca o guardado.
        assertEquals("i0d1", PerfilCrianca.guardar(guardado, PerfilCrianca.LIGADO))
        // Guardado estragado: guarda o de agora.
        assertEquals("i1d1", PerfilCrianca.guardar("lixo", PerfilCrianca.LIGADO))
        assertEquals(antes, PerfilCrianca.aoSair(guardado, PerfilCrianca.LIGADO))
    }

    @Test fun semGuardadoFicaComoEsta() {
        assertEquals(PerfilCrianca.LIGADO, PerfilCrianca.aoSair(null, PerfilCrianca.LIGADO))
        assertNull(PerfilCrianca.deTexto("i2d0"))
    }

    @Test fun alternaAMarca() {
        assertEquals(setOf("ana", "bia"), PerfilCrianca.alternar(criancas, "bia"))
        assertEquals(emptySet<String>(), PerfilCrianca.alternar(criancas, "ana"))
        assertEquals(criancas, PerfilCrianca.alternar(criancas, ""))
        assertEquals("ana", PerfilCrianca.nomeCurto("ana@gmail.com"))
    }
}

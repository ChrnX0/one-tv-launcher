package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.BuscaAjustes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaAjustesTest {
    private val g = BuscaAjustes.grupos("letra,fonte,texto,tamanho;wifi,rede,internet;protetor,descanso,screensaver")
    private val e = listOf(
        BuscaAjustes.Entrada(2, "Tamanho do texto", "Vale para todas as telas do launcher"),
        BuscaAjustes.Entrada(5, "Rede da casa", "Wi-Fi e cabo"),
        BuscaAjustes.Entrada(2, "Tela de descanso", "O que aparece quando a TV fica parada"),
        BuscaAjustes.Entrada(7, "Senha do painel", "PIN pedido no celular"),
    )

    @Test fun por_nome() { assertEquals("Tamanho do texto", BuscaAjustes.procurar(e, "tamanho", g).first().titulo) }

    @Test fun por_sinonimo() {
        assertEquals("Tamanho do texto", BuscaAjustes.procurar(e, "fonte", g).single().titulo)
        assertEquals("Tamanho do texto", BuscaAjustes.procurar(e, "letra", g).single().titulo)
        assertEquals("Tela de descanso", BuscaAjustes.procurar(e, "protetor", g).single().titulo)
        assertEquals("Rede da casa", BuscaAjustes.procurar(e, "internet", g).single().titulo)
    }

    @Test fun titulo_antes_da_explicacao() {
        val r = BuscaAjustes.procurar(e + BuscaAjustes.Entrada(1, "Senha", "a do texto"), "texto", g)
        assertEquals("Tamanho do texto", r.first().titulo)
    }

    @Test fun todas_as_palavras_e_uma_letra_errada() {
        assertTrue(BuscaAjustes.procurar(e, "senha wifi", g).isEmpty())
        assertEquals("Senha do painel", BuscaAjustes.procurar(e, "painle", g).single().titulo)
    }

    @Test fun mexidos_ha_pouco() {
        var l = emptyList<BuscaAjustes.Recente>()
        for (i in 1..10) l = BuscaAjustes.lembrar(l, 0, "linha $i", i.toLong())
        assertEquals(BuscaAjustes.MAX_RECENTES, l.size)
        assertEquals("linha 10", l.first().titulo)
        l = BuscaAjustes.lembrar(l, 0, "linha 5", 99)
        assertEquals("linha 5", l.first().titulo); assertEquals(BuscaAjustes.MAX_RECENTES, l.size)
        assertEquals(l, BuscaAjustes.ler(BuscaAjustes.texto(l)))
        assertTrue(BuscaAjustes.ler("lixo").isEmpty())
    }
}

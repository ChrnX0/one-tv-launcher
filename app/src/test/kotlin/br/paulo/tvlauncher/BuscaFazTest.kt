package br.paulo.tvlauncher

import br.paulo.tvlauncher.mcp.CaixaComando
import br.paulo.tvlauncher.search.BuscaFaz
import br.paulo.tvlauncher.search.BuscaUnica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaFazTest {
    private val apps = mapOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube")
    private fun p(f: String) = BuscaFaz.entender(f, apps)?.pedido
    private fun l(f: String) = BuscaFaz.entender(f, apps)?.local

    // ---- pelo menos 10 comandos que executam
    @Test fun desligar_em_30() { assertEquals(CaixaComando.Pedido("desligar_em", numero = 30), p("desligar em 30 minutos")) }
    @Test fun cancelar_desligar() { assertEquals(0, p("cancelar o desligamento")!!.numero) }
    @Test fun timer() { assertEquals(CaixaComando.Pedido("timer", numero = 10), p("avisa em 10 minutos")) }
    @Test fun volume() {
        assertEquals("subir", p("aumentar volume")!!.acao)
        assertEquals("descer", p("abaixar o volume")!!.acao)
        assertEquals("mudo", p("mudo")!!.acao)
    }
    @Test fun midia() { assertEquals("pausar", p("pausa")!!.acao); assertEquals("tocar", p("continuar")!!.acao) }
    @Test fun abrir_tela() { assertEquals(CaixaComando.Pedido("abrir_tela", alvo = "previsao"), p("abre a previsão")) }
    @Test fun abrir_app_com_verbo() { assertEquals("com.netflix.ninja", p("abre a netflix")!!.alvo) }
    @Test fun wifi_visita() {
        assertEquals(BuscaFaz.Local.WIFI_VISITA, l("wifi visita"))
        assertEquals(BuscaFaz.Local.WIFI_VISITA, l("wi-fi das visitas"))
        assertEquals(BuscaFaz.Local.WIFI_VISITA, l("guest wifi"))
    }
    @Test fun letra() {
        assertEquals(BuscaFaz.Local.LETRA_MAIOR, l("letra maior"))
        assertEquals(BuscaFaz.Local.LETRA_MAIOR, l("bigger text"))
        assertEquals(BuscaFaz.Local.LETRA_MENOR, l("letra menor"))
    }
    @Test fun modo_visita() {
        val liga = p("modo visita")!!
        assertEquals("modo_visita", liga.tipo); assertTrue(liga.json().contains("\"ligado\":true"))
        assertTrue(p("desligar modo visita")!!.json().contains("\"ligado\":false"))
    }
    @Test fun filtro_noturno() { assertEquals("filtro_noturno", p("luz noturna")!!.tipo) }

    // ---- o que é só busca não vira pedido
    @Test fun so_o_nome_nao_e_pedido() {
        assertNull(BuscaFaz.entender("netflix", apps))
        assertNull(BuscaFaz.entender("letra", apps))          // sem maior/menor: busca comum
        assertNull(BuscaFaz.entender("vi", apps))
        assertNull(BuscaFaz.entender("breaking bad", apps))
    }

    // ---- erro de uma letra
    @Test fun uma_letra() {
        assertTrue(BuscaUnica.umaLetra("netflx", "netflix"))   // faltando
        assertTrue(BuscaUnica.umaLetra("netfliix", "netflix")) // sobrando
        assertTrue(BuscaUnica.umaLetra("netflox", "netflix"))  // trocada
        assertTrue(BuscaUnica.umaLetra("nteflix", "netflix"))  // vizinhas
        assertFalse(BuscaUnica.umaLetra("ntflx", "netflix"))   // duas
        assertFalse(BuscaUnica.umaLetra("abcd", "dcba"))
    }

    @Test fun busca_tolerante() {
        val p = BuscaUnica.palavras("brakinG bad")
        assertEquals(4, BuscaUnica.pontosNorm("breaking bad", p, tolerante = true))
        assertNull(BuscaUnica.pontosNorm("breaking bad", p))                     // sem tolerância: não bate
        assertEquals(1, BuscaUnica.pontosNorm("breaking bad", BuscaUnica.palavras("breaking"), tolerante = true))  // certo vem antes
        assertNull(BuscaUnica.pontosNorm("sbt", BuscaUnica.palavras("sic"), tolerante = true))     // palavra curta tem de vir certa
        assertEquals(4, BuscaUnica.pontosNorm("globoplay", BuscaUnica.palavras("glbo"), tolerante = true))   // começo com uma letra trocada
    }
}

package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 2 (14/11/2026): as ações novas do catálogo fechado (ComandosIa) e o filtro noturno forçado. */
class CatalogoAcoesTest {
    private fun um(json: String) = ComandosIa.conferir("""{"acoes":[$json]}""", emptyMap())

    @Test fun recadoLimpaTextoEPrendeDuracao() {
        val a = um("""{"tipo":"recado","texto":"  Jantar   pronto! ","segundos":999}""").acoes.single() as ComandosIa.Acao.Recado
        assertEquals("Jantar pronto!", a.texto)
        assertEquals(60, a.segundos)
        assertEquals(300, (um("""{"tipo":"recado","texto":"oi","segundos":300}""").acoes.single() as ComandosIa.Acao.Recado).segundos)
        assertTrue(um("""{"tipo":"recado","texto":"   "}""").acoes.isEmpty())
        assertTrue(um("""{"tipo":"recado","texto":"${"x".repeat(201)}"}""").acoes.isEmpty())
    }

    @Test fun timerEDesligarConferemMinutos() {
        val t = um("""{"tipo":"timer","minutos":10,"nome":"bolo<script>"}""").acoes.single() as ComandosIa.Acao.Timer
        assertEquals(10, t.minutos); assertEquals("boloscript", t.nome)
        assertTrue(um("""{"tipo":"timer","minutos":0}""").acoes.isEmpty())
        assertTrue(um("""{"tipo":"timer","minutos":241}""").acoes.isEmpty())
        assertTrue(um("""{"tipo":"timer","minutos":2.5}""").acoes.isEmpty())
        assertEquals(0, (um("""{"tipo":"desligar_em","minutos":0}""").acoes.single() as ComandosIa.Acao.DesligarEm).minutos)
        assertEquals(30, (um("""{"tipo":"desligar_em","minutos":30}""").acoes.single() as ComandosIa.Acao.DesligarEm).minutos)
        assertTrue(um("""{"tipo":"desligar_em","minutos":481}""").acoes.isEmpty())
        assertTrue(um("""{"tipo":"desligar_em"}""").acoes.isEmpty())
    }

    @Test fun filtroCenaEMural() {
        assertTrue((um("""{"tipo":"filtro_noturno","ligado":true}""").acoes.single() as ComandosIa.Acao.FiltroNoturno).ligado)
        assertTrue(um("""{"tipo":"filtro_noturno","ligado":"sim"}""").acoes.isEmpty())
        assertEquals("Cinema", (um("""{"tipo":"rodar_cena","cena":" Cinema "}""").acoes.single() as ComandosIa.Acao.RodarCena).cena)
        assertTrue(um("""{"tipo":"rodar_cena","cena":"../x"}""").acoes.isEmpty())
        val m = um("""{"tipo":"mural","lista":"compras","texto":"leite\n integral"}""").acoes.single() as ComandosIa.Acao.Mural
        assertEquals(Mural.Lista.COMPRAS, m.lista); assertEquals("leite integral", m.texto)
        assertTrue(um("""{"tipo":"mural","lista":"senhas","texto":"x"}""").acoes.isEmpty())
        assertTrue(um("""{"tipo":"mural","lista":"recados","texto":" "}""").acoes.isEmpty())
    }

    @Test fun filmesESeriesSoLevamOTexto() {
        assertEquals("Duna", (um("""{"tipo":"tocar_titulo","titulo":" Duna "}""").acoes.single() as ComandosIa.Acao.TocarTitulo).pedido)
        assertTrue(um("""{"tipo":"tocar_titulo"}""").acoes.isEmpty())
        assertEquals("", (um("""{"tipo":"proximo_episodio"}""").acoes.single() as ComandosIa.Acao.ProximoEpisodio).pedido)
        assertTrue(um("""{"tipo":"continuar"}""").acoes.single() is ComandosIa.Acao.Continuar)
        assertTrue(um("""{"tipo":"o_que_estava_vendo"}""").acoes.single() is ComandosIa.Acao.OQueEstavaVendo)
    }

    @Test fun esquemaListaTodosOsTiposEContinuaFechado() {
        val tipos = ComandosIa.esquema().getJSONObject("properties").getJSONObject("acoes").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("tipo").getJSONArray("enum")
        val lista = (0 until tipos.length()).map { tipos.getString(it) }
        assertEquals(ComandosIa.TIPOS, lista)
        for (proibido in listOf("shell", "desinstalar", "permissao", "dns", "vpn", "arquivo")) assertFalse(proibido in lista)
        // Todo tipo do esquema é aceito pela conferência (nenhum cai no "não está no catálogo").
        for (t in lista) assertFalse(t, um("""{"tipo":"$t"}""").recusadas.any { "não está no catálogo" in it })
    }

    @Test fun filtroForcadoValeAteAViradaDoHorario() {
        val ini = 22 * 60; val fim = 6 * 60
        assertEquals(7 * 60, FiltroNoturno.minutosAteVirar(15 * 60, ini, fim))   // 15h: até as 22h
        assertEquals(7 * 60, FiltroNoturno.minutosAteVirar(23 * 60, ini, fim))   // 23h: até as 6h
        assertEquals(FiltroNoturno.MAX_FORCADO_MIN, FiltroNoturno.minutosAteVirar(6 * 60 + 1, 6 * 60, 6 * 60))   // janela vazia
        assertEquals(1, FiltroNoturno.minutosAteVirar(21 * 60 + 59, ini, fim))
        assertTrue(FiltroNoturno.deveLigar(agendado = false, forcado = true, ateMs = 100, agoraMs = 50))
        assertFalse(FiltroNoturno.deveLigar(agendado = false, forcado = true, ateMs = 100, agoraMs = 100))
        assertFalse(FiltroNoturno.deveLigar(agendado = true, forcado = false, ateMs = 100, agoraMs = 50))
        assertTrue(FiltroNoturno.deveLigar(agendado = true, forcado = null, ateMs = 0, agoraMs = 50))
    }
}

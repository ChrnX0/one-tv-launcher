package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 60 (14/11/2026): Filmes e Séries no catálogo de ações (VodBusca, pura). */
class VodBuscaTest {
    private val duna = Filme("1", "c", "Duna (2021)", grupo = "Ficção")
    private val duna2 = Filme("2", "c", "BR: Duna Parte Dois", grupo = "Ficção")
    private val ruptura = Serie("10", "c", "Ruptura", grupo = "Séries")
    private val eps = FichaSerie(temporadas = mapOf(
        1 to listOf(Episodio("e1", 1, 1, "A"), Episodio("e2", 1, 2, "B")),
        2 to listOf(Episodio("e3", 2, 1, "C"))))
    private var fichasPedidas = 0

    private fun dados(posicoes: Map<String, Posicao> = emptyMap(), ultimos: Map<String, UltimoEpisodio> = emptyMap(), semFicha: Boolean = false) =
        VodBusca.Dados(listOf(duna, duna2), listOf(ruptura), posicoes, ultimos) { fichasPedidas++; if (semFicha) null else eps }

    private fun resolver(json: String, d: VodBusca.Dados?) =
        VodBusca.resolver(ComandosIa.conferir("""{"acoes":[$json]}""", emptyMap()), d) { it.joinToString("|").ifEmpty { "nada" } }

    private fun ultimo(id: String, t: Int, n: Int, quando: Long) = UltimoEpisodio(id, t, n, "", "mp4", quando)

    @Test fun achaPeloNomeIgualAntesDoParecido() {
        assertEquals(duna, (VodBusca.achar("duna", dados()) as VodBusca.Item.DeFilme).filme)
        assertEquals(duna2, (VodBusca.achar("duna parte", dados()) as VodBusca.Item.DeFilme).filme)
        assertNull(VodBusca.achar("matrix", dados()))
        assertTrue(VodBusca.achar("ruptura", dados()) is VodBusca.Item.DeSerie)
    }

    @Test fun tocarFilmeNaoBaixaFicha() {
        val p = resolver("""{"tipo":"tocar_titulo","titulo":"duna"}""", dados())
        val a = p.acoes.single() as ComandosIa.Acao.TocarTitulo
        assertEquals(VodActivity.TIPO_FILME, a.alvo!!.tipo); assertEquals("1", a.alvo!!.id); assertEquals("Duna", a.alvo!!.titulo)
        assertEquals(0, fichasPedidas)
    }

    @Test fun serieContinuaOuPassaParaOSeguinte() {
        // Episódio e1 visto até o fim: a sugestão é o e2.
        val d = dados(posicoes = mapOf("c:ee1" to Posicao(99_000, 100_000, 5)), ultimos = mapOf(ruptura.chave to ultimo("e1", 1, 1, 5)))
        val a = resolver("""{"tipo":"tocar_titulo","titulo":"ruptura"}""", d).acoes.single() as ComandosIa.Acao.TocarTitulo
        assertEquals("e2", a.alvo!!.id); assertEquals(VodActivity.TIPO_EPISODIO, a.alvo!!.tipo); assertEquals(ruptura.chave, a.alvo!!.serieChave)
    }

    @Test fun proximoEpisodioPassaDeTemporadaEParaNoFim() {
        val d = dados(ultimos = mapOf(ruptura.chave to ultimo("e2", 1, 2, 5)))
        val a = resolver("""{"tipo":"proximo_episodio"}""", d).acoes.single() as ComandosIa.Acao.ProximoEpisodio
        assertEquals("e3", a.alvo!!.id); assertTrue(a.alvo!!.doComeco)
        val fim = resolver("""{"tipo":"proximo_episodio","titulo":"ruptura"}""", dados(ultimos = mapOf(ruptura.chave to ultimo("e3", 2, 1, 5))))
        assertTrue(fim.acoes.isEmpty()); assertEquals(1, fim.recusadas.size)
        assertTrue(resolver("""{"tipo":"proximo_episodio"}""", dados()).recusadas.single().contains("nenhuma série"))
        assertTrue(resolver("""{"tipo":"proximo_episodio","titulo":"duna"}""", dados()).acoes.isEmpty())   // filme não tem episódio
    }

    @Test fun continuarPegaOMaisRecente() {
        val d = dados(posicoes = mapOf(duna.chave to Posicao(120_000, 9_000_000, 50)), ultimos = mapOf(ruptura.chave to ultimo("e1", 1, 1, 10)))
        val a = resolver("""{"tipo":"continuar"}""", d).acoes.single() as ComandosIa.Acao.Continuar
        assertEquals("1", a.alvo!!.id)
        assertTrue(resolver("""{"tipo":"continuar"}""", dados()).recusadas.single().contains("nada começado"))
    }

    @Test fun oQueEstavaVendoListaDoMaisNovo() {
        val d = dados(posicoes = mapOf(duna.chave to Posicao(120_000, 9_000_000, 5)), ultimos = mapOf(ruptura.chave to ultimo("e2", 1, 2, 10)))
        val a = resolver("""{"tipo":"o_que_estava_vendo"}""", d).acoes.single() as ComandosIa.Acao.OQueEstavaVendo
        assertEquals("Ruptura (T1 E2)|Duna", a.texto)
        assertEquals("nada", (resolver("""{"tipo":"o_que_estava_vendo"}""", dados()).acoes.single() as ComandosIa.Acao.OQueEstavaVendo).texto)
    }

    @Test fun semListasOuSemInternetRecusa() {
        assertTrue(resolver("""{"tipo":"continuar"}""", null).recusadas.single().contains("abra Filmes"))
        val r = resolver("""{"tipo":"tocar_titulo","titulo":"ruptura"}""", dados(semFicha = true))
        assertTrue(r.acoes.isEmpty()); assertTrue(r.recusadas.single().contains("sem internet"))
    }

    @Test fun outrasAcoesPassamIntactas() {
        val p = resolver("""{"tipo":"timer","minutos":5},{"tipo":"tocar_titulo","titulo":"duna"}""", dados())
        assertEquals(2, p.acoes.size); assertTrue(p.acoes[0] is ComandosIa.Acao.Timer)
    }
}

package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.nio.file.Files

/** Correções da auditoria de 10/11/2026 no player (filmes/séries, guia, juntar, Continuar da HOME, UPnP, logos). */
class AuditoriaPlayerTest {
    private fun f(id: String, grupo: String, nome: String = "Filme $id") = Filme(id, "a", nome, grupo)
    private fun c(id: String, grupo: String, n: Int = 1) = Canal(id, "Canal $id", n, grupo, url = "http://e.invalid/$id")

    // ---------------------------------------------------------------- player-1: VOD respeita ocultas e parental

    @Test fun vodEscondeCategoriaAdulta() {
        val filmes = listOf(f("1", "Ação", "Aventura"), f("2", "XXX Adultos", "Aventura quente"))
        val oc = VodFiltro.ocultas(filmes.map { it.grupo }, emptySet(), emptySet(), ControleParental.Regras())
        assertEquals(setOf("XXX Adultos"), oc)
        val vis = VodFiltro.visiveis(filmes, oc) { it.grupo }
        assertEquals(listOf("1"), vis.map { it.id })
        assertTrue(Vod.grupos(vis).none { it.first == "XXX Adultos" })
        assertEquals(listOf("1"), Vod.buscar(vis, "aventura").map { it.id })   // a busca não acha o adulto
    }

    @Test fun vodAdultaLiberadaAparece() {
        val oc = VodFiltro.ocultas(listOf("Ação", "XXX Adultos"), emptySet(), setOf("XXX Adultos"), ControleParental.Regras())
        assertTrue(oc.isEmpty())
    }

    @Test fun vodRespeitaGrupoBloqueadoNoParental() {
        val series = listOf(Serie("1", "a", "Desenho", "Infantil"), Serie("2", "a", "Terror", "Terror"))
        val ligado = ControleParental.Regras(ligado = true, grupos = setOf("Terror", "Só no ao vivo"))
        val oc = VodFiltro.ocultas(series.map { it.grupo }, emptySet(), emptySet(), ligado)
        assertEquals(setOf("Terror"), oc)   // categoria que não existe no catálogo não conta
        assertEquals(listOf("1"), VodFiltro.visiveis(series, oc) { it.grupo }.map { it.id })
        // Desligado: nada bloqueado, mesmo com a lista preenchida.
        assertTrue(VodFiltro.ocultas(series.map { it.grupo }, emptySet(), emptySet(), ligado.copy(ligado = false)).isEmpty())
    }

    @Test fun vodEspacosNasPontasDoGrupo() {
        val filmes = listOf(f("1", " Adultos +18 "))
        val oc = VodFiltro.ocultas(filmes.map { it.grupo }, emptySet(), emptySet(), ControleParental.Regras())
        assertTrue(VodFiltro.visiveis(filmes, oc) { it.grupo }.isEmpty())
    }

    // ---------------------------------------------------------------- player-2: guia cortado no cache

    @Test fun guiaCortadoNaoValeComoCache() {
        val p = listOf(Programa("a", 1000, 2000, "Jornal"), Programa("a", 2000, 3000, "Filme"))
        val json = PlayerDados.programasParaJson(p)
        assertEquals(p, PlayerDados.guiaDoCache(json))
        assertNull(PlayerDados.guiaDoCache(json.dropLast(10)))
        assertNull(PlayerDados.guiaDoCache(""))
        assertNull(PlayerDados.guiaDoCache("[]"))
    }

    @Test fun gravarAtomicoTrocaOArquivoInteiro() {
        val dir = Files.createTempDirectory("cache").toFile()
        try {
            val arq = File(dir, "guia.json")
            arq.writeText("antigo")
            PlayerCarga.gravarAtomico(arq, "novo")
            assertEquals("novo", arq.readText())
            assertFalse(File(dir, "guia.json.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    // ---------------------------------------------------------------- player-3: XML com defeito no meio

    @Test fun guiaComEComercialSoltoNaoSePerde() {
        val xml = "<tv><programme channel=\"a\" start=\"20261110100000 +0000\" stop=\"20261110110000 +0000\"><title>Ok</title></programme>" +
            "<programme channel=\"a\" start=\"20261110110000 +0000\" stop=\"20261110120000 +0000\"><title>Tom & Jerry</title></programme></tv>"
        val r = Xmltv.ler(KXmlParser(), xml.byteInputStream(), setOf("a"))
        // As DUAS entradas: prova o modo tolerante (sem ele o catch salvaria só o "Ok").
        assertEquals(listOf("Ok", "Tom & Jerry"), r.map { it.titulo })
    }

    private fun fluxoQueCai(texto: String, erro: String) = object : java.io.InputStream() {
        val dados = texto.toByteArray(); var i = 0
        override fun read(): Int = if (i < dados.size) dados[i++].toInt() and 0xFF else throw java.io.IOException(erro)
    }

    private val inicioCortado = "<tv><programme channel=\"a\" start=\"20261110100000 +0000\" stop=\"20261110110000 +0000\"><title>Ok</title></programme>" +
        "<programme channel=\"a\" start=\"20261110110000 +0000\" stop=\"20261110120000 +0000\"><title>Corta"

    @Test fun guiaQueBateNoTetoFicaComOQueLeu() {
        // Passou de 150 MB: baixar de novo cortaria no mesmo ponto, então fica com o que leu.
        val r = Xmltv.ler(KXmlParser(), fluxoQueCai(inicioCortado, Xmltv.TETO), setOf("a"))
        assertEquals(listOf("Ok"), r.map { it.titulo })
    }

    @Test(expected = java.io.IOException::class) fun guiaQueCaiPorRedeSobeOErroParaTentarDeNovo() {
        // Rede caiu no meio: o erro sobe (o PlayerCarga tenta de novo), em vez de gravar meio guia como cache fresco.
        Xmltv.ler(KXmlParser(), fluxoQueCai(inicioCortado, "caiu"), setOf("a"))
    }

    // ---------------------------------------------------------------- player-4: descrição menor

    @Test fun descricaoCortadaEm300() {
        val xml = "<tv><programme channel=\"a\" start=\"20261110100000 +0000\" stop=\"20261110110000 +0000\"><title>T</title><desc>" +
            "x".repeat(1000) + "</desc></programme></tv>"
        assertEquals(Xmltv.MAX_DESCRICAO, Xmltv.ler(KXmlParser(), xml.byteInputStream()).single().descricao.length)
    }

    // ---------------------------------------------------------------- player-5: juntar linear

    @Test fun juntarDuasContasGrandesRapidoEUnico() {
        val a = FonteTv("a", "A", FonteTv.TIPO_M3U, "http://a.invalid/l.m3u")
        val b = FonteTv("b", "B", FonteTv.TIPO_M3U, "http://b.invalid/l.m3u")
        val lista = (1..20_000).map { c("c$it", "", it) }
        val t0 = System.nanoTime()
        val r = PlayerDados.juntar(listOf(a to lista, b to lista))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals((1..40_000).toList(), r.map { it.numero })
        assertTrue(r.filter { it.id.startsWith("a:") }.map { it.numero } == (1..20_000).toList())
        assertTrue("juntar levou $ms ms", ms < 1500)
    }

    // ---------------------------------------------------------------- player-7: Continuar da HOME

    @Test fun canalDeCategoriaOcultaNaoVaiParaHome() {
        val adulto = c("1", "Adultos XXX")
        val comum = c("2", "Esportes")
        val oc = PlayerFiltro.ocultasEfetivas(listOf(adulto, comum), emptySet(), emptySet())
        assertFalse(PlayerFiltro.podeIrParaHome(adulto, oc, ControleParental.Regras()))
        assertTrue(PlayerFiltro.podeIrParaHome(comum, oc, ControleParental.Regras()))
        val bloq = ControleParental.Regras(ligado = true, canais = setOf("2"))
        assertFalse(PlayerFiltro.podeIrParaHome(comum, oc, bloq))
    }

    /** Lote 10/11/2026: o "Continuar" confere de novo na hora de mostrar (parental ou modo infantil ligado depois). */
    @Test fun continuarEscondeCanalBloqueadoDepois() {
        val comum = c("2", "Esportes")
        val infantil = ControleParental.Regras(infantil = true, infantis = setOf("Infantil"))
        assertTrue(PlayerFiltro.ultimoPodeAparecer("2", comum, emptySet(), ControleParental.Regras()))
        assertFalse(PlayerFiltro.ultimoPodeAparecer("2", comum, emptySet(), infantil))           // não é infantil
        assertTrue(PlayerFiltro.ultimoPodeAparecer("3", c("3", "Infantil"), emptySet(), infantil))
        assertFalse(PlayerFiltro.ultimoPodeAparecer("2", null, emptySet(), ControleParental.Regras(ligado = true, canais = setOf("2"))))
        assertFalse(PlayerFiltro.ultimoPodeAparecer("9", null, emptySet(), infantil))            // sumiu da lista: na dúvida, esconde
        assertTrue(PlayerFiltro.ultimoPodeAparecer("9", null, emptySet(), ControleParental.Regras()))
    }

    // ---------------------------------------------------------------- player-9: DOCTYPE em qualquer lugar

    @Test fun doctypeDepoisDeComentarioGrandeERecusado() {
        val xml = "<?xml version=\"1.0\"?><!--" + "a".repeat(3000) + "--><!DOCTYPE x [<!ENTITY a \"b\">]><x/>"
        assertTrue(runCatching { Upnp.semDoctype(xml.toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Upnp.semDoctype(xml.toByteArray(Charsets.UTF_16)) }.exceptionOrNull() is IllegalArgumentException)
        assertNotNull(runCatching { Upnp.semDoctype("<x>ok</x>".toByteArray(Charsets.UTF_16)) }.getOrNull())
    }

    // ---------------------------------------------------------------- player-13: logo tenta de novo

    @Test fun logoTentaDeNovoDepoisDe5Minutos() {
        assertFalse(TentativaLogo.pular(null, 1_000))
        assertTrue(TentativaLogo.pular(0, 60_000))
        assertFalse(TentativaLogo.pular(0, 6 * 60_000))
    }
}

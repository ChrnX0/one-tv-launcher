package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.IOException

/**
 * Guia em banco (10/11/2026): leitura em fluxo do XMLTV, consultas puras (agora, próximos, janela) e o cache por canal
 * do Guia. O SQLite fica atrás da FonteGuia: aqui a fonte é em memória (ou uma que conta as consultas).
 */
class GuiaConsultaTest {
    private val h = 3_600_000L
    private fun p(canal: String, ini: Long, fim: Long, t: String) = Programa(canal, ini, fim, t)

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme channel="b" start="20261106020000 +0000" stop="20261106030000 +0000"><title>B2</title></programme>
  <programme channel="a" start="20261106010000 +0000" stop="20261106020000 +0000"><title>A1</title></programme>
  <programme channel="a" start="20261106000000 +0000" stop="20261106010000 +0000"><title>A0</title></programme>
  <programme channel="c" start="20261106000000 +0000" stop="20261106010000 +0000"><title>C0</title></programme>
  <programme channel="a" start="20261107000000 +0000" stop="20261107010000 +0000"><title>Longe</title></programme>
</tv>"""
    private val t0 = Xmltv.data("20261106000000 +0000")!!

    @Test fun fluxoEntregaUmPorUmComCanalJanelaETeto() {
        val vistos = ArrayList<Programa>()
        val n = Xmltv.lerEmFluxo(KXmlParser(), xml.byteInputStream(), setOf("a", "b"), t0, t0 + 14 * h) { vistos += it }
        // Na ordem do arquivo (o banco é que ordena), sem o canal "c" e sem o programa fora da janela.
        assertEquals(listOf("B2", "A1", "A0"), vistos.map { it.titulo })
        assertEquals(3, n)
        // Teto: para no 2º programa entregue.
        val dois = ArrayList<Programa>()
        assertEquals(2, Xmltv.lerEmFluxo(KXmlParser(), xml.byteInputStream(), max = 2) { dois += it })
        assertEquals(2, dois.size)
        // O ler() de antes continua igual: mesma escolha, só que em ordem de canal e início.
        assertEquals(listOf("A0", "A1", "B2"), Xmltv.ler(KXmlParser(), xml.byteInputStream(), setOf("a", "b"), t0, t0 + 14 * h).map { it.titulo })
    }

    @Test fun guiaExtraPreencheSoOsCanaisQueFaltamComOIdNosso() {
        // Ideia 15: o 2º guia tem outros ids; casa pelo display-name normalizado e entrega com o guiaId da nossa lista.
        val extra = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="SPORTV2"><display-name>Sportv-2 HD</display-name></channel>
  <channel id="globo"><display-name>Globo</display-name></channel>
  <channel id="vazio"/>
  <programme channel="SPORTV2" start="20261106000000 +0000" stop="20261106010000 +0000"><title>Jogo</title></programme>
  <programme channel="globo" start="20261106000000 +0000" stop="20261106010000 +0000"><title>Novela</title></programme>
  <programme channel="SPORTV2" start="20261108000000 +0000" stop="20261108010000 +0000"><title>Longe</title></programme>
</tv>"""
        // Globo já tinha programação no guia principal: não está em "faltando".
        val mescla = GuiaExtra.Mescla(GuiaExtra.faltando(listOf("globo.br" to "Globo", "sportv2.br" to "SporTV 2 FHD"), setOf("globo.br")))
        val vistos = ArrayList<Programa>()
        val n = Xmltv.lerExtraEmFluxo(KXmlParser(), extra.byteInputStream(), mescla, t0, t0 + 14 * h, 100) { vistos += it }
        assertEquals(1, n)
        assertEquals(listOf("sportv2.br" to "Jogo"), vistos.map { it.guiaId to it.titulo })
    }

    @Test fun fluxoRedeQueCaiNoMeioSobeOErroDepoisDeEntregar() {
        // Rede caiu no meio depois de entregar programas: o erro SOBE mesmo assim (é isso que faz o PlayerCarga desfazer o
        // lote e tentar de novo). O desfazer em si é do SQLite (GuiaBanco.Gravador.lote) e não roda no teste JVM.
        val corte = xml.substringBefore("<programme channel=\"c\"").toByteArray()
        val fluxo = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < corte.size) corte[i++].toInt() and 0xff else throw IOException("caiu")
        }
        val vistos = ArrayList<Programa>()
        val r = runCatching { Xmltv.lerEmFluxo(KXmlParser(), fluxo) { vistos += it } }
        assertTrue(r.exceptionOrNull() is IOException)
        assertEquals(listOf("B2", "A1", "A0"), vistos.map { it.titulo })   // entregou antes de cair
    }

    @Test fun agoraComFimAberto() {
        val l = listOf(p("a", 0, h, "1"), p("a", h, 2 * h, "2"))
        assertEquals("1", GuiaConsulta.agora(l, 0)?.titulo)
        assertEquals("2", GuiaConsulta.agora(l, h)?.titulo)          // às 21:00 já vale o das 21:00
        assertNull(GuiaConsulta.agora(l, 2 * h))                      // acabou o guia
        assertNull(GuiaConsulta.agora(emptyList(), 0))
    }

    @Test fun proximosRecortaEPulaOQueAcabou() {
        val l = (0 until 6).map { p("a", it * h, (it + 1) * h, "$it") }
        assertEquals(listOf("2", "3", "4"), GuiaConsulta.proximos(l, 2 * h + 1, 3).map { it.titulo })
        assertEquals(listOf("5"), GuiaConsulta.proximos(l, 5 * h, 4).map { it.titulo })
        assertTrue(GuiaConsulta.proximos(l, 0, 0).isEmpty())
        assertTrue(GuiaConsulta.proximos(l, 0, -1).isEmpty())
    }

    @Test fun janelaDe1hAtrasA14hAFrente() {
        assertEquals((10 * h - h) to (10 * h + 14 * h), GuiaConsulta.janela(10 * h))
        assertEquals(1f, GuiaConsulta.progresso(p("a", 0, h, "x"), 2 * h))
    }

    /** Fonte que conta as idas ao "banco", para conferir o cache por canal (zapping sem reler). */
    private class Contadora(val dados: Map<String, List<Programa>>, var falhar: Boolean = false) : FonteGuia {
        val pedidos = ArrayList<String>()
        override fun doCanal(guiaId: String): List<Programa>? { pedidos += guiaId; return if (falhar) null else dados[guiaId].orEmpty() }
        override fun comPrograma(instante: Long): Set<String>? = dados.filterValues { GuiaConsulta.agora(it, instante) != null }.keys
        override fun titulosAgora(instante: Long): Map<String, String>? =
            dados.mapNotNull { (k, l) -> GuiaConsulta.agora(l, instante)?.let { k to it.titulo } }.toMap()
    }

    @Test fun naTelaNaoLeNaHoraPoeNaFilaELeEmBloco() {
        val f = Contadora(mapOf("a" to listOf(p("a", 0, h, "A")), "b" to listOf(p("b", 0, h, "B"))))
        val g = Guia(f)
        val adiadas = ArrayList<Runnable>()
        var avisos = 0
        g.naTela = { true }; g.emSegundoPlano = { adiadas += it }; g.aoCarregar = { avisos++ }
        assertNull(g.agora("a", 10))                                  // na tela: não vai ao banco, mostra vazio...
        assertTrue(g.doCanal("b").isEmpty())
        g.precarregar(listOf("c", ""))
        assertTrue(f.pedidos.isEmpty())
        assertEquals(1, adiadas.size)                                 // ...uma leitura só em segundo plano para a fila toda
        adiadas.single().run()
        assertEquals(listOf("a", "b", "c"), f.pedidos)
        assertEquals(1, avisos)                                       // avisou a tela para redesenhar
        assertEquals("A", g.agora("a", 10)?.titulo)                   // agora vem do cache, sem ir ao banco
        assertEquals(3, f.pedidos.size)
    }

    @Test fun filaFalhaNaoGuardaNemAvisa() {
        val f = Contadora(mapOf("a" to listOf(p("a", 0, h, "A"))), falhar = true)
        val g = Guia(f)
        var avisos = 0
        g.naTela = { true }; g.emSegundoPlano = { it.run() }; g.aoCarregar = { avisos++ }
        assertNull(g.agora("a", 10))
        assertEquals(0, avisos)
        f.falhar = false
        g.agora("a", 10)                                              // pede de novo e agora chega
        assertEquals(1, avisos)
        assertEquals("A", g.agora("a", 10)?.titulo)
    }

    @Test fun titulosDeAgoraNumaConsultaSo() {
        val g = Guia(listOf(p("a", 0, h, "A0"), p("a", h, 2 * h, "A1"), p("b", 2 * h, 3 * h, "B")))
        assertEquals(mapOf("a" to "A1"), g.titulosAgora(h + 1))
    }

    @Test fun guiaConsultaCadaCanalUmaVezEGuardaPoucos() {
        val f = Contadora(mapOf("a" to listOf(p("a", 0, h, "A")), "b" to listOf(p("b", 0, h, "B"))))
        val g = Guia(f, maxCanais = 2)
        repeat(5) { g.agora("a", 10); g.proximos("a", 10) }
        assertEquals(listOf("a"), f.pedidos)                          // 10 consultas, 1 ida ao banco
        g.doCanal("b"); g.doCanal("sem")                              // canal sem guia também fica guardado (vazio)
        g.doCanal("a")                                                // "a" saiu do cache (só cabem 2)
        assertEquals(listOf("a", "b", "sem", "a"), f.pedidos)
        assertEquals(setOf("a", "b"), g.comProgramaAgora(10))
    }

    @Test fun falhaNaLeituraNaoFicaGuardada() {
        val f = Contadora(mapOf("a" to listOf(p("a", 0, h, "A"))), falhar = true)
        val g = Guia(f)
        assertNull(g.agora("a", 10))                                  // banco fechado: mostra vazio...
        f.falhar = false
        assertEquals("A", g.agora("a", 10)?.titulo)                   // ...e na próxima tenta de novo
    }

    @Test fun guiaEmMemoriaContinuaIgual() {
        val g = Guia(listOf(p("a", h, 2 * h, "2"), p("a", 0, h, "1"), p("b", 0, h, "B")))
        assertEquals(listOf("1", "2"), g.doCanal("a").map { it.titulo })   // ordena pelo início
        assertEquals(setOf("a", "b"), g.comProgramaAgora(10))
        assertTrue(Guia(emptyList()).doCanal("a").isEmpty())
    }

    @Test fun m3uPorLinhasIgualAoTexto() {
        val texto = "﻿#EXTM3U\r\n#EXTINF:-1 tvg-id=\"c1\" group-title=\"G\",Um\r\nhttp://exemplo.invalid/1.m3u8\r\n" +
            "#EXTINF:-1,Dois\nhttp://exemplo.invalid/2.m3u8\n"
        val porLinhas = texto.reader().buffered().use { M3u.ler(it.lineSequence()) }
        assertEquals(M3u.ler(texto), porLinhas)
        assertEquals(listOf("Um", "Dois"), porLinhas.map { it.nome })
    }
}

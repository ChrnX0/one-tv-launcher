package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.TimeZone

/** "Coloca na Globo" / "o que passa na Globo" (CanalBusca e as ações novas do ComandosIa). gradlew testReleaseUnitTest. */
class CanalBuscaTest {
    private fun canal(id: String, nome: String, n: Int, grupo: String = "Abertos", guia: String = "") =
        Canal(id, nome, n, grupo, "", "http://x/$id", guia)

    private val lista = listOf(
        canal("a:1", "TV Globo HD", 5, guia = "globo"),
        canal("a:2", "Globo News", 40),
        canal("a:3", "SBT", 4),
        canal("a:4", "BR: SporTV 2 FHD", 39),
        canal("a:5", "Rede Bandeirantes", 13),
        canal("a:6", "Canal Proibido", 99, grupo = "Adultos"),
        canal("a:7", "TV Cultura", 2),
    )

    private fun primeiro(termo: String) = CanalBusca.buscar(lista, termo).firstOrNull()?.canal?.id

    @Test fun chaveTiraRuido() {
        assertEquals("globo", CanalBusca.chave("TV Globo HD"))
        assertEquals("sportv 2", CanalBusca.chave("BR: SporTV 2 FHD", "BR"))
        assertEquals("tv hd", CanalBusca.chave("TV HD"))   // só ruído: fica o nome inteiro
    }

    @Test fun globoAchaTvGloboHdENaoGloboNews() {
        assertEquals("a:1", primeiro("globo"))
        assertEquals("a:1", primeiro("TV Globo"))
        assertEquals("a:2", primeiro("globo news"))
    }

    @Test fun nomeJuntoGanhaDoComecoDeOutro() {
        // Na lista da Sala o canal é "GloboNews FHD" (junto); "globo news" achava o "TV Globo" (11/11/2026).
        val sala = listOf(canal("s:1", "TV Globo HD", 2006), canal("s:2", "GloboNews FHD", 50), canal("s:3", "Globo SP FHD", 2))
        assertEquals("s:2", CanalBusca.buscar(sala, "globo news").firstOrNull()?.canal?.id)
        assertEquals("s:2", CanalBusca.buscar(sala, "globonews").firstOrNull()?.canal?.id)
        assertEquals("s:1", CanalBusca.buscar(sala, "globo").firstOrNull()?.canal?.id)
    }

    @Test fun acentoCaixaEErroPequeno() {
        assertEquals("a:1", primeiro("GLÓBO"))
        assertEquals("a:1", primeiro("glbo"))
        assertEquals("a:7", primeiro("cultúra"))
        assertEquals("a:5", primeiro("bandeirante"))
        assertEquals("a:4", primeiro("sportv dois"))
    }

    @Test fun palavraCurtaNaoAceitaErro() {
        assertNull(primeiro("sic"))   // não pode virar SBT
        assertEquals("a:3", primeiro("sbt"))
    }

    @Test fun pedacoDePalavraCurtaNaoCasa() {
        val outros = listOf(canal("m:1", "Music Box", 70), canal("m:2", "Classic Movies", 71), canal("m:3", "Rede Bandeirantes", 13))
        assertNull(CanalBusca.buscar(outros, "sic").firstOrNull())        // não pode virar "Music Box"
        assertEquals("m:3", CanalBusca.buscar(outros, "band").firstOrNull()?.canal?.id)   // começo de palavra vale
    }

    @Test fun guiaSoDosCanaisPedidos() {
        val json = PlayerDados.programasParaJson(listOf(
            Programa("globo", 1, 2, "Jornal", "", ""), Programa("sbt/1", 1, 2, "Show", "", ""), Programa("outro", 1, 2, "X", "", "")))
        val lidos = CanalBusca.programasDoCache(java.io.StringReader(json), setOf("globo", "sbt/1"))
        assertEquals(listOf("Jornal", "Show"), lidos.map { it.titulo })
    }

    @Test fun numero() {
        assertEquals("a:3", primeiro("4"))
        assertEquals("a:3", primeiro("canal 4"))
        assertEquals("a:1", primeiro("cinco"))
        assertNull(primeiro("777"))
    }

    @Test fun bloqueadoNaoTocaNemCaiEmOutro() {
        val permitido: (Canal) -> Boolean = { it.grupo != "Adultos" }
        assertTrue(CanalBusca.escolher(lista, "proibido", permitido) is CanalBusca.Escolha.Bloqueado)
        assertTrue(CanalBusca.escolher(lista, "xyzw", permitido) is CanalBusca.Escolha.NaoAchou)
        assertEquals("a:3", (CanalBusca.escolher(lista, "sbt", permitido) as CanalBusca.Escolha.Achou).canal.id)
    }

    @Test fun catalogoConfereTocarCanal() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"tocar_canal","canal":"globo"},{"tipo":"o_que_passa","canal":""}]}""", emptyMap())
        assertEquals(1, p.acoes.size)
        assertEquals("globo", (p.acoes[0] as ComandosIa.Acao.TocarCanal).pedido)
        assertEquals(1, p.recusadas.size)
    }

    @Test fun resolverEscolheCanalERespeitaParental() {
        val plano = ComandosIa.conferir("""{"acoes":[{"tipo":"tocar_canal","canal":"globo"},
            {"tipo":"tocar_canal","canal":"proibido"},{"tipo":"tocar_canal","canal":"nada disso"},
            {"tipo":"volume","acao":"mudo"}]}""", emptyMap())
        val dados = CanalBusca.Dados(lista, { it.grupo != "Adultos" }, { Guia(emptyList()) })
        val r = CanalBusca.resolver(plano, dados, 0L, TimeZone.getTimeZone("UTC"))
        val tocar = r.acoes[0] as ComandosIa.Acao.TocarCanal
        assertEquals("a:1", tocar.canalId)
        assertEquals("Colocar no canal 5 (TV Globo HD)", tocar.descricao)
        assertTrue(r.acoes[1] is ComandosIa.Acao.Volume)
        assertEquals(2, r.recusadas.size)
        assertTrue(r.recusadas.none { it.contains("Proibido") })   // não revela o nome do canal bloqueado
        // Sem lista de canais: recusa, não quebra.
        assertTrue(CanalBusca.resolver(plano, null, 0L, TimeZone.getTimeZone("UTC")).acoes.none { it is ComandosIa.Acao.TocarCanal })
    }

    @Test fun oQuePassaComGuia() {
        val h = 3_600_000L
        val json = PlayerDados.programasParaJson(listOf(
            Programa("globo", 20 * h, 21 * h, "Jornal"), Programa("globo", 21 * h, 22 * h, "Novela"),
            Programa("outro", 20 * h, 22 * h, "Filme")))
        val progs = CanalBusca.programasDoCache(StringReader(json), setOf("globo"))
        assertEquals(2, progs.size)
        var pedidos = 0
        val dados = CanalBusca.Dados(lista, { true }, { ids -> pedidos++; assertEquals(setOf("globo"), ids); Guia(progs) })
        val plano = ComandosIa.conferir("""{"acoes":[{"tipo":"o_que_passa","canal":"Globo"},{"tipo":"o_que_passa","canal":"tv globo"}]}""", emptyMap())
        val r = CanalBusca.resolver(plano, dados, 20 * h + 600_000L, TimeZone.getTimeZone("UTC"))
        assertEquals("TV Globo HD (5) agora: Jornal (até 21:00). Depois: Novela (21:00).", r.acoes[0].descricao)
        assertEquals(1, pedidos)   // o guia é lido uma vez só
        assertEquals("SBT (4): sem programação no guia.", CanalBusca.textoAgora(lista[2], Guia(progs), 0L, TimeZone.getTimeZone("UTC")))
    }

    @Test fun cacheDoGuiaComDefeitoNaoQuebra() {
        assertTrue(CanalBusca.programasDoCache(StringReader("""[{"c":"globo","i":1,"f":2,"t":"A \"}\" B"},{"c":"globo","i":5"""), setOf("globo")).size == 1)
    }

    @Test fun frasesDeVoz() {
        fun t(f: String) = (CanalBusca.interpretarFrase(f) as? CanalBusca.PedidoVoz.Tocar)?.canal
        fun q(f: String) = (CanalBusca.interpretarFrase(f) as? CanalBusca.PedidoVoz.OQuePassa)?.canal
        assertEquals("globo", t("Assistir no canal Globo"))
        assertEquals("globo", t("coloca na Globo"))
        assertEquals("sbt", t("quero ver o SBT agora"))
        assertEquals("5", t("mudar para o canal 5"))
        assertEquals("globo", q("O que está passando na Globo?"))
        assertEquals("sbt", q("o que passa no SBT agora"))
        assertNull(CanalBusca.interpretarFrase("desligar em 30 minutos"))
        assertEquals("globo", (CanalBusca.interpretarFrase("Globo", semVerboTocar = true) as CanalBusca.PedidoVoz.Tocar).canal)
        // Ideia 72: as mesmas frases em inglês, ao lado do português.
        assertEquals("globo", t("Watch Globo"))
        assertEquals("5", t("switch to channel 5 please"))
        assertEquals("cnn", t("hey google, put on CNN"))
        assertEquals("hbo", t("I want to watch the HBO now"))
        assertEquals("globo", q("What's on Globo?"))
        assertEquals("5", q("what is playing on channel 5 right now"))
        assertEquals("sbt", q("what's on now on SBT"))
        assertNull(CanalBusca.interpretarFrase("turn off in 30 minutes please"))
        val json = CanalBusca.acoesDaVoz(CanalBusca.PedidoVoz.Tocar("globo \"x\""))
        assertEquals("globo \"x\"", (ComandosIa.conferir(json, emptyMap()).acoes.single() as ComandosIa.Acao.TocarCanal).pedido)
    }
}

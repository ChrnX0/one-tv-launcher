package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.alarm.AutoDesligar
import br.paulo.tvlauncher.family.Mural
import br.paulo.tvlauncher.family.RetrospectivaAno
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.weather.AvisoTemperatura
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rodada 2 (14/11/2026): aniversários (93), calor/frio (94), mural lido (95), Sua TV no ano (92), rádios (87). */
class FamiliaRodada2Test {
    private fun dia(a: Int, m: Int, d: Int): Calendar = GregorianCalendar(a, m - 1, d, 10, 0)

    // ---- 93 aniversários
    @Test fun aniversarioHojeEAmanhaComIdade() {
        val l = listOf(Aniversarios.criar("tia Rosa", 14, 11, 1946)!!, Aniversarios.criar("Ana", 15, 11, null)!!, Aniversarios.criar("Zé", 1, 1, null)!!)
        val r = Aniversarios.proximos(l, dia(2026, 11, 14))
        assertEquals(2, r.size)
        assertEquals(Aniversarios.Quando.HOJE, r[0].quando); assertEquals(80, r[0].idade)
        assertEquals("Ana", r[1].pessoa.nome); assertEquals(Aniversarios.Quando.AMANHA, r[1].quando); assertNull(r[1].idade)
    }

    @Test fun viradaDoAno() {
        val r = Aniversarios.proximos(listOf(Aniversarios.criar("Zé", 1, 1, 2000)!!), dia(2026, 12, 31))
        assertEquals(Aniversarios.Quando.AMANHA, r.single().quando)
        assertEquals(27, r.single().idade)   // faz anos em 2027
    }

    @Test fun vinteENoveDeFevereiroEmAnoComum() {
        val p = Aniversarios.criar("Léo", 29, 2, null)!!
        assertEquals(Aniversarios.Quando.HOJE, Aniversarios.proximos(listOf(p), dia(2027, 2, 28)).single().quando)
        assertEquals(Aniversarios.Quando.AMANHA, Aniversarios.proximos(listOf(p), dia(2028, 2, 28)).single().quando)
    }

    @Test fun dataInvalidaNaoEntra() {
        assertNull(Aniversarios.criar("X", 31, 4, null))
        assertNull(Aniversarios.criar("X", 30, 2, null))
        assertNull(Aniversarios.criar("  ", 1, 1, null))
        assertNull(Aniversarios.criar("X", 1, 13, null))
    }

    @Test fun mesmoNomeTrocaEJsonIdaEVolta() {
        var l = Aniversarios.adicionar(emptyList(), Aniversarios.criar("Ana", 5, 3, null)!!)
        l = Aniversarios.adicionar(l, Aniversarios.criar("ANA", 6, 3, 1990)!!)
        assertEquals(1, l.size); assertEquals(6, l[0].dia)
        assertEquals(l, Aniversarios.deJson(Aniversarios.paraJson(l).toString()))
        assertTrue(Aniversarios.remover(l, "ana").isEmpty())
        assertTrue(Aniversarios.deJson("lixo").isEmpty())
    }

    // ---- 94 calor e frio
    @Test fun temperatura() {
        assertEquals(AvisoTemperatura.Tipo.CALOR, AvisoTemperatura.tipoDaHora(10))
        assertEquals(AvisoTemperatura.Tipo.FRIO, AvisoTemperatura.tipoDaHora(18))
        assertNull(AvisoTemperatura.tipoDaHora(3))
        assertEquals(34, AvisoTemperatura.decidir(AvisoTemperatura.Tipo.CALOR, 33.6, null))
        assertNull(AvisoTemperatura.decidir(AvisoTemperatura.Tipo.CALOR, 31.0, 5.0))
        assertEquals(8, AvisoTemperatura.decidir(AvisoTemperatura.Tipo.FRIO, 30.0, 8.2))
        assertNull(AvisoTemperatura.decidir(AvisoTemperatura.Tipo.FRIO, null, null))
        assertEquals("2026-01-05", AvisoTemperatura.chaveDia(dia(2026, 1, 5)))
    }

    // ---- 95 mural: lido
    @Test fun recadoLidoSaiDoProtetorEApagarLimpa() {
        var c = Mural.adicionar(Mural.Conteudo(), Mural.Lista.RECADOS, "Dentista quinta 15h")
        c = Mural.adicionar(c, Mural.Lista.RECADOS, "Pagar luz")
        c = Mural.marcarLido(c, "dentista QUINTA 15h")
        assertTrue(c.lido("Dentista quinta 15h"))
        assertEquals(listOf("Pagar luz"), c.naoLidos)
        val volta = Mural.deJson(Mural.paraJson(c).toString())
        assertEquals(c, volta)
        assertEquals(listOf("Dentista quinta 15h", "Pagar luz"), Mural.marcarLido(c, "Dentista quinta 15h", false).naoLidos)
        val sem = Mural.remover(c, Mural.Lista.RECADOS, "Dentista quinta 15h")
        assertTrue(sem.lidos.isEmpty())
        assertEquals(c, Mural.marcarLido(c, "não existe"))
    }

    @Test fun lidoOrfaoNoJsonEIgnorado() {
        val c = Mural.deJson("""{"compras":[],"recados":["a"],"lidos":["b","a"]}""")
        assertEquals(listOf("a"), c.lidos)
    }

    // ---- 92 Sua TV no ano
    @Test fun canalFavoritoDoAno() {
        var j = RetrospectivaAno.somar(null, 2026, "Globo", 120)
        j = RetrospectivaAno.somar(j, 2026, "SBT", 30)
        j = RetrospectivaAno.somar(j, 2026, "Globo", 60)
        assertEquals("Globo" to 180L, RetrospectivaAno.favorito(j, 2026))
        assertNull(RetrospectivaAno.favorito(j, 2027))
        val novo = RetrospectivaAno.somar(j, 2027, "Band", 10)
        assertEquals("Band" to 10L, RetrospectivaAno.favorito(novo, 2027))
    }

    @Test fun tetoDeCanais() {
        var j: String? = null
        for (i in 0..RetrospectivaAno.MAX_CANAIS + 5) j = RetrospectivaAno.somar(j, 2026, "c$i", (i + 1).toLong())
        assertEquals(RetrospectivaAno.MAX_CANAIS, org.json.JSONObject(j!!).getJSONObject("canais").length())
    }

    @Test fun linhasDoAnoEEpoca() {
        val a = RetrospectivaAno.resumir(2026, mapOf("yt" to 600 * 60_000L, "nf" to 120 * 60_000L, "eu" to 999 * 60_000L, "x" to 1000L),
            { it.uppercase() }, "eu", "Globo" to 180L)
        val (l1, l2) = RetrospectivaAno.linhas(a)
        assertEquals("12 h de TV · YT, NF", l1)
        assertEquals("Canal favorito: Globo (3 h)", l2)
        assertTrue(RetrospectivaAno.ehEpoca(dia(2026, 12, 1)))
        assertFalse(RetrospectivaAno.ehEpoca(dia(2026, 11, 30)))
    }

    // ---- 87 rádios
    @Test fun estacoesDoRadioBrowser() {
        val json = """[
          {"stationuuid":"u1","name":" Rádio  A ","url":"http://x/a.pls","url_resolved":"https://s.example/a","countrycode":"BR","tags":"mpb,pop,rock,jazz","bitrate":128,"codec":"MP3","lastcheckok":1},
          {"stationuuid":"u2","name":"Rádio A","url_resolved":"https://s.example/a","lastcheckok":1},
          {"stationuuid":"u3","name":"Quebrada","url_resolved":"https://s.example/q","lastcheckok":0},
          {"stationuuid":"u4","name":"Sem url","url_resolved":"ftp://x","lastcheckok":1},
          {"stationuuid":"u5","name":"rádio a","url_resolved":"https://outro.example/a2","countrycode":"BR","lastcheckok":1}]"""
        val l = Radios.estacoes(json)
        assertEquals(1, l.size)
        assertEquals(Radios.Radio("u1", "Rádio A", "https://s.example/a", "BR", "mpb, pop, rock", 128, "MP3"), l[0])
        assertTrue(Radios.estacoes("{").isEmpty())
    }

    @Test fun servidoresEPaises() {
        assertEquals(listOf("de1.api.radio-browser.info"),
            Radios.servidores("""[{"name":"de1.api.radio-browser.info"},{"name":"de1.api.radio-browser.info"},{"name":"evil.com"}]"""))
        val p = Radios.paises("""[{"name":"Brazil","iso_3166_1":"BR","stationcount":3000},{"name":"?","iso_3166_1":"","stationcount":5},{"name":"Z","iso_3166_1":"ZZ","stationcount":0}]""")
        assertEquals(listOf(Triple("BR", "Brazil", 3000)), p)
    }

    @Test fun enderecosDaApi() {
        assertEquals("https://s/json/stations/search?countrycode=BR&hidebroken=true&order=clickcount&reverse=true&limit=60", Radios.urlPais("s", "br"))
        assertTrue(Radios.urlNome("s", "jovem pan").contains("name=jovem+pan"))
        assertTrue(Radios.urlGenero("s", "80s").contains("tag=80s&tagExact=true"))
    }

    @Test fun playlistsEColadas() {
        assertEquals("http://a/stream", Radios.daPlaylist("[playlist]\nNumberOfEntries=1\nFile1=http://a/stream\nTitle1=x"))
        assertEquals("https://b.example/s", Radios.daPlaylist("#EXTM3U\n#EXTINF:-1,x\nhttps://b.example/s\n"))
        assertNull(Radios.daPlaylist("nada aqui"))
        assertTrue(Radios.pareceplaylist("http://x/r.PLS?a=1"))
        assertFalse(Radios.pareceplaylist("http://x/r.mp3"))
        assertNull(Radios.criarColada("x", "javascript:alert(1)"))
        assertNull(Radios.criarColada(" ", "https://a/b/c"))
        val r = Radios.criarColada("Minha", "https://a.example/s")!!
        assertTrue(r.id.startsWith("url:"))
    }

    @Test fun destinosProibidosParaPlaylist() {
        fun ip(t: String) = java.net.InetAddress.getByName(t)
        assertTrue(Radios.destinoProibido(ip("127.0.0.1"), emptySet()))
        assertTrue(Radios.destinoProibido(ip("169.254.1.1"), emptySet()))
        assertTrue(Radios.destinoProibido(ip("0.0.0.0"), emptySet()))
        assertTrue(Radios.destinoProibido(ip("::1"), emptySet()))
        assertTrue(Radios.destinoProibido(ip("10.0.0.5"), setOf(ip("10.0.0.5"))))
        assertFalse(Radios.destinoProibido(ip("10.0.0.9"), setOf(ip("10.0.0.5"))))   // NAS da casa vale
        assertFalse(Radios.destinoProibido(ip("8.8.8.8"), emptySet()))
    }

    @Test fun destinosDeFeedNaoEntramNaCasa() {
        fun ip(t: String) = java.net.InetAddress.getByName(t)
        for (t in listOf("192.168.0.1", "10.0.0.9", "172.16.3.4", "100.100.1.1", "fd00::1", "127.0.0.1"))
            assertTrue(t, Radios.destinoDeFeedProibido(ip(t), emptySet()))
        for (t in listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700::1"))
            assertFalse(t, Radios.destinoDeFeedProibido(ip(t), emptySet()))
    }

    @Test fun favoritasAlternamEMantemOrdem() {
        val a = Radios.Radio("a", "A", "https://x/a1"); val b = Radios.Radio("b", "B", "https://x/b1")
        var l = Radios.alternar(Radios.alternar(emptyList(), a), b)
        assertEquals(listOf(a, b), l)
        assertEquals(l, Radios.deJson(Radios.paraJson(l).toString()))
        l = Radios.alternar(l, a)
        assertEquals(listOf(b), l)
    }

    // ---- 80 desligar sozinha: 3 h de padrão (só a constante: o objeto usa o Looper do Android)
    @Test fun desligarPadraoTresHoras() {
        assertEquals(180, AutoDesligar.PADRAO_MIN)
    }
}

package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinTest {
    private val conta = ContaMidia("c1", ContaMidia.JELLYFIN, "NAS", "http://nas:8096", token = "tok en", usuarioId = "u1")

    @Test fun respostaDaBusca() {
        assertEquals(Jellyfin.Achado("NAS", "http://192.168.0.5:8096"),
            Jellyfin.lerAchado("""{"Address":"http://192.168.0.5:8096/","Id":"x","Name":"NAS"}"""))
        assertNull(Jellyfin.lerAchado("""{"Address":"file:///etc","Name":"x"}"""))
        assertNull(Jellyfin.lerAchado("lixo"))
    }

    @Test fun login() {
        assertEquals("t" to "u", Jellyfin.lerLogin("""{"AccessToken":"t","User":{"Id":"u"}}"""))
        runCatching { Jellyfin.lerLogin("""{"User":{"Id":"u"}}""") }.let { assertTrue(it.isFailure) }
    }

    @Test fun cabecalhoSoLevaTokenDepoisDoLogin() {
        assertTrue(!Jellyfin.cabecalho("abc").contains("Token"))
        assertTrue(Jellyfin.cabecalho("abc", "t").endsWith(", Token=\"t\""))
    }

    @Test fun itensPastasPrimeiroENomes() {
        val json = """{"Items":[
            {"Id":"e1","Name":"Piloto","Type":"Episode","MediaType":"Video","ParentIndexNumber":1,"IndexNumber":2,"SeriesName":"Série"},
            {"Id":"f1","Name":"Temporada 1","Type":"Season","IsFolder":true},
            {"Id":"m1","Name":"Filme","Type":"Movie","MediaType":"Video","ProductionYear":2021},
            {"Id":"a1","Name":"Música","Type":"Audio","MediaType":"Audio"},
            {"Name":"sem id"}]}"""
        val l = Jellyfin.lerItens(json, conta)
        assertEquals(listOf("f1", "e1", "m1", "a1"), l.map { it.id })
        assertEquals("T1E2 · Piloto", l[1].titulo)
        assertEquals("Filme (2021)", l[2].titulo)
        assertEquals("jellyfin://c1/m1", l[2].url)
        assertEquals("jellyfin://c1/a1/audio", l[3].url)
        assertEquals("Série · T1E2 · Piloto", Jellyfin.lerItens(json, conta, comSerie = true)[1].titulo)
        assertEquals(emptyList<Upnp.Entrada>(), Jellyfin.lerItens("lixo", conta))
    }

    @Test fun uriETocar() {
        assertEquals(Jellyfin.Alvo("c1", "ab-12", false), Jellyfin.lerUri("jellyfin://c1/ab-12"))
        assertEquals(Jellyfin.Alvo("c1", "ab", true), Jellyfin.lerUri("jellyfin://c1/ab/audio"))
        assertNull(Jellyfin.lerUri("jellyfin://c1/../x"))
        assertNull(Jellyfin.lerUri("jellyfin://c1/ab/outro"))
        assertNull(Jellyfin.lerUri("http://c1/ab"))
        assertEquals("http://nas:8096/Videos/m1/stream?static=true&api_key=tok+en", Jellyfin.urlTocar(conta, "m1", false))
        assertEquals("http://nas:8096/Audio/a1/stream?static=true&api_key=tok+en", Jellyfin.urlTocar(conta, "a1", true))
        assertEquals("x", JSONObject("{\"a\":\"x\"}").getString("a"))
    }

    @Test fun nomeDoServidorEAvisoDeReproducao() {
        assertEquals("Casa", Jellyfin.lerNomeServidor("""{"ServerName":" Casa ","Version":"10.11"}"""))
        assertNull(Jellyfin.lerNomeServidor("""{"ServerName":""}"""))
        assertNull(Jellyfin.lerNomeServidor("lixo"))
        val o = JSONObject(Jellyfin.corpoInforme("m1", 12_000, true))
        assertEquals("m1", o.getString("ItemId"))
        assertEquals(120_000_000L, o.getLong("PositionTicks"))
        assertEquals(true, o.getBoolean("IsPaused"))
        assertEquals(0L, JSONObject(Jellyfin.corpoInforme("m1", -5, false)).getLong("PositionTicks"))
        assertEquals("/Sessions/Playing/Stopped", Jellyfin.Momento.PAROU.caminho)
    }
}

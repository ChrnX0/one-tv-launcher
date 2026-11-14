package br.paulo.tvlauncher

import br.paulo.tvlauncher.ServicosFilmes.Espera
import br.paulo.tvlauncher.ServicosFilmes.Motivo
import br.paulo.tvlauncher.ServicosFilmes.Servico
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Respostas gravadas no formato das documentações (Trakt, Simkl, MDBList, OpenSubtitles); nenhuma conta real. */
class ServicosFilmesTest {
    @Test fun semChaveOServicoNaoExiste() {
        val vazio = ServicosFilmes.ChavesApp()
        assertFalse(vazio.disponivel(Servico.TRAKT))
        assertFalse(vazio.disponivel(Servico.SIMKL))
        assertFalse(vazio.disponivel(Servico.TMDB))
        assertFalse(vazio.disponivel(Servico.OPENSUBTITLES))
        assertTrue(vazio.disponivel(Servico.MDBLIST))   // a chave do MDBList é da pessoa
        assertFalse(ServicosFilmes.ChavesApp(traktId = "id").disponivel(Servico.TRAKT))   // sem o segredo não entra
        assertTrue(ServicosFilmes.ChavesApp(traktId = "id", traktSegredo = "s").disponivel(Servico.TRAKT))
    }

    @Test fun cabecalhosDoTrakt() {
        val c = ServicosFilmes.traktCabecalhos("abc", 457800, "tok")
        assertEquals("2", c["trakt-api-version"]); assertEquals("abc", c["trakt-api-key"])
        assertEquals("Bearer tok", c["Authorization"]); assertEquals("OneTVLauncher v1.0 (457800)", c["User-Agent"])
        assertNull(ServicosFilmes.traktCabecalhos("abc", 1)["Authorization"])
    }

    @Test fun codigoDoTrakt() {
        val c = ServicosFilmes.traktLerCodigo("""{"device_code":"d9c1","user_code":"5055CC52","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")!!
        assertEquals("5055CC52", c.codigoUsuario); assertEquals("d9c1", c.codigoAparelho); assertEquals(5, c.intervaloS)
        assertEquals("https://trakt.tv/activate/5055CC52", c.urlQr)
        assertNull(ServicosFilmes.traktLerCodigo("""{"user_code":"X"}"""))
        assertNull(ServicosFilmes.traktLerCodigo("""{"device_code":"d","user_code":"X","verification_url":"http://inseguro"}"""))
    }

    @Test fun esperaDoTraktPeloCodigoHttp() {
        assertTrue(ServicosFilmes.traktLerEspera(400, "", 0) is Espera.Pendente)
        assertTrue(ServicosFilmes.traktLerEspera(429, "", 0) is Espera.MaisDevagar)
        assertEquals(Motivo.VENCEU, (ServicosFilmes.traktLerEspera(410, "", 0) as Espera.Fim).motivo)
        assertEquals(Motivo.RECUSADO, (ServicosFilmes.traktLerEspera(418, "", 0) as Espera.Fim).motivo)
        assertEquals(Motivo.USADO, (ServicosFilmes.traktLerEspera(409, "", 0) as Espera.Fim).motivo)
        val t = ServicosFilmes.traktLerEspera(200,
            """{"access_token":"A","token_type":"bearer","expires_in":604800,"refresh_token":"R","scope":"public","created_at":1700000000}""", 5) as Espera.Token
        assertEquals("A", t.acesso); assertEquals("R", t.renovar); assertEquals(1_700_000_000_000L + 604_800_000L, t.expiraEmMs)
        assertEquals(Motivo.ERRO, (ServicosFilmes.traktLerEspera(200, "{}", 0) as Espera.Fim).motivo)
    }

    @Test fun usuarioDoTrakt() {
        assertEquals("sean", ServicosFilmes.traktLerUsuario("""{"user":{"username":"sean","name":"Sean Rudford"}}"""))
        assertNull(ServicosFilmes.traktLerUsuario("{}"))
    }

    @Test fun pinDoSimkl() {
        val c = ServicosFilmes.simklLerCodigo("""{"result":"OK","device_code":"DEVICE_CODE","user_code":"7ED24","verification_url":"https://simkl.com/pin","expires_in":900,"interval":5}""")!!
        assertEquals("7ED24", c.codigoUsuario); assertEquals("https://simkl.com/pin/7ED24", c.urlQr); assertEquals(900, c.expiraEmS)
        assertNull(ServicosFilmes.simklLerCodigo("""{"error":"client_id_failed","code":412}"""))
        assertTrue(ServicosFilmes.simklUrlCodigo("id x", 7).startsWith("https://api.simkl.com/oauth/pin?client_id=id%20x&app-name=onetvlauncher&app-version=1.0.7"))
    }

    @Test fun esperaDoSimkl() {
        assertTrue(ServicosFilmes.simklLerEspera(200, """{"result":"KO","message":"Authorization pending"}""") is Espera.Pendente)
        val t = ServicosFilmes.simklLerEspera(200, """{"result":"OK","access_token":"tok"}""") as Espera.Token
        assertEquals("tok", t.acesso); assertNull(t.expiraEmMs)
        // Doc do Simkl: o código apagado volta como um código NOVO (com device_code): é o nosso que venceu.
        assertEquals(Motivo.VENCEU, (ServicosFilmes.simklLerEspera(200,
            """{"result":"OK","device_code":"DEVICE_CODE","user_code":"AAAAA","verification_url":"https://simkl.com/pin"}""") as Espera.Fim).motivo)
        assertTrue(ServicosFilmes.simklLerEspera(429, "") is Espera.MaisDevagar)
        assertEquals("ana", ServicosFilmes.simklLerUsuario("""{"user":{"name":"ana","joined_at":"2020"},"account":{"id":1}}"""))
    }

    @Test fun chaveDoMdblist() {
        assertTrue(ServicosFilmes.mdblistChaveValida("abcdefghijklmnopqrstuvwx1"))
        assertFalse(ServicosFilmes.mdblistChaveValida("curta"))
        assertFalse(ServicosFilmes.mdblistChaveValida("abcdefghijklmnop qrst"))
        assertEquals("https://api.mdblist.com/user?apikey=abc", ServicosFilmes.mdblistUrlUsuario(" abc "))
        assertEquals("paulo", ServicosFilmes.mdblistLerUsuario("""{"api_requests":1000,"api_requests_count":3,"name":"paulo"}"""))
        assertEquals(1000, ServicosFilmes.mdblistLerLimite("""{"api_requests":1000}"""))
        assertNull(ServicosFilmes.mdblistLerUsuario("""{"error":"Invalid API key"}"""))
    }

    @Test fun renovarSoPertoDeVencer() {
        val dia = 24L * 3_600_000
        assertFalse(ServicosFilmes.precisaRenovar(10 * dia, true, 0))
        assertTrue(ServicosFilmes.precisaRenovar(dia - 1, true, 0))
        assertTrue(ServicosFilmes.precisaRenovar(-5, true, 0))   // já venceu
        assertFalse(ServicosFilmes.precisaRenovar(dia - 1, false, 0))   // sem refresh token não tem como
        assertFalse(ServicosFilmes.precisaRenovar(null, true, 0))   // Simkl: não vence
    }

    @Test fun contasIdaEVolta() {
        val antes = mapOf(
            Servico.TRAKT to ServicosFilmes.Conta("A", "R", 99L, "sean"),
            Servico.MDBLIST to ServicosFilmes.Conta("chave"),
            Servico.OPENSUBTITLES to ServicosFilmes.Conta("", usuario = "u", senha = "s\"enha"),
        )
        assertEquals(antes, ServicosFilmes.contasDeJson(ServicosFilmes.contasParaJson(antes)))
        assertTrue(ServicosFilmes.contasDeJson("lixo").isEmpty())
        assertTrue(ServicosFilmes.contasDeJson(null).isEmpty())
    }

    @Test fun limiteDoOpenSubtitles() {
        assertTrue(LegendaExterna.acabouLimite(406, null))
        assertTrue(LegendaExterna.acabouLimite(403, """{"message":"You have downloaded your allowed 5 subtitles for 24h"}"""))
        assertFalse(LegendaExterna.acabouLimite(200, """{"message":"allowed"}"""))
        assertFalse(LegendaExterna.acabouLimite(500, """{"message":"Internal"}"""))
    }
}

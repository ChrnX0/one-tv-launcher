package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.LegendaExterna
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.ServicosFilmes.Espera
import br.paulo.tvlauncher.player.ServicosFilmes.Motivo
import br.paulo.tvlauncher.player.ServicosFilmes.Servico
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
        assertTrue(ServicosFilmes.ChavesApp(traktId = "id").disponivel(Servico.TRAKT))   // apps novos do Trakt não têm segredo
        assertTrue(ServicosFilmes.ChavesApp(traktId = "id", traktSegredo = "s").disponivel(Servico.TRAKT))
    }

    @Test fun corpoDoTraktSemSegredo() {
        val sem = org.json.JSONObject(ServicosFilmes.traktCorpoToken("dev", "id", ""))
        assertFalse(sem.has("client_secret")); assertEquals("id", sem.getString("client_id"))
        assertFalse(org.json.JSONObject(ServicosFilmes.traktCorpoRenovar("r", "id", "")).has("client_secret"))
        assertFalse(org.json.JSONObject(ServicosFilmes.traktCorpoRevogar("t", "id", "")).has("client_secret"))
        assertEquals("s", org.json.JSONObject(ServicosFilmes.traktCorpoToken("dev", "id", "s")).getString("client_secret"))
    }

    @Test fun cabecalhosDoTrakt() {
        val c = ServicosFilmes.traktCabecalhos("abc", 457800, "tok")
        assertEquals("2", c["trakt-api-version"]); assertEquals("abc", c["trakt-api-key"])
        assertEquals("Bearer tok", c["Authorization"]); assertEquals("Sofaly v1.0 (457800)", c["User-Agent"])
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

    /** Exemplos de api.simkl.org/api-reference/oauth2-device (AUTH V2, RFC 8628). */
    @Test fun codigoDoSimkl() {
        val c = ServicosFilmes.simklLerCodigo("""{"device_code":"dc","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
            "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}""")!!
        assertEquals("BDWP-HQPK", c.codigoUsuario); assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", c.urlQr); assertEquals(900, c.expiraEmS)
        assertNull(ServicosFilmes.simklLerCodigo("""{"error":"invalid_client"}"""))
        assertEquals("client_id=id%20x&scope=media%3Aread%20media%3Awrite", ServicosFilmes.simklCorpoCodigo("id x"))
        assertTrue(ServicosFilmes.simklCorpoEspera("dc", "id").startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&client_id=id"))
        assertEquals("https://api.simkl.com/users/settings?client_id=a&app-name=sofaly&app-version=1.0.7", ServicosFilmes.simklUrlUsuario("a", 7))
    }

    @Test fun esperaDoSimkl() {
        assertTrue(ServicosFilmes.simklLerEspera(400, """{"error":"authorization_pending"}""", 0) is Espera.Pendente)
        assertTrue(ServicosFilmes.simklLerEspera(400, """{"error":"slow_down"}""", 0) is Espera.MaisDevagar)
        assertEquals(Motivo.VENCEU, (ServicosFilmes.simklLerEspera(400, """{"error":"expired_token"}""", 0) as Espera.Fim).motivo)
        assertEquals(Motivo.INVALIDO, (ServicosFilmes.simklLerEspera(401, """{"error":"invalid_client"}""", 0) as Espera.Fim).motivo)
        val t = ServicosFilmes.simklLerEspera(200, """{"access_token":"simkl_at_x","refresh_token":"simkl_rt_y","token_type":"Bearer",
            "expires_in":604800,"scope":"media:read media:write"}""", 1_000) as Espera.Token
        assertEquals("simkl_at_x", t.acesso); assertEquals("simkl_rt_y", t.renovar); assertEquals(1_000 + 604_800_000L, t.expiraEmMs)
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
        assertFalse(ServicosFilmes.precisaRenovar(null, true, 0))   // sem prazo conhecido
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

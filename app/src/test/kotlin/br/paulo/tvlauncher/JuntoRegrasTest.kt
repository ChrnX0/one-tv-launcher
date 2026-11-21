package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.JuntoRegras
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sessão junto e Continuar em outra TV (degrau 4): endereços, chaves, sincronia, mensagens e convites. */
class JuntoRegrasTest {
    private val eu = "onetv-sala.tail1234.ts.net"

    @Test fun enderecoDoParUsaOSufixoDaMinhaRede() {
        assertEquals("onetv-quarto.tail1234.ts.net", JuntoRegras.enderecoDoPar(eu, "onetv-quarto"))
        // Nada de endereço vindo de fora: ponto, barra ou porta no nome do par não passam.
        assertNull(JuntoRegras.enderecoDoPar(eu, "evil.com/x"))
        assertNull(JuntoRegras.enderecoDoPar(eu, "a.b"))
        assertNull(JuntoRegras.enderecoDoPar(eu, "x:443"))
        assertNull(JuntoRegras.enderecoDoPar(null, "onetv-quarto"))
        assertNull(JuntoRegras.enderecoDoPar("sem-sufixo", "onetv-quarto"))
    }

    @Test fun outrasTvsSoOnetvMenosEuOnlinePrimeiro() {
        val pares = """[{"nome":"onetv-sala","online":true},{"nome":"pc-do-paulo","online":true},
            {"nome":"onetv-quarto","online":false},{"nome":"onetv-cozinha","online":true},{"nome":"onetv-x.y","online":true},
            {"nome":"onetv-cozinha","online":true}]"""
        val l = JuntoRegras.outrasTvs(pares, eu)
        assertEquals(listOf("onetv-cozinha", "onetv-quarto"), l.map { it.host })
        assertTrue(l[0].online); assertFalse(l[1].online)
        assertTrue(JuntoRegras.outrasTvs("lixo", eu).isEmpty())
        assertTrue(JuntoRegras.outrasTvs(null, eu).isEmpty())
        assertEquals("Quarto dos pais", JuntoRegras.nomeBonito("onetv-quarto-dos-pais"))
    }

    @Test fun chavesCasamOMesmoTituloNasDuasCasas() {
        assertEquals("tmdb:filme:603", JuntoRegras.chaveVod(603, null, null, "x.mkv"))
        assertEquals("tmdb:serie:1399:s1e2", JuntoRegras.chaveVod(1399, 1, 2, null))
        assertEquals("arquivo:Filme.mkv", JuntoRegras.chaveVod(null, null, null, "smb://nas/filmes/Filme.mkv?x=1"))
        assertNull(JuntoRegras.chaveVod(null, null, null, null))
        // O mesmo canal com nomes diferentes nas listas das duas casas.
        assertEquals(JuntoRegras.chave(JuntoRegras.Tipo.CANAL, null, nome = "Globo HD"),
            JuntoRegras.chave(JuntoRegras.Tipo.CANAL, null, nome = "GLOBO [FHD]"))
        assertEquals(JuntoRegras.Tipo.CANAL, JuntoRegras.tipoDaChave("canal:globo"))
        assertNull(JuntoRegras.tipoDaChave("http://x"))
    }

    @Test fun alvoSoPulaAcimaDoLimiarEContaOAtraso() {
        // Diferença pequena: não pula (pular a cada pulso seria pior que 1 s de desvio).
        assertNull(JuntoRegras.alvo(10_000, 11_000, true, 0))
        // Lá está 3 s à frente: vai para lá, somando o atraso da mensagem.
        assertEquals(13_200L, JuntoRegras.alvo(10_000, 13_000, true, 200))
        // Lá pausado: o atraso não conta (a posição de lá não anda).
        assertEquals(20_000L, JuntoRegras.alvo(10_000, 20_000, false, 400))
        // Ao vivo: nunca pula de posição.
        assertNull(JuntoRegras.alvo(0, 60_000, true, 0, chave = "canal:globo"))
        assertEquals(2_500L, JuntoRegras.atraso(60_000))
        assertEquals(150L, JuntoRegras.atraso(300))
    }

    @Test fun eventoIdaEVoltaEFiltro() {
        val e = JuntoRegras.Evento(7, true, 61_000, 120, "tmdb:filme:603", "😂")
        assertEquals(e, JuntoRegras.eventoDeJson(JuntoRegras.eventoParaJson(e)))
        // Reação fora das 5 vira null; chave desconhecida descarta; posição absurda descarta.
        assertNull(JuntoRegras.eventoDeJson(JuntoRegras.eventoParaJson(e).put("reacao", "<script>"))!!.reacao)
        assertNull(JuntoRegras.eventoDeJson(JuntoRegras.eventoParaJson(e).put("chave", "file:///etc/passwd")))
        assertNull(JuntoRegras.eventoDeJson(JuntoRegras.eventoParaJson(e).put("pos", -5)))
        assertNull(JuntoRegras.eventoDeJson(JSONObject()))
        assertTrue(JuntoRegras.novoEvento(6, 7)); assertFalse(JuntoRegras.novoEvento(7, 7))
    }

    @Test fun conviteSoDaContaENoPrazo() {
        val agora = 1_000_000_000L
        val token = "0123456789abcdef0123456789abcdef"
        val c = JuntoRegras.conviteDeJson(JSONObject().put("token", token).put("de", "onetv-quarto.tail1234.ts.net")
            .put("titulo", "Matrix <b>").put("chave", "tmdb:filme:603"), agora)!!
        assertEquals("onetv-quarto", c.de); assertEquals("Matrix b", c.titulo)
        val conta = listOf(JuntoRegras.Tv("onetv-quarto", true))
        assertTrue(JuntoRegras.aceitarConvite(c, conta, agora + 1_000))
        assertFalse(JuntoRegras.aceitarConvite(c, conta, agora + JuntoRegras.CONVITE_MS + 1))
        assertFalse(JuntoRegras.aceitarConvite(c, listOf(JuntoRegras.Tv("onetv-cozinha", true)), agora))
        assertNull(JuntoRegras.conviteDeJson(JSONObject().put("token", "curto").put("de", "onetv-quarto").put("chave", "tmdb:filme:1"), agora))
        // Limite por hora.
        var h = emptyList<Long>()
        repeat(JuntoRegras.MAX_CONVITES_HORA) { val (ok, n) = JuntoRegras.contarConvite(h, agora); assertTrue(ok); h = n }
        assertFalse(JuntoRegras.contarConvite(h, agora).first)
        assertTrue(JuntoRegras.contarConvite(h, agora + 3_600_001L).first)
    }

    @Test fun rotas() {
        val t = "0123456789abcdef0123456789abcdef"
        assertEquals("" to "convite", JuntoRegras.rota("/junto/convite"))
        assertEquals("" to "continuar", JuntoRegras.rota("/continuar"))
        assertEquals(t to "evento", JuntoRegras.rota("/junto/$t/evento"))
        assertEquals(t to "estado", JuntoRegras.rota("/junto/$t/estado"))
        assertNull(JuntoRegras.rota("/junto/xyz/evento"))
        assertNull(JuntoRegras.rota("/junto/$t/apagar"))
        assertNull(JuntoRegras.rota("/mcp"))
    }

    @Test fun continuarSoEnderecoDaCasa() {
        val c = JuntoRegras.Continuar("Filme", "arquivo:Filme.mkv", 3_723_000, "smb://nas/Filme.mkv", "onetv-sala")
        assertEquals(c, JuntoRegras.continuarDeJson(JuntoRegras.continuarParaJson(c)))
        // Endereço de fora da casa (file://, content://, https qualquer) não passa: só o título e a posição.
        assertNull(JuntoRegras.continuarDeJson(JuntoRegras.continuarParaJson(c.copy(url = "file:///sdcard/x")))!!.url)
        assertNull(JuntoRegras.continuarDeJson(JuntoRegras.continuarParaJson(c.copy(url = "https://x.com/v.mp4")))!!.url)
        assertEquals("1:02:03", JuntoRegras.tempo(3_723_000)); assertEquals("4:05", JuntoRegras.tempo(245_000))
    }
}

/** 4c (17/11/2026): a sessão segue para o episódio seguinte da mesma série. */
class JuntoEpisodioTest {
    @Test fun mesmaSerieOutroEpisodioVale() {
        val s = "tmdb:serie:1399:s1e2"
        assertTrue(JuntoRegras.valeNaSessao(s, "tmdb:serie:1399:s1e3"))
        assertTrue(JuntoRegras.valeNaSessao(s, "tmdb:serie:1399:s2e1"))
        assertFalse(JuntoRegras.valeNaSessao(s, "tmdb:serie:1400:s1e3"))
        assertFalse(JuntoRegras.valeNaSessao(s, "tmdb:filme:1399"))
        assertTrue(JuntoRegras.valeNaSessao("canal:globo", "canal:sbt"))
        assertFalse(JuntoRegras.valeNaSessao("tmdb:filme:603", "tmdb:filme:604"))
        assertEquals(1 to 3, JuntoRegras.episodio("tmdb:serie:1399:s1e3"))
        assertNull(JuntoRegras.episodio("tmdb:serie:1399"))
    }

    @Test fun episodioComNumeroEnormeNaoDerrubaOPlayer() {
        assertEquals(1 to 3, JuntoRegras.episodio("tmdb:serie:1399:s1e3"))
        assertEquals(null, JuntoRegras.episodio("tmdb:serie:1:s99999999999e1"))
        assertEquals(null, JuntoRegras.episodio("tmdb:serie:1:s1e99999999999"))
    }
}

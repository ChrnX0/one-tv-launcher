package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** "A internet caiu? Em uma frase" (InternetEmUmaFrase): cada cenário de casa vira a frase e a causa certas. */
class InternetEmUmaFraseTest {
    private val zona = ZoneId.of("America/Sao_Paulo")
    private val min = 60_000L
    /** 10/11/2026 às [h]:[m] em São Paulo, em ms. */
    private fun t(h: Int, m: Int, dia: Int = 10) = LocalDateTime.of(2026, 11, dia, h, m).atZone(zona).toInstant().toEpochMilli()
    private val agora = t(18, 18)

    private fun ok(t: Long) = ResumoRede.Amostra(t, true, 20, 10, -60, false, true)
    private fun semNet(t: Long) = ResumoRede.Amostra(t, false, -1, -1, -60, false, false)
    private fun semDns(t: Long) = ResumoRede.Amostra(t, true, 30, -1, -60, false, true)
    private fun semWifi(t: Long) = ResumoRede.Amostra(t, false, -1, -1, 0, false, false)

    /** Tudo funcionando agora (cada teste muda só o que interessa). */
    private fun med(
        conectada: Boolean = true, dbm: Int = -55, tcp: Int? = 20, dns: Long? = 15, dnsNome: String? = null,
        http: Int? = 204, httpMs: Long? = 150, vpnFixada: Boolean = false, vpnDePe: Boolean = false,
        roteador: InternetEmUmaFrase.Roteador? = null, mudo: Boolean = false, hist: List<ResumoRede.Amostra> = emptyList(),
    ) = InternetEmUmaFrase.Medicoes(agora, conectada, dbm, tcp, dns, dnsNome, http, httpMs, vpnFixada, vpnDePe, roteador, mudo, hist)

    private fun explicar(m: InternetEmUmaFrase.Medicoes) = InternetEmUmaFrase.explicar(m, zona)

    // A internet caiu às 18:12 e a TV mede a cada 30 s desde então.
    private val quedaDesde1812 = listOf(ok(t(18, 5)), ok(t(18, 10))) + (0..12).map { semNet(t(18, 12) + it * 30_000L) }

    @Test fun tudoOk() {
        val r = explicar(med(hist = (0 until 288).map { ok(agora - it * 5 * min) }.reversed()))
        assertEquals(InternetEmUmaFrase.Causa.OK, r.causa)
        assertEquals(0, r.nivel)
        assertTrue(r.frase, r.frase.startsWith("Está tudo ok"))
        assertTrue(r.frase, r.frase.contains("Sem quedas nas últimas 24 h"))
    }

    @Test fun poucaMedicaoNaoPrometeAs24h() {
        val r = explicar(med(hist = (0..10).map { ok(agora - it * 5 * min) }.reversed()))
        assertEquals(InternetEmUmaFrase.Causa.OK, r.causa)
        assertTrue(r.frase, !r.frase.contains("Sem quedas nas últimas 24 h"))
        assertTrue(r.frase, r.frase.contains("Nenhuma queda registrada"))
    }

    @Test fun roteadorSoEMudoPorTempoOuConexaoRecusada() {
        assertTrue(InternetEmUmaFrase.roteadorMudo("o roteador não respondeu a tempo"))
        assertTrue(InternetEmUmaFrase.roteadorMudo("nada respondeu nesse endereço"))
        // 404 da API antiga, erro desconhecido, chave recusada: o roteador não está "travado".
        assertTrue(!InternetEmUmaFrase.roteadorMudo("a API antiga não respondeu nesse caminho"))
        assertTrue(!InternetEmUmaFrase.roteadorMudo("não consegui falar com o roteador (IOException)"))
        assertTrue(!InternetEmUmaFrase.roteadorMudo("a API antiga do roteador não aceitou a chave (só a de integração funciona)"))
        assertTrue(!InternetEmUmaFrase.roteadorMudo(null))
    }

    @Test fun quedaQueSoMudaDeTipoNaoDizQueVoltou() {
        // Internet caiu 17:00, às 17:10 voltou só a conexão (DNS ainda falhando), tudo ok às 17:20.
        val h = listOf(ok(t(16, 55))) + (0..3).map { semNet(t(17, 0) + it * 150_000L) } +
            (0..3).map { semDns(t(17, 10) + it * 150_000L) } + (0..11).map { ok(t(17, 20) + it * 5 * min) }
        val q = InternetEmUmaFrase.quedas(h, agora)
        assertEquals(1, q.size)
        assertEquals(t(17, 0), q[0].inicio)
        assertEquals(t(17, 20), q[0].fim)
        val r = explicar(med(hist = h))
        assertTrue(r.frase, r.frase.contains("a internet caiu às 17:00 e voltou às 17:20 (20 min)"))
    }

    @Test fun semHistoricoNaoPrometeNada() {
        val r = explicar(med())
        assertEquals(InternetEmUmaFrase.Causa.OK, r.causa)
        assertTrue(r.frase, !r.frase.contains("24 h"))
    }

    @Test fun provedorCaiuERoteadorConfirma() {
        val r = explicar(med(tcp = null, dns = null, http = null, roteador = InternetEmUmaFrase.Roteador("ok", "error", null), hist = quedaDesde1812))
        assertEquals(InternetEmUmaFrase.Causa.PROVEDOR, r.causa)
        assertEquals(2, r.nivel)
        assertTrue(r.frase, r.frase.startsWith("O Wi-Fi está ok, mas a internet do provedor caiu às 18:12; o roteador também vê a internet fora (há 6 min)."))
    }

    @Test fun modemDesligadoNoRoteador() {
        val r = explicar(med(tcp = null, dns = null, http = null, roteador = InternetEmUmaFrase.Roteador("error", "error", null)))
        assertEquals(InternetEmUmaFrase.Causa.PROVEDOR, r.causa)
        assertTrue(r.frase, r.frase.contains("perdeu a ligação com o modem"))
        assertTrue(r.frase, !r.frase.contains("às"))   // sem histórico, sem hora inventada
    }

    @Test fun roteadorTemInternetMasATvNao() {
        val r = explicar(med(dbm = -82, tcp = null, dns = null, http = null, roteador = InternetEmUmaFrase.Roteador("ok", "ok", 12), hist = quedaDesde1812))
        assertEquals(InternetEmUmaFrase.Causa.ENTRE_TV_E_ROTEADOR, r.causa)
        assertTrue(r.frase, r.frase.contains("desde as 18:12"))
        assertTrue(r.frase, r.frase.contains("-82 dBm"))
    }

    @Test fun vpnTravada() {
        val r = explicar(med(tcp = null, dns = null, http = null, vpnFixada = true, vpnDePe = true, roteador = InternetEmUmaFrase.Roteador("ok", "ok", 12)))
        assertEquals(InternetEmUmaFrase.Causa.VPN, r.causa)
        assertTrue(r.frase, r.frase.contains("VPN"))
    }

    @Test fun roteadorMudo() {
        val r = explicar(med(tcp = null, dns = null, http = null, mudo = true))
        assertEquals(InternetEmUmaFrase.Causa.ROTEADOR, r.causa)
        assertTrue(r.frase, r.frase.contains("roteador pode ter travado"))
    }

    @Test fun semRoteadorConfiguradoChutaProvedor() {
        val r = explicar(med(tcp = null, dns = null, http = null, hist = quedaDesde1812))
        assertEquals(InternetEmUmaFrase.Causa.PROVEDOR, r.causa)
        assertTrue(r.frase, r.frase.contains("às 18:12 (há 6 min)"))
        assertTrue(r.sinais.contains("roteador: não consultado"))
    }

    @Test fun semWifiDesdeQuando() {
        val h = listOf(ok(t(17, 50)), semWifi(t(17, 55)), semWifi(t(17, 55) + 30_000), semWifi(t(18, 17)))
        val r = explicar(med(conectada = false, dbm = 0, tcp = null, dns = null, http = null, hist = h))
        assertEquals(InternetEmUmaFrase.Causa.SEM_WIFI, r.causa)
        // Buraco de mais de 15 min (17:55:30 -> 18:17) corta a sequência: só dá para afirmar desde 18:17.
        assertTrue(r.frase, r.frase.startsWith("A TV está sem Wi-Fi desde as 18:17."))
    }

    @Test fun soODnsCaiu() {
        val h = listOf(ok(t(18, 0)), semDns(t(18, 5)), semDns(t(18, 10)), semDns(t(18, 15)))
        val r = explicar(med(dns = null, dnsNome = "AdGuard", hist = h))
        assertEquals(InternetEmUmaFrase.Causa.DNS, r.causa)
        assertTrue(r.frase, r.frase.contains("o DNS (AdGuard) não responde desde as 18:05"))
    }

    @Test fun paginaDeTesteBarrada() {
        val r = explicar(med(http = 302))
        assertEquals(InternetEmUmaFrase.Causa.BLOQUEIO, r.causa)
        assertTrue(r.frase, r.frase.contains("código 302"))
    }

    @Test fun lentaPorSinalFraco() {
        val r = explicar(med(dbm = -80))
        assertEquals(InternetEmUmaFrase.Causa.LENTA, r.causa)
        assertEquals(1, r.nivel)
        assertTrue(r.frase, r.frase.contains("-80 dBm"))
    }

    @Test fun lentaPeloProvedor() {
        val r = explicar(med(tcp = 450, roteador = InternetEmUmaFrase.Roteador("ok", "ok", 400)))
        assertEquals(InternetEmUmaFrase.Causa.LENTA, r.causa)
        assertTrue(r.frase, r.frase.contains("450 ms") && r.frase.contains("é o provedor"))
    }

    @Test fun vpnDesligadaComInternetOk() {
        val r = explicar(med(vpnFixada = true, vpnDePe = false))
        assertEquals(InternetEmUmaFrase.Causa.VPN_DESLIGADA, r.causa)
    }

    @Test fun quedaRecenteJaResolvida() {
        val h = listOf(ok(t(17, 0)), semNet(t(17, 10)), semNet(t(17, 10) + 30_000), ok(t(17, 20)), ok(t(17, 25)), ok(t(18, 15)))
        val r = explicar(med(hist = h))
        assertEquals(InternetEmUmaFrase.Causa.QUEDA_RECENTE, r.causa)
        assertEquals(1, r.nivel)   // voltou há menos de 1 h
        assertEquals("A internet está ok agora; a internet caiu às 17:10 e voltou às 17:20 (10 min).", r.frase)
    }

    @Test fun quedaDeOntem() {
        val h = listOf(ok(t(22, 0, 9)), semNet(t(22, 5, 9)), semNet(t(22, 10, 9)), semNet(t(22, 15, 9)), ok(t(22, 20, 9)), ok(t(18, 15)))
        val r = explicar(med(hist = h))
        assertEquals(InternetEmUmaFrase.Causa.QUEDA_RECENTE, r.causa)
        assertEquals(0, r.nivel)
        assertTrue(r.frase, r.frase.contains("caiu ontem às 22:05 e voltou às 22:20 (15 min)"))
    }

    @Test fun quedaSeguidaDeBuracoNaoInventaAVolta() {
        // Caiu às 22:05 e a TV parou de medir por 1 h: "voltou às 22:20" seria inventado.
        val h = listOf(ok(t(22, 0, 9)), semNet(t(22, 5, 9)), ok(t(23, 5, 9)), ok(t(18, 15)))
        val r = explicar(med(hist = h))
        assertEquals(InternetEmUmaFrase.Causa.QUEDA_RECENTE, r.causa)
        assertTrue(r.frase, r.frase.contains("caiu ontem às 22:05 (depois a TV ficou sem medir"))
    }

    @Test fun inicioDaFalhaPedeAmostraRecente() {
        // Última amostra falhando, mas de 40 min atrás: não dá para dizer que a falha continua.
        assertNull(InternetEmUmaFrase.inicioDaFalha(listOf(semNet(agora - 40 * min)), agora) { !it.internet })
        assertEquals(t(18, 12), InternetEmUmaFrase.inicioDaFalha(quedaDesde1812, agora) { !it.internet })
    }

    @Test fun duracoes() {
        assertEquals("menos de 1 min", InternetEmUmaFrase.duracao(30_000))
        assertEquals("6 min", InternetEmUmaFrase.duracao(6 * min))
        assertEquals("2 h", InternetEmUmaFrase.duracao(120 * min))
        assertEquals("1 h 20 min", InternetEmUmaFrase.duracao(80 * min))
    }

    @Test fun lerSaudeDoUnifi() {
        val r = InternetEmUmaFrase.lerSaudeRoteador("""{"meta":{"rc":"ok"},"data":[{"subsystem":"wlan","status":"ok"},
            {"subsystem":"wan","status":"ok"},{"subsystem":"www","status":"error","latency":null}]}""")
        assertNotNull(r)
        assertTrue(r!!.internetFora)
        assertNull(r.latenciaMs)
        val ok = InternetEmUmaFrase.lerSaudeRoteador("""{"data":[{"subsystem":"www","status":"ok","latency":14}]}""")!!
        assertTrue(ok.internetOk)
        assertEquals(14, ok.latenciaMs)
        assertNull(InternetEmUmaFrase.lerSaudeRoteador("""{"data":[{"subsystem":"lan","status":"ok"}]}"""))
        assertNull(InternetEmUmaFrase.lerSaudeRoteador("<html>"))
    }
}

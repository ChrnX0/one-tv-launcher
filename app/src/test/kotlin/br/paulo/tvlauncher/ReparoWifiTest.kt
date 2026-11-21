package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.ReparoWifi
import br.paulo.tvlauncher.network.ReparoWifi.Acao
import br.paulo.tvlauncher.network.Unifi
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wi-Fi que não volta sozinho (ReparoWifi) e o relato do roteador (Unifi.relato*). MACs e nomes são de exemplo. */
class ReparoWifiTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    @Test fun conectadoOuRecenteNaoMexe() {
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0, null, 0, 0, 0, false))
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 4 * min, t0, 0, 0, 0, false))
    }

    @Test fun religaDuasVezesComIntervaloEDepoisReinicia() {
        assertEquals(Acao.RELIGAR_WIFI, ReparoWifi.decidir(t0 + 5 * min, t0, 0, 0, 0, false))
        // logo depois da 1ª religada: espera 5 min
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 8 * min, t0, 1, t0 + 5 * min, 0, false))
        assertEquals(Acao.RELIGAR_WIFI, ReparoWifi.decidir(t0 + 10 * min, t0, 1, t0 + 5 * min, 0, false))
        assertEquals(Acao.REINICIAR, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, 0, false))
    }

    @Test fun naoReiniciaComSomNemEmCiclo() {
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, 0, somTocando = true))
        // reiniciou por isso há 1 h: não reinicia de novo
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, t0 - 60 * min, false))
        // há 7 h: pode
        assertEquals(Acao.REINICIAR, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, t0 - 7 * 60 * min, false))
    }

    @Test fun telaAcesaSoReiniciaSemNinguemMexer() {
        // tela acesa e alguém apertou tecla há 10 min: não reinicia (só religa o Wi-Fi, o que já fez)
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, 0, false, telaAcesa = true, semTeclaMs = 10 * min))
        // tela acesa, largada há 40 min: reinicia
        assertEquals(Acao.REINICIAR, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, 0, false, telaAcesa = true, semTeclaMs = 40 * min))
        // tela apagada: reinicia mesmo com tecla recente (ninguém está vendo)
        assertEquals(Acao.REINICIAR, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, 0, false, telaAcesa = false, semTeclaMs = 1 * min))
        // religar o Wi-Fi não depende da tela
        assertEquals(Acao.RELIGAR_WIFI, ReparoWifi.decidir(t0 + 5 * min, t0, 0, 0, 0, false, telaAcesa = true, semTeclaMs = 0))
    }

    @Test fun relogioQueVoltouDepoisDoReinicioNaoViraCiclo() {
        // reiniciou sem Wi-Fi e o relógio (sem NTP) voltou 2 dias: o último reinício parece "no futuro" e não libera outro
        assertEquals(Acao.NADA, ReparoWifi.decidir(t0 + 15 * min, t0, 2, t0 + 10 * min, t0 + 2 * 24 * 60 * min, false))
    }

    @Test fun relatoDosAparelhosMostraQuandoLigou() {
        val zona = ZoneId.of("UTC")
        val agora = 1_794_736_000_000L   // 15/11/2026 09:46:40 UTC
        val json = """{"meta":{"rc":"ok"},"data":[{"name":"Roteador","model":"UX7","type":"udm","uptime":24600,"version":"4.1.0","upgradable":false}]}"""
        val l = Unifi.relatoAparelhos(json, agora, zona)
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].contains("Roteador (UX7, udm): ligado há 6 h 50 min (desde 15/11 02:56)"))
        assertTrue(l[0].contains("versão 4.1.0"))
    }

    @Test fun relatoDosEventosFiltraTvEAparelhosEmOrdem() {
        val zona = ZoneId.of("UTC")
        val json = """{"meta":{"rc":"ok"},"data":[
            {"key":"EVT_WU_Disconnected","user":"aa:bb:cc:dd:ee:ff","time":1794722228000,"msg":"User[aa:bb:cc:dd:ee:ff] disconnected"},
            {"key":"EVT_WU_Connected","user":"11:22:33:44:55:66","time":1794722000000,"msg":"outro aparelho"},
            {"key":"EVT_AP_RestartedUnknown","time":1794722100000,"msg":"AP restarted"}]}"""
        val l = Unifi.relatoEventos(json, "AA:BB:CC:DD:EE:FF", zona)
        assertEquals("eventos: 3 no período, 2 sobre a TV ou os aparelhos UniFi", l[0])
        assertEquals(3, l.size)
        assertTrue(l[1], l[1].startsWith("15/11 05:55:00 EVT_AP_RestartedUnknown"))
        assertTrue(l[2], l[2].startsWith("15/11 05:57:08 [TV] EVT_WU_Disconnected"))
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.LgNetcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras do volume da LG pela rede (ideia 49). Respostas copiadas da LG da Sala em 14/11/2026. */
class LgNetcastTest {
    private val volumeOk = """<?xml version="1.0" encoding="utf-8"?><envelope><ROAPError>200</ROAPError><ROAPErrorDetail>OK</ROAPErrorDetail><data><mute>false</mute><minLevel>0</minLevel><maxLevel>100</maxLevel><level>11</level></data></envelope>"""
    private val authOk = """<?xml version="1.0" encoding="utf-8"?><envelope><ROAPError>200</ROAPError><ROAPErrorDetail>OK</ROAPErrorDetail><session>1111111111</session></envelope>"""
    private val modelo = """<?xml version="1.0" encoding="utf-8"?><envelope><device><deviceType>TV</deviceType><modelName>42LN5700-SA_70</modelName><friendlyName>[TV][LG]Sala</friendlyName><tvEvent>NC4.0 SU1</tvEvent><manufacturer>LG Electronics</manufacturer><uuid>aaaa-bbbb</uuid></device></envelope>"""

    @Test fun leVolumeEMudo() {
        assertEquals(LgNetcast.Volume(11, false, 100), LgNetcast.volume(volumeOk))
        assertTrue(LgNetcast.volume(volumeOk.replace("<mute>false", "<mute>true"))!!.mudo)
    }

    @Test fun volumeSemOkOuSemNivelEhNull() {
        assertNull(LgNetcast.volume(volumeOk.replace("<ROAPError>200", "<ROAPError>401")))
        assertNull(LgNetcast.volume(authOk))
        assertNull(LgNetcast.volume(""))
    }

    @Test fun nivelForaDaFaixaFicaNaFaixa() {
        assertEquals(100, LgNetcast.volume(volumeOk.replace("<level>11", "<level>150"))!!.nivel)
    }

    @Test fun erroDoRoap() {
        assertEquals(200, LgNetcast.erro(authOk))
        assertNull(LgNetcast.erro("<html>401</html>"))
    }

    @Test fun modeloDaLg() {
        val m = LgNetcast.modelo(modelo)!!
        assertEquals("aaaa-bbbb", m.uuid); assertEquals("[TV][LG]Sala", m.nome); assertEquals("42LN5700-SA_70", m.modelo)
        assertNull(LgNetcast.modelo(modelo.replace("LG Electronics", "Samsung")))
        assertNull(LgNetcast.modelo(modelo.replace("<uuid>aaaa-bbbb</uuid>", "")))
    }

    @Test fun corposDoRoap() {
        assertTrue(LgNetcast.corpoPedirCodigo().endsWith("<auth><type>AuthKeyReq</type></auth>"))
        assertTrue(LgNetcast.corpoParear("123456").endsWith("<auth><type>AuthReq</type><value>123456</value></auth>"))
        assertTrue(LgNetcast.corpoTecla(24).endsWith("<command><name>HandleKeyInput</name><value>24</value></command>"))
    }

    @Test fun codigoSoComNumeros() {
        assertTrue(LgNetcast.codigoValido("123456"))
        assertFalse(LgNetcast.codigoValido("12"))
        assertFalse(LgNetcast.codigoValido("12a456"))
        assertFalse(LgNetcast.codigoValido("1</value><x>"))
        assertFalse(LgNetcast.codigoValido(null))
    }

    @Test fun soIpDeCasa() {
        assertTrue(LgNetcast.ipLocal("192.168.0.20"))
        assertTrue(LgNetcast.ipLocal("10.0.0.5"))
        assertTrue(LgNetcast.ipLocal("172.20.1.1"))
        assertFalse(LgNetcast.ipLocal("172.32.1.1"))
        assertFalse(LgNetcast.ipLocal("8.8.8.8"))
        assertFalse(LgNetcast.ipLocal("192.168.1"))
        assertFalse(LgNetcast.ipLocal("192.168.1.256"))
        assertFalse(LgNetcast.ipLocal("192.168.01.5"))
        assertFalse(LgNetcast.ipLocal("tv.lan"))
        assertFalse(LgNetcast.ipLocal(null))
    }

    @Test fun teclasAteOAlvo() {
        assertEquals(5, LgNetcast.teclas(11, 16))
        assertEquals(-11, LgNetcast.teclas(11, 0))
        assertEquals(0, LgNetcast.teclas(30, 30))
        assertEquals(89, LgNetcast.teclas(11, 500))   // alvo fora da faixa para no máximo
        assertEquals(-11, LgNetcast.teclas(11, -5))
    }

    @Test fun descerNuncaSobe() {
        assertEquals(-10, LgNetcast.descerAte(40, 30))
        assertEquals(0, LgNetcast.descerAte(20, 30))
    }

    @Test fun porcentagemParaNivel() {
        assertEquals(30, LgNetcast.nivelDaPorcentagem(30))
        assertEquals(15, LgNetcast.nivelDaPorcentagem(50, 30))
        assertEquals(100, LgNetcast.nivelDaPorcentagem(150))
    }

    @Test fun agenteUdapSoNoUdap() {
        assertTrue(LgNetcast.agenteUdap(LgNetcast.CAMINHO_MODELO))
        assertFalse(LgNetcast.agenteUdap(LgNetcast.CAMINHO_AUTH))
        assertFalse(LgNetcast.agenteUdap(LgNetcast.CAMINHO_COMANDO))
        assertFalse(LgNetcast.agenteUdap(LgNetcast.CAMINHO_VOLUME))
    }

    @Test fun alarmeNuncaPassaDoTeto() {
        assertEquals(30, LgNetcast.alvoAlarme(8, 100, 50))     // o caso de 14/11: pedia 50, agora para em 30
        assertEquals(30, LgNetcast.alvoAlarme(0, 100, 100))
        assertEquals(20, LgNetcast.alvoAlarme(5, 100, 20))
        assertNull(LgNetcast.alvoAlarme(30, 100, 70))          // já está no teto: não mexe
        assertNull(LgNetcast.alvoAlarme(66, 100, 50))          // nunca abaixa
        assertNull(LgNetcast.alvoAlarme(8, 100, 0))            // mínimo desligado
    }

    @Test fun devolverDepoisDoAlarme() {
        assertEquals(8, LgNetcast.devolverAlarme(30, 30, 8))   // ninguém mexeu: volta
        assertEquals(8, LgNetcast.devolverAlarme(32, 30, 8))   // a 2 pontos: ainda conta como nosso
        assertEquals(8, LgNetcast.devolverAlarme(66, 30, 8))   // acima do teto: sempre volta (o caso que acordou a casa)
        assertNull(LgNetcast.devolverAlarme(20, 30, 8))        // alguém baixou para 20: respeita
        assertNull(LgNetcast.devolverAlarme(8, 30, 8))         // já está como era
        assertNull(LgNetcast.devolverAlarme(30, null, 8))      // não fomos nós que subimos
        assertNull(LgNetcast.devolverAlarme(30, 30, -1))       // não sabemos o de antes
    }

    @Test fun rodadaTemPoucasTeclas() {
        assertEquals(3, LgNetcast.rodada(8, 30))
        assertEquals(-3, LgNetcast.rodada(66, 8))
        assertEquals(1, LgNetcast.rodada(29, 30))
        assertEquals(0, LgNetcast.rodada(30, 30))
    }

    @Test fun teclaDeEnergiaDesliga() {
        assertEquals(1, LgNetcast.TECLA_ENERGIA)
        assertTrue(LgNetcast.corpoTecla(LgNetcast.TECLA_ENERGIA).endsWith("<value>1</value></command>"))
    }

    @Test fun despertadorSobeAosPoucosAteVolumeFixo() {
        // Opções nunca passam do teto (o alarme que foi a 66 acordou a casa).
        assertTrue(LgNetcast.OPCOES_ALARME.all { it == LgNetcast.NAO_MEXER || it in 1..LgNetcast.TETO_ALARME })
        assertEquals(30, LgNetcast.alvoFixoAlarme(70))
        assertEquals(LgNetcast.NAO_MEXER, LgNetcast.alvoFixoAlarme(0))
        assertEquals(LgNetcast.NAO_MEXER, LgNetcast.alvoFixoAlarme(-3))
        assertEquals(LgNetcast.NAO_MEXER, LgNetcast.volumeLgInicial(0))   // quem tinha "não mexer" continua sem mexer
        assertEquals(LgNetcast.PADRAO_ALARME, LgNetcast.volumeLgInicial(30))
        // Começa em 5 (ou no alvo, se menor); rampa 0 na LG vira 90 s: sempre gradual.
        assertEquals(5, LgNetcast.inicioRampaAlarme(15))
        assertEquals(3, LgNetcast.inicioRampaAlarme(3))
        assertEquals(90, LgNetcast.rampaLg(0))
        assertEquals(120, LgNetcast.rampaLg(120))
        // 5 -> 15 em 60 s: 10 passos de 6 s; nunca menos de 1 s entre teclas.
        assertEquals(6000L, LgNetcast.intervaloRampaMs(60, 5, 15))
        assertEquals(1000L, LgNetcast.intervaloRampaMs(10, 5, 30))
        assertEquals(0L, LgNetcast.intervaloRampaMs(60, 15, 15))
        // Alguém mexeu no volume durante a subida.
        assertFalse(LgNetcast.mexeramNaRampa(9, 8))
        assertTrue(LgNetcast.mexeramNaRampa(2, 8))
    }

    @Test fun desligarLgJuntoSoNaEntradaDoChromecast() {
        val cc = LgNetcast.Entrada("HDMI1", "Sala")
        assertTrue(LgNetcast.desligarLgJunto(cc, "Sala", null))
        assertTrue(LgNetcast.desligarLgJunto(LgNetcast.Entrada("HDMI1", ""), null, "HDMI1"))
        assertFalse(LgNetcast.desligarLgJunto(LgNetcast.Entrada("TV", ""), "Sala", "HDMI1"))      // antena
        assertFalse(LgNetcast.desligarLgJunto(LgNetcast.Entrada("HDMI2", "PS4"), "Sala", "HDMI1")) // outro HDMI
        assertFalse(LgNetcast.desligarLgJunto(null, "Sala", "HDMI1"))                               // não leu: não desliga
    }

    @Test fun desligarAoApagarNaoRepeteLogoDepois() {
        assertTrue(LgNetcast.desligarAoApagar(1_000L, 0L))          // nunca desligamos
        assertFalse(LgNetcast.desligarAoApagar(30_000L, 5_000L))    // o timer acabou de desligar
        assertTrue(LgNetcast.desligarAoApagar(70_000L, 5_000L))
    }

    @Test fun somDoAppEsperaALgFicarPronta() {
        val ini = 1_000_000L
        // Sem LG controlada: rampa normal.
        assertEquals(ini, LgNetcast.inicioRampaApp(ini, null, ini + 50_000))
        // LG ainda acordando (teste de 20/11: 52 s): a rampa não anda, o som fica no mínimo.
        assertEquals(ini + 50_000, LgNetcast.inicioRampaApp(ini, 0L, ini + 50_000))
        // LG pronta aos 52 s: a rampa do som conta a partir dali.
        assertEquals(ini + 52_000, LgNetcast.inicioRampaApp(ini, ini + 52_000, ini + 60_000))
        // A LG nunca respondeu: desiste e sobe a partir do limite, sem pular para o volume cheio.
        assertEquals(ini + LgNetcast.ESPERA_LG_APP_MS, LgNetcast.inicioRampaApp(ini, 0L, ini + 200_000))
    }
}

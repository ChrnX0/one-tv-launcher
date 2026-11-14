package br.paulo.tvlauncher

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
}

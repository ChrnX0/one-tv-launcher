package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.LgNetcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acordar o Chromecast quando a LG liga na entrada dele. Resposta copiada da LG da Sala em 15/11/2026. */
class AcordarComLgTest {
    private val naHdmi1 = """<?xml version="1.0" encoding="utf-8"?><envelope><ROAPError>200</ROAPError><ROAPErrorDetail>OK</ROAPErrorDetail><data><chtype>terrestrial</chtype><sourceIndex>8</sourceIndex><physicalNum>65535</physicalNum><major>65520</major><displayMajor>65520</displayMajor><minor>65520</minor><displayMinor>-16</displayMinor><chname></chname><progName></progName><audioCh>0</audioCh><inputSourceName>HDMI1</inputSourceName><inputSourceType>6</inputSourceType><labelName>Sala de estar</labelName><inputSourceIdx>3</inputSourceIdx></data></envelope>"""

    @Test fun leAEntrada() {
        assertEquals(LgNetcast.Entrada("HDMI1", "Sala de estar"), LgNetcast.entrada(naHdmi1))
        assertNull(LgNetcast.entrada(naHdmi1.replace("<ROAPError>200", "<ROAPError>401")))
        assertNull(LgNetcast.entrada(naHdmi1.replace("<inputSourceName>HDMI1</inputSourceName>", "")))
    }

    @Test fun reconheceOChromecastPeloNomeOuPelaEntradaAprendida() {
        val e = LgNetcast.entrada(naHdmi1)!!
        assertTrue(LgNetcast.entradaDoChromecast(e, "Sala de estar", null))
        assertTrue(LgNetcast.entradaDoChromecast(e, " sala DE estar ", null))
        assertTrue(LgNetcast.entradaDoChromecast(LgNetcast.Entrada("HDMI1", ""), "Sala de estar", "HDMI1"))
        assertFalse(LgNetcast.entradaDoChromecast(LgNetcast.Entrada("HDMI2", "Playstation"), "Sala de estar", "HDMI1"))
        assertFalse(LgNetcast.entradaDoChromecast(e, null, null))
        assertFalse(LgNetcast.entradaDoChromecast(e, "", null))
    }

    @Test fun chromecastDormindoTemORotuloDaLg() {
        // Sala, 15/11/2026 14:30: com o Chromecast dormindo a LG mostrou HDMI1 "Smartphone", não o nome dele.
        val dormindo = LgNetcast.Entrada("HDMI1", "Smartphone")
        assertFalse(LgNetcast.entradaDoChromecast(dormindo, "Sala de estar", null))
        assertTrue(LgNetcast.entradaDoChromecast(dormindo, "Sala de estar", "HDMI1"))
    }

    @Test fun naAntenaNuncaAcorda() {
        val antena = LgNetcast.Entrada("TV", "Sala de estar")
        assertFalse(LgNetcast.entradaDoChromecast(antena, "Sala de estar", "TV"))
    }

    @Test fun soConfereQuandoALgVoltaDepoisDeSair() {
        val r = LgNetcast.Volta()
        assertFalse(r.ver(true))                 // LG ligada quando o Chromecast dormiu: nada
        assertFalse(r.ver(false))                // uma leitura perdida ainda não é "desligou"
        assertFalse(r.ver(true))
        assertFalse(r.ver(false)); assertFalse(r.ver(false))   // desligou de verdade
        assertTrue(r.ver(true))                  // ligou: confere a entrada
        assertFalse(r.ver(true))                 // uma vez só por volta
        assertFalse(r.ver(false)); assertFalse(r.ver(false)); assertTrue(r.ver(true))
    }
}

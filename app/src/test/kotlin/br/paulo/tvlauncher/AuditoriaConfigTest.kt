package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correções da auditoria de 10/11/2026 (grupo "config"): só as regras puras, sem Android. */
class AuditoriaConfigTest {
    // config-1: a tela da TV pede o PIN nas mesmas chaves que o painel protege.
    @Test fun chavesDoPainelPedemPinNaTv() {
        for (c in Extras.CHAVES_COM_PIN) {
            assertTrue(c.name, Extras.precisaPinNaTv(c, ligadoAgora = true, temPin = true))
            if (c != Extras.Chave.HORA_DORMIR && c != Extras.Chave.MODO_SIMPLES) assertTrue(c.name, Extras.precisaPinNaTv(c, ligadoAgora = false, temPin = true))
        }
        assertTrue(Extras.precisaPinNaTv(Extras.Chave.PIN_HOME, true, true))
        assertTrue(Extras.precisaPinNaTv(Extras.Chave.VER_TELA, false, true))   // ligar ver a tela também pede
    }

    @Test fun horaDeDormirSoPedeAoDesligarEOutrasNuncaPedem() {
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.HORA_DORMIR, ligadoAgora = false, temPin = true))
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.MODO_LEVE, true, true))
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.SOM_NAVEGACAO, false, true))
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.FILTRO_NOTURNO, false, true))
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.PIN_HOME, true, temPin = false))   // sem PIN não há o que pedir
    }

    // MODO_SIMPLES (12/11/2026): ligar é livre, mas desligar pela tela da TV ou pelo painel pede o PIN.
    @Test fun conjuntoComPinTemAsOitoChaves() {
        assertEquals(setOf(Extras.Chave.JANELA_OTA, Extras.Chave.TRAVAR_CRITICOS, Extras.Chave.PIN_HOME, Extras.Chave.DIARIO_DNS,
            Extras.Chave.HORA_DORMIR, Extras.Chave.RECADO_LINK, Extras.Chave.VER_TELA, Extras.Chave.MODO_SIMPLES), Extras.CHAVES_COM_PIN)
    }

    // config-2: orientação do EXIF.
    @Test fun orientacaoExif() {
        assertEquals(0, FotosProtetor.graus(1))
        assertEquals(0, FotosProtetor.graus(0))   // sem a tag
        assertEquals(90, FotosProtetor.graus(6))
        assertEquals(180, FotosProtetor.graus(3))
        assertEquals(270, FotosProtetor.graus(8))
        assertTrue(FotosProtetor.espelha(2)); assertTrue(FotosProtetor.espelha(5))
        assertFalse(FotosProtetor.espelha(6)); assertFalse(FotosProtetor.espelha(1))
        assertTrue(FotosProtetor.deitada(6)); assertTrue(FotosProtetor.deitada(8)); assertFalse(FotosProtetor.deitada(3))
    }

    // config-10: mesmo nome não sobrescreve.
    @Test fun nomeLivreNaoSobrescreve() {
        val ja = mutableSetOf("praia.jpg")
        assertEquals("foto.jpg", FotosProtetor.nomeLivre("foto.jpg") { it in ja })
        assertEquals("praia-2.jpg", FotosProtetor.nomeLivre("praia.jpg") { it in ja })
        ja += "praia-2.jpg"
        assertEquals("praia-3.jpg", FotosProtetor.nomeLivre("praia.jpg") { it in ja })
        assertTrue(FotosProtetor.nomeValido(FotosProtetor.nomeLivre("praia.jpg") { it in ja }))   // o painel ainda aceita
    }

    // config-6: desfazer sem "antes" não grava nada.
    @Test fun modoLeveSemAntesNaoVolta() {
        assertNull(ModoLeve.paraVoltar(""))
        assertNull(ModoLeve.paraVoltar(null))
        assertEquals(0.5f, ModoLeve.paraVoltar("""{"window_animation_scale":0.5}""")!!["window_animation_scale"])
    }

    // painel-9: tetos do mDNS.
    @Test fun tetosDosVizinhos() {
        assertTrue(PainelVizinhos.cabe(0, PainelVizinhos.MAX_ACHADAS))
        assertFalse(PainelVizinhos.cabe(PainelVizinhos.MAX_ACHADAS, PainelVizinhos.MAX_ACHADAS))
        assertTrue(PainelVizinhos.cabe(PainelVizinhos.MAX_ACHADAS, PainelVizinhos.MAX_ACHADAS, jaTem = true))   // atualizar pode
    }
}

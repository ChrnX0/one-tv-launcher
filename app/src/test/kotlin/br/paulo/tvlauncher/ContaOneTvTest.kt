package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContaOneTvTest {

    @Test fun soAceitaDonoQuandoAUnicaMarcaEhAPermitida() {
        // O Android pergunta com esta marca; responder SIM é o que deixa o `dpm` passar.
        assertTrue(ContaOneTv.aceitaDono(arrayOf(ContaOneTv.PERMITE)))
    }

    @Test fun recusaAMarcaDeProibidoEQualquerOutra() {
        assertFalse(ContaOneTv.aceitaDono(arrayOf(ContaOneTv.PROIBE)))
        assertFalse(ContaOneTv.aceitaDono(arrayOf("android.account.OUTRA")))
        // Se vier a permitida junto com qualquer outra, não é "só a permitida": recusa.
        assertFalse(ContaOneTv.aceitaDono(arrayOf(ContaOneTv.PERMITE, ContaOneTv.PROIBE)))
    }

    @Test fun semMarcasNaoAceita() {
        assertFalse(ContaOneTv.aceitaDono(null))
        assertFalse(ContaOneTv.aceitaDono(emptyArray()))
    }

    @Test fun oTipoNuncaEhDoGoogle() {
        // Garantia de transparência: um autenticador do tipo com.google ensinaria a burlar DO em qualquer Android TV.
        assertFalse(ContaOneTv.TIPO.startsWith("com.google"))
        assertTrue(ContaOneTv.TIPO.startsWith("br.paulo.tvlauncher"))
    }

    @Test fun aAcaoContaSoPassaPeloAdb() {
        assertTrue(AcoesControle.permitida(ControleShizukuReceiver.CONTA, viaAdb = true))
        assertFalse(AcoesControle.permitida(ControleShizukuReceiver.CONTA, viaAdb = false))
    }
}

package br.paulo.tvlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auditoria 10/11/2026 (privilegios-3): pelo receiver dos apps (API_V23) só passam INICIAR, PARAR e ESTADO. */
class ControleShizukuReceiverTest {
    private val soAdb = listOf(
        ControleShizukuReceiver.OCULTAR, ControleShizukuReceiver.REINICIAR, ControleShizukuReceiver.TESTE_ERRO,
        ControleShizukuReceiver.ROTINA, ControleShizukuReceiver.ROTULOS, ControleShizukuReceiver.ERROS,
        ControleShizukuReceiver.DEBLOAT, ControleShizukuReceiver.PAINEL_PIN, ControleShizukuReceiver.EXTRAS,
        ControleShizukuReceiver.RECADO, "br.paulo.tvlauncher.QUALQUER_OUTRA")

    @Test fun appsSoLigamDesligamEPerguntam() {
        for (a in AcoesControle.DOS_APPS) { assertTrue(a, AcoesControle.permitida(a, viaAdb = false)) }
        for (a in soAdb) assertFalse(a, AcoesControle.permitida(a, viaAdb = false))
        assertFalse(AcoesControle.permitida(null, viaAdb = false))
    }

    @Test fun adbPodeTudo() {
        for (a in soAdb + AcoesControle.DOS_APPS) assertTrue(a, AcoesControle.permitida(a, viaAdb = true))
    }
}

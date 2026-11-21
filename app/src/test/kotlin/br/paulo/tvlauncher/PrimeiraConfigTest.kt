package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.PrimeiraConfig
import br.paulo.tvlauncher.panel.QrDoApp
import br.paulo.tvlauncher.panel.CodigoEntrada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 97: quem pode criar o PIN pelo assistente do celular (PrimeiraConfig) e o código de 15 min. */
class PrimeiraConfigTest {
    @Test fun tvComPinRecusaSempre() {
        // Mesmo com o modo ativo e um PIN válido: TV configurada nunca troca o PIN por aqui.
        assertEquals(PrimeiraConfig.Recusa.JA_TEM_PIN, PrimeiraConfig.conferir(temPin = true, modoAtivo = true, pin = "123456"))
        assertEquals(409, PrimeiraConfig.Recusa.JA_TEM_PIN.status)
    }

    @Test fun semATelaDoQrNaTvRecusa() {
        assertEquals(PrimeiraConfig.Recusa.FORA_DO_MODO, PrimeiraConfig.conferir(temPin = false, modoAtivo = false, pin = "123456"))
    }

    @Test fun pinPrecisaSerValido() {
        for (p in listOf(null, "", "12345", "1234567890123", "12a456", " 123456")) assertEquals(
            "pin=$p", PrimeiraConfig.Recusa.PIN_INVALIDO, PrimeiraConfig.conferir(temPin = false, modoAtivo = true, pin = p))
        assertNull(PrimeiraConfig.conferir(temPin = false, modoAtivo = true, pin = "123456"))
        assertNull(PrimeiraConfig.conferir(temPin = false, modoAtivo = true, pin = "123456789012"))
    }

    @Test fun codigoDaPrimeiraVale15MinEUmaVez() {
        var t = 1_000L
        val c = CodigoEntrada({ t }, PrimeiraConfig.VALIDADE_MS)
        val codigo = c.novo()
        t += 14 * 60_000L
        assertTrue(c.ativo())
        assertTrue(c.usar(codigo))
        assertFalse("uso único", c.usar(codigo))
        val outro = c.novo()
        t += PrimeiraConfig.VALIDADE_MS + 1
        assertFalse("venceu", c.usar(outro))
    }

    @Test fun codigoDeEntradaContinuaCom2Min() {
        var t = 0L
        val c = CodigoEntrada({ t })
        val codigo = c.novo()
        t += CodigoEntrada.VALIDADE_MS + 1
        assertFalse(c.usar(codigo))
        assertEquals(CodigoEntrada.VALIDADE_MS, c.validadeMs)
    }
}

/** Ideia 101: o fim do QR para o app de celular. */
class QrDoAppTest {
    @org.junit.Test fun `sem certificado e sem Tailscale nada muda no QR`() {
        org.junit.Assert.assertEquals("", QrDoApp.sufixo(null, null))
    }

    @org.junit.Test fun `impressao e o SHA-256 em hex minusculo e o nome do Tailscale entra`() {
        val s = QrDoApp.sufixo("abc".toByteArray(), "OneTV-Sala.tail1234.ts.net")
        org.junit.Assert.assertEquals(
            "&fp=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad&ts=onetv-sala.tail1234.ts.net", s)
    }

    @org.junit.Test fun `nome que nao e do Tailscale fica de fora`() {
        org.junit.Assert.assertEquals("", QrDoApp.sufixo(null, "evil.example.com"))
        org.junit.Assert.assertEquals("", QrDoApp.sufixo(null, "a&b.ts.net"))
    }
}

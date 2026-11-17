package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relatar um problema (degrau 3): só dados técnicos; a mensagem do erro nunca vai. */
class RelatarProblemaTest {
    private val d = RelatarProblema.Dados("1.0", 460100, "12", "Chromecast", "completo", 1, 0,
        "java.lang.IllegalStateException: /storage/emulated/0/Ana/foto.jpg")

    @Test fun soOTipoDoErro() {
        assertEquals("IllegalStateException", RelatarProblema.tipoDoErro(d.ultimoErro!!))
        assertEquals("?", RelatarProblema.tipoDoErro("algo sem tipo"))
        val c = RelatarProblema.corpo(d)
        assertFalse(c.contains("Ana")); assertFalse(c.contains("/storage"))
        assertTrue(c.contains("460100")); assertTrue(c.contains("1/0"))
    }

    @Test fun linkCodificado() {
        val l = RelatarProblema.link("ChrnX0/one-tv-launcher", d)
        assertTrue(l.startsWith("https://github.com/ChrnX0/one-tv-launcher/issues/new?title="))
        assertFalse(l.contains(" ")); assertFalse(l.contains("\n"))
        assertTrue(l.length < 1500)   // QR legível e URL aceita pelo GitHub
    }

    @Test fun emailQuandoHouver() {
        val m = RelatarProblema.destino("suporte@exemplo.com", "ChrnX0/one-tv-launcher", d)
        assertTrue(m.startsWith("mailto:suporte@exemplo.com?subject="))
        assertFalse(m.contains(" ")); assertFalse(m.contains("\n")); assertFalse(m.contains("Ana"))
        assertTrue(m.contains("460100")); assertFalse(m.contains("**"))
        assertTrue(m.length < 1200)   // QR ainda legível de longe
    }

    @Test fun semEmailOuInvalidoVoltaAoGithub() {
        for (e in listOf("", "  ", "sem-arroba", "a@b", "x@y.com?bcc=z@w.com", "a b@c.com"))
            assertTrue(e, RelatarProblema.destino(e, "ChrnX0/one-tv-launcher", d).startsWith("https://github.com/"))
    }
}

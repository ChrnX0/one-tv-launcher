package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ConectorWindowsTest {
    private val der = ByteArray(300) { (it * 7).toByte() }
    private val pem = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"

    @Test fun digitalEhSha1DoDer() {
        val esperado = MessageDigest.getInstance("SHA-1").digest(der).joinToString("") { "%02X".format(it) }
        assertEquals(esperado, ConectorWindows.digital(pem))
        assertEquals(40, esperado.length)
    }

    @Test fun scriptTemEnderecoDigitalECertificado() {
        val s = ConectorWindows.script("192.168.1.50", 8444, pem)!!
        assertTrue(s.startsWith("@echo off\r\n"))
        assertTrue("set \"TV=192.168.1.50\"" in s)
        assertTrue("set \"PORTA=8444\"" in s)
        assertTrue("set \"DIGITAL=${ConectorWindows.digital(pem)}\"" in s)
        // todas as linhas terminam em CRLF (o cmd.exe se perde nos rótulos com LF sozinho)
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(s))
        // o certificado embutido volta igual
        val volta = s.split("\r\n").filter { it.startsWith("::|") }.joinToString("") { it.removePrefix("::|") }
        assertTrue(der.contentEquals(Base64.getDecoder().decode(volta)))
        // só ASCII: o cmd.exe não lê UTF-8
        assertTrue(s.all { it.code < 128 })
    }

    @Test fun recusaHostQueViraComando() {
        assertNull(ConectorWindows.script("tv\" & calc & \"", 8444, pem))
        assertNull(ConectorWindows.script("tv%TEMP%", 8444, pem))
        assertNull(ConectorWindows.script("", 8444, pem))
        assertNull(ConectorWindows.script("tv-sala", 0, pem))
        assertNotNull(ConectorWindows.script("tv-sala.lan", 8444, pem))
    }

    @Test fun recusaPemComLixo() {
        assertNull(ConectorWindows.script("tv", 8444, "-----BEGIN CERTIFICATE-----\nAAAA & calc\n-----END CERTIFICATE-----"))
        assertNull(ConectorWindows.script("tv", 8444, ""))
    }
}

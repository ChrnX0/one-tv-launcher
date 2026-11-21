package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.ConectorWindows
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun letraPreferidaEhSDepoisYDepoisAQueOWindowsDer() {
        val s = ConectorWindows.script("192.168.1.50", 8444, pem)!!
        val padrao = s.indexOf("set \"LETRA=*\""); val y = s.indexOf("set \"LETRA=Y:\""); val l = s.indexOf("set \"LETRA=S:\"")
        assertTrue(padrao in 0 until y && y < l)   // a última que vale é a S
        // com espaço na frente: "Unidades:" / "Drives:" terminam em "s:" (medido num Windows 11 em português)
        assertTrue("find /i \" S:\"" in s && "find /i \" Y:\"" in s)
        assertTrue("net use %LETRA% \"%ALVO%\"" in s)
        assertFalse("fsutil" in s)                                 // pede administrador: a letra saía sempre "*"
        assertTrue("if not exist S:\\ " in s)
    }

    @Test fun elevacaoNaoPoeOCaminhoEntreAspasSimples() {
        val s = ConectorWindows.script("192.168.1.50", 8444, pem)!!
        assertFalse("'%~f0'" in s)                                 // C:\Users\D'Avila quebrava o PowerShell
        assertTrue("-FilePath \$env:EU" in s && "set \"EU=%~f0\"" in s)
    }

    @Test fun rotuloDaUnidadeVaiEmNumerosESoAscii() {
        val l = ConectorWindows.linhaDoRotulo("Sofaly · Sala \" & calc")
        assertTrue("83,111,102,97,108,121,32,183,32" in l)             // "Sofaly · " letra por letra
        assertFalse("calc" in l); assertTrue(l.all { it.code < 128 })   // nada do nome entra como texto
        val s = ConectorWindows.script("192.168.1.50", 8444, pem, "Sofaly · Sala")!!
        assertTrue(l.substringBefore("-join") in s && s.all { it.code < 128 })
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

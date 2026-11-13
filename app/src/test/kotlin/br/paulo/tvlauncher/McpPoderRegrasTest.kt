package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validações das ferramentas de poder do MCP (McpPoderRegras). */
class McpPoderRegrasTest {
    @Test fun urlApkSoHttps() {
        assertTrue(McpPoderRegras.urlApkValida("https://github.com/x/y/releases/download/v1/app.apk"))
        assertFalse(McpPoderRegras.urlApkValida("http://exemplo.com/a.apk"))
        assertFalse(McpPoderRegras.urlApkValida("https://"))
        assertFalse(McpPoderRegras.urlApkValida("https://user@host/a.apk"))
        assertFalse(McpPoderRegras.urlApkValida("file:///sdcard/a.apk"))
        assertFalse(McpPoderRegras.urlApkValida(null))
    }

    @Test fun linkRecusaEsquemasPerigosos() {
        assertTrue(McpPoderRegras.linkValido("https://www.youtube.com/results?search_query=gatos"))
        assertTrue(McpPoderRegras.linkValido("vnd.youtube:dQw4w9WgXcQ"))
        assertTrue(McpPoderRegras.linkValido("market://details?id=com.x"))
        for (ruim in listOf("file:///data", "content://x", "javascript:alert(1)", "intent:#Intent;end", "INTENT:x", "data:text/html,x", "semesquema", "https://a b", ""))
            assertFalse(ruim, McpPoderRegras.linkValido(ruim))
    }

    @Test fun textoEscapadoParaOInput() {
        assertEquals("ola%smundo", McpPoderRegras.textoParaInput("ola mundo"))
        assertEquals("d'\\''agua", McpPoderRegras.textoParaInput("d'agua"))
        assertNull(McpPoderRegras.textoParaInput("ação"))      // acento: o input text não digita
        assertNull(McpPoderRegras.textoParaInput("a\nb"))
        assertNull(McpPoderRegras.textoParaInput(""))
        assertNull(McpPoderRegras.textoParaInput("x".repeat(McpPoderRegras.MAX_TEXTO + 1)))
    }

    @Test fun horaEDias() {
        assertEquals(7 to 5, McpPoderRegras.hora("7:05"))
        assertEquals(23 to 59, McpPoderRegras.hora("23:59"))
        assertNull(McpPoderRegras.hora("24:00")); assertNull(McpPoderRegras.hora("7h30"))
        assertEquals(0, McpPoderRegras.dias(emptyList()))
        assertEquals(0b0111110, McpPoderRegras.dias(listOf("uteis")))
        assertEquals(0b1000001, McpPoderRegras.dias(listOf("sábado", "domingo")))
        assertEquals((1 shl 1) or (1 shl 3), McpPoderRegras.dias(listOf("segunda-feira", "Qua")))
        assertEquals(0b1111111, McpPoderRegras.dias(listOf("todos")))
        assertNull(McpPoderRegras.dias(listOf("feriado")))
    }

    @Test fun shaETexto() {
        assertTrue(McpPoderRegras.sha256Valido("a".repeat(64)))
        assertFalse(McpPoderRegras.sha256Valido("g".repeat(64)))
        assertTrue(McpPoderRegras.pareceTexto("olá".toByteArray()))
        assertFalse(McpPoderRegras.pareceTexto(byteArrayOf(0x50, 0x4b, 0, 3)))
        assertFalse(McpPoderRegras.pareceTexto(byteArrayOf(0xC3.toByte())))
    }
}

class McpPoderRegras2Test {
    @Test fun macEPacoteMagico() {
        val m = McpPoderRegras2.mac("AA:bb-CC:dd:EE:ff")!!
        assertEquals(6, m.size); assertEquals(0xAA.toByte(), m[0]); assertEquals(0xFF.toByte(), m[5])
        assertNull(McpPoderRegras2.mac("AA:BB:CC:DD:EE")); assertNull(McpPoderRegras2.mac("zz:bb:cc:dd:ee:ff"))
        val p = McpPoderRegras2.pacoteMagico(m)
        assertEquals(102, p.size); assertEquals(0xFF.toByte(), p[5]); assertEquals(0xAA.toByte(), p[6]); assertEquals(0xFF.toByte(), p[101])
    }

    @Test fun nomeDaUrl() {
        assertEquals("filme 1.mkv", McpPoderRegras2.nomeDaUrl("https://x.com/a/filme%201.mkv?token=1"))
        assertNull(McpPoderRegras2.nomeDaUrl("https://x.com/"))
        assertNull(McpPoderRegras2.nomeDaUrl("https://x.com/a/%2e%2e"))
    }

    @Test fun notaDoTexto() {
        assertEquals(3, McpPoderRegras2.nota("Configurações", "configuracoes"))
        assertEquals(2, McpPoderRegras2.nota("conf", "Configurações"))
        assertEquals(1, McpPoderRegras2.nota("rações", "Configurações"))
        assertEquals(0, McpPoderRegras2.nota("netflix", "YouTube"))
        assertEquals(0, McpPoderRegras2.nota("", "x"))
    }
}

class McpPerguntaShellTest {
    @Test fun naoEscondeComandoAtrasDeEspacos() {
        val cmd = "echo ok;" + " ".repeat(250) + "pm uninstall x"
        assertTrue(McpPoderRegras.textoPerguntaShell(cmd).contains("pm uninstall x"))
        val longo = "echo " + "a".repeat(500)
        assertTrue(McpPoderRegras.textoPerguntaShell(longo).contains("não mostrados"))
        assertTrue(McpPoderRegras.textoPerguntaShell("ls\n\nrm y").contains("ls rm y"))
    }

    @Test fun versao20250326SaiuPorCausaDosLotes() {
        assertFalse("2025-03-26" in McpProtocolo.VERSOES)
    }
}

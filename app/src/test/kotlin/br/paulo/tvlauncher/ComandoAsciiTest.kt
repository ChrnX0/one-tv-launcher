package br.paulo.tvlauncher

import br.paulo.tvlauncher.input.ComandoAscii
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComandoAsciiTest {
    @Test fun asciiPassaIgual() {
        val c = "dumpsys notification --noredact | grep -c \"x\""
        assertEquals(c, ComandoAscii.preparar(c))
    }

    @Test fun acentoViajaEmBase64ESoComAscii() {
        val c = "dumpsys notification | grep -c \"Depuração por Wi-Fi\""
        val p = ComandoAscii.preparar(c)
        assertTrue(p.startsWith("echo ") && p.endsWith(" | base64 -d | sh"))
        assertTrue(p.all { it in ' '..'~' })
        val b64 = p.removePrefix("echo ").removeSuffix(" | base64 -d | sh")
        assertEquals(c, String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
    }
}

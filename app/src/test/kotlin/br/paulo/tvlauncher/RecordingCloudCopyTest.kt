package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.CloudCopyRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCloudCopyTest {
    @Test fun pedacosCobremOArquivoInteiro() {
        val p = CloudCopyRules.pedacos(25L * 1024 * 1024)
        assertEquals(3, p.size)
        assertEquals(0L, p.first().first)
        assertEquals(25L * 1024 * 1024 - 1, p.last().last)
        p.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(listOf(0L..9L), CloudCopyRules.pedacos(10, 100))
        assertTrue(CloudCopyRules.pedacos(0).isEmpty())
        // 10 MiB serve ao OneDrive (múltiplo de 320 KiB) e ao Drive (256 KiB).
        assertEquals(0L, CloudCopyRules.PEDACO % (320 * 1024)); assertEquals(0L, CloudCopyRules.PEDACO % (256 * 1024))
    }

    @Test fun cabecalhosDeEnvio() {
        assertEquals("bytes 0-9/30", CloudCopyRules.contentRange(0L..9L, 30))
        assertEquals(1235L, CloudCopyRules.proximoDoDrive("bytes=0-1234"))
        assertEquals(0L, CloudCopyRules.proximoDoDrive(null))
        assertEquals("sha=qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", CloudCopyRules.digestBox(java.security.MessageDigest.getInstance("SHA-1").digest("abc".toByteArray())))
        assertEquals("https://g/me/drive/special/approot:/OneTV-gravacoes/2026-11-20%2021h00%20Globo.ts:/createUploadSession",
            CloudCopyRules.oneDriveSessao("https://g", "2026-11-20 21h00 Globo.ts"))
        val fim = JSONObject(CloudCopyRules.argDropboxFim("s1", 99, "a.ts"))
        assertEquals(99L, fim.getJSONObject("cursor").getLong("offset"))
        assertEquals("/OneTV-gravacoes/a.ts", fim.getJSONObject("commit").getString("path"))
        assertEquals(10L, JSONObject(CloudCopyRules.argDropboxAnexar("s1", 10)).getJSONObject("cursor").getLong("offset"))
    }

    @Test fun filaSobreviveEDescartaLixo() {
        val l = listOf(CloudCopyRules.Copia("usb:/x", "a.ts", "gdrive", true, 2))
        assertEquals(l, CloudCopyRules.deJson(CloudCopyRules.paraJson(l)))
        assertTrue(CloudCopyRules.deJson("""[{"d":"usb:/x","a":"../../etc.ts","n":"gdrive"},{"d":"","a":"b.ts","n":"x"}]""").isEmpty())
        assertTrue(CloudCopyRules.deJson("lixo").isEmpty())
    }

    @Test fun tentativasTemFim() {
        val c = CloudCopyRules.Copia("d", "a.ts", "n", false)
        assertNull(CloudCopyRules.depois(c, ok = true))
        assertEquals(1, CloudCopyRules.depois(c, ok = false)!!.tentativas)
        assertNull(CloudCopyRules.depois(c.copy(tentativas = CloudCopyRules.MAX_TENTATIVAS - 1), ok = false))
    }
}

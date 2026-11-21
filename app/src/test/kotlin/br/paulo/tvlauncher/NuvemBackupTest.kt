package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.NuvemBackup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envio do backup noturno para OneDrive, Dropbox e Box (NuvemBackup, ideia 98): só a parte pura. */
class NuvemBackupTest {
    @Test fun oneDriveVaiParaAPastaDoApp() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/special/approot:/OneTV-backup/onetv-backup-20261114.json:",
            NuvemBackup.oneDriveArquivo("onetv-backup-20261114.json"))
    }

    @Test fun dropboxSubstituiODoMesmoDia() {
        val o = JSONObject(NuvemBackup.argDropbox("onetv-backup-20261114.json"))
        assertEquals("/OneTV-backup/onetv-backup-20261114.json", o.getString("path"))
        assertEquals("overwrite", o.getString("mode"))
    }

    @Test fun corpoDoBoxTemAtributosAntesDoArquivo() {
        val corpo = String(NuvemBackup.corpoBox("F", """{"name":"a.json"}""", "a.json", "XYZ".toByteArray()), Charsets.UTF_8)
        val i = corpo.indexOf("name=\"attributes\""); val j = corpo.indexOf("name=\"file\"; filename=\"a.json\"")
        assertTrue(i in 0 until j)
        assertTrue(corpo.contains("\r\n\r\n{\"name\":\"a.json\"}\r\n--F\r\n"))
        assertTrue(corpo.endsWith("XYZ\r\n--F--\r\n"))
    }
}

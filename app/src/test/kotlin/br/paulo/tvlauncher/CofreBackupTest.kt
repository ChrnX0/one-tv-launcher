package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CofreBackupTest {
    private val texto = """{"versao":3,"segredos":{"unifi":"abc"},"acento":"ção"}"""

    @Test fun abreComASenhaCerta() {
        val cofre = CofreBackup.trancar(texto, "senha-boa-123")
        assertTrue(CofreBackup.eCifrado(cofre))
        assertFalse(cofre.contains("unifi"))          // nada em claro no arquivo
        assertEquals(texto, CofreBackup.abrir(cofre, "senha-boa-123"))
    }

    @Test(expected = CofreBackup.SenhaErrada::class) fun recusaSenhaErrada() {
        CofreBackup.abrir(CofreBackup.trancar(texto, "senha-boa-123"), "senha-ruim-123")
    }

    @Test(expected = CofreBackup.SenhaErrada::class) fun percebeArquivoAlterado() {
        val o = JSONObject(CofreBackup.trancar(texto, "senha-boa-123"))
        val d = o.getString("dados")
        o.put("dados", (if (d[0] == 'A') "B" else "A") + d.substring(1))
        CofreBackup.abrir(o.toString(), "senha-boa-123")
    }

    @Test(expected = IllegalArgumentException::class) fun recusaVoltasForaDoNormal() {
        CofreBackup.abrir(JSONObject(CofreBackup.trancar(texto, "senha-boa-123")).put("voltas", 2_000_000_000).toString(), "senha-boa-123")
    }

    @Test fun cadaBackupTemSalNovo() {
        assertNotEquals(CofreBackup.trancar(texto, "senha-boa-123"), CofreBackup.trancar(texto, "senha-boa-123"))
        assertFalse(CofreBackup.senhaValida("curta"))
        assertFalse(CofreBackup.eCifrado("""{"versao":2}"""))
    }
}

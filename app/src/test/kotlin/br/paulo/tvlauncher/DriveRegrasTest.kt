package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras do backup no Google Drive (DriveRegras.kt). */
class DriveRegrasTest {
    private val R = DriveRegras

    @Test fun escopoDoDriveTemQueVirMarcado() {
        assertTrue(R.escopoConcedido("openid https://www.googleapis.com/auth/userinfo.email ${R.ESCOPO}"))
        assertFalse(R.escopoConcedido("openid https://www.googleapis.com/auth/userinfo.email"))
        assertFalse(R.escopoConcedido("${R.ESCOPO}.extra"))   // só o escopo exato
        assertFalse(R.escopoConcedido(null))
        assertFalse(R.escopoConcedido(""))
    }

    @Test fun loginPedeEmailPerfilEDriveFile() {
        assertEquals(listOf("email", "profile", "https://www.googleapis.com/auth/drive.file"), R.ESCOPOS_LOGIN.split(' '))
    }

    @Test fun buscaEscapaAspas() {
        assertEquals("'a\\'b'", R.aspas("a'b"))
        assertEquals("'a\\\\b'", R.aspas("a\\b"))
        assertEquals("name = 'OneTV-backup' and mimeType = 'application/vnd.google-apps.folder' and trashed = false", R.consultaPasta())
        assertEquals("'abc123DEF_-x' in parents and trashed = false", R.consultaCopias("abc123DEF_-x"))
    }

    @Test fun idDoDriveSoLetrasNumerosHifenSublinhado() {
        assertTrue(R.idValido("1AbC_dEf-1234567890"))
        assertFalse(R.idValido("curto"))
        assertFalse(R.idValido("1AbCdEf1234/../x"))
        assertFalse(R.idValido("1AbCdEf1234?alt=media"))
        assertFalse(R.idValido(null))
    }

    private fun lista(vararg f: Triple<String, String, String>) = JSONObject().put("files", org.json.JSONArray().apply {
        for ((id, nome, mod) in f) put(JSONObject().put("id", id).put("name", nome).put("size", "2048").put("modifiedTime", mod))
    }).toString()

    @Test fun leSoAsNossasCopiasDaMaisNovaParaAMaisVelha() {
        val c = R.copias(lista(
            Triple("id0000000001", "onetv-backup-20261110.json", "2026-11-10T03:30:00.000Z"),
            Triple("id0000000002", "foto-de-ferias.jpg", "2026-11-11T03:30:00.000Z"),
            Triple("id0000000003", "onetv-backup-20261112.json", "2026-11-12T03:30:00.000Z"),
            Triple("ruim/../id", "onetv-backup-20261113.json", "2026-11-13T03:30:00.000Z"),
        ))
        assertEquals(listOf("id0000000003", "id0000000001"), c.map { it.id })
        assertEquals(2048L, c[0].tamanho)
        assertTrue(R.copias("{}").isEmpty())
    }

    @Test fun ficamAsSeteMaisNovasEUmaPorDia() {
        val dias = (1..9).map { d -> Triple("id00000000%02d".format(d), "onetv-backup-202611%02d.json".format(d), "2026-11-%02dT03:30:00.000Z".format(d)) }
        // "Fazer agora" duas vezes no dia 9: a de 10:00 é a mais nova; a das 03:30 sai.
        val repetida = Triple("id0000000099", "onetv-backup-20261109.json", "2026-11-09T10:00:00.000Z")
        val apagar = R.quaisApagar(R.copias(lista(*(dias + repetida).toTypedArray())))
        assertEquals(setOf("id0000000009", "id0000000002", "id0000000001"), apagar.toSet())
        assertTrue(R.quaisApagar(R.copias(lista(*dias.take(7).toTypedArray()))).isEmpty())
    }

    @Test fun corpoMultipartTemMetadadosEConteudo() {
        val corpo = String(R.corpoMultipart("FRONT", "onetv-backup-20261113.json", "pasta123456", "{\"v\":1}".toByteArray()), Charsets.UTF_8)
        assertTrue(corpo.startsWith("--FRONT\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
        val meta = JSONObject(corpo.substringAfter("\r\n\r\n").substringBefore("\r\n"))
        assertEquals("onetv-backup-20261113.json", meta.getString("name"))
        assertEquals("pasta123456", meta.getJSONArray("parents").getString(0))
        assertTrue(corpo.contains("\r\n\r\n{\"v\":1}\r\n--FRONT--\r\n"))
    }

    @Test fun renovacaoSabeQuandoPrecisaEntrarDeNovo() {
        assertEquals(DriveRegras.Renovacao.OK, R.renovacao(JSONObject().put("access_token", "x").put("expires_in", 3599)))
        assertEquals(DriveRegras.Renovacao.ENTRAR_DE_NOVO, R.renovacao(JSONObject().put("error", "invalid_grant")))
        assertEquals(DriveRegras.Renovacao.TENTAR_DEPOIS, R.renovacao(JSONObject().put("error", "internal_failure")))
        assertEquals(DriveRegras.Renovacao.TENTAR_DEPOIS, R.renovacao(JSONObject()))
    }
}

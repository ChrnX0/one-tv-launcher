package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EnsaioRestauracaoTest {
    private val pacote = "br.paulo.tvlauncher"
    private val permitidos = setOf("player/favoritos.json", "tunel/tailscaled.state")
    private val b64 = Base64.getEncoder()

    private fun backup(versao: Int = 3, pac: String = pacote, arquivos: JSONObject = JSONObject()
        .put("player/favoritos.json", b64.encodeToString("[1,2]".toByteArray()))) =
        JSONObject().put("versao", versao).put("pacote", pac)
            .put("preferencias", JSONObject().put("grade", JSONObject().put("a", 1)).put("player", JSONObject()))
            .put("arquivos", arquivos).toString()

    private fun conferir(t: String) = EnsaioRestauracao.conferir(t, pacote, permitidos, 3 * 1024 * 1024)

    @Test fun backupBomPassa() {
        val r = conferir(backup())
        assertTrue(r.ok); assertEquals(2, r.preferencias); assertEquals(1, r.arquivos)
    }

    @Test fun recusaVersaoPacoteEArquivoEstranho() {
        assertFalse(conferir(backup(versao = 2)).ok)
        assertFalse(conferir(backup(pac = "outro.app")).ok)
        assertFalse(conferir(backup(arquivos = JSONObject().put("../etc/passwd", "AAAA"))).ok)
        assertFalse(conferir(backup(arquivos = JSONObject().put("player/favoritos.json", "não é base64!"))).ok)
        assertFalse(conferir("{quebrado").ok)
        assertFalse(conferir(JSONObject().put("versao", 3).put("pacote", pacote).put("preferencias", JSONObject()).toString()).ok)
    }

    @Test fun recusaGrandeDemais() {
        assertFalse(EnsaioRestauracao.conferir(backup(), pacote, permitidos, 10).ok)
    }

    @Test fun ensaioComCofreDeVerdade() {
        val cofre = CofreBackup.trancar(backup(), "senha-boa-123")
        assertTrue(EnsaioRestauracao.ensaiar(cofre, "senha-boa-123", pacote, permitidos, 3 * 1024 * 1024).ok)
        val errada = EnsaioRestauracao.ensaiar(cofre, "senha-ruim-123", pacote, permitidos, 3 * 1024 * 1024)
        assertFalse(errada.ok); assertEquals("não abre com a senha guardada", errada.motivo)
        assertFalse(EnsaioRestauracao.ensaiar("{}", "senha-boa-123", pacote, permitidos, 3 * 1024 * 1024).ok)
    }
}

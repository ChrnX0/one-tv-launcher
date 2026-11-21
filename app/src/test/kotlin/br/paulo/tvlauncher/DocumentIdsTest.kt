package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdsTest {
    @Test fun leEFormata() {
        val id = DocumentIds.ler("local:interna/Download/roms")!!
        assertEquals(DocumentIds.LOCAL, id.tipo)
        assertEquals("interna", id.chave)
        assertEquals("Download/roms", id.caminho)
        assertEquals("local:interna/Download/roms", id.toString())
        assertEquals("local:8991-1F25/", DocumentIds.ler("local:8991-1F25/").toString())
        assertEquals("", DocumentIds.ler("net:a1b2")!!.caminho)
    }

    @Test fun recusaMalformadosEFugas() {
        for (ruim in listOf(null, "", "x:interna/a", "local:/a", "local:interna/../etc", "local:interna/a/../../b",
            "local:interna//a", ":a/b", "local:interna/a\\b", "local:interna/./a"))
            assertNull(ruim, DocumentIds.ler(ruim))
    }

    @Test fun paiFilhoEDentro() {
        val raiz = DocumentIds.raiz(DocumentIds.LOCAL, "8991-1F25")
        val games = raiz.filho("Games").filho("Retro-Arch")
        assertEquals("Games/Retro-Arch", games.caminho)
        assertEquals("Games", games.pai!!.caminho)
        assertEquals(raiz, games.pai!!.pai)
        assertNull(raiz.pai)
        assertTrue(DocumentIds.dentro(raiz, games))
        assertTrue(DocumentIds.dentro(games, games))
        assertTrue(DocumentIds.dentro(games, games.filho("snes.sfc")))
        // "Games/Retro-Arch2" não está dentro de "Games/Retro-Arch" (prefixo de texto não basta).
        assertFalse(DocumentIds.dentro(games, raiz.filho("Games").filho("Retro-Arch2")))
        assertFalse(DocumentIds.dentro(games, DocumentIds.ler("local:interna/Games/Retro-Arch")!!))
        assertFalse(DocumentIds.dentro(games, DocumentIds.ler("net:8991-1F25/Games/Retro-Arch")!!))
    }

    @Test fun pastaAndroidDaMemoriaFicaDeFora() {
        assertTrue(DocumentIds.protegido(DocumentIds.ler("local:interna/Android/data/x")!!))
        assertTrue(DocumentIds.protegido(DocumentIds.ler("local:interna/android")!!))
        assertFalse(DocumentIds.protegido(DocumentIds.ler("local:8991-1F25/Android")!!))
        assertFalse(DocumentIds.protegido(DocumentIds.ler("local:interna/Download")!!))
    }

    @Test fun nomes() {
        assertTrue(DocumentIds.nomeValido("jogo.srm"))
        assertFalse(DocumentIds.nomeValido("a/b"))
        assertFalse(DocumentIds.nomeValido(".."))
        assertFalse(DocumentIds.nomeValido("  "))
        assertEquals("foto.jpg", DocumentIds.nomeLivre("foto.jpg", setOf("outra.jpg")))
        assertEquals("foto (1).jpg", DocumentIds.nomeLivre("foto.jpg", setOf("foto.jpg")))
        assertEquals("foto (2).jpg", DocumentIds.nomeLivre("foto.jpg", setOf("foto.jpg", "foto (1).jpg")))
        assertEquals("roms (1)", DocumentIds.nomeLivre("roms", setOf("roms")))
        assertEquals(".bashrc (1)", DocumentIds.nomeLivre(".bashrc", setOf(".bashrc")))
    }

    @Test fun tipos() {
        assertTrue(DocumentIds.tipoServe("application/octet-stream", listOf("*/*")))
        assertTrue(DocumentIds.tipoServe("image/png", emptyList()))
        assertTrue(DocumentIds.tipoServe("video/mp4", listOf("image/*", "video/*")))
        assertFalse(DocumentIds.tipoServe("application/octet-stream", listOf("image/*")))
        assertTrue(DocumentIds.tipoServe("application/zip", listOf("APPLICATION/ZIP")))
    }

    @Test fun modos() {
        assertEquals(DocumentIds.Modo.PASTA, DocumentIds.modo("android.intent.action.OPEN_DOCUMENT_TREE"))
        assertEquals(DocumentIds.Modo.ABRIR, DocumentIds.modo("android.intent.action.GET_CONTENT"))
        assertEquals(DocumentIds.Modo.CRIAR, DocumentIds.modo("android.intent.action.CREATE_DOCUMENT"))
        assertNull(DocumentIds.modo("android.intent.action.VIEW"))
    }
}

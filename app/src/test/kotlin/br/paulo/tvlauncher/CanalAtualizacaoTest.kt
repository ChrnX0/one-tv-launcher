package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanalAtualizacaoTest {
    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,"assets":[]}"""

    @Test fun canalNormalUsaLatest() {
        assertTrue(CanalAtualizacao.url("a/b", false).endsWith("/repos/a/b/releases/latest"))
        assertTrue(CanalAtualizacao.url("a/b", true).contains("/repos/a/b/releases?per_page="))
    }

    @Test fun objetoDoLatestPassaDireto() {
        assertEquals("v10", CanalAtualizacao.escolher(r("v10"))!!.getString("tag_name"))
    }

    @Test fun listaFicaComOMaiorIncluindoPreLancamento() {
        val j = "[${r("v100")},${r("v300", pre = true)},${r("v200")}]"
        assertEquals("v300", CanalAtualizacao.escolher(j)!!.getString("tag_name"))
    }

    @Test fun ignoraRascunhoETagForaDoFormato() {
        val j = "[${r("v900", draft = true)},${r("v1.0")},${r("v120")},${r("latest")}]"
        assertEquals("v120", CanalAtualizacao.escolher(j)!!.getString("tag_name"))
    }

    @Test fun listaVaziaOuSemNenhumValidoDaNull() {
        assertNull(CanalAtualizacao.escolher("[]"))
        assertNull(CanalAtualizacao.escolher("[${r("x1")}]"))
    }

    @Test fun codigoDaTag() {
        assertEquals(456308L, CanalAtualizacao.codigo("v456308"))
        assertNull(CanalAtualizacao.codigo("456308"))
        assertNull(CanalAtualizacao.codigo("v1.0"))
        assertNull(CanalAtualizacao.codigo(null))
    }
}

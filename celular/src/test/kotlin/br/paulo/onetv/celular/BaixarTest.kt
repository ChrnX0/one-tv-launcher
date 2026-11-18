package br.paulo.onetv.celular

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BaixarTest {
    private val tabela = mapOf("apk" to "application/vnd.android.package-archive", "json" to "application/json", "opml" to null)
    private fun tipos(vararg a: String) = Baixar.tiposMime(arrayOf(*a)) { tabela[it] }

    @Test fun semAcceptAbreTudo() {
        assertArrayEquals(arrayOf("*/*"), Baixar.tiposMime(null) { null })
        assertArrayEquals(arrayOf("*/*"), tipos(""))
    }

    @Test fun mimeEExtensaoConhecida() {
        assertArrayEquals(arrayOf("image/jpeg", "image/png"), tipos("image/jpeg,image/png"))
        assertArrayEquals(arrayOf("application/json"), tipos(".json,application/json"))
    }

    @Test fun extensaoSemTipoAbreTudo() {
        // ".apks"/".xapk" não têm tipo: filtrar sumiria com eles do seletor
        assertArrayEquals(arrayOf("*/*"), tipos(".apk,.apks,.xapk,application/vnd.android.package-archive"))
        assertArrayEquals(arrayOf("*/*"), tipos(".opml,.xml,text/xml"))
    }

    @Test fun nomeSeguro() {
        assertEquals("passwd", Baixar.nomeSeguro("../../etc/passwd"))
        assertEquals("a.txt", Baixar.nomeSeguro("C:\\x\\a.txt"))
        assertEquals("arquivo", Baixar.nomeSeguro(""))
        assertEquals("arquivo", Baixar.nomeSeguro(null))
        assertEquals("env", Baixar.nomeSeguro(".env"))
        assertEquals("ab.json", Baixar.nomeSeguro("a\u0000b.json"))
        assertEquals("backup.json", Baixar.nomeSeguro("backup.json"))
    }

    @Test fun jsDoBlobEscapaAspas() {
        val js = Baixar.jsDoBlob("blob:https://x/1", "a\"b'c.json", null)
        assert("\"a\\\"b'c.json\"" in js)
    }
}

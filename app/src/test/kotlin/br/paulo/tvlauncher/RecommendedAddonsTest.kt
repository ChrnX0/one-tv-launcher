package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedAddonsTest {
    @Test fun listaEmbutidaPassaNasProprias() {
        val l = RecommendedAddons.BUILT_IN
        assertTrue(l.isNotEmpty() && l.size <= RecommendedAddons.MAX)
        assertEquals(l.size, l.map { it.id }.toSet().size)
        l.forEach { e ->
            assertTrue(e.url, e.url.startsWith("https://") && e.url.endsWith("/manifest.json"))
            assertTrue(e.id, RecommendedAddons.name(e.id) != null && RecommendedAddons.textKey(e.id) != null)
            assertTrue(e.url, AddonsProtocolo.base(e.url) != null)
        }
        // A lista do servidor tem o mesmo formato: o que a TV guarda volta igual.
        val json = org.json.JSONObject().put("addons", org.json.JSONArray().apply {
            l.forEach { put(org.json.JSONObject().put("id", it.id).put("url", it.url).put("kind", it.kind)) }
        }).toString()
        assertEquals(l, RecommendedAddons.parse(json))
    }

    @Test fun respostaDoServidorFiltrada() {
        val r = RecommendedAddons.parse("""{"addons":[
            {"id":"a","url":"https://x.dev/manifest.json","kind":"catalog"},
            {"id":"a","url":"https://y.dev/manifest.json"},
            {"id":"b","url":"http://x.dev/manifest.json"},
            {"id":"c","url":"https://x.dev/torrent/manifest.json"},
            {"id":"d","url":"https://x.dev/manifest.json","kind":"p2p"},
            {"id":"e","url":"https://x.dev/catalog.json"},
            {"id":"f","url":"https://z.dev/manifest.json"}
        ]}""")
        assertEquals(listOf("a", "f"), r!!.map { it.id })
        assertEquals("catalog", r[1].kind)
    }

    @Test fun respostaEstragadaFicaComALista() {
        assertNull(RecommendedAddons.parse(null))
        assertNull(RecommendedAddons.parse("<html>"))
        assertNull(RecommendedAddons.parse("""{"addons":[]}"""))
    }

    @Test fun escondeOQueJaEstaInstalado() {
        val l = RecommendedAddons.BUILT_IN
        val r = RecommendedAddons.toShow(l, setOf(l[0].id), setOf(AddonsProtocolo.base(l[1].url)!!))
        assertEquals(l.drop(2), r)
    }
}

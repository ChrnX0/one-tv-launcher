package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.screensaver.WallpaperPluginContract as W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugins de fundo: o pedido e a resposta no formato de Parcel que os plugins existentes (AIDL + @Parcelize) esperam,
 * e a limpeza do que eles mandam.
 */
class WallpaperPluginContractTest {
    /** Parcel falso: uma fila de valores, na ordem. */
    private class Fila : W.Saida, W.Entrada {
        val v = ArrayList<Any?>()
        var i = 0
        override fun int(v: Int) { this.v += v }
        override fun string(v: String?) { this.v += v }
        override fun int(): Int = v[i++] as Int
        override fun string(): String? = v[i++] as String?
    }

    private fun item(f: Fila, uri: String?, type: Int = W.IMAGE, mode: Int = W.MODE_CROP, title: String? = null,
                     source: String? = null, author: String? = null, action: String? = null) {
        f.int(1); f.string(uri); f.int(type); f.int(mode); f.string(title); f.string(source); f.string(author); f.string(action)
    }

    @Test fun eventoDeTempoEhOMarcadorMaisOTipo() {
        val f = Fila(); W.escreverEvento(W.Event(W.EV_TIME_ELAPSED), f)
        assertEquals(listOf<Any?>(1, 1), f.v)
    }

    @Test fun eventoDeFocoNaOrdemDoConstrutor() {
        val f = Fila(); W.escreverEvento(W.Event(W.EV_CARD_FOCUSED, "Netflix", "com.netflix.ninja", 7, 9), f)
        assertEquals(listOf<Any?>(1, 4, 7, 9, "Netflix", "com.netflix.ninja"), f.v)
    }

    @Test fun eventoOciosoBooleanoComoInteiro() {
        val f = Fila(); W.escreverEvento(W.Event(W.EV_LAUNCHER_IDLE_MODE_CHANGED, idle = true), f)
        assertEquals(listOf<Any?>(1, 16, 1), f.v)
    }

    @Test fun tipoDesconhecidoViraTempo() {
        val f = Fila(); W.escreverEvento(W.Event(W.EV_NOW_PLAYING_CHANGED), f)
        assertEquals(listOf<Any?>(1, 1), f.v)
    }

    @Test fun lerRespostaPulaNulosELimpa() {
        val f = Fila()
        f.int(4)
        item(f, "https://ex.com/a.jpg", title = "  Praia\n do  Sul ", author = "Ana")
        f.int(0)                                             // item nulo na lista
        item(f, "file:///sdcard/x.jpg")                      // esquema recusado
        item(f, "https://ex.com/b.json", type = W.LOTTIE)    // Lottie não desenhamos
        val l = W.lerResposta(f) { true }
        assertEquals(1, l.size)
        assertEquals("Praia do Sul", l[0].title)
        assertEquals("Ana", l[0].author)
        assertNull(l[0].actionUri)
    }

    @Test fun listaNulaOuVazia() {
        assertTrue(W.lerResposta(Fila().apply { int(-1) }) { true }.isEmpty())
        assertTrue(W.lerResposta(Fila().apply { int(0) }) { true }.isEmpty())
    }

    @Test fun limiteDeItens() {
        val f = Fila(); f.int(60); repeat(60) { item(f, "https://ex.com/$it.jpg") }
        assertEquals(W.MAX_ITEMS, W.lerResposta(f) { true }.size)
    }

    @Test fun esquemas() {
        assertTrue(W.esquemaAceito("https://a/b"))
        assertTrue(W.esquemaAceito("content://plugin.fotos/1"))
        assertTrue(W.esquemaAceito("android.resource://pkg/drawable/x"))
        assertFalse(W.esquemaAceito("file:///data/data/x"))
        assertFalse(W.esquemaAceito("javascript:alert(1)"))
        assertFalse(W.esquemaAceito("sem-esquema"))
    }

    @Test fun corOpacaSempre() {
        assertEquals(0xFF112233.toInt(), W.cor("#112233"))
        assertEquals(0xFF112233.toInt(), W.cor("#00112233"))
        assertNull(W.cor("azul"))
        val c = W.limpar(W.Item("#FF0000", W.COLOR), { true })
        assertEquals("#FF0000", c?.uri)
    }

    @Test fun modoInvalidoViraCorte() {
        assertEquals(W.MODE_CROP, W.limpar(W.Item("https://a/b.jpg", W.IMAGE, 99), { true })?.displayMode)
    }

    @Test fun cacheEntreLimites() {
        assertEquals(W.CACHE_PADRAO_MS, W.cacheMs(null))
        assertEquals(W.CACHE_PADRAO_MS, W.cacheMs(0))
        assertEquals(W.CACHE_MIN_MS, W.cacheMs(1000))
        assertEquals(W.CACHE_MAX_MS, W.cacheMs(Long.MAX_VALUE))
        assertTrue(W.vencido(100, 50, 1000))   // relógio voltou
        assertFalse(W.vencido(100, 500, 1000))
        assertTrue(W.vencido(100, 1100, 1000))
    }

    @Test fun proximoNuncaRepeteOAtual() {
        for (atual in 0 until 5) for (s in 0 until 4) {
            val p = W.proximo(5, atual) { s }
            assertTrue(p in 0 until 5 && p != atual)
        }
        assertEquals(-1, W.proximo(0, -1) { 0 })
        assertEquals(0, W.proximo(1, 0) { 0 })
    }

    @Test fun classeDeConfiguracao() {
        assertEquals("a.b.Settings", W.classeConfig("a.b", ".Settings"))
        assertEquals("x.y.Z", W.classeConfig("a.b", "x.y.Z"))
        assertNull(W.classeConfig("a.b", "x; rm -rf"))
        assertNull(W.classeConfig("a.b", null))
    }

    @Test fun creditoEUpdateMode() {
        assertEquals("Praia  ·  © Ana", W.credito(W.Item("https://a", title = "Praia", author = "Ana")))
        assertNull(W.credito(W.Item("https://a")))
        assertTrue(W.quer(17, W.EV_LAUNCHER_IDLE_MODE_CHANGED))
        assertFalse(W.quer(17, W.EV_CARD_FOCUSED))
    }

    @Test fun listaVaziaNaoPedeEmSeguida() {
        assertFalse(W.precisaPedir(0, 0, W.CACHE_PADRAO_MS))
        assertFalse(W.precisaPedir(0, W.VAZIO_MS - 1, W.CACHE_PADRAO_MS))
        assertTrue(W.precisaPedir(0, W.VAZIO_MS + 1, W.CACHE_PADRAO_MS))
        assertEquals(W.VAZIO_MS, W.esperaMs(0, 0, W.CACHE_PADRAO_MS))
    }

    @Test fun umItemAgendaNoVencimentoDoCache() {
        val cache = 2 * 60 * 60_000L
        assertEquals(cache - 1000, W.esperaMs(1, 1000, cache))
        assertEquals(W.ESPERA_MIN_MS, W.esperaMs(1, cache + 5, cache))   // já venceu: logo, mas não em laço
        assertTrue(W.precisaPedir(1, cache + 1, cache))
        assertFalse(W.precisaPedir(1, cache - 1, cache))
        assertEquals(W.TROCA_MS, W.esperaMs(5, 0, cache))
        assertTrue(W.precisaPedir(3, -5, cache))   // relógio voltou
    }
}

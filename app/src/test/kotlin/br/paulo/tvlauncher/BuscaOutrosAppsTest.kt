package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaOutrosAppsTest {
    private val todos = setOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom")

    @Test fun constantesDoAndroid() {
        assertEquals("android.intent.action.SEARCH", BuscaOutrosApps.ACAO)   // Intent.ACTION_SEARCH
        assertEquals("query", BuscaOutrosApps.EXTRA_TERMO)                   // SearchManager.QUERY
    }

    @Test fun soOsInstaladosNaOrdemDaTabela() {
        val r = BuscaOutrosApps.montar("  casa  do   dragão ", setOf("com.amazon.amazonvideo.livingroom", "com.netflix.ninja", "x.y"))
        assertEquals(listOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom"), r.map { it.destino.pacote })
        assertTrue(r.all { it.termo == "casa do dragão" && it.acao == BuscaOutrosApps.ACAO && it.extra == "query" })
    }

    @Test fun todosInstalados() {
        assertEquals(listOf("youtube", "netflix", "prime"), BuscaOutrosApps.montar("novela", todos).map { it.destino.chave })
    }

    @Test fun youtubeEhOPacoteDeTv() {
        assertTrue(BuscaOutrosApps.montar("abc", setOf("com.google.android.youtube")).isEmpty())
    }

    @Test fun termoCurtoOuVazioNaoMontaNada() {
        assertTrue(BuscaOutrosApps.montar("a", todos).isEmpty())
        assertTrue(BuscaOutrosApps.montar("   ", todos).isEmpty())
        assertNull(BuscaOutrosApps.limpar(" x "))
    }

    @Test fun termoLongoCortado() {
        assertEquals(BuscaOutrosApps.MAX_TERMO, BuscaOutrosApps.limpar("a".repeat(500))!!.length)
    }

    @Test fun nadaInstalado() {
        assertTrue(BuscaOutrosApps.montar("filme", emptySet()).isEmpty())
    }
}

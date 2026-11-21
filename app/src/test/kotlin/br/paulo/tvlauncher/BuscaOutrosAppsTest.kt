package br.paulo.tvlauncher

import br.paulo.tvlauncher.search.BuscaOutrosApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaOutrosAppsTest {
    private val todos = setOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom")

    @Test fun constantesDoAndroid() {
        assertEquals("android.intent.action.SEARCH", BuscaOutrosApps.ACAO)   // Intent.ACTION_SEARCH
        assertEquals("android.search.action.GLOBAL_SEARCH", BuscaOutrosApps.ACAO_GLOBAL)   // SearchManager.INTENT_ACTION_GLOBAL_SEARCH
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

    @Test fun googleTvPrimeiroEPelaBuscaGlobal() {
        val r = BuscaOutrosApps.montar("duna", todos + BuscaOutrosApps.PACOTE_GOOGLE_TV)
        assertEquals(listOf("googletv", "youtube", "netflix", "prime"), r.map { it.destino.chave })
        assertEquals(BuscaOutrosApps.ACAO_GLOBAL, r[0].acao)
        assertTrue(r.drop(1).all { it.acao == BuscaOutrosApps.ACAO })
    }

    @Test fun ondeAssistirComNomeEAno() {
        val p = BuscaOutrosApps.ondeAssistir("  Duna ", 2021, setOf(BuscaOutrosApps.PACOTE_GOOGLE_TV))!!
        assertEquals("Duna 2021", p.termo); assertEquals("com.google.android.katniss", p.destino.pacote)
        assertEquals(BuscaOutrosApps.ACAO_GLOBAL, p.acao); assertEquals("query", p.extra)
        assertEquals("Duna", BuscaOutrosApps.ondeAssistir("Duna", null, setOf(BuscaOutrosApps.PACOTE_GOOGLE_TV))!!.termo)
        assertNull(BuscaOutrosApps.ondeAssistir("Duna", 2021, todos))   // sem o Google TV: sem botão
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

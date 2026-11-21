package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.OndeAssistir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OndeAssistirTest {
    @Test fun provedoresDoPaisSemAluguel() {
        val j = """{"results":{"BR":{"flatrate":[{"provider_name":"Prime Video","display_priority":5},{"provider_name":"Netflix","display_priority":1}],
            "ads":[{"provider_name":"Netflix basic with Ads","display_priority":2}],"rent":[{"provider_name":"Apple TV","display_priority":0}]},
            "US":{"flatrate":[{"provider_name":"Hulu"}]}}}"""
        assertEquals(listOf("Netflix", "Prime Video"), OndeAssistir.lerProvedores(j, "BR"))
        assertEquals(emptyList<String>(), OndeAssistir.lerProvedores(j, "PT"))
        assertEquals(emptyList<String>(), OndeAssistir.lerProvedores("lixo", "BR"))
    }

    @Test fun pacotesConhecidos() {
        assertEquals("com.netflix.ninja", OndeAssistir.pacote("Netflix"))
        assertEquals("com.amazon.amazonvideo.livingroom", OndeAssistir.pacote(OndeAssistir.limparNome("Amazon Prime Video")))
        assertNull(OndeAssistir.pacote("Globoplay"))
    }

    @Test fun canalDentroDeOutroServicoViraOServico() {
        assertEquals("Apple TV", OndeAssistir.limparNome("Apple TV Amazon Channel"))
        assertEquals("Paramount Plus", OndeAssistir.limparNome("Paramount Plus Apple TV Channel"))
        assertEquals("Netflix", OndeAssistir.limparNome("Netflix basic with Ads"))
        assertEquals("Netflix", OndeAssistir.limparNome("Netflix Standard with Ads"))
        assertEquals("Netflix", OndeAssistir.limparNome("Netflix Standard"))
        val n = """{"results":{"BR":{"flatrate":[{"provider_name":"Netflix","display_priority":1},
            {"provider_name":"MUBI","display_priority":2}],"ads":[{"provider_name":"Netflix Standard with Ads","display_priority":3}]}}}"""
        assertEquals(listOf("Netflix", "MUBI"), OndeAssistir.lerProvedores(n, "BR"))
        val j = """{"results":{"BR":{"flatrate":[{"provider_name":"Apple TV","display_priority":1},
            {"provider_name":"Apple TV Amazon Channel","display_priority":5}]}}}"""
        assertEquals(listOf("Apple TV"), OndeAssistir.lerProvedores(j, "BR"))
    }

    @Test fun buscaSoFilmesESeriesForaDaLista() {
        val j = """{"results":[{"media_type":"movie","id":1,"title":"A","release_date":"2020-01-01"},{"media_type":"person","id":2,"name":"P"},
            {"media_type":"tv","id":3,"name":"B","first_air_date":""},{"media_type":"movie","id":4,"title":"C"}]}"""
        val a = OndeAssistir.lerBusca(j)
        assertEquals(listOf(1, 3, 4), a.map { it.tmdb })
        assertEquals(2020, a[0].ano)
        assertNull(a[1].ano)
        assertEquals(listOf(3, 4), OndeAssistir.foraDaLista(a, filmes = setOf(1), series = emptySet()).map { it.tmdb })
        assertEquals(listOf(1, 4), OndeAssistir.foraDaLista(a, filmes = emptySet(), series = setOf(3)).map { it.tmdb })
    }

    @Test fun paisDaTv() {
        assertEquals("PT", OndeAssistir.pais("pt"))
        assertEquals("BR", OndeAssistir.pais(""))
        assertEquals("BR", OndeAssistir.pais(null))
    }
}

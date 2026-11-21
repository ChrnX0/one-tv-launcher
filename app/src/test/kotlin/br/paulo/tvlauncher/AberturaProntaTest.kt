package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.AberturaPronta
import br.paulo.tvlauncher.player.AberturaSerie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AberturaProntaTest {
    @Test fun url() {
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=2", AberturaPronta.url("tt0903747", 1, 2))
        assertNull(AberturaPronta.url("903747", 1, 2))      // id sem "tt": não pergunta
        assertNull(AberturaPronta.url("tt0903747", 1, 0))
    }

    @Test fun le_a_abertura_em_ms_e_em_segundos() {
        assertEquals(AberturaSerie.Marca(5_000, 62_000),
            AberturaPronta.ler("""{"intro":{"start_ms":5000,"end_ms":62000,"confidence":0.9}}"""))
        assertEquals(AberturaSerie.Marca(5_000, 62_500),
            AberturaPronta.ler("""{"intro":{"start_sec":5,"end_sec":62.5}}"""))
    }

    @Test fun nao_pula_quando_nao_deve() {
        assertNull(AberturaPronta.ler("""{"no_intro":true,"intro":{"start_ms":0,"end_ms":60000}}"""))
        assertNull(AberturaPronta.ler("""{"intro":{"start_ms":5000,"end_ms":62000,"confidence":0.4}}"""))   // pouca gente
        assertNull(AberturaPronta.ler("""{"intro":{"start_ms":62000,"end_ms":5000}}"""))                     // fim antes do início
        assertNull(AberturaPronta.ler("""{}"""))
        assertNull(AberturaPronta.ler("não é json"))
        assertNull(AberturaPronta.ler(null))
    }

    @Test fun cache_guarda_tambem_o_nao_tem() {
        val agora = 1_000_000_000L
        val m = AberturaSerie.Marca(5_000, 62_000)
        assertEquals(m to true, AberturaPronta.doCache(AberturaPronta.paraCache(m, agora), agora + 1))
        // "não tem" vale uma semana; depois pergunta de novo
        assertEquals(null to true, AberturaPronta.doCache(AberturaPronta.paraCache(null, agora), agora + 1))
        assertNull(AberturaPronta.doCache(AberturaPronta.paraCache(null, agora), agora + AberturaPronta.REPERGUNTAR_MS + 1))
        assertNull(AberturaPronta.doCache(null, agora))
        assertNull(AberturaPronta.doCache("lixo", agora))
    }
}

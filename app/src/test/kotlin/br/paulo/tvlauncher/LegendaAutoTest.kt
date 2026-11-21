package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.LegendaExterna
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegendaAutoTest {
    private val bloco = LegendaExterna.BLOCO_HASH

    @Test fun hashDeZerosEhOTamanho() {
        val z = ByteArray(bloco)
        assertEquals("%016x".format(200_000L), LegendaExterna.hash(200_000L, z, z))
    }

    @Test fun hashSomaPalavrasLittleEndianComEstouro() {
        val a = ByteArray(bloco).also { it[0] = 1 }              // primeira palavra = 1
        val b = ByteArray(bloco).also { for (i in 0 until 8) it[i] = 0xFF.toByte() }   // -1 (estoura)
        assertEquals("%016x".format(100_000L + 1 - 1), LegendaExterna.hash(100_000L, a, b))
        val c = ByteArray(bloco).also { it[1] = 1 }              // 256
        assertEquals("%016x".format(100_000L + 256), LegendaExterna.hash(100_000L, c, ByteArray(bloco)))
    }

    @Test fun hashPedeArquivoMaiorQueUmBloco() {
        assertNull(LegendaExterna.hash(1000, ByteArray(bloco), ByteArray(bloco)))
        assertNull(LegendaExterna.hash(200_000, ByteArray(10), ByteArray(bloco)))
    }

    @Test fun tamanhoDoContentRange() {
        assertEquals(734003200L, LegendaExterna.tamanhoDoRange("bytes 0-65535/734003200"))
        assertNull(LegendaExterna.tamanhoDoRange("bytes 0-65535/*"))
        assertNull(LegendaExterna.tamanhoDoRange(null))
    }

    @Test fun buscaComHashEOrdemPeloHash() {
        val u = LegendaExterna.urlBusca(LegendaExterna.Busca("Filme", 2020, hash = "8e245d9679d31e12"), "pt")
        assertTrue(u.contains("moviehash=8e245d9679d31e12"))
        assertFalse(LegendaExterna.urlBusca(LegendaExterna.Busca("Filme", hash = "x; rm"), "pt").contains("moviehash"))
        val j = """{"data":[{"attributes":{"language":"pt-br","download_count":900,"files":[{"file_id":1}]}},
            {"attributes":{"language":"pt-pt","download_count":3,"moviehash_match":true,"files":[{"file_id":2}]}}]}"""
        val l = LegendaExterna.lerBusca(j, "pt")
        assertEquals(2L, l.first().arquivo)
        assertEquals(2L, LegendaExterna.escolherAuto(l)?.arquivo)
        assertNull(LegendaExterna.escolherAuto(emptyList()))
    }

    @Test fun faltaLegendaSoComAudioDeFora() {
        val en = Idioma.Faixa("en"); val pt = Idioma.Faixa("pt")
        assertTrue(Idioma.faltaLegenda(en, emptyList(), "pt"))
        assertFalse(Idioma.faltaLegenda(pt, emptyList(), "pt"))                    // dublado
        assertFalse(Idioma.faltaLegenda(en, listOf(Idioma.Faixa("pt-BR")), "pt"))  // já tem legenda
        assertTrue(Idioma.faltaLegenda(en, listOf(Idioma.Faixa("pt", forcada = true)), "pt"))   // só a forçada
        assertFalse(Idioma.faltaLegenda(null, emptyList(), "pt"))                   // sem áudio identificado
        // Sem idioma no áudio (Duna dublada da Sala, 17/11/2026): só a marca da lista decide.
        val und = Idioma.Faixa("und"); val nada = Idioma.Faixa(null)
        assertFalse(Idioma.faltaLegenda(und, emptyList(), "pt"))
        assertFalse(Idioma.faltaLegenda(nada, emptyList(), "pt", Idioma.Versao.DUBLADA))
        assertTrue(Idioma.faltaLegenda(und, emptyList(), "pt", Idioma.Versao.LEGENDADA))
        assertTrue(Idioma.faltaLegenda(en, emptyList(), "pt", Idioma.Versao.DUBLADA))   // a faixa diz inglês: vale a faixa
    }
}

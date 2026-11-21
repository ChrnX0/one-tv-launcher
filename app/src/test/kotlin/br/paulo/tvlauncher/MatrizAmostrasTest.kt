package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.MatrizAmostras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 6.1: matriz "toca tudo" (leitura, veredito, relatório). As amostras em si: scripts/test_amostras.py. */
class MatrizAmostrasTest {
    private val json = """{"versao":1,"amostras":[
      {"id":"v01","arquivo":"v01_h264_high_1080p_aac.mp4","categoria":"video","descricao":"H.264","video":true,"legendas":0,"legenda_externa":null,"licenca":"CC0"},
      {"id":"l06","arquivo":"l06_externa.mp4","categoria":"legenda","descricao":"externa","video":true,"legendas":1,"legenda_externa":"l06_externa.srt","licenca":"CC0"},
      {"id":"t01","arquivo":"t01_hls/mestre.m3u8","categoria":"stream","descricao":"HLS","video":true,"legendas":0,"legenda_externa":null,"licenca":"CC0"},
      {"id":"s01","arquivo":"s01_musica.mp3","categoria":"audio","descricao":"MP3","video":false,"legendas":0,"legenda_externa":null,"licenca":"CC0"},
      {"id":"x99","arquivo":"../../etc/passwd","categoria":"video","descricao":"mal","video":true,"legendas":0,"licenca":"CC0"},
      {"id":"x98","arquivo":"http://fora.exemplo/a.mp4","categoria":"video","descricao":"mal","video":true,"legendas":0,"licenca":"CC0"}
    ]}"""

    @Test fun lerRecusaCaminhoDeFora() {
        val l = MatrizAmostras.ler(json)
        assertEquals(listOf("v01", "l06", "t01", "s01"), l.map { it.id })
        assertEquals("l06_externa.srt", l[1].legendaExterna)
        assertNull(l[0].legendaExterna)
        assertFalse(l[3].video)
    }

    @Test fun enderecoSoDaBase() {
        assertEquals("http://192.168.1.20:8000/t01_hls/mestre.m3u8", MatrizAmostras.url("http://192.168.1.20:8000/", "t01_hls/mestre.m3u8"))
        assertEquals("file:///storage/ABCD/amostras/v01.mp4", MatrizAmostras.url("file:///storage/ABCD/amostras", "v01.mp4"))
        assertNull(MatrizAmostras.url("ftp://x/", "v01.mp4"))
        assertNull(MatrizAmostras.url("http://x/", "../v01.mp4"))
        assertNull(MatrizAmostras.url("http://x/", "/v01.mp4"))
    }

    @Test fun veredito() {
        val l = MatrizAmostras.ler(json)
        val bom = MatrizAmostras.Visto(pronto = true, primeiroQuadro = true, posicaoMs = 3000)
        assertTrue(MatrizAmostras.veredito(l[0], bom).tocou)
        assertEquals("sem imagem", MatrizAmostras.veredito(l[0], bom.copy(primeiroQuadro = false)).motivo)
        assertTrue("só áudio não precisa de imagem", MatrizAmostras.veredito(l[3], bom.copy(primeiroQuadro = false)).tocou)
        assertEquals("legenda não reconhecida", MatrizAmostras.veredito(l[1], bom).motivo)
        assertTrue(MatrizAmostras.veredito(l[1], bom.copy(legendasVistas = 1)).tocou)
        assertEquals("decoding_failed", MatrizAmostras.veredito(l[0], bom.copy(erro = "decoding_failed")).motivo)
        assertEquals("não andou", MatrizAmostras.veredito(l[0], bom.copy(posicaoMs = 800)).motivo)
        assertEquals("não ficou pronta", MatrizAmostras.veredito(l[0], MatrizAmostras.Visto()).motivo)
    }

    @Test fun relatorio() {
        val l = MatrizAmostras.ler(json)
        val bom = MatrizAmostras.Visto(pronto = true, primeiroQuadro = true, posicaoMs = 3000, legendasVistas = 1)
        val r = l.mapIndexed { i, a -> MatrizAmostras.veredito(a, if (i == 2) bom.copy(erro = "io_bad_http_status") else bom) }
        val t = MatrizAmostras.texto(r, "Teste X, Android 12")
        assertTrue(t, t.startsWith("toca 3 de 4 neste aparelho (Teste X, Android 12)"))
        assertTrue(t, t.contains("t01 HLS: io_bad_http_status"))
        assertEquals(3, MatrizAmostras.json(r, "x").getInt("tocou"))
    }
}

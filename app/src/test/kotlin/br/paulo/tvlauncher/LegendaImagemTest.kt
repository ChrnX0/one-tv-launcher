package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.LegendaImagem.ANCORA_FIM
import br.paulo.tvlauncher.player.LegendaImagem.ANCORA_INICIO
import br.paulo.tvlauncher.player.LegendaImagem.ANCORA_MEIO
import br.paulo.tvlauncher.player.LegendaImagem.Lugar
import br.paulo.tvlauncher.player.LegendaImagem.SEM_VALOR
import br.paulo.tvlauncher.player.LegendaImagem.lugar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Onde a legenda em imagem (PGS, VobSub, ASS) é desenhada dentro da área do vídeo. */
class LegendaImagemTest {
    @Test fun pgsDeTelaInteiraOcupaAAreaToda() {
        // PGS de Blu-ray: imagem 1920x1080 do tamanho do quadro, ancorada no canto de cima (posição 0, linha 0, tamanho 1).
        assertEquals(Lugar(0, 0, 1920, 1080),
            lugar(0, 0, 1920, 1080, 1920, 1080, 0f, ANCORA_INICIO, 0f, ANCORA_INICIO, 1f, 1f))
    }

    @Test fun areaDoVideoComBarrasPretasDesloca() {
        // Vídeo 4:3 "Ajustar" numa tela 16:9: área 1440x1080 começando em x=240. A legenda segue a área, não a tela.
        assertEquals(Lugar(240, 0, 1440, 1080),
            lugar(240, 0, 1440, 1080, 720, 576, 0f, ANCORA_INICIO, 0f, ANCORA_INICIO, 1f, 1f))
    }

    @Test fun ancoraNoMeioENoFim() {
        // Imagem de 50% da largura, centrada (meio) e com o pé na linha 0,85 (fim); altura pela proporção da imagem (2:1).
        assertEquals(Lugar(480, 438, 960, 480),
            lugar(0, 0, 1920, 1080, 400, 200, 0.5f, ANCORA_MEIO, 0.85f, ANCORA_FIM, 0.5f, SEM_VALOR))
    }

    @Test fun camposSemValorCaemNoPadraoCentradoEmbaixo() {
        // Arquivo sem posição/linha/tamanho: imagem no tamanho dela, centrada, com o pé a 95% da altura.
        val l = lugar(0, 0, 1920, 1080, 800, 100, SEM_VALOR, ANCORA_INICIO, SEM_VALOR, ANCORA_INICIO, SEM_VALOR, SEM_VALOR)!!
        assertEquals(Lugar(560, 926, 800, 100), l)
    }

    @Test fun imagemMaiorQueAAreaEncolhe() {
        val l = lugar(0, 0, 1280, 720, 1920, 200, SEM_VALOR, 0, SEM_VALOR, 0, SEM_VALOR, SEM_VALOR)!!
        assertEquals(1280, l.l)
        assertEquals(133, l.a)
    }

    @Test fun semAreaOuSemImagemNaoDesenha() {
        assertNull(lugar(0, 0, 0, 0, 100, 100, 0f, 0, 0f, 0, 1f, 1f))
        assertNull(lugar(0, 0, 1920, 1080, 0, 100, 0f, 0, 0f, 0, 1f, 1f))
    }
}

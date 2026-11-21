package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.TaxaQuadros
import br.paulo.tvlauncher.player.TaxaQuadros.Modo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ideia 86: qual modo da tela pedir para a taxa de quadros do vídeo. */
class TaxaQuadrosTest {
    // Modos típicos de um Chromecast/Google TV em 1080p e 4K.
    private val modos = listOf(
        Modo(1, 1920, 1080, 60f), Modo(2, 1920, 1080, 59.94f), Modo(3, 1920, 1080, 50f), Modo(4, 1920, 1080, 30f),
        Modo(5, 1920, 1080, 29.97f), Modo(6, 1920, 1080, 25f), Modo(7, 1920, 1080, 24f), Modo(8, 1920, 1080, 23.976f),
        Modo(9, 3840, 2160, 23.976f), Modo(10, 3840, 2160, 60f),
    )
    private val em60 = modos[0]

    @Test fun filme23976VaiPara23976() = assertEquals(8, TaxaQuadros.escolher(23.976f, em60, modos)?.id)
    @Test fun filme24VaiPara24() = assertEquals(7, TaxaQuadros.escolher(24f, em60, modos)?.id)
    @Test fun europeu25PrefereOs50() = assertEquals(3, TaxaQuadros.escolher(25f, em60, modos)?.id)
    @Test fun tv2997PrefereOs5994() = assertEquals(2, TaxaQuadros.escolher(29.97f, em60, modos)?.id)
    @Test fun video60JaServeNaoTroca() = assertNull(TaxaQuadros.escolher(60f, em60, modos))
    @Test fun video30NosSessentaNaoTroca() = assertNull(TaxaQuadros.escolher(30f, em60, modos))
    @Test fun mesmaResolucaoSempre() {
        // Em 4K só há 23,976 e 60: o 24 cabe no 23,976 (0,1%), sem cair para 1080p.
        assertEquals(9, TaxaQuadros.escolher(24f, modos[9], modos)?.id)
    }
    @Test fun semModoQueSirvaNaoTroca() = assertNull(TaxaQuadros.escolher(24f, Modo(1, 1280, 720, 60f), listOf(Modo(1, 1280, 720, 60f), Modo(2, 1280, 720, 50f))))
    @Test fun taxaDesconhecidaNaoTroca() {
        assertNull(TaxaQuadros.escolher(-1f, em60, modos))
        assertNull(TaxaQuadros.escolher(Float.NaN, em60, modos))
        assertNull(TaxaQuadros.escolher(120f, em60, modos))
    }
    @Test fun jaEstaNoModoCertoNaoTroca() = assertNull(TaxaQuadros.escolher(23.976f, modos[7], modos))
}

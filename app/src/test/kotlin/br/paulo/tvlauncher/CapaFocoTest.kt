package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** A capa da HOME é ampliada 2x: o zoom da cena tem que partir dessa base (bug da capa pequena, 12/11/2026). */
class CapaFocoTest {
    @Test fun zoomRelativoABase() {
        assertEquals(2f, CapaFoco.escalaZoom(2f, 1f), 0.001f)
        assertEquals(2.2f, CapaFoco.escalaZoom(2f, 1.1f), 0.001f)
    }

    @Test fun nuncaMenorQueABase() {
        for (f in listOf(1f, 1.05f, 1.1f)) assert(CapaFoco.escalaZoom(2f, f) >= 2f)
    }
}

/** Nome do episódio sem repetir a série (passada na TV, 12/11/2026). */
class TituloEpisodioTest {
    @Test fun tiraSerieECodigo() {
        assertEquals("Episódio 7", Series.tituloCurto("Overgeared 2026", "Overgeared 2026 - S01E07 - Episódio 7"))
        assertEquals("O Primeiro Passo de Um Ferreiro", Series.tituloCurto("Overgeared 2026", "Overgeared 2026 - S01E02 - O Primeiro Passo de Um Ferreiro"))
    }
    @Test fun tituloNormalFica() = assertEquals("Piloto", Series.tituloCurto("Dom de Iludir", "Piloto"))
    @Test fun soCodigoNaoSomeTudo() = assertEquals("Overgeared 2026 - S01E07", Series.tituloCurto("Overgeared 2026", "Overgeared 2026 - S01E07"))
}

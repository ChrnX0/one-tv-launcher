package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.BackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fundo mais rico (21/11/2026): cor escurecida, pintura com pulos e padrões que não mudam a HOME de ninguém. */
class BackgroundStyleTest {
    @Test fun escurecer() {
        assertEquals(0xFF000000.toInt(), BackgroundStyle.escurecer(0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFF808080.toInt(), BackgroundStyle.escurecer(0xFFFFFFFF.toInt(), 0.5f + 0.001f))
        assertEquals(0xFF59250F.toInt(), BackgroundStyle.escurecer(0x80FF6A2B.toInt(), 0.35f))   // sempre opaca
    }

    @Test fun cor() {
        assertEquals(BackgroundStyle.escurecer(DesignTokens.ORANGE, 0.35f), BackgroundStyle.cor(BackgroundStyle.TintColor.ACCENT, DesignTokens.ORANGE))
        assertEquals(0xFF0E1A33.toInt(), BackgroundStyle.cor(BackgroundStyle.TintColor.NIGHT, DesignTokens.ORANGE))
    }

    @Test fun pinturaComPulos() {
        assertEquals(5, BackgroundStyle.indiceObra(5, 0, 28))
        assertEquals(6, BackgroundStyle.indiceObra(5, 1, 28))
        assertEquals(0, BackgroundStyle.indiceObra(27, 1, 28))
    }

    @Test fun padroesNaoMexemNaHome() {
        assertEquals(0f, BackgroundStyle.Tint.entries.first().alpha)
        assertEquals(0f, BackgroundStyle.Blur.entries.first().dp)
        assertEquals(800L, BackgroundStyle.Fade.entries[1].ms)   // a transição que já existia nos plugins
    }
}

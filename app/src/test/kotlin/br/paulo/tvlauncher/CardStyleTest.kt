package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.CardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ajuste fino (21/11/2026): cantos, cores e o contraste de cada cor de destaque. */
class CardStyleTest {
    @Test fun raios() {
        assertEquals(16, CardStyle.raio(16, 1f))
        assertEquals(4, CardStyle.raio(16, 0.25f))
        assertEquals(2, CardStyle.raio(8, 0.1f))   // nunca canto vivo de 0 dp
        assertEquals(28, CardStyle.raio(16, 1.75f))
    }

    @Test fun padraoEhOLaranjaDaMarca() {
        assertEquals(DesignTokens.ORANGE, CardStyle.Accent.entries.first().base)
        assertEquals(1f, CardStyle.Corners.entries[1].scale)
        assertEquals(1f, CardStyle.Size.entries[1].scale)
        assertEquals(0f, CardStyle.Glow.entries.first().alpha)
    }

    /** Cada destaque: ≥ 4,5:1 sobre o fundo, ≥ 7:1 no alto contraste, e a versão "sobre o foco branco" ≥ 4,5:1. */
    @Test fun contraste() {
        for (a in CardStyle.Accent.entries) {
            assertTrue("$a base", contraste(a.base, DesignTokens.NIGHT) >= 4.5)
            assertTrue("$a alto", contraste(a.high, DesignTokens.NIGHT) >= 7.0)
            assertTrue("$a sobre branco", contraste(a.onLight, DesignTokens.FOCUS) >= 4.5)
        }
    }

    /** Nunca rosa (regra do Paulo): matiz fora de 290°–345°. */
    @Test fun nuncaRosa() {
        for (a in CardStyle.Accent.entries) {
            val hsv = FloatArray(3)
            val r = (a.base shr 16 and 0xFF) / 255f; val g = (a.base shr 8 and 0xFF) / 255f; val b = (a.base and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val h = when { d == 0f -> 0f; mx == r -> 60 * (((g - b) / d) % 6); mx == g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
            hsv[0] = (h + 360) % 360
            assertTrue("$a matiz ${hsv[0]}", hsv[0] !in 290f..345f)
        }
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun contraste(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
}

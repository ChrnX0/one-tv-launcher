package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.SofyMotion
import br.paulo.tvlauncher.observer.SofyMotion.Ease
import br.paulo.tvlauncher.observer.SofyMotion.Key
import br.paulo.tvlauncher.observer.SofyMotion.Part
import br.paulo.tvlauncher.observer.SofyPolicy.Expression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dose aprovada (20/11/2026): cada fala tem o seu movimento, de até 2,5 s, e termina na cara da expressão. */
class SofyMotionTest {

    @Test fun everyExpressionHasAShortClip() = Expression.entries.forEach { e ->
        val c = SofyMotion.clip(e)
        assertEquals(e, c.expression)
        assertTrue("${e.name} dura ${c.lengthMs} ms", c.lengthMs in 1..SofyMotion.MAX_MS)
    }

    @Test fun clipEndsOnTheExpressionFace() = Expression.entries.forEach { e ->
        val c = SofyMotion.clip(e)
        val f = SofyMotion.faceAt(c, c.lengthMs)
        assertEquals(e.name, e.eyes, f.eyes)
        assertEquals(e.name, e.mouth, f.mouth)
    }

    @Test fun facesAreAscii() = Expression.entries.forEach { e ->
        SofyMotion.clip(e).faces.forEach { f -> assertTrue(e.name, (f.eyes + f.mouth).all { it.code in 0x20..0x7E }) }
    }

    @Test fun squashStaysWithinFivePercent() = Expression.entries.forEach { e ->
        val c = SofyMotion.clip(e)
        for (ms in 0..c.lengthMs step 20) {
            val k = SofyMotion.poseAt(c, Part.BODY, ms)
            assertTrue("${e.name} sx=${k.sx} em $ms", k.sx in 0.949f..1.051f)
            assertTrue("${e.name} sy=${k.sy} em $ms", k.sy in 0.949f..1.051f)
        }
    }

    @Test fun nothingMovesAfterTheClip() = Expression.entries.forEach { e ->
        val c = SofyMotion.clip(e)
        Part.entries.forEach { p -> assertEquals("${e.name} ${p.name}", SofyMotion.poseAt(c, p, c.lengthMs), SofyMotion.poseAt(c, p, c.lengthMs + 5000)) }
    }

    @Test fun sampleClampsAndInterpolates() {
        val keys = listOf(Key(0f, rot = 0f), Key(1f, rot = 10f))
        assertEquals(0f, SofyMotion.sample(keys, -1f).rot, 0f)
        assertEquals(10f, SofyMotion.sample(keys, 2f).rot, 0f)
        assertEquals(5f, SofyMotion.sample(keys, .5f).rot, 1e-4f)
    }

    @Test fun easesStartAtZeroAndEndAtOne() = Ease.entries.forEach { e ->
        assertEquals(e.name, 0f, SofyMotion.ease(e, 0f), 1e-4f)
        assertEquals(e.name, 1f, SofyMotion.ease(e, 1f), 1e-4f)
    }
}

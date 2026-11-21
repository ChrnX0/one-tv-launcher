package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.HoraPorExtenso
import br.paulo.tvlauncher.screensaver.Protetor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relógio "Por extenso" do protetor v2 (17/11/2026). */
class HoraPorExtensoTest {
    @Test fun horasEMinutos() {
        assertEquals("Nove e cinquenta e um" to "da noite", HoraPorExtenso.texto(21, 51))
        assertEquals("Uma e dois" to "da tarde", HoraPorExtenso.texto(13, 2))
        assertEquals("Duas em ponto" to "da madrugada", HoraPorExtenso.texto(2, 0))
        assertEquals("Seis e meia" to "da manhã", HoraPorExtenso.texto(6, 30))
        assertEquals("Onze e quinze" to "da manhã", HoraPorExtenso.texto(11, 15))
        assertEquals("Dez e vinte" to "da noite", HoraPorExtenso.texto(22, 20))
    }

    @Test fun meiaNoiteEMeioDia() {
        assertEquals("Meia-noite" to "", HoraPorExtenso.texto(0, 0))
        assertEquals("Meio-dia" to "", HoraPorExtenso.texto(12, 0))
        assertEquals("Meia-noite e cinco" to "", HoraPorExtenso.texto(0, 5))
        assertEquals("Meio-dia e meia" to "", HoraPorExtenso.texto(12, 30))
    }

    @Test fun numerosDoBrasilEDePortugal() {
        assertEquals("dezesseis", HoraPorExtenso.minutos(16))
        assertEquals("dezasseis", HoraPorExtenso.minutos(16, portugal = true))
        assertEquals("cinquenta e nove", HoraPorExtenso.minutos(59))
        assertEquals("dezanove", HoraPorExtenso.minutos(19, portugal = true))
        assertEquals("Três e dezassete" to "da tarde", HoraPorExtenso.texto(15, 17, portugal = true))
        // Todos os 1440 minutos do dia saem sem espaço duplo nem palavra vazia.
        for (h in 0..23) for (m in 0..59) {
            val (a, _) = HoraPorExtenso.texto(h, m)
            assertFalse("$h:$m -> $a", a.contains("  ") || a.endsWith(" ") || a.endsWith(" e"))
        }
    }

    @Test fun soEmPortugues() {
        assertTrue(HoraPorExtenso.disponivel("pt"))
        assertFalse(HoraPorExtenso.disponivel("en"))
        assertFalse(Protetor.REL_EXTENSO in Protetor.relogiosDaLingua("es"))
        assertTrue(Protetor.REL_EXTENSO in Protetor.relogiosDaLingua("pt"))
    }
}

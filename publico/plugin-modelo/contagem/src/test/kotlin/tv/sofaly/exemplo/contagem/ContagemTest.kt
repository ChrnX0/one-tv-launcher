package tv.sofaly.exemplo.contagem

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ContagemTest {
    @Test fun dias() {
        val hoje = LocalDate.of(2026, 11, 17)
        assertEquals("38", Contagem.valor(Contagem.diasAte(hoje, LocalDate.of(2026, 12, 25))))
        assertEquals("0", Contagem.valor(Contagem.diasAte(hoje, hoje)))
        assertEquals("+2", Contagem.valor(Contagem.diasAte(hoje, LocalDate.of(2026, 11, 15))))
    }
}

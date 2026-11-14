package br.paulo.tvlauncher

import br.paulo.tvlauncher.Gatilhos.Evento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 7: eventos como gatilho de cena. */
class GatilhosTest {
    @Test fun jsonFiltraEventoENomeRuim() {
        val m = mapOf(Evento.MIDIA_TOCOU to "Cinema", Evento.CHUVA to "Fechar tudo")
        assertEquals(m, Gatilhos.lerJson(Gatilhos.paraJson(m)))
        assertEquals(mapOf(Evento.TV_LIGOU to "Manhã"),
            Gatilhos.lerJson("""{"tv_ligou":"Manhã","inventado":"X","chuva":"<script>","todos_sairam":""}"""))
        assertEquals(emptyMap<Evento, String>(), Gatilhos.lerJson("estragado"))
    }

    @Test fun intervaloMinimo() {
        assertTrue(Gatilhos.podeDisparar(null, 0))
        assertFalse(Gatilhos.podeDisparar(1000, 1000 + Gatilhos.INTERVALO_MS - 1))
        assertTrue(Gatilhos.podeDisparar(1000, 1000 + Gatilhos.INTERVALO_MS))
    }

    @Test fun tocouUmaVezEPausaComEspera() {
        val f = FiltroMidia(3000)
        assertNull(f.pausou(0))                       // pausar sem ter tocado: nada
        assertEquals(Evento.MIDIA_TOCOU, f.tocou())
        assertNull(f.tocou())                         // play repetido não repete
        assertEquals(13_000L, f.pausou(10_000))
        assertNull(f.venceu(12_000))
        assertEquals(Evento.MIDIA_PAUSOU, f.venceu(13_000))
        assertEquals(Evento.MIDIA_TOCOU, f.tocou())   // voltou a tocar depois da pausa: dispara de novo
    }

    @Test fun pausaCurtaNaoConta() {
        val f = FiltroMidia(3000)
        f.tocou()
        f.pausou(0)
        assertNull(f.tocou())                         // voltou antes de 3 s (avançar, trocar episódio)
        assertNull(f.venceu(5000))
    }
}

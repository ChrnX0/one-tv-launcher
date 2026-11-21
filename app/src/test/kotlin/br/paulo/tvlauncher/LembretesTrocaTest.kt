package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Lembretes
import br.paulo.tvlauncher.player.avisar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 85 (14/11/2026): no guia, "Me avise" (2 min antes) ou "Troque sozinho na hora" (no início). */
class LembretesTrocaTest {
    private val min = 60_000L
    private val base = Lembretes.Lembrete("c1", "SporTV", "Jogo", 100 * min, 200 * min)

    @Test fun definirTrocaOJeitoESoUmPorPrograma() {
        var l = Lembretes.definir(emptyList(), base, Lembretes.MODO_AVISAR)
        assertEquals(Lembretes.MODO_AVISAR, Lembretes.modo(l, base.chave))
        l = Lembretes.definir(l, base, Lembretes.MODO_TROCAR)
        assertEquals(1, l.size)
        assertEquals(Lembretes.MODO_TROCAR, Lembretes.modo(l, base.chave))
        l = Lembretes.definir(l, base, Lembretes.MODO_NADA)
        assertTrue(l.isEmpty())
        assertEquals(Lembretes.MODO_NADA, Lembretes.modo(l, base.chave))
    }

    @Test fun avisaDoisMinutosAntesETrocaNoInicio() {
        val avisar = base
        val trocar = base.copy(canalId = "c2", trocar = true)
        val lista = listOf(avisar, trocar)
        assertEquals(98 * min, Lembretes.proximoAviso(lista, 0))
        assertEquals(listOf(avisar), Lembretes.devidos(lista, 98 * min))
        assertEquals(100 * min, Lembretes.proximoAviso(Lembretes.marcarAvisados(lista, setOf(avisar.chave)), 98 * min))
        assertEquals(listOf(trocar), Lembretes.devidos(Lembretes.marcarAvisados(lista, setOf(avisar.chave)), 100 * min))
    }

    @Test fun jsonGuardaATroca() {
        val l = Lembretes.deJson(Lembretes.paraJson(listOf(base.copy(trocar = true), base.copy(canalId = "c2"))))
        assertTrue(l.first { it.canalId == "c1" }.trocar)
        assertFalse(l.first { it.canalId == "c2" }.trocar)
        assertFalse(Lembretes.deJson("""[{"canal":"c","titulo":"t","inicio":1,"fim":2}]""").first().trocar)   // JSON antigo
    }
}

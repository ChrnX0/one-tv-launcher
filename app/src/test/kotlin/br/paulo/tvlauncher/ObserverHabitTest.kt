package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverHabit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserverHabitTest {

    /** [noites] noites de amostras de 5 min (12 por hora); [emUso] = a hora em que alguém está vendo. */
    private fun casa(noites: Int, emUso: (Int) -> Boolean) = ObserverHabit().apply {
        repeat(noites) { for (h in 0 until 24) repeat(12) { learn(h, emUso(h)) } }
    }

    @Test fun semDadosFicaNas4h() {
        assertEquals(4, ObserverHabit().quietStart())
        assertEquals(4, casa(2) { false }.quietStart())   // 24 leituras por hora: ainda imaturo
    }

    @Test fun casaQuietaFicaNas4h() {
        assertEquals(4, casa(3) { false }.quietStart())   // empate: a mais perto das 4h
    }

    @Test fun quemVeTvAteTardeOuAcordaCedoMudaAJanela() {
        // Vê até as 3h59 todo dia: 4h-5h59 quietas (o padrão continua).
        assertEquals(4, casa(4) { it >= 19 || it < 4 }.quietStart())
        // Liga a TV às 4h para o jornal: 1h-2h e 2h-3h quietas; empate fica com a mais perto das 4h.
        assertEquals(2, casa(4) { it in 4..8 || it >= 20 || it == 0 }.quietStart())
    }

    @Test fun usoParcialEscolheAMenosUsada() {
        // 1h em uso 1 vez em 4; 3h, 4h e 5h em uso 1 vez em 2: a janela 1h-2h59 (25%) ganha de 2h-3h59 (50%) e das outras.
        val h = ObserverHabit()
        repeat(5) { for (hora in 0 until 24) repeat(12) { k -> h.learn(hora, when (hora) { 1 -> k % 4 == 0; 3, 4, 5 -> k % 2 == 0; else -> false }) } }
        assertEquals(1, h.quietStart())
    }

    @Test fun idaEVoltaPeloJson() {
        val h = casa(4) { it in 4..8 || it >= 20 || it == 0 }
        val volta = ObserverHabit.fromJson(JSONObject(h.toJson().toString()))
        assertEquals(h.quietStart(), volta.quietStart())
        assertEquals(4, ObserverHabit.fromJson(JSONObject().put("v", 2)).quietStart())
        assertEquals(4, ObserverHabit.fromJson(null).quietStart())
    }

    @Test fun manutencaoUsaAJanelaAprendida() {
        // Padrão (4h): igual a antes.
        assertEquals(ManutencaoRegras.Decisao.ADIAR, ManutencaoRegras.decidir(4, true, 5, true))
        assertEquals(ManutencaoRegras.Decisao.DESISTIR, ManutencaoRegras.decidir(5, true, 5, true))
        // Janela aprendida às 1h: 0h a 2h59; adia na 1h, desiste às 2h, fora dela desiste.
        assertEquals(ManutencaoRegras.Decisao.RODAR, ManutencaoRegras.decidir(1, false, 0, false, inicio = 1))
        assertEquals(ManutencaoRegras.Decisao.ADIAR, ManutencaoRegras.decidir(1, true, 5, true, inicio = 1))
        assertEquals(ManutencaoRegras.Decisao.DESISTIR, ManutencaoRegras.decidir(2, true, 5, true, inicio = 1))
        assertEquals(ManutencaoRegras.Decisao.DESISTIR, ManutencaoRegras.decidir(4, false, 0, false, inicio = 1))
    }
}

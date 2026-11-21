package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.settings.Receitas
import br.paulo.tvlauncher.settings.Receitas.Ajuste
import br.paulo.tvlauncher.settings.Receitas.Aviso
import br.paulo.tvlauncher.settings.Receitas.Receita
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receitas prontas (degrau 4): aplicar e desfazer devolvem o estado exato, PIN, avisos e plano. */
class ReceitasTest {
    /** Uma TV "de fábrica": tudo desligado, menos card Jogos e modo jogo (padrão true), texto 100%. */
    private val fabrica: Map<Ajuste, Int> = Ajuste.values().associateWith { 0 } +
        mapOf(Ajuste.TAMANHO_TEXTO to 100, Ajuste.CARD_JOGOS to 1, Ajuste.MODO_JOGO to 1)

    /** Simula a TV: aplica as mudanças ao mapa. */
    private fun gravar(e: Map<Ajuste, Int>, m: List<Pair<Ajuste, Int>>) = e + m

    @Test fun aplicarEDesfazerVoltaExatamente() {
        for (r in Receita.values()) {
            val foto = Receitas.guardar(null, fabrica)
            val depois = gravar(fabrica, Receitas.mudancas(r, fabrica))
            r.ajustes.forEach { (a, v) -> assertEquals("$r $a", Receitas.normalizar(a, v), depois[a]) }
            val volta = gravar(depois, Receitas.desfazer(foto, depois))
            assertEquals("$r", fabrica, volta)
        }
    }

    @Test fun duasReceitasSeguidasDesfazemAteOComeco() {
        var e = fabrica
        var foto: String? = null
        for (r in listOf(Receita.VO, Receita.CRIANCAS, Receita.ALUGUEL)) {
            foto = Receitas.guardar(foto, e)
            e = gravar(e, Receitas.mudancas(r, e))
        }
        assertEquals(fabrica, gravar(e, Receitas.desfazer(foto, e)))
    }

    @Test fun soMudaOQueEstaDiferente() {
        val m = Receitas.mudancas(Receita.GAMER, fabrica)
        assertEquals(listOf(Ajuste.CONTROLE_ABRE_JOGOS to 1), m)   // card Jogos e modo jogo já vêm ligados
        assertTrue(Receitas.mudancas(Receita.GAMER, gravar(fabrica, m)).isEmpty())
    }

    @Test fun ordemHospedeAntesDaVisita() {
        val m = Receitas.mudancas(Receita.ALUGUEL, fabrica).map { it.first }
        assertEquals(listOf(Ajuste.HOSPEDE, Ajuste.VISITA), m)
        val depois = gravar(fabrica, Receitas.mudancas(Receita.ALUGUEL, fabrica))
        assertEquals(listOf(Ajuste.HOSPEDE, Ajuste.VISITA), Receitas.desfazer(Receitas.guardar(null, fabrica), depois).map { it.first })
    }

    @Test fun desligarProtegidoPedePin() {
        val depois = gravar(fabrica, Receitas.mudancas(Receita.VO, fabrica))
        val desfazer = Receitas.desfazer(Receitas.guardar(null, fabrica), depois)
        assertTrue(Receitas.pedePin(desfazer, temPin = true))
        assertFalse("sem PIN definido não há o que pedir", Receitas.pedePin(desfazer, temPin = false))
        assertFalse("ligar não pede", Receitas.pedePin(Receitas.mudancas(Receita.VO, fabrica), temPin = true))
        // desligar algo sem trava (som de navegação) não pede
        assertFalse(Receitas.pedePin(listOf(Ajuste.SOM_NAVEGACAO to 0, Ajuste.TAMANHO_TEXTO to 100), temPin = true))
    }

    @Test fun avisos() {
        assertEquals(setOf(Aviso.PRENDE), Receitas.avisos(Receita.VO, fabrica, temPin = true))
        assertEquals(setOf(Aviso.SEM_PIN), Receitas.avisos(Receita.VO, fabrica, temPin = false))
        assertEquals(emptySet<Aviso>(), Receitas.avisos(Receita.GAMER, fabrica, temPin = false))
        // modo simples já ligado: não avisa de novo
        val simples = fabrica + (Ajuste.MODO_SIMPLES to 1)
        assertFalse(Aviso.PRENDE in Receitas.avisos(Receita.VO, simples, temPin = true))
    }

    @Test fun planoSemBloqueioNaoLigaInfantilEAvisa() {
        val semPro: (Planos.Recurso) -> Boolean = { it != Planos.Recurso.IPTV_BLOQUEIO }
        val m = Receitas.mudancas(Receita.CRIANCAS, fabrica, semPro).map { it.first }
        assertFalse(Ajuste.INFANTIL in m)
        assertTrue(Ajuste.HORA_DORMIR in m)
        assertTrue(Aviso.PLANO in Receitas.avisos(Receita.CRIANCAS, fabrica, temPin = true, permite = semPro))
        assertEquals(listOf(Ajuste.INFANTIL), Receitas.bloqueadosPeloPlano(Receita.CRIANCAS, semPro))
    }

    @Test fun fotoIdaEVoltaEFotoEstranha() {
        val t = Receitas.paraTexto(fabrica + (Ajuste.TAMANHO_TEXTO to 120))
        assertEquals(fabrica + (Ajuste.TAMANHO_TEXTO to 120), Receitas.deTexto(t))
        assertEquals(mapOf(Ajuste.LER_FOCO to 1, Ajuste.TAMANHO_TEXTO to 110),
            Receitas.deTexto("LER_FOCO=7;XYZ=1;quebrado;TAMANHO_TEXTO=112;MODO_SIMPLES=abc"))
        assertTrue(Receitas.desfazer(null, fabrica).isEmpty())
        assertTrue(Receitas.desfazer("", fabrica).isEmpty())
    }

    @Test fun nomeDaReceita() {
        assertEquals(Receita.VO, Receitas.dePalavra(" vo "))
        assertEquals(null, Receitas.dePalavra("bisa"))
    }
}

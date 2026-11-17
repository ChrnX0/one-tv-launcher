package br.paulo.tvlauncher

import br.paulo.tvlauncher.PrimeiraPergunta.Resposta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T3: uma pergunta só na primeira abertura, cobrindo cada menu e cada receita. */
class PrimeiraPerguntaTest {
    @Test fun cadaReceitaApareceUmaVez() {
        val rs = Resposta.values().mapNotNull { it.receita }
        assertEquals(Receitas.Receita.values().toSet(), rs.toSet())
        assertEquals(rs.size, rs.toSet().size)
    }

    @Test fun cadaMenuTemUmaRespostaSemReceita() {
        assertEquals(UsoTv.COMPLETO, Resposta.CASA.uso)
        assertEquals(UsoTv.SIMPLES, Resposta.ESSENCIAL.uso)
        assertEquals(UsoTv.CUIDA, Resposta.VO.uso)                 // a TV da vó é cuidada de longe
        assertTrue(UsoTv.values().all { u -> Resposta.values().any { it.uso == u } })
    }

    /** O nome antigo não volta nos textos que a pessoa lê (o histórico de versões fica como foi). */
    @Test fun semModoSimplesNosTextos() {
        val ruins = Regex("Modo simples|Simple mode|Modo simple\\b|Einfacher Modus|Mode simple|Modalità semplice|Простой режим|Tryb prosty|Basit mod|シンプルモード|简易模式|간단 모드|간편 모드|सरल मोड|आसान मोड|Mode sederhana|الوضع البسيط")
        val achados = (File("src/main/res").walk() + File("src/main/assets/painel").walk())
            .filter { it.isFile && (it.extension == "xml" || it.extension == "js" || it.extension == "html") && !it.name.startsWith("strings_versoes") }
            .filter { ruins.containsMatchIn(it.readText()) }.map { it.path }.toList()
        assertTrue("nome antigo em: $achados", achados.isEmpty())
        assertFalse(File("src/main/kotlin/br/paulo/tvlauncher/BoasVindasActivity.kt").readText().contains("uso_pergunta"))
    }
}

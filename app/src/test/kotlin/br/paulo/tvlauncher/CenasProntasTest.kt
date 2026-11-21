package br.paulo.tvlauncher

import br.paulo.tvlauncher.mcp.ComandosIa
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.CenasProntas
import br.paulo.tvlauncher.network.CenasProntas.Modelo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cenas prontas (degrau 3): todas as ações passam no catálogo fechado do ComandosIa. */
class CenasProntasTest {
    @Test fun todasPassamNoCatalogo() {
        for (m in Modelo.entries) {
            val p = ComandosIa.conferir(CenasProntas.acoes(m, if (m.pedeCanal) "Globo" else null), emptyMap())
            assertTrue("$m recusada: ${p.recusadas}", p.recusadas.isEmpty())
            assertEquals(if (m == Modelo.DORMIR) 3 else 2, p.acoes.size)
        }
    }

    @Test fun soAsQueFaltam() {
        val nomes = mapOf(Modelo.DORMIR to "Dormir", Modelo.NOVELA to "Novela", Modelo.JOGO to "Jogo")
        assertEquals(listOf(Modelo.NOVELA, Modelo.JOGO), CenasProntas.faltando(nomes, listOf("dormir", "Cinema")))
        assertEquals(Modelo.entries, CenasProntas.faltando(nomes, emptyList()))
    }

    @Test fun nomesEmTodosOsIdiomas() {
        // Os nomes das cenas prontas nos 16 idiomas passam no nome de cena (o hindi tem sinais de vogal, \p{M}).
        val res = listOf(java.io.File("src/main/res"), java.io.File("app/src/main/res")).first { it.exists() }
        val chaves = Regex("""<string name="cpr_(dormir|novela|jogo)">(.*?)</string>""")
        res.listFiles { f -> f.name.startsWith("values") }!!.forEach { pasta ->
            val f = java.io.File(pasta, "strings_cenas_prontas.xml")
            if (f.exists()) chaves.findAll(f.readText()).forEach { m ->
                assertTrue("${pasta.name}: ${m.groupValues[2]}", Cenas.nomeValido(m.groupValues[2].replace("\\'", "'")))
            }
        }
    }
}

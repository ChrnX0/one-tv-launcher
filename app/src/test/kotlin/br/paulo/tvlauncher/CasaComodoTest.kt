package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tela Casa por cômodo (13/11/2026): ler as áreas do Home Assistant e agrupar os cartões (CasaHA, puras). */
class CasaComodoTest {
    private fun e(id: String, nome: String = id) = CasaHA.Entidade(id, nome, "off")

    @Test fun lerAreasDoTemplate() {
        val m = CasaHA.lerAreas("""[["light.luz_1","Quarto"],["light.luz_2",null],["switch.cafe","  Cozinha "],["x","Sala"]]""")!!
        assertEquals(mapOf("light.luz_1" to "Quarto", "switch.cafe" to "Cozinha"), m)   // sem área e id inválido ficam de fora
        assertEquals(emptyMap<String, String>(), CasaHA.lerAreas("[]"))
    }

    @Test fun lerAreasEmbrulhadaOuQuebrada() {
        assertEquals(mapOf("light.a" to "Sala"), CasaHA.lerAreas("\"[[\\\"light.a\\\",\\\"Sala\\\"]]\""))
        assertNull(CasaHA.lerAreas("Error rendering template"))
    }

    @Test fun agrupaPorComodoEmOrdemComSemComodoNoFim() {
        val lista = listOf(e("scene.noite"), e("light.luz_1"), e("switch.tv"), e("light.abajur"), e("script.bom_dia"))
        val areas = mapOf("light.luz_1" to "Quarto", "scene.noite" to "Quarto", "switch.tv" to "Área externa",
            "light.abajur" to "quarto de hóspedes")
        val g = CasaHA.agruparPorComodo(lista, areas)
        // "Área" antes de "Quarto" (acento e maiúscula não mudam a ordem); sem cômodo (null) por último
        assertEquals(listOf("Área externa", "Quarto", "quarto de hóspedes", null), g.map { it.first })
        // dentro do cômodo: aparelhos antes das cenas
        assertEquals(listOf("light.luz_1", "scene.noite"), g[1].second.map { it.id })
        assertEquals(listOf("script.bom_dia"), g[3].second.map { it.id })
    }

    @Test fun semAreasTudoSemComodo() {
        val g = CasaHA.agruparPorComodo(listOf(e("light.a")), emptyMap())
        assertEquals(1, g.size)
        assertNull(g[0].first)
    }

    @Test fun templateSoDosDominiosDaTela() {
        val t = CasaHA.TEMPLATE_AREAS
        assertTrue(t.contains("area_name(s.entity_id)"))
        CasaHA.DOMINIOS.forEach { assertTrue(it, t.contains("'$it'")) }
    }
}

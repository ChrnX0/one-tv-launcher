package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.PanelShortcuts
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Seus atalhos" e "Conversa com" do painel (estudo do painel 20/11/2026, PR 4): a lista que a TV aceita guardar, e
 * nenhum link apontando para um bloco que não existe (o estudo pede "revisada em teste").
 */
class PanelShortcutsTest {
    private fun painel(nome: String): String = listOf("src/main/assets/painel/$nome", "app/src/main/assets/painel/$nome")
        .map(::File).first { it.isFile }.readText()

    @Test fun guardaSoAFormaCerta() {
        assertEquals(listOf("chave:FILTRO_NOTURNO", "bloco:cartaoMural"),
            PanelShortcuts.normalize(JSONArray(listOf("chave:FILTRO_NOTURNO", "bloco:cartaoMural", "chave:FILTRO_NOTURNO"))))
        assertEquals(emptyList<String>(), PanelShortcuts.normalize(JSONArray()))
        assertNull(PanelShortcuts.normalize("chave:X"))
        assertNull(PanelShortcuts.normalize(JSONArray(listOf("shell:rm"))))
        assertNull(PanelShortcuts.normalize(JSONArray(listOf("bloco:<img>"))))
        assertNull(PanelShortcuts.normalize(JSONArray(listOf(1, 2))))
        assertEquals(12, PanelShortcuts.normalize(JSONArray((1..20).map { "bloco:b$it" }))!!.size)
    }

    @Test fun deFabricaApontamParaOQueExiste() {
        val html = painel("index.html")
        val extras = painel("extras.js")
        for (a in PanelShortcuts.FACTORY) {
            val (tipo, nome) = a.split(':')
            when (tipo) {
                "bloco" -> assertTrue("bloco $nome existe", html.contains("id=\"$nome\""))
                "config" -> assertTrue("opção $nome tem linha", html.contains("data-atalho=\"config:$nome\""))
                "chave" -> assertTrue("chave $nome existe", extras.contains("$nome:"))
            }
        }
    }

    @Test fun conversaComSoBlocoQueExiste() {
        val js = painel("shortcuts.js")
        val html = painel("index.html")
        val tabela = js.substringAfter("const RELACOES = [").substringBefore("];")
        val ids = Regex("'([A-Za-z0-9]+)'").findAll(tabela).map { it.groupValues[1] }.toSet()
        assertTrue("a tabela tem relações", ids.size > 20)
        val faltam = ids.filter { !html.contains("id=\"$it\"") }
        assertEquals("ids da tabela sem bloco no index.html", emptyList<String>(), faltam)
    }
}

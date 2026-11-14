package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Confere a estrutura do index.html do painel. Motivo (05/11/2026): um merge local perdeu o </section> da aba Falar, e
 * o navegador pôs as sete abas seguintes DENTRO dela. Com a Falar escondida, todas apareciam vazias, sem nenhum erro.
 * Cada PR que mexe no painel junta abas no mesmo arquivo, então isso pode se repetir em qualquer merge.
 */
class PainelHtmlTest {
    // Os testes de unidade rodam com a pasta do módulo (app/) como diretório atual.
    private val html: String = listOf("src/main/assets/painel/index.html", "app/src/main/assets/painel/index.html")
        .map(::File).first { it.isFile }.readText()

    /** (id, profundidade) de cada <section>, contando só as <section> abertas e fechadas. */
    private fun secoes(): List<Pair<String?, Int>> {
        val r = ArrayList<Pair<String?, Int>>()
        var nivel = 0
        for (m in Regex("""<(/?)section\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html)) {
            if (m.groupValues[1] == "/") { nivel--; continue }
            val id = Regex("""\bid="([^"]+)"""").find(m.groupValues[2])?.groupValues?.get(1)
            r.add(id to nivel)
            nivel++
        }
        assertEquals("cada <section> precisa do seu </section>", 0, nivel)
        return r
    }

    /** Ideia 76: a tradução carrega ANTES dos outros scripts (o observador precisa existir quando eles escrevem). */
    @Test fun traducaoCarregaPrimeiro() {
        val i18n = html.indexOf("<script src=\"i18n.js\"")
        assertTrue("index.html carrega i18n.js", i18n >= 0)
        val outros = Regex("""<script src="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals("i18n.js é o primeiro script", "i18n.js", outros.first())
        val js = listOf("src/main/assets/painel/i18n.js", "app/src/main/assets/painel/i18n.js").map(::File).first { it.isFile }.readText()
        assertTrue("só traduz fora do português", js.contains("/^pt/i.test(idioma)"))
    }

    @Test fun nenhumaAbaDentroDeOutra() {
        val abas = secoes().filter { it.first?.startsWith("aba") == true }
        assertTrue("o painel tem abas", abas.isNotEmpty())
        val nivel = abas.first().second
        for ((id, n) in abas) assertEquals("a aba $id está dentro de outra seção (faltou um </section> antes dela?)", nivel, n)
    }

    @Test fun cadaBotaoDeAbaTemSuaSecao() {
        val ids = secoes().mapNotNull { it.first }.toSet()
        val botoes = Regex("""data-aba="([a-z]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(botoes.isNotEmpty())
        // Convenção do app.js: data-aba="arquivos" mostra a <section id="abaArquivos">; "registro" é a de Acessos.
        for (b in botoes) assertTrue("o botão de aba \"$b\" não tem <section id=\"aba${b.replaceFirstChar { it.uppercase() }}\">",
            "aba" + b.replaceFirstChar { it.uppercase() } in ids)
    }

    /**
     * O servidor só entrega os arquivos da lista TIPOS do PainelWeb.kt. Um <script src> novo que não esteja lá
     * chega como 404 na TV e a aba fica morta, sem erro nenhum no build (10/11/2026: ui.js e ia.js).
     */
    @Test fun todoArquivoDoIndexEstaNaListaDoServidor() {
        val web = listOf("src/main/kotlin/br/paulo/tvlauncher/PainelWeb.kt", "app/src/main/kotlin/br/paulo/tvlauncher/PainelWeb.kt")
            .map(::File).first { it.isFile }.readText()
        val usados = Regex("""<(?:script[^>]+src|link[^>]+href)="([^":]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(usados.isNotEmpty())
        for (u in usados) assertTrue("\"$u\" está no index.html mas não na lista TIPOS do PainelWeb.kt", "\"/$u\" to" in web)
    }

    // A CSP do painel tem style-src 'self' sem 'unsafe-inline': um style="..." no HTML é ignorado calado (auditoria
    // 10/11/2026: as quebras de linha do passo do Claude sumiam). Estilo vai no style.css, por classe.
    @Test fun semEstiloInline() {
        assertTrue("index.html tem style=\"...\" (a CSP ignora; use uma classe)", !html.contains(" style=\""))
    }
}

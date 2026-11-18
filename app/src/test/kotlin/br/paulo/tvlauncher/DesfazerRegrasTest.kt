package br.paulo.tvlauncher

import br.paulo.tvlauncher.DesfazerRegras.Categoria
import br.paulo.tvlauncher.DesfazerRegras.Mudanca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 2.11: um teste por categoria (ajuste, organização, automação, limpeza) e as regras em volta. */
class DesfazerRegrasTest {
    private val t0 = 1_800_000_000_000L

    /** Simula as prefs: aplica um passo de ida e o desfazer, como o Desfazer.kt faz com as SharedPreferences. */
    private fun idaEVolta(prefs: String, antes: Map<String, Any?>, depois: Map<String, Any?>, cat: Categoria) {
        val ms = DesfazerRegras.diferencas(prefs, antes, depois)
        assertTrue("$prefs: algo mudou", ms.isNotEmpty())
        val (lista, passo) = DesfazerRegras.registrar(emptyList(), cat, "tela", ms, emptyList(), t0, "a1")
        assertEquals(cat, DesfazerRegras.categoria(prefs, ms.first().chave))
        // Volta pelo JSON, como depois de reiniciar a TV.
        val lido = DesfazerRegras.deJson(DesfazerRegras.paraJson(lista)).single()
        assertEquals(passo, lido)
        val atual = HashMap(depois)
        val (voltam, ficaram) = DesfazerRegras.planoDesfazer(lido) { _, k -> atual[k] }
        assertEquals(0, ficaram)
        for (m in voltam) if (m.antes == null) atual.remove(m.chave) else atual[m.chave] = m.antes
        assertEquals(antes.filterValues { it != null }, atual.filterValues { it != null })
    }

    @Test fun ajuste() = idaEVolta("launcher",
        mapOf("tema" to "escuro", "tamanho_texto" to 2, "relogio_24h" to true),
        mapOf("tema" to "claro", "tamanho_texto" to 3, "relogio_24h" to true, "mostrar_clima" to false), Categoria.AJUSTE)

    @Test fun organizacao() = idaEVolta("launcher",
        mapOf("ordem" to "a,b,c", "ocultos" to setOf("x")),
        mapOf("ordem" to "b,a,c", "ocultos" to setOf("x", "y"), "pilhas" to "[{\"id\":\"1\"}]"), Categoria.ORGANIZACAO)

    @Test fun automacao() = idaEVolta("rotinas",
        mapOf("lista" to "[]"), mapOf("lista" to "[{\"hora\":\"07:00\"}]"), Categoria.AUTOMACAO)

    @Test fun limpeza() {
        val rev = DesfazerRegras.reversoDe("com.exemplo.app", ocultou = true)
        assertEquals("mostrar:com.exemplo.app", rev)
        val (lista, passo) = DesfazerRegras.registrar(emptyList(), Categoria.LIMPEZA, "Ocultou Exemplo", emptyList(), listOf(rev), t0, "l1")
        assertEquals(listOf(rev), passo!!.reverso)
        assertEquals(listOf(rev), DesfazerRegras.deJson(DesfazerRegras.paraJson(lista)).single().reverso)
        // Vocabulário fechado: comando livre não entra.
        val (_, nada) = DesfazerRegras.registrar(emptyList(), Categoria.LIMPEZA, "x", emptyList(), listOf("shell:rm -rf /", "mostrar:sem_ponto"), t0, "l2")
        assertNull(nada)
        // A chave que o Debloat grava não vira passo pela prefs (o passo vem com a ação contrária).
        assertNull(DesfazerRegras.categoria("launcher", DesfazerRegras.CHAVE_DEBLOAT))
    }

    @Test fun segredoEPassageiroNuncaEntram() {
        for (k in listOf("pin_sal", "wifi_senha", "token", "google_segredo", "ultimo_aviso", "reiniciou_em", "erro", "tentativas"))
            assertNull(k, DesfazerRegras.categoria("launcher", k))
        assertNull(DesfazerRegras.categoria("painel_seguro", "qualquer"))   // prefs não vigiada
        assertTrue(DesfazerRegras.diferencas("launcher", mapOf("pin_sal" to "a"), mapOf("pin_sal" to "b")).isEmpty())
        assertEquals(Categoria.AJUSTE, DesfazerRegras.categoria("launcher@filho", "tema"))   // perfil
        assertEquals(Categoria.ORGANIZACAO, DesfazerRegras.categoria("launcher", "ordem@filho"))
    }

    @Test fun toquesSeguidosViramUmPassoEVoltarAoQueEraSome() {
        val m1 = listOf(Mudanca("launcher", "ordem", "a,b,c", "b,a,c"))
        val (l1, _) = DesfazerRegras.registrar(emptyList(), Categoria.ORGANIZACAO, "HOME", m1, emptyList(), t0, "p1")
        val m2 = listOf(Mudanca("launcher", "ordem", "b,a,c", "b,c,a"))
        val (l2, p2) = DesfazerRegras.registrar(l1, Categoria.ORGANIZACAO, "HOME", m2, emptyList(), t0 + 800, "p2")
        assertEquals(1, l2.size)
        assertEquals("p1", p2!!.id)
        assertEquals("a,b,c", p2.mudancas.single().antes)   // o antes do PRIMEIRO toque
        assertEquals("b,c,a", p2.mudancas.single().depois)
        // Voltou ao começo: nada a desfazer, o passo some.
        val m3 = listOf(Mudanca("launcher", "ordem", "b,c,a", "a,b,c"))
        val (l3, p3) = DesfazerRegras.registrar(l2, Categoria.ORGANIZACAO, "HOME", m3, emptyList(), t0 + 1600, "p3")
        assertTrue(l3.isEmpty()); assertNull(p3)
        // Depois do intervalo: passo novo.
        val (l4, _) = DesfazerRegras.registrar(l1, Categoria.ORGANIZACAO, "HOME", m2, emptyList(), t0 + 5000, "p4")
        assertEquals(listOf("p4", "p1"), l4.map { it.id })
    }

    @Test fun chaveMexidaDepoisFica() {
        val passo = DesfazerRegras.Passo("x", t0, Categoria.AJUSTE, "Config", listOf(
            Mudanca("launcher", "tema", "escuro", "claro"), Mudanca("launcher", "relogio", false, true)))
        val atual = mapOf("tema" to "cinema", "relogio" to true)   // o tema mudou de novo depois
        val (voltam, ficaram) = DesfazerRegras.planoDesfazer(passo) { _, k -> atual[k] }
        assertEquals(listOf("relogio"), voltam.map { it.chave })
        assertEquals(1, ficaram)
        val desfeito = DesfazerRegras.marcarDesfeito(listOf(passo), "x").single()
        assertTrue(DesfazerRegras.planoDesfazer(desfeito) { _, k -> atual[k] }.first.isEmpty())
    }

    @Test fun tiposVoltamIguais() {
        for (v in listOf(null, true, 7, 9_000_000_000L, 1.5f, "texto", sortedSetOf("a", "b")))
            assertTrue("$v", DesfazerRegras.igual(v, DesfazerRegras.valorDeJson(DesfazerRegras.valorJson(v))))
        assertTrue(!DesfazerRegras.igual(1, 1L))   // Int e Long são chaves diferentes nas prefs
    }

    @Test fun limitesDeTamanhoEIdade() {
        var lista = emptyList<DesfazerRegras.Passo>()
        for (i in 0 until 60) lista = DesfazerRegras.registrar(lista, Categoria.AJUSTE, "t$i",
            listOf(Mudanca("launcher", "tema", "$i", "${i + 1}")), emptyList(), t0 + i * 10_000L, "id$i").first
        assertEquals(DesfazerRegras.MAX, lista.size)
        assertEquals("id59", lista.first().id)
        assertTrue(DesfazerRegras.podar(lista, t0 + 8 * 86_400_000L).isEmpty())
    }
}

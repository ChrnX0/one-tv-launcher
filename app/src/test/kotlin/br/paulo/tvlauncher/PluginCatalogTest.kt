package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.LojaCurada
import br.paulo.tvlauncher.screensaver.PluginCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vitrine de plugins: ids únicos, onde conseguir cada um e o que o build da Play nunca faz (baixar APK). */
class PluginCatalogTest {
    @Test fun idsUnicosEComOndeConseguir() {
        val l = PluginCatalog.LISTA
        assertEquals(l.size, l.map { it.id }.toSet().size)
        l.forEach { assertNotNull("${it.id} sem página", PluginCatalog.pagina(it)) }
        l.forEach { e -> e.repo?.let { assertTrue(e.id, LojaCurada.repoDeEndereco(it) == it) } }
    }

    @Test fun buildDaPlayNuncaInstalaApk() {
        PluginCatalog.LISTA.forEach { e ->
            assertTrue(e.id, PluginCatalog.acao(e, buildPlay = true, temPlayStore = true) != PluginCatalog.Acao.INSTALAR)
            assertTrue(e.id, PluginCatalog.acao(e, buildPlay = true, temPlayStore = false) != PluginCatalog.Acao.INSTALAR)
        }
    }

    @Test fun preferePlayDepoisRelease() {
        val o = PluginCatalog.LISTA.first { it.id == "overflight" }
        assertEquals(PluginCatalog.Acao.PLAY, PluginCatalog.acao(o, buildPlay = false, temPlayStore = true))
        assertEquals(PluginCatalog.Acao.INSTALAR, PluginCatalog.acao(o, buildPlay = false, temPlayStore = false))
        val a = PluginCatalog.LISTA.first { it.id == "arcticons" }
        assertEquals(PluginCatalog.Acao.SO_QR, PluginCatalog.acao(a, buildPlay = false, temPlayStore = false))
        assertTrue(PluginCatalog.pagina(o)!!.startsWith("https://play.google.com/"))
    }

    @Test fun lojaRecebeSoOsComRepositorio() {
        val ids = PluginCatalog.itensLoja().map { it.id }
        assertEquals(PluginCatalog.LISTA.count { it.repo != null }, ids.size)
        assertTrue(ids.all { it.startsWith("pl-") })
        assertTrue(LojaCurada.CATALOGO.map { it.id }.containsAll(ids))
        assertEquals(LojaCurada.CATALOGO.size, LojaCurada.CATALOGO.map { it.id }.toSet().size)
    }

    @Test fun nenhumNomeDeConcorrenteNaVitrine() {
        PluginCatalog.LISTA.forEach { assertTrue(it.id, !it.nome.contains("projectivy", ignoreCase = true)) }
        assertEquals("TV Icon Pack", PluginCatalog.nomeDoPacote("dev.projectivy.blueprint.app"))
    }
}

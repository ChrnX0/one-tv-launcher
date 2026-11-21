package br.paulo.tvlauncher

import android.content.pm.ApplicationInfo
import br.paulo.tvlauncher.home.AppShelf
import br.paulo.tvlauncher.home.AppShelf.Shelf
import br.paulo.tvlauncher.home.CategoriaApps
import br.paulo.tvlauncher.home.CruzLogica.Categoria
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShelfTest {
    private fun d(pkg: String, manual: String? = null, game: Boolean = false, sheet: String? = null, cat: Int = -1,
                  system: Boolean = false, world: Categoria? = null) = AppShelf.decide(pkg, manual, game, sheet, cat, system, world)

    @Test fun ordemDeQuemDecide() {
        assertEquals(Shelf.TOOLS, d("x.a", manual = "tools", game = true, sheet = "games"))      // à mão vence tudo
        assertEquals(Shelf.GAMES, d("x.a", game = true, sheet = "video"))                         // jogo vence a ficha
        assertEquals(Shelf.SPORTS, d("com.netflix.ninja", sheet = "sports"))                      // ficha vence a lista
        assertEquals(Shelf.ENTERTAINMENT, d("com.netflix.ninja"))                                 // lista de conhecidos
        assertEquals(Shelf.MUSIC_AUDIO, d("com.spotify.tv.android"))
        assertEquals(Shelf.NEWS, d("x.b", cat = ApplicationInfo.CATEGORY_NEWS))                   // o que o app declara
        assertEquals(Shelf.SYSTEM, d("x.c", system = true))
        assertEquals(Shelf.OTHER, d("x.c"))
        assertEquals(Shelf.OTHER, d("x.c", manual = "lixo", sheet = "lixo"))                      // id desconhecido não vale
    }

    @Test fun mundoEscolhidoNaCruzManda() {
        // A pessoa moveu o YouTube Music para Assistir: a prateleira acompanha (padrão de Assistir).
        assertEquals(Shelf.ENTERTAINMENT, d("com.google.android.youtube.tvmusic", sheet = "music_audio", world = Categoria.ASSISTIR))
        // Mesmo mundo: fica a prateleira fina (Esportes continua Esportes dentro de Assistir).
        assertEquals(Shelf.SPORTS, d("x.s", sheet = "sports", world = Categoria.ASSISTIR))
        assertEquals(Shelf.OTHER, d("x.s", sheet = "sports", world = Categoria.APLICATIVOS))
    }

    @Test fun cruzUsaAFichaTambem() {
        assertEquals(Categoria.OUVIR, CategoriaApps.destino("x.radio", -1, false, null, "music_audio"))
        assertEquals(Categoria.ASSISTIR, CategoriaApps.destino("x.futebol", -1, false, null, "sports"))
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("x.jogo", -1, false, null, "games"))  // jogo é a tela Jogos que decide
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("x.radio", -1, false, "aplicativos", "music_audio"))
    }

    private fun e(pkg: String, s: Shelf, use: Double = 0.0) = AppShelf.Entry(pkg, pkg, s, use)

    @Test fun poucosAppsViramUmaFileiraSo() {
        val (rows, single) = AppShelf.rows(listOf(e("b", Shelf.VIDEO), e("a", Shelf.TOOLS, 3.0), e("c", Shelf.GAMES)))
        assertTrue(single)
        assertEquals(listOf("a", "b", "c"), rows.single().pkgs)   // mais usado primeiro, depois nome
    }

    @Test fun prateleiraDeUmJuntaEmOutrosMenosSistema() {
        val (rows, single) = AppShelf.rows(listOf(
            e("nf", Shelf.ENTERTAINMENT, 1.0), e("pv", Shelf.ENTERTAINMENT, 5.0), e("ytm", Shelf.MUSIC_AUDIO),
            e("dl", Shelf.TOOLS), e("fx", Shelf.TOOLS), e("set", Shelf.SYSTEM), e("ed", Shelf.EDUCATION)))
        assertEquals(false, single)
        assertEquals(listOf(Shelf.ENTERTAINMENT, Shelf.TOOLS, Shelf.SYSTEM, Shelf.OTHER), rows.map { it.shelf })
        assertEquals(listOf("pv", "nf"), rows[0].pkgs)
        assertEquals(listOf("ed", "ytm"), rows.last().pkgs)
    }

    @Test fun fichasDoApkTodasTemCategoriaValida() {
        val json = java.io.File("src/main/assets/fichas-apps.json").readText()
        val o = JSONObject(json)
        val cats = AppShelf.sheetCategories(json)
        assertEquals(o.length(), cats.size)
        assertEquals("entertainment", cats["com.netflix.ninja"])
        assertEquals("system", cats["com.android.systemui"])
    }

    @Test fun escolhaAMaoIdaEVolta() {
        val m = mapOf("a.b" to "sports", "c.d" to "tools")
        assertEquals(m, AppShelf.fromJson(AppShelf.toJson(m)))
        assertEquals(emptyMap<String, String>(), AppShelf.fromJson("{\"a.b\":\"lixo\"}"))
    }
}

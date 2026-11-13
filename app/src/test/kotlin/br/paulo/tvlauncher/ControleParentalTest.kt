package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controle parental: regras, JSON e o efeito no filtro do player. */
class ControleParentalTest {
    private fun c(id: String, nome: String, g: String = "", n: Int = 0) = Canal(id, nome, n, g, url = "http://e.invalid/$id")
    private val canais = listOf(c("a:1", "Desenhos", "Infantil"), c("a:2", "Filmes Fortes", "Filmes"), c("a:3", "Terror 24h", " Terror "), c("a:4", "Notícias", "Jornal"))

    @Test fun bloqueadoSoComLigado() {
        val r = ControleParental.Regras(ligado = false, canais = setOf("a:2"), grupos = setOf("Terror"))
        assertFalse(ControleParental.bloqueado(canais[1], r))
        val on = r.copy(ligado = true)
        assertTrue(ControleParental.bloqueado(canais[1], on))
        assertTrue(ControleParental.bloqueado(canais[2], on))   // pela categoria (com espaço nas pontas no nome do grupo)
        assertFalse(ControleParental.bloqueado(canais[0], on))
        assertTrue(on.ativo); assertFalse(r.ativo); assertFalse(ControleParental.Regras(ligado = true).ativo)
    }

    @Test fun alternar() {
        var r = ControleParental.Regras()
        r = ControleParental.alternarCanal(r, "a:2"); assertEquals(setOf("a:2"), r.canais)
        r = ControleParental.alternarCanal(r, "a:2"); assertTrue(r.canais.isEmpty())
        r = ControleParental.alternarGrupo(r, " Terror "); assertEquals(setOf("Terror"), r.grupos)
        r = ControleParental.alternarGrupo(r, "Terror"); assertTrue(r.grupos.isEmpty())
    }

    @Test fun jsonIdaEVolta() {
        val r = ControleParental.Regras(true, setOf("a:1", "b:x"), setOf("Adulto"))
        assertEquals(r, ControleParental.deJson(ControleParental.paraJson(r)))
        assertEquals(ControleParental.Regras(), ControleParental.deJson(null))
        assertEquals(ControleParental.Regras(), ControleParental.deJson("lixo"))
        assertEquals(setOf("x"), ControleParental.deJson("""{"ligado":true,"canais":["x","",3]}""").canais.filter { it == "x" }.toSet())
    }

    @Test fun filtroDoPlayerEscondeBloqueados() {
        val r = ControleParental.Regras(true, setOf("a:2"), setOf("Terror"))
        val f = PlayerFiltro.Filtro(parental = r)
        assertEquals(listOf("a:1", "a:4"), PlayerFiltro.aplicar(canais, f).map { it.id })
        assertEquals(4, PlayerFiltro.aplicar(canais, f.copy(mostrarOcultas = true)).size)   // com o PIN, tudo aparece
        assertEquals(4, PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(parental = r.copy(ligado = false))).size)
        // Junto com categoria oculta e filtro de nome.
        val g = PlayerFiltro.Filtro(ocultas = setOf("Jornal"), parental = r)
        assertEquals(listOf("a:1"), PlayerFiltro.aplicar(canais, g).map { it.id })
    }
}

/** Modo infantil (10/11/2026): a regra única vale na TV ao vivo e em Filmes/Séries (VodFiltro). */
class ModoInfantilTest {
    private fun c(id: String, g: String) = Canal(id, id, 0, g, url = "http://e.invalid/$id")
    private val canais = listOf(c("a:1", "Infantil"), c("a:2", "Filmes"), c("a:3", " Desenhos "), c("a:4", ""))

    @Test fun soInfantisAparecem() {
        val r = ControleParental.Regras(infantil = true, infantis = setOf("Infantil", "Desenhos"))
        assertTrue(r.ativo)
        assertEquals(listOf("a:1", "a:3"), PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(parental = r)).map { it.id })
        assertEquals(4, PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(parental = r, mostrarOcultas = true)).size)   // PIN
        // Desligado: nada muda, mesmo com categorias marcadas.
        assertEquals(4, PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(parental = r.copy(infantil = false))).size)
        // Junto com o bloqueio comum: categoria infantil bloqueada continua bloqueada.
        val ambos = r.copy(ligado = true, grupos = setOf("Desenhos"))
        assertEquals(listOf("a:1"), PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(parental = ambos)).map { it.id })
    }

    @Test fun mesmaRegraEmFilmesESeries() {
        val grupos = listOf("Infantil", "Ação", "XXX Adultos", "Desenhos")
        val r = ControleParental.Regras(infantil = true, infantis = setOf("Infantil"))
        assertEquals(setOf("Ação", "XXX Adultos", "Desenhos"), VodFiltro.ocultas(grupos, emptySet(), emptySet(), r))
        // Sem modo infantil, só a adulta (como antes).
        assertEquals(setOf("XXX Adultos"), VodFiltro.ocultas(grupos, emptySet(), emptySet(), ControleParental.Regras()))
        // Bloqueio por categoria também vale no VOD.
        assertEquals(setOf("Ação", "XXX Adultos"), VodFiltro.ocultas(grupos, emptySet(), emptySet(), ControleParental.Regras(ligado = true, grupos = setOf("Ação"))))
    }

    @Test fun adultaNuncaViraInfantil() {
        var r = ControleParental.Regras()
        r = ControleParental.alternarInfantil(r, " Desenhos "); assertEquals(setOf("Desenhos"), r.infantis)
        assertEquals(r, ControleParental.alternarInfantil(r, "XXX Adultos"))
        assertEquals(r, ControleParental.alternarInfantil(r, "  "))
        r = ControleParental.alternarInfantil(r, "Desenhos"); assertTrue(r.infantis.isEmpty())
        // Arquivo com adulta marcada (mexido à mão): some na leitura.
        assertEquals(setOf("Kids"), ControleParental.deJson("""{"infantil":true,"infantis":["Kids","XXX Adultos"]}""").infantis)
    }

    @Test fun jsonComInfantil() {
        val r = ControleParental.Regras(true, setOf("a:1"), setOf("Terror"), infantil = true, infantis = setOf("Kids"))
        assertEquals(r, ControleParental.deJson(ControleParental.paraJson(r)))
        // Arquivo antigo, sem os campos novos: modo infantil desligado.
        assertFalse(ControleParental.deJson("""{"ligado":true,"canais":["x"],"grupos":[]}""").infantil)
    }

    @Test fun categoriasDosTresCatalogos() {
        val cats = CategoriasInfantis.juntar(listOf("Kids", "Kids", "Notícias", "", "XXX Adultos"), listOf("Kids", "Animação"), listOf(" Animação "))
        assertEquals(listOf("Animação", "Kids", "Notícias"), cats.map { it.nome })
        assertEquals(CategoriasInfantis.Categoria("Kids", 2, 1, 0), cats[1])
        assertEquals("Kids  (TV 2 · Filmes 1)", CategoriasInfantis.rotulo(cats[1]))
        assertEquals("Animação  (Filmes 1 · Séries 1)", CategoriasInfantis.rotulo(cats[0]))
    }
}

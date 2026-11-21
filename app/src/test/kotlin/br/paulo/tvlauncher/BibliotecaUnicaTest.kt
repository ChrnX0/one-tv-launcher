package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.BibliotecaIndice
import br.paulo.tvlauncher.home.BibliotecaUnica
import br.paulo.tvlauncher.home.BibliotecaUnica.Fonte
import br.paulo.tvlauncher.home.BibliotecaUnica.Origem
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.LeitorNomes
import br.paulo.tvlauncher.player.Idioma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 7.1: o mesmo título vindo de lista, pasta, servidor, nuvem e addon aparece uma vez, com a melhor fonte. */
class BibliotecaUnicaTest {

    private fun lista(t: String, ano: Int?, tmdb: Int? = null, grupo: String = "", serie: Boolean = false) =
        Fonte(Origem.LISTA, "Minha lista", t, ano, serie, tmdb, qualidade = BibliotecaUnica.qualidadeDoNome("$t $grupo"),
            versao = Idioma.versao(t, grupo), abrir = t)
    private fun pasta(arquivo: String, onde: String = "PC") = LeitorNomes.ler(arquivo).let { l ->
        Fonte(Origem.PASTA, onde, l.nome, l.ano, l.serie, qualidade = BibliotecaUnica.qualidadeDoNome(arquivo),
            versao = Idioma.versao(arquivo, ""), abrir = "smb://conta1/Filmes/$arquivo")
    }

    /** Conjunto com repetidos de todo tipo: nenhum título sai duas vezes, e nenhum par diferente se junta. */
    @Test fun conjuntoRepetidoSemDuplicata() {
        val fontes = listOf(
            lista("Duna: Parte Dois (2024) [L]", 2024, tmdb = 693134),
            lista("Duna - Parte Dois (2024) [D]", 2024),
            pasta("Duna.Parte.Dois.2024.2160p.WEB-DL.mkv"),
            Fonte(Origem.SERVIDOR, "Jellyfin", "Duna: Parte Dois", 2024, false, tmdb = 693134),
            Fonte(Origem.ADDON, "Cinemeta", "Dune: Part Two", 2024, false, imdb = "tt15239678", tmdb = 693134),
            Fonte(Origem.NUVEM, "OneDrive", "Duna Parte Dois", 2023, false),          // ano com 1 de diferença
            lista("Aladdin (1992)", 1992), lista("Aladdin (2019)", 2019),
            pasta("Aladdin.1992.1080p.mkv"), pasta("Aladdin.2019.720p.mkv"),
            Fonte(Origem.NUVEM, "Drive", "Aladdin", null, false),                    // sem ano e 2 candidatos: sozinho
            lista("Tropa de Elite (2007)", 2007), pasta("Tropa.de.Elite.2007.1080p.Nacional.mkv"),
            Fonte(Origem.PASTA, "Pendrive", "Tropa de Elite", null, false),          // sem ano e 1 candidato: junta
            lista("Dark", 2017, serie = true), pasta("Dark.S01E01.mkv"),             // série sem ano na pasta: junta
            lista("Dark", 2017),                                                       // um FILME com o mesmo nome: separado
            lista("O Rei Leão (1994)", 1994, tmdb = 8587), lista("O Rei Leão (2019)", 2019, tmdb = 420818),
            Fonte(Origem.ADDON, "Cinemeta", "The Lion King", 2019, false, tmdb = 420818),
        )
        val r = BibliotecaUnica.juntar(fontes)
        val chaves = r.map { (if (it.serie) "s:" else "f:") + Idioma.chaveTitulo(it.nome) + "|" + it.ano }
        // Cada (tipo, nome, ano) aparece uma vez só; o Aladdin sem ano é uma 3ª linha de propósito (ano nulo).
        val duna = r.filter { Idioma.chaveTitulo(it.nome).startsWith("duna") }
        assertEquals("Duna em uma linha só", 1, duna.size)
        assertEquals(6, duna[0].fontes.size)
        assertEquals(3, r.count { Idioma.chaveTitulo(it.nome) == "aladdin" })
        assertEquals(listOf(1992, 2019, null), r.filter { Idioma.chaveTitulo(it.nome) == "aladdin" }.map { it.ano })
        assertEquals(2, r.first { it.ano == 1992 && Idioma.chaveTitulo(it.nome) == "aladdin" }.fontes.size)
        assertEquals(3, r.first { Idioma.chaveTitulo(it.nome) == "tropa de elite" }.fontes.size)
        assertEquals(2, r.first { it.serie && Idioma.chaveTitulo(it.nome) == "dark" }.fontes.size)
        assertTrue(r.any { !it.serie && Idioma.chaveTitulo(it.nome) == "dark" })
        val leao = r.filter { it.tmdb == 420818 }
        assertEquals(1, leao.size); assertEquals(2, leao[0].fontes.size)
        assertEquals(1, r.count { it.tmdb == 8587 })
        assertEquals("sem duplicata", chaves.size, chaves.toSet().size)
    }

    @Test fun melhorFonte() {
        // Prefere dublado: a lista dublada ganha da pasta 4K legendada? Não: a pasta não tem marca (DESCONHECIDA) e é 4K.
        // Ordem: versão preferida > desconhecida > a outra; depois qualidade; depois a de casa.
        val f = listOf(
            lista("Duna 2 (2024) [L]", 2024, grupo = "FHD"),
            lista("Duna 2 (2024) [D]", 2024, grupo = "HD"),
            pasta("Duna.2.2024.2160p.mkv"),
            Fonte(Origem.ADDON, "Torrentio-livre", "Duna 2", 2024, false, qualidade = 2160),
        )
        val t = BibliotecaUnica.juntar(f, Idioma.Versao.DUBLADA).single()
        assertEquals(Idioma.Versao.DUBLADA, t.melhor.versao)
        assertEquals(listOf(Origem.LISTA, Origem.PASTA, Origem.ADDON, Origem.LISTA), t.fontes.map { it.origem })
        val leg = BibliotecaUnica.juntar(f, Idioma.Versao.LEGENDADA).single()
        assertEquals(Idioma.Versao.LEGENDADA, leg.melhor.versao)
        // Empate em tudo: a de casa (pasta) antes da internet (addon).
        val empate = BibliotecaUnica.juntar(listOf(
            Fonte(Origem.ADDON, "A", "Up", 2009, false, qualidade = 1080), Fonte(Origem.PASTA, "PC", "Up", 2009, false, qualidade = 1080))).single()
        assertEquals(Origem.PASTA, empate.melhor.origem)
    }

    @Test fun qualidadePeloNome() {
        assertEquals(2160, BibliotecaUnica.qualidadeDoNome("Filme.2024.2160p.mkv"))
        assertEquals(2160, BibliotecaUnica.qualidadeDoNome("Filme 4K"))
        assertEquals(1080, BibliotecaUnica.qualidadeDoNome("Filmes | FHD"))
        assertEquals(720, BibliotecaUnica.qualidadeDoNome("Filmes HD"))
        assertEquals(0, BibliotecaUnica.qualidadeDoNome("Filme sem marca"))
        assertEquals(0, BibliotecaUnica.qualidadeDoNome("Hdtv shadow"))
    }

    @Test fun indiceNuncaGuardaSenha() {
        assertTrue(BibliotecaIndice.enderecoGuardavel("smb://conta1/Filmes/x.mkv"))
        assertTrue(BibliotecaIndice.enderecoGuardavel("http://192.168.1.5:8200/MediaItems/22.mkv"))
        assertFalse(BibliotecaIndice.enderecoGuardavel("https://ana:segredo@nuvem.exemplo/dav/x.mkv"))
        assertFalse(BibliotecaIndice.enderecoGuardavel("http://jelly:8096/Videos/1/stream?api_key=abc"))
        val f = Fonte(Origem.SERVIDOR, "Jelly", "Up", 2009, false, abrir = "http://jelly:8096/Videos/1/stream?api_key=abc")
        val volta = BibliotecaIndice.deJson(BibliotecaIndice.paraJson(f))!!
        assertEquals("", volta.abrir)
        assertEquals(f.copy(abrir = ""), volta)
    }

    @Test fun indiceMesclaSemRepetirETemTeto() {
        val a = Fonte(Origem.PASTA, "PC", "Up", 2009, false, abrir = "smb://c/Up.mkv")
        val b = Fonte(Origem.PASTA, "PC", "Coco", 2017, false, abrir = "smb://c/Coco.mkv")
        val m = BibliotecaIndice.mesclar(listOf(a, b), listOf(a.copy(qualidade = 1080)))
        assertEquals(2, m.size); assertEquals(1080, m[0].qualidade)
        assertEquals(1, BibliotecaIndice.mesclar(listOf(a), listOf(b), maximo = 1).size)
    }
}

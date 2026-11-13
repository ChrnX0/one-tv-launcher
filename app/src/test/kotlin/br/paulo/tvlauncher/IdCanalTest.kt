package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** player-8: id estável do canal M3U e a migração dos favoritos antigos (URL como id). */
class IdCanalTest {
    @Test fun ordemDoId() {
        assertEquals("c1", IdCanal.estavel("c1", "Nome", "G", "http://e.invalid/1"))
        assertEquals("n:canal acao|esportes", IdCanal.estavel("", "  Canal  Ação ", "Esportes", "http://e.invalid/1"))
        val u = IdCanal.estavel("", "", "G", "http://ana:senha@e.invalid/1.ts?token=abc")
        assertTrue(u.startsWith("u:"))
        assertFalse(u.contains("senha") || u.contains("abc") || IdCanal.pareceUrl(u))
        // Token novo (nos parâmetros) e usuário/senha não mudam o resumo.
        assertEquals(u, IdCanal.estavel("", "", "G", "http://e.invalid/1.ts?token=xyz"))
        // tvg-id gigante ou com cara de URL vira resumo, nunca passa de 200 letras.
        assertTrue(IdCanal.estavel("x".repeat(500), "", "", "http://e.invalid/1").length <= 200)
        assertFalse(IdCanal.pareceUrl(IdCanal.estavel("http://a.invalid/x", "", "", "http://e.invalid/1")))
        assertTrue(IdCanal.estavel("", "N".repeat(400), "", "http://e.invalid/1").length <= 200)
    }

    @Test fun urlSemSegredo() {
        assertEquals("http://e.invalid/live/1.ts", IdCanal.urlSemSegredo("HTTP://ana:s3nha@E.invalid/live/1.ts?token=1#x"))
        assertEquals("sem esquema", IdCanal.urlSemSegredo("sem esquema"))
    }

    @Test fun listaM3uNaoUsaMaisAUrl() {
        val lista = "#EXTM3U\n#EXTINF:-1 group-title=\"Notícias\",Jornal\nhttp://e.invalid/1.ts?token=abc\n" +
            "#EXTINF:-1,\nhttp://e.invalid/2.ts?token=abc\n"
        val c = M3u.ler(lista)
        assertEquals("n:jornal|noticias", c[0].id)
        assertTrue(c.none { IdCanal.pareceUrl(it.id) })
        // Mesma lista amanhã, com outro token: os ids continuam os mesmos.
        assertEquals(c.map { it.id }, M3u.ler(lista.replace("token=abc", "token=def")).map { it.id })
    }

    @Test fun cacheAntigoGanhaIdNovo() {
        val fonte = FonteTv("a1", "L", FonteTv.TIPO_M3U, "http://e.invalid/l.m3u")
        val antigo = """[{"id":"http://e.invalid/1.ts?t=1","nome":"Jornal","n":1,"g":"Notícias","url":"http://e.invalid/1.ts?t=1"},""" +
            """{"id":"http://e.invalid/2.ts","nome":"Canal 2","n":2,"g":"","url":"http://e.invalid/2.ts"}]"""
        val lidos = PlayerDados.canaisDeJson(antigo, fonte)
        assertEquals(M3u.ler("#EXTINF:-1 group-title=\"Notícias\",Jornal\nhttp://e.invalid/1.ts?t=1\n#EXTINF:-1,\nhttp://e.invalid/2.ts\n").map { it.id },
            lidos.map { it.id })
    }

    @Test fun migraFavoritosAntigos() {
        val canais = PlayerDados.juntar(listOf(FonteTv("a1", "L", FonteTv.TIPO_M3U, "http://e.invalid/l") to
            M3u.ler("#EXTINF:-1,Um\nhttp://u:p@e.invalid/1.ts?token=novo\n#EXTINF:-1 tvg-id=\"t2\",Dois\nhttp://e.invalid/2.ts\n")))
        val um = canais.first { it.nome == "Um" }.id
        // Favorito pela URL exata, pela URL com token velho, um que sumiu, um já novo e um repetido depois da troca.
        val antigos = listOf("a1:http://u:p@e.invalid/1.ts?token=novo", "a1:http://e.invalid/1.ts?token=velho",
            "a1:http://e.invalid/sumiu.ts", "a1:t2")
        assertEquals(listOf(um, "a1:t2"), IdCanal.migrar(antigos, canais))
        assertNull(IdCanal.migrar(listOf("a1:t2", um), canais))   // nada antigo: não precisa gravar
        // Conta sem canal carregado agora: não mexe (não apaga bloqueio do controle parental por engano).
        assertNull(IdCanal.migrar(listOf("b9:http://x.invalid/1"), canais))
        assertNull(IdCanal.migrar(listOf("b9:http://x.invalid/1"), canais, setOf("a1", "b9")))
        assertEquals(um, IdCanal.migrarUm("a1:http://e.invalid/1.ts", canais))
        assertNull(IdCanal.migrarUm("a1:http://e.invalid/sumiu", canais))
        assertEquals("a1:t2", IdCanal.migrarUm("a1:t2", canais))
    }

    @Test fun migraContaPorConta() {
        // Duas contas: a1 carregou, b9 está fora do ar; z0 foi apagada; um id antigo sem conta na frente.
        val canais = PlayerDados.juntar(listOf(FonteTv("a1", "L", FonteTv.TIPO_M3U, "http://e.invalid/l") to
            M3u.ler("#EXTINF:-1,Um\nhttp://e.invalid/1.ts?token=novo\n")))
        val um = canais.single().id
        val antigos = listOf("b9:http://x.invalid/1", "a1:http://e.invalid/1.ts?token=velho", "z0:http://z.invalid/1",
            "http://solto.invalid/1", "a1:t9")
        assertEquals(listOf("b9:http://x.invalid/1", um, "a1:t9"), IdCanal.migrar(antigos, canais, setOf("a1", "b9")))
        // Sem saber as contas (lista vazia): nada conta como apagada.
        assertEquals(listOf("b9:http://x.invalid/1", um, "z0:http://z.invalid/1", "http://solto.invalid/1", "a1:t9"),
            IdCanal.migrar(antigos, canais))
        assertEquals("b9:http://x.invalid/1", IdCanal.migrarUm("b9:http://x.invalid/1", canais, setOf("a1", "b9")))
        assertNull(IdCanal.migrarUm("z0:http://z.invalid/1", canais, setOf("a1", "b9")))
    }

    @Test fun nomeRepetidoNaoDependeDaOrdem() {
        val a = "#EXTINF:-1 group-title=\"G\",Jornal\nhttp://e.invalid/hd.ts?t=1\n"
        val b = "#EXTINF:-1 group-title=\"G\",Jornal\nhttp://e.invalid/sd.ts?t=1\n"
        val c1 = M3u.ler(a + b); val c2 = M3u.ler(b + a)
        assertEquals(2, c1.map { it.id }.toSet().size)
        assertEquals(c1.associate { it.url to it.id }, c2.associate { it.url to it.id })
        // tvg-id repetido segue como antes: o 1º fica com o tvg-id, o 2º ganha "#2".
        val t = M3u.ler("#EXTINF:-1 tvg-id=\"x\",A\nhttp://e.invalid/1\n#EXTINF:-1 tvg-id=\"x\",A\nhttp://e.invalid/2\n")
        assertEquals(listOf("x", "x#2"), t.map { it.id })
    }
}

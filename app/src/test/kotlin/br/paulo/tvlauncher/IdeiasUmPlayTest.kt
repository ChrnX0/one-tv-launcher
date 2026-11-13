package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.TimeZone

/**
 * Ideias vistas no 1 Play (11/11/2026): TV de volta, proporção da imagem, servidor reserva, aviso de vencimento,
 * trailer e "visto até aqui". Só lógica pura; dados inventados (servidores .invalid).
 */
class IdeiasUmPlayTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // ---- TV de volta

    @Test fun origemDosDoisFormatosXtream() {
        val a = TvDeVolta.origem("http://srv.invalid:8080/live/ana/se%26gredo/123.ts")!!
        assertEquals("http://srv.invalid:8080", a.conta.servidor); assertEquals("ana", a.conta.usuario)
        assertEquals("se&gredo", a.conta.senha); assertEquals("123", a.streamId)
        val b = TvDeVolta.origem("https://srv.invalid/ana/senha/77?x=1")!!
        assertEquals("77", b.streamId); assertEquals("https://srv.invalid", b.conta.servidor)
    }

    @Test fun origemRecusaOQueNaoEXtream() {
        assertNull(TvDeVolta.origem("http://srv.invalid/canal.m3u8"))
        assertNull(TvDeVolta.origem("http://srv.invalid/live/ana/senha/abc.ts"))
        assertNull(TvDeVolta.origem("rtmp://srv.invalid/ana/senha/1"))
        assertNull(TvDeVolta.origem("http://srv.invalid/a/b/c/d/1.ts"))
    }

    @Test fun diasComTeto() {
        assertEquals(0, TvDeVolta.dias(null)); assertEquals(0, TvDeVolta.dias("x")); assertEquals(3, TvDeVolta.dias(" 3 "))
        assertEquals(7, TvDeVolta.dias(7)); assertEquals(TvDeVolta.MAX_DIAS, TvDeVolta.dias("60")); assertEquals(0, TvDeVolta.dias(-2))
    }

    @Test fun enderecoDoArquivoNoFusoDoServidor() {
        val o = TvDeVolta.Origem(Xtream.Conta("http://srv.invalid:8080", "ana", "a b"), "9")
        val ini = 1_762_862_400_000L   // 2025-11-11 12:00 UTC
        val url = TvDeVolta.urlArquivo(o, ini, ini + 59 * 60_000 + 30_000, TimeZone.getTimeZone("America/Sao_Paulo"))
        // 59,5 min arredonda para 60; 12:00 UTC = 09:00 em São Paulo
        assertEquals("http://srv.invalid:8080/timeshift/ana/a+b/60/2025-11-11:09-00/9.ts", url)
        assertTrue(TvDeVolta.urlArquivo(o, ini, ini + 1000, utc).contains("/1/2025-11-11:12-00/"))
    }

    @Test fun fusoDoLogin() {
        val padrao = TimeZone.getTimeZone("UTC")
        assertEquals("Europe/Lisbon", TvDeVolta.fuso("""{"server_info":{"timezone":"Europe/Lisbon"}}""", padrao).id)
        assertEquals("UTC", TvDeVolta.fuso("""{"server_info":{"timezone":"Nada/Disso"}}""", padrao).id)
        assertEquals("UTC", TvDeVolta.fuso("lixo", padrao).id)
        assertEquals("UTC", TvDeVolta.fuso(null, padrao).id)
    }

    @Test fun tabelaEmBase64() {
        val json = """{"epg_listings":[
            {"title":"${b64("Jornal")}","description":"${b64("Notícias")}","start_timestamp":"1000","stop_timestamp":"2000","has_archive":1},
            {"title":"${b64("Filme")}","description":"","start_timestamp":2000,"stop_timestamp":"5000","has_archive":"0"},
            {"title":"x","start_timestamp":"9","stop_timestamp":"9"},
            {"title":"${b64("Repetido")}","start_timestamp":"1000","stop_timestamp":"1500"}]}"""
        val l = TvDeVolta.lerTabela(json)
        assertEquals(2, l.size)
        assertEquals("Jornal", l[0].titulo); assertEquals("Notícias", l[0].descricao); assertTrue(l[0].temArquivo)
        assertEquals(1_000_000L, l[0].inicio); assertEquals("Filme", l[1].titulo); assertEquals(false, l[1].temArquivo)
        assertTrue(TvDeVolta.lerTabela("{}").isEmpty()); assertTrue(TvDeVolta.lerTabela("não é json").isEmpty())
    }

    private val h = 3_600_000L
    private val agora = 100 * 86_400_000L
    private val antigo = TvDeVolta.Programa(agora - 10 * 86_400_000L, agora - 10 * 86_400_000L + h, "Antigo", "", true)
    private val ontem = TvDeVolta.Programa(agora - 86_400_000L, agora - 86_400_000L + h, "Ontem", "", true)
    private val semArquivo = TvDeVolta.Programa(agora - 3 * h, agora - 2 * h, "Sem", "", false)
    private val anterior = TvDeVolta.Programa(agora - 2 * h, agora - h / 2, "Anterior", "", true)
    private val agoraP = TvDeVolta.Programa(agora - h / 2, agora + h / 2, "Agora", "", false)
    private val futuro = TvDeVolta.Programa(agora + h / 2, agora + h, "Futuro", "", true)
    private val lista = listOf(antigo, ontem, semArquivo, anterior, agoraP, futuro)

    @Test fun assistiveisDoMaisNovoAoMaisAntigo() {
        assertEquals(listOf("Agora", "Anterior", "Ontem"), TvDeVolta.assistiveis(lista, agora, 3).map { it.titulo })
        assertEquals(listOf("Agora", "Anterior", "Ontem", "Antigo"), TvDeVolta.assistiveis(lista, agora, 14).map { it.titulo })
        assertTrue(TvDeVolta.passandoAgora(agoraP, agora)); assertEquals(false, TvDeVolta.passandoAgora(anterior, agora))
    }

    @Test fun seguintePulaOQueNaoTemArquivoEVoltaAoVivo() {
        assertEquals("Agora", TvDeVolta.seguinte(lista, anterior, agora)?.titulo)
        assertNull(TvDeVolta.seguinte(lista, agoraP, agora))   // depois do que está passando = ao vivo
    }

    // ---- proporção da imagem

    @Test fun proporcaoAjustarEsticarZoom() {
        // tela 16:9, vídeo 4:3
        assertEquals(1440 to 1080, PlayerImagem.tamanho(PlayerImagem.Modo.AJUSTAR, 1920, 1080, 640, 480))
        assertEquals(1920 to 1440, PlayerImagem.tamanho(PlayerImagem.Modo.ZOOM, 1920, 1080, 640, 480))
        assertEquals(1920 to 1080, PlayerImagem.tamanho(PlayerImagem.Modo.ESTICAR, 1920, 1080, 640, 480))
        // vídeo 2,39:1 (cinema)
        assertEquals(1920 to 803, PlayerImagem.tamanho(PlayerImagem.Modo.AJUSTAR, 1920, 1080, 1920, 803))
        // anamórfico: 720x576 com pixel 16:11 ≈ 1,82
        val (l, a) = PlayerImagem.tamanho(PlayerImagem.Modo.AJUSTAR, 1920, 1080, 720, 576, 16f / 11f)
        assertEquals(1920, l); assertTrue(a in 1050..1060)
        // vídeo sem tamanho ainda = tela inteira
        assertEquals(1920 to 1080, PlayerImagem.tamanho(PlayerImagem.Modo.AJUSTAR, 1920, 1080, 0, 0))
    }

    @Test fun rotuloDaVelocidade() {
        assertEquals("Normal", PlayerImagem.rotuloVelocidade(1f)); assertEquals("2x", PlayerImagem.rotuloVelocidade(2f))
        assertEquals("1,25x", PlayerImagem.rotuloVelocidade(1.25f)); assertEquals("0,5x", PlayerImagem.rotuloVelocidade(0.5f))
    }

    // ---- servidor reserva

    @Test fun reservasNormalizadasSemRepetirOPrincipal() {
        val r = PlayerDados.lerReservas("srv2.invalid:8080, http://srv.invalid:8080/ ; srv3.invalid/get.php", "http://srv.invalid:8080").getOrThrow()
        assertEquals(listOf("http://srv2.invalid:8080", "http://srv3.invalid"), r)
        assertTrue(PlayerDados.lerReservas("", "http://a.invalid").getOrThrow().isEmpty())
        assertTrue(PlayerDados.lerReservas("ftp://x.invalid", "http://a.invalid").isFailure)
        assertTrue(PlayerDados.lerReservas("a.invalid b.invalid c.invalid d.invalid", "http://z.invalid").isFailure)
    }

    @Test fun reservasSobrevivemAoJson() {
        val f = PlayerDados.criar("id1", "Casa", FonteTv.TIPO_XTREAM, "srv.invalid:8080", "ana", "s", reservas = "srv2.invalid:8080").getOrThrow()
        assertEquals(listOf("http://srv2.invalid:8080"), f.reservas)
        val volta = PlayerDados.fontesDeJson(PlayerDados.fontesParaJson(listOf(f)))
        assertEquals(f.reservas, volta.single().reservas)
    }

    @Test fun diasDoArquivoNoCacheDeCanais() {
        val f = FonteTv("a", "A", FonteTv.TIPO_M3U, "http://l.invalid/x.m3u")
        val c = Canal("1", "Um", 1, url = "http://l.invalid/ana/s/1", arquivoDias = 5)
        assertEquals(5, PlayerDados.canaisDeJson(PlayerDados.canaisParaJson(listOf(c), f), f).single().arquivoDias)
    }

    @Test fun m3uLeCatchupDays() {
        val l = M3u.ler("""#EXTM3U
#EXTINF:-1 tvg-id="a" catchup-days="3",Canal A
http://l.invalid/ana/s/1.ts
#EXTINF:-1 tvg-id="b",Canal B
http://l.invalid/ana/s/2.ts""".lineSequence())
        assertEquals(listOf(3, 0), l.map { it.arquivoDias })
    }

    // ---- aviso de vencimento na HOME

    @Test fun avisoDeVencimento() {
        val dia = 86_400_000L
        val agoraMs = 1000 * dia + 10 * 3_600_000L   // 10h da manhã em UTC
        fun em(d: Long) = (agoraMs + d * dia) / 1000
        assertNull(ContaIptv.avisoVencimento(emptyList(), agoraMs, utc))
        assertNull(ContaIptv.avisoVencimento(listOf("Casa" to em(20)), agoraMs, utc))
        assertEquals("Casa vence em 5 dias", ContaIptv.avisoVencimento(listOf("Casa" to em(5)), agoraMs, utc))
        assertEquals("Casa vence amanhã", ContaIptv.avisoVencimento(listOf("Casa" to em(1)), agoraMs, utc))
        assertEquals("Casa vence hoje", ContaIptv.avisoVencimento(listOf("Casa" to (agoraMs + 3_600_000L) / 1000), agoraMs, utc))
        assertEquals("Casa venceu", ContaIptv.avisoVencimento(listOf("Casa" to em(-2)), agoraMs, utc))
        assertNull(ContaIptv.avisoVencimento(listOf("Casa" to em(-40)), agoraMs, utc))
        // várias contas: avisa a que vence primeiro
        assertEquals("B vence em 2 dias", ContaIptv.avisoVencimento(listOf("A" to em(6), "B" to em(2)), agoraMs, utc))
        // lista abandonada (venceu há 40 dias) não esconde o aviso da que vence amanhã
        assertEquals("B vence amanhã", ContaIptv.avisoVencimento(listOf("Velha" to em(-40), "B" to em(1)), agoraMs, utc))
        assertNull(ContaIptv.avisoVencimento(listOf("Velha" to em(-40), "B" to em(20)), agoraMs, utc))
    }

    // ---- trailer e "visto até aqui"

    @Test fun linkDoTrailer() {
        assertEquals("https://www.youtube.com/watch?v=abcDEF12_-x", Vod.linkTrailer("abcDEF12_-x"))
        assertEquals("https://www.youtube.com/watch?v=abcDEF12_-x", Vod.linkTrailer("https://youtu.be/abcDEF12_-x"))
        assertEquals("https://www.youtube.com/watch?v=abcDEF12_-x", Vod.linkTrailer("https://www.youtube.com/watch?feature=x&v=abcDEF12_-x"))
        assertEquals("", Vod.linkTrailer("")); assertEquals("", Vod.linkTrailer("https://mal.invalid/watch?v=abcDEF12_-x"))
        assertEquals("", Vod.linkTrailer("curto"))
    }

    @Test fun vistoAteAquiMarcaOsAnteriores() {
        val t1 = listOf(Episodio("1", 1, 1, "a", duracaoMin = 40), Episodio("2", 1, 2, "b"))
        val t2 = listOf(Episodio("3", 2, 1, "c"), Episodio("4", 2, 2, "d"))
        val f = FichaSerie(temporadas = linkedMapOf(1 to t1, 2 to t2))
        val antes = mapOf(t1[1].chave("x") to Posicao(5_000, 3_000_000, 1))
        val p = Series.marcarVistosAte(f, "x", t2[0], antes, 99)
        assertEquals(3, p.size)
        assertEquals(Posicao(2_400_000, 2_400_000, 99), p[t1[0].chave("x")])
        assertEquals(Posicao(3_000_000, 3_000_000, 99), p[t1[1].chave("x")])   // usa a duração conhecida
        assertNotNull(p[t2[0].chave("x")]); assertNull(p[t2[1].chave("x")])
        assertEquals(2, Series.desmarcar("x", t2[0], p).size)
        assertEquals(antes, Series.marcarVistosAte(f, "x", Episodio("99", 9, 9, "?"), antes, 99))
    }
}

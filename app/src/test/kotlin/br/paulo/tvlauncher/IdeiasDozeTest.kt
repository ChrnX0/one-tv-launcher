package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Calendar
import java.util.TimeZone

/** Regras puras das ideias de 12/11/2026 (lembretes, Web Push, desempenho, sono suave, presença, mural, semana, busca, modo simples). */
class IdeiasDozeTest {
    private val h = 3_600_000L
    private val agora = 1_800_000_000_000L

    private fun prog(titulo: String, inicio: Long, fim: Long = inicio + 2 * h, guia: String = "g") = Programa(guia, inicio, fim, titulo)
    private fun cand(id: String, numero: Int, p: Programa) = Lembretes.Candidato(id, "Canal $numero", numero, p)

    // ---------------------------------------------------------------- 1. lembretes

    @Test fun palavraInteiraSemAcento() {
        assertTrue(Lembretes.casa("Palmeiras x Santos", "palmeiras"))
        assertTrue(Lembretes.casa("GP de São Paulo de Fórmula 1", "formula 1"))
        assertFalse(Lembretes.casa("Palmeirense na TV", "palmeiras"))
        assertFalse(Lembretes.casa("Fórmula 10 anos", "formula 1"))
        assertFalse("palavra curta demais não casa nada", Lembretes.casa("Flamengo", "fl"))
    }

    @Test fun lerPalavrasLimpaERepetidas() {
        assertEquals(listOf("Flamengo", "Fórmula 1"), Lembretes.lerPalavras("Flamengo, Fórmula  1\nflamengo; F1"))
    }

    @Test fun gerarJuntaOMesmoJogoNoCanalDeMenorNumero() {
        val jogo = agora + 3 * h
        val l = Lembretes.gerar(emptyList(), sequenceOf(
            cand("a", 30, prog("Flamengo x Vasco", jogo)), cand("b", 5, prog("Flamengo x Vasco", jogo)),
            cand("c", 7, prog("Novela", jogo)),
            cand("d", 8, prog("Flamengo x Bahia", agora + 8 * 24 * h)),   // passa do horizonte de 7 dias (ideia 16)
            cand("e", 9, prog("Flamengo ao vivo", agora + 60_000L)),  // começa antes da antecedência
        ), listOf("Flamengo"), agora)
        assertEquals(1, l.size)
        assertEquals("b", l[0].canalId)
        assertEquals("Flamengo", l[0].palavra)
    }

    @Test fun gerarMantemManualEAvisado() {
        val jogo = agora + 3 * h
        val manual = Lembretes.Lembrete("x", "X", "Filme", agora + 5 * h, agora + 7 * h)
        val antes = listOf(manual, Lembretes.Lembrete("b", "Canal 5", "Flamengo x Vasco", jogo, jogo + 2 * h, "Flamengo", avisado = true))
        val l = Lembretes.gerar(antes, sequenceOf(cand("b", 5, prog("Flamengo x Vasco", jogo))), listOf("Flamengo"), agora)
        assertEquals(2, l.size)
        assertTrue(l.first { it.canalId == "b" }.avisado)
        assertTrue(l.any { it.manual && it.canalId == "x" })
        // Sem palavras: só os manuais ficam.
        assertEquals(listOf(manual), Lembretes.gerar(antes, emptySequence(), emptyList(), agora))
    }

    @Test fun gerarLimitaPorPalavra() {
        val c = (1..10).map { cand("c$it", it, prog("Flamengo jogo $it", agora + it * h)) }.asSequence()
        assertEquals(Lembretes.MAX_POR_PALAVRA, Lembretes.gerar(emptyList(), c, listOf("Flamengo"), agora).size)
    }

    @Test fun alternarMarcaEDesmarca() {
        val l = Lembretes.Lembrete("a", "A", "Jogo", agora + h, agora + 3 * h, "Flamengo")
        val (um, marcou) = Lembretes.alternar(emptyList(), l)
        assertTrue(marcou); assertTrue(um[0].manual)
        val (zero, marcou2) = Lembretes.alternar(um, l)
        assertFalse(marcou2); assertTrue(zero.isEmpty())
    }

    @Test fun devidosEProximoAviso() {
        val l = listOf(
            Lembretes.Lembrete("a", "A", "Já", agora + 60_000L, agora + h),
            Lembretes.Lembrete("b", "B", "Depois", agora + h, agora + 2 * h),
            Lembretes.Lembrete("c", "C", "Avisado", agora, agora + h, avisado = true),
        )
        assertEquals(listOf("a"), Lembretes.devidos(l, agora).map { it.canalId })
        val marcados = Lembretes.marcarAvisados(l, setOf(l[0].chave))
        assertEquals(agora + h - Lembretes.ANTECEDENCIA_MS, Lembretes.proximoAviso(marcados, agora))
        assertNull(Lembretes.proximoAviso(Lembretes.marcarAvisados(l, l.map { it.chave }.toSet()), agora))
    }

    @Test fun lembretesIdaEVoltaEmJson() {
        val l = listOf(Lembretes.Lembrete("a", "A", "Jogo \"x\"", agora, agora + h, "Flamengo", avisado = true))
        assertEquals(l, Lembretes.deJson(Lembretes.paraJson(l)))
        assertTrue(Lembretes.deJson("lixo").isEmpty())
    }

    // ---------------------------------------------------------------- 3. Web Push

    private fun b(s: String) = WebPush.deB64url(s)

    /** Vetor de teste do RFC 8291, apêndice A: prova que o celular de verdade consegue abrir o que a TV cifra. */
    @Test fun webPushBateComORfc8291() {
        val asPublica = b("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
        val asPrivada = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(
            BigInteger(1, b("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")), (WebPush.deCru(asPublica) as ECPublicKey).params))
        val corpo = WebPush.cifrar("When I grow up, I want to be a watermelon".toByteArray(),
            b("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            b("BTBZMqHH6r4Tts7J_aSIgg"), KeyPair(WebPush.deCru(asPublica), asPrivada), b("DGv6ra1nlYgDCS1FRnbzlw"))
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_" +
            "yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN", WebPush.b64url(corpo))
    }

    private fun parP256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun webPushIdaEVolta() {
        val ua = parP256()
        val auth = ByteArray(16) { it.toByte() }
        val msg = """{"titulo":"Lembrete","texto":"Flamengo x Vasco começa às 21:30"}""".toByteArray()
        val corpo = WebPush.cifrar(msg, WebPush.cru(ua.public), auth, parP256(), ByteArray(16) { 7 })
        assertArrayEquals(msg, WebPush.decifrar(corpo, ua.private, WebPush.cru(ua.public), auth))
        assertArrayEquals(WebPush.cru(ua.public), WebPush.cru(WebPush.deCru(WebPush.cru(ua.public))))
    }

    @Test fun webPushSoServicosConhecidos() {
        assertTrue(WebPush.hostAceito("https://fcm.googleapis.com/fcm/send/abc"))
        assertTrue(WebPush.hostAceito("https://wns2-bl2p.notify.windows.com/w/?token=x"))
        assertFalse(WebPush.hostAceito("http://fcm.googleapis.com/fcm/send/abc"))
        assertFalse(WebPush.hostAceito("https://fcm.googleapis.com:8443/x"))
        assertFalse(WebPush.hostAceito("https://192.168.1.1/x"))
        assertFalse(WebPush.hostAceito("https://fcm.googleapis.com.mal.com/x"))
    }

    @Test fun vapidAssinaturaConfere() {
        val par = parP256()
        val aut = WebPush.autorizacao("https://fcm.googleapis.com/fcm/send/abc", WebPush.cru(par.public), 1_000L,
            "https://github.com/ChrnX0/one-tv-launcher") { dado ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(par.private); update(dado) }.sign()
        }
        val m = Regex("""^vapid t=([^.]+)\.([^.]+)\.([^,]+), k=(.+)$""").find(aut)
        assertNotNull(m)
        val (cab, corpo, ass, k) = m!!.destructured
        assertEquals(WebPush.b64url(WebPush.cru(par.public)), k)
        val claims = org.json.JSONObject(String(WebPush.deB64url(corpo)))
        assertEquals("https://fcm.googleapis.com", claims.getString("aud"))
        assertEquals(1_000L + 12 * 3600, claims.getLong("exp"))
        val cru = WebPush.deB64url(ass)
        assertEquals(64, cru.size)
        assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(par.public); update("$cab.$corpo".toByteArray()) }.verify(cru))
    }

    // ---------------------------------------------------------------- 4. desempenho

    private fun medida(ms: Int, n: Int) = Desempenho.Medida().apply { repeat(n) { quadro(ms.toDouble()) } }

    @Test fun desempenhoPrecisaDeQuadrosEDetectaPiora() {
        assertNull(Desempenho.comparar(medida(10, 100), medida(20, 5000)))
        val c = Desempenho.comparar(medida(10, 2000), medida(20, 2000))!!
        assertTrue(c.piorou); assertEquals(10, c.p90Antes); assertEquals(20, c.p90Agora)
        assertFalse("2 ms a mais não conta", Desempenho.comparar(medida(10, 2000), medida(12, 2000))!!.piorou)
        assertFalse(Desempenho.comparar(medida(20, 2000), medida(10, 2000))!!.piorou)
    }

    @Test fun desempenhoJsonEsparso() {
        val m = medida(16, 30).apply { quadro(900.0); abertura(420) }
        val volta = Desempenho.deJson(Desempenho.paraJson(m))
        assertEquals(31, volta.total); assertEquals(1, volta.baldes[Desempenho.MAX_MS]); assertEquals(listOf(420), volta.aberturas)
        assertEquals(16, Desempenho.percentil(volta, 50))
    }

    // ---------------------------------------------------------------- 5. desligar suave

    @Test fun sonoSuaveDesceSemZerar() {
        assertEquals(0.5, SonoSuave.fracao(50, 0, 100), 1e-9)
        assertEquals(1.0, SonoSuave.fracao(500, 0, 100), 1e-9)
        assertEquals(20, SonoSuave.volume(20, 0.0))
        assertEquals(6, SonoSuave.volume(20, 1.0))
        assertEquals(1, SonoSuave.volume(1, 1.0))
        assertEquals(0, SonoSuave.volume(0, 1.0))
        assertEquals(0, SonoSuave.alfa(0.0)); assertEquals((0.65 * 255).toInt(), SonoSuave.alfa(1.0))
    }

    // ---------------------------------------------------------------- 7. presença

    @Test fun presencaSoDesligaComTodosForaETelaParada() {
        val esc = setOf("aa:bb:cc:dd:ee:ff")
        assertFalse(Presenca.decidir(null, esc, agora - h, agora, 60, true).desligar)   // roteador sem resposta
        assertNull(Presenca.decidir(setOf("aa:bb:cc:dd:ee:ff"), esc, agora - h, agora, 60, true).ausenteDesde)
        val comeco = Presenca.decidir(emptySet(), esc, null, agora, 60, true)
        assertEquals(agora, comeco.ausenteDesde); assertFalse(comeco.desligar)
        val depois = agora + 16 * 60_000L
        assertTrue(Presenca.decidir(emptySet(), esc, agora, depois, 20, true).desligar)
        assertFalse("alguém mexeu no controle", Presenca.decidir(emptySet(), esc, agora, depois, 3, true).desligar)
        assertFalse("tela já apagada", Presenca.decidir(emptySet(), esc, agora, depois, 20, false).desligar)
        assertFalse("ninguém escolhido", Presenca.decidir(emptySet(), emptySet(), agora, depois, 20, true).desligar)
    }

    @Test fun presencaLeMacs() {
        assertEquals(setOf("aa:bb:cc:dd:ee:ff", "01:23:45:67:89:ab"), Presenca.lerMacs("AA:BB:CC:DD:EE:FF, 01:23:45:67:89:ab;lixo aa:bb"))
    }

    // ---------------------------------------------------------------- 6. mural

    @Test fun muralLimitesERepetidas() {
        var c = Mural.Conteudo()
        c = Mural.adicionar(c, Mural.Lista.COMPRAS, "  leite \n")
        c = Mural.adicionar(c, Mural.Lista.COMPRAS, "LEITE")
        assertEquals(listOf("leite"), c.compras)
        repeat(5) { c = Mural.adicionar(c, Mural.Lista.RECADOS, "recado $it") }
        assertEquals(Mural.MAX_RECADOS, c.recados.size); assertEquals("recado 4", c.recados.last())
        assertEquals(Mural.MAX_TEXTO, Mural.limpar("x".repeat(200)).length)
        c = Mural.remover(c, Mural.Lista.COMPRAS, "Leite")
        assertTrue(c.compras.isEmpty())
        assertEquals(c, Mural.deJson(Mural.paraJson(c).toString()))
        assertTrue(Mural.eAniversario("Aniversário de Ana")); assertTrue(Mural.eAniversario("Ana's birthday"))
        assertEquals(Mural.Lista.COMPRAS, Mural.lista("mercado")); assertNull(Mural.lista("outra"))
    }

    // ---------------------------------------------------------------- 9. semana

    @Test fun proximoDomingoAsVinte() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"))
        cal.set(2026, Calendar.NOVEMBER, 12, 10, 0, 0)   // quinta
        val d = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")).apply {
            timeInMillis = Retrospectiva.proximoDomingo(cal.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")))
        }
        assertEquals(Calendar.SUNDAY, d.get(Calendar.DAY_OF_WEEK)); assertEquals(15, d.get(Calendar.DAY_OF_MONTH))
        assertEquals(Retrospectiva.HORA, d.get(Calendar.HOUR_OF_DAY))
        // Domingo 20h em ponto: o próximo é o da semana seguinte.
        assertEquals(d.timeInMillis + 7 * 24 * h, Retrospectiva.proximoDomingo(d.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"))))
    }

    @Test fun resumoDaSemanaSemOLauncher() {
        val r = Retrospectiva.resumir(0, 1, mapOf("eu" to 50 * h, "yt" to 5 * h, "nf" to 3 * h, "x" to 30_000L, "g" to 60 * 60_000L),
            { it.uppercase() }, "eu", null, 1, 3)
        assertEquals(listOf("YT", "NF", "G"), r.apps.map { it.nome })
        assertEquals(9 * 60L, r.minutosTv)
        val (cima, baixo) = Retrospectiva.linhas(r)
        assertTrue(cima, cima.startsWith("9 h de TV · YT 5 h"))
        assertEquals("1 queda (3 min)", baixo)
        assertEquals(r, Retrospectiva.deJson(Retrospectiva.paraJson(r).toString()))
    }

    // ---------------------------------------------------------------- 2 e 8. busca e Hoje na TV

    @Test fun buscaOrdenaPeloMelhor() {
        val nomes = listOf("Batman Begins", "O Retorno do Batman", "Batman", "Superman", "Batmóvel: a história")
        // Igual ao termo, depois começa com ele (o mais curto primeiro), depois palavra que começa com ele.
        assertEquals(listOf("Batman", "Batman Begins", "Batmóvel: a história", "O Retorno do Batman"), BuscaUnica.filtrar(nomes, "bat") { it })
        assertEquals(listOf("Batman", "Batman Begins", "O Retorno do Batman"), BuscaUnica.filtrar(nomes, "BATMAN") { it })
        assertEquals(listOf("O Retorno do Batman"), BuscaUnica.filtrar(nomes, "retorno batman") { it })
        assertTrue(BuscaUnica.filtrar(nomes, "  ") { it }.isEmpty())
        assertEquals(3, BuscaUnica.contar(nomes, "batman") { it })
        assertEquals(2, BuscaUnica.filtrar(nomes, "bat", max = 2) { it }.size)
    }

    @Test fun hojeNaTvSoComMotivo() {
        val itens = sequenceOf(
            cand("fav", 2, prog("Jornal", agora - h)),                    // passando, canal favorito
            cand("rec", 9, prog("Filme da tarde", agora + h)),           // canal recente
            cand("x", 4, prog("Flamengo x Vasco", agora + 2 * h)),        // Meu time
            cand("y", 1, prog("Flamengo x Vasco", agora + 2 * h)),        // mesmo jogo, número menor
            cand("z", 5, prog("Novela", agora)),                         // sem motivo
            cand("fav", 2, prog("Tarde demais", agora + 4 * h)),          // fora da janela de 3 h
            cand("fav", 2, prog("Acabou", agora - 3 * h, agora - h)),     // já acabou
        )
        val r = BuscaUnica.hojeNaTv(itens, agora, listOf("Flamengo"), setOf("fav"), listOf("rec"))
        assertEquals(listOf("Flamengo x Vasco", "Jornal", "Filme da tarde"), r.map { it.programa.titulo })
        assertEquals("y", r[0].canalId); assertEquals("Meu time", r[0].motivo)
        assertEquals("Canal favorito", r[1].motivo); assertEquals("Você assistiu", r[2].motivo)
    }

    // ---------------------------------------------------------------- 10. modo simples

    @Test fun modoSimplesEscolheEComplete() {
        val inst = setOf("a.b", "c.d", "e.f", "g.h")
        assertEquals(listOf("c.d", "a.b", "e.f"), ModoSimples.escolher(listOf("c.d", "x.y"), inst, listOf("a.b", "c.d", "e.f", "g.h"), 3))
        assertTrue(ModoSimples.escolher(emptyList(), inst, emptyList(), 0).isEmpty())
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv"),
            ModoSimples.lerLista("com.netflix.ninja, ; rm -rf\ncom.google.android.youtube.tv,com.netflix.ninja"))
        // Ligar o modo simples é livre; desligar pede o PIN (quem está usando não sai sem querer).
        assertFalse(Extras.precisaPinNaTv(Extras.Chave.MODO_SIMPLES, ligadoAgora = false, temPin = true))
        assertTrue(Extras.precisaPinNaTv(Extras.Chave.MODO_SIMPLES, ligadoAgora = true, temPin = true))
    }
}

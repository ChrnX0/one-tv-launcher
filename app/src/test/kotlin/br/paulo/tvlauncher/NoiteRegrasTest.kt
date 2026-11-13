package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras da 2ª leva de ideias (NoiteRegras.kt). */
class NoiteRegrasTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min

    // ---------------- hora de dormir

    @Test fun janelaQueViraAMeiaNoite() {
        val i = m(21); val f = m(6, 30)
        assertTrue(HoraDormir.dentro(m(21), i, f))        // começa na hora exata
        assertTrue(HoraDormir.dentro(m(23, 59), i, f))
        assertTrue(HoraDormir.dentro(m(0), i, f))
        assertTrue(HoraDormir.dentro(m(6, 29), i, f))
        assertFalse(HoraDormir.dentro(m(6, 30), i, f))    // termina na hora exata
        assertFalse(HoraDormir.dentro(m(12), i, f))
        assertFalse(HoraDormir.dentro(m(20, 59), i, f))
    }

    @Test fun janelaNoMesmoDiaEJanelaVazia() {
        assertTrue(HoraDormir.dentro(m(14), m(13), m(15)))
        assertFalse(HoraDormir.dentro(m(15), m(13), m(15)))
        assertFalse(HoraDormir.dentro(m(22), m(22), m(22)))   // início == fim = desligada
    }

    @Test fun bloqueiaSoForaDaListaENoHorario() {
        val ok = setOf("com.netflix.ninja")
        assertTrue(HoraDormir.bloqueia(true, m(22), m(21), m(6), "com.google.android.youtube.tv", ok))
        assertFalse(HoraDormir.bloqueia(true, m(22), m(21), m(6), "com.netflix.ninja", ok))
        assertFalse(HoraDormir.bloqueia(false, m(22), m(21), m(6), "com.google.android.youtube.tv", ok))   // chave desligada
        assertFalse(HoraDormir.bloqueia(true, m(10), m(21), m(6), "com.google.android.youtube.tv", ok))     // fora do horário
        assertFalse(HoraDormir.bloqueia(true, m(22), m(21), m(6), null, ok))
        for (p in HoraDormir.ESSENCIAIS) assertFalse(p, HoraDormir.bloqueia(true, m(22), m(21), m(6), p, emptySet()))
    }

    @Test fun listaDeAppsDigitada() {
        assertEquals(setOf("com.netflix.ninja", "com.disney.disneyplus"),
            HoraDormir.lerPermitidos(" com.netflix.ninja,com.disney.disneyplus\ncom.netflix.ninja ; lixo ; 1abc.x ; ; a.b'c"))
        assertEquals(emptySet<String>(), HoraDormir.lerPermitidos(null))
    }

    @Test fun tetoDoVolume() {
        // 15 passos, teto 30% = 4 passos: de 3 pode subir para 4; de 4 não sobe.
        assertFalse(HoraDormir.passaDoTeto(3, 15, 30))
        assertTrue(HoraDormir.passaDoTeto(4, 15, 30))
        assertTrue(HoraDormir.passaDoTeto(0, 15, 0))
        assertFalse(HoraDormir.passaDoTeto(14, 15, 100))
        assertFalse(HoraDormir.passaDoTeto(5, 0, 30))      // aparelho sem passos: não mexe
        assertFalse(HoraDormir.passaDoTeto(14, 15, 250))   // teto acima de 100 vale 100
    }

    // ---------------- consumo

    @Test fun somaPorAppMaiorPrimeiro() {
        val nomes = mapOf(10100 to "Netflix", 10200 to "YouTube")
        val r = ConsumoRede.somar(listOf(
            ConsumoRede.Uso(10100, 500), ConsumoRede.Uso(10200, 300), ConsumoRede.Uso(10100, 700),
            ConsumoRede.Uso(10300, 50), ConsumoRede.Uso(10400, 0)), { nomes[it] })
        assertEquals(listOf("Netflix" to 1200L, "YouTube" to 300L, "uid 10300" to 50L), r.map { it.nome to it.bytes })
        assertEquals(1, ConsumoRede.somar(listOf(ConsumoRede.Uso(1, 5), ConsumoRede.Uso(2, 9)), { null }, limite = 1).size)
    }

    @Test fun textoDoTamanho() {
        assertEquals("0 B", ConsumoRede.texto(0))
        assertEquals("999 B", ConsumoRede.texto(999))
        assertEquals("950 KB", ConsumoRede.texto(950_400))
        assertEquals("12,3 MB", ConsumoRede.texto(12_340_000))
        assertEquals("1,25 GB", ConsumoRede.texto(1_250_000_000))
    }

    // ---------------- tocando agora

    @Test fun escolheATocandoMaisRecente() {
        val a = TocandoAgora.Sessao("a", true, "Filme", 100)
        val b = TocandoAgora.Sessao("b", true, "Música", 200)
        val c = TocandoAgora.Sessao("c", false, "Pausado", 300)
        assertEquals("b", TocandoAgora.escolher(listOf(a, b, c))?.pacote)
        assertEquals("c", TocandoAgora.escolher(listOf(c, TocandoAgora.Sessao("d", false, "", 400)))?.pacote)
        assertNull(TocandoAgora.escolher(listOf(TocandoAgora.Sessao("d", false, " ", 400))))
        assertNull(TocandoAgora.escolher(emptyList()))
    }

    // ---------------- recado por link

    @Test fun recadoNoFormatoTvOverlay() {
        val p = RecadoLink.ler("""{"title":"Casa","message":"Jantar pronto!","duration":15}""")!!
        assertEquals(RecadoLink.Pedido("Casa", "Jantar pronto!", 15), p)
        val q = RecadoLink.ler("""{"titulo":"Mãe","texto":"Desce","segundos":1}""")!!
        assertEquals(RecadoLink.Pedido("Mãe", "Desce", RecadoLink.MIN_SEGUNDOS), q)   // segundos presos no mínimo
        assertEquals(RecadoLink.MAX_SEGUNDOS, RecadoLink.ler("""{"message":"x","duration":99999}""")!!.segundos)
        assertEquals(10, RecadoLink.ler("""{"message":"x"}""")!!.segundos)
        // Só o título: vira o texto.
        assertEquals(RecadoLink.Pedido("", "Alerta", 10), RecadoLink.ler("""{"title":"Alerta"}"""))
    }

    @Test fun recadoInvalido() {
        assertNull(RecadoLink.ler(null))
        assertNull(RecadoLink.ler("não é json"))
        assertNull(RecadoLink.ler("{}"))
        assertNull(RecadoLink.ler("""{"message":"   "}"""))
    }

    @Test fun tokenDoLink() {
        val t = Cenas.gerarToken()
        val guardado = Cenas.resumo(t)
        assertTrue(RecadoLink.confere(t, guardado))
        assertFalse(RecadoLink.confere(Cenas.gerarToken(), guardado))
        assertFalse(RecadoLink.confere(t, ""))                     // sem link gerado: nada passa
        assertFalse(RecadoLink.confere(t.uppercase(), guardado))   // formato estrito
        assertFalse(RecadoLink.confere("../$t", guardado))
    }

    // ---------------- modo leve

    @Test fun guardaEVoltaAsAnimacoes() {
        val texto = ModoLeve.guardar(mapOf("window_animation_scale" to 0.5f, "transition_animation_scale" to null))
        val o = JSONObject(texto)
        assertEquals(0.5, o.getDouble("window_animation_scale"), 1e-9)
        assertEquals(1.0, o.getDouble("transition_animation_scale"), 1e-9)   // não definido = padrão 1.0
        assertEquals(1.0, o.getDouble("animator_duration_scale"), 1e-9)
        val volta = ModoLeve.ler(texto)
        assertEquals(0.5f, volta["window_animation_scale"])
        assertEquals(1f, volta["animator_duration_scale"])
    }

    @Test fun textoEstragadoVoltaAoPadrao() {
        assertEquals(ModoLeve.CHAVES.associateWith { 1f }, ModoLeve.ler(null))
        assertEquals(ModoLeve.CHAVES.associateWith { 1f }, ModoLeve.ler("{lixo"))
        assertEquals(1f, ModoLeve.ler("""{"window_animation_scale":-3}""")["window_animation_scale"])
    }

    // ---------------- som de navegação

    @Test fun somDasTeclas() {
        assertEquals(SomNavegacao.Som.CIMA, SomNavegacao.som(19))
        assertEquals(SomNavegacao.Som.DIREITA, SomNavegacao.som(22))
        assertEquals(SomNavegacao.Som.CLIQUE, SomNavegacao.som(23))
        assertEquals(SomNavegacao.Som.CLIQUE, SomNavegacao.som(66))
        assertNull(SomNavegacao.som(4))    // VOLTAR
        assertNull(SomNavegacao.som(24))   // VOLUME +
    }

    // ---------------- hora de dormir por dia

    private val padrao = HoraDormir.Janela(21 * 60, 6 * 60 + 30)
    private val DOM = 0; private val SEX = 5; private val SAB = 6

    @Test fun semNadaPorDiaValeOPadraoTodoDia() {
        val dias = HoraDormir.lerDias(null)
        assertEquals(7, dias.size)
        for (d in 0 until 7) {
            assertTrue(HoraDormir.dentroNoDia(d, m(22), dias, padrao))
            assertTrue(HoraDormir.dentroNoDia(d, m(3), dias, padrao))       // sobra da noite anterior
            assertFalse(HoraDormir.dentroNoDia(d, m(6, 30), dias, padrao))
            assertFalse(HoraDormir.dentroNoDia(d, m(15), dias, padrao))
        }
    }

    @Test fun sextaMaisTardeViraAMeiaNoiteParaOSabado() {
        // Sexta e sábado: 23:00 até 09:00. O resto segue o padrão (21:00 até 06:30).
        val dias = MutableList<HoraDormir.Janela?>(7) { null }
        dias[SEX] = HoraDormir.Janela(m(23), m(9)); dias[SAB] = HoraDormir.Janela(m(23), m(9))
        assertTrue(HoraDormir.dentroNoDia(SEX - 1, m(22), dias, padrao))   // quinta 22h: padrão
        assertTrue(HoraDormir.dentroNoDia(SEX, m(5), dias, padrao))        // sexta 5h: sobra da quinta (até 6:30)
        assertFalse(HoraDormir.dentroNoDia(SEX, m(7), dias, padrao))       // sexta 7h: a quinta já acabou
        assertFalse(HoraDormir.dentroNoDia(SEX, m(22), dias, padrao))      // sexta 22h: livre até 23h
        assertTrue(HoraDormir.dentroNoDia(SEX, m(23), dias, padrao))
        assertTrue(HoraDormir.dentroNoDia(SAB, m(8, 59), dias, padrao))    // sábado 8:59: sobra da sexta
        assertFalse(HoraDormir.dentroNoDia(SAB, m(9), dias, padrao))
        assertTrue(HoraDormir.dentroNoDia(DOM, m(8), dias, padrao))        // domingo 8h: sobra do sábado
        assertTrue(HoraDormir.dentroNoDia(DOM, m(21), dias, padrao))       // domingo à noite volta ao padrão
        assertFalse(HoraDormir.dentroNoDia(1, m(7), dias, padrao))         // segunda 7h: a do domingo acabou 6:30
    }

    @Test fun diaLivreEJanelaNoMesmoDia() {
        val dias = MutableList<HoraDormir.Janela?>(7) { null }
        dias[SAB] = HoraDormir.Janela(m(0), m(0))                             // sábado livre
        assertTrue(HoraDormir.dentroNoDia(SAB, m(3), dias, padrao))          // mas a sobra da sexta vale
        assertFalse(HoraDormir.dentroNoDia(SAB, m(23), dias, padrao))
        assertFalse(HoraDormir.dentroNoDia(DOM, m(3), dias, padrao))         // sábado livre: nada sobra no domingo
        dias[DOM] = HoraDormir.Janela(m(13), m(15))                          // soneca de domingo
        assertTrue(HoraDormir.dentroNoDia(DOM, m(14), dias, padrao))
        assertFalse(HoraDormir.dentroNoDia(DOM, m(21), dias, padrao))
        assertFalse(HoraDormir.dentroNoDia(1, m(3), dias, padrao))           // janela do domingo não vira a noite
    }

    @Test fun guardaELeOsDias() {
        val dias = listOf(null, HoraDormir.Janela(1260, 390), null, null, null, HoraDormir.Janela(1380, 540), HoraDormir.Janela(0, 0))
        val texto = HoraDormir.escreverDias(dias)
        assertEquals(";1260-390;;;;1380-540;0-0", texto)
        assertEquals(dias, HoraDormir.lerDias(texto))
        // Estragado ou fora do relógio = segue o padrão; texto curto completa com null.
        assertEquals(listOf(null, null, HoraDormir.Janela(60, 120), null, null, null, null),
            HoraDormir.lerDias("abc;1440-10;60-120"))
        assertEquals(HoraDormir.Janela(5, 6), HoraDormir.doDia(-1, List(6) { null } + HoraDormir.Janela(5, 6), padrao))   // -1 = sábado
    }
}

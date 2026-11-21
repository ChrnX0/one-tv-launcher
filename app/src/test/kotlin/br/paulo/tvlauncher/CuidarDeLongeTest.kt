package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.family.CuidarDeLonge.Acao
import br.paulo.tvlauncher.family.CuidarDeLonge.Aviso
import br.paulo.tvlauncher.family.CuidarDeLonge.Config
import br.paulo.tvlauncher.family.CuidarDeLonge.Estado
import br.paulo.tvlauncher.family.CuidarDeLonge.Lembrete
import br.paulo.tvlauncher.home.Home
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuidarDeLongeTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val fuso = TimeZone.getTimeZone(sp)
    private fun ms(texto: String) = LocalDateTime.parse(texto).atZone(sp).toInstant().toEpochMilli()
    private fun texto(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(sp).toLocalDateTime().toString()

    private val remedio = Lembrete(1, 20, 0, CuidarDeLonge.TODO_DIA, "Remédio da pressão")
    private val pronta = Config(nome = "Ana", aparelhos = listOf("abc123"), avisos = Aviso.values().toSet(), lembretes = listOf(remedio))
    private val aceita = CuidarDeLonge.consentir(pronta, ms("2026-11-15T10:00"))

    // ---------------------------------------------------------------- consentimento

    @Test fun horaDoLembreteNoDiaDoHorarioDeVerao() {
        // Nova York, 08/03/2026: o relógio pula de 02:00 para 03:00. O lembrete das 09:00 continua às 09:00 do relógio.
        val ny = TimeZone.getTimeZone("America/New_York")
        val ms = CuidarDeLonge.instante(20260308, 9 * 60, ny)
        assertEquals(LocalDateTime.of(2026, 3, 8, 9, 0), java.time.Instant.ofEpochMilli(ms).atZone(ny.toZoneId()).toLocalDateTime())
        assertEquals(9 * 60, CuidarDeLonge.minutoDoDia(ms, ny))
    }

    @Test fun deFabricaNadaSai() {
        val c = Config()
        assertFalse(CuidarDeLonge.consentido(c))
        Aviso.values().forEach { assertFalse(CuidarDeLonge.ativo(c, it)) }
        assertEquals(CuidarDeLonge.Falta.NOME, CuidarDeLonge.falta(c))
    }

    /** Play: pedido de ajuda e "estou bem" (os dois só saem quando a pessoa aperta); com o plugin Extras, também "não ligou". */
    @Test fun buildDaPlaySoOfereceOQueAPessoaAperta() {
        assertEquals(aceita, CuidarDeLonge.limitar(aceita, familia = true))
        val play = CuidarDeLonge.limitar(aceita, familia = false)
        assertEquals(aceita.avisos.filter { it == Aviso.AJUDA || it == Aviso.ESTOU_BEM }.toSet(), play.avisos)
        assertFalse(Aviso.NAO_LIGOU in play.avisos)
        assertTrue(Aviso.NAO_LIGOU in CuidarDeLonge.limitar(aceita, familia = false, extras = true).avisos || Aviso.NAO_LIGOU !in aceita.avisos)
        // o aceite era para os 3 avisos: com menos avisos a assinatura muda e a TV pergunta de novo
        assertFalse(CuidarDeLonge.consentido(play))
        Aviso.values().forEach { assertFalse(CuidarDeLonge.ativo(play, it)) }
        val reaceita = CuidarDeLonge.consentir(play, ms("2026-11-15T11:00"))
        assertTrue(CuidarDeLonge.ativo(reaceita, Aviso.AJUDA))
        assertFalse(CuidarDeLonge.ativo(reaceita, Aviso.LEMBRETE))
        assertEquals(reaceita, CuidarDeLonge.limitar(reaceita, familia = false))
    }

    @Test fun semConsentimentoNadaSaiMesmoConfigurado() {
        Aviso.values().forEach { assertFalse(CuidarDeLonge.ativo(pronta, it)) }
        Aviso.values().forEach { assertTrue(CuidarDeLonge.ativo(aceita, it)) }
    }

    @Test fun consentirSemNomeOuAparelhoNaoValeNada() {
        val semAparelho = pronta.copy(aparelhos = emptyList())
        assertEquals(CuidarDeLonge.Falta.APARELHO, CuidarDeLonge.falta(semAparelho))
        assertFalse(CuidarDeLonge.consentido(CuidarDeLonge.consentir(semAparelho, 1L)))
        assertEquals(CuidarDeLonge.Falta.AVISO, CuidarDeLonge.falta(pronta.copy(avisos = emptySet())))
    }

    @Test fun mudarNomeAparelhoOuAvisoPedeOutroConsentimento() {
        assertFalse(CuidarDeLonge.consentido(aceita.copy(nome = "Bruno")))
        assertFalse(CuidarDeLonge.consentido(aceita.copy(aparelhos = listOf("abc123", "xyz"))))
        assertFalse(CuidarDeLonge.consentido(CuidarDeLonge.consentir(pronta.copy(avisos = setOf(Aviso.AJUDA)), 1L).copy(avisos = setOf(Aviso.AJUDA, Aviso.NAO_LIGOU))))
        // Tirar um aviso também muda o que foi aceito: pergunta de novo (nada sai sem a pessoa saber o que sai).
        assertFalse(CuidarDeLonge.consentido(aceita.copy(avisos = setOf(Aviso.AJUDA))))
        // Mudar prazo, tecla ou lembretes não muda quem recebe nem o quê.
        assertTrue(CuidarDeLonge.consentido(aceita.copy(prazoMin = 30, tecla = CuidarDeLonge.Tecla.OK, lembretes = emptyList())))
        // A ordem dos aparelhos não importa.
        val dois = CuidarDeLonge.consentir(pronta.copy(aparelhos = listOf("a", "b")), 1L)
        assertTrue(CuidarDeLonge.consentido(dois.copy(aparelhos = listOf("b", "a"))))
    }

    @Test fun retirarDesligaTudo() {
        val r = CuidarDeLonge.retirar(aceita)
        Aviso.values().forEach { assertFalse(CuidarDeLonge.ativo(r, it)) }
    }

    @Test fun jsonIdaEVolta() {
        val c = aceita.copy(horaFixa = 9 * 60, margemMin = 180, prazoMin = 30, tecla = CuidarDeLonge.Tecla.OK, acender = false)
        val de = CuidarDeLonge.deJson(CuidarDeLonge.paraJson(c).toString())
        assertEquals(c, de)
        assertTrue(CuidarDeLonge.consentido(de))
    }

    @Test fun jsonEstragadoOuForaDoLimiteVoltaAoPadrao() {
        assertEquals(Config(), CuidarDeLonge.deJson("lixo"))
        val de = CuidarDeLonge.deJson("""{"nome":"  Ana\n Maria ","aparelhos":["ok_1","../x","ok_1"],"avisos":["AJUDA","NADA"],
            "prazo":7,"margem":999,"horaFixa":5000,"tecla":"X"}""")
        assertEquals("Ana Maria", de.nome)
        assertEquals(listOf("ok_1"), de.aparelhos)
        assertEquals(setOf(Aviso.AJUDA), de.avisos)
        assertEquals(CuidarDeLonge.PADRAO_PRAZO, de.prazoMin)
        assertEquals(CuidarDeLonge.PADRAO_MARGEM, de.margemMin)
        assertEquals(-1, de.horaFixa)
        assertEquals(CuidarDeLonge.Tecla.VOLTAR, de.tecla)
    }

    // ---------------------------------------------------------------- lembretes

    @Test fun salvarLembreteLimpaENumera() {
        var l = CuidarDeLonge.salvarLembrete(emptyList(), Lembrete(0, 8, 0, 0, "  Água  "))
        l = CuidarDeLonge.salvarLembrete(l, Lembrete(0, 7, 30, CuidarDeLonge.TODO_DIA, "Café"))
        assertEquals(listOf("Café", "Água"), l.map { it.texto })   // em ordem de hora
        assertEquals(CuidarDeLonge.TODO_DIA, l.first { it.texto == "Água" }.dias)   // dias 0 = todo dia
        assertEquals(listOf(2, 1), l.map { it.id })
        // Editar mantém o id; texto vazio ou hora inválida não entra.
        l = CuidarDeLonge.salvarLembrete(l, Lembrete(1, 9, 0, CuidarDeLonge.TODO_DIA, "Água gelada"))
        assertEquals("Água gelada", l.first { it.id == 1 }.texto)
        assertEquals(l, CuidarDeLonge.salvarLembrete(l, Lembrete(0, 25, 0, CuidarDeLonge.TODO_DIA, "x")))
        assertEquals(l, CuidarDeLonge.salvarLembrete(l, Lembrete(0, 10, 0, CuidarDeLonge.TODO_DIA, "   ")))
    }

    @Test fun listaCheiaNaoCresce() {
        var l = emptyList<Lembrete>()
        repeat(CuidarDeLonge.MAX_LEMBRETES + 3) { l = CuidarDeLonge.salvarLembrete(l, Lembrete(0, 8, it % 60, CuidarDeLonge.TODO_DIA, "L$it")) }
        assertEquals(CuidarDeLonge.MAX_LEMBRETES, l.size)
    }

    @Test fun mostraNaHoraEAvisaSoDepoisDoPrazo() {
        val e = Estado()
        val naHora = CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T20:00"), fuso)
        assertEquals(listOf("Mostrar"), naHora.map { it.javaClass.simpleName })
        val antesDoPrazo = CuidarDeLonge.devidos(aceita, CuidarDeLonge.anotarMostrado(e, "1@20261115", ms("2026-11-15T20:00")), ms("2026-11-15T20:14"), fuso)
        assertTrue(antesDoPrazo.isEmpty())
        val noPrazo = CuidarDeLonge.devidos(aceita, CuidarDeLonge.anotarMostrado(e, "1@20261115", ms("2026-11-15T20:00")), ms("2026-11-15T20:15"), fuso)
        assertEquals(listOf("AvisarLembrete"), noPrazo.map { it.javaClass.simpleName })
        assertEquals("20:00", CuidarDeLonge.textoHora((noPrazo[0] as Acao.AvisarLembrete).momento, fuso))
    }

    @Test fun confirmadoNaoAvisa() {
        val e = CuidarDeLonge.anotarConfirmado(Estado(), "1@20261115", ms("2026-11-15T20:03"))
        assertTrue(CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T20:30"), fuso).isEmpty())
    }

    @Test fun umAvisoPorLembreteSemInsistir() {
        var e = CuidarDeLonge.anotarMostrado(Estado(), "1@20261115", ms("2026-11-15T20:00"))
        e = CuidarDeLonge.anotarEnvio(e, "1@20261115", aceitos = 1, agora = ms("2026-11-15T20:15"))
        assertTrue(CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T21:00"), fuso).isEmpty())
    }

    @Test fun envioQueFalhaTentaDeNovoTresVezesEDesiste() {
        var e = CuidarDeLonge.anotarMostrado(Estado(), "1@20261115", ms("2026-11-15T20:00"))
        e = CuidarDeLonge.anotarEnvio(e, "1@20261115", 0, ms("2026-11-15T20:15"))
        assertTrue(CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T20:17"), fuso).isEmpty())   // espera 5 min
        assertEquals(ms("2026-11-15T20:20"), CuidarDeLonge.proximo(aceita, e, ms("2026-11-15T20:17"), fuso))
        assertEquals(1, CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T20:20"), fuso).size)
        e = CuidarDeLonge.anotarEnvio(e, "1@20261115", 0, ms("2026-11-15T20:20"))
        e = CuidarDeLonge.anotarEnvio(e, "1@20261115", 0, ms("2026-11-15T20:25"))
        assertTrue(CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T20:40"), fuso).isEmpty())
    }

    @Test fun semConsentimentoOLembreteAparecemasNaoAvisa() {
        val semAviso = pronta   // não aceita
        assertEquals(listOf("Mostrar"), CuidarDeLonge.devidos(semAviso, Estado(), ms("2026-11-15T20:00"), fuso).map { it.javaClass.simpleName })
        val e = CuidarDeLonge.anotarMostrado(Estado(), "1@20261115", ms("2026-11-15T20:00"))
        assertTrue(CuidarDeLonge.devidos(semAviso, e, ms("2026-11-15T20:30"), fuso).isEmpty())
    }

    @Test fun lembreteVelhoNaoAparece() {
        // TV sem energia das 19h às 23h: às 23h o das 20h não aparece mais (passou de 2 h), mas o aviso ainda sai (até 6 h).
        val d = CuidarDeLonge.devidos(aceita, Estado(), ms("2026-11-15T23:00"), fuso)
        assertEquals(listOf("AvisarLembrete"), d.map { it.javaClass.simpleName })
    }

    @Test fun lembreteDeOntemPertoDaMeiaNoite() {
        val tarde = aceita.copy(lembretes = listOf(Lembrete(1, 23, 55, CuidarDeLonge.TODO_DIA, "Dormir")))
        var e = CuidarDeLonge.anotarMostrado(Estado(), "1@20261115", ms("2026-11-15T23:55"))
        val d = CuidarDeLonge.devidos(tarde, e, ms("2026-11-16T00:10"), fuso)
        assertEquals(listOf("1@20261115"), d.filterIsInstance<Acao.AvisarLembrete>().map { it.chave })
        e = CuidarDeLonge.anotarEnvio(e, "1@20261115", 1, ms("2026-11-16T00:10"))
        assertTrue(CuidarDeLonge.devidos(tarde, e, ms("2026-11-16T00:20"), fuso).isEmpty())
    }

    @Test fun diasDaSemana() {
        val segunda = 1 shl 1
        val soSegunda = aceita.copy(lembretes = listOf(remedio.copy(dias = segunda)))
        // 15/11/2026 é domingo; 16/11 é segunda.
        assertTrue(CuidarDeLonge.devidos(soSegunda, Estado(), ms("2026-11-15T20:00"), fuso).isEmpty())
        assertEquals(1, CuidarDeLonge.devidos(soSegunda, Estado(), ms("2026-11-16T20:00"), fuso).size)
        assertEquals("2026-11-16T20:00", texto(CuidarDeLonge.proximo(soSegunda, Estado(), ms("2026-11-15T12:00"), fuso)!!))
    }

    @Test fun proximoEhOLembreteOuOPrazo() {
        assertEquals("2026-11-15T20:00", texto(CuidarDeLonge.proximo(aceita.copy(avisos = setOf(Aviso.LEMBRETE)).let { CuidarDeLonge.consentir(it, 1L) },
            Estado(), ms("2026-11-15T12:00"), fuso)!!))
        val e = CuidarDeLonge.anotarMostrado(Estado(), "1@20261115", ms("2026-11-15T20:00"))
        val soLembrete = CuidarDeLonge.consentir(pronta.copy(avisos = setOf(Aviso.LEMBRETE)), 1L)
        assertEquals("2026-11-15T20:15", texto(CuidarDeLonge.proximo(soLembrete, e, ms("2026-11-15T20:01"), fuso)!!))
        assertNull(CuidarDeLonge.proximo(Config(), Estado(), ms("2026-11-15T12:00"), fuso))
    }

    // ---------------------------------------------------------------- 79: não ligou

    private fun ligadas(vararg pares: Pair<String, String>): Map<Int, Int> =
        pares.associate { (d, h) -> CuidarDeLonge.dia(ms("${d}T12:00"), fuso) to (h.substring(0, 2).toInt() * 60 + h.substring(3).toInt()) }

    @Test fun aprendeAHoraPelaMedianaComCincoDias() {
        val quatro = ligadas("2026-11-10" to "08:00", "2026-11-11" to "08:30", "2026-11-12" to "07:50", "2026-11-13" to "08:10")
        assertNull(CuidarDeLonge.horaCostume(pronta, quatro, 20261115))   // ainda aprendendo
        val cinco = quatro + ligadas("2026-11-14" to "13:00")   // um dia fora do normal não puxa a hora
        assertEquals(8 * 60 + 10, CuidarDeLonge.horaCostume(pronta, cinco, 20261115))
        assertEquals(10 * 60 + 10, CuidarDeLonge.minutoAviso(pronta, cinco, 20261115))   // + 2 h
        // Hora fixa ganha da aprendida.
        assertEquals(9 * 60, CuidarDeLonge.horaCostume(pronta.copy(horaFixa = 9 * 60), emptyMap(), 20261115))
    }

    @Test fun anotaSoAPrimeiraDoDiaDepoisDasCinco() {
        var l = CuidarDeLonge.anotarLigou(emptyMap(), ms("2026-11-15T03:00"), fuso)
        assertTrue(l.isEmpty())   // madrugada não conta
        l = CuidarDeLonge.anotarLigou(l, ms("2026-11-15T08:20"), fuso)
        l = CuidarDeLonge.anotarLigou(l, ms("2026-11-15T19:00"), fuso)
        assertEquals(mapOf(20261115 to 8 * 60 + 20), l)
        var muitos = emptyMap<Int, Int>()
        for (d in 1..40) muitos = CuidarDeLonge.anotarLigou(muitos, ms("2026-11-15T09:00") - d * 86_400_000L, fuso)
        assertEquals(CuidarDeLonge.GUARDAR_DIAS, muitos.size)
    }

    private val costume = ligadas("2026-11-10" to "08:00", "2026-11-11" to "08:00", "2026-11-12" to "08:00", "2026-11-13" to "08:00", "2026-11-14" to "08:00")

    @Test fun avisaUmaVezSeNaoLigouAteAHoraMaisMargem() {
        val e = Estado(ligadas = costume)
        assertTrue(CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T09:59"), fuso).none { it is Acao.AvisarNaoLigou })
        val d = CuidarDeLonge.devidos(aceita, e, ms("2026-11-15T10:00"), fuso).filterIsInstance<Acao.AvisarNaoLigou>()
        assertEquals(listOf("tv@20261115"), d.map { it.chave })
        val avisado = CuidarDeLonge.anotarEnvio(e, "tv@20261115", 1, ms("2026-11-15T10:00"))
        assertTrue(CuidarDeLonge.devidos(aceita, avisado, ms("2026-11-15T15:00"), fuso).none { it is Acao.AvisarNaoLigou })
        assertEquals("2026-11-15T10:00", texto(CuidarDeLonge.proximo(aceita.copy(lembretes = emptyList()), e, ms("2026-11-15T06:00"), fuso)!!))
    }

    @Test fun ligadaAgoraOuJaLigouHojeNaoAvisa() {
        val ligouHoje = Estado(ligadas = costume + (20261115 to 9 * 60))
        assertTrue(CuidarDeLonge.devidos(aceita, ligouHoje, ms("2026-11-15T11:00"), fuso).none { it is Acao.AvisarNaoLigou })
        assertTrue(CuidarDeLonge.devidos(aceita, Estado(ligadas = costume), ms("2026-11-15T11:00"), fuso, ligadaAgora = true).none { it is Acao.AvisarNaoLigou })
    }

    @Test fun naoAvisaDepoisDasOnzeEMeiaNemSemConsentimento() {
        val noite = ligadas("2026-11-10" to "22:00", "2026-11-11" to "22:00", "2026-11-12" to "22:00", "2026-11-13" to "22:00", "2026-11-14" to "22:00")
        assertNull(CuidarDeLonge.minutoAviso(pronta, noite, 20261115))   // 22h + 2 h passa das 23:30
        assertTrue(CuidarDeLonge.devidos(pronta, Estado(ligadas = costume), ms("2026-11-15T11:00"), fuso).isEmpty())
    }

    @Test fun proximoDeAmanhaDepoisDeAvisarHoje() {
        val e = CuidarDeLonge.anotarEnvio(Estado(ligadas = costume), "tv@20261115", 1, ms("2026-11-15T10:00"))
        val c = aceita.copy(lembretes = emptyList())
        assertEquals("2026-11-16T10:00", texto(CuidarDeLonge.proximo(c, e, ms("2026-11-15T10:01"), fuso)!!))
    }

    @Test fun estadoJsonIdaEVoltaEPoda() {
        var e = Estado(ligadas = costume)
        e = CuidarDeLonge.anotarEnvio(e, "tv@20261110", 1, 5L)
        e = CuidarDeLonge.anotarConfirmado(e, "1@20261115", 7L)
        assertEquals(e, CuidarDeLonge.estadoDeJson(CuidarDeLonge.estadoParaJson(e).toString()))
        val podado = CuidarDeLonge.podar(e, ms("2026-11-15T12:00"), fuso)
        assertEquals(listOf("1@20261115"), podado.marcas.map { it.chave })
        assertEquals(Estado(), CuidarDeLonge.estadoDeJson("{"))
    }

    // ---------------------------------------------------------------- 77: segurar a tecla

    @Test fun toqueCurtoPassaESegurarTresSegundosDisparaUmaVez() {
        val s = CuidarDeLonge.Segurar()
        var disparos = 0
        // Toque curto: nada é engolido.
        assertFalse(s.evento(4, true, 0, 0, { disparos++ }))
        assertFalse(s.evento(4, false, 0, 200, { disparos++ }))
        assertEquals(0, disparos)
        // Segurar: repetições até 3 s passam; com 3 s dispara e daí em diante engole, inclusive o UP.
        assertFalse(s.evento(4, true, 0, 1000, { disparos++ }))
        assertFalse(s.evento(4, true, 1, 2500, { disparos++ }))
        assertTrue(s.evento(4, true, 2, 4000, { disparos++ }))
        assertTrue(s.evento(4, true, 3, 4100, { disparos++ }))
        assertTrue(s.evento(4, false, 0, 4200, { disparos++ }))
        assertEquals(1, disparos)
        // Depois de soltar, um toque curto volta a passar.
        assertFalse(s.evento(4, true, 0, 5000, { disparos++ }))
        assertFalse(s.evento(4, false, 0, 5100, { disparos++ }))
    }

    @Test fun relogioDisparaSemRepeticaoEOutraTeclaNaoConta() {
        val s = CuidarDeLonge.Segurar()
        assertFalse(s.evento(23, true, 0, 0) {})   // OK não é a tecla escolhida
        assertFalse(s.venceu(5000))
        s.evento(4, true, 0, 1000) {}
        assertFalse(s.venceu(3999))
        assertTrue(s.venceu(4000))
        assertFalse(s.venceu(4100))   // uma vez só
        assertTrue(s.evento(4, false, 0, 4200) {})   // o UP é engolido
        s.tecla = 23
        s.evento(23, true, 0, 0) {}
        assertTrue(s.venceu(3000))
    }

    @Test fun soTeclaDeGenteAceitaOConsentimento() {
        assertTrue(CuidarDeLonge.teclaDeGente(7, 0x101))                 // controle do Chromecast (aparelho próprio)
        assertTrue(CuidarDeLonge.teclaDeGente(-1, 0x02000001))           // controle da LG pelo HDMI-CEC
        assertFalse(CuidarDeLonge.teclaDeGente(-1, 0x101))               // input keyevent do shell (teclado virtual)
        assertFalse(CuidarDeLonge.teclaDeGente(0, 0x101))
    }

    @Test fun lembreteNovoEsperaNaFilaEAjudaPassaNaFrente() {
        assertTrue(CuidarDeLonge.esperaNaFila("lembrete", "lembrete"))
        assertTrue(CuidarDeLonge.esperaNaFila("lembrete", "estou_bem"))
        assertTrue(CuidarDeLonge.esperaNaFila("estou_bem", "lembrete"))
        assertFalse(CuidarDeLonge.esperaNaFila("lembrete", "ajuda"))
        assertFalse(CuidarDeLonge.esperaNaFila("ajuda", "lembrete"))
        assertFalse(CuidarDeLonge.esperaNaFila("consentimento", "lembrete"))
    }

    @Test fun idDeLembreteApagadoNaoHerdaAsMarcas() {
        val l1 = CuidarDeLonge.Lembrete(0, 8, 0, CuidarDeLonge.TODO_DIA, "Remédio")
        var lista = CuidarDeLonge.salvarLembrete(emptyList(), l1)
        val id = lista.single().id
        var e = CuidarDeLonge.anotarConfirmado(CuidarDeLonge.Estado(), "$id@20261118", 1000)
        e = CuidarDeLonge.anotarMostrado(e, "${id + 10}@20261118", 1000)
        e = CuidarDeLonge.anotarMostrado(e, "tv@20261118", 1000)
        lista = CuidarDeLonge.removerLembrete(lista, id)
        e = CuidarDeLonge.esquecerLembrete(e, id)
        val novo = CuidarDeLonge.salvarLembrete(lista, l1.copy(texto = "Água")).single()
        assertEquals(id, novo.id)                                       // o id volta (máximo + 1)...
        assertEquals(0L, e.marca("$id@20261118").confirmado)            // ...mas sem o "confirmado" do apagado
        assertEquals(2, e.marcas.size)                                  // outras marcas (id 11, "tv@") ficam
    }

    @Test fun aparelhoCriadoDepoisDaPerguntaNaoAceita() {
        val antes = setOf(3, 7)
        assertTrue(CuidarDeLonge.aparelhoDeAntes(7, antes))     // o controle que já estava ligado
        assertFalse(CuidarDeLonge.aparelhoDeAntes(12, antes))   // teclado virtual (uhid) criado pelo shell depois
        assertTrue(CuidarDeLonge.aparelhoDeAntes(-1, antes))    // CEC da LG: decide o teclaDeGente
        assertFalse(CuidarDeLonge.aparelhoDeAntes(7, null))     // não deu para ler: recusa
    }

    /** VOLTAR segurado do painel rápido (BotoesControle, 18/11/2026): a mesma conta, com 700 ms. */
    @Test fun voltarSeguradoAbrePainelEToqueCurtoPassa() {
        val v = CuidarDeLonge.Segurar(Home.SEGURAR_HOME_MS).apply { tecla = 4 }
        var abriu = 0
        // Toque curto: nada é engolido (o app volta normalmente).
        assertFalse(v.evento(4, true, 0, 1000) { abriu++ })
        assertFalse(v.venceu(1300))
        assertFalse(v.evento(4, false, 0, 1300) { abriu++ })
        assertEquals(0, abriu)
        // Segurado: o relógio vence aos 700 ms; daí em diante as repetições e o soltar são nossos.
        assertFalse(v.evento(4, true, 0, 2000) { abriu++ })
        assertFalse(v.evento(4, true, 1, 2400) { abriu++ })   // repetição antes do tempo ainda passa
        assertTrue(v.venceu(2700))
        assertTrue(v.evento(4, true, 2, 2800) { abriu++ })
        assertTrue(v.evento(4, false, 0, 3000) { abriu++ })
        assertEquals(0, abriu)   // abriu pelo relógio (venceu), não de novo pela repetição
        // Outra tecla não é com ele.
        assertFalse(v.evento(3, true, 0, 4000) { abriu++ })
    }
}

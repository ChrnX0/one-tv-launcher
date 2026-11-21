package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.HomeCards
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardsTest {
    private val hoje = LocalDate.of(2026, 11, 18)   // quarta-feira

    // ---- contagem
    @Test fun contagemNaJanelaDeSeteDias() {
        val ferias = HomeCards.criarContagem("Férias", 2026, 11, 21)!!
        val natal = HomeCards.criarContagem("Natal", 2026, 12, 25)!!
        assertEquals(HomeCards.Falta("Férias", 3), HomeCards.contagemDaPilula(listOf(natal, ferias), hoje))
        assertNull(HomeCards.contagemDaPilula(listOf(natal), hoje))   // falta mais de 7 dias: ainda não aparece
        assertEquals(HomeCards.Falta("Natal", 0), HomeCards.contagemDaPilula(listOf(natal), LocalDate.of(2026, 12, 25)))
        assertNull(HomeCards.contagemDaPilula(listOf(ferias), LocalDate.of(2026, 11, 22)))   // já passou
    }

    @Test fun feriadoSoQuandoNaoHaContagemDaPessoa() {
        val feriado = LocalDate.of(2026, 11, 20)
        assertEquals(HomeCards.Falta(null, 2), HomeCards.contagemDaPilula(emptyList(), hoje, feriado))
        val ferias = HomeCards.criarContagem("Férias", 2026, 11, 24)!!
        assertEquals("Férias", HomeCards.contagemDaPilula(listOf(ferias), hoje, feriado)?.nome)
        assertNull(HomeCards.contagemDaPilula(emptyList(), hoje, LocalDate.of(2026, 12, 25)))
    }

    @Test fun contagemValidaETrocaPeloNome() {
        assertNull(HomeCards.criarContagem("  ", 2026, 12, 1))
        assertNull(HomeCards.criarContagem("x", 2026, 2, 30))
        val a = HomeCards.criarContagem("férias", 2026, 12, 1)!!
        val b = HomeCards.criarContagem("FERIAS", 2026, 12, 20)!!
        val velha = HomeCards.Contagem("antiga", LocalDate.of(2026, 1, 1))
        val l = HomeCards.adicionarContagem(listOf(velha, a), b, hoje)
        assertEquals(listOf(b), l)   // mesma (sem acento/maiúscula) trocou; a que passou saiu
        assertEquals(l, HomeCards.contagensDeJson(HomeCards.contagensJson(l).toString()))
        assertEquals(emptyList<HomeCards.Contagem>(), HomeCards.removerContagem(l, "Férias"))
    }

    // ---- lembrete da casa
    @Test fun lembreteCaiNosDiasCertos() {
        val dias = HomeCards.bit(DayOfWeek.TUESDAY) or HomeCards.bit(DayOfWeek.WEDNESDAY)
        val lixo = HomeCards.criarLembrete("Lixo reciclável", dias, 7 * 60)!!
        assertTrue(lixo.cai(hoje))
        assertFalse(lixo.cai(hoje.plusDays(1)))
        assertEquals(listOf(lixo), HomeCards.lembretesDeHoje(listOf(lixo), hoje))
        assertNull(HomeCards.criarLembrete("x", 0, null))
        assertNull(HomeCards.criarLembrete("", 1, null))
        assertNull(HomeCards.criarLembrete("x", 1, 24 * 60)?.minuto)   // hora inválida vira "o dia todo"
    }

    @Test fun avisoNaHoraUmaVezSo() {
        val lixo = HomeCards.criarLembrete("Lixo", HomeCards.TODOS_OS_DIAS, 7 * 60)!!
        val semHora = HomeCards.criarLembrete("Regar", HomeCards.TODOS_OS_DIAS, null)!!
        val l = listOf(lixo, semHora)
        assertEquals(emptyList<HomeCards.LembreteCasa>(), HomeCards.lembretesParaAvisar(l, hoje, 6 * 60 + 59, emptySet()))
        assertEquals(listOf(lixo), HomeCards.lembretesParaAvisar(l, hoje, 7 * 60, emptySet()))
        assertEquals(listOf(lixo), HomeCards.lembretesParaAvisar(l, hoje, 7 * 60 + 30, emptySet()))
        assertEquals(emptyList<HomeCards.LembreteCasa>(), HomeCards.lembretesParaAvisar(l, hoje, 9 * 60, emptySet()))   // tarde demais
        val ja = setOf(HomeCards.chaveAviso(hoje, lixo))
        assertEquals(emptyList<HomeCards.LembreteCasa>(), HomeCards.lembretesParaAvisar(l, hoje, 7 * 60 + 1, ja))
        assertEquals(l.toSet(), HomeCards.lembretesDeJson(HomeCards.lembretesJson(l).toString()).toSet())
    }

    // ---- serviço
    @Test fun servicoSoHttpSemSenha() {
        assertEquals("http://servidor.local:8123", HomeCards.criarServico("Casa", "http://servidor.local:8123")?.url)
        assertNull(HomeCards.criarServico("Casa", "ftp://servidor.local"))
        assertNull(HomeCards.criarServico("Casa", "http://eu:senha@servidor.local"))
        assertNull(HomeCards.criarServico("Casa", "servidor.local"))
        assertNull(HomeCards.criarServico("", "http://servidor.local"))
    }

    @Test fun foraDoArSoDepoisDeDuasFalhas() {
        val s = listOf(HomeCards.Servico("Casa", "http://a.local"), HomeCards.Servico("NAS", "http://b.local"))
        var f = 0
        f = HomeCards.falhas(f, HomeCards.respondeu(null))
        assertEquals(emptyList<String>(), HomeCards.foraDoAr(s, mapOf("Casa" to f)))
        f = HomeCards.falhas(f, HomeCards.respondeu(503))
        assertEquals(listOf("Casa"), HomeCards.foraDoAr(s, mapOf("Casa" to f)))
        f = HomeCards.falhas(f, HomeCards.respondeu(401))   // 401 = servidor de pé
        assertEquals(0, f)
    }

    // ---- cotação
    @Test fun cotacaoInverteEIgnoraLinhaEstranha() {
        val json = """[{"date":"2026-11-17","base":"BRL","quote":"BTC","rate":0.0000026791},
            {"date":"2026-11-18","base":"BRL","quote":"USD","rate":0.17286},
            {"date":"2026-11-18","base":"BRL","quote":"EUR","rate":0},
            {"date":"2026-11-18","base":"USD","quote":"EUR","rate":0.9}]"""
        val l = HomeCards.lerCotacoes(json, "BRL")
        assertEquals(listOf("BTC", "USD"), l.map { it.codigo })
        assertEquals(5.785, l[1].valor, 0.001)
        assertEquals(l, HomeCards.cotacoesDeTexto(HomeCards.cotacoesParaTexto(l)))
        assertEquals(emptyList<HomeCards.Cotacao>(), HomeCards.lerCotacoes("não é json", "BRL"))
    }

    @Test fun moedasEscolhidas() {
        assertEquals(listOf("USD", "EUR", "BTC"), HomeCards.escolhidas("", "BRL"))
        assertEquals(listOf("EUR", "BTC", "GBP"), HomeCards.escolhidas(null, "USD"))
        assertEquals(listOf("BTC", "EUR"), HomeCards.escolhidas("btc, eur, BRL, xyz, EUR", "BRL"))
        assertEquals("USD", HomeCards.moedaDaCasa("XOF"))
        assertEquals("BRL", HomeCards.moedaDaCasa("BRL"))
    }

    @Test fun valorCurtoCabeNaPilula() {
        val br = Locale.forLanguageTag("pt-BR")
        assertEquals("5,79", HomeCards.valorCurto(5.785, br, "mil", "mi"))
        assertEquals("373 mil", HomeCards.valorCurto(373_264.0, br, "mil", "mi"))
        assertEquals("1,2 mi", HomeCards.valorCurto(1_234_567.0, br, "mil", "mi"))
        assertEquals("150", HomeCards.valorCurto(150.4, br, "mil", "mi"))
    }
}

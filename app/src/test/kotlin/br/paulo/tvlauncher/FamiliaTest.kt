package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideias 33 (tempo de tela por dia) e 34 (PIN das crianças): regras puras de Familia.kt. */
class FamiliaTest {
    // ---- 34: PIN das crianças

    @Test fun pinNovoPrecisaSerValidoEDiferenteDoPainel() {
        assertEquals(PinFamilia.Novo.OK, PinFamilia.conferirNovo("246813", igualAoPainel = false))
        assertEquals(PinFamilia.Novo.IGUAL_AO_PAINEL, PinFamilia.conferirNovo("246813", igualAoPainel = true))
        assertEquals(PinFamilia.Novo.INVALIDO, PinFamilia.conferirNovo("123", igualAoPainel = false))
        assertEquals(PinFamilia.Novo.INVALIDO, PinFamilia.conferirNovo("12345a", igualAoPainel = false))
    }

    @Test fun naTvValeOdasCriancasOuOdoPainel() {
        val fam = { p: String -> p == "111111" }
        val painel = { p: String -> p == "999999" }
        assertTrue(PinFamilia.confereNaTv("111111", true, fam, painel))
        assertTrue(PinFamilia.confereNaTv("999999", true, fam, painel))
        assertFalse(PinFamilia.confereNaTv("222222", true, fam, painel))
        // Sem PIN das crianças definido, o resumo velho não conta: só o do painel.
        assertFalse(PinFamilia.confereNaTv("111111", false, fam, painel))
        assertTrue(PinFamilia.confereNaTv("999999", false, fam, painel))
    }

    @Test fun painelSoConfereSeOdasCriancasFalhou() {
        var vezesPainel = 0
        assertTrue(PinFamilia.confereNaTv("111111", true, { true }, { vezesPainel++; false }))
        assertEquals(0, vezesPainel)   // PBKDF2 de ~1 s a menos na TV
    }

    // ---- 33: tempo de tela por dia

    private val hoje = 20261114

    @Test fun somaNoDiaEZeraNoDiaSeguinte() {
        var e = TempoTela.Estado(hoje, 0, 0)
        e = TempoTela.somar(e, hoje, 60_000)
        e = TempoTela.somar(e, hoje, 60_000)
        assertEquals(120_000L, e.usadoMs)
        val amanha = TempoTela.somar(TempoTela.darMais(e, hoje), hoje + 1, 60_000)
        assertEquals(TempoTela.Estado(hoje + 1, 60_000, 0), amanha)   // o extra de ontem também some
    }

    @Test fun passoGrandeOuNegativoNaoContaInteiro() {
        val e = TempoTela.Estado(hoje, 0, 0)
        assertEquals(TempoTela.MAX_PASSO_MS, TempoTela.somar(e, hoje, 3_600_000).usadoMs)   // TV dormiu 1 h
        assertEquals(0L, TempoTela.somar(e, hoje, -5_000).usadoMs)
    }

    @Test fun restanteAcabouEMaisTempo() {
        val e = TempoTela.Estado(hoje, 30 * 60_000L, 0)
        assertEquals(0L, TempoTela.restanteMs(e, hoje, 30))
        assertTrue(TempoTela.acabou(e, hoje, 30))
        val mais = TempoTela.darMais(e, hoje)
        assertEquals(30 * 60_000L, TempoTela.restanteMs(mais, hoje, 30))
        assertFalse(TempoTela.acabou(mais, hoje, 30))
        // Estado de ontem: hoje começa cheio.
        assertEquals(120 * 60_000L, TempoTela.restanteMs(TempoTela.Estado(hoje - 1, 999_999_999, 0), hoje, 120))
    }

    @Test fun lerEscreverEEstragado() {
        val e = TempoTela.Estado(hoje, 123_456, 30)
        assertEquals(e, TempoTela.ler(TempoTela.escrever(e)))
        assertEquals(TempoTela.Estado(0, 0, 0), TempoTela.ler("lixo"))
        assertEquals(TempoTela.Estado(0, 0, 0), TempoTela.ler(null))
        assertEquals(0L, TempoTela.ler("$hoje;-5;0").usadoMs)
    }

    @Test fun quemConta() {
        val liberados = setOf("com.escola.app")
        assertTrue(TempoTela.conta("com.google.android.youtube.tv", liberados))
        assertFalse(TempoTela.conta("br.paulo.tvlauncher", liberados))   // a HOME não gasta
        assertFalse(TempoTela.conta("com.escola.app", liberados))
        assertFalse(TempoTela.conta(null, liberados))
    }

    @Test fun avisoDos5MinutosUmaVez() {
        assertTrue(TempoTela.cruzouAviso(6 * 60_000L, 5 * 60_000L))
        assertFalse(TempoTela.cruzouAviso(5 * 60_000L, 4 * 60_000L))
        assertFalse(TempoTela.cruzouAviso(6 * 60_000L, 0))   // pulou direto para o fim: vale o "acabou"
    }

    @Test fun cotaDaAVolta() {
        assertEquals(150, TempoTela.proximaCota(120))
        assertEquals(TempoTela.MIN_COTA, TempoTela.proximaCota(TempoTela.MAX_COTA))
        assertEquals(TempoTela.MAX_COTA, TempoTela.proximaCota(TempoTela.MIN_COTA, -TempoTela.PASSO_COTA))
    }

    @Test fun textoDoTempo() {
        assertEquals("45 min", TempoTela.texto(45 * 60_000L))
        assertEquals("2 h", TempoTela.texto(120 * 60_000L))
        assertEquals("1 h 30 min", TempoTela.texto(90 * 60_000L))
        assertEquals("1 min", TempoTela.texto(1))   // arredonda para cima: "0 min" com tempo sobrando confunde
    }

    // ---- 30: despertador com o último canal

    private val canais = listOf(Canal("a1", "Rádio Boa", 1, grupo = "Rádios", url = "http://x/1.m3u8"),
        Canal("a2", "Filme Forte", 2, grupo = "Adultos", url = "http://x/2.ts"))

    @Test fun canalDoDespertador() {
        assertEquals("a1", CanalDespertar.escolher(canais, "a1", ControleParental.Regras())?.id)
        assertEquals(null, CanalDespertar.escolher(canais, "zz", ControleParental.Regras()))   // canal sumiu da lista
        assertEquals(null, CanalDespertar.escolher(canais, null, ControleParental.Regras()))   // nunca assistiu nada
        // Bloqueado pelo controle parental não toca sozinho às 6 da manhã.
        assertEquals(null, CanalDespertar.escolher(canais, "a2", ControleParental.Regras(ligado = true, grupos = setOf("Adultos"))))
    }
}

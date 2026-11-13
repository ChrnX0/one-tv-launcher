package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Regras puras das ideias da pesquisa de 10/11/2026 (DNS que volta, filtro noturno, PIN da HOME, calibração, cor do
 * fundo, diário de DNS, limpeza pós-OTA, Wake-on-LAN, recado com foto, cenas, testador de teclas, apps críticos).
 * Roda no computador: gradlew testReleaseUnitTest.
 */
class IdeiasPesquisaTest {
    private fun host(h: String) = Dns.Estado(Dns.Modo.HOST, h)
    private val auto = Dns.Estado(Dns.Modo.AUTOMATICO, null)

    // ---- DNS que volta sozinho
    @Test fun dnsVoltaSoQuandoServidorFixoNovoFalha() {
        assertTrue(DnsSeguro.deveVoltar(false, host("x.dns"), host("x.dns"), auto))
        assertFalse("resolveu", DnsSeguro.deveVoltar(true, host("x.dns"), host("x.dns"), auto))
        assertFalse("novo é automático", DnsSeguro.deveVoltar(false, auto, auto, host("x.dns")))
        assertFalse("alguém trocou de novo", DnsSeguro.deveVoltar(false, host("x.dns"), host("y.dns"), auto))
        assertFalse("anterior igual", DnsSeguro.deveVoltar(false, host("x.dns"), host("X.DNS"), host("x.dns")))
    }

    // ---- filtro noturno
    @Test fun filtroAtravessaMeiaNoite() {
        val i = 22 * 60; val f = 6 * 60
        assertTrue(FiltroNoturno.dentro(23 * 60, i, f))
        assertTrue(FiltroNoturno.dentro(0, i, f))
        assertFalse(FiltroNoturno.dentro(6 * 60, i, f))
        assertFalse(FiltroNoturno.dentro(12 * 60, i, f))
        assertTrue(FiltroNoturno.dentro(13 * 60, 12 * 60, 14 * 60))
        assertFalse("início = fim é desligado", FiltroNoturno.dentro(100, 100, 100))
    }

    // ---- PIN da HOME
    @Test fun pinDaHome() {
        assertTrue(TravaHome.precisaPin(true, true, 1000, 0))
        assertFalse("liberado por 10 min", TravaHome.precisaPin(true, true, 1000, 2000))
        assertFalse("sem PIN definido não trava", TravaHome.precisaPin(true, false, 1000, 0))
        assertFalse("desligado", TravaHome.precisaPin(false, true, 1000, 0))
    }

    // ---- calibração
    @Test fun degrausDeCinza() {
        assertEquals(listOf(0, 51, 102, 153, 204, 255), Calibracao.degraus(0, 255, 6))
        assertEquals(listOf(16), Calibracao.degraus(16, 32, 1))
        assertEquals(7, Calibracao.BARRAS_75.size)
    }

    // ---- cor do fundo
    @Test fun corDoFundoIgnoraCinzaEClareia() {
        assertNull(CorDoFundo.escolher(IntArray(10) { 0xFF808080.toInt() }))
        assertNull(CorDoFundo.escolher(IntArray(10) { 0xFF0A0000.toInt() }))
        val azulEscuro = IntArray(50) { 0xFF102080.toInt() } + IntArray(10) { 0xFFC03020.toInt() }
        val c = CorDoFundo.escolher(azulEscuro)!!
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        assertTrue("azul domina", b > r && b > g)
        assertTrue("clara o bastante", CorDoFundo.luminancia(r, g, b) >= 0.55)
        assertEquals(240.0, CorDoFundo.matiz(0, 0, 255), 0.01)
    }

    // ---- diário de DNS
    @Test fun agregadorSomaEVoltaDoJson() {
        val ag = AgregadorDns()
        ag.somar("com.netflix", "API.Netflix.com.", 10)
        ag.somar("com.netflix", "api.netflix.com", 20)
        ag.somar("com.google", "ads.google.com", 5)
        assertEquals(2, ag.tamanho())
        assertEquals("api.netflix.com", ag.maisConsultados(1)[0].host)
        assertEquals(2, ag.maisConsultados(1)[0].contagem)
        assertEquals(1 to 2, ag.porApp()["com.netflix"])
        val volta = AgregadorDns.deJson(JSONObject(ag.json().toString()))
        assertEquals(20L, volta.maisConsultados(1)[0].ultimo)
    }

    @Test fun diarioGuardaSeteDias() {
        val antes = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val l = AgregadorDns.diasParaManter(1_762_732_800_000L, 7)   // 2025-11-10 00:00 UTC
            assertEquals(7, l.size); assertEquals("2025-11-10", l[0]); assertEquals("2025-11-04", l[6])
        } finally { TimeZone.setDefault(antes) }
    }

    // ---- limpeza pós-OTA
    @Test fun sistemaMudou() {
        assertFalse("primeira vez só grava", DebloatPosOta.sistemaMudou("", "a/b:14"))
        assertFalse(DebloatPosOta.sistemaMudou("a/b:14", "a/b:14"))
        assertTrue(DebloatPosOta.sistemaMudou("a/b:14", "a/b:15"))
    }

    // ---- Wake-on-LAN
    @Test fun pacoteMagico() {
        val m = AcordarPc.mac("aa:BB-cc.dd:ee:ff")!!
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()), m)
        assertNull(AcordarPc.mac("aa:bb:cc:dd:ee"))
        assertNull(AcordarPc.mac("gg:bb:cc:dd:ee:ff"))
        val p = AcordarPc.pacoteMagico(m)
        assertEquals(102, p.size)
        assertTrue((0 until 6).all { p[it] == 0xFF.toByte() })
        assertEquals(0xAA.toByte(), p[6]); assertEquals(0xFF.toByte(), p[101])
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()),
            AcordarPc.difusao(byteArrayOf(192.toByte(), 168.toByte(), 1, 153.toByte()), 24))
        assertArrayEquals(byteArrayOf(10, 0, 3, 255.toByte()), AcordarPc.difusao(byteArrayOf(10, 0, 2, 7), 23))
    }

    // ---- recado com foto
    @Test fun tipoDaImagemPelosBytes() {
        assertEquals(RecadoImagem.Tipo.JPEG, RecadoImagem.tipo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(RecadoImagem.Tipo.PNG, RecadoImagem.tipo(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(RecadoImagem.Tipo.WEBP, RecadoImagem.tipo("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)))
        assertNull(RecadoImagem.tipo("<html>".toByteArray()))
        assertEquals(1, RecadoImagem.amostra(960, 540))
        assertEquals(8, RecadoImagem.amostra(4000, 3000))   // 4000/4 = 1000 ainda passa de 960
    }

    // ---- cenas
    @Test fun cenasTokenSoPeloHash() {
        val t = Cenas.gerarToken()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(t))
        val cenas = listOf(Cenas.Cena("cinema", "{}", Cenas.resumo(t)), Cenas.Cena("dormir", "{}", Cenas.resumo(Cenas.gerarToken())))
        assertEquals("cinema", Cenas.achar(t, cenas)?.nome)
        assertNull(Cenas.achar(Cenas.gerarToken(), cenas))
        assertNull("formato errado", Cenas.achar("x", cenas))
        assertNull(Cenas.achar(t.uppercase(), cenas))
        assertEquals(2, Cenas.lerJson(Cenas.paraJson(cenas)).size)
        assertEquals(0, Cenas.lerJson("lixo").size)
        assertTrue(Cenas.nomeValido("Hora do filme"))
        assertFalse(Cenas.nomeValido("a/b"))
        assertFalse(Cenas.nomeValido(""))
    }

    // ---- testador de teclas
    @Test fun testadorSoGuardaArmadoEComLimite() {
        TesteTeclas.desarmar()
        assertFalse(TesteTeclas.armado(1000))
        TesteTeclas.armar(1000)
        assertTrue(TesteTeclas.armado(1000 + TesteTeclas.JANELA_MS - 1))
        assertFalse(TesteTeclas.armado(1000 + TesteTeclas.JANELA_MS))
        repeat(TesteTeclas.CAPACIDADE + 5) {
            TesteTeclas.adicionar(TesteTeclas.Registro(it.toLong(), "OK", 23, 0, "controle", true, 0, false, false))
        }
        assertEquals(TesteTeclas.CAPACIDADE, TesteTeclas.lista().size)
        assertEquals(5L, TesteTeclas.lista().first().hora)
        TesteTeclas.desarmar()
        assertEquals(0, TesteTeclas.lista().size)
    }

    // ---- apps travados contra parar/limpar
    @Test fun pacotesCriticos() {
        assertEquals(listOf("br.paulo.tvlauncher"), ExtrasDO.pacotesCriticos("br.paulo.tvlauncher", null))
        assertEquals(listOf("br.paulo.tvlauncher"), ExtrasDO.pacotesCriticos("br.paulo.tvlauncher", " "))
        assertEquals(listOf("br.paulo.tvlauncher", "com.adguard.vpn"), ExtrasDO.pacotesCriticos("br.paulo.tvlauncher", "com.adguard.vpn"))
    }
}

package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.abs

/**
 * Nascer e pôr do sol contra uma tabela publicada (10/11/2026): timeanddate.com, São dono, novembro de 2026
 * (https://www.timeanddate.com/sun/brazil/sao-paulo?month=11&year=2026), horários em minutos com refração.
 * Lugar público (centro de São dono), não o da casa.
 */
class AstronomiaTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val fuso = TimeZone.getTimeZone(sp)
    private val LAT = -23.5475
    private val LON = -46.6361

    private fun ms(texto: String) = LocalDateTime.parse(texto).atZone(sp).toInstant().toEpochMilli()

    /** Diferença em minutos entre o calculado e o publicado ("05:15"). A tabela arredonda para o minuto. */
    private fun erroMin(calculado: Long, dia: String, hhmm: String) = abs(calculado - ms("${dia}T$hhmm")) / 60_000.0

    private fun conferir(dia: String, nascer: String, por: String) {
        val r = Astronomia.nascerEPor(ms("${dia}T12:00"), LAT, LON, fuso)
        assertNotNull(r)
        val (n, p) = r!!
        // O modelo simplificado do NOAA erra ~1° no sol; em minutos, cabe em 2 com folga nesta latitude.
        assertTrue("nascer $dia: erro ${erroMin(n, dia, nascer)} min", erroMin(n, dia, nascer) <= 2.0)
        assertTrue("pôr $dia: erro ${erroMin(p, dia, por)} min", erroMin(p, dia, por) <= 2.0)
    }

    @Test fun inicioDeNovembro() = conferir("2026-11-01", "05:20", "18:20")
    @Test fun meioDeNovembro() = conferir("2026-11-10", "05:15", "18:25")
    @Test fun fimDeNovembro() = conferir("2026-11-30", "05:11", "18:39")

    @Test fun mesmoDiaQualquerHoraDaOMesmoResultado() {
        val cedo = Astronomia.nascerEPor(ms("2026-11-10T00:01"), LAT, LON, fuso)
        val tarde = Astronomia.nascerEPor(ms("2026-11-10T23:59"), LAT, LON, fuso)
        assertEquals(cedo, tarde)
    }

    @Test fun cacheDevolveOMesmoObjetoEMudaComODiaOuOLugar() {
        val a = Astronomia.nascerEPor(ms("2026-11-11T08:00"), LAT, LON, fuso)
        assertSame(a, Astronomia.nascerEPor(ms("2026-11-11T20:00"), LAT, LON, fuso))           // mesmo dia e lugar
        val outroDia = Astronomia.nascerEPor(ms("2026-11-12T08:00"), LAT, LON, fuso)
        assertFalse(a == outroDia)
        val outroLugar = Astronomia.nascerEPor(ms("2026-11-12T08:00"), -15.79, -47.88, fuso)  // Brasília
        assertFalse(outroDia == outroLugar)
    }

    @Test fun precisaoDeSegundosNaoDeJanelaDe2Min() {
        // Antes o resultado era sempre um múltiplo de 2 min desde a meia-noite (o fim da janela da busca).
        val (n, _) = Astronomia.nascerEPor(ms("2026-11-10T12:00"), LAT, LON, fuso)!!
        val alturaNoNascer = Astronomia.alturaSol(n, LAT, LON)
        assertEquals(-0.833, alturaNoNascer, 0.01)
    }

    @Test fun noiteSemFimPertoDoPoloDaNull() {
        // Tromsø (69,6° N) em 20/12: o sol não nasce.
        assertNull(Astronomia.nascerEPor(ms("2026-12-20T12:00"), 69.65, 18.96, TimeZone.getTimeZone("Europe/Oslo")))
    }

    @Test fun eDiaUsaOPorRealMaisAMargemDoCrepusculo() {
        val dia = "2026-11-10"
        val (n, p) = Astronomia.nascerEPor(ms("${dia}T12:00"), LAT, LON, fuso)!!
        val margem = Config.MARGEM_CREPUSCULO_MIN * 60_000L
        // eDia usa o fuso da TV (o padrão da JVM); os instantes daqui independem do fuso, mas o "dia" não: fixa.
        val antes = TimeZone.getDefault()
        TimeZone.setDefault(fuso)
        try {
            assertFalse(Astronomia.eDia(n - 60_000, LAT, LON, isDayDaApi = true))
            assertTrue(Astronomia.eDia(n + 60_000, LAT, LON, isDayDaApi = false))
            assertTrue(Astronomia.eDia(p + margem - 60_000, LAT, LON, isDayDaApi = false))   // céu ainda claro
            assertFalse(Astronomia.eDia(p + margem + 60_000, LAT, LON, isDayDaApi = true))
            // Perto do polo, sem nascer nem pôr: vale o que a API disse.
            assertTrue(Astronomia.eDia(ms("2026-12-20T12:00"), 69.65, 18.96, isDayDaApi = true))
        } finally { TimeZone.setDefault(antes) }
    }
}

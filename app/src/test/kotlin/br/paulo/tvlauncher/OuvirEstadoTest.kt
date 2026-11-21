package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.TocandoAgora
import br.paulo.tvlauncher.listen.OuvirEstado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Ouvir parte 1 (17/11/2026): o estado do nosso som que o ":player" conta ao processo principal. */
class OuvirEstadoTest {
    private val e = OuvirEstado.Estado("radio", "abc", "Rádio Sertaneja 106.7", "Henrique & Juliano - Liberdade", true, 4321, 1_000L)

    @Test
    fun idaEVoltaPeloJson() {
        assertEquals(e, OuvirEstado.deJson(OuvirEstado.paraJson(e)))
    }

    @Test
    fun arquivoRuimNaoVira() {
        assertNull(OuvirEstado.deJson(null))
        assertNull(OuvirEstado.deJson(""))
        assertNull(OuvirEstado.deJson("{\"titulo\":\"X\""))            // meio arquivo
        assertNull(OuvirEstado.deJson("{\"titulo\":\"  \",\"pid\":3}")) // sem título
        assertNull(OuvirEstado.deJson("{\"titulo\":\"X\"}"))            // sem pid: não dá para saber se está vivo
    }

    @Test
    fun soValeComOProcessoVivo() {
        assertNotNull(OuvirEstado.valido(e, setOf(1, 4321), 2_000L))
        assertNull(OuvirEstado.valido(e, setOf(1, 999), 2_000L))   // o ":player" caiu: a pílula não pode ficar
        assertNull(OuvirEstado.valido(null, setOf(4321), 2_000L))
        assertNull(OuvirEstado.valido(e, setOf(4321), 500L))   // gravado antes de a TV reiniciar
    }

    @Test
    fun pausadoHaMuitoTempoSome() {
        val p = e.copy(tocando = false)
        assertNotNull(OuvirEstado.valido(p, setOf(4321), 1_000L + TocandoAgora.PAUSADA_MAX_MS))
        assertNull(OuvirEstado.valido(p, setOf(4321), 1_001L + TocandoAgora.PAUSADA_MAX_MS))
        // Tocando não vence por tempo.
        assertNotNull(OuvirEstado.valido(e, setOf(4321), 1_000L + 10 * TocandoAgora.PAUSADA_MAX_MS))
    }

    @Test
    fun soComandosConhecidos() {
        assertEquals(setOf("alternar", "pausar", "continuar", "parar", "avancar", "voltar"), OuvirEstado.COMANDOS)
    }

    @Test
    fun textoLongoECortado() {
        val l = OuvirEstado.deJson(OuvirEstado.paraJson(e.copy(titulo = "a".repeat(500), detalhe = "b".repeat(500))))!!
        assertEquals(120, l.titulo.length)
        assertEquals(200, l.detalhe.length)
    }
}

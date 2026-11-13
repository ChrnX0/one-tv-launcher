package br.paulo.tvlauncher

import android.content.ComponentCallbacks2.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriaTest {
    @Test fun soAbrirOutroAppNaoSolta() = assertFalse(Memoria.soltarCaches(TRIM_MEMORY_UI_HIDDEN))
    @Test fun moderadoComAppNaFrenteNaoSolta() = assertFalse(Memoria.soltarCaches(TRIM_MEMORY_RUNNING_MODERATE))
    @Test fun apertadoSolta() {
        assertTrue(Memoria.soltarCaches(TRIM_MEMORY_RUNNING_LOW))
        assertTrue(Memoria.soltarCaches(TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(Memoria.soltarCaches(TRIM_MEMORY_BACKGROUND))
        assertTrue(Memoria.soltarCaches(TRIM_MEMORY_COMPLETE))
    }
    @Test fun capasDaHomeSoComElaEscondida() {
        assertFalse(Memoria.soltarCapasDaHome(TRIM_MEMORY_RUNNING_CRITICAL, homeNaFrente = true))
        assertTrue(Memoria.soltarCapasDaHome(TRIM_MEMORY_RUNNING_CRITICAL, homeNaFrente = false))
    }
}

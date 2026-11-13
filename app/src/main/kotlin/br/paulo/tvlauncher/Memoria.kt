package br.paulo.tvlauncher

import android.content.ComponentCallbacks2

/**
 * Quando soltar memória (12/11/2026, Sala com 2 GB e a ZRAM cheia). Puro: teste JVM em MemoriaTest.
 * Os números são as constantes do ComponentCallbacks2 (RUNNING_MODERATE 5, RUNNING_LOW 10, RUNNING_CRITICAL 15,
 * UI_HIDDEN 20, BACKGROUND 40, MODERATE 60, COMPLETE 80).
 */
object Memoria {
    /** Caches que se refazem do disco (logos e pôsteres): soltar já no "apertado" com o app na frente. */
    fun soltarCaches(nivel: Int): Boolean =
        nivel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && nivel != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            nivel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

    /** Capas da HOME: só com a HOME fora da tela; com ela na frente, soltar faria a grade piscar. */
    fun soltarCapasDaHome(nivel: Int, homeNaFrente: Boolean): Boolean = !homeNaFrente && soltarCaches(nivel)
}

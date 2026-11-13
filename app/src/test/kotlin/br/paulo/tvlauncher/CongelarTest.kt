package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Congelar pelo shell (12/11/2026): o comando montado e a barreira contra nome de pacote que mudaria o comando. */
class CongelarTest {
    @Test fun congelaEDescongelaNoUsuarioCerto() {
        assertEquals("pm disable-user --user 0 com.google.android.videos", Congelar.comando("com.google.android.videos", true, 0))
        assertEquals("pm enable --user 0 com.google.android.videos", Congelar.comando("com.google.android.videos", false, 0))
        assertEquals("pm enable --user 10 com.a.b", Congelar.comando("com.a.b", false, 10))
    }

    @Test fun recusaNomeQueMudariaOComando() {
        listOf("", "semponto", "com.a; reboot", "com.a b", "com.a\$(id)", "com.a'b", ".com.a", "com..a", "com.a.", "1com.a")
            .forEach { assertNull(it, Congelar.comando(it, true, 0)) }
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.AppStorageRules
import br.paulo.tvlauncher.home.AppStorageRules.Destino
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStorageTest {
    private val nosso = "tv.sofaly.launcher"

    @Test fun appDeUsuarioPodeMover() = assertTrue(AppStorageRules.podeMover("com.retroarch", nosso, sistema = false, protegido = false))
    @Test fun launcherNuncaMove() = assertFalse(AppStorageRules.podeMover(nosso, nosso, sistema = false, protegido = false))
    @Test fun sistemaNaoMove() = assertFalse(AppStorageRules.podeMover("com.google.android.youtube.tv", nosso, sistema = true, protegido = false))
    @Test fun protegidoNaoMove() = assertFalse(AppStorageRules.podeMover("com.adguard.vpn", nosso, sistema = false, protegido = true))
    @Test fun pacoteEstranhoNaoMove() = assertFalse(AppStorageRules.podeMover("a; reboot", nosso, sistema = false, protegido = false))

    @Test fun destinos() {
        assertEquals(Destino.EXTERNO, AppStorageRules.destino(pode = true, noExterno = false, temAdotado = true))
        assertEquals(Destino.NENHUM, AppStorageRules.destino(pode = true, noExterno = false, temAdotado = false))
        // Já está fora: sempre pode voltar, mesmo sem saber do volume agora.
        assertEquals(Destino.DISPOSITIVO, AppStorageRules.destino(pode = true, noExterno = true, temAdotado = false))
        assertEquals(Destino.NENHUM, AppStorageRules.destino(pode = false, noExterno = true, temAdotado = true))
    }

    @Test fun leVolumesAdotados() {
        val saida = "private mounted null\r\nprivate:8,3 mounted 5a1b2c3d-0000-4e5f-8a9b-0123456789ab\nprivate:8,5 unmounted abcd-1234\n"
        assertEquals(listOf("5a1b2c3d-0000-4e5f-8a9b-0123456789ab"), AppStorageRules.adotados(saida))
        assertEquals(emptyList<String>(), AppStorageRules.adotados("private mounted null\n"))
    }

    @Test fun comando() {
        assertEquals("pm move-package com.retroarch internal", AppStorageRules.comando("com.retroarch", null))
        assertEquals("pm move-package com.retroarch 5a1b-2c3d", AppStorageRules.comando("com.retroarch", "5a1b-2c3d"))
        assertNull(AppStorageRules.comando("com.retroarch", "x; rm -rf /"))
        assertNull(AppStorageRules.comando("com.x && reboot", null))
    }

    @Test fun resultado() {
        assertNull(AppStorageRules.falha("Success\r\n"))
        assertEquals("shell", AppStorageRules.falha(null))
        assertEquals("Failure [MOVE_FAILED_INSUFFICIENT_STORAGE]", AppStorageRules.falha("Failure [MOVE_FAILED_INSUFFICIENT_STORAGE]\n"))
    }

    @Test fun appGrandePedeAviso() {
        val mb = 1024L * 1024
        assertTrue(AppStorageRules.arriscado(904 * mb, 100))   // o RetroArch da Sala
        assertTrue(AppStorageRules.arriscado(300 * mb, 1))
        assertTrue(AppStorageRules.arriscado(50 * mb, 20))
        assertFalse(AppStorageRules.arriscado(299 * mb, 19))
    }
}

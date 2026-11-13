package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirarDonoTest {
    // Saída real de `pm list users` na Sala (13/11/2026), com o nome do usuário 0 trocado.
    private val sala = "Users:\n\tUserInfo{0:Dono:4c13} running\n\tUserInfo{11:new_user:1010}\n"

    @Test fun usuariosExtrasSaoOsDiferentesDeZero() {
        assertEquals(listOf(11), VirarDono.usuariosExtras(sala))
        assertEquals(emptyList<Int>(), VirarDono.usuariosExtras("Users:\n\tUserInfo{0:Dono:c13} running\n"))
        assertEquals(emptyList<Int>(), VirarDono.usuariosExtras(""))
    }

    @Test fun soSuccessContaComoCerto() {
        assertTrue(VirarDono.deuCerto("Success: Device owner set to package br.paulo.tvlauncher\nActive admin set to component {...}"))
        assertFalse(VirarDono.deuCerto("java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device."))
        assertFalse(VirarDono.deuCerto(null))
    }
}

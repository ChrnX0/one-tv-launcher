package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class TravaSemDonoTest {
    @Test fun comandoDoAdministrador() {
        assertEquals("dpm set-active-admin --user 0 br.paulo.tvlauncher/br.paulo.tvlauncher.AdminReceiver",
            TravaSemDono.montar("br.paulo.tvlauncher", "br.paulo.tvlauncher.AdminReceiver", 0))
    }
}

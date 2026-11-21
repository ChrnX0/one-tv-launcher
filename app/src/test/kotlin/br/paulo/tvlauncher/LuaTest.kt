package br.paulo.tvlauncher

import br.paulo.tvlauncher.weather.Lua
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class LuaTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")

    // 17/11/2026 10:56 em São Paulo (13:56 UTC): a cheia cai em 24/11, faltando 7,1 dias -> "em 7 d" (antes, 8).
    @Test fun diasPeloCalendario() {
        val agora = 1_794_923_760_000L
        assertEquals(7, Lua.diasNoCalendario(agora, 7.1, sp))
        assertEquals(1, Lua.diasNoCalendario(agora, 0.2, sp))      // ainda hoje: mínimo 1
        assertEquals(1, Lua.diasNoCalendario(agora, 0.6, sp))      // passa da meia-noite: amanhã
        assertEquals(30 - 17, Lua.diasNoCalendario(agora, 12.9, sp))
    }

    @Test fun idadeConhecida() {
        assertEquals(7.67, Lua.idade(1_794_923_760_000L), 0.05)
    }
}

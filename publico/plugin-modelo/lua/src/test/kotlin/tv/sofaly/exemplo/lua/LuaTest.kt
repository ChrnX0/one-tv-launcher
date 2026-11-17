package tv.sofaly.exemplo.lua

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LuaTest {
    // Datas de efemérides públicas (timeanddate.com): Lua cheia 5/11/2025 13:19 UTC, Lua nova 20/11/2025 06:47 UTC.
    @Test fun cheiaENova() {
        assertEquals(Lua.Fase.CHEIA, Lua.fase(Lua.idade(Instant.parse("2025-11-05T13:19:00Z"))))
        assertEquals(Lua.Fase.NOVA, Lua.fase(Lua.idade(Instant.parse("2025-11-20T06:47:00Z"))))
        assertEquals(0, Lua.diasAteCheia(Lua.idade(Instant.parse("2025-11-05T03:00:00Z"))))
    }
}

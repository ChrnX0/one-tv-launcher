package br.paulo.tvlauncher

import br.paulo.tvlauncher.games.Jogos
import br.paulo.tvlauncher.games.Jogos.Grupo
import br.paulo.tvlauncher.network.Rede
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JogosTest {
    @Test fun conhecidoECategoriaSaoJogo() {
        assertTrue(Jogos.ehJogo("com.nvidia.geforcenow", false, emptyMap()))
        assertTrue(Jogos.ehJogo("com.qualquer.corrida", true, emptyMap()))
        assertFalse(Jogos.ehJogo("com.netflix.ninja", false, emptyMap()))
    }

    @Test fun escolhaVenceTudo() {
        assertFalse(Jogos.ehJogo("com.nvidia.geforcenow", false, mapOf("com.nvidia.geforcenow" to Jogos.FORA)))
        assertTrue(Jogos.ehJogo("com.app.qualquer", false, mapOf("com.app.qualquer" to "RETRO")))
        assertEquals(Grupo.RETRO, Jogos.grupo("com.app.qualquer", mapOf("com.app.qualquer" to "RETRO")))
        assertEquals(Grupo.TV, Jogos.grupo("com.limelight", mapOf("com.limelight" to "TV")))
    }

    @Test fun grupoPadrao() {
        assertEquals(Grupo.PC, Jogos.grupo("com.valvesoftware.steamlink", emptyMap()))
        assertEquals(Grupo.NUVEM, Jogos.grupo("com.nvidia.geforcenow", emptyMap()))
        // A versão de TV do PS Remote Play tem outro pacote (o Paulo achou o card sem ele, 14/11/2026).
        assertEquals(Grupo.PC, Jogos.grupo("com.playstation.remoteplay.tv", emptyMap()))
        assertEquals(Grupo.PC, Jogos.grupo("eu.hetko.xbplayer", emptyMap()))   // Xbox (15/11/2026)
        assertEquals(Grupo.PC, Jogos.grupo("com.xstreaming", emptyMap()))
        assertEquals(Grupo.TV, Jogos.grupo("com.jogo.instalado", emptyMap()))
    }

    @Test fun agruparNaOrdemESemGrupoVazio() {
        val apps = listOf("com.jogo.tv" to true, "com.retroarch" to false, "com.netflix.ninja" to false, "com.nvidia.geforcenow" to false)
        val g = Jogos.agrupar(apps, emptyMap())
        assertEquals(listOf(Grupo.NUVEM, Grupo.RETRO, Grupo.TV), g.keys.toList())
        assertEquals(listOf("com.jogo.tv"), g[Grupo.TV])
    }

    @Test fun escolhasIdaEVolta() {
        val m = Jogos.escolher(Jogos.escolher(emptyMap(), "a", "PC"), "b", Jogos.FORA)
        assertEquals(m, Jogos.escolhasDeJson(Jogos.escolhasParaJson(m)))
        assertEquals(mapOf("b" to Jogos.FORA), Jogos.escolher(m, "a", null))
        // Valor desconhecido (versão futura, edição à mão) é ignorado; JSON quebrado = nada.
        assertEquals(emptyMap<String, String>(), Jogos.escolhasDeJson("""{"x":"OUTRO"}"""))
        assertEquals(emptyMap<String, String>(), Jogos.escolhasDeJson("{quebrado"))
        assertEquals(emptyMap<String, String>(), Jogos.escolhasDeJson(null))
    }

    @Test fun appDoPc() {
        assertEquals("com.limelight", Jogos.appDoPc(setOf("com.valvesoftware.steamlink", "com.limelight"), null))
        assertEquals("com.valvesoftware.steamlink", Jogos.appDoPc(setOf("com.valvesoftware.steamlink", "com.limelight"), "com.valvesoftware.steamlink"))
        assertEquals("com.valvesoftware.steamlink", Jogos.appDoPc(setOf("com.valvesoftware.steamlink"), "com.limelight"))
        assertNull(Jogos.appDoPc(emptySet(), null))
    }

    @Test fun rede() {
        assertEquals(Jogos.Rede.SEM, Jogos.avaliarRede(false, false, -50, 10))
        assertEquals(Jogos.Rede.SEM, Jogos.avaliarRede(true, false, -50, null))
        assertEquals(Jogos.Rede.BOA, Jogos.avaliarRede(true, false, -50, 20))
        assertEquals(Jogos.Rede.OK, Jogos.avaliarRede(true, false, -65, 20))
        assertEquals(Jogos.Rede.OK, Jogos.avaliarRede(true, true, 0, 60))
        assertEquals(Jogos.Rede.FRACA, Jogos.avaliarRede(true, false, -75, 20))
        assertEquals(Jogos.Rede.FRACA, Jogos.avaliarRede(true, true, 0, 120))
        // No cabo o sinal Wi-Fi não conta.
        assertEquals(Jogos.Rede.BOA, Jogos.avaliarRede(true, true, -80, 15))
    }

    @Test fun ip() {
        assertTrue(Jogos.ipValido("192.168.1.10"))
        assertFalse(Jogos.ipValido("192.168.1"))
        assertFalse(Jogos.ipValido("192.168.1.300"))
        assertFalse(Jogos.ipValido("pc.lan"))
    }

    @Test fun ordemPorUso() {
        val uso = mapOf("b" to 100L, "c" to 300L)
        assertEquals(listOf("c", "b", "a", "d"), Jogos.ordenarPorUso(listOf("a", "b", "c", "d"), uso))
        assertEquals(listOf("a", "d"), Jogos.ordenarPorUso(listOf("a", "d"), emptyMap()))
        assertEquals("c", Jogos.ultimoUsado(listOf("a", "b", "c"), uso))
        assertNull(Jogos.ultimoUsado(listOf("a"), uso))
    }
}

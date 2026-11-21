package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.SeriesTempo
import br.paulo.tvlauncher.weather.MapaMundo
import br.paulo.tvlauncher.weather.NuvensSatelite
import br.paulo.tvlauncher.weather.VistaMapa
import java.io.DataInputStream
import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CeuDeVerdadeTest {
    @Test fun vistaPoeACidadeNoLugarCertoEVolta() {
        val v = VistaMapa(-23.55, -46.63, 960f, 540f)
        assertEquals(960f * 0.62f, v.x(-46.63), 0.01f); assertEquals(540f * 0.46f, v.y(-23.55), 0.01f)
        assertEquals(-40.0, v.lon(v.x(-40.0)), 1e-6); assertEquals(-20.0, v.lat(v.y(-20.0)), 1e-6)
        val c = v.caixa()
        assertEquals(VistaMapa.ALTURA_GRAUS.toDouble(), c[3] - c[1], 1e-4)   // 9° de altura
        assertTrue(c[0] < -46.63 && c[2] > -46.63)
    }

    @Test fun satelitePorLongitude() {
        assertEquals("GOES-East_ABI_Band13_Clean_Infrared", NuvensSatelite.camada(-46.6))
        assertEquals("GOES-East_ABI_Band13_Clean_Infrared", NuvensSatelite.camada(-74.0))
        assertEquals("GOES-West_ABI_Band13_Clean_Infrared", NuvensSatelite.camada(-122.4))
        assertNull(NuvensSatelite.camada(-0.12)); assertNull(NuvensSatelite.camada(139.7))
    }

    @Test fun opacidadeDaNuvem() {
        assertEquals(0, NuvensSatelite.opacidade(200, 200, 200, 0))   // fora do disco
        assertEquals(0, NuvensSatelite.opacidade(90, 90, 90, 255))     // chão quente
        assertEquals(190, NuvensSatelite.opacidade(240, 240, 240, 255)) // topo frio
        assertEquals(200, NuvensSatelite.opacidade(255, 104, 0, 255))   // colorido = tempestade
        assertTrue(NuvensSatelite.opacidade(180, 180, 180, 255) in 40..150)
        // Borrar espalha um ponto sem mudar a soma (longe da borda).
        val a = IntArray(100).also { it[55] = 250 }
        val b = NuvensSatelite.borrar(a, 10, 10)
        assertEquals(10, b[55]); assertEquals(10, b[33]); assertEquals(0, b[11])
    }

    @Test fun mapaEmbutidoTemACostaDeSaoPaulo() {
        val arq = listOf(File("src/main/assets/mapa/mundo.bin"), File("app/src/main/assets/mapa/mundo.bin")).first { it.exists() }
        val linhas = DataInputStream(arq.inputStream().buffered()).use { MapaMundo.ler(it) }
        assertTrue(linhas.size > 2000)
        assertEquals(setOf(0, 1, 2), linhas.map { it.camada }.toSet())
        // Algum ponto de costa a menos de 0,4° de Santos (-23,96, -46,33): na escala 1:50m os pontos ficam a dezenas de km.
        val perto = linhas.filter { it.camada == 0 }.any { l ->
            (0 until l.pontos.size / 2).any { i -> kotlin.math.abs(l.pontos[2 * i] / 100.0 + 46.33) < 0.4 && kotlin.math.abs(l.pontos[2 * i + 1] / 100.0 + 23.96) < 0.4 }
        }
        assertTrue(perto)
    }

    @Test fun seriesIdaEVolta() {
        val pares = listOf(1000L to 3.456, 2000L to 0.0)
        assertEquals(listOf(1000L to 3.46, 2000L to 0.0), SeriesTempo.ler(SeriesTempo.escrever(pares)))
        assertTrue(SeriesTempo.ler(null).isEmpty()); assertEquals(1, SeriesTempo.ler("x;1|2;3|").size)
        assertEquals(1794949200000L, SeriesTempo.msLocal("2026-11-17T18:00", TimeZone.getTimeZone("GMT-3")))
    }
}

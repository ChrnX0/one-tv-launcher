package br.paulo.tvlauncher

import br.paulo.tvlauncher.RetroLibrary.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroLibraryTest {
    private val pen = "/storage/8991-1F25"

    @Test fun consolePelaExtensaoOuPasta() {
        assertEquals(Console.SNES, RetroLibrary.console("$pen/Games/Retro-Arch/Roms/Snes/F/F-Zero (USA).sfc"))
        assertEquals(Console.SNES, RetroLibrary.console("$pen/Jogos/Super Mario World.smc"))   // extensão exclusiva vence
        assertEquals(Console.PS1, RetroLibrary.console("$pen/Roms/PSX/Crash.bin"))
        assertEquals(Console.MD, RetroLibrary.console("$pen/Roms/Mega_Drive/Sonic.zip"))
        assertNull(RetroLibrary.console("$pen/Roms/Crash.bin"))     // genérica sem pasta do console
        assertNull(RetroLibrary.console("$pen/Filmes/filme.mkv"))
    }

    @Test fun nomeLimpo() {
        assertEquals("Super Mario World", RetroLibrary.nomeLimpo("/x/Super Mario World (USA) [!].sfc"))
        assertEquals("The Legend of Zelda - A Link to the Past",
            RetroLibrary.nomeLimpo("/x/Legend of Zelda, The - A Link to the Past (USA).sfc"))
        assertEquals("Donkey Kong Country", RetroLibrary.nomeLimpo("/x/Donkey_Kong_Country (Rev 1).smc"))
    }

    /** Nomes reais do pen drive da Sala (21/11/2026). */
    @Test fun nomesDoPenDriveDaSala() {
        assertEquals("The Lion King", RetroLibrary.nomeLimpo("/s/Lion King The.smc"))
        assertEquals("The Jungle Book", RetroLibrary.nomeLimpo("/s/Jungle Book, The.sfc"))
        assertEquals("Knights of the Round", RetroLibrary.nomeLimpo("/s/Knights of the Round .sfc.sfc"))
        assertEquals("Batman Returns", RetroLibrary.nomeLimpo("/s/Batman Returns (BR.sfc"))
        assertEquals("The Great Circus Mystery Starring Mickey & Minnie",
            RetroLibrary.nomeLimpo("/s/Great Circus Mystery Starring Mickey & Minnie, The (USA) (PT-BR).smc"))
        assertEquals("Super Mario World 2025", RetroLibrary.nomeLimpo("/s/Super Mario World 2025 (PT-BR) .sfc"))
        assertTrue(RetroLibrary.portugues("/s/Gradius III (USA) (PT-BR).smc"))
        assertTrue(RetroLibrary.portugues("/s/Beauty and the Beast (BR-U).sfc"))
        assertTrue(!RetroLibrary.portugues("/s/Brain Lord.smc"))
        // Mesmo jogo em .sfc e .smc (Super Castlevania IV na Sala) fica um só; arquivo sem extensão fica de fora.
        val j = RetroLibrary.montar("$pen/Snes/S/Super Castlevania IV.sfc\n$pen/Snes/S/Super Castlevania IV.smc\n" +
            "$pen/Snes/F/F-zero\n$pen/System Volume Information/WPSettings.dat")
        assertEquals(listOf("Super Castlevania IV"), j.map { it.nome })
    }

    @Test fun montarTiraRepetidosEPreferRegiao() {
        val find = listOf(
            "$pen/Roms/Snes/Super Mario World (Japan).sfc",
            "$pen/Roms/Snes/Super Mario World (USA).smc",
            "$pen/Roms/Snes/Super Mario World (USA).png",
            "$pen/Roms/Snes/Aladdin.sfc",
            "$pen/Roms/Snes/._Aladdin.sfc",
            "$pen/Android/data/com.retroarch/files/sofaly/Aladdin.sfc",
            "$pen/Roms/GBA/Pokemon.gba",
            "$pen/Roms/leia-me.txt",
        ).joinToString("\r\n")
        val jogos = RetroLibrary.montar(find)
        assertEquals(listOf("Aladdin", "Super Mario World", "Pokemon"), jogos.map { it.nome })
        val mario = jogos[1]
        assertTrue(mario.caminho.endsWith("(USA).smc"))
        assertEquals("$pen/Roms/Snes/Super Mario World (USA).png", mario.capa)
        assertEquals(listOf(Console.SNES to 2, Console.GBA to 1), RetroLibrary.porConsole(jogos))
    }

    @Test fun continuarPelaUltimaVez() {
        val jogos = RetroLibrary.montar("/a/snes/A.sfc\n/a/snes/B.sfc\n/a/snes/C.sfc")
        val r = RetroLibrary.continuar(jogos, mapOf("/a/snes/C.sfc" to 20L, "/a/snes/A.sfc" to 10L))
        assertEquals(listOf("C", "A"), r.map { it.nome })
    }

    @Test fun destinoNoMesmoPenDrive() {
        assertEquals("$pen/Android/data/com.retroarch/files/sofaly/F-Zero (USA).sfc",
            RetroLibrary.destinoNoEmulador("$pen/Roms/Snes/F-Zero (USA).sfc"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch/files/sofaly/x.gba",
            RetroLibrary.destinoNoEmulador("/storage/emulated/0/Roms/x.gba"))
        assertNull(RetroLibrary.destinoNoEmulador("/data/local/tmp/x.gba"))
    }

    @Test fun comandoCopiarProtegeAspas() {
        val c = RetroLibrary.comandoCopiar("/a/Jogo's (USA).sfc", "/b/Jogo's (USA).sfc")
        assertTrue(c, c.contains("cp '/a/Jogo'\\''s (USA).sfc' '/b/Jogo'\\''s (USA).sfc'"))
        assertTrue(c.startsWith("mkdir -p '/b'"))
    }

    @Test fun extrasDoRetroArch() {
        // Pasta real da Sala (21/11/2026): os núcleos da Play ficam em lib/arm do app, como lib<nucleo>.so.
        val e = RetroLibrary.extrasRetroArch("/x/a.sfc", "snes9x", "/data/app/~~x/com.retroarch-y/lib/arm")
        assertEquals("/data/app/~~x/com.retroarch-y/lib/arm/libsnes9x.so", e["LIBRETRO"])
        assertNull(e["CONFIGFILE"])
        assertEquals("/x/a.sfc", e["ROM"])
    }

    /** Módulos reais do RetroArch da Play na Sala (21/11/2026): pega o primeiro núcleo instalado da lista do console. */
    @Test fun nucleoPelosModulosInstalados() {
        val sala = listOf("config.armeabi_v7a", "core_bsnes", "core_bsnes.config.armeabi_v7a", "core_snes9x",
            "core_snes9x.config.armeabi_v7a", "core_picodrive", "core_mgba")
        assertEquals("snes9x", RetroLibrary.nucleoInstalado(Console.SNES, sala))
        assertEquals("picodrive", RetroLibrary.nucleoInstalado(Console.MD, sala))      // sem genesis_plus_gx: o próximo
        assertEquals("mgba", RetroLibrary.nucleoInstalado(Console.GB, sala))
        assertNull(RetroLibrary.nucleoInstalado(Console.PS1, sala))                    // nenhum: baixar no RetroArch
        assertNull(RetroLibrary.nucleoInstalado(Console.SNES, listOf("core_snes9x.config.armeabi_v7a")))
    }
}

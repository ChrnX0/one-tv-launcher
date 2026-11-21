package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.BackupConfig
import br.paulo.tvlauncher.player.FonteTv
import br.paulo.tvlauncher.player.M3u
import br.paulo.tvlauncher.player.PlayerDados
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDadosTetoTest {
    @Test fun numeroRepetidoNoTetoDoIntNaoViraNegativo() {
        val base = M3u.ler("#EXTINF:-1,Um\nhttp://e.invalid/1.ts\n#EXTINF:-1,Dois\nhttp://e.invalid/2.ts\n#EXTINF:-1,Tres\nhttp://e.invalid/3.ts\n")
        val l = listOf(base[0].copy(numero = Int.MAX_VALUE), base[1].copy(numero = Int.MAX_VALUE), base[2].copy(numero = 1))
        val canais = PlayerDados.juntar(listOf(FonteTv("a1", "L", FonteTv.TIPO_M3U, "http://e.invalid/l") to l))
        assertTrue(canais.all { it.numero > 0 })
        assertEquals(canais.size, canais.map { it.numero }.toSet().size)   // nenhum número repetido
    }
}

class BackupConfigPrefixoTest {
    @Test fun chavesComParteVariavelTemTipoFixo() {
        assertEquals("s", BackupConfig.tipoPorPrefixo("launcher", "botao_99"))
        assertEquals("s", BackupConfig.tipoPorPrefixo("launcher", "ordem@joana"))
        assertEquals(null, BackupConfig.tipoPorPrefixo("launcher", "outra"))
        assertEquals(null, BackupConfig.tipoPorPrefixo("dns", "botao_1"))
    }
}

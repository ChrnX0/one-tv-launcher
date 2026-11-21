package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.StorageModeRules
import br.paulo.tvlauncher.backup.StorageModeRules.Modo
import br.paulo.tvlauncher.backup.StorageModeRules.Motivo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageModeRulesTest {
    private val GB = 1024L * 1024 * 1024

    // Saída real da Sala em 20/11/2026 (pen drive adotado inteiro).
    private val sala = "emulated:8,2;0 unmounted null\r\nprivate mounted null\nemulated;0 mounted null\nprivate:8,2 mounted dba6ec11-3e62-4776-b0d1-5bf4079f3f95\n"

    @Test fun discos() = assertEquals(listOf("disk:8,0"), StorageModeRules.discos("disk:8,0\r\nlixo; reboot\n"))

    @Test fun modoDaSala() {
        val v = StorageModeRules.doDisco("disk:8,0", StorageModeRules.volumes(sala))
        assertEquals(listOf("private:8,2"), v.map { it.id })
        assertEquals(Modo.MEMORIA_TV, StorageModeRules.modo(v))
    }

    @Test fun misto() {
        val v = StorageModeRules.volumes("public:8,1 mounted 8991-1F25\nprivate:8,2 mounted abc-1\nprivate mounted null")
        assertEquals(Modo.MISTO, StorageModeRules.modo(StorageModeRules.doDisco("disk:8,0", v)))
    }

    @Test fun comumEOutroDisco() {
        val v = StorageModeRules.volumes("public:8,1 mounted 8991-1F25\npublic:8,17 mounted 1111-2222")
        assertEquals(Modo.COMUM, StorageModeRules.modo(StorageModeRules.doDisco("disk:8,0", v)))
        assertEquals(listOf("public:8,17"), StorageModeRules.doDisco("disk:8,16", v).map { it.id })
    }

    @Test fun recomendacoes() {
        assertEquals(Motivo.PEN_LENTO, StorageModeRules.recomendar(1 * GB, 64 * GB, 3.0).motivo)
        assertEquals(Motivo.PEN_PEQUENO, StorageModeRules.recomendar(1 * GB, 4 * GB, null).motivo)
        assertEquals(Motivo.TV_FOLGADA, StorageModeRules.recomendar(6 * GB, 64 * GB, 20.0).motivo)
        assertEquals(Modo.MISTO, StorageModeRules.recomendar(GB, 32 * GB, 11.0).modo)
        assertEquals(Modo.MEMORIA_TV, StorageModeRules.recomendar(GB, 16 * GB, 11.0).modo)
    }

    @Test fun comandos() {
        assertEquals("sm partition disk:8,0 public", StorageModeRules.comandoParticao("disk:8,0", Modo.COMUM))
        assertEquals("sm partition disk:8,0 private", StorageModeRules.comandoParticao("disk:8,0", Modo.MEMORIA_TV))
        assertEquals("sm partition disk:8,0 mixed 25", StorageModeRules.comandoParticao("disk:8,0", Modo.MISTO, 25))
        assertNull(StorageModeRules.comandoParticao("disk:8,0", Modo.MISTO, 100))
        assertNull(StorageModeRules.comandoParticao("disk:8,0; reboot", Modo.COMUM))
    }

    @Test fun resultadoParticao() {
        assertNull(StorageModeRules.falhaParticao(""))
        assertEquals("shell", StorageModeRules.falhaParticao(null))
        assertEquals("java.lang.IllegalStateException: Failed to partition",
            StorageModeRules.falhaParticao("java.lang.IllegalStateException: Failed to partition\n\tat x"))
    }

    @Test fun df() {
        val m = StorageModeRules.df("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-5 30000000 1000 29000000 1% /mnt/expand/dba6\n")
        assertEquals(30000000L * 1024 to 29000000L * 1024, m["/mnt/expand/dba6"])
    }
    @Test fun dd() {
        assertEquals(12.8, StorageModeRules.velocidadeDd("33554432 bytes (32 M) copied, 2.5 s, 13 M/s")!!, 0.01)
        assertNull(StorageModeRules.velocidadeDd("dd: permission denied"))
    }
    @Test fun caminhos() {
        org.junit.Assert.assertTrue(StorageModeRules.caminhoSeguro("/storage/8991-1F25"))
        org.junit.Assert.assertFalse(StorageModeRules.caminhoSeguro("/storage/x'; reboot"))
    }

    @Test fun penLentoNaoViraMemoriaDaTv() {
        assertTrue(StorageModeRules.podeFormatar(Modo.COMUM, 1.0, medivel = true))
        assertFalse(StorageModeRules.podeFormatar(Modo.MEMORIA_TV, null, medivel = true))   // sem medida: mede antes
        assertFalse(StorageModeRules.podeFormatar(Modo.MISTO, 9.9, medivel = true))
        assertTrue(StorageModeRules.podeFormatar(Modo.MISTO, 10.0, medivel = true))
        assertTrue(StorageModeRules.podeFormatar(Modo.MEMORIA_TV, null, medivel = false))   // já adotado inteiro
        assertFalse(StorageModeRules.podeFormatar(Modo.DESCONHECIDO, 50.0, medivel = true))
        val gb = 1024L * 1024 * 1024
        assertEquals(StorageModeRules.Motivo.PEN_LENTO, StorageModeRules.recomendar(1 * gb, 64 * gb, 8.0).motivo)
    }
}

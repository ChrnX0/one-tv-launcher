package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacoteDivididoTest {
    private val tv32 = listOf("armeabi-v7a", "armeabi")
    private val tv64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test fun arquitetura() {
        assertEquals("arm64-v8a", PacoteDividido.arquitetura("split_config.arm64_v8a.apk"))
        assertEquals("armeabi-v7a", PacoteDividido.arquitetura("config.armeabi_v7a.apk"))
        assertEquals("x86_64", PacoteDividido.arquitetura("splits/base-x86_64.apk"))
        assertEquals("x86", PacoteDividido.arquitetura("splits/base-x86.apk"))
        assertNull(PacoteDividido.arquitetura("split_config.xxhdpi.apk"))
        assertNull(PacoteDividido.arquitetura("base.apk"))
        assertNull(PacoteDividido.arquitetura("com.netflix.ninja.apk"))
    }

    @Test fun xapkNaTv32() {
        val z = listOf("manifest.json", "icon.png", "com.app.apk", "config.arm64_v8a.apk", "config.armeabi_v7a.apk",
            "config.xhdpi.apk", "config.pt.apk", "Android/obb/com.app/main.1.com.app.obb")
        assertEquals(listOf("com.app.apk", "config.armeabi_v7a.apk", "config.xhdpi.apk", "config.pt.apk"),
            PacoteDividido.partes(z, tv32))
        assertTrue(PacoteDividido.temObb(z))
        assertEquals("com.app.apk", PacoteDividido.escolherBase(PacoteDividido.partes(z, tv32)!!))
    }

    @Test fun apksDoBundletool() {
        val z = listOf("toc.pb", "splits/base-master.apk", "splits/base-xxhdpi.apk", "splits/base-arm64_v8a.apk",
            "splits/base-armeabi_v7a.apk", "standalones/standalone-armeabi_v7a.apk")
        val p = PacoteDividido.partes(z, tv64)!!
        assertEquals(4, p.size)
        assertFalse(p.any { it.startsWith("standalones/") })
        assertEquals("splits/base-master.apk", PacoteDividido.escolherBase(listOf("splits/base-xxhdpi.apk") + p))
    }

    @Test fun soArm64NaoServeNaTv32() {
        assertNull(PacoteDividido.partes(listOf("base.apk", "split_config.arm64_v8a.apk"), tv32))
        assertNull(PacoteDividido.partes(listOf("leiame.txt"), tv32))
        // Sem parte de arquitetura (app só Java/Kotlin): vai tudo.
        assertEquals(2, PacoteDividido.partes(listOf("base.apk", "split_config.xhdpi.apk"), tv32)!!.size)
    }

    @Test fun recusaCaminhoEstranhoETeto() {
        val p = PacoteDividido.partes(listOf("base.apk", "../fora.apk", "/abs.apk", "pasta/"), tv32)!!
        assertEquals(listOf("base.apk"), p)
        assertNull(PacoteDividido.partes((0..PacoteDividido.MAX_PARTES).map { "split_$it.apk" }, tv32))
    }

    @Test fun nomeNaSessaoEBase() {
        assertEquals("3_base.apk", PacoteDividido.nomeNaSessao("splits/base.apk", 3))
        assertEquals("0_a_b.apk", PacoteDividido.nomeNaSessao("x/a b.apk", 0))
        assertTrue(PacoteDividido.ehBase("0_com.app.apk"))
        assertFalse(PacoteDividido.ehBase("1_split_config.pt.apk"))
        assertFalse(PacoteDividido.ehBase("2_config.armeabi_v7a.apk"))
        assertEquals("0_base.apk", PacoteDividido.escolherBase(listOf("1_split_x.apk", "0_base.apk")))
    }
}

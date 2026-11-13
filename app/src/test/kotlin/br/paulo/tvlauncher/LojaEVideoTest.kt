package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Loja curada (escolha do APK, confiança no 1º uso) e vídeo ao vivo (corte do H.264). gradlew testReleaseUnitTest. */
class LojaEVideoTest {
    @Test fun escolheApkDe32Bits() {
        assertEquals("app-armeabi-v7a-release.apk", LojaCurada.escolherApk(listOf("app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk", "app-universal.apk")))
        assertEquals("app-universal.apk", LojaCurada.escolherApk(listOf("app-arm64-v8a.apk", "app-universal.apk", "notas.txt")))
        assertEquals("NewPipe_v0.27.apk", LojaCurada.escolherApk(listOf("NewPipe_v0.27.apk", "checksums.txt")))
        assertNull("só 64 bits", LojaCurada.escolherApk(listOf("app-arm64-v8a.apk")))
        assertNull("dois sem dizer arquitetura: não chuta", LojaCurada.escolherApk(listOf("a.apk", "b.apk")))
        assertNull("debug não", LojaCurada.escolherApk(listOf("app-debug.apk")))
    }

    @Test fun confiaNoPrimeiroUsoDepoisCobra() {
        assertNull(LojaCurada.conferir("org.x", setOf("aa"), null))
        assertNull(LojaCurada.conferir("org.x", setOf("aa"), "org.x" to setOf("aa")))
        assertNotNull("outra assinatura", LojaCurada.conferir("org.x", setOf("bb"), "org.x" to setOf("aa")))
        assertNotNull("outro pacote", LojaCurada.conferir("org.y", setOf("aa"), "org.x" to setOf("aa")))
        assertNotNull("sem assinatura", LojaCurada.conferir("org.x", emptySet(), null))
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun separaNalMesmoEmPedacos() {
        val fluxo = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        val sep = VideoAoVivo.SeparadorNal()
        val saida = ArrayList<ByteArray>()
        for (x in fluxo) saida += sep.colocar(byteArrayOf(x))   // um byte por vez: o pior caso
        sep.fim()?.let { saida += it }
        assertEquals(3, saida.size)
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 1, 2), saida[0])
        assertArrayEquals(b(0, 0, 1, 0x68, 3), saida[1])
        assertArrayEquals(b(0, 0, 0, 1, 0x65, 9, 9), saida[2])
    }

    @Test fun quadroComTamanho() {
        assertArrayEquals(b(0, 0, 1, 2, 7, 7, 7) .copyOfRange(0, 4) + ByteArray(258) { 7 }, VideoAoVivo.quadro(ByteArray(258) { 7 }))
    }

    @Test(expected = IllegalStateException::class) fun nalGiganteEhLixo() {
        VideoAoVivo.SeparadorNal(maximo = 10).colocar(ByteArray(20))
    }
}

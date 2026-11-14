package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FotosRedeTest {
    private fun e(id: String, tipo: Upnp.Tipo, pasta: Boolean = false) = Upnp.Entrada(id, id.substringAfterLast('/'), pasta, null, tipo, null)

    @Test fun origemIdaEVoltaSemSenha() {
        val o = FotosRede.Origem("abc", ContaMidia.SMB, "Fotos/2026", "2026")
        val j = FotosRede.paraJson(o, 42L)
        assertFalse(j.contains("senha"))
        assertEquals(o to 42L, FotosRede.deJson(j))
    }

    @Test fun origemInvalidaVira_null() {
        assertNull(FotosRede.deJson(null))
        assertNull(FotosRede.deJson("lixo"))
        assertNull(FotosRede.deJson("""{"conta":"x","tipo":"plex","caminho":""}"""))   // só SMB e WebDAV
        assertNull(FotosRede.deJson("""{"conta":"","tipo":"smb"}"""))
    }

    @Test fun soFotosPorNomeComTeto() {
        val l = listOf(e("b.jpg", Upnp.Tipo.FOTO), e("Sub", Upnp.Tipo.PASTA, true), e("a.png", Upnp.Tipo.FOTO), e("v.mp4", Upnp.Tipo.VIDEO))
        assertEquals(listOf("a.png", "b.jpg"), FotosRede.fotosDa(l).map { it.id })
        assertEquals(1, FotosRede.fotosDa(l, max = 1).size)
    }

    @Test fun nomeLocalEstavelESeguro() {
        val n = FotosRede.nomeLocal("Fotos/../x.jpg")
        assertEquals(n, FotosRede.nomeLocal("Fotos/../x.jpg"))
        assertNotEquals(n, FotosRede.nomeLocal("Fotos/y.jpg"))
        assertTrue(Regex("[0-9a-f]{24}\\.jpg").matches(n))
    }

    @Test fun planoBaixaNovasEApagaSumidas() {
        val velha = FotosRede.nomeLocal("a.jpg")
        val sumiu = FotosRede.nomeLocal("c.jpg")
        val (baixar, apagar) = FotosRede.plano(listOf("a.jpg", "b.jpg"), setOf(velha, sumiu, ".baixando.tmp"))
        assertEquals(listOf("b.jpg"), baixar)
        assertEquals(setOf(sumiu, ".baixando.tmp"), apagar.toSet())
    }

    @Test fun precisaACada6Horas() {
        val h = 60 * 60_000L
        assertFalse(FotosRede.precisa(false, 0, 10 * h))
        assertTrue(FotosRede.precisa(true, 0, 10 * h))
        assertFalse(FotosRede.precisa(true, 9 * h, 10 * h))
        assertTrue(FotosRede.precisa(true, 20 * h, 10 * h))   // relógio voltou: renova
    }
}

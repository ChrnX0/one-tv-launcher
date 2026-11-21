package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.SemInternet
import br.paulo.tvlauncher.network.SemInternet.Estado
import br.paulo.tvlauncher.network.SemInternet.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemInternetTest {
    private val nomes = mapOf(Item.PENDRIVE to "pendrive", Item.NA_REDE to "Na rede", Item.GRAVACOES to "Gravações")
    private fun frase(e: Estado) = SemInternet.frase(SemInternet.itens(e), "Sem internet", { "Funcionam: $it" }, nomes)

    @Test fun tudoFuncionando() {
        val e = Estado(redeLocal = true, pendrive = true, servidores = listOf("192.168.1.10"), destinoGravacao = "pendrive")
        assertEquals("Sem internet · Funcionam: pendrive, Na rede, Gravações", frase(e))
    }

    @Test fun semWifiNaRedeEGravacaoNaRedeSaem() {
        val e = Estado(redeLocal = false, pendrive = true, servidores = listOf("192.168.1.10"), destinoGravacao = "rede")
        assertEquals(listOf(Item.PENDRIVE), SemInternet.itens(e))
    }

    @Test fun servidorDaInternetNaoConta() {
        val e = Estado(redeLocal = true, pendrive = false, servidores = listOf("https://meu.plex.direct:32400"), destinoGravacao = null)
        assertEquals("Sem internet", frase(e))
    }

    @Test fun gravacaoNoPendriveSemPendriveNaoConta() {
        val e = Estado(redeLocal = true, pendrive = false, servidores = emptyList(), destinoGravacao = "pendrive")
        assertTrue(SemInternet.itens(e).isEmpty())
    }

    @Test fun servidoresDeCasa() {
        for (s in listOf("192.168.1.5", "\\\\NAS\\filmes", "http://192.168.1.5:8096/", "smb://paulo@nas/pasta",
            "nas.local", "10.0.0.2:21", "[fd00::1]:8096", "172.20.0.1"))
            assertTrue(s, SemInternet.servidorDeCasa(s))
        for (s in listOf("https://jellyfin.exemplo.com", "8.8.8.8", "172.32.0.1", "ftp://ftp.exemplo.org/pub", "[2001:db8::1]", ""))
            assertFalse(s, SemInternet.servidorDeCasa(s))
    }
}

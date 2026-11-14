package br.paulo.tvlauncher

import br.paulo.tvlauncher.FilaRede.Passo
import br.paulo.tvlauncher.Upnp.Tipo
import org.junit.Assert.assertEquals
import org.junit.Test

class FilaRedeTest {
    private val tipos = listOf(null, Tipo.VIDEO, Tipo.AUDIO, Tipo.FOTO, Tipo.VIDEO, Tipo.AUDIO)

    @Test fun proximoDoMesmoTipo() {
        assertEquals(4, FilaRede.proximo(tipos, 1, Tipo.VIDEO))
        assertEquals(5, FilaRede.proximo(tipos, 2, Tipo.AUDIO))
        assertEquals(-1, FilaRede.proximo(tipos, 4, Tipo.VIDEO))
        assertEquals(-1, FilaRede.proximo(tipos, 0, Tipo.FOTO))
    }

    @Test fun musicaEmendaDireto() {
        assertEquals(Passo.TOCAR_JA, FilaRede.depois(Tipo.AUDIO, true, true, false, 50))
        assertEquals(Passo.NADA, FilaRede.depois(Tipo.AUDIO, false, true, false, 0))
        assertEquals(Passo.NADA, FilaRede.depois(Tipo.AUDIO, true, true, true, 0))
        assertEquals(Passo.NADA, FilaRede.depois(Tipo.AUDIO, true, false, false, 0))
    }

    @Test fun videoContaEPergunta() {
        assertEquals(Passo.CONTAR, FilaRede.depois(Tipo.VIDEO, true, true, false, 1))
        assertEquals(Passo.PERGUNTAR, FilaRede.depois(Tipo.VIDEO, true, true, false, FimDoVideo.SEGUIDOS_MAX))
        assertEquals(Passo.NADA, FilaRede.depois(Tipo.VIDEO, true, true, true, 0))
        assertEquals(Passo.NADA, FilaRede.depois(Tipo.VIDEO, false, true, false, 0))
    }
}

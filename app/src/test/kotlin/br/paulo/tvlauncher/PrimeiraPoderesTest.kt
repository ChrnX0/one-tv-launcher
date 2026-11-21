package br.paulo.tvlauncher

import br.paulo.tvlauncher.privilege.PrimeiraPoderes
import br.paulo.tvlauncher.privilege.PrimeiraPoderes.Estado
import br.paulo.tvlauncher.privilege.PrimeiraPoderes.Passo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pareamento guiado pelo celular (degrau 2, item 6): a ordem dos passos. */
class PrimeiraPoderesTest {
    @Test fun ordem() {
        assertEquals(Passo.TECLAS, PrimeiraPoderes.proximo(Estado(false, false, false, false)))
        assertEquals(Passo.DEV, PrimeiraPoderes.proximo(Estado(true, false, false, false)))
        assertEquals(Passo.PAREAR, PrimeiraPoderes.proximo(Estado(true, true, false, false)))
        assertEquals(Passo.DONO, PrimeiraPoderes.proximo(Estado(true, true, true, false)))
        assertNull(PrimeiraPoderes.proximo(Estado(true, true, true, true)))
    }

    @Test fun shellDispensaOpcoesDoDesenvolvedor() {
        // Alguém desligou as Opções do desenvolvedor depois de parear: o shell segue, não volta a pedir o passo 2.
        assertEquals(Passo.DONO, PrimeiraPoderes.proximo(Estado(true, false, true, false)))
        assertEquals(true, PrimeiraPoderes.json(Estado(true, false, true, false)).getBoolean("dev"))
    }

    @Test fun idsDoPainel() {
        assertEquals(Passo.PAREAR, Passo.de("parear"))
        assertNull(Passo.de("wipe"))
        assertNull(Passo.de(null))
    }
}

package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.Perfil
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoSeTest {
    private val nascer = 6 * 3_600_000L
    private val por = 18 * 3_600_000L
    private fun e(agora: Long = 12 * 3_600_000L, emCasa: Boolean? = true, dia: Int = Calendar.MONDAY, perfil: String? = "ana@x.com") =
        SoSe.Estado(agora, emCasa, nascer, por, dia, perfil = perfil)

    @Test fun alguem_em_casa() {
        assertTrue(SoSe.vale(SoSe.Condicao.AlguemEmCasa, e()))
        assertFalse(SoSe.vale(SoSe.Condicao.AlguemEmCasa, e(emCasa = false)))
        assertFalse(SoSe.vale(SoSe.Condicao.AlguemEmCasa, e(emCasa = null)))      // não sei: não roda
    }

    @Test fun depois_do_por_do_sol() {
        assertFalse(SoSe.vale(SoSe.Condicao.DepoisDoPorDoSol, e(agora = 12 * 3_600_000L)))
        assertTrue(SoSe.vale(SoSe.Condicao.DepoisDoPorDoSol, e(agora = 19 * 3_600_000L)))
        assertTrue(SoSe.vale(SoSe.Condicao.DepoisDoPorDoSol, e(agora = 3 * 3_600_000L)))   // madrugada ainda é noite
        assertFalse(SoSe.vale(SoSe.Condicao.DepoisDoPorDoSol, SoSe.Estado(19 * 3_600_000L)))  // sem localização: não roda
    }

    @Test fun dia_util() {
        assertTrue(SoSe.vale(SoSe.Condicao.DiaUtil, e(dia = Calendar.FRIDAY)))
        assertFalse(SoSe.vale(SoSe.Condicao.DiaUtil, e(dia = Calendar.SATURDAY)))
        assertFalse(SoSe.vale(SoSe.Condicao.DiaUtil, e(dia = Calendar.SUNDAY)))
        assertFalse(SoSe.vale(SoSe.Condicao.DiaUtil, e().copy(feriado = true)))
    }

    @Test fun perfil_ativo() {
        assertTrue(SoSe.vale(SoSe.Condicao.Perfil("ANA@x.com"), e()))
        assertFalse(SoSe.vale(SoSe.Condicao.Perfil("bia@x.com"), e()))
        assertFalse(SoSe.vale(SoSe.Condicao.Perfil("ana@x.com"), e(perfil = null)))
    }

    @Test fun todas_juntas_e_gravar() {
        val l = listOf(SoSe.Condicao.AlguemEmCasa, SoSe.Condicao.DepoisDoPorDoSol)
        assertFalse(SoSe.todas(l, e()))
        assertTrue(SoSe.todas(l, e(agora = 20 * 3_600_000L)))
        assertTrue(SoSe.todas(emptyList(), e(emCasa = false)))
        val m = mapOf(Gatilhos.Evento.MIDIA_TOCOU to l, Gatilhos.Evento.CAMPAINHA to listOf(SoSe.Condicao.Perfil("ana@x.com")))
        assertEquals(m, SoSe.lerJson(SoSe.paraJson(m)))
        assertTrue(SoSe.lerJson("""{"midia_tocou":["xyz"]}""").isEmpty())   // condição desconhecida (backup de versão nova) sai
        assertEquals(6, SoSe.opcoes(listOf("a@x.com", "b@x.com")).size)
    }
}

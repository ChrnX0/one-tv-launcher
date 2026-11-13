package br.paulo.tvlauncher

import br.paulo.tvlauncher.VigiaRegras.Estado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atualização vigiada (10/11/2026): a máquina de estados e a conferência do APK. */
class VigiaRegrasTest {
    private val min = 60_000L
    private val t0 = VigiaRegras.RELOGIO_MINIMO_MS + 30L * 24 * 60 * min   // um mês depois do piso do relógio

    /** A TV estava na 100 e acabou de abrir a 101. */
    private fun recemAtualizada(): Estado = VigiaRegras.aoIniciar(Estado(versaoVista = 100), 101, t0)

    @Test fun relogioErradoNoBootNaoMexe() {
        val e = recemAtualizada()
        // TV reiniciou e abriu antes do NTP (hora em 1970): a vigia não acaba nem a queda conta.
        val cedo = 5 * min
        assertEquals(e, VigiaRegras.aoIniciar(e, 101, cedo))
        val q = VigiaRegras.aoCair(e, 101, cedo, "VodActivity")
        assertFalse(q.marcouRuim)
        assertEquals(e, q.estado)
        // Atualizou com a hora errada: não anota nada; com a hora certa, vê a versão nova e vigia.
        val antes = Estado(versaoVista = 100)
        assertEquals(antes, VigiaRegras.aoIniciar(antes, 101, cedo))
        assertEquals(101L, VigiaRegras.aoIniciar(antes, 101, t0).vigiando)
    }

    @Test fun primeiraVezNaoVigia() {
        val e = VigiaRegras.aoIniciar(Estado(), 100, t0)
        assertEquals(100L, e.versaoVista)
        assertEquals(0L, e.vigiando)
    }

    @Test fun versaoNovaComecaVigia() {
        val e = recemAtualizada()
        assertEquals(101L, e.vigiando)
        assertEquals(t0, e.vigiaDesde)
        // Abrir de novo a mesma versão não reinicia a vigia.
        assertEquals(t0, VigiaRegras.aoIniciar(e, 101, t0 + 5 * min).vigiaDesde)
    }

    @Test fun duasQuedasEm10MinMarcamRuim() {
        val q1 = VigiaRegras.aoCair(recemAtualizada(), 101, t0 + min, null)
        assertFalse(q1.marcouRuim)
        val q2 = VigiaRegras.aoCair(q1.estado, 101, t0 + 9 * min, "PlayerActivity")
        assertTrue(q2.marcouRuim)
        val e = q2.estado
        assertEquals(listOf(101L), e.ruins)
        assertTrue(e.pausada)
        assertEquals(101L, e.pausaPor)
        assertEquals("PlayerActivity", e.telaSuspeita)
        assertTrue(e.avisoPendente && e.registroPendente)
        assertEquals(0L, e.vigiando)
    }

    @Test fun quedasEspalhadasNaoMarcam() {
        val q1 = VigiaRegras.aoCair(recemAtualizada(), 101, t0 + min, null)
        val q2 = VigiaRegras.aoCair(q1.estado, 101, t0 + 30 * min, null)   // 29 min depois: a 1ª já saiu da janela
        assertFalse(q2.marcouRuim)
        assertEquals(1, q2.estado.quedas.size)
    }

    @Test fun quedaDepoisDaVigiaNaoConta() {
        var e = recemAtualizada()
        val depois = t0 + VigiaRegras.DURACAO_VIGIA_MS + min
        e = VigiaRegras.aoCair(e, 101, depois, null).estado
        assertFalse(VigiaRegras.aoCair(e, 101, depois + min, null).marcouRuim)
    }

    @Test fun quedaAntesDeAnotarAAberturaConta() {
        // A versão nova cai antes do aoIniciar (queda no começo do processo): o aoCair faz o aoIniciar antes.
        val q1 = VigiaRegras.aoCair(Estado(versaoVista = 100), 101, t0, null)
        val q2 = VigiaRegras.aoCair(q1.estado, 101, t0 + min, null)
        assertTrue(q2.marcouRuim)
    }

    @Test fun nuncaReinstalaRuimEPausaSoSaiComVersaoMaior() {
        val ruim = VigiaRegras.aoCair(VigiaRegras.aoCair(recemAtualizada(), 101, t0, null).estado, 101, t0 + min, null).estado
        assertFalse(VigiaRegras.podeInstalar(ruim, 101))
        assertFalse(VigiaRegras.podeInstalar(ruim, 100))   // pausada: nada até sair uma maior
        assertTrue(VigiaRegras.podeInstalar(ruim, 102))
        // O Release 101 de novo não tira a pausa; o 102 tira.
        assertTrue(VigiaRegras.aoVerRelease(ruim, 101).pausada)
        val liberada = VigiaRegras.aoVerRelease(ruim, 102)
        assertFalse(liberada.pausada)
        // Mesmo sem pausa, a 101 continua proibida (ex.: o PC voltou a TV para a 100).
        val naCem = VigiaRegras.aoIniciar(liberada, 100, t0 + 60 * min)
        assertFalse(VigiaRegras.podeInstalar(naCem, 101))
        assertTrue(VigiaRegras.podeInstalar(naCem, 102))
    }

    @Test fun modoSeguroSoNaVersaoRuim() {
        val ruim = VigiaRegras.aoCair(VigiaRegras.aoCair(recemAtualizada(), 101, t0, null).estado, 101, t0 + min, "VodActivity").estado
        val reaberta = VigiaRegras.aoIniciar(ruim, 101, t0 + 2 * min)
        assertTrue(VigiaRegras.telaBloqueada(reaberta, 101, "VodActivity"))
        assertFalse(VigiaRegras.telaBloqueada(reaberta, 101, "PlayerActivity"))
        // Versão 102 instalada: a tela volta e a 102 é vigiada.
        val nova = VigiaRegras.aoIniciar(reaberta, 102, t0 + 60 * min)
        assertFalse(VigiaRegras.telaBloqueada(nova, 102, "VodActivity"))
        assertEquals(102L, nova.vigiando)
        assertEquals("", nova.telaSuspeita)
        assertFalse(nova.pausada)
    }

    @Test fun liberarTiraPausaMasNaoARuim() {
        val ruim = VigiaRegras.aoCair(VigiaRegras.aoCair(recemAtualizada(), 101, t0, null).estado, 101, t0 + min, "VodActivity").estado
        val l = VigiaRegras.liberar(ruim)
        assertFalse(l.pausada)
        assertFalse(VigiaRegras.telaBloqueada(l, 101, "VodActivity"))
        assertFalse(VigiaRegras.podeInstalar(l, 101))
    }

    @Test fun telaIdentificadaNumaQuedaPosterior() {
        val ruim = VigiaRegras.aoCair(VigiaRegras.aoCair(recemAtualizada(), 101, t0, null).estado, 101, t0 + min, null).estado
        assertEquals("", ruim.telaSuspeita)
        val e = VigiaRegras.aoCair(ruim, 101, t0 + 2 * min, "VodActivity").estado
        assertTrue(VigiaRegras.telaBloqueada(e, 101, "VodActivity"))
    }

    @Test fun telaDoErro() {
        val p = "br.paulo.tvlauncher."
        assertEquals("PlayerActivity", VigiaRegras.telaDoErro(listOf("java.lang.String", "${p}PlayerGrade", "${p}PlayerActivity\$carregar\$1", "${p}VodActivity")))
        assertNull(VigiaRegras.telaDoErro(listOf("${p}Grade", "${p}MainActivity", "${p}VodActivity")))   // a HOME nunca é bloqueada
        assertNull(VigiaRegras.telaDoErro(listOf("android.app.Activity", "${p}Grade")))
        assertNull(VigiaRegras.telaDoErro(listOf("${p}sub.OutraActivity")))
    }

    @Test fun nuncaBloqueiaAsTelasDeSocorro() {
        val e = Estado(telaSuspeita = "ConfigLauncherActivity", seguroVersao = 101)
        assertFalse(VigiaRegras.telaBloqueada(e, 101, "ConfigLauncherActivity"))
    }

    @Test fun textoIdaEVolta() {
        val e = Estado(1, 2, 3, listOf(4, 5), listOf(6, 7), 8, "VodActivity", 9, true, false)
        assertEquals(e, VigiaRegras.deTexto(VigiaRegras.paraTexto(e)))
        assertEquals(Estado(), VigiaRegras.deTexto("lixo\n=\nversao_vista=abc"))
        assertEquals("", VigiaRegras.deTexto("tela=../x y").telaSuspeita)
    }

    @Test fun conferenciaDoApk() {
        fun c(legivel: Boolean = true, pacote: String? = "br.paulo.tvlauncher", apk: Long = 102, tag: Long = 102,
              inst: Long = 101, chave: Boolean = true, pode: Boolean = true) =
            ConferenciaApk.conferir(legivel, pacote, "br.paulo.tvlauncher", apk, tag, inst, chave, pode)
        assertEquals(ConferenciaApk.Resultado.OK, c())
        assertEquals(ConferenciaApk.Resultado.ILEGIVEL, c(legivel = false))
        assertEquals(ConferenciaApk.Resultado.PACOTE, c(pacote = "com.outro"))
        assertEquals(ConferenciaApk.Resultado.VERSAO_TAG, c(apk = 103))
        assertEquals(ConferenciaApk.Resultado.NAO_E_MAIOR, c(apk = 101, tag = 101))
        assertEquals(ConferenciaApk.Resultado.NAO_E_MAIOR, c(apk = 100, tag = 100))
        assertEquals(ConferenciaApk.Resultado.CHAVE, c(chave = false))
        assertEquals(ConferenciaApk.Resultado.RUIM, c(pode = false))
    }
}

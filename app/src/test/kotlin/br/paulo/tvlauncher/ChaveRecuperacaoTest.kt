package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.ChaveRecuperacao
import br.paulo.tvlauncher.backup.CofreBackup
import br.paulo.tvlauncher.home.abrir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Elevação 19.6: a chave de recuperação abre o backup SEM a senha, e só ela (a TV guarda só a pública). */
class ChaveRecuperacaoTest {
    private val claro = """{"versao":3,"pacote":"br.paulo.tvlauncher","preferencias":{"launcher":{"tema":{"t":"s","v":"escuro"}}}}"""

    @Test fun restauracaoSoComAChave() {
        val nova = ChaveRecuperacao.gerar()
        val env = CofreBackup.trancar(claro, "senha-do-dono", nova.publica)
        // O que a TV tem (o envelope e a pública) não contém o código.
        val semTraco = nova.codigo.replace("-", "")
        assertFalse(env.contains(semTraco.substring(6, 30)))
        // Só com a chave, sem a senha: abre igual.
        assertEquals(claro, CofreBackup.abrirComChave(env, nova.codigo))
        // O campo de senha aceita o código (o painel e a tela da TV usam o mesmo campo).
        assertEquals(claro, CofreBackup.abrir(env, nova.codigo))
        // A senha continua abrindo.
        assertEquals(claro, CofreBackup.abrir(env, "senha-do-dono"))
    }

    @Test fun codigoDigitadoComFolgaAbre() {
        val nova = ChaveRecuperacao.gerar()
        val env = CofreBackup.trancar(claro, "senha-do-dono", nova.publica)
        val baguncado = "  " + nova.codigo.lowercase().replace("-", " ").replace('0', 'o') + "\n"
        assertEquals(claro, CofreBackup.abrirComChave(env, baguncado))
    }

    @Test fun outraChaveOuLetraTrocadaNaoAbre() {
        val nova = ChaveRecuperacao.gerar()
        val env = CofreBackup.trancar(claro, "senha-do-dono", nova.publica)
        try { CofreBackup.abrirComChave(env, ChaveRecuperacao.gerar().codigo); fail("outra chave abriu") } catch (_: CofreBackup.SenhaErrada) {}
        // Uma letra trocada: a verificação do código recusa antes de tentar.
        val i = 20
        val c = nova.codigo
        val trocado = c.substring(0, i) + (if (c[i] == 'A') 'B' else 'A') + c.substring(i + 1)
        assertNull(ChaveRecuperacao.brutoDoCodigo(trocado))
        try { CofreBackup.abrirComChave(env, trocado); fail("código errado abriu") } catch (_: CofreBackup.SenhaErrada) {}
        try { CofreBackup.abrir(env, "senha-errada"); fail("senha errada abriu") } catch (_: CofreBackup.SenhaErrada) {}
    }

    @Test fun formatoDoCodigo() {
        repeat(20) {
            val c = ChaveRecuperacao.gerar().codigo
            assertTrue(c, Regex("^SOFALY(-[0-9A-HJKMNP-TV-Z]{1,5}){11}$").matches(c))
            assertTrue(ChaveRecuperacao.codigoValido(c))
            assertTrue(ChaveRecuperacao.pareceCodigo(c))
        }
        assertFalse(ChaveRecuperacao.pareceCodigo("minha senha comprida"))
    }

    @Test fun backupSemChaveContinuaVersao1() {
        val env = CofreBackup.trancar(claro, "senha-do-dono")
        assertFalse(CofreBackup.temRecuperacao(env))
        assertEquals(claro, CofreBackup.abrir(env, "senha-do-dono"))
        try { CofreBackup.abrirComChave(env, ChaveRecuperacao.gerar().codigo); fail() } catch (_: IllegalArgumentException) {}
    }
}

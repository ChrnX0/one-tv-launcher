package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Partes puras do login pela conta Google (LoginGoogle). */
class LoginGoogleTest {
    @Test fun leContasDeTodosOsPerfis() {
        val dump = """
            User UserInfo{0:dono:c13}:
              Accounts: 2
                Account {name=dono@Gmail.com, type=com.google}
                Account {name=x@y.com, type=com.outro}
            User UserInfo{11:Mae:410}:
              Accounts: 1
                Account {name=mae@gmail.com, type=com.google}
        """.trimIndent()
        assertEquals(setOf("dono@gmail.com", "mae@gmail.com"), LoginGoogle.contasDoDumpsys(dump))
    }

    @Test fun validaCliente() {
        assertTrue(LoginGoogle.idValido("1234567890-abcdefghij.apps.googleusercontent.com"))
        assertFalse(LoginGoogle.idValido("1234567890-abc.mal.com"))
        assertFalse(LoginGoogle.idValido("a b.apps.googleusercontent.com"))
        assertTrue(LoginGoogle.segredoValido("GOCSPX-abcdEFGH1234"))
        assertFalse(LoginGoogle.segredoValido("x\"<script>"))
    }

    private fun sessao(id: String, expira: Long, estado: LoginGoogle.Estado) =
        LoginGoogle.Sessao(id, emptyMap(), "ABCD", "https://www.google.com/device", expira).also { it.estado = estado }

    /** mcp-4: 5 logins abertos (de qualquer um pela internet) não podem impedir um login novo. */
    @Test fun lotadoAbreVagaEmVezDeRecusar() {
        val agora = 1_000_000L
        val m = HashMap<String, LoginGoogle.Sessao>()
        for (i in 1..5) m["e$i"] = sessao("e$i", agora + 900_000 + i, LoginGoogle.Estado.ESPERANDO)
        val maisVelha = m.getValue("e1")
        LoginGoogle.abrirVaga(m, agora)
        assertEquals(4, m.size)
        assertFalse("e1" in m)
        assertEquals(LoginGoogle.Estado.ERRO, maisVelha.estado)   // a thread dela para de perguntar ao Google
    }

    @Test fun encerradasSaemAntesDasQueEsperam() {
        val agora = 1_000_000L
        val m = HashMap<String, LoginGoogle.Sessao>()
        m["e"] = sessao("e", agora + 100, LoginGoogle.Estado.ESPERANDO)
        m["ok"] = sessao("ok", agora + 50, LoginGoogle.Estado.OK)
        for (i in 1..3) m["r$i"] = sessao("r$i", agora + 900_000, LoginGoogle.Estado.RECUSADO)
        m["velha"] = sessao("velha", agora - 1, LoginGoogle.Estado.ESPERANDO)   // vencida
        LoginGoogle.abrirVaga(m, agora)
        assertEquals(4, m.size)
        assertTrue("e" in m && "ok" in m)
        assertFalse("velha" in m)
    }

    @Test fun ritmoDeInicios() {
        // Revisão da auditoria: no máximo 6 inícios por minuto, contados ANTES de chamar o Google.
        val fila = ArrayDeque<Long>()
        repeat(6) { assertTrue(LoginGoogle.cabeNoRitmo(fila, 1_000L + it)) }
        assertFalse(LoginGoogle.cabeNoRitmo(fila, 2_000L))
        assertTrue(LoginGoogle.cabeNoRitmo(fila, 61_000L))   // passou 1 minuto do primeiro
    }

    @Test fun soOLoginDoBackupPedeODrive() {
        assertEquals("email profile", LoginGoogle.escopos(LoginGoogle.Finalidade.CONECTOR))
        assertEquals("email profile", LoginGoogle.escopos(LoginGoogle.Finalidade.FOTO))
        assertEquals(DriveRegras.ESCOPOS_LOGIN, LoginGoogle.escopos(LoginGoogle.Finalidade.DRIVE))
    }
}

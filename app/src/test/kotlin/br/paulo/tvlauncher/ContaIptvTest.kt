package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Área "Conta" das Configurações: leitura do user_info e as linhas mostradas. Dados inventados. */
class ContaIptvTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val xtream = FonteTv("a1", "Playon", FonteTv.TIPO_XTREAM, "http://servidor.invalid:8080", "ana", "segredo")
    private val m3u = FonteTv("b2", "Lista", FonteTv.TIPO_M3U, "http://lista.invalid/canais.m3u")
    private val m3uPainel = FonteTv("c3", "Painel", FonteTv.TIPO_M3U, "http://p.invalid:80/get.php?username=bia&password=s%26nha&type=m3u_plus")

    @Test fun leOsCamposNovosDoUserInfo() {
        val s = Xtream.lerSituacao("""{"user_info":{"username":"ana","auth":1,"status":"Active","exp_date":"1798761600",
            "created_at":"1735689600","is_trial":"0","max_connections":"3","active_cons":"1","message":"Bem-vindo"}}""")!!
        assertTrue(s.ativa); assertEquals("ana", s.usuario); assertEquals(1735689600L, s.criadaEm)
        assertEquals(3, s.telasMax); assertEquals(1, s.telasEmUso); assertFalse(s.teste); assertEquals("Bem-vindo", s.mensagem)
        val t = Xtream.lerSituacao("""{"user_info":{"auth":1,"status":"Active","is_trial":"1","created_at":null,"message":null}}""")!!
        assertTrue(t.teste); assertNull(t.criadaEm); assertEquals("", t.mensagem); assertEquals("", t.usuario)
    }

    @Test fun contaDaListaGetPhp() {
        val c = Xtream.contaDaLista(m3uPainel.endereco)!!
        assertEquals("http://p.invalid:80", c.servidor); assertEquals("bia", c.usuario); assertEquals("s&nha", c.senha)
        assertNull(Xtream.contaDaLista("http://lista.invalid/canais.m3u"))
        assertNull(Xtream.contaDaLista("http://p.invalid/get.php?username=bia"))   // sem senha
        assertEquals("M3U (painel Xtream)", ContaIptv.tipoLogin(m3uPainel))
        assertEquals("M3U", ContaIptv.tipoLogin(m3u))
        assertEquals("Xtream Codes", ContaIptv.tipoLogin(xtream))
    }

    @Test fun estadoEmPortugues() {
        fun s(ativa: Boolean, st: String, teste: Boolean = false) = Xtream.Situacao(ativa, st, null, null, null, teste = teste)
        assertEquals("Ativa", ContaIptv.estado(s(true, "Active")))
        assertEquals("Ativa (teste)", ContaIptv.estado(s(true, "Active", teste = true)))
        assertEquals("Desativada (vencida)", ContaIptv.estado(s(false, "Expired")))
        assertEquals("Desativada (bloqueada pelo vendedor)", ContaIptv.estado(s(false, "Banned")))
        assertEquals("Desativada (usuário ou senha recusados)", ContaIptv.estado(s(false, "")))
    }

    @Test fun prazo() {
        val dia = 86_400_000L
        val agora = 1_800_000_000_000L
        assertEquals("faltam 10 dias", ContaIptv.prazo((agora + 10 * dia + 1000) / 1000, agora))
        assertEquals("falta 1 dia", ContaIptv.prazo((agora + dia + 1000) / 1000, agora))
        assertEquals("vence hoje", ContaIptv.prazo((agora + 3_600_000) / 1000, agora))
        assertEquals("venceu ontem", ContaIptv.prazo((agora - 3_600_000) / 1000, agora))
        assertEquals("venceu há 5 dias", ContaIptv.prazo((agora - 4 * dia - 3_600_000) / 1000, agora))
    }

    @Test fun linhasDaContaXtream() {
        val s = Xtream.Situacao(true, "Active", 1798761600L, 2, 0, usuario = "ana", criadaEm = 1735689600L)
        val l = ContaIptv.linhas(xtream, s, consultou = true, agoraMs = 1_790_000_000_000L, fuso = utc)
        val mapa = l.associate { it.rotulo to it.valor }
        assertEquals("Playon", mapa["Lista de reprodução"])
        assertEquals("ana", mapa["Usuário"])
        assertEquals("Ativa", mapa["Situação"])
        assertEquals("01/01/2025", mapa["Criada em"])
        assertTrue(mapa["Vence em"]!!.startsWith("01/01/2027 · faltam"))
        assertEquals("2 (0 em uso agora)", mapa["Conexões máximas"])
        assertEquals("Xtream Codes", mapa["Tipo de login"])
        assertTrue(l.none { it.valor.contains("segredo") })   // a senha nunca aparece
        assertFalse(l.first { it.rotulo == "Vence em" }.alerta)
        // Faltando 3 dias: alerta.
        val perto = ContaIptv.linhas(xtream, s, true, agoraMs = 1798761600L * 1000 - 3 * 86_400_000L, fuso = utc)
        assertTrue(perto.first { it.rotulo == "Vence em" }.alerta)
    }

    @Test fun linhasSemDados() {
        val comum = ContaIptv.linhas(m3u, null, consultou = false, agoraMs = 0)
        assertTrue(comum.first { it.rotulo == "Situação" }.valor.startsWith("lista M3U comum"))
        assertTrue(comum.none { it.rotulo == "Usuário" })
        val semRede = ContaIptv.linhas(xtream, null, consultou = true, agoraMs = 0)
        assertTrue(semRede.first { it.rotulo == "Situação" }.valor.startsWith("o servidor não respondeu"))
        assertEquals("ana", semRede.first { it.rotulo == "Usuário" }.valor)   // o usuário vem da conta cadastrada
    }
}

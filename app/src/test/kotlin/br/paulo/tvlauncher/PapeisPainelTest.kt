package br.paulo.tvlauncher

import br.paulo.tvlauncher.input.LeitorNomes
import br.paulo.tvlauncher.panel.PapeisPainel
import br.paulo.tvlauncher.panel.PapeisPainel.Papel
import br.paulo.tvlauncher.panel.Sessoes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elevação 18.1: a trava dos papéis, conferida ROTA POR ROTA. As rotas saem do próprio código do painel (PainelWeb e
 * PainelExtras): rota nova que aparecer lá entra aqui sozinha e, sem estar na lista do papel, tem de ser negada.
 */
class PapeisPainelTest {
    private val fonte = listOf("PainelWeb.kt", "PainelExtras.kt", "PainelCuidar.kt", "PainelAddons.kt").map { CodeSource.file(it) }

    /** Todas as rotas "/api/..." do painel, com GET e POST (o teste não sabe o método de cada uma: confere os dois). */
    private fun rotas(): Set<String> {
        val r = HashSet<String>()
        val web = fonte[0].readText()
        Regex("\"(/api/[a-z0-9/_-]+)\"").findAll(web).forEach { r += it.groupValues[1].trimEnd('/') }
        Regex("rota == \"([a-z0-9/_-]+)\"").findAll(fonte[1].readText()).forEach { r += "/api/extras/" + it.groupValues[1] }
        for ((i, base) in listOf(2 to "/api/cuidar", 3 to "/api/addons")) if (fonte[i].exists())
            Regex("\"(/[a-z0-9/_-]*)\"").findAll(fonte[i].readText()).forEach { r += base + it.groupValues[1].trimEnd('/') }
        r += listOf("/api/apps/com.exemplo/abrir", "/api/apps/com.exemplo/icone", "/api/apps/com.exemplo/ficha",
            "/api/apps/com.exemplo/desinstalar", "/api/apps/com.exemplo/limpar", "/api/apps/com.exemplo/ocultar",
            "/api/apps/com.exemplo/parar", "/api/apps/com.exemplo/permissao", "/api/apps/com.exemplo/especial",
            "/api/apps/com.exemplo/permissoes")
        return r
    }

    private val convidadoPode = setOf("GET /api/apps", "GET /api/uso", "POST /api/tela/tecla", "POST /api/tela/texto",
        "POST /api/extras/dormir", "POST /api/extras/acordar", "GET /api/extras/tocando", "POST /api/extras/tocando",
        "POST /api/apps/com.exemplo/abrir", "GET /api/apps/com.exemplo/icone")
    private val familiaPode = convidadoPode + setOf("GET /api/clima", "GET /api/tela/foto", "POST /api/recado",
        "GET /api/fotos", "GET /api/fotos/ver", "POST /api/fotos/enviar", "POST /api/extras/recado-foto",
        "GET /api/apps/com.exemplo/ficha")

    @Test fun cadaRotaParaCadaPapel() {
        val rotas = rotas()
        assertTrue("achou poucas rotas (${rotas.size}): o leitor do código quebrou?", rotas.size > 120)
        val erradas = ArrayList<String>()
        for (rota in rotas) for (metodo in listOf("GET", "POST")) {
            val r = "$metodo $rota"
            if (!PapeisPainel.permite(Papel.DONO, metodo, rota)) erradas += "dono barrado: $r"
            if (PapeisPainel.permite(Papel.FAMILIA, metodo, rota) != (r in familiaPode)) erradas += "família: $r"
            if (PapeisPainel.permite(Papel.CONVIDADO, metodo, rota) != (r in convidadoPode)) erradas += "convidado: $r"
        }
        assertEquals(emptyList<String>(), erradas)
    }

    @Test fun sensiveisNuncaParaQuemNaoEDono() {
        for (r in listOf("/api/backup/completo", "/api/backup/recuperacao", "/api/papeis/convite", "/api/papeis/revogar",
            "/api/config", "/api/apps/instalar", "/api/arquivos/apagar", "/api/mcp/config", "/api/shizuku", "/api/dono",
            "/api/extras/desfazer", "/api/extras/diario-tv", "/api/registro", "/api/tv/reiniciar", "/api/apps/com.x/desinstalar"))
            for (p in listOf(Papel.FAMILIA, Papel.CONVIDADO)) for (m in listOf("GET", "POST"))
                assertFalse("$p $m $r", PapeisPainel.permite(p, m, r))
    }

    @Test fun sessaoDoConvidadoCaiComOConviteENaoEleva() {
        var agora = 1_000_000L
        val s = Sessoes({ agora })
        val t = s.criar(elevada = true, papel = Papel.CONVIDADO, venceEm = agora + 60_000)
        assertEquals(Papel.CONVIDADO, s.papel(t))
        assertFalse("convidado nunca elevado", s.elevada(t))
        agora += 61_000
        assertNull("venceu com o convite", s.papel(t))
        // Família que digita o PIN do dono vira dono (sabe o PIN).
        val f = s.criar(elevada = false, papel = Papel.FAMILIA)
        assertFalse(s.elevada(f))
        s.elevar(f)
        assertEquals(Papel.DONO, s.papel(f)); assertTrue(s.elevada(f))
        // Revogar derruba quem entrou por convite, não o dono.
        val g = s.criar(elevada = false, papel = Papel.FAMILIA)
        s.encerrarConvidados()
        assertNull(s.papel(g)); assertNotNull(s.papel(f))
    }

    @Test fun convites() {
        val t0 = 5_000_000L
        val (codFam, l1) = PapeisPainel.criar(emptyList(), Papel.FAMILIA, t0)
        val (codConv, l2) = PapeisPainel.criar(l1, Papel.CONVIDADO, t0)
        // A TV guarda o resumo, nunca o código.
        val json = PapeisPainel.paraJson(l2)
        assertFalse(json.contains(codFam)); assertFalse(json.contains(codConv))
        val lidos = PapeisPainel.deJson(json)
        assertEquals(Papel.FAMILIA, PapeisPainel.usar(lidos, codFam, t0 + 1)!!.papel)
        assertEquals(Papel.CONVIDADO, PapeisPainel.usar(lidos, codConv, t0 + 1)!!.papel)
        // Convidado vence em 24 h; família não.
        val depois = t0 + PapeisPainel.CONVIDADO_MS + 1
        assertNull(PapeisPainel.usar(lidos, codConv, depois))
        assertNotNull(PapeisPainel.usar(lidos, codFam, depois))
        // Revogado não entra; código inventado também não.
        val idFam = PapeisPainel.usar(lidos, codFam, t0)!!.id
        assertNull(PapeisPainel.usar(PapeisPainel.revogar(lidos, idFam), codFam, t0))
        assertNull(PapeisPainel.usar(lidos, "0".repeat(48), t0))
        assertNull(PapeisPainel.usar(lidos, null, t0))
        // Ninguém cria convite de dono, e "dono" escrito à mão no arquivo é ignorado.
        assertTrue(runCatching { PapeisPainel.criar(emptyList(), Papel.DONO, t0) }.isFailure)
        assertTrue(PapeisPainel.deJson(json.replace("\"familia\"", "\"dono\"")).none { it.papel == Papel.DONO })
    }

    // Rodada 15: quem tem o link do convite entrava 8 vezes e derrubava a sessão do dono.
    @Test fun convidadoNaoDerrubaODono() {
        val agora = 1_000L
        val s = Sessoes({ agora })
        val dono = s.criar()
        repeat(20) { s.criar(elevada = false, papel = Papel.CONVIDADO) }
        assertTrue("o dono continua dentro", s.existe(dono))
        assertEquals(Papel.DONO, s.papel(dono))
    }

    @Test fun anoMaximoAcompanhaOAno() {
        val ano = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        assertEquals(ano + 1, LeitorNomes.anoMaximoAgora())
        assertEquals(ano + 1, LeitorNomes.ler("Filme.${ano + 1}.1080p.mkv").ano)
    }
}

package br.paulo.tvlauncher.panel

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevação 18.1 (17/11/2026): papéis no painel. Quem entra com o PIN é o DONO (tudo, como sempre). Para os outros, o
 * dono manda um convite (link ou QR):
 *  - FAMILIA: o dia a dia (controle remoto, abrir apps, recados e fotos, clima, tempo de tela de hoje). Convite vale
 *    até o dono revogar; cada entrada dura como uma sessão comum (12 h).
 *  - CONVIDADO de um dia: só o controle remoto e abrir apps. O convite vence em 24 h e a sessão cai junto.
 * A trava é conferida no SERVIDOR ([permite]), em toda requisição, antes da rota: esconder botão na página é só
 * conforto. Regra: o que não está na lista do papel é negado (rota nova nasce só do dono).
 * Ninguém além do dono confirma PIN ("elevada"): desinstalar, restaurar, apagar etc. ficam fora por construção.
 * Lógica pura (PapeisPainelTest, que confere cada rota do PainelWeb para cada papel).
 */
object PapeisPainel {
    enum class Papel(val chave: String) {
        DONO("dono"), FAMILIA("familia"), CONVIDADO("convidado");
        companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } }
    }

    const val CONVIDADO_MS = 24 * 3600_000L
    const val MAX_CONVITES = 12

    /** O que o convidado pode: o controle e abrir apps. */
    private val CONVIDADO_ROTAS = setOf(
        "GET /api/apps", "GET /api/uso",
        "POST /api/tela/tecla", "POST /api/tela/texto",
        "POST /api/extras/dormir", "POST /api/extras/acordar", "GET /api/extras/tocando", "POST /api/extras/tocando",
    )
    /** /api/apps/<pacote>/<ação> liberadas ao convidado. */
    private val CONVIDADO_APP = setOf("GET icone", "POST abrir")

    /** A família: o do convidado e mais o dia a dia da casa. */
    private val FAMILIA_ROTAS = CONVIDADO_ROTAS + setOf(
        "GET /api/clima", "GET /api/tela/foto",
        "POST /api/recado", "GET /api/fotos", "GET /api/fotos/ver", "POST /api/fotos/enviar",
        "POST /api/extras/recado-foto",
    )
    private val FAMILIA_APP = CONVIDADO_APP + setOf("GET ficha")

    /** O papel pode fazer [metodo] [caminho]? DONO pode tudo; os outros, só a lista (negado por padrão). */
    fun permite(papel: Papel, metodo: String, caminho: String): Boolean {
        if (papel == Papel.DONO) return true
        val rotas = if (papel == Papel.FAMILIA) FAMILIA_ROTAS else CONVIDADO_ROTAS
        if ("$metodo $caminho" in rotas) return true
        if (caminho.startsWith("/api/apps/")) {
            val partes = caminho.removePrefix("/api/apps/").split('/')
            if (partes.size == 2) return "$metodo ${partes[1]}" in (if (papel == Papel.FAMILIA) FAMILIA_APP else CONVIDADO_APP)
        }
        return false
    }

    /** Para a página esconder o que o papel não usa (a trava de verdade é [permite]). */
    fun paginas(papel: Papel): List<String> = when (papel) {
        Papel.DONO -> emptyList()   // vazio = todas
        Papel.FAMILIA -> listOf("controle", "natv")   // o Início lê saúde, rede etc., que a família não vê
        Papel.CONVIDADO -> listOf("controle")
    }

    // ---------------------------------------------------------------- convites

    data class Convite(val id: String, val resumo: String, val papel: Papel, val criado: Long, val vence: Long?)

    private val aleatorio = SecureRandom()

    private fun resumo(codigo: String) = MessageDigest.getInstance("SHA-256").digest(codigo.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Convite novo: devolve o código (vai no link, só para quem recebe) e a lista nova. A TV guarda só o RESUMO do
     * código (SHA-256), como faz com o token da sessão: quem ler as prefs não consegue entrar.
     */
    fun criar(lista: List<Convite>, papel: Papel, agora: Long): Pair<String, List<Convite>> {
        require(papel != Papel.DONO) { "dono entra com o PIN" }
        val b = ByteArray(24).also { aleatorio.nextBytes(it) }
        val codigo = b.joinToString("") { "%02x".format(it) }
        val r = resumo(codigo)
        val c = Convite(r.take(8), r, papel, agora, if (papel == Papel.CONVIDADO) agora + CONVIDADO_MS else null)
        return codigo to (listOf(c) + validos(lista, agora)).take(MAX_CONVITES)
    }

    fun validos(lista: List<Convite>, agora: Long) = lista.filter { it.vence == null || agora < it.vence }

    /** O convite do [codigo], se vale agora. Comparação do resumo em tempo constante. */
    fun usar(lista: List<Convite>, codigo: String?, agora: Long): Convite? {
        if (codigo == null || !Regex("^[0-9a-f]{48}$").matches(codigo)) return null
        val r = resumo(codigo).toByteArray()
        return validos(lista, agora).firstOrNull { MessageDigest.isEqual(it.resumo.toByteArray(), r) }
    }

    fun revogar(lista: List<Convite>, id: String?): List<Convite> = if (id == null) emptyList() else lista.filter { it.id != id }

    fun paraJson(lista: List<Convite>): String = JSONArray().apply {
        lista.forEach { put(JSONObject().put("id", it.id).put("r", it.resumo).put("p", it.papel.chave).put("c", it.criado).put("v", it.vence ?: 0L)) }
    }.toString()

    fun deJson(t: String?): List<Convite> {
        val a = runCatching { JSONArray(t ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = Papel.de(o.optString("p"))?.takeIf { it != Papel.DONO } ?: return@mapNotNull null
            val r = o.optString("r").takeIf { it.length == 64 } ?: return@mapNotNull null
            Convite(o.optString("id"), r, p, o.optLong("c"), o.optLong("v").takeIf { it > 0 })
        }
    }

    /** Para o painel do dono: sem o resumo. */
    fun publico(c: Convite): JSONObject = JSONObject().put("id", c.id).put("papel", c.papel.chave).put("criado", c.criado)
        .put("vence", c.vence ?: JSONObject.NULL)
}

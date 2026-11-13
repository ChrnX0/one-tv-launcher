package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Confirmações antes de ações de poder (09/11/2026, pedido do dono; desenho em docs/consentimento.md).
 *
 * O launcher é Device Owner e tem shell próprio. Quem instala sem entender do assunto fica protegido: cada ação de poder
 * tem um modo (perguntar na TV, permitir sempre, bloqueado). Quem usa de longe (painel, Claude) liga "Liberar tudo".
 *
 * Só lógica pura (sem Android), testada em ConfirmacoesTest. Quem grava e pergunta na TV é o ConfirmacoesTv.
 */
object Confirmacoes {
    enum class Modo(val chave: String, val rotulo: String) {
        PERGUNTAR("perguntar", "Perguntar na TV"),
        PERMITIR("permitir", "Permitir sempre"),
        BLOQUEAR("bloquear", "Bloqueado");
        companion object { fun de(chave: String?): Modo? = entries.firstOrNull { it.chave == chave } }
    }

    /**
     * As ações de poder. `padrao` = o modo numa instalação nova. `modos` = os que a linha aceita (a atualização não
     * pergunta: ela só instala com a tela apagada, quando não há ninguém para responder).
     */
    enum class Acao(val chave: String, val titulo: String, val explicacao: String, val padrao: Modo,
                    val modos: List<Modo> = Modo.entries) {
        MCP_CLIENTE("mcp_cliente", "Novo login de IA pela internet",
            "Claude, ChatGPT e outros se conectando à TV pelo MCP (além do PIN ou da conta Google).", Modo.PERGUNTAR),
        MCP_ACOES("mcp_acoes", "Ações pedidas pela IA",
            "Abrir app, volume, despertador e outras do catálogo fechado.", Modo.PERGUNTAR),
        MCP_SHELL("mcp_shell", "Comandos de shell pela IA",
            "Comando livre no shell da TV, como o adb. O mais perigoso.", Modo.BLOQUEAR),
        INSTALAR("instalar", "Instalar app",
            "APK enviado pelo painel (além do PIN).", Modo.PERGUNTAR),
        REMOVER("remover", "Desinstalar, parar ou limpar dados de app",
            "Pelo painel (além do PIN).", Modo.PERGUNTAR),
        PERMISSOES("permissoes", "Dar permissão ou acesso especial a app",
            "Pelo painel. Tirar permissão nunca pergunta.", Modo.PERGUNTAR),
        ABRIR_FORA("abrir_fora", "Abrir a TV para fora de casa",
            "Ligar o acesso pela internet (MCP) ou a rede Tailscale.", Modo.PERGUNTAR),
        ATUALIZACAO("atualizacao", "Atualização automática do launcher",
            "Instala a versão nova sozinha com a tela apagada. Bloqueado = só avisa.", Modo.PERMITIR,
            listOf(Modo.PERMITIR, Modo.BLOQUEAR));
        companion object { fun de(chave: String?): Acao? = entries.firstOrNull { it.chave == chave } }
    }

    data class Regras(val liberarTudo: Boolean = false, val modos: Map<Acao, Modo> = emptyMap())

    /** O modo que vale agora para a ação. "Liberar tudo" permite tudo. */
    fun modo(r: Regras, a: Acao): Modo {
        if (r.liberarTudo) return Modo.PERMITIR
        val m = r.modos[a] ?: a.padrao
        return if (m in a.modos) m else a.padrao
    }

    /**
     * Regras de quem ainda não tem nada gravado. Instalação nova: os padrões, protegidos. TV que já tinha o launcher
     * (a Sala do dono, atualizada): "Liberar tudo" ligado, para nada mudar sem ele mexer (escolha dele, 09/11/2026).
     */
    fun iniciais(instalacaoNova: Boolean) = Regras(liberarTudo = !instalacaoNova)

    /** Mudar pede o PIN quando deixa algo MAIS liberado. Ficar mais seguro nunca pede senha. */
    fun pedePin(antes: Regras, depois: Regras): Boolean {
        if (depois.liberarTudo && !antes.liberarTudo) return true
        if (depois.liberarTudo) return false
        return Acao.entries.any { nivel(modo(depois, it)) > nivel(modo(antes, it)) }
    }
    private fun nivel(m: Modo) = when (m) { Modo.BLOQUEAR -> 0; Modo.PERGUNTAR -> 1; Modo.PERMITIR -> 2 }

    /** O próximo modo da linha (OK na tela das Configurações vai girando). */
    fun proximo(a: Acao, atual: Modo): Modo {
        val i = a.modos.indexOf(atual)
        return a.modos[(i + 1).mod(a.modos.size)]
    }

    fun paraJson(r: Regras): String {
        val m = JSONObject()
        for ((a, modo) in r.modos) m.put(a.chave, modo.chave)
        return JSONObject().put("liberarTudo", r.liberarTudo).put("modos", m).toString()
    }

    /** Null = texto ausente ou ilegível (quem chama decide o inicial). Chave ou modo desconhecido é ignorado. */
    fun deJson(texto: String?): Regras? {
        val o = runCatching { JSONObject(texto ?: return null) }.getOrNull() ?: return null
        val modos = LinkedHashMap<Acao, Modo>()
        o.optJSONObject("modos")?.let { m ->
            for (k in m.keys()) {
                val a = Acao.de(k) ?: continue
                val modo = Modo.de(m.optString(k)) ?: continue
                if (modo in a.modos) modos[a] = modo
            }
        }
        return Regras(o.optBoolean("liberarTudo", false), modos)
    }
}

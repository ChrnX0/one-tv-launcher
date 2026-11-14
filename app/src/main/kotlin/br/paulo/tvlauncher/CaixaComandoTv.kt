package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parte Android da caixa de comando do painel (ideia 10; a regra pura está no CaixaComando). Chamada pela rota
 * POST /api/extras/comando (PainelExtras), que já passou por todas as defesas do painel (rede de casa, Host, Origin,
 * X-Painel e sessão). Roda na thread do pedido HTTP, nunca na da tela.
 *
 * Frase -> CaixaComando.interpretar (com o leitor de canais da voz, CanalBusca) -> JSON do catálogo -> PedidoIa.propor
 * (o mesmo caminho do Claude: ComandosIa.conferir, Confirmações, execução). Responde o que entendeu ("Abrindo YouTube"),
 * ou "não entendi" com 3 exemplos.
 */
object CaixaComandoTv {
    const val ORIGEM = "painel"
    /** Quanto esperar o pedido ser conferido (canais e títulos são lidos do disco) antes de responder ao celular. */
    private const val ESPERA_MS = 5_000L

    fun executar(app: Context, fraseCrua: String?, ip: String): Pair<Int, JSONObject> {
        val frase = ComandosIa.limparFrase(fraseCrua?.take(CaixaComando.MAX_FRASE))
            ?: return 400 to JSONObject().put("erro", app.getString(R.string.cc_vazia))
        val apps = PedidoIa.appsDaHome(app)
        val pedido = CaixaComando.interpretar(frase, apps) { f ->
            when (val p = CanalBusca.interpretarFrase(f)) {
                is CanalBusca.PedidoVoz.OQuePassa -> CaixaComando.Pedido("o_que_passa", alvo = p.canal)
                is CanalBusca.PedidoVoz.Tocar -> CaixaComando.Pedido("tocar_canal", alvo = p.canal)
                null -> null
            }
        }
        if (pedido == null) {
            PainelArmazem.registrar(app, ip, "comando: não entendi", "nada")
            return 200 to JSONObject().put("entendeu", false).put("texto", app.getString(R.string.cc_nao_entendi))
                .put("exemplos", JSONArray(listOf(app.getString(R.string.cc_exemplo_1), app.getString(R.string.cc_exemplo_2),
                    app.getString(R.string.cc_exemplo_3))))
        }
        val p = PedidoIa.propor(app, frase, ORIGEM, pedido.json())
        // O pedido é conferido noutra thread: espera um pouco para dizer o que aconteceu (canal não achado, bloqueado...).
        val modo = ConfirmacoesTv.modo(app, Confirmacoes.Acao.MCP_ACOES)
        val limite = System.currentTimeMillis() + ESPERA_MS
        while (System.currentTimeMillis() < limite && (p.situacao == PedidoIa.Situacao.PENSANDO ||
                (p.situacao == PedidoIa.Situacao.ESPERANDO && modo != Confirmacoes.Modo.PERGUNTAR))) Thread.sleep(100)
        val entendi = entendido(app, pedido, apps)
        val texto = when (p.situacao) {
            PedidoIa.Situacao.FEITO, PedidoIa.Situacao.PENSANDO -> listOf(entendi, p.mensagem).filter { it.isNotBlank() }.joinToString(" · ")
            PedidoIa.Situacao.ESPERANDO -> app.getString(R.string.cc_confirme_na_tv, entendi)
            else -> app.getString(R.string.cc_nao_fiz, entendi,
                (p.plano?.recusadas?.firstOrNull()?.substringAfter(": ") ?: p.mensagem).ifBlank { p.situacao.texto })
        }
        PainelArmazem.registrar(app, ip, "comando: ${pedido.tipo}", p.situacao.name.lowercase())
        return 200 to JSONObject().put("entendeu", true).put("texto", texto).put("situacao", p.situacao.name.lowercase())
    }

    /** "Abrindo YouTube", "Colocando em Globo", "Aviso em 7 minutos"... no idioma da TV. */
    private fun entendido(app: Context, p: CaixaComando.Pedido, apps: Map<String, String>): String = when (p.tipo) {
        "abrir_app" -> app.getString(R.string.cc_abrindo, apps[p.alvo] ?: p.alvo)
        "abrir_tela" -> app.getString(R.string.cc_abrindo, app.getString(when (p.alvo) {
            "previsao" -> R.string.cc_tela_previsao
            "agenda" -> R.string.cc_tela_agenda
            "despertador" -> R.string.cc_tela_despertador
            "configuracoes" -> R.string.cc_tela_configuracoes
            else -> R.string.cc_tela_inicio
        }))
        "tocar_canal" -> app.getString(R.string.cc_canal, p.alvo)
        "o_que_passa" -> app.getString(R.string.cc_o_que_passa, p.alvo)
        "timer" -> app.getString(R.string.cc_timer, p.numero)
        "desligar_em" -> if (p.numero == 0) app.getString(R.string.cc_desligar_cancelar) else app.getString(R.string.cc_desligar, p.numero)
        "volume" -> app.getString(when (p.acao) { "subir" -> R.string.cc_volume_subir; "descer" -> R.string.cc_volume_descer; else -> R.string.cc_mudo })
        "midia" -> app.getString(if (p.acao == "pausar") R.string.cc_pausar else R.string.cc_continuar)
        else -> p.tipo
    }
}

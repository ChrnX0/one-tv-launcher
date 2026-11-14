package br.paulo.tvlauncher

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * As ferramentas que o servidor MCP oferece ao Claude (opção E, 05/11/2026).
 *
 * LEITURA responde na hora. AÇÃO pelo catálogo fechado (ComandosIa) e, desde 09/11/2026, SHELL LIVRE (executar_shell,
 * usuário shell uid 2000 pelo adb próprio da TV), sem lista de bloqueio: decisão do dono ("quero controle total").
 * As duas passam pelas Confirmações (ConfirmacoesTv): na Sala, "Liberar tudo" (executa na hora); numa instalação nova,
 * as ações perguntam na TV e o shell vem bloqueado. Cada comando vai para o registro do painel; a chave "Shell pelo
 * Claude" nas Configurações desliga.
 */
class McpFerramentas(ctx: Context) : McpProtocolo.Ferramentas, McpProtocolo.ComConteudo {
    private val app = ctx.applicationContext

    override fun lista(): JSONArray = JSONArray().apply {   // + ferramentas das ideias grandes (McpExtras.kt)
        for (i in 0 until LISTA.length()) put(LISTA.get(i)); McpExtras.LISTA.forEach { put(it) }; McpPoder.LISTA.forEach { put(it) }; McpPoder2.LISTA.forEach { put(it) }; McpIdeias.LISTA.forEach { put(it) }
    }

    override fun conteudo(nome: String, argumentos: JSONObject): JSONArray? =
        if (McpExtras.conhece(nome)) McpExtras.chamar(app, nome, argumentos) else null

    override val acoesPedemOk: Boolean get() = ConfirmacoesTv.modo(app, Confirmacoes.Acao.MCP_ACOES) == Confirmacoes.Modo.PERGUNTAR

    override fun chamar(nome: String, argumentos: JSONObject): String = when (nome) {
        "estado_tv" -> estadoTv().toString()
        "clima" -> clima().toString()
        "diagnostico_rede" -> diagnostico().toString()
        "historico_rede" -> HistoricoRede.json(app, argumentos.optInt("horas", 24).coerceIn(1, 168)).toString()
        "aparelhos_rede" -> aparelhos().toString()
        "aparelhos_tailscale" -> if (!TunelTailscale.redeLigada(app)) throw McpProtocolo.ErroFerramenta("a opção Rede Tailscale está desligada nas Configurações da TV")
            else TunelTailscale.pares ?: throw McpProtocolo.ErroFerramenta("a lista ainda não chegou (o túnel manda a cada minuto)")
        "apps" -> JSONObject(PedidoIa.appsDaHome(app) as Map<*, *>).toString()
        "agenda_hoje" -> Agenda.resumoHoje(app) ?: "nada na agenda hoje (ou a agenda não está ligada)"
        "pedir_acoes" -> pedir(argumentos).toString()
        "executar_shell" -> shell(argumentos)
        "ver_pedido" -> (PedidoIa.estado(argumentos.optString("id")) ?: throw McpProtocolo.ErroFerramenta("pedido não encontrado (só os 5 últimos ficam)")).json().toString()
        else -> if (McpPoder.conhece(nome)) McpPoder.chamar(app, nome, argumentos)
            else if (McpPoder2.conhece(nome)) McpPoder2.chamar(app, nome, argumentos)
            else if (McpIdeias.conhece(nome)) McpIdeias.chamar(app, nome, argumentos)
            else throw McpProtocolo.ErroFerramenta("ferramenta desconhecida")
    }

    private fun estadoTv(): JSONObject {
        val pm = app.getSystemService(PowerManager::class.java)
        val am = app.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("tela_ligada", pm?.isInteractive ?: JSONObject.NULL)
            .put("app_na_frente", McpPoder.appNaFrente() ?: JSONObject.NULL)
            // Shell próprio e túnel no ar ou fora (e há quanto tempo), com a ação sugerida e a última manutenção da noite.
            .put("saude", SaudeAcesso.json(app))
            .put("volume", am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
            .put("volume_max", am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
            .put("mudo", am?.isStreamMute(AudioManager.STREAM_MUSIC) ?: JSONObject.NULL)
    }

    /** O que a HOME mostrou por último (ela grava em "clima" a cada atualização). */
    private fun clima(): JSONObject {
        val c = app.getSharedPreferences("clima", Context.MODE_PRIVATE)
        if (!c.contains("em")) throw McpProtocolo.ErroFerramenta("a TV ainda não buscou o clima")
        return JSONObject()
            .put("temperatura_c", c.getInt("temp", 0))
            .put("chovendo", c.getBoolean("chovendo", false))
            .put("codigo_wmo", c.getInt("codigo", -1))
            .put("dia", c.getBoolean("eDia", true))
            .put("umidade_pct", c.getInt("umidade", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("chance_chuva_pct", c.getInt("chuva", 0))
            .put("atualizado_ha_min", (System.currentTimeMillis() - c.getLong("em", 0)) / 60_000)
    }

    private fun diagnostico(): JSONObject {
        val w = Diagnostico.wifi(app); val i = Diagnostico.internet(app); val d = Diagnostico.dns(app); val v = Diagnostico.vpn(app)
        fun item(x: Diagnostico.Item) = JSONObject().put("titulo", x.titulo).put("detalhe", x.detalhe).put("nivel", x.nivel)
        return JSONObject().put("wifi", item(w)).put("internet", item(i)).put("dns", item(d)).put("vpn", item(v))
            .put("veredito", item(Diagnostico.veredito(w, i, d, v)))
            // A causa provável numa frase (DiagnosticoInternet): mede mais um pouco (até ~7 s no total).
            .put("em_uma_frase", DiagnosticoInternet.json(app))
    }

    private fun aparelhos(): JSONObject {
        val l = try { UnifiCliente.ler(app) } catch (e: Exception) {
            throw McpProtocolo.ErroFerramenta("roteador não respondeu (ou não está configurado no painel): ${e.javaClass.simpleName}")
        }
        val lista = JSONArray()
        for (a in l.aparelhos) lista.put(JSONObject().put("nome", a.nome).put("ip", a.ip ?: JSONObject.NULL)
            .put("tipo", a.tipo).put("desde", a.desde ?: JSONObject.NULL))
        return JSONObject().put("quantos", l.aparelhos.size).put("aparelhos", lista)
    }

    private fun shell(a: JSONObject): String {
        if (!McpServidor.shellLigado(app)) throw McpProtocolo.ErroFerramenta("o shell pelo Claude está desligado nas Configurações da TV")
        val cmd = a.optString("comando").trim()
        if (cmd.isEmpty() || cmd.length > 8000) throw McpProtocolo.ErroFerramenta("comando vazio ou longo demais (máx. 8000 caracteres)")
        // Até 40 s: o túnel corta a resposta em 60 s, e um comando mais longo rodaria sem a saída chegar (auditoria 10/11/2026).
        val limite = a.optInt("tempo_s", 20).coerceIn(1, 40) * 1000L
        // Confirmações: Sala com "Liberar tudo" roda direto; instalação nova vem BLOQUEADO (ou pergunta na TV, até 60 s).
        if (!ConfirmacoesTv.permitir(app, Confirmacoes.Acao.MCP_SHELL, McpPoderRegras.textoPerguntaShell(cmd, rotulo = txt(R.string.cf_shell), naoMostrados = txt(R.string.cf_shell_cortado)), "internet"))
            throw McpProtocolo.ErroFerramenta(
                if (ConfirmacoesTv.modo(app, Confirmacoes.Acao.MCP_SHELL) == Confirmacoes.Modo.BLOQUEAR)
                    "o shell pela IA está bloqueado nas Confirmações da TV (Configurações › Sistema › Confirmações)"
                else "a TV negou o comando, ou ninguém respondeu em 60 s")
        PainelArmazem.registrar(app, "internet", "shell MCP", cmd.take(300))
        val saida = GuardiaoShizuku.comando(app, cmd, limite)
            ?: throw McpProtocolo.ErroFerramenta("o shell da TV não respondeu (adb próprio desconectado ou passou do tempo)")
        // Saída grande demais derruba a resposta: corta e avisa.
        return if (saida.length <= 60_000) saida else saida.take(60_000) + "\n[... cortado: ${saida.length} caracteres no total]"
    }

    private fun pedir(a: JSONObject): JSONObject {
        val acoes = a.optJSONArray("acoes") ?: throw McpProtocolo.ErroFerramenta("falta \"acoes\"")
        val motivo = a.optString("motivo").ifBlank { "pedido do Claude" }
        val p = PedidoIa.propor(app, motivo, "mcp", JSONObject().put("acoes", acoes).put("resposta", "").toString())
        if (p.situacao == PedidoIa.Situacao.ERRO) throw McpProtocolo.ErroFerramenta(p.mensagem)
        return p.json().put("dica", "a TV mostra o pedido; chame ver_pedido com este id em alguns segundos")
    }

    companion object {
        private fun ferramenta(nome: String, descricao: String, props: JSONObject = JSONObject(), obrigatorios: List<String> = emptyList()) =
            JSONObject().put("name", nome).put("description", descricao)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                    .apply { if (obrigatorios.isNotEmpty()) put("required", JSONArray(obrigatorios)) })
                .put("annotations", JSONObject().put("readOnlyHint", nome != "pedir_acoes" && nome != "executar_shell")
                    .apply { if (nome == "executar_shell") put("destructiveHint", true) })

        val LISTA: JSONArray = JSONArray()
            .put(ferramenta("estado_tv", "Tela ligada ou dormindo, qual app está na frente, o volume e a saúde do acesso (shell próprio e túnel, com a ação sugerida)."))
            .put(ferramenta("clima", "Temperatura, chuva e umidade que a tela inicial mostrou por último."))
            .put(ferramenta("diagnostico_rede", "Wi-Fi, internet, DNS e VPN da TV agora, com um veredito e a resposta em uma frase (causa provável)."))
            .put(ferramenta("historico_rede", "Histórico de rede da TV (quedas, latência, DNS, sinal) nas últimas N horas.",
                JSONObject().put("horas", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 168))))
            .put(ferramenta("aparelhos_rede", "Aparelhos conectados no roteador UniFi da casa (nome, IP, tipo)."))
            .put(ferramenta("aparelhos_tailscale", "Aparelhos da conta Tailscale do dono (nome, sistema, online). Só com a opção Rede Tailscale ligada na TV."))
            .put(ferramenta("apps", "Apps que a tela inicial mostra (pacote -> nome). Só estes podem ser abertos."))
            .put(ferramenta("agenda_hoje", "Resumo dos compromissos de hoje do perfil atual da TV."))
            .put(ferramenta("pedir_acoes",
                "Pede ações à TV: abrir app, abrir tela, criar despertador, volume, mídia, modo visita, recado na tela, timer, " +
                    "desligar em X min, filtro noturno, rodar cena, item no mural, e Filmes e Séries (tocar_titulo, continuar, " +
                    "proximo_episodio, o_que_estava_vendo; títulos de categoria oculta ou bloqueada não aparecem). " +
                    "Conforme as Confirmações da TV, executa na hora, espera OK no controle (60 s) ou é recusado; " +
                    "use ver_pedido para saber o resultado. " +
                    "Máximo ${ComandosIa.MAX_ACOES} ações. " +
                    "tocar_canal: põe a TV ao vivo num canal pelo nome ou número, como a pessoa falou (campo canal, ex.: \"globo\", \"5\"); " +
                    "a TV escolhe na lista dela, e canal bloqueado pelo controle parental é recusado. " +
                    "o_que_passa: diz o programa de agora e o próximo de um canal, pelo guia (o texto volta em ver_pedido, campo mensagem). " +
                    "Se a TV ao vivo nunca foi aberta, o pedido é recusado. " +
                    "casa: liga, desliga ou alterna um aparelho do Home Assistant (entidade copiada de casa_listar), na mesma lista " +
                    "das outras ações (ex.: apagar a luz da sala e abrir a Netflix de uma vez).",
                JSONObject()
                    .put("acoes", ComandosIa.esquema(openApi = false).getJSONObject("properties").getJSONObject("acoes"))
                    .put("motivo", JSONObject().put("type", "string").put("description", "frase curta mostrada na TV")),
                listOf("acoes")))
            .put(ferramenta("executar_shell",
                "Roda um comando no shell da TV (Android 14, usuário shell uid 2000, como `adb shell`): pm, am, cmd, settings, " +
                    "dumpsys, input, screencap, logcat -d etc. Devolve a saída (stdout+stderr). Conforme as Confirmações da TV, roda direto, pede OK no controle ou é recusado. " +
                    "Cuidado: desinstalar ou limpar br.paulo.tvlauncher perde o Device Owner (irreversível), e mexer em DNS/VPN/Wi-Fi " +
                    "pode derrubar a internet da casa. Comandos interativos ou sem fim (logcat sem -d, top) param no tempo_s.",
                JSONObject()
                    .put("comando", JSONObject().put("type", "string").put("description", "linha de comando, ex.: dumpsys battery"))
                    .put("tempo_s", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 40).put("description", "limite em segundos (padrão 20)")),
                listOf("comando")))
            .put(ferramenta("ver_pedido", "Situação de um pedido feito com pedir_acoes.",
                JSONObject().put("id", JSONObject().put("type", "string")), listOf("id")))
    }
}

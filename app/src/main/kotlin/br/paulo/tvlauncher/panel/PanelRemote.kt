package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.ConectarClaudeActivity
import br.paulo.tvlauncher.LoginGoogle
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.mcp.AtalhosTunel
import br.paulo.tvlauncher.mcp.McpServidor
import br.paulo.tvlauncher.mcp.TunelTailscale
import br.paulo.tvlauncher.network.Unifi
import br.paulo.tvlauncher.network.UnifiArmazem
import br.paulo.tvlauncher.network.UnifiCliente
import br.paulo.tvlauncher.panel.PainelWeb.Resposta
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.txt
import org.json.JSONArray
import org.json.JSONObject

/** Painel web: roteador UniFi (só leitura) e Claude pela internet (MCP + túnel). Saiu do PainelWeb na refatoração de 21/11/2026. */

/** O que o painel mostra. A chave NUNCA vem inteira: só os 4 últimos caracteres. */
internal fun PainelWeb.roteadorEstado(): JSONObject = JSONObject()
    .put("ligado", UnifiArmazem.ligado(app))
    .put("endereco", UnifiArmazem.endereco(app) ?: JSONObject.NULL)
    .put("chave", UnifiArmazem.finalDaChave(app) ?: JSONObject.NULL)
    .put("certificado", UnifiArmazem.certificado(app) ?: JSONObject.NULL)
    .put("avisoNovos", UnifiArmazem.avisoNovos(app))
    .put("conhecidos", UnifiArmazem.conhecidos(app).size)
    .put("pronto", UnifiArmazem.pronto(app))

/** Campos opcionais: endereco, chave, ligado, avisoNovos. Trocar o endereço apaga chave e certificado (ver UnifiArmazem). */
internal fun PainelWeb.roteadorConfig(req: Requisicao, ip: String): Resposta {
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val mudou = ArrayList<String>()
    if (o.has("endereco")) {
        val e = Unifi.enderecoValido(o.optString("endereco"))
            ?: return erro(400, "endereço inválido: use o IP do roteador na rede de casa (ex.: 192.168.0.1)")
        if (e != UnifiArmazem.endereco(app)) { UnifiArmazem.definirEndereco(app, e); certificadoPendente = null; mudou.add("endereço") }
    }
    if (o.has("chave")) {
        val bruta = o.optString("chave")
        if (bruta.isEmpty()) { UnifiArmazem.definirChave(app, null); mudou.add("chave apagada") } else {
            val c = Unifi.chaveValida(bruta) ?: return erro(400, "chave inválida (cole a chave inteira criada em Integrações do UniFi)")
            if (UnifiArmazem.endereco(app) == null) return erro(400, "salve o endereço do roteador antes da chave")
            if (!UnifiArmazem.definirChave(app, c)) return erro(500, "não consegui guardar a chave cifrada")
            mudou.add("chave")
        }
    }
    if (o.has("ligado")) { UnifiArmazem.definirLigado(app, o.optBoolean("ligado")); mudou.add("ligado=${o.optBoolean("ligado")}") }
    if (o.has("avisoNovos")) { UnifiArmazem.definirAvisoNovos(app, o.optBoolean("avisoNovos")); mudou.add("aviso=${o.optBoolean("avisoNovos")}") }
    UnifiCliente.limparCache()
    if (mudou.isNotEmpty()) PainelArmazem.registrar(app, ip, "roteador: ${mudou.joinToString(", ")}", "ok")
    return json(200, roteadorEstado())
}

/** Passo 1 do certificado: lê do roteador SEM mandar a chave e devolve a impressão para o dono conferir. */
internal fun PainelWeb.roteadorCertificado(ip: String): Resposta {
    val endereco = UnifiArmazem.endereco(app) ?: return erro(400, "salve o endereço do roteador primeiro")
    val c = try { UnifiCliente.buscarCertificado(app, endereco) } catch (e: UnifiCliente.Falha) { return erro(502, e.message ?: "falhou") }
    certificadoPendente = endereco to c.impressao
    PainelArmazem.registrar(app, ip, "roteador: ler certificado", "ok")
    return json(200, JSONObject().put("impressao", c.impressao).put("emitidoPara", c.emitidoPara).put("validoAte", c.validoAte)
        .put("igualAoConfirmado", c.impressao == UnifiArmazem.certificado(app)))
}

/** Passo 2: o dono confirma a impressão que viu. Só vale a lida agora há pouco, do endereço atual. */
internal fun PainelWeb.roteadorConfiar(req: Requisicao, ip: String): Resposta {
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val pendente = certificadoPendente
    if (pendente == null || pendente.first != UnifiArmazem.endereco(app) || pendente.second != o.optString("impressao"))
        return erro(409, "leia o certificado de novo antes de confirmar")
    UnifiArmazem.definirCertificado(app, pendente.second)
    certificadoPendente = null
    UnifiCliente.limparCache()
    PainelArmazem.registrar(app, ip, "roteador: confiar no certificado", "ok")
    return json(200, roteadorEstado())
}

internal fun PainelWeb.roteadorAparelhos(req: Requisicao): Resposta {
    val l = try { UnifiCliente.ler(app, forcar = req.consulta["atualizar"] == "1") } catch (e: UnifiCliente.Falha) {
        return erro(502, e.message ?: "falhou")
    }
    val lista = org.json.JSONArray()
    for (a in l.aparelhos) lista.put(JSONObject().put("nome", a.nome).put("ip", a.ip ?: JSONObject.NULL)
        .put("mac", a.mac ?: JSONObject.NULL).put("tipo", a.tipo).put("desde", a.desde ?: JSONObject.NULL))
    return json(200, JSONObject().put("versao", l.versao ?: JSONObject.NULL).put("respostaMs", l.respostaMs)
        .put("em", l.em).put("aparelhos", lista))
}

internal fun PainelWeb.roteadorWifiTv(): Resposta {
    val w = try { UnifiCliente.wifiDaTv(app) } catch (e: UnifiCliente.Falha) { return erro(502, e.message ?: "falhou") }
    val o = JSONObject().put("noWifi", w.conectadaNoWifi).put("dbm", w.dbm).put("banda", Unifi.banda(w.mhz) ?: JSONObject.NULL)
        .put("mbps", w.mbps).put("achadaNoRoteador", w.achadaNoRoteador).put("dicas", org.json.JSONArray(w.dicas))
    w.ap?.let { ap ->
        val radios = org.json.JSONArray()
        for (r in ap.radios) radios.put(JSONObject().put("ghz", r.ghz).put("canal", r.canal ?: JSONObject.NULL)
            .put("larguraMhz", r.larguraMhz ?: JSONObject.NULL).put("retransmissoesPct", r.retransmissoesPct ?: JSONObject.NULL))
        o.put("pontoDeAcesso", JSONObject().put("nome", ap.nome).put("modelo", ap.modelo ?: JSONObject.NULL)
            .put("cpuPct", ap.cpuPct ?: JSONObject.NULL).put("radios", radios))
    }
    w.peloRoteador?.let { r ->
        o.put("peloRoteador", JSONObject().put("sinalDbm", r.sinalDbm ?: JSONObject.NULL)
            .put("banda", Unifi.bandaDoRadio(r.radio) ?: JSONObject.NULL).put("canal", r.canal ?: JSONObject.NULL)
            .put("txMbps", r.txMbps ?: JSONObject.NULL).put("rxMbps", r.rxMbps ?: JSONObject.NULL)
            .put("satisfacao", r.satisfacao ?: JSONObject.NULL).put("rede", r.rede ?: JSONObject.NULL))
    }
    w.falhaApiAntiga?.let { o.put("falhaApiAntiga", it) }
    return json(200, o)
}

internal fun PainelWeb.estadoReconectar(): JSONObject = JSONObject()
    .put("estado", UnifiCliente.estadoReconectar.name.lowercase())
    .put("detalhe", UnifiCliente.detalheReconectar ?: JSONObject.NULL)

/** Só ABRE a confirmação na TV: quem decide é quem está com o controle (ConfirmarRoteadorActivity). */
internal fun PainelWeb.roteadorReconectar(ip: String): Resposta {
    UnifiCliente.pedirReconectar(app)?.let { return erro(409, it) }
    PainelArmazem.registrar(app, ip, "roteador: reconectar a TV", "esperando OK na TV")
    return json(200, estadoReconectar())
}

internal fun PainelWeb.roteadorEsquecer(ip: String): Resposta {
    UnifiArmazem.esquecer(app)
    certificadoPendente = null
    UnifiCliente.limparCache()
    PainelArmazem.registrar(app, ip, "roteador: esquecer", "ok")
    return json(200, roteadorEstado())
}

internal fun PainelWeb.mcpEstado(): JSONObject = JSONObject()
    .put("ligado", McpServidor.ligado(app))
    .put("tunelConfigurado", TunelTailscale.configurado(app))
    .put("tunelRodando", TunelTailscale.rodando())
    .put("temChave", TunelTailscale.temChave(app))
    .put("endereco", TunelTailscale.nome(app)?.let { "https://$it/mcp" } ?: JSONObject.NULL)
    .put("erro", TunelTailscale.ultimoErro(app) ?: JSONObject.NULL)
    .put("logins", McpServidor.logins(app))
    .put("google", LoginGoogle.configurado(app))
    .put("binario", TunelTailscale.binario(app).exists())
    .put("conta", TunelTailscale.conta(app) ?: JSONObject.NULL)
    .put("rede", TunelTailscale.redeLigada(app))
    .put("atalhos", TunelTailscale.atalhos(app))
    .put("passo", ConectarClaudeActivity.Passo.agora(app).let {
        JSONObject().put("id", it.id).put("titulo", it.titulo).put("texto", it.texto).put("qr", it.qr ?: JSONObject.NULL)
    })

/** Ligar/desligar, colar a chave do Tailscale (PIN de novo), revogar logins, esquecer o Tailscale (PIN de novo). */
internal fun PainelWeb.mcpConfig(req: Requisicao, token: String?, ip: String): Resposta {
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    // Abrir a TV para fora (MCP na internet, painel na rede Tailscale, atalhos para aparelhos da casa) pede o PIN de
    // novo, como instalar e desinstalar: um cookie roubado na Wi-Fi (o painel é HTTP) não pode publicar a TV
    // (revisão 09/11/2026). Desligar e revogar continuam sem PIN: fechar nunca deve ser difícil.
    val abre = o.optBoolean("ligado") || o.optBoolean("rede") || o.optString("atalhos").isNotBlank()
    if (abre && !sessoes.elevada(token)) return precisaPin()
    // Confirmações primeiro, antes de mudar qualquer coisa: abrir a TV para fora de casa.
    if (o.optBoolean("ligado") && !McpServidor.ligado(app))
        confirmarNaTv(Confirmacoes.Acao.ABRIR_FORA, txt(R.string.cf_ligar_mcp), ip)?.let { return it }
    if (o.optBoolean("rede") && !TunelTailscale.redeLigada(app))
        confirmarNaTv(Confirmacoes.Acao.ABRIR_FORA, txt(R.string.cf_ligar_tailscale), ip)?.let { return it }
    val mudou = ArrayList<String>()
    if (o.has("chave")) {
        if (!sessoes.elevada(token)) return precisaPin()
        val c = o.optString("chave").trim()
        if (!TunelTailscale.chaveValida(c)) return erro(400, "chave inválida: cole a chave inteira, que começa com tskey-")
        if (!TunelTailscale.definirChave(app, c)) return erro(500, "não consegui guardar a chave cifrada")
        mudou.add("chave do Tailscale")
    }
    if (o.optBoolean("esquecer")) {
        if (!sessoes.elevada(token)) return precisaPin()
        TunelTailscale.esquecer(app); mudou.add("esquecer Tailscale")
    }
    if (o.has("google_id")) {
        if (!sessoes.elevada(token)) return precisaPin()
        val id = o.optString("google_id").trim(); val seg = o.optString("google_segredo").trim()
        if (id.isEmpty()) { LoginGoogle.definir(app, null, null); mudou.add("login Google desligado") }
        else {
            if (!LoginGoogle.idValido(id)) return erro(400, "id inválido: termina em .apps.googleusercontent.com")
            if (!LoginGoogle.segredoValido(seg)) return erro(400, "segredo do cliente inválido")
            LoginGoogle.definir(app, id, seg); McpServidor.revogar(app); mudou.add("login Google ligado")
        }
    }
    if (o.optBoolean("revogar")) { McpServidor.revogar(app); mudou.add("revogar logins") }
    if (o.has("atalhos")) {
        if (!TunelTailscale.definirAtalhos(app, o.optString("atalhos")))
            return erro(400, "atalhos inválidos: use aparelho:porta separados por vírgula (máximo ${AtalhosTunel.MAXIMO}), ex.: nas:8096")
        mudou.add("atalhos")
    }
    if (o.has("rede")) { TunelTailscale.definirRede(app, o.optBoolean("rede")); mudou.add("rede=${o.optBoolean("rede")}") }
    if (o.has("ligado")) {
        if (o.optBoolean("ligado") && !PainelArmazem.temPin(app)) return erro(400, "defina o PIN antes")
        McpServidor.definirLigado(app, o.optBoolean("ligado")); mudou.add("ligado=${o.optBoolean("ligado")}")
    } else if (mudou.isNotEmpty()) TunelTailscale.ajustar(app)
    if (mudou.isNotEmpty()) PainelArmazem.registrar(app, ip, "mcp: ${mudou.joinToString(", ")}", "ok")
    return json(200, mcpEstado())
}

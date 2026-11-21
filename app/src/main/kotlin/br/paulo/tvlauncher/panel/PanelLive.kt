package br.paulo.tvlauncher.panel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import br.paulo.tvlauncher.AutorizaShizukuService
import br.paulo.tvlauncher.BoasVindasActivity
import br.paulo.tvlauncher.ConfirmacoesTv
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.mcp.ChaveAcesso
import br.paulo.tvlauncher.panel.PainelWeb.Resposta
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.PareamentoNaTv
import br.paulo.tvlauncher.privilege.PrimeiraPoderes
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.privilege.SaudeAcesso
import br.paulo.tvlauncher.txt
import br.paulo.tvlauncher.weather.LocalClima
import org.json.JSONArray
import org.json.JSONObject

/** Painel web: eventos ao vivo, digital do celular e papéis/convites. Saiu do PainelWeb na refatoração de 21/11/2026. */

/**
 * Server-Sent Events: a página recebe "o que está na TV" na hora em que muda (abriu um app, a TV dormiu), em vez
 * de perguntar a cada 30 s. Barato de propósito: a cada 1,5 s só olha se a tela está acesa e os eventos de uso dos
 * últimos segundos (não as 6 h do [PainelApps.naTela]); só escreve quando algo mudou, mais um "ping" a cada 5 s.
 * Como o /api/estado, usa existe() e não valida(): ficar com a página aberta não segura a sessão viva para sempre;
 * quando ela vence, o fluxo fecha e a página vai para o PIN no próximo pedido.
 */
internal fun PainelWeb.eventos(token: String?): Resposta {
    if (token == null || !sessoes.existe(token)) return erro(401, "entre com o PIN")
    val meu = Any()
    eventosDaSessao[token] = meu   // o fluxo antigo desta sessão vê que não é mais o dela e sai no próximo passo
    if (eventosAbertos.get() >= MAX_EVENTOS) Thread.sleep(EVENTOS_PASSO_MS + 200)   // dá tempo do antigo sair
    if (eventosAbertos.incrementAndGet() > MAX_EVENTOS) {
        eventosAbertos.decrementAndGet()
        return erro(503, "muitas janelas do painel abertas; esta atualiza a cada 30 s")
    }
    return Resposta(200, null, ByteArray(0), fluxo = { saida ->
        try { transmitirEventos(token, saida) { eventosDaSessao[token] === meu } }
        finally { eventosAbertos.decrementAndGet(); eventosDaSessao.remove(token, meu) }
    })
}

internal fun PainelWeb.transmitirEventos(token: String?, saida: java.io.OutputStream, atual: () -> Boolean) {
    val cab = StringBuilder("HTTP/1.1 200 OK\r\n")
    for ((k, v) in PainelHttp.CABECALHOS_SEGURANCA) cab.append(k).append(": ").append(v).append("\r\n")
    cab.append("Content-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n")
    saida.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
    // Reconectar depois de 3 s se cair (padrão do navegador, explícito).
    saida.write("retry: 3000\n\n".toByteArray()); saida.flush()

    val pm = app.getSystemService(android.os.PowerManager::class.java)
    val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
    val inicio = System.currentTimeMillis()
    var desde = inicio
    var naTela = PainelApps.naTela(app)
    var enviado: String? = null
    var ultimaEscrita = inicio
    while (true) {
        val agora = System.currentTimeMillis()
        if (agora - inicio > EVENTOS_DURACAO_MS || !ligado() || !sessoes.existe(token) || !atual()) break
        if (pm != null && !pm.isInteractive) naTela = "em espera"
        else {
            var ultimo: String? = null
            try {
                // 3 s de sobra para trás: o Android às vezes grava o evento com um pouco de atraso.
                val ev = usm.queryEvents(desde - 3_000, agora)
                val e = android.app.usage.UsageEvents.Event()
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e)
                    if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) ultimo = e.packageName
                }
            } catch (_: Exception) {}
            naTela = when {
                ultimo != null -> PainelApps.rotuloNaTela(app, ultimo)
                naTela == "em espera" -> PainelApps.naTela(app)   // acordou sem abrir nada novo: recalcula inteiro
                else -> naTela
            }
        }
        desde = agora
        val json = JSONObject().put("naTela", naTela).toString()
        if (json != enviado) {
            saida.write("event: estado\ndata: $json\n\n".toByteArray()); saida.flush()
            enviado = json; ultimaEscrita = agora
        } else if (agora - ultimaEscrita > 5_000) {
            saida.write(": ping\n\n".toByteArray()); saida.flush()   // a cada 5 s: descobre logo a conexão morta (a vaga volta)
            ultimaEscrita = agora
        }
        try { Thread.sleep(EVENTOS_PASSO_MS) } catch (_: InterruptedException) { break }
    }
}

/**
 * Confere o PIN com o contador global. Devolve null se acertou, ou a resposta de erro pronta (PIN errado,
 * bloqueado, painel desligado por excesso de erros).
 */
internal fun PainelWeb.conferirPin(req: Requisicao, ip: String, acao: String): Resposta? {
    val pin = try { JSONObject(req.corpoTexto()).optString("pin") } catch (e: Exception) { "" }
    synchronized(travaPin) {
        val ctl = controle()
        // Painel desligado (por erros ou nas Configurações) ou contador esgotado: não confere mais nada, nem os
        // pedidos que já estavam na fila quando a porta fechou.
        if (ctl.esgotado() || !PainelArmazem.podeLigar(app)) return erro(403, "painel desligado. Religue nas Configurações do launcher, na TV.")
        val espera = ctl.esperaMs()
        if (espera > 0) return erro(429, "muitas tentativas erradas; espere ${(espera + 999) / 1000} s") { put("esperarSeg", (espera + 999) / 1000) }
        if (PainelArmazem.conferirPin(app, pin)) { ctl.registrarSucesso(); return null }
        PainelArmazem.registrar(app, ip, acao, "PIN errado")
        return when (val r = ctl.registrarFalha()) {
            is ControleTentativas.Resultado.Errou -> erro(401, "PIN errado") { put("restantes", r.restantes) }
            is ControleTentativas.Resultado.Bloqueou -> erro(429, "PIN errado; bloqueado por ${r.ms / 60_000} min") { put("esperarSeg", r.ms / 1000) }
            ControleTentativas.Resultado.Desligar -> { desligarPorErros(ip); erro(403, "PIN errado demais: o painel foi desligado. Religue nas Configurações do launcher, na TV.") }
        }
    }
}

/**
 * A senha da unidade de rede (cabeçalho Basic) é o PIN do painel, com o MESMO contador de erros e o mesmo bloqueio
 * crescente do login. null = pode entrar. O cache leva junto a marca do PIN: trocou o PIN, a senha velha cai.
 */
internal fun PainelWeb.conferirSenhaDav(cabecalho: String?, ip: String): PainelDav.Recusa? {
    val pin = DavLogica.senhaBasic(cabecalho) ?: return PainelDav.Recusa(401, "senha: o PIN do painel")
    val chave = cabecalho + "\u0000" + PainelArmazem.marcaPin(app)
    if (senhasDav.certo(chave)) return null
    synchronized(travaPin) {
        val ctl = controle()
        if (ctl.esgotado() || !PainelArmazem.podeLigar(app)) return PainelDav.Recusa(403, "painel desligado. Religue nas Configurações do launcher, na TV.")
        val espera = ctl.esperaMs()
        if (espera > 0) return PainelDav.Recusa(429, "muitas tentativas erradas; espere ${(espera + 999) / 1000} s",
            mapOf("Retry-After" to ((espera + 999) / 1000).toString()))
        // A mesma senha errada repetida pelo cliente (o Windows e o davfs2 repetem sozinhos) conta uma vez só.
        if (senhasDav.errado(chave)) return PainelDav.Recusa(401, "PIN errado")
        if (PainelArmazem.conferirPin(app, pin)) {
            ctl.registrarSucesso()
            senhasDav.lembrarCerto(chave)
            PainelArmazem.registrar(app, ip, "unidade de rede: entrar", "ok")
            return null
        }
        senhasDav.lembrarErrado(chave)
        PainelArmazem.registrar(app, ip, "unidade de rede", "PIN errado")
        return when (val r = ctl.registrarFalha()) {
            is ControleTentativas.Resultado.Errou -> PainelDav.Recusa(401, "PIN errado")
            is ControleTentativas.Resultado.Bloqueou -> PainelDav.Recusa(429, "PIN errado; bloqueado por ${r.ms / 60_000} min",
                mapOf("Retry-After" to (r.ms / 1000).toString()))
            ControleTentativas.Resultado.Desligar -> { desligarPorErros(ip); PainelDav.Recusa(403, "PIN errado demais: o painel foi desligado.") }
        }
    }
}

internal fun PainelWeb.desligarPorErros(ip: String) {
    PainelArmazem.definirDesligadoPorErros(app, true)
    PainelArmazem.registrar(app, ip, "painel", "desligado por PIN errado demais")
    Log.w(TAG, "PIN errado ${ControleTentativas.LIMITE_DESLIGAR} vezes: painel desligado")
    // Fecha a porta logo depois de responder (a conexão atual continua aberta até a resposta sair).
    Handler(Looper.getMainLooper()).post {
        ajustar(app)
        try { Toast.makeText(app, app.getString(R.string.painel_desligado_pin), Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }
}

internal fun PainelWeb.entrar(req: Requisicao, ip: String): Resposta {
    conferirPin(req, ip, "entrar")?.let { return it }
    if (!ligado()) return erro(503, "painel desligado")
    val token = sessoes.criar()
    PainelArmazem.registrar(app, ip, "entrar", "ok")
    return json(200, JSONObject().put("ok", true), cookieSessao(token))
}

/**
 * Entrar com o código do QR da TV, sem PIN. A sessão nasce SEM elevação: o que pede o PIN de novo (desinstalar,
 * instalar, backup...) continua pedindo. Código errado não conta como tentativa de PIN (192 bits: não se adivinha).
 */
internal fun PainelWeb.entrarPeloQr(req: Requisicao, ip: String): Resposta {
    if (!ligado() || !PainelArmazem.podeLigar(app)) return erro(503, "painel desligado")
    val codigo = try { JSONObject(req.corpoTexto()).optString("codigo") } catch (e: Exception) { "" }
    if (!codigoEntrada.usar(codigo)) return erro(403, "código vencido ou já usado: gere outro na TV")
    val token = sessoes.criar(elevada = false)
    PainelArmazem.registrar(app, ip, "entrar pelo QR da TV", "ok")
    return json(200, JSONObject().put("ok", true), cookieSessao(token))
}

/**
 * Passo 1 do assistente da primeira configuração ([PrimeiraConfig]): com o código do QR da TV, cria o PIN, liga o
 * painel e entra (sessão elevada: a pessoa acabou de digitar o PIN). Sem volta só no sentido de que, com PIN, este
 * caminho fecha para sempre; trocar o PIN depois é pelas Configurações da TV, como sempre.
 */
internal fun PainelWeb.primeiraConfig(req: Requisicao, ip: String): Resposta {
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val pin = o.optString("pin")
    PrimeiraConfig.conferir(PainelArmazem.temPin(app), modoPrimeira, pin)?.let { return erro(it.status, it.mensagem) }
    if (!codigoPrimeira.usar(o.optString("codigo"))) return erro(PrimeiraConfig.Recusa.CODIGO.status, PrimeiraConfig.Recusa.CODIGO.mensagem)
    synchronized(travaPin) {
        if (PainelArmazem.temPin(app)) return erro(409, PrimeiraConfig.Recusa.JA_TEM_PIN.mensagem)   // corrida: outro criou antes
        PainelArmazem.definirPin(app, pin)   // PBKDF2 (~1 s): numa thread do pedido, não na da tela
        tentativas?.zerar()
    }
    PainelArmazem.definirLigado(app, true)
    val token = sessoes.criar(elevada = true)
    PainelArmazem.registrar(app, ip, "primeira configuração: PIN criado pelo celular", "ok")
    return json(200, JSONObject().put("ok", true), cookieSessao(token))
}

/** Só pelo https do Tailscale: é o único endereço com nome de domínio e certificado que o navegador aceita. */
internal fun PainelWeb.chaveDisponivel(host: String?): Boolean = ChecagensPainel.viaHttps(host, nomesHttps())

internal fun PainelWeb.chavesEstado(host: String?): JSONObject = JSONObject()
    .put("disponivel", chaveDisponivel(host))
    .put("endereco", nomesHttps().firstOrNull()?.let { "https://$it:${ChecagensPainel.PORTA_HTTPS}/" } ?: JSONObject.NULL)
    .put("chaves", JSONArray().apply {
        for (c in PainelArmazem.chaves(app)) put(JSONObject().put("id", c.id).put("nome", c.nome).put("criada", c.criada).put("usada", c.usadaEm))
    })

internal fun PainelWeb.cadastrarChave(req: Requisicao, token: String?, host: String?, ip: String): Resposta {
    if (!chaveDisponivel(host)) return erro(403, "a digital só funciona pelo endereço do Tailscale (https)")
    if (!sessoes.elevada(token)) return precisaPin()
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val desafio = o.optString("desafio")
    if (!desafiosChave.usar(desafio)) return erro(403, "o pedido venceu: tente de novo")
    val nova = try {
        ChaveAcesso.cadastrar(o.optString("id"), o.optString("chavePublica"), o.optInt("alg"), o.optString("authData"),
            o.optString("clientData"), desafio, ChaveAcesso.rpId(host!!), ChaveAcesso.origem(host), o.optString("nome"), System.currentTimeMillis())
    } catch (e: ChaveAcesso.Recusa) {
        PainelArmazem.registrar(app, ip, "cadastrar digital", "recusado: ${e.message}")
        return erro(400, "o celular mandou uma chave que a TV não aceita (${e.message})")
    }
    val lista = PainelArmazem.chaves(app).filter { it.id != nova.id }
    if (lista.size >= ChaveAcesso.MAX_CHAVES) return erro(409, "já tem ${ChaveAcesso.MAX_CHAVES} digitais: apague uma antes")
    PainelArmazem.gravarChaves(app, lista + nova)
    PainelArmazem.registrar(app, ip, "cadastrar digital \"${nova.nome}\"", "ok")
    return json(200, chavesEstado(host))
}

internal fun PainelWeb.apagarChave(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val id = corpoJson(req)?.optString("id").orEmpty()
    val lista = PainelArmazem.chaves(app)
    val sai = lista.firstOrNull { it.id == id } ?: return erro(404, "essa digital não existe")
    PainelArmazem.gravarChaves(app, lista - sai)
    PainelArmazem.registrar(app, ip, "apagar digital \"${sai.nome}\"", "ok")
    return json(200, chavesEstado(req.cabecalho("host")))
}

/** Entrar com a digital: sessão SEM elevação, como a do QR (o que não tem volta ainda pede o PIN). */
internal fun PainelWeb.entrarComChave(req: Requisicao, host: String?, ip: String): Resposta {
    if (!ligado() || !PainelArmazem.podeLigar(app)) return erro(503, "painel desligado")
    if (!chaveDisponivel(host)) return erro(403, "a digital só funciona pelo endereço do Tailscale (https)")
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val desafio = o.optString("desafio")
    if (!desafiosChave.usar(desafio)) return erro(403, "o pedido venceu: tente de novo")
    val lista = PainelArmazem.chaves(app)
    val chave = lista.firstOrNull { it.id == o.optString("id") }
        ?: return erro(403, "esta digital não está cadastrada nesta TV: entre com o PIN e cadastre de novo")
    val contador = try {
        ChaveAcesso.conferirEntrada(chave, o.optString("authData"), o.optString("clientData"), o.optString("assinatura"), desafio, ChaveAcesso.rpId(host!!), ChaveAcesso.origem(host))
    } catch (e: ChaveAcesso.Recusa) {
        PainelArmazem.registrar(app, ip, "entrar com a digital \"${chave.nome}\"", "recusado: ${e.message}")
        return erro(403, "a digital não confere: entre com o PIN")
    }
    PainelArmazem.gravarChaves(app, lista.map { if (it.id == chave.id) it.copy(contador = contador, usadaEm = System.currentTimeMillis()) else it })
    val token = sessoes.criar(elevada = false)
    PainelArmazem.registrar(app, ip, "entrar com a digital \"${chave.nome}\"", "ok")
    return json(200, JSONObject().put("ok", true), cookieSessao(token))
}

internal fun PainelWeb.primeiraEstado(): JSONObject {
    val loc = java.util.Locale.getDefault()
    return JSONObject()
        .put("idiomaTv", loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) })
        .put("audio", if (IdiomaPlayer.prefs(app).padrao == Idioma.Pref.ORIGINAL) "original" else "dublado")
        .put("cidade", LocalClima.ler(app)?.let { LocalClima.descrever(it) } ?: JSONObject.NULL)
        .put("contasTv", PlayerArmazem.fontes(app).size)
}

internal fun PainelWeb.estadoPoderes() = PrimeiraPoderes.Estado(
    teclas = AutorizaShizukuService.instancia != null,
    dev = Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
    shell = SaudeAcesso.shellEsperado(app),
    dono = PrivilegioDO.somosDonos(app))

/**
 * Abre NA TV a tela do passo pedido (só abre telas: quem confirma é a pessoa, com o controle). O dono do aparelho
 * nunca liga daqui: abre as boas-vindas, onde a pergunta (com o aviso das contas) é respondida na TV.
 */
internal fun PainelWeb.fazerPoderNaTv(req: Requisicao, ip: String): Resposta {
    val passo = PrimeiraPoderes.Passo.de(corpoJson(req)?.optString("passo")) ?: return erro(400, "passo desconhecido")
    val i = { acao: String -> android.content.Intent(acao).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
        when (passo) {
            PrimeiraPoderes.Passo.TECLAS -> app.startActivity(i(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            PrimeiraPoderes.Passo.DEV -> app.startActivity(i(Settings.ACTION_DEVICE_INFO_SETTINGS))
            PrimeiraPoderes.Passo.PAREAR -> {
                // A janela do pareamento é do serviço de acessibilidade: monta na thread da tela.
                if (AutorizaShizukuService.instancia == null) return erro(409, "ligue antes as teclas do controle (passo 1)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { PareamentoNaTv.comecar(app) }
            }
            PrimeiraPoderes.Passo.DONO -> app.startActivity(android.content.Intent(app, BoasVindasActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PainelArmazem.registrar(app, ip, "abrir na TV: ${passo.id}", "ok")
        json(200, JSONObject().put("ok", true))
    } catch (e: Exception) {
        erro(500, "não consegui abrir na TV; faça pelo controle, em Configurações")
    }
}

internal fun PainelWeb.abrirIdiomaNaTv(ip: String): Resposta = try {
    app.startActivity(android.content.Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    PainelArmazem.registrar(app, ip, "abrir o idioma da TV", "ok")
    json(200, JSONObject().put("ok", true))
} catch (e: Exception) {
    erro(500, "não consegui abrir: na TV, vá em Configurações > Sistema > Idioma")
}

/** PIN redigitado para uma ação sem volta: a sessão fica "elevada" por 5 min. */
internal fun PainelWeb.confirmar(req: Requisicao, token: String?, ip: String): Resposta {
    conferirPin(req, ip, "confirmar PIN")?.let { return it }
    sessoes.elevar(token)
    return json(200, JSONObject().put("ok", true))
}

internal fun PainelWeb.precisaPin() = erro(403, "confirme o PIN") { put("precisaPin", true) }

internal fun PainelWeb.prefsPapeis() = app.getSharedPreferences("painel_papeis", android.content.Context.MODE_PRIVATE)

internal fun PainelWeb.criarConvite(req: Requisicao, token: String?, ip: String, https: Boolean, host: String?): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val papel = PapeisPainel.Papel.de(corpoJson(req)?.optString("papel"))?.takeIf { it != PapeisPainel.Papel.DONO }
        ?: return erro(400, "papel inválido")
    val (codigo, lista) = synchronized(this) { PapeisPainel.criar(convites(), papel, System.currentTimeMillis()).also { salvarConvites(it.second) } }
    val link = (if (https) "https://" else "http://") + host + "/#convite=" + codigo
    PainelArmazem.registrar(app, ip, "convite criado", papel.chave)   // o código nunca vai para o registro
    return json(200, JSONObject().put("link", link).put("papel", papel.chave)
        .put("qr", java.util.Base64.getEncoder().encodeToString(QrCodigo.png(link, 480))), mapOf("Cache-Control" to "no-store"))
}

internal fun PainelWeb.revogarConvite(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    synchronized(this) { salvarConvites(if (o.optBoolean("todos")) emptyList() else PapeisPainel.revogar(convites(), o.optString("id"))) }
    sessoes.encerrarConvidados()
    PainelArmazem.registrar(app, ip, "convite revogado", if (o.optBoolean("todos")) "todos" else o.optString("id").take(8))
    return json(200, JSONObject().put("ok", true))
}

/** Entrar pelo link do convite: sessão do papel do convite (convidado: cai quando o convite vence). */
internal fun PainelWeb.entrarPorConvite(req: Requisicao, ip: String): Resposta {
    // Painel desligado pelo dono ou por erros de PIN (aberto só para "Trazer de outra TV"): convite não entra.
    if (!ligado() || !PainelArmazem.podeLigar(app)) return erro(503, "painel desligado")
    val c = PapeisPainel.usar(convites(), corpoJson(req)?.optString("codigo"), System.currentTimeMillis())
        ?: return erro(401, "convite vencido ou revogado")
    val token = sessoes.criar(elevada = false, papel = c.papel, venceEm = c.vence ?: Long.MAX_VALUE)
    PainelArmazem.registrar(app, ip, "entrou por convite", c.papel.chave)
    return json(200, JSONObject().put("ok", true).put("papel", c.papel.chave), cookieSessao(token))
}

/**
 * Confirmações (ConfirmacoesTv): com o modo "Perguntar na TV", mostra a pergunta e espera até 60 s (esta requisição
 * fica parada, numa thread do servidor, não na da tela). Null = pode seguir; senão, a resposta de recusa pronta.
 */
internal fun PainelWeb.confirmarNaTv(a: Confirmacoes.Acao, oQue: String, ip: String): Resposta? =
    if (ConfirmacoesTv.permitir(app, a, oQue, txt(R.string.cf_quem_painel, ip))) null
    else erro(403, if (ConfirmacoesTv.modo(app, a) == Confirmacoes.Modo.BLOQUEAR)
        "bloqueado na TV (Configurações › Sistema › Confirmações)" else "negado na TV, ou ninguém respondeu em 60 s") { put("negadoNaTv", true) }

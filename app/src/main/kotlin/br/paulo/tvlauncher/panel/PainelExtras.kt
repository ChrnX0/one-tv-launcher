package br.paulo.tvlauncher.panel

import android.content.Context
import android.util.Base64
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Desfazer
import br.paulo.tvlauncher.DesfazerRegras
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Gatilhos
import br.paulo.tvlauncher.Presenca
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Retrospectiva
import br.paulo.tvlauncher.TocandoAgoraTv
import br.paulo.tvlauncher.alarm.HoraDormir
import br.paulo.tvlauncher.alarm.HoraDormirTv
import br.paulo.tvlauncher.alarm.RecadoLink
import br.paulo.tvlauncher.family.Diario
import br.paulo.tvlauncher.family.RecadoImagem
import br.paulo.tvlauncher.family.TravaRelogio
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.games.TurmaDiscordTv
import br.paulo.tvlauncher.input.TesteTeclas
import br.paulo.tvlauncher.mcp.CaixaComandoTv
import br.paulo.tvlauncher.mcp.ComandosIa
import br.paulo.tvlauncher.mcp.McpAutorizacao
import br.paulo.tvlauncher.mcp.McpServidor
import br.paulo.tvlauncher.mcp.PedidoIa
import br.paulo.tvlauncher.network.AcordarPc
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.ConsumoRedeTv
import br.paulo.tvlauncher.network.DiarioDns
import br.paulo.tvlauncher.observer.Caches
import br.paulo.tvlauncher.observer.ErrosFiltrados
import br.paulo.tvlauncher.observer.ModoLeveTv
import br.paulo.tvlauncher.player.LojaCurada
import br.paulo.tvlauncher.player.VideoAoVivo
import br.paulo.tvlauncher.player.salvar
import br.paulo.tvlauncher.privilege.ExtrasDO
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.ExtrasAbas
import br.paulo.tvlauncher.settings.FiltroNoturno
import br.paulo.tvlauncher.weather.AvisoRegras
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rotas do painel das ideias de 10/11/2026, num arquivo só delas para o PainelWeb.kt (que está em vários PRs abertos)
 * ganhar só três ganchos: "/cena/<token>", "/api/extras/..." e a resposta em fluxo do vídeo.
 *
 * Todas as rotas daqui já passaram pelas defesas do PainelWeb (rede de casa, Host, Origin + X-Painel nos POST, sessão),
 * menos "/cena/<token>", que é para os Atalhos do iPhone e o Home Assistant: ali a senha é o próprio token de 128 bits
 * ([Cenas]). O que mexe em segurança ou privacidade exige o PIN redigitado nos últimos 5 min ([elevada]).
 */
object PainelExtras {
    /** Corpo maior só para o recado com foto (a página reduz a foto antes de mandar: ~200 a 400 KB em base64). */
    const val LIMITE_FOTO = 768 * 1024
    const val ROTA_FOTO = "/api/extras/recado-foto"

    /** Chaves que só mudam com o PIN redigitado. O conjunto mora em [Extras] para a tela da TV usar o MESMO. */
    private val CHAVES_COM_PIN get() = Extras.CHAVES_COM_PIN

    @Volatile private var ultimoRecadoLink = 0L

    /**
     * POST /recado/<token> (2ª leva, 10/11/2026): recado no formato do TvOverlay/PiPup, para o Home Assistant. 404 para
     * token errado ou chave desligada (sem dizer qual), 429 se vier mais de 1 a cada 3 s.
     */
    fun recadoPorLink(app: Context, metodo: String, token: String, corpo: String?, ip: String): Pair<Int, JSONObject> {
        if (metodo != "POST") return erro(405, "use POST")
        if (!Extras.ligado(app, Extras.Chave.RECADO_LINK) ||
            !RecadoLink.confere(token, Extras.texto(app, RecadoLink.CHAVE_RESUMO))) return erro(404, "não existe")
        val agora = System.currentTimeMillis()
        if (agora - ultimoRecadoLink < RecadoLink.INTERVALO_MS) return erro(429, "devagar: 1 recado a cada 3 s")
        val p = RecadoLink.ler(corpo) ?: return erro(400, "mande {\"title\":\"...\",\"message\":\"...\",\"duration\":10}")
        ultimoRecadoLink = agora
        val r = AvisoTv.mostrar(app, p.titulo.ifEmpty { app.getString(R.string.recado_link_titulo) }, p.texto, p.segundos,
            som = true, guardarSeApagada = false, fica = AvisoRegras.Fica.ATENCAO)
        PainelArmazem.registrar(app, ip, "recado por link", r.name.lowercase())
        return if (r == AvisoTv.Resultado.MOSTRADO) ok() else erro(409, r.name.lowercase())
    }

    /** Cria (ou troca) o link do recado. Devolve o token, que só aparece agora. */
    fun novoLinkRecado(app: Context): String {
        val t = Cenas.gerarToken()
        Extras.definirTexto(app, RecadoLink.CHAVE_RESUMO, Cenas.resumo(t))
        return t
    }

    /** Efeito de mudar uma chave da 2ª leva (o que precisa de shell ou de Settings roda fora da thread da tela). */
    fun aoMudar(app: Context, c: Extras.Chave) {
        when (c) {
            Extras.Chave.MODO_LEVE -> Fundo.rodar { ModoLeveTv.aplicar(app) }
            Extras.Chave.TOCANDO_AGORA -> Fundo.rodar { TocandoAgoraTv.ajustar(app, Extras.ligado(app, c)) }
            // Hora de dormir ligada trava o relógio da TV; desligada destrava (DO fora da thread da tela).
            Extras.Chave.HORA_DORMIR -> Fundo.rodar { TravaRelogio.ajustar(app) }
            Extras.Chave.TEMPO_TELA -> Fundo.rodar { TravaRelogio.ajustar(app) }   // ideia 33: data travada (senão zera a cota)
            // Ideias de 12/11/2026: os alarmes periódicos ligam e desligam junto com a chave.
            Extras.Chave.PRESENCA -> Presenca.agendar(app)
            Extras.Chave.RETROSPECTIVA -> Retrospectiva.agendar(app)
            else -> {}
        }
    }

    /** Minuto do dia em [o][k], ou null se faltar ou estiver fora de 0..1439. */
    private fun minuto2(o: JSONObject, k: String): Int? = if (o.has(k)) o.optInt(k, -1).takeIf { it in 0 until 24 * 60 } else null

    private fun ok(o: JSONObject = JSONObject()) = 200 to o.put("ok", true)
    private fun erro(codigo: Int, msg: String) = codigo to JSONObject().put("erro", msg)
    private fun pin() = 403 to JSONObject().put("erro", "confirme o PIN").put("precisaPin", true)

    /** POST /cena/<token>: roda a cena. 404 para token desconhecido (sem dizer se a cena existe). */
    fun cena(app: Context, metodo: String, token: String, ip: String): Pair<Int, JSONObject> {
        if (metodo != "POST") return erro(405, "use POST")
        val r = Cenas.rodarPorToken(app, token, "link") ?: return erro(404, "não existe")
        PainelArmazem.registrar(app, ip, "cena por link", r)
        return ok(JSONObject().put("resultado", r))
    }

    /** Resto do caminho depois de "/api/extras/". Null = rota desconhecida (o PainelWeb responde 404). */
    fun rotear(app: Context, metodo: String, rota: String, corpo: JSONObject?, consulta: Map<String, String>,
               elevada: Boolean, ip: String): Pair<Int, JSONObject>? {
        val get = metodo == "GET"; val post = metodo == "POST"
        return when {
            get && rota == "estado" -> ok(estado(app))
            // Erros do launcher sem dado pessoal (ErrosFiltrados), para o botão "Relatar no GitHub" da aba Saúde (11/11/2026).
            get && rota == "erros" -> ok(ErrosFiltrados.json(app, 5))
            // Caixa de comando sem IA (ideia 10): frase -> uma ação do catálogo fechado (CaixaComandoTv).
            post && rota == "comando" -> CaixaComandoTv.executar(app, corpo?.optString("frase"), ip)
            post && rota == "chave" -> {
                val c = Extras.Chave.values().firstOrNull { it.name == corpo?.optString("chave") } ?: return erro(400, "chave desconhecida")
                if (c in CHAVES_COM_PIN && !elevada) return pin()
                val sim = corpo!!.optBoolean("ligado")
                Extras.definir(app, c, sim)
                when (c) {
                    Extras.Chave.FILTRO_NOTURNO -> android.os.Handler(android.os.Looper.getMainLooper()).post { FiltroNoturno.avaliar() }
                    Extras.Chave.JANELA_OTA, Extras.Chave.TRAVAR_CRITICOS, Extras.Chave.DIARIO_DNS -> ExtrasDO.aplicar(app)
                    else -> aoMudar(app, c)
                }
                PainelArmazem.registrar(app, ip, "extras: ${c.name} = $sim", "ok")
                ok(estado(app))
            }
            post && rota == "filtro" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                fun minuto(k: String, padrao: Int) = if (o.has(k)) o.optInt(k).coerceIn(0, 24 * 60 - 1) else padrao
                Extras.definirNumero(app, FiltroNoturno.CHAVE_INICIO, minuto("inicio", Extras.numero(app, FiltroNoturno.CHAVE_INICIO, FiltroNoturno.PADRAO_INICIO)))
                Extras.definirNumero(app, FiltroNoturno.CHAVE_FIM, minuto("fim", Extras.numero(app, FiltroNoturno.CHAVE_FIM, FiltroNoturno.PADRAO_FIM)))
                if (o.has("forca")) Extras.definirNumero(app, FiltroNoturno.CHAVE_FORCA, o.optInt("forca").coerceIn(5, 100))
                android.os.Handler(android.os.Looper.getMainLooper()).post { FiltroNoturno.avaliar() }
                ok(estado(app))
            }
            post && rota == "wol" -> {
                if (!AcordarPc.guardarMac(app, corpo?.optString("mac") ?: "")) return erro(400, "MAC inválido (ex.: AA:BB:CC:DD:EE:FF)")
                // IP do PC (opcional, card Jogos): com ele, "Jogar no PC" sabe quando o PC acordou em vez de esperar 20 s.
                if (corpo?.has("ip") == true && !JogosTv.guardarIpPc(app, corpo.optString("ip"))) return erro(400, "IP inválido (ex.: 192.168.1.10)")
                PainelArmazem.registrar(app, ip, "extras: MAC do computador guardado", "ok")
                ok(estado(app))
            }
            // Turma do Discord da tela Jogos: o webhook nunca volta inteiro para o navegador (só se existe).
            get && rota == "turma" -> ok(JSONObject().put("servidor", TurmaDiscordTv.servidor(app)).put("webhook", TurmaDiscordTv.temWebhook(app)))
            post && rota == "turma" -> {
                corpo?.optString("servidor", null)?.let { if (!TurmaDiscordTv.guardarServidor(app, it)) return erro(400, "ID do servidor inválido (17 a 20 números)") }
                corpo?.optString("webhook", null)?.let { if (!TurmaDiscordTv.guardarWebhook(app, it)) return erro(400, "Webhook inválido (https://discord.com/api/webhooks/...)") }
                ok(JSONObject().put("servidor", TurmaDiscordTv.servidor(app)).put("webhook", TurmaDiscordTv.temWebhook(app)))
            }
            post && rota == "acordar" -> {
                val r = AcordarPc.acordar(app)
                PainelArmazem.registrar(app, ip, "acordar o computador", if (r) "enviado" else "falhou")
                if (r) ok() else erro(409, "não enviei: falta o MAC ou a TV está sem rede")
            }
            get && rota == "diario" -> {
                if (!elevada) return pin()
                val dia = consulta["dia"]?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: DiarioDns.diaDe(System.currentTimeMillis())
                val ag = DiarioDns.carregar(app, dia)
                ok(JSONObject().put("dia", dia).put("dias", JSONArray(DiarioDns.diasGuardados(app))).put("erro", DiarioDns.erro(app))
                    .put("linhas", JSONArray().apply {
                        ag.maisConsultados(200).forEach { put(JSONObject().put("app", it.pacote).put("nome", it.host).put("vezes", it.contagem)) }
                    }))
            }
            post && rota == "diario/apagar" -> {
                if (!elevada) return pin()
                DiarioDns.apagarTudo(app)
                PainelArmazem.registrar(app, ip, "diário de DNS apagado", "ok")
                ok()
            }
            post && rota == "teclas/armar" -> {
                TesteTeclas.armar()
                PainelArmazem.registrar(app, ip, "testador de teclas armado por 2 min", "ok")
                ok()
            }
            get && rota == "teclas" -> ok(TesteTeclas.json())
            get && rota == "cenas" -> ok(JSONObject().put("cenas", JSONArray().apply {
                Cenas.lista(app).forEach { put(JSONObject().put("nome", it.nome).put("acoes", it.acoes)) }
            }).put("gatilhos", JSONObject(Gatilhos.paraJson(Gatilhos.todos(app)))))
            // Eventos como gatilho (ideia 7): {"midia_tocou": "Cinema", ...}; vazio tira o evento. PIN, como criar cena.
            post && rota == "cenas/gatilhos" -> {
                if (!elevada) return pin()
                val o = corpo ?: return erro(400, "JSON inválido")
                val nomes = Cenas.lista(app).map { it.nome }.toSet()
                val m = Gatilhos.lerJson(o.toString())
                m.values.firstOrNull { it !in nomes }?.let { return erro(400, "a cena \"$it\" não existe") }
                Gatilhos.salvar(app, m)
                PainelArmazem.registrar(app, ip, "gatilhos de cena: ${m.size}", "ok")
                ok(JSONObject(Gatilhos.paraJson(m)))
            }
            post && rota == "cenas/criar" -> {
                if (!elevada) return pin()
                val o = corpo ?: return erro(400, "JSON inválido")
                val acoes = o.opt("acoes")?.toString() ?: ""   // {"acoes":[...]} como objeto ou como texto
                val r = Cenas.criar(app, o.optString("nome"), acoes, Cenas.appsInstalados(app))
                if (r.startsWith("erro:")) return erro(400, r.removePrefix("erro:").trim())
                PainelArmazem.registrar(app, ip, "cena criada: ${o.optString("nome")}", "ok")
                ok(JSONObject().put("token", r))   // só aparece agora: a TV guarda só o resumo
            }
            // Testar uma cena sem salvar (14/11/2026, ideia 3): o mesmo caminho de uma cena de verdade (PedidoIa, com as
            // Confirmações da TV). Sem PIN, como o controle remoto do painel: só ações do catálogo fechado.
            post && rota == "cenas/testar" -> {
                val acoes = corpo?.opt("acoes")?.toString() ?: return erro(400, "JSON inválido")
                val plano = ComandosIa.conferir(acoes, Cenas.appsInstalados(app))
                if (plano.acoes.isEmpty()) return ok(JSONObject().put("recusadas", JSONArray(plano.recusadas.ifEmpty { listOf("nenhuma ação") })))
                val p = PedidoIa.propor(app, "teste de cena", "painel", acoes)
                PainelArmazem.registrar(app, ip, "cena testada", p.situacao.name.lowercase())
                ok(JSONObject().put("id", p.id).put("recusadas", JSONArray(plano.recusadas)))
            }
            post && rota == "cenas/apagar" -> {
                if (!elevada) return pin()
                val nome = corpo?.optString("nome") ?: ""
                if (!Cenas.apagar(app, nome)) return erro(404, "não existe")
                PainelArmazem.registrar(app, ip, "cena apagada: $nome", "ok")
                ok()
            }
            get && rota == "loja" -> ok(JSONObject().put("apps", LojaCurada.json(app)))
            post && rota == "loja/instalar" -> {
                if (!elevada) return pin()
                val id = corpo?.optString("id") ?: ""
                val r = LojaCurada.instalar(app, id)   // bloqueia até instalar (minutos): esta é a thread do pedido
                PainelArmazem.registrar(app, ip, "loja: $id", r)
                if (r.startsWith("erro:")) erro(409, r.removePrefix("erro:").trim()) else ok(JSONObject().put("resultado", r))
            }
            post && rota == "loja/repo" -> {
                if (!elevada) return pin()   // um repositório novo é um app novo que a TV aceita instalar
                val repo = corpo?.optString("repo").orEmpty()
                val remover = corpo?.optBoolean("remover") ?: false
                LojaCurada.definirRepo(app, repo, remover)?.let { return erro(400, it) }
                PainelArmazem.registrar(app, ip, if (remover) "loja: repositório tirado" else "loja: repositório somado", repo.take(120))
                ok(JSONObject().put("apps", LojaCurada.json(app)))
            }
            post && rota == "recado-foto" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val bytes = try { Base64.decode(o.optString("imagem"), Base64.DEFAULT) } catch (e: Exception) { return erro(400, "imagem inválida") }
                val r = RecadoImagem.mostrar(app, o.optString("texto").ifBlank { null }, bytes,
                    if (o.has("segundos")) o.optInt("segundos") else null) ?: return erro(400, "imagem inválida (JPEG, PNG ou WebP até 4 MB)")
                PainelArmazem.registrar(app, ip, "recado com foto (${bytes.size / 1024} KB)", r.name.lowercase())
                if (r == AvisoTv.Resultado.MOSTRADO) ok() else erro(409, "a acessibilidade do launcher está desligada, ou a TV está dormindo")
            }
            // ---- 2ª leva (10/11/2026)
            post && rota == "caches/limpar" -> {
                val r = Caches.limpar(app)   // espera o shell (até 40 s): esta é a thread do pedido
                PainelArmazem.registrar(app, ip, "limpar caches", r)
                ok(JSONObject().put("resultado", r))
            }
            get && rota == "consumo" -> ok(ConsumoRedeTv.json(app, consulta["dias"]?.toIntOrNull()?.coerceIn(1, 30) ?: 1))
            get && rota == "tocando" -> ok(TocandoAgoraTv.json(app))
            post && rota == "tocando" -> {
                val a = corpo?.optString("acao") ?: ""
                if (TocandoAgoraTv.controlar(app, a)) ok() else erro(409, "nada tocando (ou a chave está desligada)")
            }
            post && rota == "dormir" -> {
                if (!elevada) return pin()
                val o = corpo ?: return erro(400, "JSON inválido")
                fun minuto(k: String, padrao: Int) = if (o.has(k)) o.optInt(k).coerceIn(0, 24 * 60 - 1) else padrao
                Extras.definirNumero(app, HoraDormir.CHAVE_INICIO, minuto("inicio", HoraDormirTv.inicio(app)))
                Extras.definirNumero(app, HoraDormir.CHAVE_FIM, minuto("fim", HoraDormirTv.fim(app)))
                if (o.has("teto")) Extras.definirNumero(app, HoraDormir.CHAVE_TETO, o.optInt("teto").coerceIn(0, 100))
                // Horário por dia: 7 itens (domingo primeiro), cada um {inicio, fim} ou null (segue o padrão).
                o.optJSONArray("dias")?.let { a ->
                    HoraDormirTv.definirDias(app, (0 until 7).map { d ->
                        a.optJSONObject(d)?.let { j -> HoraDormir.Janela(minuto2(j, "inicio") ?: return@map null, minuto2(j, "fim") ?: return@map null) }
                    })
                }
                if (o.has("apps")) Extras.definirTexto(app, HoraDormir.CHAVE_PERMITIDOS,
                    HoraDormir.lerPermitidos(o.optString("apps")).joinToString(","))
                PainelArmazem.registrar(app, ip, "hora de dormir: horário/apps", "ok")
                ok(estado(app))
            }
            post && rota == "recado-link" -> {
                if (!elevada) return pin()
                val t = novoLinkRecado(app)
                PainelArmazem.registrar(app, ip, "recado por link: link novo", "ok")
                ok(JSONObject().put("token", t))   // só aparece agora
            }
            // ---- cliente de IA novo sem esperar versão (ideia 61): domínios liberados para o login do MCP
            get && rota == "mcp/hosts" -> ok(JSONObject().put("hosts", JSONArray(McpServidor.hostsExtras(app).sorted()))
                .put("fixos", JSONArray(McpAutorizacao.HOSTS_HTTPS.sorted())))
            post && rota == "mcp/hosts" -> {
                if (!elevada) return pin()
                val a = corpo?.optJSONArray("hosts") ?: return erro(400, "faltou a lista")
                val lista = (0 until a.length()).map { a.optString(it) }
                if (!McpServidor.definirHostsExtras(app, lista))
                    return erro(400, "domínio inválido (ex.: app.exemplo.com, sem https:// e sem barra) ou mais de ${McpAutorizacao.MAX_HOSTS_EXTRAS}")
                PainelArmazem.registrar(app, ip, "IA: domínios liberados", lista.joinToString(", ").take(200))
                ok(JSONObject().put("hosts", JSONArray(McpServidor.hostsExtras(app).sorted())))
            }
            // ---- diário da TV e webhook (ideias 58 e 59, Diario.kt)
            get && rota == "diario-tv" -> ok(JSONObject().put("itens", Diario.ler(app, 24 * 7))   // "diario" é o de DNS (acima)
                .put("webhook", Diario.webhook(app)).put("origens", JSONArray(Diario.origensWebhook(app).toList()))
                .put("ultimo", Diario.ultimoWebhook(app))
                .put("passos", JSONArray().apply { Desfazer.lista(app).forEach { put(DesfazerRegras.resumo(it)) } }))
            // Elevação 2.11: desfazer uma mudança feita nas telas da TV. Pede o PIN: volta ajustes de qualquer tipo.
            post && rota == "desfazer" -> {
                if (!elevada) return pin()
                val id = corpo?.optString("id").orEmpty()
                if (!Regex("^[a-z0-9]{1,20}$").matches(id)) return erro(400, "id inválido")
                val r = Desfazer.desfazer(app, id, ip) ?: return erro(404, "nada a desfazer")
                ok(JSONObject().put("voltaram", r.voltaram).put("ficaram", r.ficaram))
            }
            post && rota == "diario/webhook" -> {
                if (!elevada) return pin()   // um link novo recebe tudo o que acontece na TV: pede o PIN, como o recado por link
                val origens = corpo?.optJSONArray("origens")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                if (!Diario.definirWebhook(app, corpo?.optString("url").orEmpty(), origens))
                    return 400 to JSONObject().put("erro", "endereço inválido: https, ou http só dentro de casa (IP da rede, .local, .lan)")
                PainelArmazem.registrar(app, ip, "webhook do diário", if (Diario.webhook(app).isEmpty()) "desligado" else "definido")
                ok(JSONObject().put("ok", true))
            }
            // ---- casa (Home Assistant, CasaHA.kt)
            get && rota == "casa" -> {
                val o = JSONObject().put("configurado", CasaHA.configurado(app)).put("endereco", CasaHA.endereco(app) ?: JSONObject.NULL)
                    .put("campainha", CasaHA.texto(app, CasaHA.CHAVE_CAMPAINHA)).put("camera", CasaHA.texto(app, CasaHA.CHAVE_CAMERA))
                if (CasaHA.configurado(app)) try {
                    o.put("aparelhos", JSONArray().apply { CasaHA.listar(app).forEach { put(it.json()) } })
                } catch (e: CasaHA.Erro) { o.put("erroCasa", e.message) }
                ok(o)
            }
            post && rota == "casa/config" -> {
                if (!elevada) return pin()
                CasaHA.configurar(app, corpo?.optString("endereco") ?: "", corpo?.optString("token") ?: "")?.let { return erro(400, it) }
                PainelArmazem.registrar(app, ip, "casa: Home Assistant configurado", "ok")   // o token nunca vai para o registro
                ok()
            }
            post && rota == "casa/campainha" -> {
                if (!elevada) return pin()
                CasaHA.configurarCampainha(app, corpo?.optString("sensor")?.trim() ?: "", corpo?.optString("camera")?.trim() ?: "")?.let { return erro(400, it) }
                ok()
            }
            post && rota == "casa/esquecer" -> {
                if (!elevada) return pin()
                CasaHA.esquecer(app); PainelArmazem.registrar(app, ip, "casa: Home Assistant esquecido", "ok"); ok()
            }
            post && rota == "casa/acionar" -> try {
                val id = corpo?.optString("id") ?: ""; val acao = corpo?.optString("acao") ?: ""
                val r = CasaHA.acionar(app, id, acao)
                PainelArmazem.registrar(app, ip, "casa: $id $acao", "ok")
                ok(JSONObject().put("resultado", r))
            } catch (e: CasaHA.Erro) { erro(409, e.message ?: "erro") }
            else -> PainelIdeias.rotear(app, metodo, rota, corpo, ip)   // ideias de 12/11/2026
        }
    }

    private fun estado(app: Context): JSONObject = JSONObject()
        .put("chaves", JSONObject().apply { Extras.Chave.values().forEach { put(it.name, Extras.ligado(app, it)) } })
        .put("comPin", JSONArray(CHAVES_COM_PIN.map { it.name }))
        // Painel v3 (16/11/2026): em que aba das Configurações da TV cada chave mora, para o painel mostrá-la no mesmo lugar.
        .put("abas", JSONObject().apply { ExtrasAbas.MAPA.forEach { (chave, aba) -> put(chave, aba.name) } })
        .put("filtro", JSONObject()
            .put("inicio", Extras.numero(app, FiltroNoturno.CHAVE_INICIO, FiltroNoturno.PADRAO_INICIO))
            .put("fim", Extras.numero(app, FiltroNoturno.CHAVE_FIM, FiltroNoturno.PADRAO_FIM))
            .put("forca", Extras.numero(app, FiltroNoturno.CHAVE_FORCA, FiltroNoturno.PADRAO_FORCA)))
        .put("mac", AcordarPc.macGuardado(app))
        .put("pcIp", JogosTv.ipPc(app))
        .put("diarioErro", DiarioDns.erro(app))
        .put("otaPendente", ExtrasDO.atualizacaoPendente(app) ?: JSONObject.NULL)
        .put("dormir", JSONObject().put("inicio", HoraDormirTv.inicio(app)).put("fim", HoraDormirTv.fim(app))
            .put("teto", HoraDormirTv.teto(app)).put("apps", HoraDormirTv.permitidos(app).joinToString(", "))
            .put("dias", JSONArray().apply { HoraDormirTv.dias(app).forEach { j ->
                put(j?.let { JSONObject().put("inicio", it.inicio).put("fim", it.fim) } ?: JSONObject.NULL) } }))
        .put("temLinkRecado", Extras.texto(app, RecadoLink.CHAVE_RESUMO).isNotEmpty())
        .put("livre", Caches.livreTexto())

    /**
     * GET /api/extras/video: o H.264 em quadros [tamanho][NAL] até o fim ([VideoAoVivo.transmitir]). Sem Content-Length:
     * o corpo acaba quando a conexão fecha (HTTP/1.1 com "Connection: close").
     */
    fun video(app: Context, saida: OutputStream) {
        val cab = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        saida.write(cab.toByteArray(Charsets.ISO_8859_1)); saida.flush()
        VideoAoVivo.transmitir(app, saida) { false }
    }
}

package br.paulo.tvlauncher.panel

import android.content.Context
import br.paulo.tvlauncher.Avisos
import br.paulo.tvlauncher.CompatibilidadeTv
import br.paulo.tvlauncher.LembretesTv
import br.paulo.tvlauncher.ModoSimples
import br.paulo.tvlauncher.Presenca
import br.paulo.tvlauncher.ReceitasTv
import br.paulo.tvlauncher.Retrospectiva
import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.family.Mural
import br.paulo.tvlauncher.home.HomeCards
import br.paulo.tvlauncher.home.HomeCardsTv
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.observer.DesempenhoTv
import br.paulo.tvlauncher.settings.Receitas
import br.paulo.tvlauncher.settings.VisualCodigoTv
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rotas do painel das ideias de 12/11/2026, em "/api/extras/ideias/...". O [PainelExtras.rotear] passa para cá o que
 * não conhece, então todas já passaram pelas defesas do PainelWeb (rede de casa, Host, Origin + X-Painel, sessão).
 *
 * Nada aqui instala, apaga app nem mexe em rede. O que é só do dono (desligar o modo simples) segue a regra das chaves
 * com PIN, que mora no [PainelExtras].
 */
object PainelIdeias {
    /** Importar a lista OPML do app de podcast do celular: corpo maior que o comum (PainelWeb.limiteCorpo). */
    const val ROTA_OPML = "/api/extras/ideias/podcasts/opml"
    const val LIMITE_OPML = 512 * 1024
    private fun ok(o: JSONObject = JSONObject()) = 200 to o.put("ok", true)
    private fun erro(codigo: Int, msg: String) = codigo to JSONObject().put("erro", msg)

    fun rotear(app: Context, metodo: String, rota: String, corpo: JSONObject?, ip: String): Pair<Int, JSONObject>? {
        if (!rota.startsWith("ideias/")) return null
        val r = rota.removePrefix("ideias/")
        val get = metodo == "GET"; val post = metodo == "POST"
        return when {
            get && r == "estado" -> ok(estado(app))
            // ---- 1. lembretes e Meu time
            post && r == "palavras" -> {
                LembretesTv.definirPalavras(app, corpo?.optString("texto") ?: "")
                PainelArmazem.registrar(app, ip, "Meu time: palavras", "ok")
                ok(LembretesTv.json(app))
            }
            post && r == "lembretes/apagar" -> {
                if (!LembretesTv.apagar(app, corpo?.optString("chave") ?: "")) return erro(404, "não existe")
                ok(LembretesTv.json(app))
            }
            // ---- 3. avisos no celular
            post && r == "avisos/inscrever" -> {
                val o = corpo?.optJSONObject("inscricao") ?: return erro(400, "falta a inscrição")
                Avisos.inscrever(app, o, corpo.optString("nome"))?.let { return erro(400, it) }
                PainelArmazem.registrar(app, ip, "avisos no celular: aparelho inscrito", "ok")
                ok(Avisos.json(app))
            }
            post && r == "avisos/remover" -> {
                if (!Avisos.removerPorId(app, corpo?.optString("id") ?: "")) return erro(404, "não existe")
                PainelArmazem.registrar(app, ip, "avisos no celular: aparelho removido", "ok")
                ok(Avisos.json(app))
            }
            post && r == "avisos/tipo" -> {
                val t = Avisos.Tipo.values().firstOrNull { it.name == corpo?.optString("tipo") } ?: return erro(400, "tipo desconhecido")
                Avisos.definir(app, t, corpo!!.optBoolean("ligado"))
                ok(Avisos.json(app))
            }
            post && r == "avisos/testar" -> ok(JSONObject().put("resultado", Avisos.testar(app)))   // bloqueia: thread do pedido
            // ---- 4. desempenho
            // ---- degrau 4: receitas prontas. Sem a sessão elevada aqui, o que DESLIGA algo com PIN (desfazer o
            // modo simples, o infantil...) é recusado com precisaPin e se faz na TV, que pede o PIN.
            get && r == "receitas" -> ok(receitas(app))
            post && r == "receitas/aplicar" -> {
                val rec = Receitas.dePalavra(corpo?.optString("receita")) ?: return erro(400, "receita desconhecida")
                if (Receitas.pedePin(ReceitasTv.mudancas(app, rec), PainelArmazem.temPin(app)))
                    return 403 to JSONObject().put("erro", "faça na TV: pede o PIN").put("precisaPin", true)
                val m = ReceitasTv.aplicar(app, rec)
                PainelArmazem.registrar(app, ip, "receita ${rec.name}", Receitas.paraTexto(m.toMap()))
                ok(receitas(app))
            }
            post && r == "receitas/desfazer" -> {
                if (Receitas.pedePin(ReceitasTv.mudancasDesfazer(app), PainelArmazem.temPin(app)))
                    return 403 to JSONObject().put("erro", "faça na TV: pede o PIN").put("precisaPin", true)
                val m = ReceitasTv.desfazer(app)
                PainelArmazem.registrar(app, ip, "receita desfeita", Receitas.paraTexto(m.toMap()))
                ok(receitas(app))
            }
            get && r == "desempenho" -> ok(DesempenhoTv.json(app))
            get && r == "compatibilidade" -> ok(CompatibilidadeTv.json(app))   // página "Funciona no seu aparelho?"
            // tema por QR: o código do visual desta TV e aplicar um recebido
            get && r == "visual" -> ok(JSONObject().put("codigo", VisualCodigoTv.codigo(app)).put("link", VisualCodigoTv.link(app)))
            post && r == "visual" -> {
                val feito = VisualCodigoTv.aplicar(app, corpo?.optString("codigo") ?: "") ?: return erro(400, "código inválido")
                PainelArmazem.registrar(app, ip, "visual aplicado", feito.joinToString(", "))
                ok(JSONObject().put("feito", org.json.JSONArray(feito)))
            }
            // ---- 6. mural
            post && r == "mural" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val l = Mural.lista(o.optString("lista")) ?: return erro(400, "lista: compras ou recados")
                val novo = when (o.optString("acao")) {
                    "adicionar" -> Mural.editar(app) { Mural.adicionar(it, l, o.optString("texto")) }
                    "remover" -> Mural.editar(app) { Mural.remover(it, l, o.optString("texto")) }
                    "limpar" -> Mural.editar(app) { Mural.limparLista(it, l) }
                    "lido" -> Mural.editar(app) { Mural.marcarLido(it, o.optString("texto"), true) }
                    "nao_lido" -> Mural.editar(app) { Mural.marcarLido(it, o.optString("texto"), false) }
                    else -> return erro(400, "acao: adicionar, remover, limpar, lido ou nao_lido")
                }
                ok(Mural.paraJson(novo))
            }
            // ---- cartões próprios da tela inicial (HomeCards, 18/11/2026): contagem, lembrete da casa, serviço, moedas
            post && r == "cartoes" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val remover = o.optString("acao") == "remover"
                when (o.optString("tipo")) {
                    "contagem" -> if (remover) HomeCardsTv.editarContagens(app) { HomeCards.removerContagem(it, o.optString("nome")) } else {
                        val d = runCatching { java.time.LocalDate.parse(o.optString("data")) }.getOrNull() ?: return erro(400, "nome ou data inválidos")
                        val c = HomeCards.criarContagem(o.optString("nome"), d.year, d.monthValue, d.dayOfMonth) ?: return erro(400, "nome ou data inválidos")
                        HomeCardsTv.editarContagens(app) { HomeCards.adicionarContagem(it, c, java.time.LocalDate.now()) }
                    }
                    "lembrete" -> if (remover) HomeCardsTv.editarLembretes(app) { HomeCards.removerLembrete(it, o.optString("texto")) } else {
                        val l = HomeCards.criarLembrete(o.optString("texto"), o.optInt("dias"), if (o.has("minuto") && !o.isNull("minuto")) o.optInt("minuto") else null)
                            ?: return erro(400, "texto ou dias inválidos")
                        HomeCardsTv.editarLembretes(app) { HomeCards.adicionarLembrete(it, l) }
                    }
                    "servico" -> if (remover) HomeCardsTv.editarServicos(app) { HomeCards.removerServico(it, o.optString("nome")) } else {
                        val s = HomeCards.criarServico(o.optString("nome"), o.optString("url")) ?: return erro(400, "nome ou endereço inválidos (http ou https)")
                        HomeCardsTv.editarServicos(app) { HomeCards.adicionarServico(it, s) }
                    }
                    "moedas" -> HomeCardsTv.definirMoedas(app, o.optString("moedas"))
                    else -> return erro(400, "tipo: contagem, lembrete, servico ou moedas")
                }
                PainelArmazem.registrar(app, ip, "cartões da tela inicial: ${o.optString("tipo")}", "ok")
                ok(JSONObject().put("cartoes", HomeCardsTv.json(app)))
            }
            // ---- ideia 93: aniversários
            post && r == "aniversarios" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val nova = when (o.optString("acao")) {
                    "adicionar" -> {
                        val p = Aniversarios.criar(o.optString("nome"), o.optInt("dia"), o.optInt("mes"),
                            if (o.has("ano") && !o.isNull("ano")) o.optInt("ano") else null) ?: return erro(400, "nome ou data inválidos")
                        Aniversarios.editar(app) { Aniversarios.adicionar(it, p) }
                    }
                    "remover" -> Aniversarios.editar(app) { Aniversarios.remover(it, o.optString("nome")) }
                    else -> return erro(400, "acao: adicionar ou remover")
                }
                PainelArmazem.registrar(app, ip, "aniversários: ${nova.size}", "ok")
                ok(JSONObject().put("lista", Aniversarios.paraJson(nova)))
            }
            // ---- ideia 87: rádios (colar o endereço de qualquer rádio; vira favorita na TV)
            post && r == "radios" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val nova = when (o.optString("acao")) {
                    "colar" -> Radios.colar(app, o.optString("nome"), o.optString("url")).getOrElse { return erro(400, it.message ?: "inválido") }
                    "remover" -> Radios.editarFavoritas(app) { l -> l.filterNot { it.id == o.optString("id") } }
                    else -> return erro(400, "acao: colar ou remover")
                }
                PainelArmazem.registrar(app, ip, "rádios favoritas: ${nova.size}", "ok")
                ok(JSONObject().put("lista", Radios.paraJson(nova)))
            }
            // ---- Ouvir parte 2 (17/11/2026): podcasts. Colar o feed, tirar, importar e exportar a lista (OPML).
            post && r == "podcasts" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                when (o.optString("acao")) {
                    "colar" -> Podcasts.adicionar(app, o.optString("url")).getOrElse { return erro(400, it.message ?: "inválido") }
                    "remover" -> Podcasts.desassinar(app, o.optString("url"))
                    else -> return erro(400, "acao: colar ou remover")
                }
                PainelArmazem.registrar(app, ip, "podcasts: ${Podcasts.assinaturas(app).size}", "ok")
                ok(JSONObject().put("lista", podcastsJson(app)))
            }
            post && r == "podcasts/opml" -> {
                val texto = corpo?.optString("opml").orEmpty()
                if (texto.isBlank()) return erro(400, "lista vazia")
                val (assinados, falharam) = Podcasts.importarOpml(app, texto)
                PainelArmazem.registrar(app, ip, "podcasts importados: $assinados (falharam $falharam)", "ok")
                ok(JSONObject().put("assinados", assinados).put("falharam", falharam).put("lista", podcastsJson(app)))
            }
            get && r == "podcasts/opml" -> ok(JSONObject().put("opml", Podcasts.exportarOpml(app)))
            // ---- 7. presença
            get && r == "presenca" -> ok(Presenca.json(app))   // chama o roteador: thread do pedido
            post && r == "presenca" -> {
                val a = corpo?.optJSONArray("macs") ?: return erro(400, "falta macs")
                Presenca.definirEscolhidos(app, (0 until a.length()).map { a.optString(it) })
                // Ideia 40: Home Assistant e IPs; campo ausente (painel antigo) = não mexe.
                if (corpo.has("entidades") || corpo.has("ips")) Presenca.definirOutras(app,
                    corpo.optString("entidades", Presenca.entidades(app).joinToString(",")), corpo.optString("ips", Presenca.ips(app).joinToString(",")))
                Presenca.agendar(app)
                PainelArmazem.registrar(app, ip, "presença: ${Presenca.escolhidos(app).size} celulares escolhidos", "ok")
                ok(JSONObject().put("escolhidos", JSONArray(Presenca.escolhidos(app).toList()))
                    .put("entidades", JSONArray(Presenca.entidades(app))).put("ips", JSONArray(Presenca.ips(app))))
            }
            // ---- 9. retrospectiva
            post && r == "retrospectiva/montar" -> ok(Retrospectiva.paraJson(Retrospectiva.montar(app)))
            // ---- 10. modo simples
            post && r == "simples" -> {
                val a = corpo?.optJSONArray("apps") ?: return erro(400, "falta apps")
                ModoSimples.definirEscolhidos(app, (0 until a.length()).map { a.optString(it) })
                ok(JSONObject().put("apps", JSONArray(ModoSimples.escolhidos(app))))
            }
            else -> null
        }
    }

    private fun podcastsJson(app: Context): JSONArray = JSONArray().apply {
        Podcasts.assinaturas(app).forEach { a -> put(JSONObject().put("url", a.url).put("titulo", a.titulo).put("autor", a.autor)) }
    }

    private fun estado(app: Context): JSONObject {
        val instalados = ModoSimples.instalados(app)
        return JSONObject()
            .put("lembretes", LembretesTv.json(app))
            .put("avisos", Avisos.json(app))
            .put("desempenho", DesempenhoTv.json(app))
            .put("mural", Mural.paraJson(Mural.ler(app)))
            .put("aniversarios", Aniversarios.paraJson(Aniversarios.ler(app)))
            .put("cartoes", HomeCardsTv.json(app))
            .put("radios", Radios.paraJson(Radios.favoritas(app)))
            .put("podcasts", podcastsJson(app))
            .put("presenca", JSONArray(Presenca.escolhidos(app).toList()))
            .put("presencaOutras", JSONObject().put("entidades", Presenca.entidades(app).joinToString(", ")).put("ips", Presenca.ips(app).joinToString(", ")))
            .put("retrospectiva", Retrospectiva.ultimaMedida(app)?.let { Retrospectiva.paraJson(it).put("linhas", JSONArray(Retrospectiva.linhas(it).toList())) } ?: JSONObject.NULL)
            .put("simples", JSONObject().put("apps", JSONArray(ModoSimples.escolhidos(app)))
                .put("instalados", JSONArray().apply {
                    instalados.entries.sortedBy { it.value.lowercase() }.forEach { put(JSONObject().put("pacote", it.key).put("nome", it.value)) }
                }))
    }

    /** Estado das receitas para o painel: as 4, a ativa, o que cada uma mudaria e os avisos. */
    private fun receitas(app: Context): JSONObject {
        val temPin = PainelArmazem.temPin(app)
        val agora = ReceitasTv.ler(app)
        val lista = JSONArray()
        for (rec in Receitas.Receita.values()) {
            val m = Receitas.mudancas(rec, agora, ReceitasTv.permite(app))
            lista.put(JSONObject().put("id", rec.name).put("nome", ReceitasTv.nome(app, rec)).put("texto", ReceitasTv.explicacao(app, rec))
                .put("muda", JSONArray(m.map { ReceitasTv.linha(app, it.first, it.second) }))
                .put("avisos", JSONArray(Receitas.avisos(rec, agora, temPin, ReceitasTv.permite(app)).map { it.name }))
                .put("pedePin", Receitas.pedePin(m, temPin)))
        }
        return JSONObject().put("receitas", lista).put("ativa", ReceitasTv.ativa(app)?.name ?: JSONObject.NULL)
            .put("desfazer", JSONArray(ReceitasTv.mudancasDesfazer(app).map { ReceitasTv.linha(app, it.first, it.second) }))
    }
}

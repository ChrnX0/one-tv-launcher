package br.paulo.tvlauncher

import android.content.Context
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
            get && r == "desempenho" -> ok(DesempenhoTv.json(app))
            // ---- 6. mural
            post && r == "mural" -> {
                val o = corpo ?: return erro(400, "JSON inválido")
                val l = Mural.lista(o.optString("lista")) ?: return erro(400, "lista: compras ou recados")
                val novo = when (o.optString("acao")) {
                    "adicionar" -> Mural.editar(app) { Mural.adicionar(it, l, o.optString("texto")) }
                    "remover" -> Mural.editar(app) { Mural.remover(it, l, o.optString("texto")) }
                    "limpar" -> Mural.editar(app) { Mural.limparLista(it, l) }
                    else -> return erro(400, "acao: adicionar, remover ou limpar")
                }
                ok(Mural.paraJson(novo))
            }
            // ---- 7. presença
            get && r == "presenca" -> ok(Presenca.json(app))   // chama o roteador: thread do pedido
            post && r == "presenca" -> {
                val a = corpo?.optJSONArray("macs") ?: return erro(400, "falta macs")
                Presenca.definirEscolhidos(app, (0 until a.length()).map { a.optString(it) })
                Presenca.agendar(app)
                PainelArmazem.registrar(app, ip, "presença: ${Presenca.escolhidos(app).size} celulares escolhidos", "ok")
                ok(JSONObject().put("escolhidos", JSONArray(Presenca.escolhidos(app).toList())))
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

    private fun estado(app: Context): JSONObject {
        val instalados = ModoSimples.instalados(app)
        return JSONObject()
            .put("lembretes", LembretesTv.json(app))
            .put("avisos", Avisos.json(app))
            .put("desempenho", DesempenhoTv.json(app))
            .put("mural", Mural.paraJson(Mural.ler(app)))
            .put("presenca", JSONArray(Presenca.escolhidos(app).toList()))
            .put("retrospectiva", Retrospectiva.ultima(app)?.let { Retrospectiva.paraJson(it).put("linhas", JSONArray(Retrospectiva.linhas(it).toList())) } ?: JSONObject.NULL)
            .put("simples", JSONObject().put("apps", JSONArray(ModoSimples.escolhidos(app)))
                .put("instalados", JSONArray().apply {
                    instalados.entries.sortedBy { it.value.lowercase() }.forEach { put(JSONObject().put("pacote", it.key).put("nome", it.value)) }
                }))
    }
}

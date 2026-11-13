package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ferramentas MCP das ideias de 12/11/2026 (arquivo próprio, como o McpExtras): "põe leite na lista da TV", "me lembra
 * do jogo do Flamengo", "tem algum filme do Nolan?", "como foi a semana na TV?". Nada aqui instala, apaga app nem mexe em
 * rede; o mural e os lembretes são listas da própria TV.
 */
object McpIdeias {
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun enumS(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
    private fun ferramenta(nome: String, descricao: String, props: JSONObject = JSONObject(), obrigatorios: List<String> = emptyList(), leitura: Boolean = true) =
        JSONObject().put("name", nome).put("description", descricao)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                .apply { if (obrigatorios.isNotEmpty()) put("required", JSONArray(obrigatorios)) })
            .put("annotations", JSONObject().put("readOnlyHint", leitura))

    val LISTA: List<JSONObject> = listOf(
        ferramenta("lembretes", "Lembretes da grade da TV: listar, definir as palavras do 'Meu time' (ex.: 'Flamengo, Fórmula 1'; " +
            "todo programa do guia com uma delas no título vira lembrete 2 min antes) ou apagar um lembrete (chave de listar).",
            JSONObject().put("acao", enumS("listar", "meu_time", "apagar")).put("palavras", s("para meu_time, separadas por vírgula"))
                .put("chave", s("para apagar")), listOf("acao"), leitura = false),
        ferramenta("mural", "Mural da família no protetor de tela: lista de compras e até 3 recados fixos. Ver, adicionar, remover ou limpar.",
            JSONObject().put("acao", enumS("ver", "adicionar", "remover", "limpar")).put("lista", enumS("compras", "recados"))
                .put("texto", s("até 60 caracteres")), listOf("acao"), leitura = false),
        ferramenta("buscar_tv", "Procura nos catálogos guardados da TV (canais, filmes, séries e apps) pelo nome. Não baixa nada.",
            JSONObject().put("termo", s("ex.: 'globo', 'batman'")), listOf("termo")),
        ferramenta("semana_tv", "A última retrospectiva da semana (horas de TV, apps mais vistos, internet, quedas) e o desempenho da HOME por versão."),
    )

    fun conhece(nome: String) = LISTA.any { it.optString("name") == nome }

    private fun erro(t: String): Nothing = throw McpProtocolo.ErroFerramenta(t)

    fun chamar(ctx: Context, nome: String, a: JSONObject): String {
        val app = ctx.applicationContext
        return when (nome) {
            "lembretes" -> when (a.optString("acao")) {
                "listar" -> LembretesTv.json(app).toString()
                "meu_time" -> { LembretesTv.definirPalavras(app, a.optString("palavras")); LembretesTv.json(app).put("aviso", "a TV procura no guia em alguns segundos").toString() }
                "apagar" -> if (LembretesTv.apagar(app, a.optString("chave"))) LembretesTv.json(app).toString() else erro("lembrete não encontrado")
                else -> erro("acao: listar, meu_time ou apagar")
            }
            "mural" -> {
                val acao = a.optString("acao")
                if (acao == "ver") return Mural.paraJson(Mural.ler(app)).toString()
                val l = Mural.lista(a.optString("lista").ifEmpty { "compras" }) ?: erro("lista: compras ou recados")
                Mural.paraJson(when (acao) {
                    "adicionar" -> Mural.editar(app) { Mural.adicionar(it, l, a.optString("texto")) }
                    "remover" -> Mural.editar(app) { Mural.remover(it, l, a.optString("texto")) }
                    "limpar" -> Mural.editar(app) { Mural.limparLista(it, l) }
                    else -> erro("acao: ver, adicionar, remover ou limpar")
                }).put("mural_no_protetor", Extras.ligado(app, Extras.Chave.MURAL)).toString()
            }
            "buscar_tv" -> buscar(app, a.optString("termo")).toString()
            "semana_tv" -> JSONObject()
                .put("semana", Retrospectiva.ultima(app)?.let { Retrospectiva.paraJson(it) } ?: JSONObject.NULL)
                .put("desempenho", DesempenhoTv.json(app)).toString()
            else -> erro("ferramenta desconhecida")
        }
    }

    private fun buscar(app: Context, termo: String): JSONObject {
        if (BuscaUnica.palavras(termo).isEmpty()) erro("termo vazio")
        val parental = PlayerArmazem.parental(app)
        val canais = runCatching { PlayerCarga.canaisDoCache(app) }.getOrDefault(emptyList()).let { t ->
            val oc = PlayerFiltro.ocultasEfetivas(t, PlayerArmazem.gruposEscondidos(app), PlayerArmazem.gruposLiberados(app))
            t.filter { PlayerFiltro.podeIrParaHome(it, oc, parental) }
        }
        fun <T> semOcultas(l: List<T>, grupo: (T) -> String) = VodFiltro.visiveis(l, VodFiltro.ocultas(l.map(grupo),
            PlayerArmazem.gruposEscondidos(app), PlayerArmazem.gruposLiberados(app), parental), grupo)
        val filmes = semOcultas(runCatching { PlayerCarga.filmes(app, soCache = true) }.getOrDefault(emptyList())) { it.grupo }
        val series = semOcultas(runCatching { PlayerCarga.series(app, soCache = true) }.getOrDefault(emptyList())) { it.grupo }
        val apps = ModoSimples.instalados(app).entries.map { it.key to it.value }
        return JSONObject()
            .put("canais", JSONArray().apply { BuscaUnica.filtrar(canais, termo) { it.nome }.forEach { put(JSONObject().put("nome", it.nome).put("numero", it.numero)) } })
            .put("filmes", JSONArray().apply { BuscaUnica.filtrar(filmes, termo) { it.nome }.forEach { put(JSONObject().put("nome", it.nome).put("ano", it.ano ?: JSONObject.NULL)) } })
            .put("series", JSONArray().apply { BuscaUnica.filtrar(series, termo) { it.nome }.forEach { put(JSONObject().put("nome", it.nome).put("ano", it.ano ?: JSONObject.NULL)) } })
            .put("apps", JSONArray().apply { BuscaUnica.filtrar(apps, termo) { it.second }.forEach { put(JSONObject().put("nome", it.second).put("pacote", it.first)) } })
    }
}

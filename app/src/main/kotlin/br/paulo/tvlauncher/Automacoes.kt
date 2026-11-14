package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Um motor só: "quando X, faça Y" (14/11/2026, ideia 1, o fecho das ideias 2 a 7). Junta numa lista só, com um
 * modelo só ([Regra]), tudo o que a TV faz sozinha:
 *  - quando for hora ([Rotinas]: ligar, desligar, abrir app, rodar cena);
 *  - quando apertar um botão ([RemapTeclas]: toque, segurar, toque duplo → app ou cena);
 *  - quando algo acontecer ([Gatilhos]: filme começou/pausou, TV ligou, todos saíram, chuva → cena);
 *  - por link ([Cenas]: cada cena tem o seu).
 *
 * Decisão de armazenamento: cada tipo CONTINUA na chave em que já estava (rotinas em "rotinas", botões em
 * "launcher"/remap e "launcher"/botao_*, eventos em "launcher"/gatilhos, cenas em "cenas"), todas já no backup
 * completo. Nada é migrado na TV: uma migração de dados nas TVs instaladas podia perder as rotinas da Sala (os
 * alarmes delas são agendados pelo id) e não trazia nada para quem usa. O modelo único mora aqui, na leitura: a tela
 * Automações (AutomacoesActivity), o MCP e o painel leem [listar]/[json], e cada edição vai ao dono de cada tipo.
 */
object Automacoes {
    sealed class Quando {
        data class Horario(val hora: Int, val minuto: Int, val dias: Int) : Quando()
        data class Botao(val tecla: Int, val gesto: RemapTeclas.Gesto) : Quando()
        data class Evento(val evento: Gatilhos.Evento) : Quando()
        data class Link(val cena: String) : Quando()
    }

    /**
     * [faz]: "ligar", "desligar", "app:<pacote>" ou "cena:<nome>" (o mesmo formato dos botões). [ref] diz onde a
     * regra mora, para abrir o editor certo: "rotina:<id>", "tecla:<código>", "evento:<chave>", "link:<cena>".
     */
    data class Regra(val quando: Quando, val faz: String, val ativa: Boolean, val ref: String)

    /** Pura (AutomacoesTest): junta os quatro tipos, na ordem horário, botão, evento, link. */
    fun montar(rotinas: List<Rotinas.Rotina>, remap: Map<Int, Map<RemapTeclas.Gesto, String>>,
               gatilhos: Map<Gatilhos.Evento, String>, cenas: List<String>): List<Regra> {
        val r = ArrayList<Regra>()
        for (x in rotinas) r += Regra(Quando.Horario(x.hora, x.minuto, x.dias), when (x.acao) {
            Rotinas.Acao.LIGAR -> "ligar"
            Rotinas.Acao.DESLIGAR -> "desligar"
            Rotinas.Acao.ABRIR -> "app:${x.pacote}"
            Rotinas.Acao.CENA -> "cena:${x.cena}"
        }, x.ativa, "rotina:${x.id}")
        for ((tecla, g) in remap.toSortedMap()) for (gesto in RemapTeclas.Gesto.values()) g[gesto]?.let {
            r += Regra(Quando.Botao(tecla, gesto), it, true, "tecla:$tecla")
        }
        for (e in Gatilhos.Evento.values()) gatilhos[e]?.let { r += Regra(Quando.Evento(e), "cena:$it", true, "evento:${e.chave}") }
        for (c in cenas) r += Regra(Quando.Link(c), "cena:$c", true, "link:$c")
        return r
    }

    fun listar(ctx: Context): List<Regra> =
        montar(Rotinas.listar(ctx), RemapTeclas.todas(ctx), Gatilhos.todos(ctx), Cenas.lista(ctx).map { it.nome })

    /** As regras em JSON (MCP, painel): {"quando": {...}, "faz": "...", "ativa": true}. Pura. */
    fun json(regras: List<Regra>): JSONArray = JSONArray().apply {
        for (r in regras) put(JSONObject().put("faz", r.faz).put("ativa", r.ativa).put("quando", when (val q = r.quando) {
            is Quando.Horario -> JSONObject().put("tipo", "horario").put("hora", "%02d:%02d".format(q.hora, q.minuto)).put("dias", q.dias)
            is Quando.Botao -> JSONObject().put("tipo", "botao").put("tecla", q.tecla).put("gesto", q.gesto.chave)
            is Quando.Evento -> JSONObject().put("tipo", "evento").put("evento", q.evento.chave)
            is Quando.Link -> JSONObject().put("tipo", "link")
        }))
    }
}

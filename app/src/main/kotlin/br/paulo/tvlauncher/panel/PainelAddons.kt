package br.paulo.tvlauncher.panel

import android.content.Context
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.player.AddonsProtocolo
import br.paulo.tvlauncher.player.AddonsTv
import br.paulo.tvlauncher.player.RecommendedAddons
import org.json.JSONArray
import org.json.JSONObject

/**
 * Addons no painel web (16/11/2026; [AddonsTv]). Instalar e remover pedem o PIN de novo (mudam o que a TV mostra e de
 * onde ela busca vídeo). Instalar também exige o aceite dos termos: quem instala responde pelo conteúdo do addon.
 *
 *   GET  /api/addons            lista + se os termos já foram aceitos
 *   POST /api/addons/instalar   {url, aceite}  (PIN)
 *   POST /api/addons/remover    {base}         (PIN)
 *
 * Recusa volta com `motivo` (P2P, ADULTO, PRECISA_CONFIGURAR, SEM_RECURSO_UTIL, INVALIDO, ENDERECO, CHEIO, SEM_REDE,
 * SEM_ACEITE): o painel traduz.
 */
object PainelAddons {
    private fun ok(o: JSONObject = JSONObject()) = 200 to o.put("ok", true)
    private fun erro(codigo: Int, msg: String, motivo: String) = codigo to JSONObject().put("erro", msg).put("motivo", motivo)
    private fun pin() = 403 to JSONObject().put("erro", "confirme o PIN").put("precisaPin", true)

    // O registro do painel chega ao Diário e à ferramenta MCP diario_tv: nada do addon vai para lá (nome, servidor e IP
    // são de terceiro ou da rede de casa). Só o evento. Code scanning do PR #176.

    fun estado(app: Context): JSONObject = JSONObject()
        .put("aceite", AddonsTv.aceitou(app))
        .put("max", AddonsProtocolo.MAX_ADDONS)
        .put("addons", JSONArray().apply {
            AddonsTv.instalados(app).forEach { a ->
                put(JSONObject().put("base", a.base).put("nome", a.m.nome).put("versao", a.m.versao).put("descricao", a.m.descricao)
                    .put("recursos", JSONArray(a.m.recursos.map { it.nome }.distinct()))
                    .put("catalogos", a.m.catalogos.size).put("grupo", AddonsTv.grupoDe(a)))
            }
        })
        // Lista curada (18/11/2026): o que ainda não está instalado, com o texto no idioma da TV.
        .put("sugeridos", JSONArray().apply {
            AddonsTv.sugeridos(app).forEach { s ->
                put(JSONObject().put("id", s.e.id).put("nome", s.nome).put("texto", s.texto).put("url", s.e.url).put("grupo", s.e.group))
            }
        })
        // Filtro por tipo no painel: as seções na ordem certa, com o nome no idioma da TV ("" = Todos).
        .put("grupos", JSONArray().apply {
            put(JSONObject().put("id", "").put("nome", app.getString(R.string.ad_grupo_todos)))
            RecommendedAddons.GROUPS.forEach { g -> put(JSONObject().put("id", g).put("nome", AddonsTv.nomeGrupo(app, g))) }
        })

    fun rotear(app: Context, metodo: String, rota: String, corpo: JSONObject?, elevada: Boolean, ip: String): Pair<Int, JSONObject>? {
        val get = metodo == "GET"; val post = metodo == "POST"
        return when {
            get && rota == "" -> ok(estado(app))
            post && rota == "/instalar" -> {
                if (!elevada) return pin()
                val url = corpo?.optString("url").orEmpty()
                if (corpo?.optBoolean("aceite") == true) AddonsTv.aceitar(app)
                when (val r = AddonsTv.instalar(app, url)) {
                    is AddonsTv.Resultado.Ok -> {
                        PainelArmazem.registrar(app, ip, "addon instalado", "ok")
                        ok(estado(app).put("instalado", r.i.m.nome))
                    }
                    is AddonsTv.Resultado.Recusado -> erro(422, "addon recusado", r.motivo.name)
                    AddonsTv.Resultado.Cheio -> erro(422, "limite de addons", "CHEIO")
                    AddonsTv.Resultado.SemRede -> erro(502, "o addon não respondeu", "SEM_REDE")
                    AddonsTv.Resultado.SemAceite -> erro(409, "aceite os termos", "SEM_ACEITE")
                }
            }
            post && rota == "/remover" -> {
                if (!elevada) return pin()
                val base = corpo?.optString("base").orEmpty()
                val a = AddonsTv.instalados(app).firstOrNull { it.base == base } ?: return erro(404, "addon não instalado", "INVALIDO")
                AddonsTv.remover(app, base)
                PainelArmazem.registrar(app, ip, "addon removido", "ok")
                ok(estado(app))
            }
            else -> null
        }
    }
}

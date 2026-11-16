package br.paulo.tvlauncher

import android.content.Context
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

    /**
     * O que vai ao registro do painel, que também chega ao Diário e à ferramenta MCP diario_tv: só o servidor do
     * endereço do addon, com letras, números, ponto e hífen (até 60). O nome livre do manifesto é texto de terceiro e
     * poderia trazer instrução para um modelo que leia o diário; por isso não vai ao registro (code scanning, PR #176).
     */
    fun rotuloParaRegistro(base: String): String =
        base.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == ':' }.take(60).ifEmpty { "?" }

    fun estado(app: Context): JSONObject = JSONObject()
        .put("aceite", AddonsTv.aceitou(app))
        .put("max", AddonsProtocolo.MAX_ADDONS)
        .put("addons", JSONArray().apply {
            AddonsTv.instalados(app).forEach { a ->
                put(JSONObject().put("base", a.base).put("nome", a.m.nome).put("versao", a.m.versao).put("descricao", a.m.descricao)
                    .put("recursos", JSONArray(a.m.recursos.map { it.nome }.distinct()))
                    .put("catalogos", a.m.catalogos.size))
            }
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
                        PainelArmazem.registrar(app, ip, "addon instalado: ${rotuloParaRegistro(r.i.base)}", "ok")
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
                PainelArmazem.registrar(app, ip, "addon removido: ${rotuloParaRegistro(a.base)}", "ok")
                ok(estado(app))
            }
            else -> null
        }
    }
}

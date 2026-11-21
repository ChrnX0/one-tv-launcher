package br.paulo.tvlauncher.panel

import android.content.Context
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Avisos
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.alarm.NaoPerturbe
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.family.Recado
import br.paulo.tvlauncher.weather.AvisoRegras
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rotas do painel para o "Cuidar de longe" (/api/cuidar...). A foto do pedido de ajuda (/api/cuidar/foto) sai direto
 * no PainelWeb (é imagem, não JSON).
 *
 * O que o painel PODE: escolher quem recebe, quais avisos, prazos, hora, escrever lembretes (com o PIN de novo: é o
 * que decide para quem vão fotos da tela), abrir a pergunta na TV, desligar (sem PIN: desligar nunca pode ser difícil),
 * mandar teste e responder ao pedido de ajuda com um recado na TV.
 * O que o painel NÃO pode: aceitar no lugar de quem mora na casa. Isso só a tecla física na TV (CuidarTelaActivity).
 */
object PainelCuidar {
    private fun ok(ctx: Context) = 200 to CuidarDeLongeTv.json(ctx).put("ok", true)
    private fun erro(codigo: Int, msg: String) = codigo to JSONObject().put("erro", msg)
    private fun pin() = 403 to JSONObject().put("erro", "confirme o PIN").put("precisaPin", true)

    /** [rota]: o que vem depois de "/api/cuidar" ("" ou "/lembrete" ...). Null = não é daqui. */
    fun rotear(app: Context, metodo: String, rota: String, corpo: JSONObject?, elevada: Boolean, ip: String): Pair<Int, JSONObject>? {
        val get = metodo == "GET"; val post = metodo == "POST"
        return when {
            get && rota == "" -> ok(app)
            post && rota == "" -> {
                if (!elevada) return pin()
                val o = corpo ?: return erro(400, "JSON inválido")
                CuidarDeLongeTv.mudar(app) { c -> aplicar(c, o) }
                PainelArmazem.registrar(app, ip, "cuidar de longe: configuração", "ok")
                ok(app)
            }
            post && rota == "/lembrete" -> {
                if (!elevada) return pin()
                val o = corpo ?: return erro(400, "JSON inválido")
                when (o.optString("acao")) {
                    "remover" -> CuidarDeLongeTv.removerLembrete(app, o.optInt("id"))
                    "salvar" -> {
                        val antes = CuidarDeLongeTv.config(app).lembretes
                        val l = CuidarDeLonge.Lembrete(o.optInt("id"), o.optInt("hora", -1), o.optInt("minuto", -1),
                            o.optInt("dias", CuidarDeLonge.TODO_DIA), o.optString("texto"))
                        val depois = CuidarDeLonge.salvarLembrete(antes, l)
                        if (depois == antes) return erro(400, if (antes.size >= CuidarDeLonge.MAX_LEMBRETES) "já há ${CuidarDeLonge.MAX_LEMBRETES} lembretes"
                            else "confira a hora e o texto")
                        CuidarDeLongeTv.mudar(app) { it.copy(lembretes = depois) }
                    }
                    else -> return erro(400, "ação desconhecida")
                }
                PainelArmazem.registrar(app, ip, "cuidar de longe: lembretes", "ok")
                ok(app)
            }
            post && rota == "/perguntar" -> {
                if (!elevada) return pin()
                when (CuidarDeLongeTv.pedirConsentimento(app)) {
                    CuidarDeLonge.Falta.NOME -> erro(409, "falta o nome de quem recebe")
                    CuidarDeLonge.Falta.APARELHO -> erro(409, "escolha pelo menos um celular")
                    CuidarDeLonge.Falta.AVISO -> erro(409, "escolha pelo menos um aviso")
                    null -> { PainelArmazem.registrar(app, ip, "cuidar de longe: pergunta aberta na TV", "ok"); ok(app) }
                }
            }
            post && rota == "/desligar" -> { CuidarDeLongeTv.retirar(app, ip); ok(app) }
            post && rota == "/testar" -> {
                val (aceitos, total) = CuidarDeLongeTv.testar(app)
                if (total == 0) erro(409, "nenhum celular escolhido") else ok(app).also { it.second.put("teste", "$aceitos de $total receberam") }
            }
            post && rota == "/responder" -> {
                val texto = Recado.limpar(corpo?.optString("texto")) ?: return erro(400, "recado vazio ou longo demais (até ${Recado.MAX_CARACTERES} letras)")
                val c = CuidarDeLongeTv.config(app)
                AvisoTv.mostrar(app, c.nome.ifEmpty { app.getString(R.string.cuidar_titulo) }, texto, 300, som = true, guardarSeApagada = true,
                    fica = AvisoRegras.Fica.ATENCAO, prioridade = NaoPerturbe.Tipo.ALERTA_OFICIAL)   // quem cuida de longe respondeu: passa
                PainelArmazem.registrar(app, ip, "cuidar de longe: resposta na TV", "ok")
                ok(app)
            }
            else -> null
        }
    }

    /** Só os campos que vieram; cada um limpo pelas regras puras. */
    private fun aplicar(c: CuidarDeLonge.Config, o: JSONObject): CuidarDeLonge.Config {
        var n = c
        if (o.has("nome")) n = n.copy(nome = CuidarDeLonge.limparNome(o.optString("nome")))
        if (o.has("aparelhos")) n = n.copy(aparelhos = lista(o.optJSONArray("aparelhos")).filter { CuidarDeLonge.idValido(it) }.distinct().take(Avisos.MAX_INSCRICOES))
        if (o.has("avisos")) n = n.copy(avisos = lista(o.optJSONArray("avisos")).mapNotNull { s -> CuidarDeLonge.Aviso.values().firstOrNull { it.name == s } }.toSet())
        if (o.has("tecla")) CuidarDeLonge.Tecla.values().firstOrNull { it.name == o.optString("tecla") }?.let { n = n.copy(tecla = it) }
        if (o.has("prazo")) n = n.copy(prazoMin = CuidarDeLonge.prazoValido(o.optInt("prazo")))
        if (o.has("margem")) n = n.copy(margemMin = CuidarDeLonge.margemValida(o.optInt("margem")))
        if (o.has("horaFixa")) n = n.copy(horaFixa = o.optInt("horaFixa", -1).let { if (it in 0 until 24 * 60) it else -1 })
        if (o.has("acender")) n = n.copy(acender = o.optBoolean("acender", true))
        return n
    }

    private fun lista(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
}

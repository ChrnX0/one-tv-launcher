package br.paulo.tvlauncher

import android.content.Context
import br.paulo.tvlauncher.ServicosFilmes.Servico
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serviços de filmes e séries no painel web (aba Configurações), com as mesmas ações da tela da TV
 * (ServicosFilmesActivity): entrar no Trakt/Simkl pelo código (o painel mostra o código e um link que abre a página do
 * serviço já com ele), colar a chave do MDBList, usuário e senha do OpenSubtitles, sair. Feito para quem está no celular,
 * inclusive fora de casa pelo Tailscale. Entrar e sair pedem o PIN de novo (decidem para onde vão os dados da casa).
 * Nenhuma chave, senha ou nome de conta vai para o registro de acessos.
 */
object PainelServicos {
    sealed class Resultado {
        class Ok(val estado: JSONObject) : Resultado()
        object PrecisaPin : Resultado()
        class Erro(val codigo: Int, val mensagem: String) : Resultado()
    }

    private val FRASES = mapOf(
        Servico.TRAKT to "Deixa o que você viu e a sua lista iguais aos do celular.",
        Servico.SIMKL to "Igual ao Trakt, ótimo também para anime. Use um ou os dois.",
        Servico.MDBLIST to "Opcional: notas do Rotten Tomatoes, Letterboxd e Metacritic, e listas como o Top 250.",
        Servico.TMDB to "Sinopse, elenco e data do próximo episódio, no idioma da TV.",
        Servico.OPENSUBTITLES to "Legendas da internet. Sem conta: 5 por dia. Com conta grátis: 20.",
    )

    fun estado(app: Context): JSONObject {
        val lista = JSONArray()
        for (e in ServicosContas.estados(app)) {
            if (e.situacao == ServicosContas.Situacao.INDISPONIVEL) continue
            val o = JSONObject().put("id", e.servico.id).put("nome", e.servico.nome).put("frase", FRASES[e.servico])
                .put("situacao", e.situacao.name.lowercase()).put("usuario", e.usuario)
                .put("login", when (e.servico) {
                    Servico.TRAKT, Servico.SIMKL -> "codigo"
                    Servico.MDBLIST -> "chave"
                    Servico.OPENSUBTITLES -> "senha"
                    Servico.TMDB -> "nenhum"
                })
            ServicosContas.sessao(e.servico)?.let { s ->
                val l = JSONObject().put("estado", s.estado.name.lowercase())
                when (s.estado) {
                    ServicosContas.EstadoLogin.ESPERANDO -> l.put("codigo", s.codigo.codigoUsuario).put("url", s.codigo.url)
                        .put("urlDireta", s.codigo.urlQr).put("restaMs", (s.expira - System.currentTimeMillis()).coerceAtLeast(0))
                    ServicosContas.EstadoLogin.OK -> l.put("usuario", s.usuario)
                    ServicosContas.EstadoLogin.ERRO -> l.put("motivo", motivo(s.motivo))
                }
                o.put("sessao", l)
            }
            lista.put(o)
        }
        return JSONObject().put("servicos", lista).put("semChaves", lista.length() == 0)
            .put("atribuicaoTmdb", "This product uses the TMDB API but is not endorsed or certified by TMDB.")
    }

    private fun motivo(m: ServicosFilmes.Motivo?) = when (m) {
        ServicosFilmes.Motivo.RECUSADO -> "o acesso foi recusado no celular"
        ServicosFilmes.Motivo.USADO -> "este código já foi usado; peça outro"
        ServicosFilmes.Motivo.ERRO -> "o serviço não respondeu; tente de novo daqui a pouco"
        else -> "o código venceu; peça outro"
    }

    /** POST /api/servicos: {acao: entrar|cancelar|sair|chave|senha, servico, chave?, usuario?, senha?}. Bloqueia (rede). */
    fun agir(app: Context, o: JSONObject, elevada: Boolean, registrar: (String, String) -> Unit): Resultado {
        val s = Servico.de(o.optString("servico")) ?: return Resultado.Erro(400, "serviço desconhecido")
        val acao = o.optString("acao")
        if (acao != "cancelar" && !elevada) return Resultado.PrecisaPin
        if (s != Servico.OPENSUBTITLES && !ServicosContas.disponivel(s)) return Resultado.Erro(400, "esta versão do launcher não tem a chave do ${s.nome}")
        when (acao) {
            "entrar" -> {
                if (s != Servico.TRAKT && s != Servico.SIMKL) return Resultado.Erro(400, "o ${s.nome} não entra por código")
                runCatching { ServicosContas.entrar(app, s) }.onFailure {
                    registrar("${s.nome}: entrar", "falhou: ${it.javaClass.simpleName}")
                    return Resultado.Erro(502, it.message ?: "o ${s.nome} não respondeu")
                }
                registrar("${s.nome}: entrar", "código pedido")
            }
            "cancelar" -> ServicosContas.cancelar(s)
            "sair" -> { ServicosContas.sair(app, s); registrar("${s.nome}: sair", "ok") }
            "chave" -> {
                if (s != Servico.MDBLIST) return Resultado.Erro(400, "só o MDBList usa chave pessoal")
                ServicosContas.definirMdblist(app, o.optString("chave")).onFailure {
                    registrar("MDBList: chave", "recusada"); return Resultado.Erro(400, it.message ?: "chave recusada")
                }
                registrar("MDBList: chave", "ok")
            }
            "senha" -> {
                if (s != Servico.OPENSUBTITLES) return Resultado.Erro(400, "só o OpenSubtitles usa usuário e senha")
                ServicosContas.definirOpenSubtitles(app, o.optString("usuario"), o.optString("senha")).onFailure {
                    registrar("OpenSubtitles: conta", "recusada"); return Resultado.Erro(400, it.message ?: "conta recusada")
                }
                registrar("OpenSubtitles: conta", "ok")
            }
            else -> return Resultado.Erro(400, "ação desconhecida")
        }
        return Resultado.Ok(estado(app))
    }
}

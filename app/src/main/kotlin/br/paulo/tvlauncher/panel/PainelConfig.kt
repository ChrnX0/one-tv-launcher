package br.paulo.tvlauncher.panel

import android.content.Context
import br.paulo.tvlauncher.ConfirmacoesTv
import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.PinFamilia
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.TaxaQuadros
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.settings.TamanhoTexto
import br.paulo.tvlauncher.settings.Tema
import br.paulo.tvlauncher.weather.AvisoChuva
import br.paulo.tvlauncher.weather.AvisoTemperatura
import org.json.JSONObject

/**
 * Aba "Configurações" do painel web (14/11/2026, pedido do dono: "o painel web e as configurações do launcher têm que
 * se conversar"). As mesmas opções das Configurações da TV que antes só existiam lá, gravadas pelos MESMOS objetos que a
 * tela da TV usa (Tema, TamanhoTexto, ControleParental, ConfirmacoesTv...): mudar de um lado aparece do outro.
 *
 * O PIN segue a regra da TV: o que lá pede o PIN (controle parental, modo infantil, PIN das crianças, liberar mais nas
 * Confirmações) aqui pede o PIN de novo (sessão elevada). Trocar o PIN do painel também. Nenhum PIN vai para o registro.
 */
object PainelConfig {
    /** Tempos do protetor de tela, os mesmos da linha da TV (Int.MAX_VALUE = nunca). */
    val TEMPOS_PROTETOR = listOf(60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, Int.MAX_VALUE)

    sealed class Resultado {
        class Ok(val estado: JSONObject) : Resultado()
        object PrecisaPin : Resultado()
        class Erro(val mensagem: String) : Resultado()
    }

    fun estado(app: Context): JSONObject {
        val par = PlayerArmazem.parental(app)
        val conf = ConfirmacoesTv.regras(app)
        return JSONObject()
            .put("tema", Tema.atual(app).name.lowercase())
            .put("temas", org.json.JSONArray().apply { for (t in Tema.values()) put(JSONObject().put("valor", t.name.lowercase()).put("nome", t.nome)) })
            .put("tamanhoTexto", TamanhoTexto.atual(app))
            .put("altoContraste", AltoContraste.ligado(app))
            .put("avisoChuva", AvisoChuva.ligado(app))
            .put("taxaQuadros", TaxaQuadros.ligada(app))
            .put("avisoTemperatura", AvisoTemperatura.ligado(app))
            .put("audio", if (IdiomaPlayer.prefs(app).padrao == Idioma.Pref.ORIGINAL) "original" else "dublado")
            .put("protetorMs", PrivilegioDO.tempoDescanso(app).toLong())
            .put("barraProtetor", Protetor.widget(app))
            .put("parental", par.ligado)
            .put("parentalCanais", par.canais.size + par.grupos.size)
            .put("infantil", par.infantil)
            .put("infantis", par.infantis.size)
            .put("idadeInfantil", par.idadeMax)
            .put("pinFamilia", PainelArmazem.temPinFamilia(app))
            .put("visita", ModoVisita.ativo(app))
            .put("atalhos", org.json.JSONArray(PanelShortcuts.read(app)))
            .put("unidadeRede", PainelArmazem.unidadeRede(app))
            .put("unidadeRedeEndereco", PainelWeb.enderecoUnidade(app) ?: org.json.JSONObject.NULL)
            .put("mtpPermitidos", org.json.JSONArray(MtpNaRede.permitidos(app)))
            .put("confirmacoes", JSONObject().put("liberarTudo", conf.liberarTudo).put("acoes", org.json.JSONArray().apply {
                for (a in Confirmacoes.Acao.entries) put(JSONObject().put("chave", a.chave).put("titulo", a.titulo)
                    .put("explicacao", a.explicacao).put("modo", Confirmacoes.modo(conf.copy(liberarTudo = false), a).chave)
                    .put("modos", org.json.JSONArray().apply { for (m in a.modos) put(JSONObject().put("valor", m.chave).put("nome", m.rotulo)) }))
            }))
    }

    /**
     * Muda UM campo por pedido (o painel grava cada controle na hora). [elevada] = PIN conferido há pouco.
     * [registrar] recebe o que mudou, para o registro de acessos (nunca o PIN).
     */
    fun mudar(app: Context, o: JSONObject, elevada: Boolean, registrar: (String) -> Unit): Resultado {
        val campo = o.optString("campo")
        val v = o.opt("valor")
        when (campo) {
            "tema" -> {
                val t = Tema.values().firstOrNull { it.name.equals(v as? String, ignoreCase = true) } ?: return Resultado.Erro("tema desconhecido")
                Tema.definir(app, t); registrar("tema: ${t.name.lowercase()}")
            }
            "tamanhoTexto" -> {
                val n = (v as? Number)?.toInt()?.takeIf { it in TamanhoTexto.OPCOES } ?: return Resultado.Erro("tamanho inválido")
                TamanhoTexto.definir(app, n); registrar("tamanho do texto: $n%")
            }
            // Estudo global 20/11/2026 (ideia 3): uma chave só de alto contraste; o painel e o celular seguem a da TV.
            "altoContraste" -> { val b = v as? Boolean ?: return Resultado.Erro("valor inválido"); AltoContraste.definir(app, b); registrar("alto contraste: $b") }
            "avisoChuva" -> { val b = v as? Boolean ?: return Resultado.Erro("valor inválido"); AvisoChuva.definirLigado(app, b); registrar("aviso de chuva: $b") }
            "taxaQuadros" -> { val b = v as? Boolean ?: return Resultado.Erro("valor inválido"); TaxaQuadros.definir(app, b); registrar("taxa de quadros do filme: $b") }
            "avisoTemperatura" -> { val b = v as? Boolean ?: return Resultado.Erro("valor inválido"); AvisoTemperatura.definirLigado(app, b); registrar("aviso de calor/frio: $b") }
            "audio" -> {
                val p = when (v) { "original" -> Idioma.Pref.ORIGINAL; "dublado" -> Idioma.Pref.DUBLADO; else -> return Resultado.Erro("valor inválido") }
                IdiomaPlayer.definirPadrao(app, p); registrar("áudio preferido: $v")
            }
            "protetorMs" -> {
                val ms = (v as? Number)?.toLong()?.takeIf { l -> TEMPOS_PROTETOR.any { it.toLong() == l } }?.toInt() ?: return Resultado.Erro("tempo inválido")
                if (!PrivilegioDO.definirTempoDescanso(app, ms)) return Resultado.Erro("não consegui gravar (Device Owner inativo?)")
                registrar("protetor de tela: " + if (ms == Int.MAX_VALUE) "nunca" else "${ms / 60_000} min")
            }
            "barraProtetor" -> {
                if (v !in Protetor.WIDGETS) return Resultado.Erro("valor inválido")
                Protetor.definirWidget(app, v as String); registrar("widgets do protetor: $v")
            }
            // A TV como unidade de rede (PainelDav): mesma senha (o PIN) e mesmas pastas da aba Arquivos. Ligar abre os
            // arquivos da TV para a rede: pede o PIN de novo (15/11/2026), como o MCP; desligar não pede.
            "unidadeRede" -> {
                val b = v as? Boolean ?: return Resultado.Erro("valor inválido")
                if (b && !elevada) return Resultado.PrecisaPin
                PainelArmazem.definirUnidadeRede(app, b); PainelWeb.unidadeRedeMudou(app); registrar("unidade de rede: $b")
            }
            // Esquecer os computadores aceitos na TV para o MTP pela rede: tirar acesso nunca pede PIN.
            "mtpEsquecer" -> { MtpNaRede.esquecerTodos(app); registrar("computadores permitidos: esquecidos") }
            "atalhos" -> {
                val l = PanelShortcuts.normalize(v) ?: return Resultado.Erro("valor inválido")
                PanelShortcuts.write(app, l); registrar("atalhos do painel: ${l.size}")
            }
            "visita" -> { val b = v as? Boolean ?: return Resultado.Erro("valor inválido"); ModoVisita.definir(app, b); registrar("modo visita: $b") }
            // Controle parental e modo infantil: na TV, ligar e desligar pedem o PIN; aqui também.
            "parental", "infantil" -> {
                val b = v as? Boolean ?: return Resultado.Erro("valor inválido")
                if (!elevada) return Resultado.PrecisaPin
                val atual = PlayerArmazem.parental(app)
                val nova = if (campo == "parental") atual.copy(ligado = b) else atual.copy(infantil = b)
                if (!PlayerArmazem.definirParental(app, nova)) return Resultado.Erro("não consegui gravar; tente de novo")
                registrar((if (campo == "parental") "controle parental: " else "modo infantil: ") + b)
            }
            // Ideia 91: idade máxima no modo infantil (Classificacao.OPCOES; -1 = só as categorias infantis). Pede o PIN.
            "idadeInfantil" -> {
                val n = (v as? Number)?.toInt()?.takeIf { it in Classificacao.OPCOES } ?: return Resultado.Erro("valor inválido")
                if (!elevada) return Resultado.PrecisaPin
                if (!PlayerArmazem.definirParental(app, PlayerArmazem.parental(app).copy(idadeMax = n))) return Resultado.Erro("não consegui gravar; tente de novo")
                registrar("idade máxima no modo infantil: $n")
            }
            // PIN das crianças: "" apaga. Igual ao do painel não vale (PinFamilia.conferirNovo).
            "pinFamilia" -> {
                val pin = v as? String ?: return Resultado.Erro("valor inválido")
                if (!elevada) return Resultado.PrecisaPin
                if (pin.isEmpty()) { PainelArmazem.apagarPinFamilia(app); registrar("PIN das crianças: apagado") }
                else when (PinFamilia.conferirNovo(pin, PinSeguro.valido(pin) && PainelArmazem.conferirPin(app, pin))) {
                    PinFamilia.Novo.INVALIDO -> return Resultado.Erro("o PIN precisa ter de ${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} números")
                    PinFamilia.Novo.IGUAL_AO_PAINEL -> return Resultado.Erro("o PIN das crianças precisa ser diferente do PIN do painel")
                    PinFamilia.Novo.OK -> { PainelArmazem.definirPinFamilia(app, pin); registrar("PIN das crianças: trocado") }
                }
            }
            // PIN do painel (o mesmo das Configurações da TV). Igual ao das crianças não vale: elas ganhariam o do painel.
            "pinPainel" -> {
                val pin = v as? String ?: return Resultado.Erro("valor inválido")
                if (!elevada) return Resultado.PrecisaPin
                if (!PinSeguro.valido(pin)) return Resultado.Erro("o PIN precisa ter de ${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} números")
                if (PainelArmazem.temPinFamilia(app) && PainelArmazem.conferirPinFamilia(app, pin))
                    return Resultado.Erro("o PIN do painel precisa ser diferente do PIN das crianças")
                PainelArmazem.definirPin(app, pin); registrar("PIN do painel: trocado")
            }
            // Confirmações: liberar mais pede o PIN; ficar mais seguro, não (Confirmacoes.pedePin, a mesma regra da TV).
            "liberarTudo", "confirmacao" -> {
                val antes = ConfirmacoesTv.regras(app)
                val novas = if (campo == "liberarTudo") antes.copy(liberarTudo = v as? Boolean ?: return Resultado.Erro("valor inválido"))
                else {
                    val acao = Confirmacoes.Acao.entries.firstOrNull { it.chave == o.optString("acao") } ?: return Resultado.Erro("ação desconhecida")
                    val modo = acao.modos.firstOrNull { it.chave == v } ?: return Resultado.Erro("modo inválido")
                    antes.copy(modos = antes.modos + (acao to modo))
                }
                if (Confirmacoes.pedePin(antes, novas) && !elevada) return Resultado.PrecisaPin
                ConfirmacoesTv.definir(app, novas)
                registrar(if (campo == "liberarTudo") "confirmações: liberar tudo ${novas.liberarTudo}" else "confirmação ${o.optString("acao")}: $v")
            }
            else -> return Resultado.Erro("opção desconhecida")
        }
        return Resultado.Ok(estado(app))
    }
}

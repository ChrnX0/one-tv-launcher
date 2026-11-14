package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * O CATÁLOGO FECHADO do que uma IA de fora pode pedir ao launcher, e a conferência de cada pedido. Nada de Android
 * aqui: é só texto e JSON, para rodar nos testes do computador.
 *
 * Histórico: nasceu para a IA na nuvem com a chave do dono (bloco 6, 05/11/2026). Essa parte saiu no mesmo dia
 * (decisão do dono: o Gemini do controle já faz o mesmo); o catálogo e a confirmação na TV ficaram para servir de
 * base ao servidor MCP (uma IA de fora, como o Claude, chamando ferramentas da TV).
 *
 * Por que um catálogo e não "a IA manda um comando": o que vem de fora é tratado como NÃO CONFIÁVEL. A IA só
 * consegue escolher um destes tipos, com argumentos conferidos aqui (pacote tem de estar na lista de apps que a TV
 * mandou, hora de 0 a 23 etc.). Não existe tipo para shell, desinstalar, permissão, DNS, VPN ou arquivo. O OK na TV
 * (ConfirmarIaActivity) só é pedido com a linha "Ações pedidas pela IA" em "Perguntar" (Confirmações, 09/11/2026).
 */
object ComandosIa {
    const val MAX_ACOES = 5
    const val MAX_FRASE = 300

    val TELAS = mapOf(
        "previsao" to "a previsão do tempo",
        "agenda" to "a agenda",
        "despertador" to "o despertador",
        "configuracoes" to "as Configurações do launcher",
        "inicio" to "a tela inicial",
    )
    val VOLUME = mapOf("subir" to "Aumentar o volume", "descer" to "Diminuir o volume", "mudo" to "Ligar/desligar o mudo")
    val MIDIA = mapOf("pausar" to "Pausar", "tocar" to "Continuar tocando", "proxima" to "Próximo", "anterior" to "Anterior")
    /** Casa (11/11/2026, ideia grande 1): o mesmo catálogo fechado do [CasaHA.servico] (luz, tomada, cortina, cena...). */
    val CASA = mapOf("ligar" to "Ligar", "desligar" to "Desligar", "alternar" to "Alternar")
    private val NOMES_DIAS = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")
    const val MAX_TIMER_MIN = 240
    const val MAX_DESLIGAR_MIN = 480
    const val MAX_TITULO = 80

    /**
     * Os tipos do catálogo, na ordem do esquema. Ideia 2 (14/11/2026): recado, timer, desligar_em, filtro_noturno,
     * rodar_cena e mural. Ideia 60: tocar_titulo, continuar, proximo_episodio e o_que_estava_vendo (Filmes e Séries).
     */
    val TIPOS = listOf("abrir_app", "abrir_tela", "despertador", "volume", "midia", "modo_visita", "tocar_canal", "o_que_passa", "casa",
        "recado", "timer", "desligar_em", "filtro_noturno", "rodar_cena", "mural",
        "tocar_titulo", "continuar", "proximo_episodio", "o_que_estava_vendo")

    /**
     * Texto visível das ações novas (strings_acoes.xml em values e values-pt, no idioma da TV). Nos testes do computador não há
     * Context: devolve "#id", e os testes conferem o tipo da ação, não a frase.
     */
    internal fun t(id: Int, vararg a: Any): String = try { txt(id, *a) } catch (e: Throwable) { "#$id" }

    /** Uma ação já conferida. [descricao] é o que a TV mostra antes de executar (em português, para quem está no sofá). */
    sealed class Acao(val descricao: String) {
        class AbrirApp(val pacote: String, nome: String) : Acao("Abrir $nome")
        class AbrirTela(val tela: String) : Acao("Abrir ${TELAS.getValue(tela)}")
        class Despertador(val hora: Int, val minuto: Int, val dias: Int, val nome: String) :
            Acao("Criar despertador às %02d:%02d (%s)".format(hora, minuto, textoDias(dias)) + (if (nome.isNotEmpty()) " \"$nome\"" else ""))
        class Volume(val acao: String, val vezes: Int) : Acao(VOLUME.getValue(acao) + if (acao != "mudo" && vezes > 1) " ($vezes vezes)" else "")
        class Midia(val acao: String) : Acao(MIDIA.getValue(acao))
        class ModoVisita(val ligado: Boolean) : Acao(if (ligado) "Ligar o modo visita" else "Desligar o modo visita")
        // Canais da TV ao vivo (10/11/2026). Saem da conferência só com o que a pessoa disse ([pedido]); o canal de verdade
        // é escolhido depois, na TV, entre os da lista dela (CanalBusca.resolver), respeitando o controle parental.
        class TocarCanal(val pedido: String, val canalId: String = "", val nomeCanal: String = "", val numero: Int = 0) :
            Acao(if (canalId.isEmpty()) "Colocar no canal $pedido" else "Colocar no canal $numero ($nomeCanal)")
        /** [texto] = a resposta do guia ("X agora: ... Depois: ..."), preenchida pelo CanalBusca.resolver. */
        class OQuePassa(val pedido: String, val texto: String = "") : Acao(texto.ifEmpty { "Ver o que passa em $pedido" })
        /** Um aparelho do Home Assistant ([CasaHA]): junta a casa às cenas ("Cinema" apaga a luz e abre o app). */
        class Casa(val entidade: String, val acao: String) : Acao("${CASA.getValue(acao)} ${entidade.substringAfter('.').replace('_', ' ')} (casa)")

        // ---- ideia 2 (14/11/2026): o que já existia na TV por outro caminho (painel, voz), agora no catálogo.
        /** Recado por cima de qualquer tela ([AvisoTv]), como o do painel. [texto] já passou pelo [Recado.limpar]. */
        class Recado(val texto: String, val segundos: Int) : Acao(t(R.string.acao_recado, texto.replace('\n', ' ')))
        /** O timer da cozinha ([br.paulo.tvlauncher.Timer]): um só por vez, um novo substitui o anterior. */
        class Timer(val minutos: Int, val nome: String) :
            Acao(if (nome.isEmpty()) t(R.string.acao_timer, minutos) else t(R.string.acao_timer_nome, minutos, nome))
        /** Timer de sono ([Desligamento]). [minutos] = 0 cancela o que estiver marcado. */
        class DesligarEm(val minutos: Int) :
            Acao(if (minutos == 0) t(R.string.acao_desligar_cancelar) else t(R.string.acao_desligar_em, minutos))
        /** Película âmbar AGORA, até a próxima virada do horário ([br.paulo.tvlauncher.FiltroNoturno.forcar]). */
        class FiltroNoturno(val ligado: Boolean) :
            Acao(t(if (ligado) R.string.acao_filtro_ligar else R.string.acao_filtro_desligar))
        /** Roda outra cena pelo nome. Uma cena não pode conter esta ação ([Cenas.criar] recusa): nada de cena em cadeia. */
        class RodarCena(val cena: String) : Acao(t(R.string.acao_rodar_cena, cena))
        /** Um item no mural do protetor ([Mural]): lista de compras ou recado fixo. */
        class Mural(val lista: br.paulo.tvlauncher.Mural.Lista, val texto: String) :
            Acao(t(if (lista == br.paulo.tvlauncher.Mural.Lista.COMPRAS) R.string.acao_mural_compras else R.string.acao_mural_recado, texto))

        // ---- ideia 60 (14/11/2026): Filmes e Séries. Saem da conferência só com o que a pessoa disse; o título de verdade
        // é escolhido depois, na TV, nas listas dela (VodTv.resolver), só entre as categorias visíveis (as mesmas travas
        // das telas Filmes e Séries: adultas, escondidas e controle parental). [alvo] != null = já resolvida.
        class TocarTitulo(val pedido: String, val alvo: VodBusca.Alvo? = null) :
            Acao(if (alvo == null) t(R.string.acao_tocar_titulo, pedido) else t(R.string.acao_tocar, alvo.titulo))
        class Continuar(val alvo: VodBusca.Alvo? = null) :
            Acao(if (alvo == null) t(R.string.acao_continuar) else t(R.string.acao_tocar, alvo.titulo))
        class ProximoEpisodio(val pedido: String, val alvo: VodBusca.Alvo? = null) : Acao(when {
            alvo != null -> t(R.string.acao_tocar, alvo.titulo)
            pedido.isEmpty() -> t(R.string.acao_proximo)
            else -> t(R.string.acao_proximo_de, pedido)
        })
        /** Só informação: [texto] = os últimos títulos vistos, preenchido pelo VodTv.resolver (volta no ver_pedido). */
        class OQueEstavaVendo(val texto: String = "") : Acao(texto.ifEmpty { t(R.string.acao_o_que_via) })
    }

    /** O resultado da conferência: as ações válidas, a resposta em texto da IA e o que foi recusado (para mostrar). */
    class Plano(val acoes: List<Acao>, val resposta: String, val recusadas: List<String>)

    /** Bit 0 = domingo ... bit 6 = sábado (o mesmo formato do Despertador). 0 = uma vez só. */
    fun textoDias(dias: Int): String = when (dias) {
        0 -> "uma vez"
        0b1111111 -> "todo dia"
        0b0111110 -> "seg a sex"
        0b1000001 -> "fim de semana"
        else -> (0..6).filter { dias and (1 shl it) != 0 }.joinToString(", ") { NOMES_DIAS[it] }
    }

    /**
     * O esquema que vai para o Gemini (responseSchema, subconjunto do OpenAPI): um objeto com a lista de ações e uma
     * resposta curta. Cada ação é plana (todos os campos opcionais menos "tipo"): é o formato que o Gemini segue
     * melhor, e a conferência abaixo decide quais campos valem para cada tipo.
     */
    fun esquema(openApi: Boolean = true): JSONObject {
        // Gemini usa o OpenAPI (tipos em MAIÚSCULAS); Claude, OpenAI e Perplexity usam JSON Schema (minúsculas) e
        // pedem additionalProperties=false em cada objeto.
        fun t(nome: String) = if (openApi) nome else nome.lowercase()
        fun objeto() = JSONObject().put("type", t("OBJECT")).apply { if (!openApi) put("additionalProperties", false) }
        fun texto(desc: String, enum: Collection<String>? = null) = JSONObject().put("type", t("STRING")).put("description", desc)
            .apply { if (enum != null) put("enum", JSONArray(enum.toList())) }
        fun inteiro(desc: String) = JSONObject().put("type", t("INTEGER")).put("description", desc)
        val acao = objeto()
            .put("properties", JSONObject()
                .put("tipo", texto("o que fazer", TIPOS))
                .put("pacote", texto("abrir_app: o pacote, copiado exatamente da lista de apps"))
                .put("tela", texto("abrir_tela", TELAS.keys))
                .put("hora", inteiro("despertador: hora de 0 a 23"))
                .put("minuto", inteiro("despertador: minuto de 0 a 59"))
                .put("dias", JSONObject().put("type", t("ARRAY")).put("items", JSONObject().put("type", t("INTEGER")))
                    .put("description", "despertador: dias da semana que repete, 0=domingo ... 6=sábado; vazio = uma vez só"))
                .put("nome", texto("despertador e timer: nome curto, opcional"))
                .put("acao", texto("volume: subir|descer|mudo; midia: pausar|tocar|proxima|anterior; casa: ligar|desligar|alternar", VOLUME.keys + MIDIA.keys + CASA.keys))
                .put("entidade", texto("casa: id do Home Assistant (ex.: light.sala), copiado da lista da casa; cena e script só ligar"))
                .put("vezes", inteiro("volume: quantos passos, de 1 a 10"))
                .put("ligado", JSONObject().put("type", t("BOOLEAN")).put("description", "modo_visita e filtro_noturno: true liga, false desliga"))
                .put("canal", texto("tocar_canal e o_que_passa: nome ou número do canal, como a pessoa falou (ex.: globo, 5)"))
                .put("texto", texto("recado: a mensagem na tela (até 200 letras, 3 linhas); mural: o item"))
                .put("segundos", inteiro("recado: quanto tempo fica na tela, 30, 60 ou 300 (padrão 60)"))
                .put("minutos", inteiro("timer: de 1 a $MAX_TIMER_MIN; desligar_em: de 1 a $MAX_DESLIGAR_MIN, 0 cancela"))
                .put("cena", texto("rodar_cena: o nome da cena, como foi criada no painel"))
                .put("lista", texto("mural: compras ou recados", listOf("compras", "recados")))
                .put("titulo", texto("tocar_titulo e proximo_episodio: nome do filme ou da série, como a pessoa falou; " +
                    "proximo_episodio sem titulo = a última série vista. continuar retoma o último filme ou série vista; " +
                    "o_que_estava_vendo devolve os últimos títulos em ver_pedido (campo mensagem)")))
            .put("required", JSONArray(listOf("tipo")))
        return objeto()
            .put("properties", JSONObject()
                .put("acoes", JSONObject().put("type", t("ARRAY")).put("items", acao))
                .put("resposta", texto("uma frase curta em português para a pessoa; se não der para fazer, explique por quê")))
            .put("required", JSONArray(listOf("acoes", "resposta")))
    }

    /**
     * Confere o JSON devolvido pela IA. Nunca lança: o que não passa vira uma linha em [Plano.recusadas].
     * [apps]: pacote -> nome dos apps que podem ser abertos (os mesmos mandados nas instruções).
     */
    fun conferir(texto: String, apps: Map<String, String>): Plano {
        // Alguns provedores embrulham o JSON em ```json ... ``` ou põem texto antes: tenta o trecho entre a 1ª { e a última }.
        val raiz = try { JSONObject(texto) } catch (e: Exception) {
            val i = texto.indexOf('{'); val f = texto.lastIndexOf('}')
            try { if (i >= 0 && f > i) JSONObject(texto.substring(i, f + 1)) else null } catch (e2: Exception) { null }
        } ?: return Plano(emptyList(), "", listOf("a IA respondeu algo que não é JSON"))
        val resposta = raiz.optString("resposta").take(300)
        val lista = raiz.optJSONArray("acoes") ?: JSONArray()
        val acoes = ArrayList<Acao>()
        val recusadas = ArrayList<String>()
        for (i in 0 until lista.length()) {
            if (acoes.size >= MAX_ACOES) { recusadas += "mais de $MAX_ACOES ações (o resto foi ignorado)"; break }
            val o = lista.optJSONObject(i) ?: run { recusadas += "ação ${i + 1} não é um objeto"; null } ?: continue
            when (val r = umaAcao(o, apps)) {
                is Acao -> acoes += r
                is String -> recusadas += r
            }
        }
        return Plano(acoes, resposta, recusadas)
    }

    /** Devolve a Acao, ou o motivo (String) da recusa. */
    private fun umaAcao(o: JSONObject, apps: Map<String, String>): Any {
        fun inteiro(nome: String): Int? = if (o.has(nome) && o.opt(nome) is Number) (o.get(nome) as Number).let {
            if (it.toDouble() != it.toInt().toDouble()) null else it.toInt()
        } else null
        return when (val tipo = o.optString("tipo")) {
            "abrir_app" -> {
                val p = o.optString("pacote")
                val nome = apps[p] ?: return "abrir_app: \"${p.take(60)}\" não é um app desta TV"
                Acao.AbrirApp(p, nome)
            }
            "abrir_tela" -> {
                val t = o.optString("tela")
                if (t in TELAS) Acao.AbrirTela(t) else "abrir_tela: tela \"${t.take(30)}\" não existe"
            }
            "despertador" -> {
                val h = inteiro("hora"); val m = inteiro("minuto") ?: 0
                if (h == null || h !in 0..23 || m !in 0..59) return "despertador: hora inválida"
                var dias = 0
                val arr = o.optJSONArray("dias")
                if (arr != null) for (i in 0 until arr.length()) {
                    val d = arr.opt(i) as? Number ?: return "despertador: dia inválido"
                    if (d.toInt() !in 0..6 || d.toDouble() != d.toInt().toDouble()) return "despertador: dia inválido"
                    dias = dias or (1 shl d.toInt())
                }
                // Nome: só o texto simples, curto (aparece na tela do alarme).
                val nome = o.optString("nome").filter { it.isLetterOrDigit() || it == ' ' }.trim().take(30)
                Acao.Despertador(h, m, dias, nome)
            }
            "volume" -> {
                val a = o.optString("acao")
                if (a !in VOLUME) return "volume: \"${a.take(20)}\" não existe"
                val v = (inteiro("vezes") ?: 1)
                if (v !in 1..10) return "volume: vezes fora de 1 a 10"
                Acao.Volume(a, v)
            }
            "midia" -> {
                val a = o.optString("acao")
                if (a in MIDIA) Acao.Midia(a) else "midia: \"${a.take(20)}\" não existe"
            }
            "modo_visita" -> {
                if (o.opt("ligado") !is Boolean) "modo_visita: falta ligado true/false" else Acao.ModoVisita(o.getBoolean("ligado"))
            }
            "tocar_canal", "o_que_passa" -> {
                // Aqui só confere o texto (curto, sem caracteres de controle). Qual canal é, a TV decide na lista dela.
                val c = o.optString("canal").filter { !it.isISOControl() }.trim().take(CanalBusca.MAX_TERMO)
                if (c.isEmpty()) "$tipo: falta o canal (nome ou número)"
                else if (tipo == "tocar_canal") Acao.TocarCanal(c) else Acao.OQuePassa(c)
            }
            "casa" -> {
                // Só os domínios e serviços do CasaHA.servico (o mesmo catálogo de casa_acionar). Nada de serviço livre.
                val e = o.optString("entidade").trim(); val a = o.optString("acao")
                if (a !in CASA) return "casa: ação \"${a.take(20)}\" não existe"
                if (CasaHA.servico(e, a) == null) "casa: \"${e.take(60)}\" não é aparelho aceito, ou a ação não vale para ele" else Acao.Casa(e, a)
            }
            "recado" -> {
                val txt = br.paulo.tvlauncher.Recado.limpar(o.optString("texto")) ?: return "recado: texto vazio ou longo demais"
                Acao.Recado(txt, br.paulo.tvlauncher.Recado.duracao(inteiro("segundos")))
            }
            "timer" -> {
                val m = inteiro("minutos")
                if (m == null || m !in 1..MAX_TIMER_MIN) return "timer: minutos de 1 a $MAX_TIMER_MIN"
                Acao.Timer(m, o.optString("nome").filter { it.isLetterOrDigit() || it == ' ' }.trim().take(30))
            }
            "desligar_em" -> {
                val m = inteiro("minutos")
                if (m == null || m !in 0..MAX_DESLIGAR_MIN) "desligar_em: minutos de 0 a $MAX_DESLIGAR_MIN" else Acao.DesligarEm(m)
            }
            "filtro_noturno" -> {
                if (o.opt("ligado") !is Boolean) "filtro_noturno: falta ligado true/false" else Acao.FiltroNoturno(o.getBoolean("ligado"))
            }
            "rodar_cena" -> {
                val c = o.optString("cena").trim()
                if (Cenas.nomeValido(c)) Acao.RodarCena(c) else "rodar_cena: nome de cena inválido"
            }
            "mural" -> {
                val l = br.paulo.tvlauncher.Mural.lista(o.optString("lista")) ?: return "mural: lista compras ou recados"
                val txt = br.paulo.tvlauncher.Mural.limpar(o.optString("texto").filter { !it.isISOControl() })
                if (txt.isEmpty()) "mural: texto vazio" else Acao.Mural(l, txt)
            }
            "tocar_titulo", "proximo_episodio" -> {
                // Como no canal: aqui só o texto. Qual filme ou série é, a TV decide nas listas dela.
                val tt = o.optString("titulo").filter { !it.isISOControl() }.trim().take(MAX_TITULO)
                if (tipo == "proximo_episodio") Acao.ProximoEpisodio(tt)
                else if (tt.isEmpty()) "tocar_titulo: falta o titulo" else Acao.TocarTitulo(tt)
            }
            "continuar" -> Acao.Continuar()
            "o_que_estava_vendo" -> Acao.OQueEstavaVendo()
            else -> "tipo \"${tipo.take(30)}\" não está no catálogo"
        }
    }

    /** A frase que o painel manda: sem caracteres de controle, até [MAX_FRASE]. Null = inválida (vazia). */
    fun limparFrase(f: String?): String? {
        val t = f?.filter { !it.isISOControl() }?.trim()?.take(MAX_FRASE) ?: return null
        return t.ifEmpty { null }
    }
}

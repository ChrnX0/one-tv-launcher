package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevação 2.11 (17/11/2026): "Desfazer em tudo". Toda mudança de ajuste, organização, automação e limpeza vira um
 * PASSO que guarda o valor de antes de cada chave mexida. O aviso de 10 s ([BarraDesfazer]) e o Diário (TV, painel e
 * MCP) desfazem o passo. Lógica pura aqui (DesfazerRegrasTest); quem lê e grava as prefs é o [Desfazer].
 *
 * Regras:
 *  - categoria pela prefs e pela chave ([categoria]); chave que não é escolha da pessoa (horário, contador, cache) e
 *    chave que guarda segredo (PIN, senha, token) nunca entram: o segredo não pode ir parar no diário.
 *  - mudanças em sequência na mesma tela e categoria, a menos de [JUNTAR_MS], viram um passo só (ex.: a seta que move
 *    um app 5 casas). O "antes" de cada chave é o do PRIMEIRO toque.
 *  - desfazer só volta a chave que ainda está como o passo deixou; se alguém mexeu depois, aquela chave fica (não
 *    apaga a mudança mais nova sem querer) e o resultado diz quantas ficaram.
 *  - limpeza (app oculto pela TV) desfaz por ação reversa do vocabulário fechado [REVERSO]: "mostrar:<pacote>" ou
 *    "ocultar:<pacote>". Nada de comando livre.
 */
object DesfazerRegras {
    enum class Categoria(val chave: String) {
        AJUSTE("ajuste"), ORGANIZACAO("organizacao"), AUTOMACAO("automacao"), LIMPEZA("limpeza");
        companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } }
    }

    /** Uma chave mexida: o valor antes e o depois (null = a chave não existia). */
    data class Mudanca(val prefs: String, val chave: String, val antes: Any?, val depois: Any?)

    data class Passo(
        val id: String,
        val quando: Long,
        val categoria: Categoria,
        /** O que mudou, em palavras da pessoa ("Configurações", "Ordem dos apps", "Ocultar YouTube Kids"). */
        val rotulo: String,
        val mudancas: List<Mudanca>,
        val reverso: List<String> = emptyList(),
        val desfeito: Boolean = false,
    )

    const val AVISO_MS = 10_000L
    const val JUNTAR_MS = 1_500L
    const val MAX = 50
    const val MAX_DIAS = 7L
    private const val DIA_MS = 86_400_000L

    /** Prefs vigiadas e a categoria de cada uma. "launcher" depende da chave (ver [categoria]). */
    val PREFS_AJUSTE = setOf("launcher", "player", "clima", "dns", "nao_perturbe", "pacote_icones", "config_busca",
        "aviso_temperatura", "volume_lg", "ceu", "jogos", "loja_curada", "catalogo_pasta", "aviso_tv")
    val PREFS_AUTOMACAO = setOf("rotinas", "automacoes", "despertador", "agenda_perfis", "manutencao", "timer_regras")
    val PREFS_VIGIADAS = PREFS_AJUSTE + PREFS_AUTOMACAO

    /** Chaves de "launcher" que são organização da linha de apps (Organizacao.kt). */
    private val CHAVES_ORGANIZACAO = setOf("ordem", "pilhas", "pilhas_auto", "auto_fora", "frentes_auto", "ocultos")
    /** Guardada pelo Debloat: o passo de limpeza vem com a ação reversa, não pela prefs. */
    const val CHAVE_DEBLOAT = "debloat_ocultos"

    private val SEGREDO = Regex("""pin|senha|token|segredo|secret|password|chave_api|api_key|cookie|sessao""", RegexOption.IGNORE_CASE)
    /** Estado que a TV muda sozinha: não é escolha da pessoa e encheria o diário. */
    private val PASSAGEIRO = Regex("""(^|_)(ultim[oa]s?|cache|contador|contagem|visto|vistos|quando|em|ts|desde|medida|erro|tentativas|historico|pendente|aberto|sessao)($|_)""", RegexOption.IGNORE_CASE)

    /** A categoria de uma chave mexida, ou null = não entra (passageira, segredo ou prefs não vigiada). */
    fun categoria(prefs: String, chave: String): Categoria? {
        val base = prefs.substringBefore('@')
        val k = chave.substringBefore('@')
        if (SEGREDO.containsMatchIn(chave) || PASSAGEIRO.containsMatchIn(k)) return null
        return when {
            base == "launcher" && k == CHAVE_DEBLOAT -> null
            base == "launcher" && k in CHAVES_ORGANIZACAO -> Categoria.ORGANIZACAO
            base in PREFS_AUTOMACAO -> Categoria.AUTOMACAO
            base in PREFS_AJUSTE -> Categoria.AJUSTE
            else -> null
        }
    }

    /** O que mudou entre duas fotos de uma prefs, só as chaves que entram ([categoria]). */
    fun diferencas(prefs: String, antes: Map<String, *>, depois: Map<String, *>): List<Mudanca> =
        (antes.keys + depois.keys).sorted().mapNotNull { k ->
            val a = antes[k]; val d = depois[k]
            if (igual(a, d) || categoria(prefs, k) == null) null else Mudanca(prefs, k, copia(a), copia(d))
        }

    private fun copia(v: Any?): Any? = if (v is Set<*>) v.map { it.toString() }.toSortedSet() else v
    fun igual(a: Any?, b: Any?): Boolean = when {
        a is Set<*> && b is Set<*> -> a.map { it.toString() }.toSet() == b.map { it.toString() }.toSet()
        a is Number && b is Number && a !is Float && b !is Float -> a.toLong() == b.toLong() && a::class == b::class
        else -> a == b
    }

    /**
     * Junta [novas] ao último passo quando é a mesma tela/categoria a menos de [JUNTAR_MS]; senão abre um passo novo.
     * Devolve a lista nova (o mais novo primeiro) e o passo que ficou na frente.
     */
    fun registrar(lista: List<Passo>, categoria: Categoria, rotulo: String, novas: List<Mudanca>, reverso: List<String>,
                  agora: Long, id: String): Pair<List<Passo>, Passo?> {
        val rev = reverso.filter { REVERSO.matches(it) }
        if (novas.isEmpty() && rev.isEmpty()) return lista to null
        val topo = lista.firstOrNull()
        val juntar = topo != null && !topo.desfeito && topo.categoria == categoria && topo.rotulo == rotulo &&
            agora - topo.quando in 0..JUNTAR_MS && rev.isEmpty() && topo.reverso.isEmpty()
        val passo = if (juntar) {
            val porChave = LinkedHashMap<String, Mudanca>()
            topo!!.mudancas.forEach { porChave[it.prefs + "\u0000" + it.chave] = it }
            for (m in novas) {
                val k = m.prefs + "\u0000" + m.chave
                val velha = porChave[k]
                porChave[k] = if (velha == null) m else velha.copy(depois = m.depois)
            }
            // Voltou ao que era: some do passo (mover para a direita e de volta = nada a desfazer).
            topo.copy(quando = agora, mudancas = porChave.values.filterNot { igual(it.antes, it.depois) })
        } else Passo(id, agora, categoria, rotulo.take(120), novas, rev)
        val resto = if (juntar) lista.drop(1) else lista
        val nova = if (passo.mudancas.isEmpty() && passo.reverso.isEmpty()) resto else listOf(passo) + resto
        return podar(nova, agora) to passo.takeIf { it.mudancas.isNotEmpty() || it.reverso.isNotEmpty() }
    }

    fun podar(lista: List<Passo>, agora: Long): List<Passo> =
        lista.filter { agora - it.quando <= MAX_DIAS * DIA_MS }.take(MAX)

    /** Vocabulário fechado das ações reversas. */
    val REVERSO = Regex("""^(mostrar|ocultar):[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    /** O contrário de uma ação de limpeza: ocultou -> mostrar, mostrou -> ocultar. */
    fun reversoDe(pacote: String, ocultou: Boolean) = (if (ocultou) "mostrar:" else "ocultar:") + pacote

    /**
     * O que desfazer agora: cada mudança cuja chave ainda está como o passo deixou ([atual] lê o valor de hoje).
     * Devolve as que voltam e quantas ficaram porque mudaram depois.
     */
    fun planoDesfazer(passo: Passo, atual: (prefs: String, chave: String) -> Any?): Pair<List<Mudanca>, Int> {
        if (passo.desfeito) return emptyList<Mudanca>() to 0
        val (voltam, mexidas) = passo.mudancas.partition { igual(atual(it.prefs, it.chave), it.depois) }
        return voltam to mexidas.size
    }

    fun marcarDesfeito(lista: List<Passo>, id: String): List<Passo> = lista.map { if (it.id == id) it.copy(desfeito = true) else it }

    // ---------------------------------------------------------------- JSON

    fun valorJson(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("t", "n")
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Set<*> -> JSONObject().put("t", "c").put("v", JSONArray(v.map { it.toString() }.sorted()))
        else -> JSONObject().put("t", "s").put("v", v.toString())
    }

    fun valorDeJson(o: JSONObject?): Any? = when (o?.optString("t")) {
        "b" -> o.optBoolean("v")
        "i" -> o.optInt("v")
        "l" -> o.optLong("v")
        "f" -> o.optDouble("v").toFloat()
        "c" -> o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSortedSet() } ?: sortedSetOf<String>()
        "s" -> o.optString("v")
        else -> null
    }

    fun paraJson(lista: List<Passo>): String = JSONArray().apply {
        for (p in lista) put(JSONObject().put("id", p.id).put("quando", p.quando).put("cat", p.categoria.chave)
            .put("rotulo", p.rotulo).put("desfeito", p.desfeito).put("rev", JSONArray(p.reverso))
            .put("m", JSONArray().apply {
                p.mudancas.forEach { put(JSONObject().put("p", it.prefs).put("k", it.chave).put("a", valorJson(it.antes)).put("d", valorJson(it.depois))) }
            }))
    }.toString()

    fun deJson(texto: String?): List<Passo> {
        val a = runCatching { JSONArray(texto ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val cat = Categoria.de(o.optString("cat")) ?: return@mapNotNull null
            val m = o.optJSONArray("m") ?: JSONArray()
            val rev = o.optJSONArray("rev") ?: JSONArray()
            Passo(o.optString("id"), o.optLong("quando"), cat, o.optString("rotulo"),
                (0 until m.length()).mapNotNull { j -> m.optJSONObject(j)?.let {
                    Mudanca(it.optString("p"), it.optString("k"), valorDeJson(it.optJSONObject("a")), valorDeJson(it.optJSONObject("d")))
                } },
                (0 until rev.length()).map { rev.optString(it) }.filter { REVERSO.matches(it) },
                o.optBoolean("desfeito"))
        }
    }

    /** Para o painel e o MCP: sem os valores (podem ser longos), só o que a pessoa precisa para escolher. */
    fun resumo(p: Passo): JSONObject = JSONObject().put("id", p.id).put("quando", p.quando).put("categoria", p.categoria.chave)
        .put("rotulo", p.rotulo).put("mudancas", p.mudancas.size + p.reverso.size).put("desfeito", p.desfeito)
}

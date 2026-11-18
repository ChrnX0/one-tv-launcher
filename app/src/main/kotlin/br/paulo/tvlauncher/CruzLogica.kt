package br.paulo.tvlauncher

/**
 * Tela inicial em cruz (17/11/2026, ideia do dono; especificação em home-grupos/cruz-especificacao-2026-11-17.md):
 * uma LINHA de categorias (Continuar, Assistir, Ouvir, Jogar, Aplicativos) e, na categoria em foco, uma COLUNA com os
 * itens dela. ◀ ▶ trocam de categoria, ▲ ▼ andam na coluna, OK abre o item escolhido.
 *
 * Aqui só a lógica pura (estado + tecla -> novo estado), sem Android: o teste JVM (CruzLogicaTest) mede quantas teclas
 * cada destino leva, com a mesma tabela da amostra navegável (TV 1, Séries 2, Rádio 2, Podcasts 3, Jogos 3, Sofá 4,
 * Continuar 2). Quem desenha é a [HomeCruz].
 *
 * Regras (aceite da especificação):
 * - abre em Assistir › TV;
 * - ▲ no primeiro item sobe para a barra de cima ([Resultado.SubirTopo]); ▼ no último não faz nada (sem dar a volta);
 * - ◀ na primeira categoria e ▶ na última não fazem nada; Voltar não faz nada;
 * - HOME vai para Assistir (mantendo o item lembrado); HOME já em Assistir vai para o 1º item (TV);
 * - cada categoria lembra o último item ESCOLHIDO (OK), pelo id do item, não pela posição (a lista muda).
 * Categoria sem itens não existe (quem monta já a tira); item de recurso desligado também não.
 */
object CruzLogica {

    /** As categorias da linha, na ordem fixa da especificação. O [id] é o que vai para as preferências. */
    enum class Categoria(val id: String) { CONTINUAR("continuar"), ASSISTIR("assistir"), OUVIR("ouvir"), JOGAR("jogar"), APLICATIVOS("aplicativos") }

    enum class Tecla { ESQUERDA, DIREITA, CIMA, BAIXO, OK, VOLTAR, HOME }

    /** O que a tela precisa saber da cruz: as categorias presentes e os ids dos itens de cada uma, em ordem. */
    data class Mapa(val categorias: List<Categoria>, val itens: List<List<String>>) {
        init { require(categorias.size == itens.size) }
        fun indice(c: Categoria) = categorias.indexOf(c)
    }

    /** [cat] = posição na linha; [sel] = item escolhido em cada categoria (mesmo tamanho de [Mapa.categorias]). */
    data class Estado(val cat: Int, val sel: List<Int>) {
        fun item(m: Mapa): String? = m.itens.getOrNull(cat)?.getOrNull(sel.getOrElse(cat) { 0 })
        fun categoria(m: Mapa): Categoria? = m.categorias.getOrNull(cat)
    }

    sealed class Resultado(val estado: Estado) {
        /** Mudou (ou não) de lugar dentro da cruz. */
        class Andou(estado: Estado) : Resultado(estado)
        /** OK num item: abrir [item] da [categoria]. */
        class Abrir(estado: Estado, val categoria: Categoria, val item: String) : Resultado(estado)
        /** ▲ no primeiro item: o foco sai da cruz para a barra de cima. */
        class SubirTopo(estado: Estado) : Resultado(estado)
    }

    /**
     * Estado de abertura: Assistir › 1º item (a TV, quando existe). As outras categorias começam no item lembrado
     * ([lembrados]: id da categoria -> id do item), ou no 1º se ele sumiu.
     * Sem Assistir (sem nada para assistir), a 1ª categoria que existir.
     */
    fun inicial(m: Mapa, lembrados: Map<String, String> = emptyMap()): Estado {
        val sel = m.categorias.mapIndexed { i, c ->
            if (c == Categoria.ASSISTIR) 0 else m.itens[i].indexOf(lembrados[c.id]).coerceAtLeast(0)
        }
        val cat = m.indice(Categoria.ASSISTIR).takeIf { it >= 0 } ?: 0
        return Estado(cat, sel)
    }

    /**
     * O mesmo estado num mapa novo (apps instalados, recurso ligado/desligado, Continuar que mudou): cada categoria fica
     * no mesmo ITEM se ele ainda existe, senão na mesma posição (o vizinho); a categoria em foco idem (sumiu = vizinha).
     */
    fun reajustar(antigo: Mapa, e: Estado, novo: Mapa): Estado {
        val sel = novo.categorias.mapIndexed { i, c ->
            val j = antigo.indice(c)
            val eraId = if (j >= 0) antigo.itens[j].getOrNull(e.sel.getOrElse(j) { 0 }) else null
            val achado = novo.itens[i].indexOf(eraId)
            when {
                achado >= 0 -> achado
                j >= 0 -> e.sel.getOrElse(j) { 0 }.coerceIn(0, (novo.itens[i].size - 1).coerceAtLeast(0))
                else -> 0
            }
        }
        val catAntiga = antigo.categorias.getOrNull(e.cat)
        val cat = novo.indice(catAntiga ?: Categoria.ASSISTIR).takeIf { it >= 0 }
            ?: e.cat.coerceIn(0, (novo.categorias.size - 1).coerceAtLeast(0))
        return Estado(cat, sel)
    }

    fun passo(m: Mapa, e: Estado, t: Tecla): Resultado {
        if (m.categorias.isEmpty()) return if (t == Tecla.CIMA) Resultado.SubirTopo(e) else Resultado.Andou(e)
        val n = m.itens[e.cat].size
        val s = e.sel[e.cat]
        return when (t) {
            Tecla.ESQUERDA -> Resultado.Andou(if (e.cat > 0) e.copy(cat = e.cat - 1) else e)
            Tecla.DIREITA -> Resultado.Andou(if (e.cat < m.categorias.lastIndex) e.copy(cat = e.cat + 1) else e)
            Tecla.CIMA -> if (s > 0) Resultado.Andou(e.copy(sel = e.sel.trocar(e.cat, s - 1))) else Resultado.SubirTopo(e)
            Tecla.BAIXO -> Resultado.Andou(if (s < n - 1) e.copy(sel = e.sel.trocar(e.cat, s + 1)) else e)
            Tecla.OK -> m.itens[e.cat].getOrNull(s)?.let { Resultado.Abrir(e, m.categorias[e.cat], it) } ?: Resultado.Andou(e)
            Tecla.VOLTAR -> Resultado.Andou(e)
            Tecla.HOME -> {
                val a = m.indice(Categoria.ASSISTIR)
                when {
                    a < 0 -> Resultado.Andou(e)
                    e.cat != a -> Resultado.Andou(e.copy(cat = a))
                    else -> Resultado.Andou(e.copy(sel = e.sel.trocar(a, 0)))   // HOME duas vezes: a TV
                }
            }
        }
    }

    /**
     * Menor sequência de teclas (sem HOME) do estado de abertura até abrir [alvo]; null se não chega em [limite] teclas.
     * É a conta da tabela da amostra; serve ao teste e a quem quiser mostrar "N teclas" no painel.
     */
    fun menorCaminho(m: Mapa, alvo: String, lembrados: Map<String, String> = emptyMap(), limite: Int = 14): List<Tecla>? {
        val ini = inicial(m, lembrados)
        val fila = ArrayDeque(listOf(ini to emptyList<Tecla>()))
        val visto = hashSetOf(ini)
        val teclas = listOf(Tecla.ESQUERDA, Tecla.DIREITA, Tecla.CIMA, Tecla.BAIXO, Tecla.OK, Tecla.VOLTAR)
        while (fila.isNotEmpty()) {
            val (e, cam) = fila.removeFirst()
            if (cam.size >= limite) break
            for (t in teclas) {
                val r = passo(m, e, t)
                val novo = cam + t
                if (r is Resultado.Abrir) { if (r.item == alvo) return novo; continue }
                if (r is Resultado.SubirTopo) continue
                if (visto.add(r.estado)) fila.addLast(r.estado to novo)
            }
        }
        return null
    }

    private fun List<Int>.trocar(i: Int, v: Int) = toMutableList().also { it[i] = v }

    // ---- quais itens entram em Aplicativos

    /** Quantos apps a coluna Aplicativos mostra antes de "Todos os apps" (decisão 3 da especificação). */
    const val APPS_NA_COLUNA = 8
    const val ID_TODOS_APPS = "todos_apps"

    /**
     * Os [APPS_NA_COLUNA] apps da coluna e "Todos os apps" no fim (estudo da HOME, 18/11/2026, onda 2):
     * 1. os [fixados] pelo dono ("Fixar no topo"), na ordem em que foram fixados;
     * 2. depois por [frequencia] (aberturas que valem metade a cada [MEIA_VIDA_MS]; ver [somarAbertura]): quem abre o
     *    YouTube todo dia e o Globoplay uma vez ontem vê o YouTube em cima, não o último aberto;
     * 3. empate e nunca aberto pela HOME: último uso do Android, depois a ordem da linha de apps de hoje (ordem manual).
     */
    fun appsDaColuna(
        ordemDaLinha: List<String>, ultimoUso: Map<String, Long>,
        frequencia: Map<String, Double> = emptyMap(), fixados: List<String> = emptyList(),
    ): List<String> = ordenarApps(ordemDaLinha, ultimoUso, frequencia, fixados).take(APPS_NA_COLUNA) + ID_TODOS_APPS

    /** A mesma ordem de [appsDaColuna], sem corte nem "Todos os apps" (apps movidos para Assistir/Ouvir, jogos em Jogar). */
    fun ordenarApps(
        ordem: List<String>, ultimoUso: Map<String, Long>,
        frequencia: Map<String, Double> = emptyMap(), fixados: List<String> = emptyList(),
    ): List<String> {
        val pos = ordem.withIndex().associate { it.value to it.index }
        val topo = fixados.filter { it in pos }.distinct()
        return topo + ordem.filter { it !in topo }.sortedWith(compareBy<String>(
            { -(frequencia[it] ?: 0.0) }, { -(ultimoUso[it] ?: 0L) }, { pos[it] ?: Int.MAX_VALUE }))
    }

    /**
     * Continuar (18/11/2026): os itens com quando (ms) do mais recente para o mais antigo, no máximo [max]. Empate (ou
     * quando = 0, sem data) mantém a ordem em que vieram.
     */
    fun <T> recentes(itens: List<Pair<Long, T>>, max: Int): List<T> =
        itens.withIndex().sortedWith(compareBy({ -it.value.first }, { it.index })).take(max.coerceAtLeast(0)).map { it.value.second }

    /** Meia-vida da contagem de aberturas: 7 dias (uma abertura de uma semana atrás vale meia de hoje). */
    const val MEIA_VIDA_MS = 7L * 24 * 3600 * 1000

    /** Quanto vale hoje uma contagem [pontos] guardada em [desde]. */
    fun frequenciaAgora(pontos: Double, desde: Long, agora: Long): Double =
        if (agora <= desde) pontos else pontos * Math.pow(0.5, (agora - desde).toDouble() / MEIA_VIDA_MS)

    /** A contagem depois de mais uma abertura em [agora] (guarda-se o par pontos/agora). */
    fun somarAbertura(pontos: Double, desde: Long, agora: Long): Double = frequenciaAgora(pontos, desde, agora) + 1.0
}

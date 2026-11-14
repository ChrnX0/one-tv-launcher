package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Player universal, filmes do Xtream (06/11/2026): o catálogo de filmes, com pôsteres, categorias, ordem, busca,
 * "continuar assistindo" e a ficha de cada filme. Quem toca é a VodActivity.
 *
 *   [ categorias ]   [ grade de pôsteres (6 por linha)                ]
 *     ⌕ Buscar         ▓▓ ▓▓ ▓▓ ▓▓ ▓▓ ▓▓      OK num pôster abre a ficha: sinopse, elenco, duração,
 *     ⇅ Ordem          ▓▓ ▓▓ ▓▓ ▓▓ ▓▓ ▓▓      e os botões Continuar / Do começo / Assistir.
 *     ▶ Continuar
 *     ★ Interesses     OK segurado 2 s num pôster põe/tira dos interesses (como os favoritos dos canais).
 *     ✓ Assistidos     tudo o que já foi visto, com a etiqueta "42%" ou "Finalizado" no pôster.
 *     ☰ Filtrar        gênero + época + nota mínima + só não vistos, sobre a categoria escolhida (ideia 23, FilmesFiltro).
 *     ✦ Sortear        um filme ao acaso da grade (filtrada), preferindo os Interesses; abre a ficha dele.
 *     Todos · Ação · ...
 *
 * As duas colunas são ListView/GridView do Android (só desenham o que está na tela: 30 mil filmes custam o mesmo que 30).
 * Processo ":player" (manifesto), como o resto do player.
 */
class FilmesActivity : Activity() {
    private companion object {
        const val BUSCAR = "*buscar"
        const val ORDEM = "*ordem"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val ASSISTIDOS = "*assistidos"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
        const val OCULTAS = "*ocultas"
        const val FILTRO = "*filtro"       // ideia 23: menu do filtro combinado
        const val SORTEIO = "*sorteio"     // ideia 23: sorteia um filme da grade
        const val COLECAO = "*colecao"     // ideia 23: "Coleção deste filme" (aberta pela ficha)
    }

    private data class Item(val chave: String, val nome: String, val quantos: Int)

    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val suave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val ambar = Estilo.ATENCAO
    private val verdeEtiqueta = 0xE62E7D4F.toInt()
    private val fonteMedia = Fontes.manrope(500)

    private var filmes: List<Filme> = emptyList()      // o que a tela mostra (sem as categorias ocultas, até o PIN)
    private var todos: List<Filme> = emptyList()       // o catálogo inteiro, como veio do servidor
    private var ocultas: Set<String> = emptySet()      // categorias escondidas (adultas, escondidas, controle parental)
    private var mostrarOcultas = false                 // true só depois do PIN, como no player da TV ao vivo
    private var indoAoVideo = false                    // saiu para a VodActivity: o PIN continua valendo na volta
    private val janelas by lazy { PlayerJanelas(this) }
    private var posicoes: Map<String, Posicao> = emptyMap()
    private var interesses: Set<String> = emptySet()
    private val segurar = SegurarOk()
    private var ordem = Vod.Ordem.RECENTES
    private var itens: List<Item> = emptyList()
    private var chave = TODOS
    private var termo = ""
    private var resultados: List<Filme> = emptyList()
    private var naGrade: List<Filme> = emptyList()
    private var ficha: Filme? = null
    private var fichaInfo: FichaFilme? = null   // a ficha carregada do filme em `ficha` (as cenas vão para a HOME)
    // Dublado x legendado (Idioma, 11/11/2026): o mesmo filme nas duas versões do catálogo, e o que a pessoa prefere.
    private var indiceVersoes: Map<String, List<Filme>> = emptyMap()
    private var prefsIdioma = Idioma.Prefs()
    // Ideia 23: filtro combinado (vale para a categoria escolhida), a coleção aberta pela ficha e a da ficha aberta.
    private var filtro = FilmesFiltro.Criterios()
    private var colecao: Pair<String, List<Filme>>? = null          // nome exibido + filmes, do mais antigo ao mais novo
    private var colecaoFicha: Pair<Filme, List<Filme>>? = null      // calculada fora da tela quando a ficha abre

    private lateinit var listaCats: ListView
    private lateinit var grade: GridView
    private lateinit var cabecalho: TextView
    private lateinit var focado: TextView
    private lateinit var aviso: TextView
    private lateinit var painelFicha: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences("player", MODE_PRIVATE).getString("ordem_filmes", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        // Coerência visual parte 2 (12/11/2026): o grafite e a margem da HOME (eram FUNDO liso e 32/26 dp).
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(context) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(18)) }
        listaCats = ListView(this).apply { estiloLista(this) }
        colunas.addView(coluna(rotulo(getString(R.string.filmes_titulo)), listaCats), LinearLayout.LayoutParams(dp(210), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cabecalho = rotulo("")
        direita.addView(cabecalho)
        grade = GridView(this).apply {
            numColumns = Estilo.Leitura.GRADE_COLUNAS
            horizontalSpacing = dp(12); verticalSpacing = dp(Estilo.Leitura.GRADE_VAO)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estiloLista(this)
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        // A informação do item em foco fica EM CIMA da grade, como o destaque da HOME (era uma linha de 13 sp embaixo).
        focado = TextView(this).apply { textSize = Estilo.Leitura.FOCADO; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(4), dp(10), 0, dp(10)) }
        direita.addView(focado, 1)   // logo abaixo do cabeçalho da categoria
        // Dica fixa das teclas, no mesmo jeito da lista de canais do player (player_lista_teclas: 11sp, cor apagada).
        direita.addView(TextView(this).apply { text = getString(R.string.vod_grade_teclas); textSize = Estilo.Leitura.DICA; setTextColor(apagado); setPadding(dp(8), dp(6), 0, 0) })
        colunas.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))
        painelFicha = FrameLayout(this).apply { visibility = View.GONE; background = Estilo.fundoTela(context) }
        raiz.addView(painelFicha, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.filmes_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        listaCats.adapter = adaptadorCats
        grade.adapter = adaptadorGrade
        listaCats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                tela.removeCallbacks(trocarCat)
                if (listaCats.hasFocus()) tela.postDelayed(trocarCat, 180)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaCats.setOnItemClickListener { _, _, pos, _ ->
            when (itens.getOrNull(pos)?.chave) {
                BUSCAR -> buscar()
                ORDEM -> escolherOrdem()
                OCULTAS -> alternarOcultas()
                FILTRO -> menuFiltro()
                SORTEIO -> sortear()
                else -> escolherCat(pos, focarGrade = true)
            }
        }
        grade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { mostrarFocado(naGrade.getOrNull(pos)) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grade.setOnItemClickListener { _, _, pos, _ -> naGrade.getOrNull(pos)?.let { abrirFicha(it, trocar = true) } }
        carregar()
    }

    override fun onResume() {
        super.onResume()
        // Voltou da VodActivity: a posição mudou ("continuar" e os botões da ficha).
        indoAoVideo = false
        val lista = todos
        if (lista.isNotEmpty()) Fundo.rodar {
            val p = PlayerArmazem.posicoes(this)
            val oc = lerOcultas(lista)   // o controle parental pode ter mudado nas Configurações
            val pi = IdiomaPlayer.prefs(this)   // trocar o áudio no filme muda a escolha dele
            tela.post {
                if (isDestroyed) return@post
                val mudou = oc != ocultas
                posicoes = p; prefsIdioma = pi; ocultas = oc
                // "Assistidos" e "Continuar" mudam de conteúdo ao voltar do vídeo: refaz a grade sem perder o lugar.
                recalcular(if (mudou || chave == ASSISTIDOS || chave == CONTINUAR || filtro.naoVistos) ModoGrade.MANTER_LUGAR else ModoGrade.IGUAL)
                adaptadorGrade.notifyDataSetChanged()   // a barrinha de progresso do card muda na hora
                ficha?.let { abrirFicha(it, manterFoco = true) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // O PIN vale só enquanto a tela está na frente (igual ao player): saiu para a HOME ou outro app, tranca de novo.
        // Ir assistir um filme daqui não conta, senão a cada filme da categoria liberada teria de digitar o PIN.
        if (mostrarOcultas && !indoAoVideo) {
            mostrarOcultas = false
            if (ficha?.let { it.grupo.trim() in ocultas } == true) fecharFicha()
            refiltrarOcultas()
        }
    }

    override fun onDestroy() { calculo.fechar(); tela.removeCallbacksAndMessages(null); super.onDestroy() }

    /** Categorias ocultas deste catálogo (lê arquivos: chamar fora da thread da tela). */
    private fun lerOcultas(lista: List<Filme>) = VodFiltro.ocultas(lista.map { it.grupo },
        PlayerArmazem.gruposEscondidos(this), PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))

    private fun refiltrarOcultas() = recalcular(ModoGrade.NOVA)

    /** "Mostrar categorias ocultas" pede o PIN do painel (o mesmo do player); "Esconder de novo" não pede nada. */
    private fun alternarOcultas() {
        if (mostrarOcultas) { mostrarOcultas = false; refiltrarOcultas() }
        else janelas.pedirPin(aoLiberar = { mostrarOcultas = true; refiltrarOcultas() }, aviso = { avisoBreve(it) })
    }

    private fun carregar() {
        Fundo.rodar {
            // Abre pelo cache na hora; se ele venceu, baixa em segundo plano para a próxima vez (sem trocar a grade na mão
            // de quem já está navegando). Sem cache nenhum, espera o download como antes.
            val cache = PlayerCarga.filmes(this, soCache = true)
            val f = cache.ifEmpty { PlayerCarga.filmes(this) }
            if (cache.isNotEmpty() && PlayerCarga.catalogoVelho(this, series = false))
                Fundo.rodar("filmes-atualizar") { runCatching { PlayerCarga.filmes(this) } }
            val p = PlayerArmazem.posicoes(this)
            val i = PlayerArmazem.interesses(this, series = false)
            val oc = lerOcultas(f)
            val pi = IdiomaPlayer.prefs(this)
            tela.post {
                if (isDestroyed) return@post
                todos = f; ocultas = oc; posicoes = p; interesses = i; prefsIdioma = pi
                if (f.isEmpty()) { aviso.text = getString(R.string.filmes_vazio); return@post }
                // Aberta pela busca única da HOME (12/11/2026): já entra com o termo buscado.
                intent.getStringExtra(BuscaUnica.EXTRA_TERMO)?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> termo = t; chave = BUSCA }
                // A 1ª conta (ocultas, contagens, ordem) também sai da thread da tela; o "Carregando" fica até ela chegar.
                recalcular(ModoGrade.NOVA, inicial = true) { aviso.visibility = View.GONE; grade.requestFocus() }
                // O índice das versões sai depois da grade na tela (é a parte demorada; a ficha funciona sem ele).
                Fundo.rodar {
                    val idx = Idioma.indice(f, { it.nome }, { it.grupo })
                    tela.post { if (!isDestroyed && todos === f) indiceVersoes = idx }
                }
            }
        }
    }

    // ---------------------------------------------------------------- categorias

    private val trocarCat = Runnable { escolherCat(listaCats.selectedItemPosition) }

    /** O que fazer com a grade quando a conta chegar. */
    private enum class ModoGrade { NOVA, MANTER_LUGAR, IGUAL }

    /** Resultado de uma conta: tudo que a tela precisa, calculado fora dela. */
    private class Estado(val filmes: List<Filme>, val resultados: List<Filme>, val itens: List<Item>, val chave: String,
                         val grade: List<Filme>?)

    private val calculo = CalculoTela(tela, "filmes-conta")

    // Caches da conta (só a thread da [calculo] lê e escreve). A chave é a própria lista (===): lista nova = refaz.
    private var cacheVisiveis: Triple<List<Filme>, Set<String>, List<Filme>>? = null
    private var cacheBusca: Triple<List<Filme>, String, List<Filme>>? = null
    private var cacheOrdem: Triple<List<Filme>, Vod.Ordem, List<Filme>>? = null
    private var cacheGrupos: Pair<List<Filme>, List<Pair<String, Int>>>? = null

    /**
     * Refaz, FORA da thread da tela, as categorias ocultas, a busca, as contagens das categorias e (conforme [modo]) a
     * grade. Pedidos seguidos (rolar pelas categorias) descartam os velhos. [inicial]: escolhe a categoria de entrada
     * (Continuar se houver). [depois] roda na tela com a grade nova já posta.
     */
    private fun recalcular(modo: ModoGrade, inicial: Boolean = false, depois: (() -> Unit)? = null) {
        val todosS = todos; val ocultasS = if (mostrarOcultas) emptySet() else ocultas; val mostrar = mostrarOcultas
        val termoS = termo; val posS = posicoes; val intS = interesses; val ordemS = ordem; val chaveS = chave
        val temOcultas = ocultas.isNotEmpty()
        val filtroS = filtro; val colecaoS = colecao
        val rotulo = { id: Int -> getString(id) }
        val antesSel = grade.selectedItemPosition
        calculo.pedir(
            calcular = {
                val vis = cacheVisiveis?.takeIf { it.first === todosS && it.second == ocultasS }?.third
                    ?: (if (mostrar) todosS else VodFiltro.visiveis(todosS, ocultasS) { it.grupo }).also { cacheVisiveis = Triple(todosS, ocultasS, it) }
                val res = if (termoS.isEmpty()) emptyList() else cacheBusca?.takeIf { it.first === vis && it.second == termoS }?.third
                    ?: Vod.buscar(vis, termoS).also { cacheBusca = Triple(vis, termoS, it) }
                val cont = Vod.continuar(vis, posS)
                val ch0 = if (inicial && chaveS != BUSCA) (if (cont.isNotEmpty()) CONTINUAR else TODOS) else chaveS
                val l = ArrayList<Item>()
                l += Item(BUSCAR, rotulo(R.string.player_acao_buscar), -1)
                l += Item(ORDEM, getString(R.string.filmes_ordem, nomeOrdem(ordemS)), -1)
                // Só aparece quando há categoria oculta neste catálogo (mesmo texto do menu de filtros do player).
                if (temOcultas) l += Item(OCULTAS, rotulo(if (mostrar) R.string.player_ocultas_esconder else R.string.player_ocultas_mostrar), -1)
                l += Item(FILTRO, rotulo(R.string.filmes_filtro), -1)
                l += Item(SORTEIO, rotulo(R.string.filmes_sorteio), -1)
                // A coleção vem da ficha; filme de categoria oculta não entra (o PIN pode ter trancado de novo).
                val col = colecaoS?.let { (n, fs) -> n to fs.filter { mostrar || it.grupo.trim() !in ocultasS } }?.takeIf { it.second.isNotEmpty() }
                if (col != null) l += Item(COLECAO, getString(R.string.filmes_colecao_cat, col.first), col.second.size)
                if (cont.isNotEmpty()) l += Item(CONTINUAR, rotulo(R.string.filmes_continuar), cont.size)
                // Interesses aparece sempre (vazio explica como usar); Assistidos só depois do 1º filme visto.
                val ints = Vod.interesses(vis, intS) { it.chave }
                l += Item(INTERESSES, rotulo(R.string.filmes_interesses), ints.size)
                val vistos = Vod.assistidos(vis, posS)
                if (vistos.isNotEmpty()) l += Item(ASSISTIDOS, rotulo(R.string.filmes_assistidos), vistos.size)
                if (termoS.isNotEmpty()) l += Item(BUSCA, getString(R.string.player_cat_busca, termoS), res.size)
                l += Item(TODOS, rotulo(R.string.player_cat_todos), vis.size)
                val grupos = cacheGrupos?.takeIf { it.first === vis }?.second ?: Vod.grupos(vis).also { cacheGrupos = vis to it }
                grupos.forEach { (g, n) -> l += Item("g:$g", PlayerLista.nomeExibido(g).ifEmpty { rotulo(R.string.player_cat_outros) }, n) }
                val ch = if (l.none { it.chave == ch0 }) TODOS else ch0
                val g = if (modo == ModoGrade.IGUAL && ch == chaveS) null else {
                    // A ordem do catálogo inteiro fica guardada: uma categoria é o filtro dela (filtrar mantém a ordem).
                    fun ordenados() = cacheOrdem?.takeIf { it.first === vis && it.second == ordemS }?.third
                        ?: Vod.ordenar(vis, ordemS).also { cacheOrdem = Triple(vis, ordemS, it) }
                    when {
                        ch == CONTINUAR -> cont                  // já na ordem de "mais recente"
                        ch == INTERESSES -> ints                 // o último marcado primeiro
                        ch == ASSISTIDOS -> vistos               // o visto mais recente primeiro
                        ch == BUSCA -> res
                        ch == COLECAO -> col?.second.orEmpty()   // já na ordem dos anos
                        ch.startsWith("g:") -> { val alvo = ch.removePrefix("g:"); ordenados().filter { it.grupo.trim() == alvo } }
                        else -> ordenados()
                    }.let { FilmesFiltro.filtrar(it, filtroS, posS) }   // o filtro combinado vale sobre qualquer categoria
                }
                Estado(vis, res, l, ch, g)
            },
            aplicar = { e ->
                if (!isDestroyed) {
                    val velhos = itens
                    filmes = e.filmes; resultados = e.resultados; itens = e.itens; chave = e.chave
                    atualizarCats(velhos)
                    if (!listaCats.hasFocus()) listaCats.setSelection(itens.indexOfFirst { it.chave == chave }.coerceAtLeast(0))
                    if (e.grade != null) {
                        naGrade = e.grade
                        adaptadorGrade.notifyDataSetChanged()
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isNotEmpty()) {
                            val p = antesSel.coerceIn(0, naGrade.size - 1); grade.setSelection(p); mostrarFocado(naGrade[p])
                        } else {
                            grade.setSelection(0); mostrarFocado(naGrade.firstOrNull())
                        }
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isEmpty() && grade.hasFocus()) listaCats.requestFocus()   // esvaziou
                    }
                    val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
                    val cab = getString(R.string.filmes_cabecalho, nome.uppercase(Locale.getDefault()), naGrade.size)
                    // Resumo do filtro no título da coluna: a pessoa sabe por que a categoria tem menos filmes.
                    cabecalho.text = if (filtro.ativo) getString(R.string.filmes_filtro_cabecalho, cab, resumoFiltro().uppercase(Locale.getDefault())) else cab
                    depois?.invoke()
                }
            },
            demorou = { if (!isDestroyed) { aviso.text = getString(R.string.filmes_carregando); aviso.visibility = View.VISIBLE; avisoDaConta = true } },
            fim = { if (avisoDaConta) { aviso.visibility = View.GONE; avisoDaConta = false } },
        )
    }
    private var avisoDaConta = false

    /**
     * Lista de categorias depois de uma conta (13/11/2026). Trocar de categoria só muda a cor da escolhida, mas o
     * notifyDataSetChanged religava todas as linhas visíveis (medido na Sala com atrace: ~24 linhas por troca, a maior
     * parte do tempo de layout da troca). Com os mesmos itens, só recolore as linhas na tela; com itens novos, refaz.
     */
    private fun atualizarCats(velhos: List<Item>) {
        if (velhos != itens) { adaptadorCats.notifyDataSetChanged(); return }
        val primeiro = listaCats.firstVisiblePosition
        for (i in 0 until listaCats.childCount) {
            val item = itens.getOrNull(primeiro + i) ?: continue
            ((listaCats.getChildAt(i) as? LinearLayout)?.getChildAt(0) as? TextView)?.setTextColor(corCat(item))
        }
    }

    private fun escolherCat(pos: Int, focarGrade: Boolean = false) {
        val it = itens.getOrNull(pos) ?: return
        if (it.chave == BUSCAR || it.chave == ORDEM || it.chave == OCULTAS || it.chave == FILTRO || it.chave == SORTEIO) return
        if (it.chave == chave) { if (focarGrade && naGrade.isNotEmpty() && !calculo.calculando) grade.requestFocus(); return }
        chave = it.chave
        atualizarCats(itens)   // mesma lista: só troca a cor da escolhida
        recalcular(ModoGrade.NOVA) { if (focarGrade && naGrade.isNotEmpty()) grade.requestFocus() }
    }

    // ---------------------------------------------------------------- interesses (OK segurado 2 s)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Só o OK com a grade em foco e a ficha fechada; o resto segue o caminho normal do Android.
        if (!SegurarOk.ehOk(e.keyCode) || painelFicha.visibility == View.VISIBLE || !grade.hasFocus()) {
            if (SegurarOk.ehOk(e.keyCode)) segurar.soltar()
            return super.dispatchKeyEvent(e)
        }
        return segurar.tratar(e,
            aoCurto = { naGrade.getOrNull(grade.selectedItemPosition)?.let { abrirFicha(it, trocar = true) } },
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { alternarInteresse(it) } })
    }

    private fun alternarInteresse(f: Filme) {
        val antes = interesses
        interesses = Vod.alternarInteresse(interesses, f.chave)
        if (interesses === antes) { avisoBreve(getString(R.string.filmes_interesses_cheio)); return }
        val virou = f.chave in interesses
        val copia = interesses
        Fundo.rodar { PlayerArmazem.definirInteresses(this, series = false, copia) }   // disco fora da thread da tela
        adaptadorGrade.notifyDataSetChanged()
        recalcular(if (chave == INTERESSES) ModoGrade.MANTER_LUGAR else ModoGrade.IGUAL)
        avisoBreve(getString(if (virou) R.string.filmes_interesse_pos else R.string.filmes_interesse_tirou, Vod.nomeLimpo(f.nome)))
    }

    private val esconderAviso = Runnable { aviso.visibility = View.GONE }

    /** O mesmo balão do "Carregando", por 2,5 s. */
    private fun avisoBreve(t: String) {
        aviso.text = t; aviso.visibility = View.VISIBLE
        tela.removeCallbacks(esconderAviso); tela.postDelayed(esconderAviso, 2_500)
    }

    private fun nomeOrdem(o: Vod.Ordem) = getString(when (o) {
        Vod.Ordem.RECENTES -> R.string.filmes_ordem_recentes
        Vod.Ordem.NOME -> R.string.filmes_ordem_nome
        Vod.Ordem.NOTA -> R.string.filmes_ordem_nota
        Vod.Ordem.ANO -> R.string.filmes_ordem_ano
    })

    private fun escolherOrdem() {
        val todas = Vod.Ordem.values()
        Estilo.lista(this, getString(R.string.filmes_ordem_titulo), todas.map { nomeOrdem(it) }, listOf(todas.indexOf(ordem))) { i ->
            ordem = todas[i]
            getSharedPreferences("player", MODE_PRIVATE).edit().putString("ordem_filmes", ordem.name).apply()
            recalcular(ModoGrade.NOVA)
        }
    }

    private fun buscar() {
        val campo = EditText(this).apply {
            setText(termo); setSelection(termo.length); hint = getString(R.string.filmes_busca_dica)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; isSingleLine = true
        }
        val aplicar = { t: String ->
            if (t.isNotEmpty()) {
                termo = t; chave = BUSCA
                recalcular(ModoGrade.NOVA) { if (resultados.isNotEmpty()) grade.requestFocus() }
            }
        }
        val d = CaixaTv(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_acao_buscar)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(campo) })
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aplicar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) { d.dismiss(); aplicar(campo.text.toString().trim()); true } else false
        }
        d.show()
    }

    private val adaptadorCats = object : BaseAdapter() {
        override fun getCount() = itens.size
        override fun getItem(p: Int) = itens[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val linha = (reuso as? LinearLayout) ?: LinearLayout(this@FilmesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(TextView(context).apply { textSize = Estilo.Leitura.CATEGORIA; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                // Folga entre o nome cortado e o número (12/11: "Reality show / Program…" encostava no 304).
                addView(TextView(context).apply { textSize = Estilo.Leitura.CONTAGEM; setTextColor(apagado) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }
            val it = itens[p]
            (linha.getChildAt(0) as TextView).apply {
                text = when (it.chave) {
                    BUSCAR, BUSCA -> "⌕  "; ORDEM -> "⇅  "; CONTINUAR -> "▶  "; INTERESSES -> "★  "; ASSISTIDOS -> "✓  "
                    FILTRO -> "☰  "; SORTEIO -> "✦  "; COLECAO -> "❒  "; else -> ""
                } + it.nome
                setTextColor(corCat(it))
            }
            (linha.getChildAt(1) as TextView).text = if (it.quantos < 0) "" else it.quantos.toString()
            return linha
        }
    }

    // ---------------------------------------------------------------- grade de pôsteres

    private class Card(val capa: ImageView, val nome: TextView, val barra: BarraTempo.Barra, val etiqueta: TextView, val estrela: TextView)

    private val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val f = naGrade[p]
            c.nome.text = Vod.nomeLimpo(f.nome)
            val pos = posicoes[f.chave]
            if (Vod.deveRetomar(pos) && pos!!.duracao > 0) {
                c.barra.visibility = View.VISIBLE; c.barra.fracao = BarraTempo.fracao(pos.ms, pos.duracao)
            } else c.barra.visibility = View.GONE
            // Etiqueta: "faltam 1 h 04" (a barra já dá a proporção; sem número de %), "Finalizado" (95% ou mais) ou
            // "Assistido" (servidor sem duração).
            val pct = Vod.progresso(pos)
            if (pct == null) c.etiqueta.visibility = View.GONE else {
                c.etiqueta.visibility = View.VISIBLE
                c.etiqueta.text = when (pct) { 100 -> getString(R.string.filmes_finalizado); 0 -> getString(R.string.filmes_assistido); else -> getString(R.string.tempo_faltam, BarraTempo.duracao(pos!!.duracao - pos.ms)) }
                (c.etiqueta.background as GradientDrawable).setColor(if (pct == 100) verdeEtiqueta else 0xD90B0F17.toInt())
            }
            c.estrela.visibility = if (f.chave in interesses) View.VISIBLE else View.GONE
            PlayerLogos.carregar(this@FilmesActivity, f.capa, c.capa, lado = 360)
            return v
        }
    }

    /** Cabeçalho do card: o nome (2 linhas no máximo) encostado embaixo, logo acima do pôster. */
    private fun cabecalhoCard(alturaDp: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(0, 0, 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, dp(alturaDp))
    }

    private fun novoCard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(6)) }
        val capa = CapaCartao(this, dp(8).toFloat())   // cinza só enquanto o pôster não chega (CapaCartao.kt)
        // Largura da coluna / altura 2:3 (pôster de cinema). A largura real vem do GridView; aqui uma altura fixa basta.
        // A capa vai num FrameLayout para a etiqueta (embaixo, à esquerda) e a estrela (em cima, à direita) ficarem por cima.
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val etiqueta = TextView(this).apply {
            textSize = 11f; typeface = fonteMedia; setTextColor(papel); setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPadding(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        val nome = TituloCard(this, Estilo.Leitura.CARD).apply { setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(cabecalhoCard(Estilo.Leitura.NOME_CARD_ALTURA).apply { addView(nome) })
        col.addView(moldura, LinearLayout.LayoutParams(-1, dp(Estilo.Leitura.POSTER_ALTURA)))
        val barra = BarraTempo.Barra(this)
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(5) })
        // 4 + nome + pôster + barra (5 + 5) + 6
        col.layoutParams = AbsListView.LayoutParams(-1, dp(4 + Estilo.Leitura.NOME_CARD_ALTURA + Estilo.Leitura.POSTER_ALTURA + 10 + 6))
        col.tag = Card(capa, nome, barra, etiqueta, estrela)
        return col
    }

    private fun mostrarFocado(f: Filme?) {
        if (f == null && chave == INTERESSES) { focado.text = getString(R.string.filmes_interesses_vazio); return }
        if (f == null && filtro.ativo) { focado.text = getString(R.string.filmes_filtro_vazio); return }
        focado.text = f?.let {
            val pos = posicoes[it.chave]?.takeIf { p -> p.duracao > 0 }
            Estilo.infoFoco(Vod.nomeLimpo(it.nome), it.nota, listOfNotNull(it.ano?.toString(), PlayerLista.nomeExibido(it.grupo).ifEmpty { null },
                pos?.takeIf { p -> Vod.deveRetomar(p) }?.let { p -> getString(R.string.tempo_faltam, BarraTempo.duracao(p.duracao - p.ms)) }))
        } ?: ""
    }

    // ---------------------------------------------------------------- ficha do filme

    /** O par na outra versão (dublado x legendado), só entre os filmes que a tela pode mostrar (parental). */
    private fun outraVersao(f: Filme): Filme? = Idioma.outraVersao(f, indiceVersoes, { it.nome }, { it.grupo }, { it.ano })
        ?.takeIf { mostrarOcultas || it.grupo.trim() !in ocultas }

    /**
     * [trocar]: veio da grade. Se a pessoa prefere a outra versão e ela existe, a ficha já abre nela (com um botão para
     * voltar). Não troca se este filme já foi começado: quem começou dublado continua de onde parou.
     */
    private fun abrirFicha(f0: Filme, manterFoco: Boolean = false, trocar: Boolean = false) {
        val f = if (!trocar || Vod.deveRetomar(posicoes[f0.chave])) f0 else outraVersao(f0)
            ?.takeIf { Idioma.versao(it.nome, it.grupo) == Idioma.versaoDesejada(prefsIdioma.para(f0.chave)) } ?: f0
        if (ficha !== f) fichaInfo = null   // outro filme: as cenas da ficha antiga não valem
        ficha = f
        desenharFicha(f, null, manterFoco)
        // Coleção (ideia 23): o nome-base de cada filme do catálogo visível, fora da tela (dezenas de milhares de nomes).
        if (colecaoFicha?.first !== f) {
            colecaoFicha = null
            val vis = filmes
            Fundo.rodar {
                val col = FilmesFiltro.colecao(vis, f)
                tela.post { if (!isDestroyed && ficha === f) { colecaoFicha = f to col; if (col.size > 1) desenharFicha(f, fichaInfo, manterFoco = true) } }
            }
        }
        Fundo.rodar {
            val info = PlayerCarga.fichaFilme(this, f)
            tela.post { if (!isDestroyed && ficha === f) { fichaInfo = info; desenharFicha(f, info, manterFoco = true) } }
        }
    }

    private fun fecharFicha() {
        ficha = null; painelFicha.visibility = View.GONE; painelFicha.removeAllViews()
        if (naGrade.isNotEmpty()) grade.requestFocus() else listaCats.requestFocus()
    }

    private fun desenharFicha(f: Filme, info: FichaFilme?, manterFoco: Boolean) {
        val focoAntes = if (manterFoco) (painelFicha.findFocus()?.tag as? String) else null
        painelFicha.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(48), dp(40), dp(48), dp(32)) }
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Estilo.BORDA) }
            clipToOutline = true
        }
        PlayerLogos.carregar(this, f.capa, capa, lado = 600)
        linha.addView(capa, LinearLayout.LayoutParams(dp(220), dp(330)))
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), 0, 0, 0) }
        texto.addView(TextView(this).apply { text = Vod.nomeLimpo(f.nome); textSize = Estilo.Leitura.TITULO; typeface = fonteMedia; setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(
            (f.ano?.toString() ?: info?.lancamento?.take(4)?.takeIf { it.isNotEmpty() }),
            info?.duracaoMin?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
            f.nota?.let { "★ %.1f".format(Locale.getDefault(), it) },
            info?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(f.grupo).ifEmpty { null },
            rotuloVersao(Idioma.versao(f.nome, f.grupo)),
        ).joinToString("   ·   ")
        texto.addView(TextView(this).apply { text = dados; textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPadding(0, dp(10), 0, 0) })

        // Botões: Continuar (se começou) + Do começo, ou só Assistir.
        val pos = posicoes[f.chave]
        // Começou e não terminou: barra de onde parou + "faltam 1 h 01 · termina às 22:41" acima dos botões (13/11/2026).
        if (Vod.deveRetomar(pos) && pos!!.duracao > 0) {
            val resta = (pos.duracao - pos.ms).coerceAtLeast(0)
            texto.addView(BarraTempo.Linha(this).apply {
                mostrar(BarraTempo.fracao(pos.ms, pos.duracao), BarraTempo.textoFaltam(context, resta, System.currentTimeMillis() + resta))
            }, LinearLayout.LayoutParams(dp(460), -2).apply { topMargin = dp(18) })
        }
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, dp(18)) }
        val primeiro: TextView
        if (Vod.deveRetomar(pos)) {
            primeiro = botao(getString(R.string.filmes_continuar_de, relogio(pos!!.ms)), "continuar") { tocar(f, doComeco = false) }
            botoes.addView(primeiro)
            botoes.addView(botao(getString(R.string.filmes_do_comeco), "comeco") { tocar(f, doComeco = true) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        } else {
            primeiro = botao(getString(R.string.filmes_assistir), "assistir") { tocar(f, doComeco = true) }
            botoes.addView(primeiro)
        }
        info?.trailer?.takeIf { it.isNotEmpty() }?.let { link ->
            botoes.addView(botao(getString(R.string.trailer), "trailer") { VodActivity.abrirTrailer(this, link) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        // O mesmo filme na outra versão do catálogo (dublado x legendado): abre a ficha dela.
        outraVersao(f)?.let { o ->
            val r = if (Idioma.versao(o.nome, o.grupo) == Idioma.Versao.LEGENDADA) R.string.idioma_versao_legendada else R.string.idioma_versao_dublada
            botoes.addView(botao(getString(r), "versao") { abrirFicha(o) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        // Ideia 23: os outros filmes com o mesmo nome-base (sequências, dublado e legendado) viram uma categoria na grade.
        colecaoFicha?.takeIf { it.first === f && it.second.size > 1 }?.second?.let { lista ->
            botoes.addView(botao(getString(R.string.filmes_colecao), "colecao") { abrirColecao(lista) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        texto.addView(botoes)

        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (info == null) corpo.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = Estilo.Leitura.AVISO; setTextColor(apagado) })
        info?.sinopse?.takeIf { it.isNotEmpty() }?.let { corpo.addView(TextView(this).apply { text = it; textSize = Estilo.Leitura.SINOPSE; setTextColor(papel); setLineSpacing(0f, 1.25f) }) }
        info?.diretor?.takeIf { it.isNotEmpty() }?.let { corpo.addView(rotuloValor(getString(R.string.filmes_direcao), it)) }
        info?.elenco?.takeIf { it.isNotEmpty() }?.let { corpo.addView(rotuloValor(getString(R.string.filmes_elenco), it)) }
        // A sinopse longa rola com ▼ (o ScrollView não pega foco; quem rola é a tecla, ver onKeyDown).
        val rolagem = ScrollView(this).apply { addView(corpo); isFocusable = false; tag = "rolagem" }
        texto.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        linha.addView(texto, LinearLayout.LayoutParams(0, -1, 1f))
        painelFicha.addView(linha, FrameLayout.LayoutParams(-1, -1))
        painelFicha.visibility = View.VISIBLE
        (focoAntes?.let { t -> botoes.findViewWithTag<View>(t) } ?: primeiro).requestFocus()
    }

    private fun abrirColecao(lista: List<Filme>) {
        colecao = Vod.nomeLimpo(lista.first().nome) to lista
        chave = COLECAO
        fecharFicha()
        recalcular(ModoGrade.NOVA) { if (naGrade.isNotEmpty()) grade.requestFocus() }
    }

    // ---------------------------------------------------------------- filtro combinado e sorteio (ideia 23)

    /** Cor do nome na lista de categorias: a escolhida e o Filtrar em vigor em âmbar (como o Filtros da TV ao vivo). */
    private fun corCat(it: Item) = if (it.chave == chave || (it.chave == FILTRO && filtro.ativo)) ambar else papel

    private fun nomeGenero(g: FilmesFiltro.Genero) = getString(when (g) {
        FilmesFiltro.Genero.ACAO -> R.string.genero_acao
        FilmesFiltro.Genero.AVENTURA -> R.string.genero_aventura
        FilmesFiltro.Genero.COMEDIA -> R.string.genero_comedia
        FilmesFiltro.Genero.DRAMA -> R.string.genero_drama
        FilmesFiltro.Genero.TERROR -> R.string.genero_terror
        FilmesFiltro.Genero.SUSPENSE -> R.string.genero_suspense
        FilmesFiltro.Genero.ROMANCE -> R.string.genero_romance
        FilmesFiltro.Genero.FICCAO -> R.string.genero_ficcao
        FilmesFiltro.Genero.FANTASIA -> R.string.genero_fantasia
        FilmesFiltro.Genero.ANIMACAO -> R.string.genero_animacao
        FilmesFiltro.Genero.INFANTIL -> R.string.genero_infantil
        FilmesFiltro.Genero.DOCUMENTARIO -> R.string.genero_documentario
        FilmesFiltro.Genero.CRIME -> R.string.genero_crime
        FilmesFiltro.Genero.GUERRA -> R.string.genero_guerra
        FilmesFiltro.Genero.FAROESTE -> R.string.genero_faroeste
        FilmesFiltro.Genero.MUSICAL -> R.string.genero_musical
    })

    private fun nomeEpoca(c: FilmesFiltro.Criterios): String? =
        c.ano?.toString() ?: c.decada?.let { getString(R.string.filmes_filtro_decada, it) }

    private fun nomeNota(n: Int) = "★ " + getString(R.string.filmes_filtro_nota_min, n)

    /** "Ação · Anos 1990 · ★ 7+ · não vistos": o que está filtrando, para o título da coluna. */
    private fun resumoFiltro(): String = listOfNotNull(
        filtro.genero?.let { nomeGenero(it) }, nomeEpoca(filtro), filtro.notaMin?.let { nomeNota(it) },
        if (filtro.naoVistos) getString(R.string.filmes_filtro_nao_vistos_curto) else null,
    ).joinToString(" · ")

    private fun aplicarFiltro(novo: FilmesFiltro.Criterios) {
        filtro = novo
        atualizarCats(itens)   // a cor do "Filtrar" muda
        recalcular(ModoGrade.NOVA)
    }

    // Gêneros e épocas do catálogo visível: contados uma vez por lista (fora da tela) e reaproveitados no menu.
    private var opcoesFiltro: Triple<List<Filme>, List<FilmesFiltro.Genero>, Pair<List<Int>, List<Int>>>? = null

    /** O menu do filtro (Estilo.lista, como o da Ordem). Cada escolha aplica na hora e volta a este menu. */
    private fun menuFiltro() {
        val vis = filmes
        val pronto = opcoesFiltro?.takeIf { it.first === vis }
        if (pronto == null) {
            val ano = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            Fundo.rodar {
                val o = Triple(vis, FilmesFiltro.generosPresentes(vis), FilmesFiltro.epocasPresentes(vis, ano))
                tela.post { if (!isDestroyed) { opcoesFiltro = o; menuFiltro() } }
            }
            return
        }
        val qualquer = getString(R.string.filmes_filtro_qualquer)
        val linhas = arrayListOf<CharSequence>(
            getString(R.string.filmes_filtro_genero, filtro.genero?.let { nomeGenero(it) } ?: qualquer),
            getString(R.string.filmes_filtro_epoca, nomeEpoca(filtro) ?: qualquer),
            getString(R.string.filmes_filtro_nota, filtro.notaMin?.let { nomeNota(it) } ?: qualquer),
            getString(R.string.filmes_filtro_nao_vistos, getString(if (filtro.naoVistos) R.string.filmes_filtro_sim else R.string.filmes_filtro_nao)),
        )
        if (filtro.ativo) linhas += getString(R.string.filmes_filtro_limpar)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo), linhas) { i ->
            when (i) {
                0 -> escolherGenero(pronto.second)
                1 -> escolherEpoca(pronto.third)
                2 -> escolherNota()
                3 -> { aplicarFiltro(filtro.copy(naoVistos = !filtro.naoVistos)); menuFiltro() }
                else -> aplicarFiltro(FilmesFiltro.Criterios())
            }
        }
    }

    private fun escolherGenero(generos: List<FilmesFiltro.Genero>) {
        if (generos.isEmpty()) { avisoBreve(getString(R.string.filmes_filtro_sem_genero)); return }
        val opcoes = listOf<FilmesFiltro.Genero?>(null) + generos
        val marcada = opcoes.indexOf(filtro.genero).coerceAtLeast(0)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo),
            opcoes.map { it?.let { g -> nomeGenero(g) } ?: getString(R.string.filmes_filtro_qualquer) }, listOf(marcada)) { i ->
            aplicarFiltro(filtro.copy(genero = opcoes[i])); menuFiltro()
        }
    }

    private fun escolherEpoca(epocas: Pair<List<Int>, List<Int>>) {
        // null = qualquer; ano positivo (2026); década negativa (-1990), para não confundir com um ano.
        val opcoes = listOf<Int?>(null) + epocas.first + epocas.second.map { -it }
        val atual = filtro.ano ?: filtro.decada?.let { -it }
        val marcada = opcoes.indexOf(atual).coerceAtLeast(0)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo), opcoes.map { o ->
            when { o == null -> getString(R.string.filmes_filtro_qualquer); o > 0 -> o.toString(); else -> getString(R.string.filmes_filtro_decada, -o) }
        }, listOf(marcada)) { i ->
            val o = opcoes[i]
            aplicarFiltro(filtro.copy(ano = o?.takeIf { it > 0 }, decada = o?.takeIf { it < 0 }?.let { -it })); menuFiltro()
        }
    }

    private fun escolherNota() {
        val opcoes = listOf<Int?>(null) + FilmesFiltro.NOTAS
        val marcada = opcoes.indexOf(filtro.notaMin).coerceAtLeast(0)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo), opcoes.map { n ->
            n?.let { nomeNota(it) } ?: getString(R.string.filmes_filtro_qualquer)
        }, listOf(marcada)) { i -> aplicarFiltro(filtro.copy(notaMin = opcoes[i])); menuFiltro() }
    }

    /** Sorteio: um filme da grade como está (categoria + filtro), preferindo os Interesses; abre a ficha dele. */
    private fun sortear() {
        val g = naGrade; val ints = interesses; val pos = posicoes
        Fundo.rodar {
            val f = FilmesFiltro.sortear(g, ints, pos)
            tela.post {
                if (isDestroyed) return@post
                if (f == null) avisoBreve(getString(R.string.filmes_sorteio_vazio)) else abrirFicha(f, trocar = true)
            }
        }
    }

    private fun rotuloVersao(v: Idioma.Versao): String? = when (v) {
        Idioma.Versao.LEGENDADA -> getString(R.string.idioma_legendado)
        Idioma.Versao.DUBLADA -> getString(R.string.idioma_dublado_curto)
        Idioma.Versao.DESCONHECIDA -> null
    }

    private fun tocar(f: Filme, doComeco: Boolean) {
        indoAoVideo = true
        // Capa e cena do card Filmes na HOME: só de filme de categoria visível (nada de adulto/oculto na HOME).
        val paraHome = f.grupo.trim() !in ocultas
        val capa = f.capa
        val info = fichaInfo?.takeIf { ficha === f }   // o "Assistir" mora na ficha: quase sempre já carregou
        val cenas = info?.cenas.orEmpty()
        // A ficha curta vai junto, para o destaque da HOME (nota, gênero, ano, duração, sinopse; 12/11/2026).
        val fichaHome = PlayerArmazem.FichaHome(Vod.nomeLimpo(f.nome), nota = f.nota, ano = f.ano, genero = info?.genero.orEmpty(),
            duracaoMin = info?.duracaoMin, sinopse = info?.sinopse.orEmpty(), chave = f.chave)
        if (paraHome) Fundo.rodar { PlayerArmazem.definirCapaHome(this, serie = false, poster = capa, cenas = cenas, ficha = fichaHome) }
        VodActivity.abrir(this, VodActivity.TIPO_FILME, f.fonteId, f.id, f.extensao, Vod.nomeLimpo(f.nome), doComeco,
            busca = LegendaExterna.Busca(LegendaExterna.nomeDeBusca(f.nome), f.ano))
    }

    private fun botao(texto: String, marca: String, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = Estilo.Leitura.BOTAO; typeface = fonteMedia; tag = marca
        setPadding(dp(26), dp(12), dp(26), dp(12))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Estilo.SUPERFICIE_ALTA) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.FUNDO, papel)))
        setOnClickListener { aoApertar() }
    }

    private fun rotuloValor(r: String, v: String) = TextView(this).apply {
        text = "$r: $v"; textSize = Estilo.Leitura.CREDITOS; setTextColor(suave); setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.15f)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (painelFicha.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { fecharFicha(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    painelFicha.findViewWithTag<ScrollView>("rolagem")?.smoothScrollBy(0, if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dp(80) else -dp(80))
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && listaCats.hasFocus().not() && itens.isNotEmpty()) { listaCats.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    private fun estiloLista(l: AbsListView) {
        l.isVerticalScrollBarEnabled = false
        PlayerFoco.molduraSoComFoco(l, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Estilo.SUPERFICIE_ALTA); setStroke(dp(2), papel) })
        l.setDrawSelectorOnTop(l is GridView)   // no pôster a moldura vai por cima (a capa cobre o fundo)
        l.cacheColorHint = Color.TRANSPARENT
        if (l is ListView) l.divider = null
    }

    private fun coluna(titulo: TextView, lista: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; addView(titulo); addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = Estilo.Leitura.ROTULO; typeface = Fontes.manrope(600); letterSpacing = 0.06f; setTextColor(Estilo.TEXTO_3)
        setPadding(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun relogio(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun vidro(raio: Int) = GradientDrawable().apply { cornerRadius = dp(raio).toFloat(); setColor(0xD90B0F17.toInt()); setStroke(dp(1), Estilo.SUPERFICIE_ALTA) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

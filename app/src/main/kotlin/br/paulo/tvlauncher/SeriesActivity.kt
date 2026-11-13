package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Player universal, séries do Xtream (06/11/2026): catálogo de séries (mesma arrumação dos filmes: categorias à esquerda,
 * pôsteres à direita) e a ficha da série com temporadas e episódios.
 *
 * Na ficha: o botão principal oferece "Continuar T2E5 de 12:34" (ou o episódio seguinte, se o último terminou, ou
 * "Assistir T1E1"); embaixo, as temporadas e a lista de episódios com a barra do que já foi visto. Quando um episódio
 * vai até o fim, o próximo começa sozinho (VodActivity devolve "terminou").
 */
class SeriesActivity : Activity() {
    private companion object {
        const val BUSCAR = "*buscar"
        const val ORDEM = "*ordem"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val ASSISTIDOS = "*assistidos"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
        const val OCULTAS = "*ocultas"
        const val CODIGO_VIDEO = 7
    }

    private data class Item(val chave: String, val nome: String, val quantos: Int)

    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val suave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val ambar = Estilo.ATENCAO
    private val coral = 0xFFFF5A4E.toInt()
    private val verdeEtiqueta = 0xE62E7D4F.toInt()
    private val fonteMedia = Fontes.manrope(500)

    private var series: List<Serie> = emptyList()      // o que a tela mostra (sem as categorias ocultas, até o PIN)
    private var todas: List<Serie> = emptyList()       // o catálogo inteiro, como veio do servidor
    private var ocultas: Set<String> = emptySet()      // categorias escondidas (adultas, escondidas, controle parental)
    private var mostrarOcultas = false                 // true só depois do PIN, como no player da TV ao vivo
    private var indoAoVideo = false                    // saiu para a VodActivity: o PIN continua valendo na volta
    private val janelas by lazy { PlayerJanelas(this) }
    private var ultimos: Map<String, UltimoEpisodio> = emptyMap()
    private var posicoes: Map<String, Posicao> = emptyMap()
    private var interesses: Set<String> = emptySet()
    private var progresso: Map<String, ProgressoSerie> = emptyMap()   // episódios vistos / total, por série
    private val segurar = SegurarOk()
    private var ordem = Vod.Ordem.RECENTES
    private var itens: List<Item> = emptyList()
    private var chave = TODOS
    private var termo = ""
    private var resultados: List<Serie> = emptyList()
    private var naGrade: List<Serie> = emptyList()

    // Ficha aberta
    private var aberta: Serie? = null
    // Dublado x legendado (Idioma, 11/11/2026): a mesma série nas duas versões do catálogo, e o que a pessoa prefere.
    private var indiceVersoes: Map<String, List<Serie>> = emptyMap()
    private var prefsIdioma = Idioma.Prefs()
    private var fichaAberta: FichaSerie? = null
    private var temporada = 1
    private var tocando: Episodio? = null   // o episódio que foi para a VodActivity (para o "próximo")

    private lateinit var listaCats: ListView
    private lateinit var grade: GridView
    private lateinit var cabecalho: TextView
    private lateinit var focado: TextView
    private lateinit var aviso: TextView
    private lateinit var painel: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences("player", MODE_PRIVATE).getString("ordem_series", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        // Coerência visual parte 2 (12/11/2026): o grafite e a margem da HOME (eram FUNDO liso e 32/26 dp).
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(context) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(18)) }
        listaCats = ListView(this).apply { estilo(this) }
        colunas.addView(coluna(rotulo(getString(R.string.series_titulo)), listaCats), LinearLayout.LayoutParams(dp(210), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cabecalho = rotulo("")
        direita.addView(cabecalho)
        grade = GridView(this).apply {
            numColumns = Estilo.Leitura.GRADE_COLUNAS; horizontalSpacing = dp(12); verticalSpacing = dp(Estilo.Leitura.GRADE_VAO); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estilo(this); setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        // A informação do item em foco fica EM CIMA da grade, como o destaque da HOME (era uma linha de 13 sp embaixo).
        focado = TextView(this).apply { textSize = Estilo.Leitura.FOCADO; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(4), dp(10), 0, dp(10)) }
        direita.addView(focado, 1)   // logo abaixo do cabeçalho da categoria
        // Dica fixa das teclas, no mesmo jeito da lista de canais do player (player_lista_teclas: 11sp, cor apagada).
        direita.addView(TextView(this).apply { text = getString(R.string.vod_grade_teclas); textSize = Estilo.Leitura.DICA; setTextColor(apagado); setPadding(dp(8), dp(6), 0, 0) })
        colunas.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))
        // Focável: enquanto a ficha carrega, o foco sai da grade de baixo (senão ▲▼ mexeriam nela por trás).
        painel = FrameLayout(this).apply { visibility = View.GONE; background = Estilo.fundoTela(context); isFocusable = true }
        raiz.addView(painel, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xD90B0F17.toInt()) }
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.series_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        listaCats.adapter = adaptadorCats
        grade.adapter = adaptadorGrade
        listaCats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                tela.removeCallbacks(trocarCat); if (listaCats.hasFocus()) tela.postDelayed(trocarCat, 180)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaCats.setOnItemClickListener { _, _, pos, _ ->
            when (itens.getOrNull(pos)?.chave) {
                BUSCAR -> buscar()
                ORDEM -> escolherOrdem()
                OCULTAS -> alternarOcultas()
                else -> escolherCat(pos, focarGrade = true)
            }
        }
        grade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                mostrarFocado(naGrade.getOrNull(pos))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grade.setOnItemClickListener { _, _, pos, _ -> naGrade.getOrNull(pos)?.let { abrirSerie(it, trocar = true) } }
        carregar()
    }

    override fun onResume() {
        super.onResume()
        indoAoVideo = false
        // O controle parental pode ter mudado nas Configurações (igual à tela de Filmes): relê as categorias ocultas.
        val lista = todas
        if (lista.isNotEmpty()) Fundo.rodar {
            val oc = VodFiltro.ocultas(lista.map { it.grupo }, PlayerArmazem.gruposEscondidos(this),
                PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))
            val pi = IdiomaPlayer.prefs(this)   // trocar o áudio num episódio muda a escolha da série
            tela.post {
                if (isDestroyed) return@post
                prefsIdioma = pi
                if (oc == ocultas) return@post
                ocultas = oc
                if (aberta?.let { !mostrarOcultas && it.grupo.trim() in oc } == true) fecharFicha()
                refiltrarOcultas()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // O PIN vale só enquanto a tela está na frente (igual ao player): saiu para a HOME ou outro app, tranca de novo.
        // Ir assistir um episódio daqui não conta, senão a cada episódio teria de digitar o PIN.
        if (mostrarOcultas && !indoAoVideo) {
            mostrarOcultas = false
            if (aberta?.let { it.grupo.trim() in ocultas } == true) fecharFicha()
            refiltrarOcultas()
        }
    }

    override fun onDestroy() { calculo.fechar(); tela.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun refiltrarOcultas() = recalcular(ModoGrade.NOVA)

    /** "Mostrar categorias ocultas" pede o PIN do painel (o mesmo do player); "Esconder de novo" não pede nada. */
    private fun alternarOcultas() {
        if (mostrarOcultas) { mostrarOcultas = false; refiltrarOcultas() }
        else janelas.pedirPin(aoLiberar = { mostrarOcultas = true; refiltrarOcultas() }, aviso = { avisoBreve(it) })
    }

    private fun carregar() {
        Fundo.rodar {
            // Abre pelo cache na hora; vencido, atualiza em segundo plano para a próxima vez (mesma regra de Filmes).
            val cache = PlayerCarga.series(this, soCache = true)
            val s = cache.ifEmpty { PlayerCarga.series(this) }
            if (cache.isNotEmpty() && PlayerCarga.catalogoVelho(this, series = true))
                Fundo.rodar("series-atualizar") { runCatching { PlayerCarga.series(this) } }
            // Categorias ocultas: a mesma regra do player (adultas, escondidas) + as bloqueadas no controle parental.
            val oc = VodFiltro.ocultas(s.map { it.grupo }, PlayerArmazem.gruposEscondidos(this),
                PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))
            val u = PlayerArmazem.ultimosEpisodios(this)
            val p = PlayerArmazem.posicoes(this)
            val i = PlayerArmazem.interesses(this, series = true)
            val pr = PlayerArmazem.progressoSeries(this)
            val pi = IdiomaPlayer.prefs(this)
            tela.post {
                if (isDestroyed) return@post
                todas = s; ocultas = oc; ultimos = u; posicoes = p; interesses = i; progresso = pr; prefsIdioma = pi
                if (s.isEmpty()) { aviso.text = getString(R.string.series_vazio); return@post }
                // Aberta pela busca única da HOME (12/11/2026): já entra com o termo buscado.
                intent.getStringExtra(BuscaUnica.EXTRA_TERMO)?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> termo = t; chave = BUSCA }
                // A 1ª conta (ocultas, contagens, ordem) também sai da thread da tela; o "Carregando" fica até ela chegar.
                recalcular(ModoGrade.NOVA, inicial = true) { aviso.visibility = View.GONE; grade.requestFocus() }
                // O índice das versões sai depois da grade na tela (é a parte demorada; a ficha funciona sem ele).
                Fundo.rodar {
                    val idx = Idioma.indice(s, { it.nome }, { it.grupo })
                    tela.post { if (!isDestroyed && todas === s) indiceVersoes = idx }
                }
            }
        }
    }

    // ---------------------------------------------------------------- catálogo

    private val trocarCat = Runnable { escolherCat(listaCats.selectedItemPosition) }

    /** O que fazer com a grade quando a conta chegar. */
    private enum class ModoGrade { NOVA, MANTER_LUGAR, IGUAL }

    /** Resultado de uma conta: tudo que a tela precisa, calculado fora dela. */
    private class Estado(val series: List<Serie>, val resultados: List<Serie>, val itens: List<Item>, val chave: String,
                         val grade: List<Serie>?)

    private val calculo = CalculoTela(tela, "series-conta")

    // Caches da conta (só a thread da [calculo] lê e escreve). A chave é a própria lista (===): lista nova = refaz.
    private var cacheVisiveis: Triple<List<Serie>, Set<String>, List<Serie>>? = null
    private var cacheBusca: Triple<List<Serie>, String, List<Serie>>? = null
    private var cacheOrdem: Triple<List<Serie>, Vod.Ordem, List<Serie>>? = null
    private var cacheGrupos: Pair<List<Serie>, List<Pair<String, Int>>>? = null
    private var avisoDaConta = false

    /**
     * Refaz, FORA da thread da tela, as categorias ocultas, a busca, as contagens das categorias e (conforme [modo]) a
     * grade (igual à FilmesActivity). Pedidos seguidos descartam os velhos. [inicial]: escolhe a categoria de entrada.
     */
    private fun recalcular(modo: ModoGrade, inicial: Boolean = false, depois: (() -> Unit)? = null) {
        val todasS = todas; val ocultasS = if (mostrarOcultas) emptySet() else ocultas; val mostrar = mostrarOcultas
        val termoS = termo; val ultS = ultimos; val posS = posicoes; val intS = interesses; val progS = progresso
        val ordemS = ordem; val chaveS = chave; val temOcultas = ocultas.isNotEmpty()
        val rotulo = { id: Int -> getString(id) }
        val antesSel = grade.selectedItemPosition
        calculo.pedir(
            calcular = {
                val vis = cacheVisiveis?.takeIf { it.first === todasS && it.second == ocultasS }?.third
                    ?: (if (mostrar) todasS else VodFiltro.visiveis(todasS, ocultasS) { it.grupo }).also { cacheVisiveis = Triple(todasS, ocultasS, it) }
                val res = if (termoS.isEmpty()) emptyList() else cacheBusca?.takeIf { it.first === vis && it.second == termoS }?.third
                    ?: Series.buscar(vis, termoS).also { cacheBusca = Triple(vis, termoS, it) }
                val cont = Series.continuar(vis, ultS, progS)
                val ch0 = if (inicial && chaveS != BUSCA) (if (cont.isNotEmpty()) CONTINUAR else TODOS) else chaveS
                val l = ArrayList<Item>()
                l += Item(BUSCAR, rotulo(R.string.player_acao_buscar), -1)
                l += Item(ORDEM, getString(R.string.filmes_ordem, nomeOrdem(ordemS)), -1)
                // Só aparece quando há categoria oculta neste catálogo (mesmo texto do menu de filtros do player).
                if (temOcultas) l += Item(OCULTAS, rotulo(if (mostrar) R.string.player_ocultas_esconder else R.string.player_ocultas_mostrar), -1)
                if (cont.isNotEmpty()) l += Item(CONTINUAR, rotulo(R.string.filmes_continuar), cont.size)
                val ints = Vod.interesses(vis, intS) { it.chave }
                l += Item(INTERESSES, rotulo(R.string.filmes_interesses), ints.size)
                val vistas = Series.assistidos(vis, ultS, posS, progS)
                if (vistas.isNotEmpty()) l += Item(ASSISTIDOS, rotulo(R.string.filmes_assistidos), vistas.size)
                if (termoS.isNotEmpty()) l += Item(BUSCA, getString(R.string.player_cat_busca, termoS), res.size)
                l += Item(TODOS, rotulo(R.string.player_cat_todos), vis.size)
                val grupos = cacheGrupos?.takeIf { it.first === vis }?.second ?: Series.grupos(vis).also { cacheGrupos = vis to it }
                grupos.forEach { (g, n) -> l += Item("g:$g", PlayerLista.nomeExibido(g).ifEmpty { rotulo(R.string.player_cat_outros) }, n) }
                val ch = if (l.none { it.chave == ch0 }) TODOS else ch0
                val g = if (modo == ModoGrade.IGUAL && ch == chaveS) null else {
                    // A ordem do catálogo inteiro fica guardada: uma categoria é o filtro dela (filtrar mantém a ordem).
                    fun ordenadas() = cacheOrdem?.takeIf { it.first === vis && it.second == ordemS }?.third
                        ?: Series.ordenar(vis, ordemS).also { cacheOrdem = Triple(vis, ordemS, it) }
                    when {
                        ch == CONTINUAR -> cont
                        ch == ASSISTIDOS -> vistas
                        ch == INTERESSES -> ints                 // a última marcada primeiro
                        ch == BUSCA -> res
                        ch.startsWith("g:") -> { val alvo = ch.removePrefix("g:"); ordenadas().filter { it.grupo.trim() == alvo } }
                        else -> ordenadas()
                    }
                }
                Estado(vis, res, l, ch, g)
            },
            aplicar = { e ->
                if (!isDestroyed) {
                    series = e.series; resultados = e.resultados; itens = e.itens; chave = e.chave
                    adaptadorCats.notifyDataSetChanged()
                    if (!listaCats.hasFocus()) listaCats.setSelection(itens.indexOfFirst { it.chave == chave }.coerceAtLeast(0))
                    if (e.grade != null) {
                        naGrade = e.grade
                        adaptadorGrade.notifyDataSetChanged()
                        // A linha do rodapé passa a mostrar o 1º item da categoria nova (o setSelection(0) não dispara
                        // onItemSelected quando a posição já era 0, e o texto ficava velho).
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isNotEmpty()) {
                            val p = antesSel.coerceIn(0, naGrade.size - 1); grade.setSelection(p); mostrarFocado(naGrade[p])
                        } else {
                            grade.setSelection(0); mostrarFocado(naGrade.firstOrNull())
                        }
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isEmpty() && grade.hasFocus()) listaCats.requestFocus()   // esvaziou
                    }
                    val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
                    cabecalho.text = getString(R.string.series_cabecalho, nome.uppercase(Locale.getDefault()), naGrade.size)
                    depois?.invoke()
                }
            },
            demorou = { if (!isDestroyed) { aviso.text = getString(R.string.series_carregando); aviso.visibility = View.VISIBLE; avisoDaConta = true } },
            fim = { if (avisoDaConta) { aviso.visibility = View.GONE; avisoDaConta = false } },
        )
    }

    private fun escolherCat(pos: Int, focarGrade: Boolean = false) {
        val it = itens.getOrNull(pos) ?: return
        if (it.chave == BUSCAR || it.chave == ORDEM || it.chave == OCULTAS) return
        if (it.chave == chave) { if (focarGrade && naGrade.isNotEmpty() && !calculo.calculando) grade.requestFocus(); return }
        chave = it.chave
        adaptadorCats.notifyDataSetChanged()
        recalcular(ModoGrade.NOVA) { if (focarGrade && naGrade.isNotEmpty()) grade.requestFocus() }
    }

    private fun mostrarFocado(s: Serie?) {
        if (s == null && chave == INTERESSES) { focado.text = getString(R.string.filmes_interesses_vazio); return }
        focado.text = s?.let {
            Estilo.infoFoco(Vod.nomeLimpo(it.nome), it.nota, listOfNotNull(it.ano?.toString(), PlayerLista.nomeExibido(it.grupo).ifEmpty { null }))
        } ?: ""
    }

    private fun nomeOrdem(o: Vod.Ordem) = getString(when (o) {
        Vod.Ordem.RECENTES -> R.string.series_ordem_recentes
        Vod.Ordem.NOME -> R.string.filmes_ordem_nome
        Vod.Ordem.NOTA -> R.string.filmes_ordem_nota
        Vod.Ordem.ANO -> R.string.filmes_ordem_ano
    })

    private fun escolherOrdem() {
        val todas = Vod.Ordem.values()
        Estilo.lista(this, getString(R.string.filmes_ordem_titulo), todas.map { nomeOrdem(it) }, listOf(todas.indexOf(ordem))) { i ->
            ordem = todas[i]
            getSharedPreferences("player", MODE_PRIVATE).edit().putString("ordem_series", ordem.name).apply()
            recalcular(ModoGrade.NOVA)
        }
    }

    private fun buscar() {
        val campo = EditText(this).apply {
            setText(termo); setSelection(termo.length); hint = getString(R.string.series_busca_dica)
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
            .setNegativeButton(android.R.string.cancel, null).create()
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
            val linha = (reuso as? LinearLayout) ?: LinearLayout(this@SeriesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(TextView(context).apply { textSize = Estilo.Leitura.CATEGORIA; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                // Folga entre o nome cortado e o número (12/11: "Reality show / Program…" encostava no 304).
                addView(TextView(context).apply { textSize = Estilo.Leitura.CONTAGEM; setTextColor(apagado) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }
            val it = itens[p]
            (linha.getChildAt(0) as TextView).apply {
                text = when (it.chave) { BUSCAR, BUSCA -> "⌕  "; ORDEM -> "⇅  "; CONTINUAR -> "▶  "; INTERESSES -> "★  "; ASSISTIDOS -> "✓  "; else -> "" } + it.nome
                setTextColor(if (it.chave == chave) ambar else papel)
            }
            (linha.getChildAt(1) as TextView).text = if (it.quantos < 0) "" else it.quantos.toString()
            return linha
        }
    }

    private class Card(val capa: ImageView, val nome: TextView, val sub: TextView, val estrela: TextView, val etiqueta: TextView)

    private val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val s = naGrade[p]
            c.nome.text = Vod.nomeLimpo(s.nome)
            c.sub.text = ultimos[s.chave]?.let { "T${it.temporada}E${it.numero}" }.orEmpty()
            c.sub.visibility = if (c.sub.text.isEmpty()) View.GONE else View.VISIBLE
            // Mesma etiqueta dos filmes: "% dos episódios vistos" ou "Finalizado". Sem conta ainda (ficha nunca aberta
            // depois desta versão) mas com episódio visto: "Assistido".
            val pct = Series.percentual(s, progresso[s.chave])
            val visto = pct != null || ultimos[s.chave]?.let { Vod.assistido(posicoes["${s.fonteId}:e${it.episodioId}"]) } == true
            if (!visto) c.etiqueta.visibility = View.GONE else {
                c.etiqueta.visibility = View.VISIBLE
                c.etiqueta.text = when (pct) { 100 -> getString(R.string.filmes_finalizado); null -> getString(R.string.filmes_assistido); else -> "$pct%" }
                (c.etiqueta.background as GradientDrawable).setColor(if (pct == 100) verdeEtiqueta else 0xD90B0F17.toInt())
            }
            c.estrela.visibility = if (s.chave in interesses) View.VISIBLE else View.GONE
            PlayerLogos.carregar(this@SeriesActivity, s.capa, c.capa, lado = 360)
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
        // Capa num FrameLayout para a estrela dos interesses ficar por cima (canto de cima, à direita).
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPadding(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        val etiqueta = TextView(this).apply {
            textSize = 11f; typeface = fonteMedia; setTextColor(papel); setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD90B0F17.toInt()) }
        }
        moldura.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        // Nome e "T2E5" em cima do pôster, encostados nele (a linha do episódio some quando não há).
        val nome = TituloCard(this, Estilo.Leitura.CARD).apply { setTextColor(papel); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val sub = TextView(this).apply { textSize = 12f; setTextColor(ambar); maxLines = 1 }
        col.addView(cabecalhoCard(Estilo.Leitura.NOME_CARD_ALTURA + Estilo.Leitura.SUB_CARD_ALTURA).apply { addView(nome); addView(sub) })
        col.addView(moldura, LinearLayout.LayoutParams(-1, dp(Estilo.Leitura.POSTER_ALTURA)))
        // 4 + nome e episódio + pôster + 6
        col.layoutParams = AbsListView.LayoutParams(-1, dp(4 + Estilo.Leitura.NOME_CARD_ALTURA + Estilo.Leitura.SUB_CARD_ALTURA + Estilo.Leitura.POSTER_ALTURA + 6))
        col.tag = Card(capa, nome, sub, estrela, etiqueta)
        return col
    }

    // ---------------------------------------------------------------- ficha da série

    /** A mesma série na outra versão (dublada x legendada), só entre as que a tela pode mostrar (parental). */
    private fun outraVersao(s: Serie): Serie? = Idioma.outraVersao(s, indiceVersoes, { it.nome }, { it.grupo }, { it.ano })
        ?.takeIf { mostrarOcultas || it.grupo.trim() !in ocultas }

    /**
     * [trocar]: veio da grade. Se a pessoa prefere a outra versão e ela existe, a ficha já abre nela (com um botão para
     * voltar). Não troca série já começada: quem vinha vendo dublada continua nela.
     */
    private fun abrirSerie(s0: Serie, trocar: Boolean = false) {
        val s = if (!trocar || ultimos[s0.chave] != null) s0 else outraVersao(s0)
            ?.takeIf { Idioma.versao(it.nome, it.grupo) == Idioma.versaoDesejada(prefsIdioma.para(s0.chave)) } ?: s0
        aberta = s; fichaAberta = null
        desenharFicha()
        Fundo.rodar {
            val f = PlayerCarga.fichaSerie(this, s)
            tela.post {
                if (isDestroyed || aberta !== s) return@post
                fichaAberta = f
                atualizarProgresso(s, f)
                temporada = ultimos[s.chave]?.temporada?.takeIf { f?.temporadas?.containsKey(it) == true } ?: f?.temporadas?.keys?.firstOrNull() ?: 1
                desenharFicha()
            }
        }
    }

    private fun fecharFicha() {
        aberta = null; fichaAberta = null; painel.visibility = View.GONE; painel.removeAllViews()
        if (naGrade.isNotEmpty()) grade.requestFocus() else listaCats.requestFocus()
    }

    /** Recalcula "vistos / total" com a ficha aberta (só ela tem os episódios) e grava se mudou. */
    private fun atualizarProgresso(s: Serie, f: FichaSerie?) {
        f ?: return
        val novo = Series.progressoDaFicha(s, f, posicoes, progresso[s.chave]) ?: return
        if (novo == progresso[s.chave] || (novo.vistos == 0 && s.chave !in progresso)) return   // nada visto: não guarda
        // Guarda só séries que ainda estão no "último episódio" (até 200) mais esta: o arquivo não cresce sem fim.
        progresso = progresso.filterKeys { it in ultimos || it == s.chave } + (s.chave to novo)
        val copia = progresso
        Fundo.rodar { PlayerArmazem.definirProgressoSeries(this, copia) }
        adaptadorGrade.notifyDataSetChanged(); recalcular(ModoGrade.IGUAL)
    }

    // ---------------------------------------------------------------- interesses (OK segurado 2 s, igual aos filmes)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!SegurarOk.ehOk(e.keyCode) || painel.visibility == View.VISIBLE || !grade.hasFocus()) {
            if (SegurarOk.ehOk(e.keyCode)) segurar.soltar()
            return super.dispatchKeyEvent(e)
        }
        return segurar.tratar(e,
            aoCurto = { naGrade.getOrNull(grade.selectedItemPosition)?.let { abrirSerie(it, trocar = true) } },
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { alternarInteresse(it) } })
    }

    private fun alternarInteresse(s: Serie) {
        val antes = interesses
        interesses = Vod.alternarInteresse(interesses, s.chave)
        if (interesses === antes) { avisoBreve(getString(R.string.filmes_interesses_cheio)); return }
        val virou = s.chave in interesses
        val copia = interesses
        Fundo.rodar { PlayerArmazem.definirInteresses(this, series = true, copia) }
        adaptadorGrade.notifyDataSetChanged()
        recalcular(if (chave == INTERESSES) ModoGrade.MANTER_LUGAR else ModoGrade.IGUAL)
        avisoBreve(getString(if (virou) R.string.filmes_interesse_pos else R.string.filmes_interesse_tirou, Vod.nomeLimpo(s.nome)))
    }

    private val esconderAviso = Runnable { aviso.visibility = View.GONE }

    private fun avisoBreve(t: String) {
        aviso.text = t; aviso.visibility = View.VISIBLE
        tela.removeCallbacks(esconderAviso); tela.postDelayed(esconderAviso, 2_500)
    }

    private fun desenharFicha(focarEpisodio: Int? = null) {
        val s = aberta ?: return
        val f = fichaAberta
        painel.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(48), dp(28), dp(48), dp(20)) }   // 34/24 -> 28/20: sobra altura para os episódios com a letra maior
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Estilo.BORDA) }; clipToOutline = true
        }
        PlayerLogos.carregar(this, s.capa, capa, lado = 600)
        linha.addView(capa, LinearLayout.LayoutParams(dp(180), dp(270)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        col.addView(TextView(this).apply { text = Vod.nomeLimpo(s.nome); textSize = Estilo.Leitura.TITULO; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(s.ano?.toString(), f?.temporadas?.size?.let { resources.getQuantityString(R.plurals.series_temporadas, it, it) },
            s.nota?.let { "★ %.1f".format(Locale.getDefault(), it) }, f?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(s.grupo).ifEmpty { null },
            when (Idioma.versao(s.nome, s.grupo)) {
                Idioma.Versao.LEGENDADA -> getString(R.string.idioma_legendado)
                Idioma.Versao.DUBLADA -> getString(R.string.idioma_dublado_curto)
                Idioma.Versao.DESCONHECIDA -> null
            }).joinToString("   ·   ")
        col.addView(TextView(this).apply { text = dados; textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPadding(0, dp(8), 0, 0) })
        f?.sinopse?.takeIf { it.isNotEmpty() }?.let {
            col.addView(TextView(this).apply { text = it; textSize = Estilo.Leitura.SINOPSE; setTextColor(papel); setLineSpacing(0f, 1.15f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) })
        }

        if (f == null) {
            col.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = Estilo.Leitura.AVISO; setTextColor(apagado); setPadding(0, dp(14), 0, 0) })
        } else if (f.temporadas.isEmpty()) {
            col.addView(TextView(this).apply { text = getString(R.string.series_sem_episodios); textSize = Estilo.Leitura.AVISO; setTextColor(suave); setPadding(0, dp(14), 0, 0) })
        } else {
            // Botão principal.
            val sugestao = Series.sugestao(f, s.fonteId, ultimos[s.chave], posicoes)
            val principal = sugestao?.let { (ep, retomar) ->
                val txt = if (retomar) getString(R.string.series_continuar, ep.rotulo, relogio(posicoes[ep.chave(s.fonteId)]?.ms ?: 0))
                    else getString(R.string.series_assistir, ep.rotulo)
                botao(txt) { tocar(s, ep, doComeco = !retomar) }
            }
            // Botão principal e, se houver, o trailer ao lado (11/11/2026).
            val linhaBotoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            principal?.let { linhaBotoes.addView(it) }
            f.trailer.takeIf { it.isNotEmpty() }?.let { link ->
                linhaBotoes.addView(botao(getString(R.string.trailer)) { VodActivity.abrirTrailer(this, link) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (principal != null) dp(12) else 0 })
            }
            // A mesma série na outra versão do catálogo (dublada x legendada): abre a ficha dela.
            outraVersao(s)?.let { o ->
                val r = if (Idioma.versao(o.nome, o.grupo) == Idioma.Versao.LEGENDADA) R.string.idioma_versao_legendada else R.string.idioma_versao_dublada
                linhaBotoes.addView(botao(getString(r)) { abrirSerie(o) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (linhaBotoes.childCount > 0) dp(12) else 0 })
            }
            if (linhaBotoes.childCount > 0) col.addView(linhaBotoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })

            // Temporadas.
            val temps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            f.temporadas.keys.forEach { t ->
                temps.addView(botao(getString(R.string.series_temporada, t), pequeno = true, marcado = t == temporada) {
                    temporada = t; desenharFicha(focarEpisodio = -1)
                }.apply { tag = "t$t" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(temps) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

            // Episódios da temporada.
            val eps = f.temporadas[temporada].orEmpty()
            val lista = ListView(this).apply { estilo(this) }
            lista.adapter = object : BaseAdapter() {
                override fun getCount() = eps.size
                override fun getItem(p: Int) = eps[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View = linhaEpisodio(s, eps[p], reuso)
            }
            lista.setOnItemClickListener { _, _, p, _ -> eps.getOrNull(p)?.let { ep ->
                tocar(s, ep, doComeco = !Vod.deveRetomar(posicoes[ep.chave(s.fonteId)]))
            } }
            // OK segurado num episódio: marcar como visto até aqui, ou como não visto (11/11/2026, visto no 1 Play).
            lista.setOnItemLongClickListener { _, _, p, _ -> eps.getOrNull(p)?.let { ep -> menuEpisodio(s, f, ep, p) }; true }
            col.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

            painel.post {
                when {
                    focarEpisodio == -1 -> temps.findViewWithTag<View>("t$temporada")?.requestFocus()
                    focarEpisodio != null -> { lista.requestFocus(); lista.setSelection(focarEpisodio) }
                    else -> principal?.requestFocus() ?: lista.requestFocus()
                }
            }
        }
        linha.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        painel.addView(linha, FrameLayout.LayoutParams(-1, -1))
        painel.visibility = View.VISIBLE
        if (f == null) painel.requestFocus()
    }

    private fun menuEpisodio(s: Serie, f: FichaSerie, ep: Episodio, posicao: Int) {
        val opcoes = listOf(getString(R.string.series_visto_ate_aqui, ep.rotulo), getString(R.string.series_nao_visto))
        Estilo.lista(this, "${ep.rotulo} · ${Series.tituloCurto(s.nome, ep.titulo)}", opcoes) { i ->
            posicoes = if (i == 0) Series.marcarVistosAte(f, s.fonteId, ep, posicoes, System.currentTimeMillis())
                else Series.desmarcar(s.fonteId, ep, posicoes)
            val copia = posicoes
            Fundo.rodar { PlayerArmazem.definirPosicoes(this, copia) }
            atualizarProgresso(s, f)
            desenharFicha(focarEpisodio = posicao)
        }
    }

    private fun linhaEpisodio(s: Serie, ep: Episodio, reuso: View?): View {
        val v = (reuso as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            val topo = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM; typeface = Fontes.manrope(600); setTextColor(ambar) }, LinearLayout.LayoutParams(dp(48), -2))
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(apagado) })
            addView(topo)
            addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(48), dp(3), 0, 0) })
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressTintList = ColorStateList.valueOf(coral); progressBackgroundTintList = ColorStateList.valueOf(Estilo.VIDRO_BORDA)
            }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6); marginStart = dp(48) })
        }
        val topo = v.getChildAt(0) as LinearLayout
        (topo.getChildAt(0) as TextView).text = ep.numero.toString()
        (topo.getChildAt(1) as TextView).text = Series.tituloCurto(s.nome, ep.titulo)
        (topo.getChildAt(2) as TextView).text = ep.duracaoMin?.let { "$it min" }.orEmpty()
        (v.getChildAt(1) as TextView).apply { text = ep.sinopse; visibility = if (ep.sinopse.isEmpty()) View.GONE else View.VISIBLE }
        val barra = v.getChildAt(2) as ProgressBar
        val pos = posicoes[ep.chave(s.fonteId)]
        if (pos != null && pos.duracao > 0) { barra.visibility = View.VISIBLE; barra.progress = (pos.ms * 1000 / pos.duracao).toInt() }
        else barra.visibility = View.INVISIBLE
        return v
    }

    private fun tocar(s: Serie, ep: Episodio, doComeco: Boolean) {
        tocando = ep
        indoAoVideo = true
        val agora = System.currentTimeMillis()
        ultimos = Series.registrarUltimo(ultimos, s.chave, UltimoEpisodio(ep.id, ep.temporada, ep.numero, ep.titulo, ep.extensao, agora))
        val copia = ultimos
        // Capa e cena do card Séries na HOME: só de série de categoria visível (nada de adulto/oculto na HOME).
        val paraHome = s.grupo.trim() !in ocultas
        val capa = s.capa
        val cenas = fichaAberta?.cenas.orEmpty()
        // A ficha curta vai junto, para o destaque da HOME: série, "T1 E4" e a sinopse do episódio (12/11/2026).
        val fichaHome = PlayerArmazem.FichaHome(Vod.nomeLimpo(s.nome), episodio = "T${ep.temporada} E${ep.numero}", nota = s.nota,
            ano = s.ano, genero = fichaAberta?.genero.orEmpty(), duracaoMin = ep.duracaoMin,
            sinopse = ep.sinopse.ifEmpty { fichaAberta?.sinopse.orEmpty() }, chave = ep.chave(s.fonteId))
        Fundo.rodar {
            PlayerArmazem.definirUltimosEpisodios(this, copia)
            if (paraHome) PlayerArmazem.definirCapaHome(this, serie = true, poster = capa, cenas = cenas, ficha = fichaHome)
        }
        VodActivity.abrirComResultado(this, CODIGO_VIDEO, VodActivity.TIPO_EPISODIO, s.fonteId, ep.id, ep.extensao,
            "${Vod.nomeLimpo(s.nome)} · ${ep.rotulo} · ${Series.tituloCurto(s.nome, ep.titulo)}", doComeco, chaveIdioma = s.chave,
            busca = LegendaExterna.Busca(LegendaExterna.nomeDeBusca(s.nome), temporada = ep.temporada, episodio = ep.numero))
    }

    // API antiga (deprecated), mas é a que existe numa Activity comum, sem AndroidX.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_VIDEO) return
        val s = aberta; val f = fichaAberta; val ep = tocando
        // Posições mudaram: relê e redesenha a ficha (barras e botão principal).
        Fundo.rodar {
            val p = PlayerArmazem.posicoes(this)
            tela.post {
                if (isDestroyed) return@post
                posicoes = p
                if (s != null && f != null) atualizarProgresso(s, f)
                val terminou = resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
                val prox = if (terminou && s != null && f != null && ep != null) Series.proximo(f, ep) else null
                if (prox != null && s != null) { temporada = prox.temporada; desenharFicha(); tocar(s, prox, doComeco = true) }
                else { adaptadorGrade.notifyDataSetChanged(); recalcular(ModoGrade.IGUAL); if (aberta != null) desenharFicha() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (painel.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) { fecharFicha(); return true }
        if (painel.visibility != View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK && !listaCats.hasFocus() && itens.isNotEmpty()) { listaCats.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    private fun botao(texto: String, pequeno: Boolean = false, marcado: Boolean = false, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = if (pequeno) Estilo.Leitura.BOTAO_PEQUENO else Estilo.Leitura.BOTAO; typeface = fonteMedia
        setPadding(dp(if (pequeno) 18 else 26), dp(if (pequeno) 8 else 12), dp(if (pequeno) 18 else 26), dp(if (pequeno) 8 else 12))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (marcado) 0x4DF5B544 else Estilo.SUPERFICIE_ALTA) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.FUNDO, papel)))
        setOnClickListener { aoApertar() }
    }

    private fun estilo(l: AbsListView) {
        l.isVerticalScrollBarEnabled = false
        PlayerFoco.molduraSoComFoco(l, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Estilo.SUPERFICIE_ALTA); setStroke(dp(2), papel) })
        l.setDrawSelectorOnTop(l is GridView)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Controle parental (07/11/2026): escolher os canais bloqueados. Só abre pelas Configurações do launcher, depois do PIN
 * (a Activity não é exportada: nenhum outro app a chama). Duas colunas, como a lista de canais do player:
 *  - esquerda, as categorias: OK bloqueia ou libera a categoria INTEIRA (vale também para canal novo que entrar nela);
 *  - direita, os canais da categoria em foco: OK bloqueia ou libera só aquele canal.
 * Cada OK grava na hora (PlayerArmazem.definirParental); o player relê ao voltar para a frente.
 *
 * Modo infantil (10/11/2026, com [EXTRA_INFANTIS]): a MESMA tela marca as categorias infantis, em vez de abrir uma tela
 * nova. A esquerda junta as categorias da TV ao vivo, dos Filmes e das Séries (a marca vale nas três); a direita só mostra
 * o que tem na categoria em foco, para ajudar a decidir.
 */
class BloqueioCanaisActivity : Activity() {
    companion object {
        const val EXTRA_INFANTIS = "infantis"
        private const val MAX_AMOSTRA = 200   // nomes guardados por categoria para a coluna da direita (memória da HOME)
    }

    private val modoInfantis by lazy { intent.getBooleanExtra(EXTRA_INFANTIS, false) }
    private var categoriasInf: List<CategoriasInfantis.Categoria> = emptyList()
    private var amostras: Map<String, List<String>> = emptyMap()   // categoria -> nomes de canais, filmes e séries
    private var daAmostra: List<String> = emptyList()

    private val tela = Handler(Looper.getMainLooper())
    // Cores do sistema de design (Estilo).
    private val papel = Estilo.TEXTO
    private val suave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val coral = Estilo.ERRO
    private val verde = Estilo.ACENTO
    private val fonteLeve = Fontes.manrope(200)
    private val fonteMedia = Fontes.manrope(500)

    private var regras = ControleParental.Regras()
    private var canais: List<Canal> = emptyList()
    private var grupos: List<Pair<String, Int>> = emptyList()   // nome da categoria -> quantos canais
    private var grupoAtual: String? = null                       // null = todas
    private var daCategoria: List<Canal> = emptyList()

    private lateinit var subtitulo: TextView
    private lateinit var listaCats: ListView
    private lateinit var listaCanais: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(72), dp(40), dp(72), dp(24))
        }
        raiz.addView(TextView(this).apply { text = if (modoInfantis) getString(R.string.infantil_titulo) else getString(R.string.lbl_canais_bloqueados); textSize = 32f; typeface = fonteLeve; setTextColor(papel) })
        subtitulo = TextView(this).apply { textSize = 15f; setTextColor(suave); text = if (modoInfantis) getString(R.string.infantil_carregando) else getString(R.string.bc_carregando) }
        raiz.addView(subtitulo)
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listaCats = lista()
        listaCanais = lista()
        colunas.addView(coluna(getString(R.string.bc_categorias), listaCats), LinearLayout.LayoutParams(0, -1, 0.38f))
        colunas.addView(coluna(if (modoInfantis) getString(R.string.infantil_coluna_itens) else getString(R.string.bc_canais), listaCanais), LinearLayout.LayoutParams(0, -1, 0.62f).apply { marginStart = dp(20) })
        raiz.addView(colunas, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        // Dica fixa das teclas, no mesmo jeito da lista de canais do player (11sp, cor apagada).
        raiz.addView(TextView(this).apply {
            text = if (modoInfantis) getString(R.string.infantil_dica)
                else getString(R.string.bc_dica)
            textSize = 11f; setTextColor(apagado); setPadding(0, dp(10), 0, 0)
        })
        setContentView(raiz)

        listaCats.adapter = adaptadorCats
        listaCanais.adapter = adaptadorCanais
        listaCats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = escolherCategoria(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listaCats.setOnItemClickListener { _, _, pos, _ ->
            if (modoInfantis) { alternarInfantil(pos); return@setOnItemClickListener }
            val g = grupos.getOrNull(pos - 1)?.first ?: return@setOnItemClickListener   // "Todas" não bloqueia nada
            mudar(ControleParental.alternarGrupo(regras, g))
        }
        listaCanais.setOnItemClickListener { _, _, pos, _ ->
            if (modoInfantis) { subtitulo.text = getString(R.string.infantil_so_categoria); return@setOnItemClickListener }
            val c = daCategoria.getOrNull(pos) ?: return@setOnItemClickListener
            if (c.grupo.trim() in regras.grupos) { subtitulo.text = getString(R.string.bc_categoria_bloqueada, c.grupo.trim()); return@setOnItemClickListener }
            mudar(ControleParental.alternarCanal(regras, c.id))
        }
        if (modoInfantis) carregarInfantis() else carregar()
    }

    /**
     * Modo infantil: as categorias dos três catálogos. Canais como na outra tela (cache; sem cache, baixa); filmes e séries
     * só do cache que o player gravou: baixar o catálogo inteiro aqui pesaria na memória da HOME. Fora da thread da tela.
     */
    private fun carregarInfantis() {
        Fundo.rodar {
            val tv = PlayerCarga.canais(this)
            PlayerArmazem.migrarIdsAntigos(this, tv)   // antes de gravar o parental: canal bloqueado pelo id antigo não se perde
            val r = PlayerArmazem.parental(this)
            val filmes = runCatching { PlayerCarga.filmes(this, soCache = true) }.getOrDefault(emptyList())
            val series = runCatching { PlayerCarga.series(this, soCache = true) }.getOrDefault(emptyList())
            val cats = CategoriasInfantis.juntar(tv.map { it.grupo }, filmes.map { it.grupo }, series.map { it.grupo })
            // Só uma amostra dos nomes de cada categoria: a lista inteira de filmes não precisa ficar na memória da HOME.
            val am = HashMap<String, MutableList<String>>()
            fun guardar(grupo: String, nome: String) {
                val l = am.getOrPut(grupo.trim()) { ArrayList() }
                if (l.size < MAX_AMOSTRA) l += nome
            }
            tv.forEach { guardar(it.grupo, "TV · ${it.nome}") }   // o tipo em texto, sem emoji (sistema de design)
            filmes.forEach { guardar(it.grupo, getString(R.string.bc_filme, it.nome)) }
            series.forEach { guardar(it.grupo, getString(R.string.bc_serie, it.nome)) }
            tela.post {
                if (isDestroyed) return@post
                regras = r; categoriasInf = cats; amostras = am
                if (cats.isEmpty()) { subtitulo.text = getString(R.string.infantil_vazio); return@post }
                atualizarSubtitulo()
                adaptadorCats.notifyDataSetChanged()
                escolherCategoria(0)
                listaCats.requestFocus()
                listaCats.setSelection(0)
            }
        }
    }

    private fun alternarInfantil(pos: Int) {
        val g = categoriasInf.getOrNull(pos)?.nome ?: return
        val novas = ControleParental.alternarInfantil(regras, g)
        if (novas == regras) { subtitulo.text = getString(R.string.infantil_limite); return }
        if (!PlayerArmazem.definirParental(this, novas)) { subtitulo.text = getString(R.string.bc_nao_gravou); return }
        regras = novas
        atualizarSubtitulo()
        adaptadorCats.notifyDataSetChanged()
    }

    private fun carregar() {
        Fundo.rodar {
            val lidos = PlayerCarga.canais(this)   // do cache (12 h); sem cache, baixa
            PlayerArmazem.migrarIdsAntigos(this, lidos)   // canal bloqueado pelo id antigo (URL) vira o id estável
            val r = PlayerArmazem.parental(this)
            val gs = lidos.groupingBy { it.grupo.trim() }.eachCount().entries
                .sortedBy { it.key.lowercase() }.map { it.key to it.value }
            tela.post {
                if (isDestroyed) return@post
                regras = r; canais = lidos; grupos = gs
                if (lidos.isEmpty()) { subtitulo.text = getString(R.string.bc_nenhum_canal); return@post }
                atualizarSubtitulo()
                adaptadorCats.notifyDataSetChanged()
                escolherCategoria(0)
                listaCats.requestFocus()
                listaCats.setSelection(0)
            }
        }
    }

    private fun escolherCategoria(pos: Int) {
        if (modoInfantis) {
            daAmostra = categoriasInf.getOrNull(pos)?.let { amostras[it.nome] }.orEmpty()
            adaptadorCanais.notifyDataSetChanged()
            return
        }
        grupoAtual = if (pos == 0) null else grupos.getOrNull(pos - 1)?.first
        daCategoria = if (grupoAtual == null) canais else canais.filter { it.grupo.trim() == grupoAtual }
        adaptadorCanais.notifyDataSetChanged()
    }

    private fun mudar(novas: ControleParental.Regras) {
        if (novas == regras) { subtitulo.text = getString(R.string.bc_limite); return }
        if (!PlayerArmazem.definirParental(this, novas)) { subtitulo.text = getString(R.string.bc_nao_gravou); return }
        regras = novas
        atualizarSubtitulo()
        adaptadorCats.notifyDataSetChanged()
        adaptadorCanais.notifyDataSetChanged()
    }

    private fun atualizarSubtitulo() {
        if (modoInfantis) {
            val n = categoriasInf.count { it.nome in regras.infantis }
            subtitulo.text = getString(if (regras.infantil) R.string.infantil_resumo_ligado else R.string.infantil_resumo_desligado, n, categoriasInf.size)
            return
        }
        val n = canais.count { it.id in regras.canais || it.grupo.trim() in regras.grupos }
        val estado = getString(if (regras.ligado) R.string.bc_parental_on else R.string.bc_parental_off)
        subtitulo.text = getString(R.string.bc_resumo, n, canais.size, regras.grupos.size, estado)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // ▶ nas categorias vai para os canais; ◀ nos canais volta para as categorias (como na lista do player).
        val temDireita = if (modoInfantis) daAmostra.isNotEmpty() else daCategoria.isNotEmpty()
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && listaCats.hasFocus() && temDireita) { listaCanais.requestFocus(); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && listaCanais.hasFocus()) { listaCats.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- listas

    private val adaptadorCats = object : BaseAdapter() {
        override fun getCount() = if (modoInfantis) categoriasInf.size else if (canais.isEmpty()) 0 else grupos.size + 1
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, pai: ViewGroup?): View {
            val t = (v as? TextView) ?: item()
            if (modoInfantis) {
                // Marcada = aparece no modo infantil (verde, com ✓); o resto some quando o modo está ligado.
                val cat = categoriasInf[p]
                val marcada = cat.nome in regras.infantis
                t.text = "${if (marcada) "✓ " else ""}${CategoriasInfantis.rotulo(cat)}"
                t.setTextColor(if (marcada) verde else papel)
                return t
            }
            if (p == 0) { t.text = getString(R.string.bc_todas, canais.size); t.setTextColor(papel); return t }
            val (g, n) = grupos[p - 1]
            val bloq = g in regras.grupos
            t.text = "${g.ifEmpty { getString(R.string.bc_outros) }}  ($n)${if (bloq) "  · " + getString(R.string.bc_bloqueada) else ""}"   // bloqueio dito em texto e em cor
            t.setTextColor(if (bloq) coral else papel)
            return t
        }
    }

    private val adaptadorCanais = object : BaseAdapter() {
        override fun getCount() = if (modoInfantis) daAmostra.size else daCategoria.size
        override fun getItem(p: Int): Any = if (modoInfantis) daAmostra[p] else daCategoria[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, pai: ViewGroup?): View {
            val t = (v as? TextView) ?: item()
            if (modoInfantis) { t.text = daAmostra[p]; t.setTextColor(papel); return t }
            val c = daCategoria[p]
            val porGrupo = c.grupo.trim() in regras.grupos
            val bloq = porGrupo || c.id in regras.canais
            t.text = "${c.numero}  ${c.nome}${if (porGrupo) "  · " + getString(R.string.bc_bloqueado_cat) else if (bloq) "  · " + getString(R.string.bc_bloqueado) else ""}"
            t.setTextColor(if (bloq) coral else papel)
            return t
        }
    }

    private fun item() = TextView(this).apply {
        textSize = 17f; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun lista() = ListView(this).apply {
        divider = null; dividerHeight = dp(4); isVerticalScrollBarEnabled = false
        itemsCanFocus = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), vidro(true))
            addState(intArrayOf(), GradientDrawable())
        }
        setDrawSelectorOnTop(false)
    }

    private fun coluna(titulo: String, l: ListView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = vidro(false)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(TextView(this@BloqueioCanaisActivity).apply {
            text = titulo; textSize = 13f; typeface = fonteMedia; setTextColor(suave); letterSpacing = 0.12f; setPadding(dp(16), dp(4), 0, dp(8))
        })
        addView(l, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Lista (ListView) não deixa escurecer o texto do item focado: aqui o foco é a superfície alta com anel branco. */
    private fun vidro(focado: Boolean): GradientDrawable =
        if (focado) Estilo.fundo(this, Estilo.SUPERFICIE_ALTA, Estilo.RAIO, Estilo.FOCO, 2) else Estilo.superficie(this, false)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

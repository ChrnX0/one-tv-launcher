package br.paulo.tvlauncher.player

import android.app.Activity
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.CalculoTela
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.ModoSimples
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.VigiaAtualizacao
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.input.SegurarOk
import br.paulo.tvlauncher.panel.PainelCatalogo
import br.paulo.tvlauncher.settings.Fontes

/**
 * O que Filmes e Séries têm de igual (refatoração de 21/11/2026): antes eram duas cópias de ~20 funções (busca, ordem,
 * categorias ocultas com PIN, pular para a letra, Trakt/Simkl, idades do TMDB, o aviso breve), que já tinham começado a
 * divergir. Aqui fica o estado comum e essas funções; cada tela continua com o que é dela (ficha, cartão, a conta da grade).
 *
 * [kind] ("filmes"/"series") monta as chaves já gravadas nas TVs ("ordem_filmes", "ultima_series_<perfil>"...): não mudar.
 */
abstract class CatalogActivity<T>(protected val kind: String, protected val isSeries: Boolean) : Activity() {
    protected companion object {
        const val MIN_PULAR = 30   // grade menor que isso se anda no controle mesmo
        const val ESPERA_EXTERNO_MS = 400L   // quanto a ficha do servidor espera pela de fora (com MDBList/Simkl ligados ela vem 150-400 ms depois, medido 16/11)
        const val BUSCAR = "*buscar"
        const val FILTRO = "*filtro"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val ASSISTIDOS = "*assistidos"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
    }

    /** O que fazer com a grade quando a conta chegar. A ordem importa: do mais forte ao mais fraco. */
    protected enum class ModoGrade { NOVA, MANTER_LUGAR, IGUAL }

    protected val tela = android.os.Handler(android.os.Looper.getMainLooper())
    protected val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    protected val suave = Estilo.TEXTO_2
    protected val apagado = Estilo.TEXTO_3
    protected val ambar = Estilo.ATENCAO
    protected val verdeEtiqueta = 0xE62E7D4F.toInt()
    protected val fonteMedia = Fontes.nunito(500)

    protected var catalogo: List<T> = emptyList()      // o catálogo inteiro, como veio do servidor
    /** Listas de fora escolhidas (Trakt, MDBList) com os itens: viram categorias em LISTAS (fatia 3). */
    @Volatile protected var listasFora: List<Pair<SincronizarFilmes.ListaFora, List<SincronizarFilmes.Item>>> = emptyList()
    protected var ocultas: Set<String> = emptySet()      // categorias escondidas (adultas, escondidas, controle parental)
    @Volatile protected var porIdade: IdadesTmdb.Filtro? = null   // ideia 91: modo infantil com idade máxima
    protected var mostrarOcultas = false                 // true só depois do PIN, como no player da TV ao vivo
    protected var indoAoVideo = false                    // saiu para a VodActivity: o PIN continua valendo na volta
    protected val janelas by lazy { PlayerJanelas(this) }
    protected var posicoes: Map<String, Posicao> = emptyMap()
    protected var interesses: Set<String> = emptySet()
    /** De que perfil são os interesses e posições na memória (ideia 11): trocou de perfil, a tela se refaz ao voltar. */
    protected var perfilCarregado = ""
    protected val segurar = SegurarOk()
    protected var ordem = Vod.Ordem.RECENTES
    protected var itens: List<ColunaCategorias.Linha> = emptyList()   // as três partes da coluna, juntas (para achar o nome)
    /** "N novos" por categoria (14/11/2026): o item mais novo que a pessoa já tinha visto no catálogo, da visita anterior. */
    protected var marcoNovos = 0L
    /** A última categoria aberta (por perfil), lida no carregar: a tela volta nela (acréscimo aprovado, 14/11/2026). */
    protected var ultimaSalva: String? = null
    protected var chave = TODOS
    protected var termo = ""
    protected var resultados: List<T> = emptyList()
    protected var naGrade: List<T> = emptyList()
    // Dublado x legendado (Idioma, 11/11/2026): o mesmo título nas duas versões do catálogo, e o que a pessoa prefere.
    protected var indiceVersoes: Map<String, List<T>> = emptyMap()
    protected var prefsIdioma = Idioma.Prefs()

    protected lateinit var coluna: ColunaCategorias
    /** Modo simples da HOME (ModoSimples): o player segue, com menos linhas, letra maior e entrada no Continuar. */
    protected val simples by lazy { ModoSimples.ligado(this) }
    protected lateinit var grade: GridView
    protected lateinit var aviso: TextView
    /** A grade e a coluna, atrás da ficha. Escondidas enquanto a ficha está aberta: a ficha cobre a tela toda e,
     *  visíveis, eram desenhadas de novo a cada quadro (fundo, 10 pôsteres, a coluna), o que pesava ao abrir (15/11/2026). */
    protected lateinit var telaDeTras: View
    protected val calculo = CalculoTela(tela, "$kind-conta")

    // ---------------------------------------------------------------- o que cada tela diz de si

    protected abstract val adaptadorGrade: BaseAdapter
    protected abstract val buscaDica: Int
    protected abstract val ordemRecentes: Int
    protected abstract fun groupOf(t: T): String
    protected abstract fun nameOf(t: T): String
    protected abstract fun tmdbOf(t: T): Int?
    /** A data que conta para os "novos" (filme adicionado, série atualizada). */
    protected abstract fun addedOf(t: T): Long
    protected abstract fun outraVersao(t: T): T?
    protected abstract fun titulosDoCatalogo(vis: List<T>): List<SincronizarFilmes.Titulo>
    protected abstract fun recalcular(modoPedido: ModoGrade, inicialPedido: Boolean = false, depoisPedido: (() -> Unit)? = null)
    protected abstract fun menuFiltroUnico()
    protected abstract fun mostrarFocado(t: T?)
    /** A categoria [k] vira a "última aberta"? (busca e coleção são de passagem). */
    protected open fun lembrarCategoria(k: String) = k != BUSCA

    override fun onDestroy() {
        calculo.fechar()
        if (!VigiaAtualizacao.fechada(this)) coluna.parar()   // modo seguro: o onCreate parou antes de criar a coluna
        tela.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Preferências por perfil (ideia 11): a última categoria e o marco dos "novos" são de cada pessoa.
    protected fun prefsPlayer() = getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE)
    protected fun chavePref(nome: String) = "${nome}_${kind}_" + Perfil.chaveDados(this)
    private fun guardarOrdem() = prefsPlayer().edit().putString("ordem_$kind", ordem.name).apply()

    /** Guarda o marco dos "novos": na próxima visita, conta o que entrou depois de agora. */
    protected fun guardarMarco() {
        val lista = catalogo; val antes = marcoNovos
        if (lista.isEmpty()) return
        Fundo.rodar {
            val m = ColunaRegras.novoMarco(lista, antes) { addedOf(it) }
            if (m != antes) prefsPlayer().edit().putLong(chavePref("marco"), m).apply()
        }
    }

    /** OK numa linha da coluna: as ações abrem a janela delas; categoria abre e leva o foco para a grade. */
    protected fun okNaColuna(k: String) {
        when (k) {
            BUSCAR -> buscar()
            FILTRO -> menuFiltroUnico()
            else -> escolherCat(k, focarGrade = true)
        }
    }

    protected fun refiltrarOcultas() = recalcular(ModoGrade.NOVA)

    /** "Mostrar categorias ocultas" pede o PIN do painel (o mesmo do player); "Esconder de novo" não pede nada. */
    protected fun alternarOcultas() {
        if (mostrarOcultas) { mostrarOcultas = false; refiltrarOcultas() }
        else janelas.pedirPin(aoLiberar = { mostrarOcultas = true; refiltrarOcultas() }, aviso = { avisoBreve(it) })
    }

    /**
     * Ao abrir: as listas escolhidas (do disco já; da rede se venceram) e a sincronização com Trakt/Simkl (no máximo a
     * cada 20 min). Mudou algo aqui: relê Interesses e posições e refaz a coluna sem perder o lugar.
     */
    protected fun sincronizarFora(lista: List<T>, forcar: Boolean) {
        val escolhidas = ServicosSync.listas(this)
        val algumaConta = ServicosSync.ativo(this, ServicosFilmes.Servico.TRAKT) || ServicosSync.ativo(this, ServicosFilmes.Servico.SIMKL)
        if (escolhidas.isEmpty() && !algumaConta) { listasFora = emptyList(); return }
        Fundo.rodar("$kind-fora") {
            if (escolhidas.isNotEmpty()) {
                listasFora = escolhidas.map { it to ServicosSync.itensGuardados(this, it) }
                tela.post { if (!isDestroyed) recalcular(ModoGrade.IGUAL) }
                listasFora = escolhidas.map { it to ServicosSync.itens(this, it, forcar) }
                tela.post { if (!isDestroyed) recalcular(ModoGrade.IGUAL) }
            }
            if (!algumaConta) return@rodar
            val tipo = if (isSeries) SincronizarFilmes.Tipo.SERIE else SincronizarFilmes.Tipo.FILME
            val m = ServicosSync.sincronizar(this, tipo, titulosDoCatalogo(lista), forcar)
            if (!m.interesses && !m.posicoes) return@rodar
            val i = PlayerArmazem.interesses(this, series = isSeries); val p = PlayerArmazem.posicoes(this)
            tela.post {
                if (isDestroyed) return@post
                interesses = i; posicoes = p
                adaptadorGrade.notifyDataSetChanged()
                recalcular(ModoGrade.MANTER_LUGAR)
            }
        }
    }

    /** Ideia 91: com idade máxima, pergunta ao TMDB a classificação dos que faltam (infantis primeiro) e refaz a grade. */
    protected fun classificarIdades(lista: List<T>) {
        val fi = porIdade ?: return
        Fundo.rodar("$kind-idades") {
            val ids = lista.filter { groupOf(it).trim() !in fi.duras }.sortedBy { if (groupOf(it).trim() in fi.infantis) 0 else 1 }.mapNotNull { tmdbOf(it) }
            if (IdadesTmdb.classificar(this, serie = isSeries, ids) > 0) {
                val novo = IdadesTmdb.filtro(this, lista.map { groupOf(it) }, serie = isSeries)
                tela.post { if (!isDestroyed && catalogo === lista) { porIdade = novo; recalcular(ModoGrade.MANTER_LUGAR) } }
            }
        }
    }

    protected fun escolherCat(k: String, focarGrade: Boolean = false) {
        if (k == BUSCAR || k == FILTRO || itens.none { it.chave == k }) return
        if (k == chave) { if (focarGrade && naGrade.isNotEmpty() && !calculo.calculando) grade.requestFocus(); return }
        chave = k
        coluna.escolher(k)   // mesma lista: só troca a cor da escolhida
        if (lembrarCategoria(k)) { ultimaSalva = k; prefsPlayer().edit().putString(chavePref("ultima"), k).apply() }
        recalcular(ModoGrade.NOVA) { if (focarGrade && naGrade.isNotEmpty()) grade.requestFocus() }
    }

    private val esconderAviso = Runnable { aviso.visibility = View.GONE }

    /** O mesmo balão do "Carregando", por 2,5 s. */
    protected fun avisoBreve(t: String) {
        aviso.text = t; aviso.visibility = View.VISIBLE
        tela.removeCallbacks(esconderAviso); tela.postDelayed(esconderAviso, 2_500)
    }

    protected fun nomeOrdem(o: Vod.Ordem) = getString(when (o) {
        Vod.Ordem.RECENTES -> ordemRecentes
        Vod.Ordem.NOME -> R.string.filmes_ordem_nome
        Vod.Ordem.NOTA -> R.string.filmes_ordem_nota
        Vod.Ordem.ANO -> R.string.filmes_ordem_ano
    })

    protected fun escolherOrdem() {
        val todas = Vod.Ordem.values()
        Estilo.lista(this, getString(R.string.filmes_ordem_titulo), todas.map { nomeOrdem(it) }, listOf(todas.indexOf(ordem))) { i ->
            ordem = todas[i]
            guardarOrdem()
            recalcular(ModoGrade.NOVA)
        }
    }

    protected fun buscar() {
        val campo = EditText(this).apply {
            setText(termo); setSelection(termo.length); hint = getString(buscaDica)
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
            .setView(FrameLayout(this).apply { setPaddingRelative(dp(20), dp(10), dp(20), 0); addView(campo) })
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aplicar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) { d.dismiss(); aplicar(campo.text.toString().trim()); true } else false
        }
        d.show()
    }

    // ---------------------------------------------------------------- pular para uma letra (acréscimo 3)

    /** Só em Todos ou numa categoria (as listas que seguem a ordem do catálogo), e com bastante item. */
    protected fun podePularLetra() = naGrade.size >= MIN_PULAR && (chave == TODOS || chave.startsWith("g:"))

    /** As letras grandes por cima; escolhida a letra, põe em ordem de nome (se não estiver) e pula para ela. */
    protected fun pularParaLetra() {
        if (!podePularLetra()) return
        PainelCatalogo.escolherLetra(this) { letra ->
            val pular = {
                val atual = naGrade
                // Busca binária: a grade em ordem de nome está ordenada pela mesma chave.
                val i = ColunaRegras.indiceDaLetra(atual.size, { Vod.chaveNome(nameOf(atual[it])) }, letra)
                if (i >= 0) { grade.requestFocus(); grade.setSelection(i); mostrarFocado(atual[i]) }
            }
            if (ordem == Vod.Ordem.NOME) pular() else {
                ordem = Vod.Ordem.NOME
                guardarOrdem()
                recalcular(ModoGrade.NOVA) { pular() }
            }
        }
    }

    /**
     * Legendado sem a versão dublada junto (selo LEG no pôster). Espera o índice de versões: nos primeiros segundos
     * (índice ainda vazio) todo legendado parecia sem par e ganhava o selo (visto na Sala, 19/11/2026). Índice vazio de
     * verdade = nenhum nome com marca, então nenhum LEG para mostrar.
     */
    protected fun soLegendado(t: T): Boolean =
        indiceVersoes.isNotEmpty() && Idioma.versao(nameOf(t), groupOf(t)) == Idioma.Versao.LEGENDADA && outraVersao(t) == null

    // ---------------------------------------------------------------- peças de tela

    protected fun coluna(titulo: TextView, lista: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; addView(titulo); addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    protected fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = Estilo.Leitura.ROTULO; typeface = Fontes.nunito(600); letterSpacing = 0.06f; setTextColor(Estilo.TEXTO_3)
        setPaddingRelative(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, lista completa (06/11/2026): a tela de canais que abre com OK por cima do vídeo.
 *
 *   [ categorias ]  [ canais da categoria         ]  [ detalhe do canal em foco ]
 *     Todos 312       12  (logo) Canal  programa ▬     logo, programa de agora, horário,
 *     Favoritos 4     13  (logo) Canal  programa ▬     barra, descrição e o que vem depois
 *     Recentes 9      ...
 *     Esportes 42
 *
 * As duas listas são ListView (do próprio Android): só cria as linhas que cabem na tela, então 20 mil canais custam o
 * mesmo que 20. O ◀ ▶ entre as colunas é a navegação de foco padrão do Android (a Activity devolve false para essas teclas).
 *
 * Teclas: ▲▼ andam; ◀ ▶ trocam de coluna; OK num canal assiste; OK segurado 2 s (ou MENU) põe/tira dos favoritos;
 * OK segurado numa categoria esconde/mostra a categoria; VOLTAR fecha.
 * No topo da coluna de categorias ficam três ações: Buscar, Filtros e Guia de programação (as janelas são da PlayerActivity).
 * Filmes e Séries não aparecem aqui: abrem pelos cards da HOME (06/11/2026, pedido do dono).
 */
/** Vermelho da etiqueta AO VIVO: branco sobre ele passa de 4,5:1 (legível de longe). */
private const val COR_AO_VIVO = 0xFFC62828.toInt()

class PlayerListaTela(
    private val act: Activity,
    private val aoAssistir: (Canal, String) -> Unit,      // canal escolhido + categoria em que estava
    private val aoFavoritar: (Canal) -> Unit,
    private val aoBuscar: () -> Unit,
    private val aoFiltros: () -> Unit,
    private val aoOcultarGrupo: (String) -> Unit,   // nome do grupo (vazio = "Outros")
    private val aoGuia: (List<Canal>, String) -> Unit,   // guia em grade dos canais da categoria + nome dela
) {
    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo), como em Filmes e no player (ideia 17)
    private val suave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val ambar = Estilo.ATENCAO
    private val fonteMedia = Fontes.manrope(500)
    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var favoritos: Set<String> = emptySet()
    private var recentes: List<String> = emptyList()
    private var grupos: List<GrupoCanais> = emptyList()   // todas as linhas da coluna (ações, fixas e categorias), para achar o nome
    private var esportes: Pair<List<Canal>, Int>? = null    // quantos canais de esporte esta lista tem (conta uma vez por lista)
    private var chave = PlayerLista.TODOS              // categoria cujos canais estão na coluna do meio
    private var doMeio: List<Canal> = emptyList()
    private var atualId: String? = null
    private var busca: Pair<String, List<Canal>>? = null   // termo + resultados (a categoria "Busca")
    private var resumoFiltro = ""                           // "4K · BR" na linha de Filtros
    private var ocultas: Set<String> = emptySet()           // grupos ocultos que estão aparecendo (PIN): ganham o cadeado

    val raiz: FrameLayout = FrameLayout(act).apply {
        visibility = View.GONE
        // Escuro à esquerda, mais claro à direita: o vídeo continua aparecendo atrás do detalhe.
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Estilo.alfa(Estilo.FUNDO, 0xF5), Estilo.alfa(Estilo.FUNDO, 0xE6), Estilo.alfa(Estilo.FUNDO, 0xB3)))
    }
    // Coluna esquerda nova (14/11/2026, esboço tv.png aprovado): ações fixas, LISTAS, Todos e Esportes fixos, A a Z rolando.
    private val coluna = ColunaCategorias(act, act.getString(R.string.home_tv),
        aoParar = { escolherGrupo(it) }, aoOk = { okNaColuna(it) },
        aoSegurar = { k -> if (k.startsWith("g:")) aoOcultarGrupo(k.removePrefix("g:")) })
    private val listaCanais = lista()
    private val tituloMeio = rotulo()
    private lateinit var detLogo: ImageView
    private lateinit var detCanal: TextView
    private lateinit var detTitulo: TextView
    private lateinit var detHorario: TextView
    private lateinit var detBarra: BarraTempo.Barra
    private lateinit var detDescricao: TextView
    private lateinit var detSeguir: LinearLayout

    val aberta: Boolean get() = raiz.visibility == View.VISIBLE

    init {
        val colunas = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(26), dp(32), dp(22)) }
        colunas.addView(coluna.raiz, LinearLayout.LayoutParams(dp(Estilo.Leitura.COLUNA_CATEGORIAS), -1))
        colunas.addView(coluna(tituloMeio, listaCanais), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14); marginEnd = dp(18) })
        colunas.addView(montarDetalhe(), LinearLayout.LayoutParams(dp(290), -1))
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))

    }

    /** OK numa linha da coluna: as ações abrem a janela delas (da PlayerActivity); categoria abre e foca os canais. */
    private fun okNaColuna(k: String) {
        when (k) {
            PlayerLista.ACAO_BUSCAR -> aoBuscar()
            PlayerLista.ACAO_FILTROS -> aoFiltros()
            PlayerLista.ACAO_GUIA -> grupos.firstOrNull { it.chave == chave }?.let { aoGuia(doMeio, nomeDoGrupo(it)) }
            else -> { escolherGrupo(k); if (doMeio.isNotEmpty()) listaCanais.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- uso pela PlayerActivity

    /**
     * @param canais já filtrados (PlayerFiltro.aplicar): o que não passa no filtro nem aparece
     * @param ocultas grupos ocultos que estão visíveis agora (depois do PIN), para marcar com o cadeado
     */
    fun abrir(canais: List<Canal>, guia: Guia, favoritos: Set<String>, recentes: List<String>, chave: String, atual: Canal?,
              resumoFiltro: String = "", ocultas: Set<String> = emptySet(), focarCategorias: Boolean = false) {
        this.canais = canais; this.guia = guia; this.favoritos = favoritos; this.recentes = recentes
        this.resumoFiltro = resumoFiltro; this.ocultas = ocultas
        atualId = atual?.id
        busca = busca?.let { (t, r) -> val ids = canais.mapTo(HashSet()) { it.id }; t to r.filter { it.id in ids } }
        montarGrupos()
        this.chave = PlayerLista.chaveValida(grupos, chave)
        mostrarColuna()
        preencherMeio(manterNoAtual = true)
        raiz.visibility = View.VISIBLE
        if (focarCategorias || doMeio.isEmpty()) focarGrupoAtual() else listaCanais.requestFocus()
    }

    /** Resultado de uma busca: vira a categoria "Busca", já escolhida, com o foco nos canais achados. */
    fun mostrarBusca(termo: String, resultados: List<Canal>) {
        busca = termo to resultados
        montarGrupos()
        chave = PlayerLista.BUSCA
        mostrarColuna()
        preencherMeio(manterNoAtual = false)
        if (doMeio.isEmpty()) focarGrupoAtual() else listaCanais.requestFocus()
    }

    /** O foco está num canal (coluna do meio)? Aí o OK é da PlayerActivity: curto assiste, segurado 2 s favorita. */
    fun canaisComFoco(): Boolean = aberta && listaCanais.hasFocus()
    fun canalEmFoco(): Canal? = doMeio.getOrNull(listaCanais.selectedItemPosition)
    fun assistirEmFoco() { canalEmFoco()?.let { aoAssistir(it, chave) } }

    /** O guia em grade fechou e a lista ficou por baixo: o foco volta para ela. */
    fun voltarFoco() { coluna.focar() }

    private fun focarGrupoAtual() = coluna.focar()

    /**
     * As linhas da coluna. Ações: Buscar, Filtro, Favoritos, Recentes, Guia. Fixas: Busca (se houver), Todos, Esportes
     * (se a lista tiver canal de esporte). Rolando: as categorias da lista em ordem alfabética.
     */
    private fun montarGrupos() {
        val base = PlayerLista.grupos(canais, favoritos, recentes, busca?.let { it.first to it.second.size })
        val nEsportes = esportes?.takeIf { it.first === canais }?.second
            ?: canais.count { ColunaRegras.ehEsporte(it.grupo, it.nome) }.also { esportes = canais to it }
        val porChave = base.associateBy { it.chave }
        val fixas = listOfNotNull(porChave[PlayerLista.BUSCA], porChave[PlayerLista.TODOS],
            GrupoCanais(PlayerLista.ESPORTES, "", nEsportes).takeIf { nEsportes > 0 })
        val cats = base.filter { it.chave.startsWith("g:") && it.quantos > 0 }
        val (semNome, comNome) = cats.partition { it.nome.isBlank() }
        val rola = comNome.sortedBy { ColunaRegras.chaveAZ(it.nome) } + semNome
        grupos = listOf(GrupoCanais(PlayerLista.ACAO_BUSCAR, "", -1), GrupoCanais(PlayerLista.ACAO_FILTROS, resumoFiltro, -1),
            porChave.getValue(PlayerLista.FAVORITOS), porChave.getValue(PlayerLista.RECENTES), GrupoCanais(PlayerLista.ACAO_GUIA, "", -1)) +
            fixas + rola
        nAcoes = 5; nFixas = fixas.size
    }
    private var nAcoes = 0
    private var nFixas = 0

    private fun linha(g: GrupoCanais) = ColunaCategorias.Linha(
        g.chave, nomeDoGrupo(g), if (g.chave == PlayerLista.ACAO_FILTROS) -1 else g.quantos, iconeDoGrupo(g),
        destaque = g.chave == PlayerLista.ACAO_FILTROS && g.nome.isNotEmpty(),
        detalhe = if (g.chave == PlayerLista.ACAO_FILTROS) g.nome else "")

    private fun mostrarColuna() {
        val l = grupos.map { linha(it) }
        coluna.mostrar(l.take(nAcoes), l.drop(nAcoes).take(nFixas), l.drop(nAcoes + nFixas), chave)
    }

    fun fechar() {
        tela.removeCallbacksAndMessages(null); coluna.parar()
        raiz.visibility = View.GONE
    }

    /** O guia terminou de carregar com a lista aberta. */
    fun atualizarGuia(g: Guia) {
        guia = g
        if (!aberta) return
        adaptadorCanais.notifyDataSetChanged()
        mostrarDetalhe(doMeio.getOrNull(listaCanais.selectedItemPosition))
    }

    /** Favorito mudou (OK segurado): redesenha a estrela e a contagem sem tirar o foco do lugar. */
    fun atualizarFavoritos(f: Set<String>) {
        favoritos = f
        montarGrupos()
        mostrarColuna()
        if (chave == PlayerLista.FAVORITOS) {
            val pos = listaCanais.selectedItemPosition
            doMeio = PlayerLista.filtrar(canais, chave, favoritos, recentes)
            adaptadorCanais.notifyDataSetChanged()
            atualizarTituloMeio()
            if (doMeio.isEmpty()) { coluna.focar(); mostrarDetalhe(null) }
            else listaCanais.setSelection(pos.coerceAtMost(doMeio.size - 1))
        } else adaptadorCanais.notifyDataSetChanged()
    }

    /**
     * Teclas que chegam à Activity com a lista aberta (as ▲▼ e OK simples a ListView já tratou). Tudo é tratado aqui,
     * inclusive ◀ ▶, para o foco nunca escapar para fora da lista. Devolve false para o que não é da lista
     * (volume segue para o Android; números, a Activity fecha a lista e digita o canal).
     */
    fun tecla(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> {
            if (coluna.temFoco() && doMeio.isNotEmpty()) listaCanais.requestFocus() else fechar()
            true
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_YELLOW -> {
            if (listaCanais.hasFocus()) doMeio.getOrNull(listaCanais.selectedItemPosition)?.let { aoFavoritar(it) }
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (coluna.temFoco()) {
                // Desceu rápido nas categorias e já apertou ▶: aplica agora a categoria em que parou.
                coluna.chaveEmFoco()?.let { escolherGrupo(it) }
                if (doMeio.isNotEmpty()) listaCanais.requestFocus()
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (!coluna.temFoco()) coluna.focar(); true }
        // ▲▼ e OK repetidos (tecla segurada), CH+ CH- e INFO não podem trocar de canal por baixo da lista.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_INFO -> true
        else -> false   // volume, mídia, números: seguem para a Activity / o Android
    }

    // ---------------------------------------------------------------- colunas

    private fun escolherGrupo(k: String) {
        if (k == chave || k.startsWith("*acao:") || grupos.none { it.chave == k }) return   // passar pelas ações não troca o meio
        chave = k
        coluna.escolher(k)
        preencherMeio(manterNoAtual = true)
    }

    private fun preencherMeio(manterNoAtual: Boolean) {
        doMeio = when (chave) {
            PlayerLista.BUSCA -> busca?.second.orEmpty()
            // Esportes: quem está ao vivo agora (pelo guia) primeiro (acréscimo aprovado em 14/11/2026).
            PlayerLista.ESPORTES -> {
                val agora = System.currentTimeMillis()
                val lista = PlayerLista.filtrar(canais, chave, favoritos, recentes)
                aoVivo = lista.filterTo(HashSet()) { c ->
                    c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.agora(it, agora) }?.let { ColunaRegras.pareceAoVivo(it.titulo, it.categoria) } == true
                }.mapTo(HashSet()) { it.id }
                ColunaRegras.aoVivoPrimeiro(lista) { it.id in aoVivo }
            }
            else -> PlayerLista.filtrar(canais, chave, favoritos, recentes)
        }
        if (chave != PlayerLista.ESPORTES) aoVivo = emptySet()
        adaptadorCanais.notifyDataSetChanged()
        atualizarTituloMeio()
        val pos = if (manterNoAtual) PlayerLista.posicao(doMeio, atualId) else 0
        // Abre com o canal no meio da coluna, não colado no topo (dá para ver os vizinhos).
        listaCanais.setSelectionFromTop(pos, dp(150))
        mostrarDetalhe(doMeio.getOrNull(pos))
    }

    /** Canais de Esportes com evento ao vivo agora (pelo título no guia): vêm primeiro e levam a etiqueta AO VIVO. */
    private var aoVivo: Set<String> = emptySet()

    private fun atualizarTituloMeio() {
        val nome = grupos.firstOrNull { it.chave == chave }?.let { nomeDoGrupo(it) } ?: ""
        val filtro = listOfNotNull(resumoFiltro.ifEmpty { null }, if (aoVivo.isNotEmpty()) act.getString(R.string.coluna_ao_vivo_primeiro) else null)
            .joinToString("  ·  ").let { if (it.isEmpty()) "" else "  ·  $it" }
        tituloMeio.text = act.getString(R.string.player_canais_de, nome.uppercase(Locale.getDefault()), doMeio.size) + filtro
    }

    private fun nomeDoGrupo(g: GrupoCanais): String = when (g.chave) {
        PlayerLista.TODOS -> act.getString(R.string.player_cat_todos)
        PlayerLista.FAVORITOS -> act.getString(R.string.player_cat_favoritos)
        PlayerLista.RECENTES -> act.getString(R.string.player_cat_recentes)
        PlayerLista.ESPORTES -> act.getString(R.string.coluna_esportes)
        PlayerLista.BUSCA -> act.getString(R.string.player_cat_busca, g.nome)
        PlayerLista.ACAO_BUSCAR -> act.getString(R.string.player_acao_buscar)
        PlayerLista.ACAO_FILTROS -> act.getString(R.string.coluna_filtro)
        PlayerLista.ACAO_GUIA -> act.getString(R.string.coluna_guia)
        else -> PlayerLista.nomeExibido(g.nome).ifEmpty { act.getString(R.string.player_cat_outros) }
    }

    /** Qual ícone a linha da categoria leva (null = nenhum): ações, Favoritos, Recentes, Busca e as ocultas abertas pelo PIN. */
    private fun iconeDoGrupo(g: GrupoCanais): Any? = when (g.chave) {
        PlayerLista.FAVORITOS -> IconePlayer.Tipo.ESTRELA
        PlayerLista.RECENTES -> IconePlayer.Tipo.RECENTES
        PlayerLista.BUSCA, PlayerLista.ACAO_BUSCAR -> IconeMidia.Tipo.LUPA
        PlayerLista.ACAO_FILTROS -> IconePlayer.Tipo.FILTROS
        PlayerLista.ACAO_GUIA -> IconePlayer.Tipo.GRADE
        PlayerLista.TODOS -> IconePlayer.Tipo.TODOS
        PlayerLista.ESPORTES -> IconePlayer.Tipo.ESPORTE
        else -> if (g.nome in ocultas) IconePlayer.Tipo.CADEADO else null
    }

    // Um Drawable por ícone e cor (as linhas são recicladas; criar um a cada desenho seria lixo à toa na TV de 2 GB).
    private val icones = HashMap<Pair<Any, Int>, Drawable>()

    private fun icone(tipo: Any, cor: Int): Drawable = icones.getOrPut(tipo to cor) {
        when (tipo) {
            is IconePlayer.Tipo -> IconePlayer(tipo, cor)
            else -> IconeMidia(tipo as IconeMidia.Tipo, cor)
        }.apply { setBounds(0, 0, dp(18), dp(18)) }
    }

    /** Peças de uma linha de canal, guardadas na tag para não procurar a cada desenho. */
    private class Linha(val numero: TextView, val logo: ImageView, val nome: TextView, val sub: TextView, val tempo: TextView, val barra: BarraTempo.Barra, val estrela: View, val vivo: View)

    private val adaptadorCanais = object : BaseAdapter() {
        override fun getCount() = doMeio.size
        override fun getItem(p: Int) = doMeio[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novaLinhaCanal()
            val l = v.tag as Linha
            val c = doMeio[p]
            val agora = System.currentTimeMillis()
            val prog = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.agora(it, agora) }
            l.numero.text = c.numero.toString()
            l.numero.setTextColor(if (c.id == atualId) ambar else papel)
            l.nome.text = c.nome
            l.sub.text = when {
                c.id == atualId -> act.getString(R.string.player_assistindo) + (prog?.let { " · ${it.titulo}" } ?: "")
                prog != null -> prog.titulo
                else -> PlayerLista.nomeExibido(c.grupo)
            }
            // Barra do programa + "faltam 22 min" à direita do nome dele (13/11/2026).
            l.barra.visibility = if (prog != null) View.VISIBLE else View.INVISIBLE
            prog?.let { l.barra.fracao = guia.progresso(it, agora) }
            l.tempo.text = prog?.let { BarraTempo.textoFaltam(act, it.fim - agora) } ?: ""
            l.tempo.visibility = if (prog != null) View.VISIBLE else View.GONE
            l.estrela.visibility = if (c.id in favoritos) View.VISIBLE else View.GONE
            l.vivo.visibility = if (c.id in aoVivo) View.VISIBLE else View.GONE
            PlayerLogos.carregar(act, c.logo, l.logo)
            return v
        }
    }

    private fun novaLinhaCanal(): View {
        val linha = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            layoutParams = AbsListView.LayoutParams(-1, dp(70))   // 58 -> 70: letra maior e barra de 5 dp (13/11/2026)
        }
        val numero = TextView(act).apply { textSize = Estilo.Leitura.ITEM; typeface = Fontes.manrope(600); gravity = Gravity.CENTER }
        linha.addView(numero, LinearLayout.LayoutParams(dp(50), -2))
        val logo = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Estilo.BORDA) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        linha.addView(logo, LinearLayout.LayoutParams(dp(58), dp(40)).apply { marginEnd = dp(12) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val nome = TextView(act).apply { textSize = Estilo.Leitura.ITEM; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        // "AO VIVO" em vermelho forte, letra branca, ao lado do nome (Esportes; acréscimo 4 do painel, 14/11/2026).
        val vivo = TextView(act).apply {
            text = act.getString(R.string.coluna_ao_vivo); textSize = Estilo.Leitura.ITEM_DETALHE; typeface = Fontes.manrope(700)
            setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(8), dp(1), dp(8), dp(2)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(COR_AO_VIVO) }
        }
        val linhaNome = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        linhaNome.addView(vivo, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        linhaNome.addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
        val sub = TextView(act).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val tempo = TextView(act).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 1 }
        val linhaSub = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        linhaSub.addView(sub, LinearLayout.LayoutParams(0, -2, 1f))
        linhaSub.addView(tempo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        val barra = BarraTempo.Barra(act)
        col.addView(linhaNome); col.addView(linhaSub)
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(4) })
        linha.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val estrela = ImageView(act).apply { setImageDrawable(IconePlayer(IconePlayer.Tipo.ESTRELA, ambar)) }
        linha.addView(estrela, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })
        linha.tag = Linha(numero, logo, nome, sub, tempo, barra, estrela, vivo)
        return linha
    }

    // ---------------------------------------------------------------- detalhe (direita)

    private fun montarDetalhe(): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Estilo.alfa(Estilo.FUNDO, 0xCC)); setStroke(dp(1), Estilo.SUPERFICIE_ALTA) }
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        detLogo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_START }
        col.addView(detLogo, LinearLayout.LayoutParams(dp(120), dp(64)))
        detCanal = TextView(act).apply { textSize = Estilo.Leitura.AVISO; setTextColor(suave); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(detCanal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detTitulo = TextView(act).apply { textSize = Estilo.Leitura.PROGRAMA; typeface = fonteMedia; setTextColor(papel); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        col.addView(detTitulo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        detHorario = TextView(act).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave) }
        col.addView(detHorario, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        detBarra = BarraTempo.Barra(act)
        col.addView(detBarra, LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(8) })
        detDescricao = TextView(act).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        col.addView(detDescricao, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detSeguir = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(detSeguir, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        col.addView(TextView(act).apply { text = act.getString(R.string.player_lista_teclas); textSize = Estilo.Leitura.DICA; setTextColor(apagado); setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return col
    }

    private fun mostrarDetalhe(c: Canal?) {
        detSeguir.removeAllViews()
        if (c == null) {
            detLogo.setImageDrawable(null); detCanal.text = ""; detHorario.text = ""; detDescricao.text = ""
            detBarra.visibility = View.INVISIBLE
            detTitulo.text = act.getString(if (chave == PlayerLista.FAVORITOS) R.string.player_sem_favoritos else R.string.player_categoria_vazia)
            return
        }
        PlayerLogos.carregar(act, c.logo, detLogo)
        detCanal.text = listOf(c.numero.toString(), c.nome, PlayerLista.nomeExibido(c.grupo)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        val agora = System.currentTimeMillis()
        val prox = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, 4) }.orEmpty()
        val atual = prox.firstOrNull()?.takeIf { agora >= it.inicio }
        if (atual == null) {
            detTitulo.text = c.nome
            detHorario.text = if (prox.isEmpty()) act.getString(R.string.player_sem_guia) else ""
            detBarra.visibility = View.INVISIBLE
            detDescricao.text = ""
        } else {
            detTitulo.text = atual.titulo
            val faltam = ((atual.fim - agora) / 60_000L).coerceAtLeast(0)
            detHorario.text = act.getString(R.string.player_horario, hora.format(Date(atual.inicio)), hora.format(Date(atual.fim)), faltam) +
                (atual.categoria.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: "")
            detBarra.visibility = View.VISIBLE
            detBarra.fracao = guia.progresso(atual, agora)
            detDescricao.text = atual.descricao
        }
        val depois = prox.filter { it !== atual }.take(3)
        if (depois.isNotEmpty()) {
            detSeguir.addView(rotulo().apply { text = act.getString(R.string.player_depois) })
            depois.forEach { p ->
                detSeguir.addView(TextView(act).apply {
                    text = "${hora.format(Date(p.inicio))}   ${p.titulo}"; textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(papel)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(3))
                })
            }
        }
    }

    // ---------------------------------------------------------------- peças

    private fun lista() = ListView(act).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        itemsCanFocus = false
        isFocusable = true
        choiceMode = AbsListView.CHOICE_MODE_NONE
        // A moldura branca do foco (só aparece na lista que tem o foco: é o que diz ao usuário em que coluna está).
        PlayerFoco.molduraSoComFoco(this, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Estilo.SUPERFICIE_ALTA); setStroke(dp(2), Estilo.FOCO) })
        setDrawSelectorOnTop(false)
        cacheColorHint = 0
    }

    private fun coluna(titulo: TextView, lista: ListView) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(titulo)
        addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rotulo() = TextView(act).apply {
        textSize = Estilo.Leitura.ROTULO; typeface = Fontes.manrope(600); letterSpacing = 0.06f; setTextColor(Estilo.TEXTO_3)
        setPadding(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    // Segundo init, no FIM da classe de propósito: o Kotlin inicia as propriedades na ordem em que aparecem, e os
    // adaptadores acima só existem a partir daqui (no 1º init seriam null).
    init {
        listaCanais.adapter = adaptadorCanais
        listaCanais.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { mostrarDetalhe(doMeio.getOrNull(pos)) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        // OK pelo controle não chega aqui (PlayerActivity.dispatchKeyEvent trata curto e segurado 2 s); fica o clique
        // para quem usa outro meio (mouse, toque).
        listaCanais.setOnItemClickListener { _, _, pos, _ -> doMeio.getOrNull(pos)?.let { aoAssistir(it, chave) } }
    }
}

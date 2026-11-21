package br.paulo.tvlauncher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.home.CapaCartao
import br.paulo.tvlauncher.home.FundoFicha
import br.paulo.tvlauncher.home.GridRequest
import br.paulo.tvlauncher.home.IconeMidia
import br.paulo.tvlauncher.home.TituloCard
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.SegurarOk
import br.paulo.tvlauncher.panel.PainelCatalogo
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.CatalogActivity
import br.paulo.tvlauncher.player.ColunaCategorias
import br.paulo.tvlauncher.player.ColunaRegras
import br.paulo.tvlauncher.player.Conforto
import br.paulo.tvlauncher.player.ConfortoRegras
import br.paulo.tvlauncher.player.ElencoTmdb
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.EtiquetaSerie
import br.paulo.tvlauncher.player.FichaSerie
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.IconePlayer
import br.paulo.tvlauncher.player.IdadesTmdb
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.InfoExternaCache
import br.paulo.tvlauncher.player.JanelaIcones
import br.paulo.tvlauncher.player.LegendaExterna
import br.paulo.tvlauncher.player.OndeAssistir
import br.paulo.tvlauncher.player.PlayerAcessivel
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerFoco
import br.paulo.tvlauncher.player.PlayerLista
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.PosterGridSize
import br.paulo.tvlauncher.player.ProgressoSerie
import br.paulo.tvlauncher.player.Serie
import br.paulo.tvlauncher.player.Series
import br.paulo.tvlauncher.player.ServicosSync
import br.paulo.tvlauncher.player.SincronizarFilmes
import br.paulo.tvlauncher.player.UltimoEpisodio
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.VodFiltro
import br.paulo.tvlauncher.player.avisoBreve
import br.paulo.tvlauncher.player.vazioDoCatalogo
import br.paulo.tvlauncher.search.BuscaUnica
import br.paulo.tvlauncher.settings.Fontes
import java.util.Locale

/**
 * Player universal, séries do Xtream (06/11/2026): catálogo de séries (mesma arrumação dos filmes: categorias à esquerda,
 * pôsteres à direita) e a ficha da série com temporadas e episódios.
 *
 * Na ficha: o botão principal oferece "Continuar T2E5 de 12:34" (ou o episódio seguinte, se o último terminou, ou
 * "Assistir T1E1"); embaixo, as temporadas e a lista de episódios com a barra do que já foi visto. Quando um episódio
 * vai até o fim, o próximo começa sozinho (VodActivity devolve "terminou").
 */
class SeriesActivity : CatalogActivity<Serie>("series", isSeries = true) {
    private companion object {
        const val ANTERIORMENTE_OFF = "anteriormente_off"   // prefs "player" (vai no backup): o dono desligou o resumo
        const val MAX_NOVIDADES = 15
        const val CODIGO_VIDEO = 7
    }

    override val buscaDica = R.string.series_busca_dica
    override val ordemRecentes = R.string.series_ordem_recentes
    override fun groupOf(t: Serie) = t.grupo
    override fun nameOf(t: Serie) = t.nome
    override fun tmdbOf(t: Serie) = tmdbDaSerie(t)
    override fun addedOf(t: Serie) = t.atualizada


    private var series: List<Serie> = emptyList()      // o que a tela mostra (sem as categorias ocultas, até o PIN)
    // Ficha de fora (TMDB: sinopse, próximo episódio; Trakt: nota) da série aberta (InfoExterna, 14/11/2026).
    private var externo: Pair<Serie, InfoExterna.Guardado>? = null
    @Volatile private var cacheTitulos: Pair<List<Serie>, List<SincronizarFilmes.Titulo>>? = null
    private var ultimos: Map<String, UltimoEpisodio> = emptyMap()
    /** Próximo episódio marcado no TMDB (só o que já está guardado), por chave: vira a etiqueta "Novo ep. sex., 20/11". */
    private var proximos: Map<String, InfoExterna.ProximoEp> = emptyMap()
    private var progresso: Map<String, ProgressoSerie> = emptyMap()   // episódios vistos / total, por série
    private var soNaoVistas = false          // Filtro: só séries sem episódio visto

    // Ficha aberta
    private var aberta: Serie? = null
    private var fichaAberta: FichaSerie? = null
    // A outra versão da série aberta, com os episódios dela, para o ▼ do player trocar no mesmo ponto (18/11/2026).
    private var outraFicha: Pair<Serie, FichaSerie>? = null
    private var temporada = 1
    private var tocando: Episodio? = null   // o episódio que foi para a VodActivity (para o "próximo")
    private var seguidos = 0   // ideia 19: episódios seguidos que terminaram sem ninguém apertar tecla
    private var avisoProximo: android.app.Dialog? = null

    private lateinit var painelInfo: PainelCatalogo   // topo (categoria + pílulas do filtro) e a ficha rápida da série em foco
    private lateinit var painel: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        perfilCarregado = Perfil.chaveDados(this)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE).getString("ordem_series", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        // Coerência visual parte 2 (12/11/2026): o grafite e a margem da HOME (eram FUNDO liso e 32/26 dp).
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(context) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(18)) }
        // Coluna esquerda nova (14/11/2026, esboço aprovado): ações fixas, LISTAS, Todos fixo, categorias de A a Z rolando.
        coluna = ColunaCategorias(this, getString(R.string.series_titulo), aoParar = { escolherCat(it) }, aoOk = { okNaColuna(it) },
            escala = PlayerAcessivel.escala(simples))
        colunas.addView(coluna.raiz, LinearLayout.LayoutParams(dp((Estilo.Leitura.COLUNA_CATEGORIAS * PlayerAcessivel.escala(simples)).toInt()), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Painel direito (esboço direita-filmes.png, aprovado em 14/11/2026): o filtro em pílulas e a ficha rápida.
        painelInfo = PainelCatalogo(this)
        direita.addView(painelInfo.topo)
        direita.addView(painelInfo.faixa, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        painelInfo.alinharCom(coluna.caixaAcoes)
        grade = GridView(this).apply {
            numColumns = Estilo.Leitura.GRADE_COLUNAS
            horizontalSpacing = dp(PosterGridSize.VAO_COLUNAS); verticalSpacing = dp(PosterGridSize.VAO_FILEIRAS)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estilo(this); setPaddingRelative(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        // Igual a Filmes (19/11/2026): colunas e altura do pôster pelo espaço real (PosterGridSize), 2 fileiras inteiras.
        grade.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val d = resources.displayMetrics.density
            val m = PosterGridSize.calcular(((r - l - v.paddingLeft - v.paddingRight) / d).toInt(), ((b - t - v.paddingTop - v.paddingBottom) / d).toInt())
            if (m == medidaGrade) return@addOnLayoutChangeListener
            medidaGrade = m
            // Depois deste layout (mudar a grade dentro dele seria ignorado): as colunas novas e os cards na altura nova.
            v.post { if (!isDestroyed) { grade.numColumns = m.colunas; adaptadorGrade.notifyDataSetChanged() } }
        }
        // Dica fixa das teclas, no mesmo jeito da lista de canais do player (player_lista_teclas: 11sp, cor apagada).
        // Sem faixa de dicas embaixo (Paulo, 20/11/2026: tirava espaço dos pôsteres). Como no Netflix, Google TV e Apple TV,
        // a grade de pôsteres é só conteúdo; o "segurar OK" continua na Ajuda.
        colunas.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
        raiz.addView(colunas, FrameLayout.LayoutParams(-1, -1))
        telaDeTras = colunas
        // Focável: enquanto a ficha carrega, o foco sai da grade de baixo (senão ▲▼ mexeriam nela por trás).
        // Sem fundo próprio (16/11/2026): com a ficha aberta a grade de trás fica INVISIBLE e o grafite da raiz aparece. Antes
        // eram dois fundos de tela cheia, um sobre o outro (overdraw visto na Sala), no quadro mais caro, o de abrir a ficha.
        painel = FrameLayout(this).apply { visibility = View.GONE; isFocusable = true }
        raiz.addView(painel, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xD9121528.toInt()) }
            setPaddingRelative(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.series_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)

        grade.adapter = adaptadorGrade
        // ◀ na 1ª coluna da grade volta para a categoria escolhida (não para a linha da coluna que estiver mais perto).
        grade.setOnKeyListener { v, tecla, e ->
            val codigo = Rtl.tecla(tecla, v)   // 16.8: no árabe a coluna de categorias fica à direita
            if (e.action != KeyEvent.ACTION_DOWN) false
            else if (codigo == KeyEvent.KEYCODE_DPAD_LEFT && grade.selectedItemPosition % grade.numColumns == 0) { coluna.focar(); true }
            // ▶ na última coluna de uma grade grande: as letras, para pular (acréscimo 3).
            else if (codigo == KeyEvent.KEYCODE_DPAD_RIGHT && e.repeatCount == 0 && podePularLetra() &&
                (grade.selectedItemPosition % grade.numColumns == grade.numColumns - 1 || grade.selectedItemPosition == naGrade.size - 1)) { pularParaLetra(); true }
            else false
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

    /** A tela vazia (sem lista de TV) está à mostra: ao voltar das Configurações, recarrega. */
    private var vazioMostrado = false

    override fun onResume() {
        super.onResume()
        // Perfis de verdade (ideia 11): os interesses na memória são do perfil de antes; gravar com eles misturaria os dois.
        if (Perfil.chaveDados(this) != perfilCarregado) { recreate(); return }
        // Voltou de "Adicionar lista" (tela vazia): a lista nova só aparece recarregando a tela inteira.
        if (vazioMostrado) { recreate(); return }
        indoAoVideo = false
        // O controle parental pode ter mudado nas Configurações (igual à tela de Filmes): relê as categorias ocultas.
        val lista = catalogo
        if (lista.isNotEmpty()) Fundo.rodar {
            val oc = VodFiltro.ocultas(lista.map { it.grupo }, PlayerArmazem.gruposEscondidos(this),
                PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))
            val pi = IdiomaPlayer.prefs(this)   // trocar o áudio num episódio muda a escolha da série
            val fi = IdadesTmdb.filtro(this, lista.map { it.grupo }, serie = true)
            tela.post {
                if (isDestroyed) return@post
                prefsIdioma = pi
                if (oc == ocultas && fi?.max == porIdade?.max) return@post
                ocultas = oc; porIdade = fi
                if (aberta?.let { !mostrarOcultas && it.grupo.trim() in oc } == true) fecharFicha()
                refiltrarOcultas()
            }
        }
    }

    private var ponte: View? = null
    private val tirarPonte = Runnable { ponte?.let { (it.parent as? ViewGroup)?.removeView(it) }; ponte = null }

    private fun cobrirPonte() {
        if (ponte != null) return
        val v = View(this).apply { setBackgroundColor(Estilo.FUNDO) }
        (window.decorView as? ViewGroup)?.addView(v, ViewGroup.LayoutParams(-1, -1)) ?: return
        ponte = v
        tela.postDelayed(tirarPonte, 4_000)   // o próximo não abriu (contagem cancelada, erro): a ficha volta
    }

    override fun onStop() {
        tela.removeCallbacks(tirarPonte); tirarPonte.run()
        avisoProximo?.dismiss()   // ideia 19: saiu da tela (HOME, protetor) com a contagem andando: nada começa sozinho
        super.onStop()
        if (!indoAoVideo) guardarMarco()
        // O PIN vale só enquanto a tela está na frente (igual ao player): saiu para a HOME ou outro app, tranca de novo.
        // Ir assistir um episódio daqui não conta, senão a cada episódio teria de digitar o PIN.
        if (mostrarOcultas && !indoAoVideo) {
            mostrarOcultas = false
            if (aberta?.let { it.grupo.trim() in ocultas } == true) fecharFicha()
            refiltrarOcultas()
        }
    }

    /** O Filtro único (14/11/2026): Ordem, só não vistas, Sortear e "Mostrar ocultas". Mexe só na grade da direita. */
    override fun menuFiltroUnico() {
        val opcoes = ArrayList<Pair<CharSequence, () -> Unit>>()
        opcoes += getString(R.string.coluna_filtro_ordem, nomeOrdem(ordem)) to { escolherOrdem() }
        opcoes += getString(R.string.filmes_filtro_nao_vistos, getString(if (soNaoVistas) R.string.filmes_filtro_sim else R.string.filmes_filtro_nao)) to {
            soNaoVistas = !soNaoVistas; recalcular(ModoGrade.NOVA); menuFiltroUnico()
        }
        opcoes += getString(R.string.coluna_filtro_sortear) to { sortear() }
        if (podePularLetra()) opcoes += getString(R.string.coluna_letra) to { pularParaLetra() }
        if (ocultas.isNotEmpty()) opcoes += JanelaIcones.comIcone(this, IconePlayer.Tipo.OCULTO,
            getString(if (mostrarOcultas) R.string.coluna_filtro_ocultas_esconder else R.string.coluna_filtro_ocultas_mostrar)) to { alternarOcultas() }
        Estilo.lista(this, getString(R.string.coluna_filtro_titulo), opcoes.map { it.first }) { i -> opcoes[i].second() }
    }

    /** Sorteio: uma série da grade como está, preferindo os Interesses ainda não vistos; abre a ficha dela. */
    private fun sortear() {
        val g = naGrade
        if (g.isEmpty()) { avisoBreve(getString(R.string.filmes_sorteio_vazio)); return }
        val preferidas = g.filter { it.chave in interesses && it.chave !in ultimos }
        abrirSerie((preferidas.ifEmpty { g.filter { it.chave !in ultimos }.ifEmpty { g } }).random(), trocar = true)
    }

    private fun carregar() {
        Fundo.rodar {
            // Abre pelo cache na hora; vencido, atualiza em segundo plano para a próxima vez (mesma regra de Filmes).
            val cache = PlayerCarga.series(this, soCache = true)
            val s = cache.ifEmpty { PlayerCarga.series(this) }
            if (cache.isNotEmpty() && PlayerCarga.catalogoVelho(this, series = true))
                Fundo.rodar("series-atualizar") { runCatching { PlayerCarga.series(this) } }
            // A outra tela do catálogo (Filmes/Séries) vai para a memória agora: trocar de uma para a outra abre sem espera.
            Fundo.rodar("catalogo-aquecer") { PlayerCarga.aquecer(applicationContext) }
            // Categorias ocultas: a mesma regra do player (adultas, escondidas) + as bloqueadas no controle parental.
            val oc = VodFiltro.ocultas(s.map { it.grupo }, PlayerArmazem.gruposEscondidos(this),
                PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))
            val fi = IdadesTmdb.filtro(this, s.map { it.grupo }, serie = true)
            val u = PlayerArmazem.ultimosEpisodios(this)
            val p = PlayerArmazem.posicoes(this)
            val i = PlayerArmazem.interesses(this, series = true)
            val pr = PlayerArmazem.progressoSeries(this)
            val px = proximosGuardados(s)
            val pi = IdiomaPlayer.prefs(this)
            val marco = prefsPlayer().getLong(chavePref("marco"), 0L)
            val ultima = prefsPlayer().getString(chavePref("ultima"), null)
            tela.post {
                if (isDestroyed) return@post
                catalogo = s; ocultas = oc; porIdade = fi; ultimos = u; posicoes = p; interesses = i; progresso = pr; prefsIdioma = pi; proximos = px
                marcoNovos = marco; ultimaSalva = ultima
                conferirNovidades()
                if (s.isEmpty()) {
                    aviso.visibility = View.GONE
                    vazioMostrado = true
                    val vazio = vazioDoCatalogo(this, getString(R.string.series_vazio))
                    (aviso.parent as FrameLayout).addView(vazio, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    vazio.getFocusables(View.FOCUS_DOWN).firstOrNull()?.requestFocus()   // o botão "Adicionar lista" já vem focado
                    return@post
                }
                // Aberta pela busca única da HOME (12/11/2026): já entra com o termo buscado.
                intent.getStringExtra(BuscaUnica.EXTRA_TERMO)?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> termo = t; chave = BUSCA }
                // A 1ª conta (ocultas, contagens, ordem) também sai da thread da tela; o "Carregando" fica até ela chegar.
                recalcular(ModoGrade.NOVA, inicialPedido = true) { aviso.visibility = View.GONE; grade.requestFocus() }
                sincronizarFora(s, forcar = false)
                classificarIdades(s)
                // O índice das versões sai depois da grade na tela (é a parte demorada; a ficha funciona sem ele).
                Fundo.rodar {
                    val idx = Idioma.indice(s, { it.nome }, { it.grupo })
                    tela.post { if (!isDestroyed && catalogo === s) indiceVersoes = idx }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Trakt/Simkl e listas de fora (fatia 3)

    override fun titulosDoCatalogo(vis: List<Serie>): List<SincronizarFilmes.Titulo> =
        cacheTitulos?.takeIf { it.first === vis }?.second ?: vis.map {
            SincronizarFilmes.Titulo(it.chave, it.tmdb ?: InfoExternaCache.guardado(this, it.chave)?.tmdb, InfoExterna.chaveNomeAno(Vod.nomeLimpo(it.nome), it.ano))
        }.also { cacheTitulos = vis to it }

    /** Episódios vistos no Trakt e ainda não aqui: marca como vistos (visto é união). Na tela, com a ficha aberta. */
    private fun trazerEpisodiosVistos(s: Serie, f: FichaSerie, tmdb: Int) {
        Fundo.rodar {
            val fora = ServicosSync.episodiosVistosFora(this, tmdb)
            if (fora.isEmpty()) return@rodar
            tela.post {
                if (isDestroyed || aberta !== s) return@post
                val agora = System.currentTimeMillis()
                val novas = HashMap(posicoes)
                var mudou = false
                for ((t, eps) in f.temporadas) for (ep in eps) {
                    if (SincronizarFilmes.Ep(t, ep.numero) !in fora) continue
                    val k = ep.chave(s.fonteId)
                    if (Vod.finalizado(novas[k], episodio = true)) continue
                    val d = novas[k]?.duracao?.takeIf { it > 0 } ?: ((ep.duracaoMin ?: 1) * 60_000L)
                    novas[k] = Posicao(d, d, agora); mudou = true
                }
                if (!mudou) return@post
                posicoes = novas
                val copia = novas
                Fundo.rodar { PlayerArmazem.definirPosicoes(this, copia) }
                atualizarProgresso(s, f)
                desenharFicha()
            }
        }
    }

    // ---------------------------------------------------------------- catálogo

    /**
     * Série concluída que o servidor atualizou (last_modified) desde a conta: relê a ficha dela em segundo plano para
     * dizer QUANTOS episódios novos ("3 episódios novos", "Nova temporada"). Só as dos Interesses e do Continuar, no
     * máximo [MAX_NOVIDADES] por visita (cada uma é um pedido ao servidor).
     */
    /** Fora da tela (a primeira leitura do arquivo de fichas de fora vai ao disco). */
    private fun proximosGuardados(series: List<Serie>): Map<String, InfoExterna.ProximoEp> {
        if (!InfoExternaCache.temFicha()) return emptyMap()
        val r = HashMap<String, InfoExterna.ProximoEp>()
        for (s in series) InfoExternaCache.guardado(this, s.chave)?.detalhe?.proximo?.let { r[s.chave] = it }
        return r
    }

    private fun conferirNovidades() {
        val alvo = catalogo.filter { s -> (s.chave in interesses || s.chave in ultimos) && Series.etiqueta(s, progresso[s.chave]) == EtiquetaSerie.Novos(0) }
            .take(MAX_NOVIDADES)
        if (alvo.isEmpty()) return
        Fundo.rodar("series-novidades") {
            val fichas = alvo.mapNotNull { s -> runCatching { PlayerCarga.fichaSerie(this, s) }.getOrNull()?.let { s to it } }
            tela.post { if (!isDestroyed) fichas.forEach { (s, f) -> atualizarProgresso(s, f) } }
        }
    }

    /** Resultado de uma conta: tudo que a tela precisa, calculado fora dela. */
    private class Estado(val series: List<Serie>, val resultados: List<Serie>, val acoes: List<ColunaCategorias.Linha>,
                         val botoes: List<ColunaCategorias.Linha>,
                         val fixas: List<ColunaCategorias.Linha>, val rola: List<ColunaCategorias.Linha>, val chave: String,
                         val grade: List<Serie>?, val deLista: Pair<Int, Int>? = null)

    // Caches da conta (só a thread da [calculo] lê e escreve). A chave é a própria lista (===): lista nova = refaz.
    private var cacheVisiveis: Triple<List<Serie>, Set<String>, List<Serie>>? = null
    @Volatile private var cacheIdade: IdadesTmdb.Filtro? = null

    private fun tmdbDaSerie(s: Serie): Int? = s.tmdb ?: InfoExternaCache.guardado(this, s.chave)?.tmdb

    private var cacheBusca: Triple<List<Serie>, String, List<Serie>>? = null
    private var cacheOrdem: Triple<List<Serie>, Vod.Ordem, List<Serie>>? = null
    private var cacheGrupos: Pair<List<Serie>, List<Pair<String, Int>>>? = null
    private var cacheNovos: Triple<List<Serie>, Long, Map<String, Int>>? = null
    private var avisoDaConta = false

    /**
     * Refaz, FORA da thread da tela, as categorias ocultas, a busca, as contagens das categorias e (conforme [modo]) a
     * grade (igual à FilmesActivity). Pedidos seguidos descartam os velhos. [inicial]: escolhe a categoria de entrada.
     */
    /** Pedido que ainda não chegou à tela: o próximo herda o que ele exigia ([GridRequest]). */
    private var pedidoPendente: GridRequest.Need? = null

    override fun recalcular(modoPedido: ModoGrade, inicialPedido: Boolean, depoisPedido: (() -> Unit)?) {
        val pedido = GridRequest.merge(pedidoPendente, GridRequest.Need(2 - modoPedido.ordinal, inicialPedido, depoisPedido))
        pedidoPendente = pedido
        val modo = ModoGrade.entries[2 - pedido.strength]; val inicial = pedido.initial; val depois = pedido.after
        val todasS = catalogo; val ocultasS = if (mostrarOcultas) emptySet() else ocultas; val mostrar = mostrarOcultas
        val idadeS = porIdade
        val termoS = termo; val ultS = ultimos; val posS = posicoes; val intS = interesses; val progS = progresso
        val ordemS = ordem; val chaveS = chave; val marcoS = marcoNovos; val ultimaS = ultimaSalva; val naoVistasS = soNaoVistas
        val listasS = listasFora
        val ocultasTodas = ocultas
        val rotulo = { id: Int -> getString(id) }
        val antesSel = grade.selectedItemPosition
        calculo.pedir(
            calcular = {
                val vis = cacheVisiveis?.takeIf { it.first === todasS && it.second == ocultasS && cacheIdade === idadeS }?.third
                    ?: (when {
                        mostrar -> todasS
                        idadeS != null -> todasS.filter { Classificacao.visivel(it.grupo, tmdbDaSerie(it)?.let(idadeS.idades::get), idadeS.max, idadeS.infantis, idadeS.duras) }
                        else -> VodFiltro.visiveis(todasS, ocultasS) { it.grupo }
                    }).also { cacheVisiveis = Triple(todasS, ocultasS, it); cacheIdade = idadeS }
                val res = if (termoS.isEmpty()) emptyList() else cacheBusca?.takeIf { it.first === vis && it.second == termoS }?.third
                    ?: Series.buscar(vis, termoS).also { cacheBusca = Triple(vis, termoS, it) }
                val cont = Series.continuar(vis, ultS, progS)
                val ints = Vod.interesses(vis, intS) { it.chave }
                val vistas = Series.assistidos(vis, ultS, posS, progS)
                fun L(c: String, n: String, q: Int, i: Any?, nv: Int, d: Boolean, det: String, et: String = "") =
                    ColunaCategorias.Linha(c, n, q, i, nv, d, det, et)
                // Buscar e Filtro viram botões redondos ao lado do título (pedido do Paulo: mais espaço na lista).
                // No modo simples fica só Buscar.
                val botoes = listOfNotNull(
                    L(BUSCAR, rotulo(R.string.player_acao_buscar), -1, IconeMidia.Tipo.LUPA, 0, false, ""),
                    if (simples) null else L(FILTRO, rotulo(R.string.coluna_filtro), -1, IconePlayer.Tipo.FILTROS, 0, naoVistasS || mostrar, ""),
                )
                // Bloco fixo (esboço aprovado): sempre as mesmas três linhas.
                val acoes = listOf(
                    L(INTERESSES, rotulo(R.string.filmes_interesses), ints.size, IconePlayer.Tipo.ESTRELA, 0, false, ""),
                    L(CONTINUAR, rotulo(R.string.filmes_continuar), cont.size, IconePlayer.Tipo.PLAY, 0, false, ""),
                    L(ASSISTIDOS, rotulo(R.string.filmes_assistidos), vistas.size, IconePlayer.Tipo.VISTO, 0, false, ""),
                )
                    // Modo simples (HOME no modo simples): só Interesses e Continuar no bloco fixo.
                    .let { a -> if (simples) PlayerAcessivel.blocoSimples(a, { it.chave }, setOf(INTERESSES, CONTINUAR)) else a }
                val fixas = ArrayList<ColunaCategorias.Linha>()
                if (termoS.isNotEmpty()) fixas += L(BUSCA, getString(R.string.player_cat_busca, termoS), res.size, IconeMidia.Tipo.LUPA, 0, false, "")
                fixas += L(TODOS, rotulo(R.string.player_cat_todos), vis.size, IconePlayer.Tipo.TODOS, 0, false, "")
                // Listas de fora, na parte que rola, em ordem alfabética com as categorias: só as séries que a lista de IPTV tem, na ordem da lista.
                val fora = HashMap<String, Pair<List<String>, Int>>()
                // 19/11/2026 (Paulo: "o MDBList não roda com a lista"): as listas de fora iam nas FIXAS e, com várias,
                // ocupavam a coluna e espremiam as categorias. Agora abrem a parte que ROLA, antes das categorias de A a Z.
                val linhasFora = ArrayList<ColunaCategorias.Linha>()
                if (listasS.isNotEmpty()) {
                    val cat = titulosDoCatalogo(vis)
                    for ((l, itensL) in listasS) {
                        val (chs, casaram) = SincronizarFilmes.cruzar(itensL, cat, SincronizarFilmes.Tipo.SERIE)
                        if (chs.isEmpty()) continue
                        fora["x:${l.chave}"] = chs to itensL.count { it.tipo == SincronizarFilmes.Tipo.SERIE }
                        linhasFora += L("x:${l.chave}", l.nome, casaram, null, 0, false, "", if (l.servico == "trakt") "Trakt" else "MDBList")
                    }
                }
                val grupos = cacheGrupos?.takeIf { it.first === vis }?.second ?: Series.grupos(vis).also { cacheGrupos = vis to it }
                val novos = cacheNovos?.takeIf { it.first === vis && it.second == marcoS }?.third
                    ?: ColunaRegras.novosPorGrupo(vis, marcoS, { it.grupo }, { it.atualizada }).also { cacheNovos = Triple(vis, marcoS, it) }
                val rola = ColunaRegras.intercalarAZ(grupos.filter { it.second > 0 }.map { (g, n) ->
                    L("g:$g", PlayerLista.nomeExibido(g).ifEmpty { rotulo(R.string.player_cat_outros) }, n,
                        if (mostrar && g in ocultasTodas) IconePlayer.Tipo.CADEADO else null, novos[g] ?: 0, false, "")
                }, linhasFora) { if (it.chave.startsWith("g:")) it.chave.removePrefix("g:") else it.nome }   // categoria: o grupo cru (como ordenarAZ)
                val todasChaves = (acoes + fixas + rola).mapTo(HashSet()) { it.chave }
                val ch0 = if (inicial && simples) PlayerAcessivel.entradaSimples(listOf(CONTINUAR to cont.size, INTERESSES to ints.size), TODOS)
                    else if (inicial && chaveS != BUSCA) ColunaRegras.categoriaInicial(ultimaS?.takeIf { it != BUSCA }, todasChaves,
                    if (cont.isNotEmpty()) CONTINUAR else TODOS) else chaveS
                val ch = if (ch0 !in todasChaves || ch0 == BUSCAR || ch0 == FILTRO) TODOS else ch0
                val g = if (modo == ModoGrade.IGUAL && ch == chaveS) null else {
                    // A ordem do catálogo inteiro fica guardada: uma categoria é o filtro dela (filtrar mantém a ordem).
                    fun ordenadas() = cacheOrdem?.takeIf { it.first === vis && it.second == ordemS }?.third
                        ?: Series.ordenar(vis, ordemS).also { cacheOrdem = Triple(vis, ordemS, it) }
                    when {
                        ch == CONTINUAR -> cont
                        ch == ASSISTIDOS -> vistas
                        ch == INTERESSES -> ints                 // a última marcada primeiro
                        ch == BUSCA -> res
                        ch.startsWith("x:") -> { val porChave = vis.associateBy { it.chave }; fora[ch]?.first.orEmpty().mapNotNull { porChave[it] } }
                        ch.startsWith("g:") -> { val alvo = ch.removePrefix("g:"); ordenadas().filter { it.grupo.trim() == alvo } }
                        else -> ordenadas()
                    }.let { lista -> if (naoVistasS) lista.filter { it.chave !in ultS && (progS[it.chave]?.vistos ?: 0) == 0 } else lista }
                }
                Estado(vis, res, acoes, botoes, fixas, rola, ch, g, fora[ch]?.let { it.first.size to it.second })
            },
            aplicar = { e ->
                pedidoPendente = null
                if (!isDestroyed) {
                    series = e.series; resultados = e.resultados; itens = e.acoes + e.fixas + e.rola; chave = e.chave
                    coluna.mostrarBotoes(e.botoes)
                    coluna.mostrar(e.acoes, e.fixas, e.rola, chave)
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
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isEmpty() && grade.hasFocus()) coluna.focar()   // esvaziou
                    }
                    val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
                    // "Drama 310 [Ordem: mais recentes] [Só não vistos]": o que está na grade e por quê (acréscimo 1).
                    painelInfo.mostrarTopo(nome, naGrade.size, listOfNotNull(e.deLista?.let { (n, t) -> getString(R.string.sync_n_de, n, t) to false }
                        ?: (getString(R.string.coluna_filtro_ordem, nomeOrdem(ordem)) to false),
                        if (soNaoVistas) getString(R.string.filmes_filtro_nao_vistos_curto) to true else null))
                    depois?.invoke()
                }
            },
            demorou = { if (!isDestroyed) { aviso.text = getString(R.string.series_carregando); aviso.visibility = View.VISIBLE; avisoDaConta = true } },
            // pedidoPendente também aqui: o fim roda com erro ou sucesso (com erro o aplicar não roda e o pedido ficava
            // preso, e toda conta seguinte voltava a grade ao topo; debug 20/11/2026).
            fim = { pedidoPendente = null; if (avisoDaConta) { aviso.visibility = View.GONE; avisoDaConta = false } },
        )
    }

    /**
     * Ficha rápida da série em foco (acréscimo 2): nota, ano, temporadas, gênero e episódio numa linha, sem abrir a
     * ficha. Temporadas só se a ficha já estiver guardada no disco (nunca pede ao servidor a cada passo).
     */
    override fun mostrarFocado(s: Serie?) {
        tela.removeCallbacks(lerFichaRapida)
        if (s == null) { painelInfo.mostrarFoco(if (chave == INTERESSES) getString(R.string.filmes_interesses_vazio) else ""); return }
        val info = fichasRapidas[s.chave]
        painelInfo.mostrarFoco(Estilo.infoFoco(Idioma.tituloCurto(Vod.nomeTela(Idioma.semMarca(s.nome), s.ano)), notaRapida[s.chave] ?: s.nota, listOfNotNull(
            s.ano?.toString(),
            if (soLegendado(s)) getString(R.string.idioma_legendado) else null,
            info?.temporadas?.size?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.series_temporadas, it, it) },
            info?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(s.grupo).ifEmpty { null },
            ultimos[s.chave]?.let { "T${it.temporada}E${it.numero}" },
            extRapidas[s.chave]?.ifEmpty { null })))
        focoRapido = s; if (s.chave !in fichasRapidas) { tela.postDelayed(lerFichaRapida, 400) }
    }

    private val hoje: java.time.LocalDate get() = java.time.LocalDate.now()

    /** "sex., 20/11" do próximo episódio, só nas séries dos Interesses (o pedido: "nas séries de Interesses"). */
    private fun proximoCurto(s: Serie): String? = proximos[s.chave]?.takeIf { s.chave in interesses }
        ?.let { InfoExterna.quandoCurto(it, hoje, getString(R.string.ext_hoje), getString(R.string.ext_amanha)) }

    /** Nota de fora (Trakt) e "Novo episódio sexta, 20/11" para a faixa da ficha rápida; lidas no Fundo. */
    private val extRapidas = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 40
    }
    /** A média das notas de fora (a estrela), quando há; sem ela, a estrela é a da lista de canais. */
    private val notaRapida = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Float>) = size > 40
    }

    private val fichasRapidas = object : LinkedHashMap<String, FichaSerie?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FichaSerie?>) = size > 40
    }
    @Volatile private var focoRapido: Serie? = null   // lido também no Fundo: o pôster ainda em foco?
    private val lerFichaRapida = Runnable {
        val s = focoRapido ?: return@Runnable
        Fundo.rodar {
            // Só o que a tela usa (a ficha inteira, com todos os episódios, não fica na memória).
            // Sem ficha no disco: pede ao servidor se o foco ainda está nela (mesmo motivo de Filmes).
            val info = (PlayerCarga.fichaSerieGuardada(this, s) ?: if (focoRapido === s) PlayerCarga.fichaSerie(this, s) else null)?.let { f -> f.copy(temporadas = f.temporadas.mapValues { emptyList() }, cenas = emptyList()) }
            val ext = textoExtRapido(s.chave, serie = true)
            val media = InfoExternaCache.guardado(this, s.chave)?.let { InfoExterna.notasDe(it).media }
            tela.post {
                if (isDestroyed) return@post
                fichasRapidas[s.chave] = info; extRapidas[s.chave] = ext
                if (media != null) notaRapida[s.chave] = media else notaRapida.remove(s.chave)
                if (naGrade.getOrNull(grade.selectedItemPosition) === s) mostrarFocado(s)
            }
        }
    }

    /** Só o que está guardado (sem rede): notas e, na série, o próximo episódio. Vazio = nada a mostrar. */
    private fun textoExtRapido(chave: String, serie: Boolean): String {
        val g = InfoExternaCache.guardado(this, chave) ?: return ""
        val notas = InfoExterna.notasDe(g).takeIf { !it.vazia }?.let { InfoExterna.textoNotas(it) }
        val prox = if (serie) g.detalhe?.proximo?.let { InfoExterna.textoProximo(it, hoje, getString(R.string.ext_proximo), getString(R.string.ext_hoje), getString(R.string.ext_amanha)) } else null
        return listOfNotNull(notas, prox).joinToString("  ·  ")
    }

    private class Card(val capa: ImageView, val nome: TextView, val estrela: TextView, val etiqueta: TextView, val barra: BarraTempo.Barra,
                       val moldura: View, val selo: TextView)

    /** Medida atual da grade (PosterGridSize); até a grade ter tamanho, a de antes. */
    private var medidaGrade = PosterGridSize.Medida(Estilo.Leitura.GRADE_COLUNAS, Estilo.Leitura.POSTER_ALTURA, Estilo.Leitura.POSTER_ALTURA + PosterGridSize.EXTRA_CARD)

    override val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val s = naGrade[p]
            // Card reaproveitado de antes de a grade mudar de tamanho: acerta a altura (só muda quando a medida muda).
            val alturaCard = dp(medidaGrade.alturaCard)
            if (v.layoutParams.height != alturaCard) {
                v.layoutParams.height = alturaCard
                c.moldura.layoutParams.height = dp(medidaGrade.alturaPoster)
            }
            // O nome só aparece enquanto o pôster não chega ou quando ele não existe (CapaCartao.aoTrocar). O "T2E5"
            // saiu do card (19/11/2026, igual a Filmes): fica na linha do topo, junto da série em foco.
            c.nome.text = Vod.nomeTela(Idioma.semMarca(s.nome), s.ano)
            c.selo.visibility = if (soLegendado(s)) View.VISIBLE else View.GONE
            // Etiqueta: "20 de 62" episódios vistos (sem número de %) ou "Finalizado". Sem conta ainda (ficha nunca aberta
            // depois desta versão) mas com episódio visto: "Assistido".
            // 14/11/2026: verde "Concluída"; amarela "Nova temporada" / "3 episódios novos" quando a concluída ganhou
            // episódio; senão "20 de 62" (episódios concluídos) enquanto está em andamento.
            val pct = Series.percentual(s, progresso[s.chave])
            val etq = Series.etiqueta(s, progresso[s.chave])
            val texto = when (etq) {
                EtiquetaSerie.Concluida -> getString(R.string.coluna_etiqueta_concluida)
                EtiquetaSerie.NovaTemporada -> getString(R.string.coluna_etiqueta_nova_temporada)
                is EtiquetaSerie.Novos -> if (etq.quantos > 0) resources.getQuantityString(R.plurals.coluna_etiqueta_n_novos, etq.quantos, etq.quantos) else getString(R.string.coluna_etiqueta_novos)
                // Série dos Interesses com episódio novo marcado (TMDB) ganha do "20 de 62": a barra já mostra o progresso.
                // 20/11/2026: o "20 de 62" saiu do pôster (a barra já mostra o andamento; o selo cobria a arte).
                null -> proximoCurto(s)?.let { getString(R.string.ext_proximo_curto, it) }
            }
            if (texto == null) c.etiqueta.visibility = View.GONE else {
                c.etiqueta.visibility = View.VISIBLE
                c.etiqueta.text = texto
                val novo = etq is EtiquetaSerie.Novos || etq == EtiquetaSerie.NovaTemporada
                val marcado = etq == null && proximoCurto(s) != null
                c.etiqueta.setTextColor(if (novo) Estilo.FUNDO else if (marcado) ambar else papel)
                (c.etiqueta.background as GradientDrawable).setColor(when (etq) {
                    EtiquetaSerie.Concluida -> verdeEtiqueta
                    null -> 0xD9121528.toInt()
                    else -> ambar
                })
            }
            // Barra embaixo do pôster, como a dos filmes (13/11/2026): episódios vistos / total. Só enquanto a série está
            // em andamento (1 a 99%); finalizada fica só a etiqueta verde, igual ao filme terminado.
            val prog = progresso[s.chave]
            if (pct != null && pct in 1..99 && prog != null && prog.total > 0) {
                c.barra.visibility = View.VISIBLE; c.barra.fracao = BarraTempo.fracao(prog.vistos.toLong(), prog.total.toLong())
            } else c.barra.visibility = View.GONE
            c.estrela.visibility = if (s.chave in interesses) View.VISIBLE else View.GONE
            (c.capa as? CapaCartao)?.semente = Idioma.semMarca(s.nome)   // capa gerada quando não há pôster (ideia 8)
            PlayerLogos.carregar(this@SeriesActivity, s.capa, c.capa, lado = 360)
            return v
        }
    }

    private fun novoCard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(4), dp(4), dp(4), dp(6)) }
        val capa = CapaCartao(this, dp(8).toFloat())   // cinza só enquanto o pôster não chega (CapaCartao.kt)
        // Capa num FrameLayout para a estrela dos interesses ficar por cima (canto de cima, à direita).
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPaddingRelative(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD9121528.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        val etiqueta = TextView(this).apply {
            textSize = Estilo.Leitura.DICA; typeface = fonteMedia; setTextColor(papel); setPaddingRelative(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD9121528.toInt()) }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END   // selo de uma linha: nunca cobre metade do pôster
        }
        moldura.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        // 19/11/2026, o mesmo de Filmes: nada de nome em cima do pôster (repetia a arte e gastava espaço). O nome fica
        // POR CIMA do cinza de espera, centrado, e some quando o pôster chega.
        val nome = TituloCard(this, Estilo.Leitura.CARD).apply {
            setTextColor(papel); maxLines = 4; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPaddingRelative(dp(6), dp(6), dp(6), dp(6))
        }
        moldura.addView(nome, 1, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        capa.aoTrocar = { tem -> nome.visibility = if (tem) View.INVISIBLE else View.VISIBLE }
        // Selo LEG em cima, à esquerda (a estrela dos interesses fica à direita).
        val selo = TextView(this).apply {
            text = getString(R.string.filmes_selo_leg); textSize = Estilo.Leitura.DICA; typeface = fonteMedia; setTextColor(papel)
            setPaddingRelative(dp(6), dp(1), dp(6), dp(2)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD9121528.toInt()) }
        }
        moldura.addView(selo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
        col.addView(moldura, LinearLayout.LayoutParams(-1, dp(medidaGrade.alturaPoster)))
        val barra = BarraTempo.Barra(this)
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(5) })
        // 4 + pôster + barra (5 + 5) + 6 = PosterGridSize.EXTRA_CARD
        col.layoutParams = AbsListView.LayoutParams(-1, dp(medidaGrade.alturaCard))
        col.tag = Card(capa, nome, estrela, etiqueta, barra, moldura, selo)
        return col
    }

    // ---------------------------------------------------------------- ficha da série

    /** A mesma série na outra versão (dublada x legendada), só entre as que a tela pode mostrar (parental). */
    override fun outraVersao(s: Serie): Serie? = Idioma.outraVersao(s, indiceVersoes, { it.nome }, { it.grupo }, { it.ano })
        ?.takeIf { mostrarOcultas || it.grupo.trim() !in ocultas }

    /**
     * [trocar]: veio da grade. Se a pessoa prefere a outra versão e ela existe, a ficha já abre nela (com um botão para
     * voltar). Não troca série já começada: quem vinha vendo dublada continua nela.
     */
    private fun abrirSerie(s0: Serie, trocar: Boolean = false) {
        val s = if (!trocar || ultimos[s0.chave] != null) s0 else outraVersao(s0)
            ?.takeIf { Idioma.versaoComPar(it.nome, it.grupo, s0.nome, s0.grupo) == Idioma.versaoDesejada(prefsIdioma.para(s0.chave)) } ?: s0
        if (aberta !== s) verEpisodios = false
        aberta = s; fichaAberta = null; outraFicha = null
        // A ficha abre já com o foco nela (a grade de trás não mexe mais), mas o "carregando" só aparece se a ficha do
        // servidor demorar: antes eram 3 desenhos seguidos ao abrir (carregando, servidor, TMDB), cada um com quadros
        // de 50-110 ms na TV (medido em 15/11/2026). Agora os que chegam juntos viram um só.
        painel.removeAllViews(); painel.visibility = View.VISIBLE; painel.requestFocus()
        telaDeTras.visibility = View.INVISIBLE
        pedirDesenho(250)
        val externoVem = InfoExternaCache.temFicha()
        Fundo.rodar {
            val f = PlayerCarga.fichaSerie(this, s)
            tela.post {
                if (isDestroyed || aberta !== s) return@post
                fichaAberta = f
                atualizarProgresso(s, f)
                temporada = ultimos[s.chave]?.temporada?.takeIf { f?.temporadas?.containsKey(it) == true } ?: f?.temporadas?.keys?.firstOrNull() ?: 1
                // A ficha de fora chega 50-400 ms depois (mais com MDBList/Simkl): espera por ela para desenhar uma vez só.
                pedirDesenho(if (externoVem && externo?.first !== s) ESPERA_EXTERNO_MS else 0)
            }
            // Ficha de fora, depois da do servidor; sem chave nenhuma, não faz nada.
            if (externoVem) {
                val g = InfoExternaCache.resolver(this, s.chave, s.nome, s.ano, true, f?.tmdb ?: s.tmdb)
                tela.post {
                    if (isDestroyed || aberta !== s) return@post
                    // Reaberta (volta do vídeo): a mesma ficha de fora de antes não muda nada na tela, não redesenha.
                    val mudou = externo?.let { it.first !== s || it.second != g } ?: true
                    externo = s to g
                    if (mudou || desenhoMarcado) pedirDesenho()
                }
                val id = g.tmdb
                if (f != null && id != null) tela.post { if (!isDestroyed && aberta === s) trazerEpisodiosVistos(s, f, id) }
            }
            // A outra versão (dublada/legendada): os episódios dela, para trocar no player sem voltar à ficha. Por último:
            // antes vinha antes da ficha de fora e a ida ao servidor atrasava ela (a ficha piscava; debug 20/11/2026).
            outraVersao(s)?.let { o -> PlayerCarga.fichaSerie(this, o)?.let { fo -> tela.post { if (!isDestroyed && aberta === s) outraFicha = o to fo } } }
        }
    }

    /**
     * Um desenho da ficha no próximo giro da tela (ou daqui a [espera] ms); vários pedidos seguidos viram um só.
     * Cada desenho refaz a ficha inteira (16-27 ms de tela na TV da Sala, medido em 16/11/2026 com atrace): servidor e
     * TMDB chegando 40-50 ms um do outro eram dois desenhos e um tranco a mais.
     */
    private var desenhoMarcado = false
    private val desenhoPendente = Runnable { desenhoMarcado = false; if (aberta != null && !isDestroyed) desenharFicha() }
    private fun pedirDesenho(espera: Long = 0) { desenhoMarcado = true; tela.removeCallbacks(desenhoPendente); tela.postDelayed(desenhoPendente, espera) }

    private fun fecharFicha() {
        tela.removeCallbacks(desenhoPendente); desenhoMarcado = false
        aberta = null; fichaAberta = null; verEpisodios = false; painel.visibility = View.GONE; painel.removeAllViews()
        telaDeTras.visibility = View.VISIBLE
        if (naGrade.isNotEmpty()) grade.requestFocus() else coluna.focar()
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
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { menuPoster(it) } })
    }

    /** OK segurado no pôster (14/11/2026): pôr/tirar dos Interesses ou tirar do Continuar (os episódios se marcam na ficha). */
    private fun menuPoster(s: Serie) {
        val opcoes = ArrayList<Pair<String, () -> Unit>>()
        opcoes += getString(if (s.chave in interesses) R.string.coluna_menu_tirar_interesse else R.string.coluna_menu_por_interesse) to { alternarInteresse(s) }
        if (s.chave in ultimos) opcoes += getString(R.string.coluna_menu_tirar_continuar) to {
            ultimos = ultimos - s.chave
            val copia = ultimos
            Fundo.rodar { PlayerArmazem.definirUltimosEpisodios(this, copia) }
            adaptadorGrade.notifyDataSetChanged(); recalcular(ModoGrade.MANTER_LUGAR)
            avisoBreve(getString(R.string.coluna_tirado_continuar, Vod.nomeLimpo(s.nome)))
        }
        Estilo.lista(this, Vod.nomeLimpo(s.nome), opcoes.map { it.first }) { i -> opcoes[i].second() }
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

    private var verEpisodios = false     // ficha C: a direita mostra a lista de episódios em vez das informações

    /**
     * Ficha no estilo C "Botões grandes" (15/11/2026): à esquerda as ações em letra grande, uma embaixo da outra; à direita
     * as informações ou, depois de "Episódios", a lista de episódios da temporada (VOLTAR volta às informações).
     * [focarEpisodio]: abre direto na lista, com o foco nesse episódio. Os dados chegam aos poucos e cada um redesenha:
     * o foco fica onde estava (na mesma ação, ou no mesmo episódio).
     */
    /** Chave "esconder resumo de episódio não visto" ([Conforto]), relida a cada vez que a ficha é desenhada. */
    private var semSpoiler = false
    /** Tem a busca do Google TV? (botão "Onde assistir" da ficha; conferido uma vez por tela.) */
    private val googleTv by lazy { BuscaActivity.temGoogleTv(this) }

    private fun desenharFicha(focarEpisodio: Int? = null) {
        semSpoiler = Conforto.semSpoiler(this)
        val s = aberta ?: return
        val f = fichaAberta
        val focoAntes = painel.findFocus()
        val marcaAntes = focoAntes?.tag as? String
        var focarEp = focarEpisodio
        if (focarEp == null && verEpisodios && marcaAntes == "eps") focarEp = (focoAntes as? ListView)?.selectedItemPosition?.coerceAtLeast(0)
        if (focarEp != null) verEpisodios = true
        painel.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(48), dp(36), dp(48), dp(24)) }
        val ext = externo?.takeIf { it.first === s }?.second
        val sinopse = f?.sinopse?.takeIf { it.isNotEmpty() } ?: ext?.detalhe?.sinopse?.takeIf { it.isNotEmpty() }
        val temEps = f != null && f.temporadas.isNotEmpty()
        if (!temEps) verEpisodios = false

        // ---- ações (esquerda)
        val (colAcoes, acoes) = FichaAcoes.coluna(this)
        var primeiro: View? = null
        if (f != null && temEps) {
            Series.sugestao(f, s.fonteId, ultimos[s.chave], posicoes)?.let { (ep, retomar) ->
                primeiro = (if (retomar) FichaAcoes.acao(this, getString(R.string.series_continuar_curto, ep.rotulo), relogio(posicoes[ep.chave(s.fonteId)]?.ms ?: 0), "continuar") { talvezAnteriormente(s, f, ep, doComeco = false) }
                    else FichaAcoes.acao(this, getString(R.string.series_assistir, ep.rotulo), marca = "assistir") { talvezAnteriormente(s, f, ep, doComeco = true) })
                    .also { FichaAcoes.adicionar(acoes, it) }
            }
            val nTemp = f.temporadas.size
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.series_episodios), if (nTemp > 1) getString(R.string.series_temporada, temporada) else null, "episodios") {
                verEpisodios = true; desenharFicha(focarEpisodio = 0)
            }.also { if (primeiro == null) primeiro = it })
            if (nTemp > 1) FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.series_trocar_temporada), nTemp.toString(), "temporadas") { escolherTemporada(s, f) })
        }
        // Trailer: o do TMDB (pelo id da série) primeiro; o do servidor só sem ele (18/11/2026).
        TrailerLink.escolher(ext?.detalhe?.linkTrailer, f?.trailer)?.let { link ->
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.trailer), marca = "trailer") { VodActivity.abrirTrailer(this, link) })
        }
        // A mesma série na outra versão do catálogo (dublada x legendada): abre a ficha dela.
        outraVersao(s)?.let { o ->
            val r = if (Idioma.versaoComPar(o.nome, o.grupo, s.nome, s.grupo) == Idioma.Versao.LEGENDADA) R.string.idioma_versao_legendada else R.string.idioma_versao_dublada
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(r), marca = "versao") { verEpisodios = false; abrirSerie(o) })
        }
        // Ideia 84: elenco com foto e o que mais cada pessoa fez que está na lista.
        tmdbDe(s, f)?.takeIf { ElencoActivity.disponivel(it) }?.let { id ->
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.elenco_botao), marca = "elenco") { ElencoActivity.abrir(this, id, serie = true, nome = Vod.nomeLimpo(s.nome)) })
        }
        FichaAcoes.ondeAssistir(this, acoes, s.nome, s.ano, googleTv)
        FichaAcoes.conviteSimkl(this, acoes)
        val acaoSinopse = sinopse?.let { txt ->
            FichaAcoes.acao(this, getString(R.string.series_sinopse_mais), marca = "sinopse") { FichaAcoes.mostrarSinopse(this, Vod.nomeLimpo(s.nome), txt) }
                .also { FichaAcoes.adicionar(acoes, it); it.visibility = if (verEpisodios) View.VISIBLE else View.GONE }
        }
        if (acoes.childCount > 0) linha.addView(colAcoes, LinearLayout.LayoutParams(dp(FichaAcoes.LARGURA_DP), -1))

        // ---- direita: título e dados sempre; depois informações ou episódios
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(if (acoes.childCount > 0) dp(36) else 0, 0, 0, 0) }
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(TextView(this).apply { text = Vod.nomeTela(s.nome, s.ano); textSize = Estilo.Leitura.TITULO; typeface = fonteMedia; setTextColor(papel); maxLines = if (verEpisodios) 1 else 2; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(s.ano?.toString(), f?.temporadas?.size?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.series_temporadas, it, it) },
            (ext?.let { InfoExterna.notasDe(it).media } ?: notaRapida[s.chave] ?: s.nota)?.let { "★ %.1f".format(Locale.getDefault(), it) },   // a mesma da grade (21/11)
            // Gêneros do TMDB primeiro: vêm no idioma da TV; os do servidor vinham em inglês ("Action & Adventure", Sala 16/11).
            ext?.detalhe?.generos?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: f?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(s.grupo).ifEmpty { null },
            when (Idioma.versao(s.nome, s.grupo)) {
                Idioma.Versao.LEGENDADA -> getString(R.string.idioma_legendado)
                Idioma.Versao.DUBLADA -> getString(R.string.idioma_dublado_curto)
                Idioma.Versao.DESCONHECIDA -> null
            }).joinToString("   ·   ")
        texto.addView(TextView(this).apply { text = dados; textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPaddingRelative(0, dp(8), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        var txtSinopse: TextView? = null
        if (!verEpisodios) {
            // De fora: nota (Trakt) e "Novo episódio sexta, 21/11 (T2E5)" (TMDB), numa linha, em âmbar.
            val proximo = ext?.detalhe?.proximo?.let {
                InfoExterna.textoProximo(it, java.time.LocalDate.now(), getString(R.string.ext_proximo), getString(R.string.ext_hoje), getString(R.string.ext_amanha))
            }
            listOfNotNull(ext?.let { InfoExterna.notasDe(it) }?.takeIf { !it.vazia }?.let { InfoExterna.textoNotas(it) }, proximo).takeIf { it.isNotEmpty() }?.let { partes ->
                texto.addView(TextView(this).apply { text = partes.joinToString("   ·   "); textSize = Estilo.Leitura.DADOS; setTextColor(ambar); setPaddingRelative(0, dp(6), 0, 0); maxLines = 2 })
            }
            // Atribuição da nota do Simkl na ficha (17/11/2026): "Dados de Simkl (simkl.com)", discreta.
            if (ext?.let { InfoExterna.notasDe(it).simkl } != null)
                texto.addView(TextView(this).apply { text = getString(R.string.sync_fonte_simkl); textSize = Estilo.Leitura.DICA; setTextColor(suave); setPaddingRelative(0, dp(2), 0, 0) })
            // Ideia 82: "Também em: Netflix · Prime Video" (TMDB/JustWatch). DESLIGADO (OndeAssistir.JUSTWATCH_LIBERADO): licença.
            tmdbDe(s, f)?.takeIf { OndeAssistir.JUSTWATCH_LIBERADO && InfoExternaCache.temTmdb() }?.let { id ->
                val linhaOnde = TextView(this).apply { textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPaddingRelative(0, dp(6), 0, 0); visibility = View.GONE; maxLines = 1 }
                texto.addView(linhaOnde)
                Fundo.rodar { InfoExternaCache.provedores(id, serie = true).takeIf { it.isNotEmpty() }?.let { p ->
                    tela.post { linhaOnde.text = getString(R.string.onde_tambem_em, p.joinToString(" · ")); linhaOnde.visibility = View.VISIBLE }
                } }
            }
            // A série inteira: barra + "20 de 62 episódios" (13/11/2026), quando já tem episódio visto.
            progresso[s.chave]?.takeIf { it.total > 0 && it.vistos > 0 }?.let { p ->
                texto.addView(BarraTempo.Linha(this).apply {
                    mostrar(BarraTempo.fracao(p.vistos.toLong(), p.total.toLong()), BarraTempo.textoEpisodios(context, p.vistos, p.total))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        }
        topo.addView(texto, LinearLayout.LayoutParams(0, -2, 1f))
        if (!verEpisodios) {
            val capa = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Estilo.BORDA) }; clipToOutline = true
            }
            PlayerLogos.carregar(this, s.capa, capa, lado = 600)
            topo.addView(capa, LinearLayout.LayoutParams(dp(140), dp(210)).apply { marginStart = dp(24) })
        }
        col.addView(topo)

        var lista: ListView? = null
        when {
            f == null -> col.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = Estilo.Leitura.AVISO; setTextColor(apagado); setPaddingRelative(0, dp(14), 0, 0) })
            !temEps -> col.addView(TextView(this).apply { text = getString(R.string.series_sem_episodios); textSize = Estilo.Leitura.AVISO; setTextColor(suave); setPaddingRelative(0, dp(14), 0, 0) })
            verEpisodios -> {
                // "Temporada 2 · 6 de 13 vistos" e os episódios dela.
                val eps = f.temporadas[temporada].orEmpty()
                val vistosT = eps.count { Vod.finalizado(posicoes[it.chave(s.fonteId)], episodio = true) }
                col.addView(TextView(this).apply {
                    text = getString(R.string.series_temporada, temporada) + (if (eps.isNotEmpty() && vistosT > 0) "   ·   " + BarraTempo.textoEpisodios(context, vistosT, eps.size) else "")
                    textSize = Estilo.Leitura.DADOS; typeface = fonteMedia; setTextColor(papel); setPaddingRelative(dp(12), dp(16), 0, dp(4))
                })
                val l = ListView(this).apply { estilo(this); tag = "eps" }
                l.adapter = object : BaseAdapter() {
                    override fun getCount() = eps.size
                    override fun getItem(p: Int) = eps[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View = linhaEpisodio(s, eps[p], reuso)
                }
                l.setOnItemClickListener { _, _, p, _ -> eps.getOrNull(p)?.let { ep ->
                    tocar(s, ep, doComeco = !Vod.deveRetomar(posicoes[ep.chave(s.fonteId)], episodio = true))
                } }
                // OK segurado num episódio: marcar como visto até aqui, ou como não visto (11/11/2026, visto no 1 Play).
                l.setOnItemLongClickListener { _, _, p, _ -> eps.getOrNull(p)?.let { ep -> menuEpisodio(s, f, ep, p) }; true }
                col.addView(l, LinearLayout.LayoutParams(-1, 0, 1f))
                lista = l
            }
            else -> sinopse?.let {
                txtSinopse = TextView(this).apply { text = it; textSize = Estilo.Leitura.SINOPSE; setTextColor(papel); setLineSpacing(0f, 1.25f); maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(0, dp(16), 0, 0) }
                col.addView(txtSinopse)
            }
        }
        linha.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        painel.addView(linha, FrameLayout.LayoutParams(-1, -1))
        FundoFicha.por(this, painel, f?.cenas?.firstOrNull())   // a cena que a lista manda, atrás da ficha (18/11/2026)
        painel.visibility = View.VISIBLE
        // "Sinopse inteira" só aparece quando a sinopse não coube (dá para saber só depois de desenhada).
        val ts = txtSinopse
        if (ts != null && acaoSinopse != null) ts.post { if (FichaAcoes.cortada(ts)) acaoSinopse.visibility = View.VISIBLE }
        val l = lista
        when {
            l != null && focarEp != null -> painel.post { l.requestFocus(); l.setSelection(focarEp.coerceIn(0, (l.count - 1).coerceAtLeast(0))) }
            else -> (marcaAntes?.let { t -> acoes.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE } } ?: primeiro)?.requestFocus() ?: painel.requestFocus()
        }
        if (telaDeTras.visibility != View.INVISIBLE) telaDeTras.visibility = View.INVISIBLE   // o foco já está na ficha
    }

    /** "Trocar temporada": a lista das temporadas com onde parou ("✓" toda vista, "6/13" começada); escolher abre os episódios. */
    private fun escolherTemporada(s: Serie, f: FichaSerie) {
        val ts = f.temporadas.keys.toList()
        val rotulos = ts.map { t ->
            val daTemp = f.temporadas[t].orEmpty()
            val vistosT = daTemp.count { Vod.finalizado(posicoes[it.chave(s.fonteId)], episodio = true) }
            getString(R.string.series_temporada, t) + when { daTemp.isEmpty() || vistosT == 0 -> ""; vistosT >= daTemp.size -> "   ✓"; else -> "   $vistosT/${daTemp.size}" }
        }
        Estilo.lista(this, getString(R.string.series_trocar_temporada), rotulos, listOf(ts.indexOf(temporada)).filter { it >= 0 }) { i ->
            temporada = ts[i]; desenharFicha(focarEpisodio = 0)
        }
    }

    private fun menuEpisodio(s: Serie, f: FichaSerie, ep: Episodio, posicao: Int) {
        val semResumo = prefsPlayer().getBoolean(ANTERIORMENTE_OFF, false)
        val opcoes = listOf(getString(R.string.series_visto_ate_aqui, ep.rotulo), getString(R.string.series_nao_visto)) +
            listOfNotNull(getString(R.string.anteriormente_ligar).takeIf { semResumo })
        Estilo.lista(this, "${ep.rotulo} · ${Series.tituloCurto(s.nome, ep.titulo)}", opcoes) { i ->
            if (i == 2) { prefsPlayer().edit().putBoolean(ANTERIORMENTE_OFF, false).apply(); return@lista }
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
            orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
            val topo = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM; typeface = Fontes.nunito(600); setTextColor(ambar) }, LinearLayout.LayoutParams(dp(48), -2))
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM; typeface = fonteMedia; setTextColor(papel); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            topo.addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(apagado) })
            addView(topo)
            addView(TextView(context).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(dp(48), dp(3), 0, 0) })
            addView(BarraTempo.Barra(context), LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(6); marginStart = dp(48) })
        }
        val topo = v.getChildAt(0) as LinearLayout
        (topo.getChildAt(0) as TextView).text = ep.numero.toString()
        (topo.getChildAt(1) as TextView).text = Series.tituloCurto(s.nome, ep.titulo)
        val pos = posicoes[ep.chave(s.fonteId)]
        // Esconder spoiler (16/11/2026, ideia do Stremio): episódio nunca aberto não mostra o resumo, se a chave estiver ligada.
        val escondido = ep.sinopse.isNotEmpty() && ConfortoRegras.esconderResumo(semSpoiler, pos != null)
        (v.getChildAt(1) as TextView).apply {
            text = if (escondido) getString(R.string.cf_resumo_escondido) else ep.sinopse
            visibility = if (ep.sinopse.isEmpty()) View.GONE else View.VISIBLE
        }
        val barra = v.getChildAt(2) as BarraTempo.Barra
        if (pos != null && pos.duracao > 0) { barra.visibility = View.VISIBLE; barra.fracao = BarraTempo.fracao(pos.ms, pos.duracao) }
        else barra.visibility = View.INVISIBLE
        // À direita do nome: "faltam 35 min · termina às 17:28" se começou, "visto" se acabou, senão a duração.
        (topo.getChildAt(2) as TextView).text = when {
            pos != null && pos.duracao > 0 && Vod.deveRetomar(pos, episodio = true) -> (pos.duracao - pos.ms).coerceAtLeast(0).let { r ->
                BarraTempo.textoFaltam(this, r, System.currentTimeMillis() + r) }
            Vod.finalizado(pos, episodio = true) -> getString(R.string.tempo_visto)
            else -> ep.duracaoMin?.let { "$it min" }.orEmpty()
        }
        return v
    }

    private fun tmdbDe(s: Serie, f: FichaSerie?): Int? = s.tmdb ?: f?.tmdb ?: externo?.takeIf { it.first.chave == s.chave }?.second?.tmdb

    /**
     * Ideia 83 "Anteriormente" (14/11/2026): série parada há mais de 3 semanas, antes do próximo episódio um cartão com o
     * resumo do anterior (TMDB no idioma da TV; sem ele, a sinopse da lista). Sem resumo, ou desligado, toca direto.
     */
    private fun talvezAnteriormente(s: Serie, f: FichaSerie, ep: Episodio, doComeco: Boolean) {
        val todos = Series.emOrdem(f)
        val ant = if (prefsPlayer().getBoolean(ANTERIORMENTE_OFF, false)) null
            else ElencoTmdb.anterior(todos, ep, ElencoTmdb.ultimaVez(todos, s.fonteId, posicoes, ultimos[s.chave]), System.currentTimeMillis())
        if (ant == null) { tocar(s, ep, doComeco); return }
        val id = tmdbDe(s, f)
        Fundo.rodar {
            val deFora = id?.let { InfoExternaCache.episodio(it, ant.temporada, ant.numero) }
            val resumo = deFora?.second ?: ant.sinopse.trim().takeIf { it.isNotEmpty() }
            val nome = deFora?.first?.takeIf { it.isNotBlank() } ?: Series.tituloCurto(s.nome, ant.titulo)
            tela.post {
                if (isDestroyed || aberta !== s) return@post
                if (resumo == null) tocar(s, ep, doComeco) else mostrarAnteriormente(s, ep, doComeco, "${ant.rotulo} · $nome", resumo)
            }
        }
    }

    private fun mostrarAnteriormente(s: Serie, ep: Episodio, doComeco: Boolean, titulo: String, resumo: String) {
        val d = android.app.Dialog(this)
        val nunca = Estilo.botao(this, getString(R.string.anteriormente_pular_sempre), primario = false)
        val ver = Estilo.botao(this, getString(R.string.anteriormente_assistir, ep.rotulo))
        nunca.setOnClickListener {
            d.dismiss(); prefsPlayer().edit().putBoolean(ANTERIORMENTE_OFF, true).apply()
            avisoBreve(getString(R.string.anteriormente_desligado)); tocar(s, ep, doComeco)
        }
        ver.setOnClickListener { d.dismiss(); tocar(s, ep, doComeco) }
        Estilo.vestir(d, Estilo.dialogo(this, getString(R.string.anteriormente_titulo, Vod.nomeLimpo(s.nome)), titulo, resumo, listOf(nunca, ver)))
        d.setOnShowListener { ver.requestFocus() }
        d.show()
    }

    private fun tocar(s: Serie, ep: Episodio, doComeco: Boolean) {
        tocando = ep
        indoAoVideo = true
        val agora = System.currentTimeMillis()
        // Começar um episódio conclui o anterior, se ele estava começado (14/11/2026).
        fichaAberta?.let { f ->
            val novas = Series.concluirAnterior(f, s.fonteId, ep, posicoes, agora)
            if (novas !== posicoes) {
                val mudou = novas.keys.firstOrNull { novas[it] != posicoes[it] }
                posicoes = novas
                if (mudou != null) { val p = novas[mudou]; Fundo.rodar { PlayerArmazem.definirPosicao(this, mudou, p) } }
            }
        }
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
        // Dublado x legendado: com a outra versão carregada, o ▼ do player troca no mesmo ponto. Se a pessoa já trocou
        // nesta série (a escolha fica guardada na chave dela), os próximos episódios abrem direto na versão escolhida.
        val titulo = "${Vod.nomeLimpo(s.nome)} · ${ep.rotulo} · ${Series.tituloCurto(s.nome, ep.titulo)}"
        val outra = outraFicha?.takeIf { it.first.chave == outraVersao(s)?.chave }
        val epOutra = outra?.let { Series.mesmoEpisodio(it.second, ep) }
        val vOutra = outra?.let { Idioma.versaoComPar(it.first.nome, it.first.grupo, s.nome, s.grupo) } ?: Idioma.Versao.DESCONHECIDA
        val vEsta = outra?.let { Idioma.versaoComPar(s.nome, s.grupo, it.first.nome, it.first.grupo) } ?: Idioma.versao(s.nome, s.grupo)
        val naOutra = epOutra != null && prefsIdioma.titulos[s.chave]?.let { Idioma.versaoDesejada(it) == vOutra } == true
        val (srcSerie, srcEp) = if (naOutra) outra!!.first to epOutra!! else s to ep
        val prox = fichaAberta?.let { Series.proximo(it, ep) }
        val temProximo = prox != null
        // Na outra versão (dublado/legendado), o id do seguinte é o daquela ficha: sem ele à mão, não prepara.
        val proximoEp = prox?.takeIf { !naOutra }
        val contagem = Conforto.contagem(this)
        VodActivity.abrirComResultado(this, CODIGO_VIDEO, VodActivity.TIPO_EPISODIO, srcSerie.fonteId, srcEp.id, srcEp.extensao,
            titulo, doComeco, chaveIdioma = s.chave,
            busca = LegendaExterna.Busca(LegendaExterna.nomeDeBusca(s.nome), temporada = ep.temporada, episodio = ep.numero,
                tmdb = s.tmdb ?: externo?.takeIf { it.first.chave == s.chave }?.second?.tmdb, versao = if (naOutra) vOutra else vEsta)) { i ->
            // Proposta C, etapa 1: com episódio seguinte, o player oferece "Próximo episódio" já nos créditos.
            if (temProximo) i.putExtra(VodActivity.EXTRA_PROXIMO_CONTAGEM, contagem)
            // Item 11: o seguinte vai junto para o player preparar marcas e legenda antes do fim (mesma versão/fonte).
            proximoEp?.let { n -> i.putExtra(VodActivity.EXTRA_PROXIMO_EP, "${n.id}|${n.temporada}|${n.numero}") }
            // Zero tela preta: abre sobre a cena da série com "Série · T1 E2" e o nome do episódio, não sobre o preto.
            VodActivity.comCortina(i, cenas.firstOrNull() ?: capa, "${Vod.nomeLimpo(s.nome)} · T${ep.temporada} E${ep.numero}",
                Series.tituloCurto(s.nome, ep.titulo))
            when {
                epOutra == null -> i
                naOutra -> VodActivity.comOutraVersao(i, s.fonteId, ep.id, ep.extensao, titulo, vEsta)
                else -> VodActivity.comOutraVersao(i, outra!!.first.fonteId, epOutra.id, epOutra.extensao, titulo, vOutra)
            }
        }
    }

    // API antiga (deprecated), mas é a que existe numa Activity comum, sem AndroidX.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_VIDEO) return
        // Zero tela preta, parte 2 (20/11/2026): o próximo episódio já foi escolhido no player. Sem isto a ficha da série
        // piscava entre um episódio e o outro; agora um véu escuro cobre a tela já no primeiro quadro desta volta, e a
        // cortina do próximo episódio entra por cima. Sai sozinho quando o player cobre a tela (onStop) ou em 4 s.
        if (resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_PROXIMO_JA, false) == true) cobrirPonte()
        val s = aberta; val f = fichaAberta; val ep = tocando
        // Posições mudaram: relê e redesenha a ficha (barras e botão principal).
        Fundo.rodar {
            val p = PlayerArmazem.posicoes(this)
            // A troca de versão no player grava a escolha: relê para o próximo episódio já sair na versão escolhida.
            val pi = IdiomaPlayer.prefs(this)
            tela.post {
                if (isDestroyed) return@post
                posicoes = p; prefsIdioma = pi
                if (s != null && f != null) atualizarProgresso(s, f)
                val terminou = resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
                val teclou = data?.getBooleanExtra(VodActivity.RESULTADO_TECLA, true) ?: true
                val desligou = data?.getBooleanExtra(VodActivity.RESULTADO_DESLIGOU, false) == true
                seguidos = FimDoVideo.seguidos(seguidos, teclou || !terminou)
                val prox = if (terminou && s != null && f != null && ep != null) Series.proximo(f, ep) else null
                // Ideia 19 (14/11/2026): antes o próximo começava na hora. Agora: contagem de 10 s com cancelar; depois de
                // 3 seguidos sem tecla, pergunta "ainda está assistindo?"; e nada segue se era para desligar ao fim.
                val contagem = Conforto.contagem(this)
                val passo = FimDoVideo.depois(terminou, prox != null, desligou, seguidos, contagem)
                // "Próximo episódio" já escolhido nos créditos (OK ou contagem no player): começa sem contar de novo.
                val ja = data?.getBooleanExtra(VodActivity.RESULTADO_PROXIMO_JA, false) == true
                if (prox != null && s != null && ja && passo == FimDoVideo.Passo.PROXIMO_CONTANDO) {
                    temporada = prox.temporada; desenharFicha(); tocar(s, prox, doComeco = true)
                }
                else if (prox != null && s != null && passo != FimDoVideo.Passo.NADA && passo != FimDoVideo.Passo.DESLIGAR) {
                    temporada = prox.temporada; desenharFicha()
                    avisarProximo(s, prox, perguntar = passo == FimDoVideo.Passo.PERGUNTAR)
                }
                else { adaptadorGrade.notifyDataSetChanged(); recalcular(ModoGrade.IGUAL); if (aberta != null) desenharFicha() }
            }
        }
    }

    /**
     * Ideia 19: cartão por cima da ficha antes do próximo episódio. [perguntar] = false: "Assistir agora · 10 s" contando
     * (os segundos vêm das Configurações desde 16/11/2026, [Conforto.contagem]) e
     * começa sozinho no zero; VOLTAR ou "Cancelar" fica na ficha. [perguntar] = true: "Ainda está assistindo?" sem contagem.
     */
    private fun avisarProximo(s: Serie, prox: Episodio, perguntar: Boolean) {
        avisoProximo?.dismiss()
        val d = android.app.Dialog(this)
        var resta = Conforto.contagem(this).takeIf { it > 0 } ?: FimDoVideo.CONTAGEM_S
        val parar = Estilo.botao(this, getString(R.string.vod_proximo_cancelar), primario = false)
        val ver = Estilo.botao(this, if (perguntar) getString(R.string.vod_proximo_continuar) else getString(R.string.vod_proximo_agora, resta))
        val tique = object : Runnable {
            override fun run() {
                if (!d.isShowing) return
                resta--
                if (resta <= 0) { d.dismiss(); tocar(s, prox, doComeco = true); return }
                ver.text = getString(R.string.vod_proximo_agora, resta)
                tela.postDelayed(this, 1_000)
            }
        }
        parar.setOnClickListener { d.dismiss() }
        ver.setOnClickListener { d.dismiss(); if (perguntar) seguidos = 0; tocar(s, prox, doComeco = true) }
        val titulo = "${prox.rotulo} · ${Series.tituloCurto(s.nome, prox.titulo)}"
        Estilo.vestir(d, Estilo.dialogo(this, getString(if (perguntar) R.string.vod_ainda_assistindo else R.string.vod_proximo_episodio),
            titulo, if (perguntar) getString(R.string.vod_ainda_assistindo_texto) else null, listOf(parar, ver)))
        d.setOnShowListener { ver.requestFocus() }
        d.setOnDismissListener { tela.removeCallbacks(tique); if (avisoProximo === d) avisoProximo = null }
        avisoProximo = d
        d.show()
        if (!perguntar) tela.postDelayed(tique, 1_000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Na lista de episódios, VOLTAR volta às informações da série (com o foco em "Episódios"); depois fecha a ficha.
        if (painel.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK && verEpisodios && fichaAberta != null) {
            verEpisodios = false; desenharFicha(); painel.findViewWithTag<View>("episodios")?.requestFocus(); return true
        }
        if (painel.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) { fecharFicha(); return true }
        if (painel.visibility != View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK && !coluna.temFoco() && itens.isNotEmpty()) { coluna.focar(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- peças

    private fun botao(texto: String, pequeno: Boolean = false, marcado: Boolean = false, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = if (pequeno) Estilo.Leitura.BOTAO_PEQUENO else Estilo.Leitura.BOTAO; typeface = fonteMedia
        setPaddingRelative(dp(if (pequeno) 18 else 26), dp(if (pequeno) 8 else 12), dp(if (pequeno) 18 else 26), dp(if (pequeno) 8 else 12))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            // Sem foco: mais apagado (6% + fio, texto a 70%), para o botão branco com foco saltar aos olhos a 3 m (15/11/2026).
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (marcado) 0x4DF5B544 else Estilo.SUPERFICIE); if (!marcado) setStroke(dp(1), Estilo.BORDA) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.FUNDO, if (marcado) papel else Estilo.TEXTO_2)))
        setOnClickListener { aoApertar() }
    }

    private fun estilo(l: AbsListView) {
        l.isVerticalScrollBarEnabled = false
        PlayerFoco.molduraSoComFoco(l, GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Estilo.SUPERFICIE_ALTA); setStroke(dp(2), papel) })
        l.setDrawSelectorOnTop(l is GridView)
        l.cacheColorHint = Color.TRANSPARENT
        if (l is ListView) l.divider = null
    }

    private fun relogio(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

}

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
        const val MIN_PULAR = 30   // grade menor que isso se anda no controle mesmo
        const val ESPERA_EXTERNO_MS = 400L   // quanto a ficha do servidor espera pela de fora (com MDBList/Simkl ligados ela vem 150-400 ms depois, medido 16/11)
        const val BUSCAR = "*buscar"
        const val CONTINUAR = "*continuar"
        const val INTERESSES = "*interesses"
        const val ASSISTIDOS = "*assistidos"
        const val TODOS = "*todos"
        const val BUSCA = "*busca"
        const val FILTRO = "*filtro"       // ideia 23: menu do filtro combinado
        const val COLECAO = "*colecao"     // ideia 23: "Coleção deste filme" (aberta pela ficha)
    }


    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val suave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val ambar = Estilo.ATENCAO
    private val verdeEtiqueta = 0xE62E7D4F.toInt()
    private val fonteMedia = Fontes.nunito(500)

    private var filmes: List<Filme> = emptyList()      // o que a tela mostra (sem as categorias ocultas, até o PIN)
    // Nota de fora (Trakt, InfoExterna): valem no Filtro (nota mínima) e na ordem "Nota".
    @Volatile private var notasFora: Map<String, Float> = emptyMap()
    /** Listas de fora escolhidas (Trakt, MDBList) com os itens: viram categorias em LISTAS (fatia 3, esboço listas-a.png). */
    @Volatile private var listasFora: List<Pair<SincronizarFilmes.ListaFora, List<SincronizarFilmes.Item>>> = emptyList()
    // Ficha de fora (TMDB + notas) do filme da ficha aberta, e a contagem da saga na lista: (nome, tem, total).
    private var externo: Pair<Filme, InfoExterna.Guardado>? = null
    private var sagaExterna: Pair<Filme, Triple<String, Int, Int>>? = null
    private var todos: List<Filme> = emptyList()       // o catálogo inteiro, como veio do servidor
    private var ocultas: Set<String> = emptySet()      // categorias escondidas (adultas, escondidas, controle parental)
    @Volatile private var porIdade: IdadesTmdb.Filtro? = null   // ideia 91: modo infantil com idade máxima
    private var mostrarOcultas = false                 // true só depois do PIN, como no player da TV ao vivo
    private var indoAoVideo = false                    // saiu para a VodActivity: o PIN continua valendo na volta
    private val janelas by lazy { PlayerJanelas(this) }
    private var posicoes: Map<String, Posicao> = emptyMap()
    private var interesses: Set<String> = emptySet()
    /** De que perfil são os interesses e posições na memória (ideia 11): trocou de perfil, a tela se refaz ao voltar. */
    private var perfilCarregado = ""
    private val segurar = SegurarOk()
    private var ordem = Vod.Ordem.RECENTES
    private var itens: List<ColunaCategorias.Linha> = emptyList()   // as três partes da coluna, juntas (para achar o nome)
    /** "N novos" por categoria (14/11/2026): o filme mais novo que a pessoa já tinha visto no catálogo, da visita anterior. */
    private var marcoNovos = 0L
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

    private lateinit var coluna: ColunaCategorias
    /** Modo simples da HOME (ModoSimples): o player segue, com menos linhas, letra maior e entrada no Continuar. */
    private val simples by lazy { ModoSimples.ligado(this) }
    private lateinit var grade: GridView
    private lateinit var painel: PainelCatalogo   // topo (categoria + pílulas do filtro) e a ficha rápida do pôster em foco
    private lateinit var aviso: TextView
    private lateinit var painelFicha: FrameLayout

    /** A grade e a coluna, atrás da ficha. Escondidas enquanto a ficha está aberta: a ficha cobre a tela toda e,
     *  visíveis, eram desenhadas de novo a cada quadro (fundo, 10 pôsteres, a coluna), o que pesava ao abrir (15/11/2026). */
    private lateinit var telaDeTras: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        perfilCarregado = Perfil.chaveDados(this)
        ordem = runCatching { Vod.Ordem.valueOf(getSharedPreferences("player", MODE_PRIVATE).getString("ordem_filmes", "")!!) }.getOrDefault(Vod.Ordem.RECENTES)
        // Coerência visual parte 2 (12/11/2026): o grafite e a margem da HOME (eram FUNDO liso e 32/26 dp).
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(context) }
        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(18)) }
        // Coluna esquerda nova (14/11/2026, esboço aprovado): ações fixas, LISTAS, Todos fixo, categorias de A a Z rolando.
        coluna = ColunaCategorias(this, getString(R.string.filmes_titulo), aoParar = { escolherCat(it) }, aoOk = { okNaColuna(it) },
            escala = PlayerAcessivel.escala(simples))
        colunas.addView(coluna.raiz, LinearLayout.LayoutParams(dp((Estilo.Leitura.COLUNA_CATEGORIAS * PlayerAcessivel.escala(simples)).toInt()), -1))
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Painel direito (esboço direita-filmes.png, aprovado em 14/11/2026): o filtro em pílulas e a ficha rápida.
        painel = PainelCatalogo(this)
        direita.addView(painel.topo)
        direita.addView(painel.faixa, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        painel.alinharCom(coluna.caixaAcoes)
        grade = GridView(this).apply {
            numColumns = Estilo.Leitura.GRADE_COLUNAS
            horizontalSpacing = dp(PosterGridSize.VAO_COLUNAS); verticalSpacing = dp(PosterGridSize.VAO_FILEIRAS)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            estiloLista(this)
            setPaddingRelative(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        // Colunas e altura do pôster pelo espaço real da grade (PosterGridSize): 2 fileiras inteiras, sem fileira cortada.
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
        // Sem fundo próprio (16/11/2026): a grade de trás fica INVISIBLE com a ficha aberta e o grafite da raiz aparece
        // (eram dois fundos de tela cheia, um sobre o outro, no quadro de abrir a ficha).
        painelFicha = FrameLayout(this).apply { visibility = View.GONE }
        raiz.addView(painelFicha, FrameLayout.LayoutParams(-1, -1))
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPaddingRelative(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.filmes_carregando)
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
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { mostrarFocado(naGrade.getOrNull(pos)) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grade.setOnItemClickListener { _, _, pos, _ -> naGrade.getOrNull(pos)?.let { abrirFicha(it, trocar = true) } }
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
        // Voltou da VodActivity: a posição mudou ("continuar" e os botões da ficha).
        indoAoVideo = false
        val lista = todos
        if (lista.isNotEmpty()) Fundo.rodar {
            val p = PlayerArmazem.posicoes(this)
            val oc = lerOcultas(lista)   // o controle parental pode ter mudado nas Configurações
            val fi = IdadesTmdb.filtro(this, lista.map { it.grupo }, serie = false)
            val pi = IdiomaPlayer.prefs(this)   // trocar o áudio no filme muda a escolha dele
            tela.post {
                if (isDestroyed) return@post
                val mudou = oc != ocultas || fi?.max != porIdade?.max
                posicoes = p; prefsIdioma = pi; ocultas = oc; porIdade = fi
                // "Assistidos" e "Continuar" mudam de conteúdo ao voltar do vídeo: refaz a grade sem perder o lugar.
                recalcular(if (mudou || chave == ASSISTIDOS || chave == CONTINUAR || filtro.naoVistos) ModoGrade.MANTER_LUGAR else ModoGrade.IGUAL)
                adaptadorGrade.notifyDataSetChanged()   // a barrinha de progresso do card muda na hora
                ficha?.let { abrirFicha(it, manterFoco = true) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!indoAoVideo) guardarMarco()
        // O PIN vale só enquanto a tela está na frente (igual ao player): saiu para a HOME ou outro app, tranca de novo.
        // Ir assistir um filme daqui não conta, senão a cada filme da categoria liberada teria de digitar o PIN.
        if (mostrarOcultas && !indoAoVideo) {
            mostrarOcultas = false
            if (ficha?.let { it.grupo.trim() in ocultas } == true) fecharFicha()
            refiltrarOcultas()
        }
    }

    override fun onDestroy() {
        calculo.fechar()
        if (!VigiaAtualizacao.fechada(this)) coluna.parar()   // modo seguro: o onCreate parou antes de criar a coluna
        tela.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Preferências por perfil (ideia 11): a última categoria e o marco dos "novos" são de cada pessoa.
    private fun prefsPlayer() = getSharedPreferences("player", MODE_PRIVATE)
    private fun chavePref(nome: String) = "${nome}_filmes_" + Perfil.chaveDados(this)

    /** Guarda o marco dos "novos": na próxima visita, conta o que entrou depois de agora. */
    private fun guardarMarco() {
        val lista = todos; val antes = marcoNovos
        if (lista.isEmpty()) return
        Fundo.rodar {
            val m = ColunaRegras.novoMarco(lista, antes) { it.adicionado }
            if (m != antes) prefsPlayer().edit().putLong(chavePref("marco"), m).apply()
        }
    }

    /** OK numa linha da coluna: as ações abrem a janela delas; categoria abre e leva o foco para a grade. */
    private fun okNaColuna(k: String) {
        when (k) {
            BUSCAR -> buscar()
            FILTRO -> menuFiltroUnico()
            else -> escolherCat(k, focarGrade = true)
        }
    }

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
            val fi = IdadesTmdb.filtro(this, f.map { it.grupo }, serie = false)
            notasFora = InfoExternaCache.notas(this)
            val pi = IdiomaPlayer.prefs(this)
            val marco = prefsPlayer().getLong(chavePref("marco"), 0L)
            val ultima = prefsPlayer().getString(chavePref("ultima"), null)
            tela.post {
                if (isDestroyed) return@post
                todos = f; ocultas = oc; porIdade = fi; posicoes = p; interesses = i; prefsIdioma = pi; marcoNovos = marco
                ultimaSalva = ultima
                if (f.isEmpty()) {
                    aviso.visibility = View.GONE
                    vazioMostrado = true
                    val vazio = vazioDoCatalogo(this, getString(R.string.filmes_vazio))
                    (aviso.parent as FrameLayout).addView(vazio, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    vazio.getFocusables(View.FOCUS_DOWN).firstOrNull()?.requestFocus()   // o botão "Adicionar lista" já vem focado
                    return@post
                }
                // Aberta pela busca única da HOME (12/11/2026): já entra com o termo buscado.
                intent.getStringExtra(BuscaUnica.EXTRA_TERMO)?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> termo = t; chave = BUSCA }
                // A 1ª conta (ocultas, contagens, ordem) também sai da thread da tela; o "Carregando" fica até ela chegar.
                recalcular(ModoGrade.NOVA, inicialPedido = true) { aviso.visibility = View.GONE; grade.requestFocus() }
                sincronizarFora(f, forcar = false)
                classificarIdades(f)
                // O índice das versões sai depois da grade na tela (é a parte demorada; a ficha funciona sem ele).
                Fundo.rodar {
                    val idx = Idioma.indice(f, { it.nome }, { it.grupo })
                    // Com o índice, a grade junta as versões do mesmo filme; o foco fica onde estava.
                    tela.post { if (!isDestroyed && todos === f) { indiceVersoes = idx; recalcular(ModoGrade.MANTER_LUGAR) } }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Trakt/Simkl e listas de fora (fatia 3)

    @Volatile private var cacheTitulos: Pair<List<Filme>, List<SincronizarFilmes.Titulo>>? = null

    /** Chave, id TMDB (da lista ou já achado) e nome+ano de cada filme, para cruzar com as listas de fora. Fora da tela. */
    private fun titulosDoCatalogo(vis: List<Filme>): List<SincronizarFilmes.Titulo> =
        cacheTitulos?.takeIf { it.first === vis }?.second ?: vis.map {
            SincronizarFilmes.Titulo(it.chave, InfoExternaCache.tmdbSemRede(this, it.chave, it.tmdb), InfoExterna.chaveNomeAno(Vod.nomeLimpo(it.nome), it.ano))
        }.also { cacheTitulos = vis to it }

    private fun etiquetaLista(l: SincronizarFilmes.ListaFora) = if (l.servico == "trakt") "Trakt" else "MDBList"

    /**
     * Ao abrir: as listas escolhidas (do disco já; da rede se venceram) e a sincronização com Trakt/Simkl (no máximo a
     * cada 20 min). Mudou algo aqui: relê Interesses e posições e refaz a coluna sem perder o lugar.
     */
    private fun sincronizarFora(f: List<Filme>, forcar: Boolean) {
        val escolhidas = ServicosSync.listas(this)
        val algumaConta = ServicosSync.ativo(this, ServicosFilmes.Servico.TRAKT) || ServicosSync.ativo(this, ServicosFilmes.Servico.SIMKL)
        if (escolhidas.isEmpty() && !algumaConta) { listasFora = emptyList(); return }
        Fundo.rodar("filmes-fora") {
            if (escolhidas.isNotEmpty()) {
                listasFora = escolhidas.map { it to ServicosSync.itensGuardados(this, it) }
                tela.post { if (!isDestroyed) recalcular(ModoGrade.IGUAL) }
                listasFora = escolhidas.map { it to ServicosSync.itens(this, it, forcar) }
                tela.post { if (!isDestroyed) recalcular(ModoGrade.IGUAL) }
            }
            if (!algumaConta) return@rodar
            val m = ServicosSync.sincronizar(this, SincronizarFilmes.Tipo.FILME, titulosDoCatalogo(f), forcar)
            if (!m.interesses && !m.posicoes) return@rodar
            val i = PlayerArmazem.interesses(this, series = false); val p = PlayerArmazem.posicoes(this)
            tela.post {
                if (isDestroyed) return@post
                interesses = i; posicoes = p
                adaptadorGrade.notifyDataSetChanged()
                recalcular(ModoGrade.MANTER_LUGAR)
            }
        }
    }

    // ---------------------------------------------------------------- categorias

    /** A última categoria aberta (por perfil), lida no carregar: a tela volta nela (acréscimo aprovado, 14/11/2026). */
    private var ultimaSalva: String? = null

    /** O que fazer com a grade quando a conta chegar. */
    /** A ordem importa: do mais forte ao mais fraco ([GridRequest.Need.strength] = 2 - ordinal). */
    private enum class ModoGrade { NOVA, MANTER_LUGAR, IGUAL }

    /** Resultado de uma conta: tudo que a tela precisa, calculado fora dela. */
    private class Estado(val filmes: List<Filme>, val resultados: List<Filme>, val acoes: List<ColunaCategorias.Linha>,
                         val botoes: List<ColunaCategorias.Linha>,
                         val fixas: List<ColunaCategorias.Linha>, val rola: List<ColunaCategorias.Linha>, val chave: String,
                         /** "Todos" como botão ao lado de LISTAS, igual na TV (Paulo, 21/11/2026). */
                         val botoesListas: List<ColunaCategorias.Linha>,
                         val grade: List<Filme>?, val deLista: Pair<Int, Int>? = null)

    private val calculo = CalculoTela(tela, "filmes-conta")

    // Caches da conta (só a thread da [calculo] lê e escreve). A chave é a própria lista (===): lista nova = refaz.
    private var cacheVisiveis: Triple<List<Filme>, Set<String>, List<Filme>>? = null
    private class SemPares(val v: List<Filme>, val idx: Map<String, List<Filme>>, val pos: Map<String, Posicao>,
                           val ints: Set<String>, val res: List<Filme>)
    @Volatile private var cacheSemPares: SemPares? = null
    @Volatile private var cacheIdade: IdadesTmdb.Filtro? = null

    private fun tmdbDe(f: Filme): Int? = InfoExternaCache.tmdbSemRede(this, f.chave, f.tmdb)

    /** Ideia 91: com idade máxima, pergunta ao TMDB a classificação dos que faltam (infantis primeiro) e refaz a grade. */
    private fun classificarIdades(f: List<Filme>) {
        val fi = porIdade ?: return
        Fundo.rodar("filmes-idades") {
            val ids = f.filter { it.grupo.trim() !in fi.duras }.sortedBy { if (it.grupo.trim() in fi.infantis) 0 else 1 }.mapNotNull { tmdbDe(it) }
            if (IdadesTmdb.classificar(this, serie = false, ids) > 0) {
                val novo = IdadesTmdb.filtro(this, f.map { it.grupo }, serie = false)
                tela.post { if (!isDestroyed && todos === f) { porIdade = novo; recalcular(ModoGrade.MANTER_LUGAR) } }
            }
        }
    }
    private var cacheBusca: Triple<List<Filme>, String, List<Filme>>? = null
    private var cacheOrdem: Triple<List<Filme>, Vod.Ordem, List<Filme>>? = null
    private var cacheOrdemNotas: Map<String, Float>? = null
    private var cacheGrupos: Pair<List<Filme>, List<Pair<String, Int>>>? = null
    private var cacheNovos: Triple<List<Filme>, Long, Map<String, Int>>? = null

    /**
     * Refaz, FORA da thread da tela, as categorias ocultas, a busca, as contagens das categorias e (conforme [modo]) a
     * grade. Pedidos seguidos (rolar pelas categorias) descartam os velhos. [inicial]: escolhe a categoria de entrada
     * (Continuar se houver). [depois] roda na tela com a grade nova já posta.
     */
    /** Pedido que ainda não chegou à tela: o próximo herda o que ele exigia ([GridRequest]). */
    private var pedidoPendente: GridRequest.Need? = null

    private fun recalcular(modoPedido: ModoGrade, inicialPedido: Boolean = false, depoisPedido: (() -> Unit)? = null) {
        val pedido = GridRequest.merge(pedidoPendente, GridRequest.Need(2 - modoPedido.ordinal, inicialPedido, depoisPedido))
        pedidoPendente = pedido
        val modo = ModoGrade.entries[2 - pedido.strength]; val inicial = pedido.initial; val depois = pedido.after
        val todosS = todos; val ocultasS = if (mostrarOcultas) emptySet() else ocultas; val mostrar = mostrarOcultas
        val idadeS = porIdade
        val idxS = indiceVersoes
        val termoS = termo; val posS = posicoes; val intS = interesses; val ordemS = ordem; val chaveS = chave; val notasS = notasFora
        val listasS = listasFora
        val temOcultas = ocultas.isNotEmpty()
        val filtroS = filtro; val colecaoS = colecao; val marcoS = marcoNovos; val ultimaS = ultimaSalva
        val rotulo = { id: Int -> getString(id) }
        val antesSel = grade.selectedItemPosition
        calculo.pedir(
            calcular = {
                // Entre parênteses de propósito: sem eles o ".let" de baixo só valia quando o cache errava (o "?:" pega
                // tudo à direita), e a grade com o índice pronto voltava sem juntar nada (visto na Sala pelo log, 19/11/2026).
                val vis = (cacheVisiveis?.takeIf { it.first === todosS && it.second == ocultasS && cacheIdade === idadeS }?.third
                    ?: (when {
                        mostrar -> todosS
                        idadeS != null -> todosS.filter { Classificacao.visivel(it.grupo, tmdbDe(it)?.let(idadeS.idades::get), idadeS.max, idadeS.infantis, idadeS.duras) }
                        else -> VodFiltro.visiveis(todosS, ocultasS) { it.grupo }
                    }).also { cacheVisiveis = Triple(todosS, ocultasS, it); cacheIdade = idadeS })
                    // Um item por filme: dublado e legendado do mesmo título viram um (a ficha tem as duas versões).
                    // Fica o que tem algo guardado (posição, visto ou Interesses); com algo nos dois, os dois ficam.
                    // A conta refaz quando posições ou Interesses mudam (voltar do vídeo troca o mapa).
                    .let { v -> cacheSemPares?.takeIf { it.v === v && it.idx === idxS && it.pos === posS && it.ints === intS }?.res
                        ?: Idioma.semPares(v, idxS, { it.nome }, { it.grupo }, { it.ano }, { posS.containsKey(it.chave) || it.chave in intS })
                            { android.util.Log.i("TvLauncher.Filmes", "versoes: $it") }
                            .also { cacheSemPares = SemPares(v, idxS, posS, intS, it) } }
                val res = if (termoS.isEmpty()) emptyList() else cacheBusca?.takeIf { it.first === vis && it.second == termoS }?.third
                    ?: Vod.buscar(vis, termoS).also { cacheBusca = Triple(vis, termoS, it) }
                val cont = Vod.continuar(vis, posS)
                val ints = Vod.interesses(vis, intS) { it.chave }
                val vistos = Vod.assistidos(vis, posS)
                fun L(c: String, n: String, q: Int, i: Any?, nv: Int, d: Boolean, det: String, et: String = "") =
                    ColunaCategorias.Linha(c, n, q, i, nv, d, det, et)
                // Buscar e Filtro viram botões redondos ao lado do título (pedido do Paulo: mais espaço na lista).
                // No modo simples fica só Buscar.
                val botoes = listOfNotNull(
                    L(BUSCAR, rotulo(R.string.player_acao_buscar), -1, IconeMidia.Tipo.LUPA, 0, false, ""),
                    if (simples) null else L(FILTRO, rotulo(R.string.coluna_filtro), -1, IconePlayer.Tipo.FILTROS, 0, filtroS.ativo || mostrar, ""),
                )
                // Bloco fixo (esboço aprovado): sempre as mesmas três linhas, para a mão decorar onde fica cada uma.
                val acoes = listOf(
                    L(INTERESSES, rotulo(R.string.filmes_interesses), ints.size, IconePlayer.Tipo.ESTRELA, 0, false, ""),
                    L(CONTINUAR, rotulo(R.string.filmes_continuar), cont.size, IconePlayer.Tipo.PLAY, 0, false, ""),
                    L(ASSISTIDOS, rotulo(R.string.filmes_assistidos), vistos.size, IconePlayer.Tipo.VISTO, 0, false, ""),
                )
                    // Modo simples (HOME no modo simples): só Interesses e Continuar no bloco fixo.
                    .let { a -> if (simples) PlayerAcessivel.blocoSimples(a, { it.chave }, setOf(INTERESSES, CONTINUAR)) else a }
                val fixas = ArrayList<ColunaCategorias.Linha>()
                if (termoS.isNotEmpty()) fixas += L(BUSCA, getString(R.string.player_cat_busca, termoS), res.size, IconeMidia.Tipo.LUPA, 0, false, "")
                // A coleção vem da ficha; filme de categoria oculta não entra (o PIN pode ter trancado de novo).
                val col = colecaoS?.let { (n, fs) -> n to fs.filter { mostrar || it.grupo.trim() !in ocultasS } }?.takeIf { it.second.isNotEmpty() }
                if (col != null) fixas += L(COLECAO, getString(R.string.filmes_colecao_cat, col.first), col.second.size, null, 0, false, "")
                // Todos é botão ao lado de "LISTAS", como na TV (pedido do Paulo, 21/11/2026), não mais uma linha fixa.
                val botoesListas = listOf(L(TODOS, rotulo(R.string.player_cat_todos), vis.size, IconePlayer.Tipo.TODOS, 0, false, ""))
                // Listas de fora, na parte que rola, em ordem alfabética com as categorias: só os títulos que a lista de IPTV tem, na ordem da lista.
                val fora = HashMap<String, Pair<List<String>, Int>>()
                // 19/11/2026 (Paulo: "o MDBList não roda com a lista"): as listas de fora iam nas FIXAS e, com várias,
                // ocupavam a coluna e espremiam as categorias. Agora abrem a parte que ROLA, antes das categorias de A a Z.
                val linhasFora = ArrayList<ColunaCategorias.Linha>()
                if (listasS.isNotEmpty()) {
                    val cat = titulosDoCatalogo(vis)
                    for ((l, itens) in listasS) {
                        val (chs, casaram) = SincronizarFilmes.cruzar(itens, cat, SincronizarFilmes.Tipo.FILME)
                        if (chs.isEmpty()) continue
                        fora["x:${l.chave}"] = chs to itens.count { it.tipo == SincronizarFilmes.Tipo.FILME }
                        linhasFora += L("x:${l.chave}", l.nome, casaram, null, 0, false, "", etiquetaLista(l))
                    }
                }
                val grupos = cacheGrupos?.takeIf { it.first === vis }?.second ?: Vod.grupos(vis).also { cacheGrupos = vis to it }
                val novos = cacheNovos?.takeIf { it.first === vis && it.second == marcoS }?.third
                    ?: ColunaRegras.novosPorGrupo(vis, marcoS, { it.grupo }, { it.adicionado }).also { cacheNovos = Triple(vis, marcoS, it) }
                // Categoria vazia não aparece (acréscimo aprovado): os grupos já vêm só dos filmes visíveis.
                val rola = ColunaRegras.intercalarAZ(grupos.filter { it.second > 0 }.map { (g, n) ->
                    L("g:$g", PlayerLista.nomeExibido(g).ifEmpty { rotulo(R.string.player_cat_outros) }, n,
                        if (mostrar && g in ocultas) IconePlayer.Tipo.CADEADO else null, novos[g] ?: 0, false, "")
                }, linhasFora) { if (it.chave.startsWith("g:")) it.chave.removePrefix("g:") else it.nome }   // categoria: o grupo cru (como ordenarAZ)
                val todasChaves = (acoes + fixas + rola + botoesListas).mapTo(HashSet()) { it.chave }
                // Entrada: a última categoria aberta; sem ela, Continuar (se houver) ou Todos.
                val ch0 = if (inicial && simples) PlayerAcessivel.entradaSimples(listOf(CONTINUAR to cont.size, INTERESSES to ints.size), TODOS)
                    else if (inicial && chaveS != BUSCA) ColunaRegras.categoriaInicial(ultimaS?.takeIf { it != BUSCA && it != COLECAO },
                    todasChaves, if (cont.isNotEmpty()) CONTINUAR else TODOS) else chaveS
                val ch = if (ch0 !in todasChaves || ch0 == BUSCAR || ch0 == FILTRO) TODOS else ch0
                val g = if (modo == ModoGrade.IGUAL && ch == chaveS) null else {
                    // A ordem do catálogo inteiro fica guardada: uma categoria é o filtro dela (filtrar mantém a ordem).
                    fun ordenados() = cacheOrdem?.takeIf { it.first === vis && it.second == ordemS && cacheOrdemNotas === notasS }?.third
                        ?: Vod.ordenar(vis, ordemS, notasS).also { cacheOrdem = Triple(vis, ordemS, it); cacheOrdemNotas = notasS }
                    when {
                        ch == CONTINUAR -> cont                  // já na ordem de "mais recente"
                        ch == INTERESSES -> ints                 // o último marcado primeiro
                        ch == ASSISTIDOS -> vistos               // o visto mais recente primeiro
                        ch == BUSCA -> res
                        ch == COLECAO -> col?.second.orEmpty()   // já na ordem dos anos
                        ch.startsWith("x:") -> { val porChave = vis.associateBy { it.chave }; fora[ch]?.first.orEmpty().mapNotNull { porChave[it] } }
                        ch.startsWith("g:") -> { val alvo = ch.removePrefix("g:"); ordenados().filter { it.grupo.trim() == alvo } }
                        else -> ordenados()
                    }.let { FilmesFiltro.filtrar(it, filtroS, posS, notasS, filtroS.lista?.let { k -> fora[k]?.first?.toHashSet() }) }   // o filtro combinado vale sobre qualquer categoria
                }
                Estado(vis, res, acoes, botoes, fixas, rola, ch, botoesListas, g, fora[ch]?.let { it.first.size to it.second })
            },
            aplicar = { e ->
                pedidoPendente = null
                if (!isDestroyed) {
                    filmes = e.filmes; resultados = e.resultados; itens = e.acoes + e.fixas + e.rola + e.botoesListas; chave = e.chave
                    coluna.mostrarBotoes(e.botoes)
                    coluna.mostrarBotoesListas(e.botoesListas)
                    coluna.mostrar(e.acoes, e.fixas, e.rola, chave)
                    if (e.grade != null) {
                        naGrade = e.grade
                        adaptadorGrade.notifyDataSetChanged()
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isNotEmpty()) {
                            val p = antesSel.coerceIn(0, naGrade.size - 1); grade.setSelection(p); mostrarFocado(naGrade[p])
                        } else {
                            grade.setSelection(0); mostrarFocado(naGrade.firstOrNull())
                        }
                        if (modo == ModoGrade.MANTER_LUGAR && naGrade.isEmpty() && grade.hasFocus()) coluna.focar()   // esvaziou
                    }
                    val nome = itens.firstOrNull { it.chave == chave }?.nome.orEmpty()
                    // "Ação 1.204 [Ordem: mais recentes] [Só não vistos] [★ 7+]": o que está na grade e por quê (acréscimo 1).
                    // Lista de fora: "112 de 250 na sua lista" no lugar da ordem (a ordem é a da lista).
                    val primeira = e.deLista?.let { (n, t) -> getString(R.string.sync_n_de, n, t) to false }
                        ?: (getString(R.string.coluna_filtro_ordem, nomeOrdem(ordem)) to false)
                    painel.mostrarTopo(nome, naGrade.size, listOf(primeira) + pilulasFiltro())
                    depois?.invoke()
                }
            },
            demorou = { if (!isDestroyed) { aviso.text = getString(R.string.filmes_carregando); aviso.visibility = View.VISIBLE; avisoDaConta = true } },
            // pedidoPendente também aqui: o fim roda com erro ou sucesso (com erro o aplicar não roda e o pedido ficava
            // preso, e toda conta seguinte voltava a grade ao topo; debug 20/11/2026).
            fim = { pedidoPendente = null; if (avisoDaConta) { aviso.visibility = View.GONE; avisoDaConta = false } },
        )
    }
    private var avisoDaConta = false

    private fun escolherCat(k: String, focarGrade: Boolean = false) {
        if (k == BUSCAR || k == FILTRO || itens.none { it.chave == k }) return
        if (k == chave) { if (focarGrade && naGrade.isNotEmpty() && !calculo.calculando) grade.requestFocus(); return }
        chave = k
        coluna.escolher(k)   // mesma lista: só troca a cor da escolhida
        if (k != BUSCA && k != COLECAO) { ultimaSalva = k; prefsPlayer().edit().putString(chavePref("ultima"), k).apply() }
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
            aoSegurar = { naGrade.getOrNull(grade.selectedItemPosition)?.let { menuPoster(it) } })
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

    /**
     * OK segurado no pôster (14/11/2026): pôr/tirar dos Interesses, marcar como assistido ou não, tirar do Continuar.
     * É como se corrige quando a régua do "concluído" errar.
     */
    private fun menuPoster(f: Filme) {
        val pos = posicoes[f.chave]
        val opcoes = ArrayList<Pair<String, () -> Unit>>()
        opcoes += getString(if (f.chave in interesses) R.string.coluna_menu_tirar_interesse else R.string.coluna_menu_por_interesse) to { alternarInteresse(f) }
        if (!Vod.finalizado(pos)) opcoes += getString(R.string.coluna_menu_assistido) to {
            mudarPosicoes(f, { Vod.marcarAssistido(it, f.chave, System.currentTimeMillis()) }, R.string.coluna_marcado_assistido)
        }
        if (Vod.deveRetomar(pos)) opcoes += getString(R.string.coluna_menu_tirar_continuar) to {
            mudarPosicoes(f, { Vod.esquecer(it, f.chave) }, R.string.coluna_tirado_continuar)
        }
        if (Vod.finalizado(pos)) opcoes += getString(R.string.coluna_menu_nao_assistido) to {
            mudarPosicoes(f, { Vod.esquecer(it, f.chave) }, R.string.coluna_marcado_nao_assistido)
        }
        // Destaque da HOME é este filme: dá para tirar (aberto por engano, ou não quer que apareça para as visitas).
        if (PlayerArmazem.capaHome(this, serie = false).ficha?.chave == f.chave) opcoes += getString(R.string.coluna_menu_tirar_home) to {
            Fundo.rodar { PlayerArmazem.tirarCapaHome(this, serie = false, chave = f.chave) }
            avisoBreve(getString(R.string.coluna_tirado_home, Vod.nomeLimpo(f.nome)))
        }
        Estilo.lista(this, Vod.nomeLimpo(f.nome), opcoes.map { it.first }) { i -> opcoes[i].second() }
    }

    /** Muda a posição salva (no disco, fora da tela) e refaz Continuar/Assistidos e as etiquetas sem perder o lugar. */
    private fun mudarPosicoes(f: Filme, mudar: (Map<String, Posicao>) -> Map<String, Posicao>, aviso: Int) {
        posicoes = mudar(posicoes)
        val chaveF = f.chave; val nova = posicoes[chaveF]
        Fundo.rodar { PlayerArmazem.definirPosicao(this, chaveF, nova) }
        adaptadorGrade.notifyDataSetChanged()
        recalcular(ModoGrade.MANTER_LUGAR)
        avisoBreve(getString(aviso, Vod.nomeLimpo(f.nome)))
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
            .setView(FrameLayout(this).apply { setPaddingRelative(dp(20), dp(10), dp(20), 0); addView(campo) })
            .setPositiveButton(R.string.player_acao_buscar) { _, _ -> aplicar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) { d.dismiss(); aplicar(campo.text.toString().trim()); true } else false
        }
        d.show()
    }

    // ---------------------------------------------------------------- grade de pôsteres

    private class Card(val capa: ImageView, val nome: TextView, val barra: BarraTempo.Barra, val etiqueta: TextView, val estrela: TextView,
                       val moldura: View, val selo: TextView)

    /** Medida atual da grade (PosterGridSize); até a grade ter tamanho, a de antes (5 colunas, pôster de 180). */
    private var medidaGrade = PosterGridSize.Medida(Estilo.Leitura.GRADE_COLUNAS, Estilo.Leitura.POSTER_ALTURA, Estilo.Leitura.POSTER_ALTURA + PosterGridSize.EXTRA_CARD)

    private val adaptadorGrade = object : BaseAdapter() {
        override fun getCount() = naGrade.size
        override fun getItem(p: Int) = naGrade[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novoCard()
            val c = v.tag as Card
            val f = naGrade[p]
            // Card reaproveitado de antes de a grade mudar de tamanho: acerta a altura (só muda quando a medida muda).
            val alturaCard = dp(medidaGrade.alturaCard)
            if (v.layoutParams.height != alturaCard) {
                v.layoutParams.height = alturaCard
                c.moldura.layoutParams.height = dp(medidaGrade.alturaPoster)
            }
            // O nome só aparece enquanto o pôster não chega ou quando ele não existe (CapaCartao.aoTrocar).
            c.nome.text = Vod.nomeTela(Idioma.semMarca(f.nome), f.ano)
            // Selo LEG: só o legendado que não tem a versão dublada junto (com par, a ficha escolhe a versão).
            c.selo.visibility = if (soLegendado(f)) View.VISIBLE else View.GONE
            val pos = posicoes[f.chave]
            if (Vod.deveRetomar(pos) && pos!!.duracao > 0) {
                c.barra.visibility = View.VISIBLE; c.barra.fracao = BarraTempo.fracao(pos.ms, pos.duracao)
            } else c.barra.visibility = View.GONE
            // Etiqueta: "faltam 1 h 04" (a barra já dá a proporção; sem número de %), "Finalizado" (95% ou mais) ou
            // "Assistido" (servidor sem duração).
            // 14/11/2026: concluído (régua de minutos) = "Assistido" em verde; começado sem duração não ganha etiqueta.
            // 20/11/2026 (Paulo: o selo "falta 1 h 12 min" cobria o pôster e quebrava em duas linhas): no pôster só a barra,
            // como no "Continuar" do Netflix e do Apple TV; o "faltam…" fica na caixa de cima do filme em foco.
            // O selo fica só para "Assistido".
            val pct = Vod.progresso(pos)?.takeIf { it == 100 }
            if (pct == null) c.etiqueta.visibility = View.GONE else {
                c.etiqueta.visibility = View.VISIBLE
                c.etiqueta.text = when (pct) { 100 -> getString(R.string.filmes_assistido); else -> BarraTempo.faltam(this@FilmesActivity, BarraTempo.duracao(pos!!.duracao - pos.ms)) }
                (c.etiqueta.background as GradientDrawable).setColor(if (pct == 100) verdeEtiqueta else 0xD9121528.toInt())
            }
            c.estrela.visibility = if (f.chave in interesses) View.VISIBLE else View.GONE
            (c.capa as? CapaCartao)?.semente = Idioma.semMarca(f.nome)   // capa gerada quando não há pôster (ideia 8)
            PlayerLogos.carregar(this@FilmesActivity, f.capa, c.capa, lado = 360)
            return v
        }
    }

    private fun novoCard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(4), dp(4), dp(4), dp(6)) }
        val capa = CapaCartao(this, dp(8).toFloat())   // cinza só enquanto o pôster não chega (CapaCartao.kt)
        // Largura da coluna / altura 2:3 (pôster de cinema). A largura real vem do GridView; aqui uma altura fixa basta.
        // A capa vai num FrameLayout para a etiqueta (embaixo, à esquerda) e a estrela (em cima, à direita) ficarem por cima.
        val moldura = FrameLayout(this)
        moldura.addView(capa, FrameLayout.LayoutParams(-1, -1))
        val etiqueta = TextView(this).apply {
            textSize = Estilo.Leitura.DICA; typeface = fonteMedia; setTextColor(papel); setPaddingRelative(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD9121528.toInt()) }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END   // selo de uma linha: nunca cobre metade do pôster
        }
        moldura.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        val estrela = TextView(this).apply {
            text = "★"; textSize = 15f; setTextColor(ambar); setPaddingRelative(dp(6), dp(1), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xD9121528.toInt()) }
        }
        moldura.addView(estrela, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        // 19/11/2026: o nome saiu de cima do pôster (repetia o título da arte e gastava 48 dp por fileira). Fica POR CIMA
        // do cinza de espera, centrado, e some quando o pôster chega; o título do filme em foco aparece grande no topo.
        val nome = TituloCard(this, Estilo.Leitura.CARD).apply {
            setTextColor(papel); maxLines = 4; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPaddingRelative(dp(6), dp(6), dp(6), dp(6))
        }
        moldura.addView(nome, 1, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        capa.aoTrocar = { tem -> nome.visibility = if (tem) View.INVISIBLE else View.VISIBLE }
        // Selo LEG (em cima, à esquerda; a estrela fica à direita): no lugar do "Leg" que vinha colado no nome.
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
        col.tag = Card(capa, nome, barra, etiqueta, estrela, moldura, selo)
        return col
    }

    /**
     * Ficha rápida do pôster em foco (acréscimo 2): nota, ano, duração e gênero numa linha, sem abrir a ficha.
     * Duração só se a ficha já estiver guardada no disco (nunca pede ao servidor a cada passo do controle).
     */
    private fun mostrarFocado(f: Filme?) {
        tela.removeCallbacks(lerFichaRapida)
        if (f == null) {
            painel.mostrarFoco(when {
                chave == INTERESSES -> getString(R.string.filmes_interesses_vazio)
                filtro.ativo -> getString(R.string.filmes_filtro_vazio)
                else -> ""
            }); return
        }
        val info = fichasRapidas[f.chave]
        val pos = posicoes[f.chave]?.takeIf { p -> p.duracao > 0 }
        painel.mostrarFoco(Estilo.infoFoco(Idioma.tituloCurto(Vod.nomeTela(Idioma.semMarca(f.nome), f.ano)), notaRapida[f.chave] ?: f.nota, listOfNotNull(
            f.ano?.toString(),
            if (soLegendado(f)) getString(R.string.idioma_legendado) else null,
            info?.duracaoMin?.let { if (it >= 60) "${it / 60} h ${"%02d".format(it % 60)}" else "$it min" },
            info?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(f.grupo).ifEmpty { null },
            pos?.takeIf { p -> Vod.deveRetomar(p) }?.let { p -> BarraTempo.faltam(this, BarraTempo.duracao(p.duracao - p.ms)) },
            extRapidas[f.chave]?.ifEmpty { null })))
        focoRapido = f; if (f.chave !in fichasRapidas) { tela.postDelayed(lerFichaRapida, 400) }   // espera o dedo parar
    }

    // As fichas já lidas do disco (null = não tem guardada), só as últimas 60: a TV tem 2 GB.
    private val fichasRapidas = object : LinkedHashMap<String, FichaFilme?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FichaFilme?>) = size > 60
    }
    /** Nota de fora (Trakt) já guardada, para a faixa da ficha rápida; lidas no Fundo. */
    private val extRapidas = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 60
    }
    /** A média das notas de fora (a estrela), quando há; sem ela, a estrela é a da lista de canais. */
    private val notaRapida = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Float>) = size > 60
    }
    @Volatile private var focoRapido: Filme? = null   // lido também no Fundo: o pôster ainda em foco?
    private val lerFichaRapida = Runnable {
        val f = focoRapido ?: return@Runnable
        Fundo.rodar {
            // Sem ficha no disco (título nunca aberto, ou aberto antes de 14/11, quando a ficha ainda não ficava no disco):
            // pede ao servidor, só se o foco ainda está nele (parou o dedo). A resposta fica no disco para a próxima vez.
            val info = PlayerCarga.fichaFilmeGuardada(this, f) ?: if (focoRapido === f) PlayerCarga.fichaFilme(this, f) else null
            val notas = InfoExternaCache.guardado(this, f.chave)?.let { InfoExterna.notasDe(it) }?.takeIf { !it.vazia }
            val ext = notas?.let { InfoExterna.textoNotas(it) }.orEmpty()
            val media = notas?.media
            tela.post {
                if (isDestroyed) return@post
                fichasRapidas[f.chave] = info; extRapidas[f.chave] = ext
                if (media != null) notaRapida[f.chave] = media else notaRapida.remove(f.chave)
                if (naGrade.getOrNull(grade.selectedItemPosition) === f) mostrarFocado(f)
            }
        }
    }

    /** As pílulas do filtro em vigor (em âmbar: é o que está escondendo títulos). */
    private fun pilulasFiltro(): List<Pair<String, Boolean>> = listOfNotNull(
        nomeListaFiltro(), filtro.genero?.let { nomeGenero(it) }, nomeEpoca(filtro), filtro.notaMin?.let { nomeNota(it) },
        if (filtro.naoVistos) getString(R.string.filmes_filtro_nao_vistos_curto) else null,
    ).map { it to true }

    // ---------------------------------------------------------------- pular para uma letra (acréscimo 3)

    /** Só em Todos ou numa categoria (as listas que seguem a ordem do catálogo), e com bastante filme. */
    private fun podePularLetra() = naGrade.size >= MIN_PULAR && (chave == TODOS || chave.startsWith("g:"))

    /** As letras grandes por cima; escolhida a letra, põe em ordem de nome (se não estiver) e pula para ela. */
    private fun pularParaLetra() {
        if (!podePularLetra()) return
        PainelCatalogo.escolherLetra(this) { letra ->
            val pular = {
                val atual = naGrade
                // Busca binária: a grade em ordem de nome está ordenada pela mesma chave.
                val i = ColunaRegras.indiceDaLetra(atual.size, { Vod.chaveNome(atual[it].nome) }, letra)
                if (i >= 0) { grade.requestFocus(); grade.setSelection(i); mostrarFocado(atual[i]) }
            }
            if (ordem == Vod.Ordem.NOME) pular() else {
                ordem = Vod.Ordem.NOME
                getSharedPreferences("player", MODE_PRIVATE).edit().putString("ordem_filmes", ordem.name).apply()
                recalcular(ModoGrade.NOVA) { pular() }
            }
        }
    }

    // ---------------------------------------------------------------- ficha do filme

    /** O par na outra versão (dublado x legendado), só entre os filmes que a tela pode mostrar (parental). */
    /**
     * Legendado sem a versão dublada junto (selo LEG no pôster). Espera o índice de versões: nos primeiros segundos
     * (índice ainda vazio) todo legendado parecia sem par e ganhava o selo (visto na Sala, 19/11/2026). Índice vazio de
     * verdade = nenhum nome com marca, então nenhum LEG para mostrar.
     */
    private fun soLegendado(f: Filme): Boolean =
        indiceVersoes.isNotEmpty() && Idioma.versao(f.nome, f.grupo) == Idioma.Versao.LEGENDADA && outraVersao(f) == null

    private fun outraVersao(f: Filme): Filme? {
        // Filtra ANTES de procurar: uma cópia oculta que vinha primeiro no catálogo escondia o par visível (e a grade,
        // que já junta só pelo que está nela, sumia com o cartão sem a ficha oferecer a outra versão).
        val k = Idioma.chaveTitulo(f.nome)
        val podem = indiceVersoes[k]?.filter { mostrarOcultas || it.grupo.trim() !in ocultas } ?: return null
        return Idioma.outraVersao(f, mapOf(k to podem), { it.nome }, { it.grupo }, { it.ano })
    }

    /**
     * [trocar]: veio da grade. Se a pessoa prefere a outra versão e ela existe, a ficha já abre nela (com um botão para
     * voltar). Não troca se este filme já foi começado: quem começou dublado continua de onde parou.
     */
    private fun abrirFicha(f0: Filme, manterFoco: Boolean = false, trocar: Boolean = false) {
        val f = if (!trocar || Vod.deveRetomar(posicoes[f0.chave])) f0 else outraVersao(f0)
            ?.takeIf { Idioma.versaoComPar(it.nome, it.grupo, f0.nome, f0.grupo) == Idioma.versaoDesejada(prefsIdioma.para(f0.chave)) } ?: f0
        if (ficha !== f) fichaInfo = null   // outro filme: as cenas da ficha antiga não valem
        ficha = f
        // A mesma ficha reaberta (volta do vídeo ou do Elenco) fica como estava até a ficha nova chegar: sem piscar.
        desenharFicha(f, fichaInfo, manterFoco)
        // Coleção (ideia 23): o nome-base de cada filme do catálogo visível, fora da tela (dezenas de milhares de nomes).
        if (colecaoFicha?.first !== f) {
            colecaoFicha = null
            val vis = filmes
            Fundo.rodar {
                val col = FilmesFiltro.colecao(vis, f)
                // Com um desenho já marcado, a coleção entra nele (os dados são lidos na hora de desenhar).
                tela.post { if (!isDestroyed && ficha === f) { colecaoFicha = f to col; if (col.size > 1 && !desenhoMarcado) pedirDesenho() } }
            }
        }
        val externoVem = InfoExternaCache.temFicha()
        Fundo.rodar {
            val info = PlayerCarga.fichaFilme(this, f)
            // A ficha de fora chega 50-400 ms depois (mais com MDBList/Simkl): espera por ela para desenhar uma vez só.
            tela.post { if (!isDestroyed && ficha === f) { fichaInfo = info; pedirDesenho(if (externoVem && externo?.first !== f) ESPERA_EXTERNO_MS else 0) } }
            buscarExterno(f, info)
        }
    }

    /**
     * Ficha de fora (fatia 2, 14/11/2026): TMDB (sinopse no idioma da TV quando a lista não tem, elenco, saga) e as notas
     * do Trakt (já guardada de madrugada). Roda fora da tela, depois da ficha do servidor; sem chave nenhuma, não faz nada.
     */
    private fun buscarExterno(f: Filme, info: FichaFilme?) {
        if (!InfoExternaCache.temFicha()) return
        val g = InfoExternaCache.resolver(this, f.chave, f.nome, f.ano ?: info?.lancamento?.take(4)?.toIntOrNull(), false, info?.tmdb ?: f.tmdb)
        val saga = g.detalhe?.colecao?.let { c ->
            val partes = InfoExternaCache.colecao(c.id).takeIf { it.size > 1 } ?: return@let null
            val vis = todos
            val ids = vis.mapNotNullTo(HashSet()) { it.tmdb }
            val nomes = vis.mapTo(HashSet()) { InfoExterna.chaveNomeAno(Vod.nomeLimpo(it.nome), it.ano) }
            val (tem, total) = InfoExterna.contarNaLista(partes, ids, nomes)
            Triple(c.nome, tem, total)
        }
        tela.post {
            if (isDestroyed || ficha !== f) return@post
            // Reaberta (volta do vídeo ou do Elenco): a mesma ficha de fora de antes não muda nada na tela, não redesenha.
            val mudou = externo?.let { it.first !== f || it.second != g } != false || sagaExterna?.second != saga
            externo = f to g
            sagaExterna = saga?.let { f to it }
            if (mudou || desenhoMarcado) pedirDesenho()
        }
    }

    /** Os dados que chegam juntos (servidor, coleção, TMDB) viram um desenho só da ficha, mantendo o foco (15/11/2026). */
    // Cada desenho refaz a ficha inteira (16-27 ms de tela na TV da Sala, atrace de 16/11/2026): servidor e TMDB chegando
    // 40-50 ms um do outro eram dois desenhos e um tranco a mais. [espera]: adia o desenho; um pedido novo substitui.
    private var desenhoMarcado = false
    private val desenhoPendente = Runnable { desenhoMarcado = false; ficha?.takeIf { !isDestroyed }?.let { desenharFicha(it, fichaInfo, manterFoco = true) } }
    private fun pedirDesenho(espera: Long = 0) { desenhoMarcado = true; tela.removeCallbacks(desenhoPendente); tela.postDelayed(desenhoPendente, espera) }

    private fun fecharFicha() {
        tela.removeCallbacks(desenhoPendente); desenhoMarcado = false
        ficha = null; painelFicha.visibility = View.GONE; painelFicha.removeAllViews()
        telaDeTras.visibility = View.VISIBLE
        if (naGrade.isNotEmpty()) grade.requestFocus() else coluna.focar()
    }

    /**
     * Ficha no estilo C "Botões grandes" (15/11/2026): à esquerda as ações uma embaixo da outra, em letra grande (▲▼ anda,
     * nada sai cortado da tela nem é empurrado quando um dado chega depois); à direita título, dados, pôster e sinopse.
     * Os dados chegam aos poucos (servidor, coleção, TMDB) e cada um redesenha: o foco fica na mesma ação.
     */
    /** Tem a busca do Google TV? (botão "Onde assistir" da ficha; conferido uma vez por tela.) */
    private val googleTv by lazy { BuscaActivity.temGoogleTv(this) }

    private fun desenharFicha(f: Filme, info: FichaFilme?, manterFoco: Boolean) {
        val focoAntes = if (manterFoco) (painelFicha.findFocus()?.tag as? String) else null
        painelFicha.removeAllViews()
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(48), dp(36), dp(48), dp(28)) }
        val ext = externo?.takeIf { it.first === f }?.second
        val pos = posicoes[f.chave]

        // ---- ações (coluna da esquerda)
        val (colAcoes, acoes) = FichaAcoes.coluna(this)
        val primeiro: View
        if (Vod.deveRetomar(pos)) {
            primeiro = FichaAcoes.acao(this, getString(R.string.filmes_continuar), getString(R.string.ficha_continuar_de, ponto(pos!!.ms)), marca = "continuar") { tocar(f, doComeco = false) }
            FichaAcoes.adicionar(acoes, primeiro)
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.filmes_do_comeco), marca = "comeco") { tocar(f, doComeco = true) })
        } else {
            primeiro = FichaAcoes.acao(this, getString(R.string.filmes_assistir), marca = "assistir") { tocar(f, doComeco = true) }
            FichaAcoes.adicionar(acoes, primeiro)
        }
        // Trailer: o do TMDB (pelo id do filme) primeiro; o do servidor só sem ele (18/11/2026: o do IPTV às vezes é de outro filme).
        TrailerLink.escolher(ext?.detalhe?.linkTrailer, info?.trailer)?.let { link ->
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.trailer), marca = "trailer") { VodActivity.abrirTrailer(this, link) })
        }
        // Ideia 84: elenco com foto e o que mais cada pessoa fez que está na lista.
        (f.tmdb ?: info?.tmdb ?: ext?.tmdb)?.takeIf { ElencoActivity.disponivel(it) }?.let { id ->
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.elenco_botao), marca = "elenco") { ElencoActivity.abrir(this, id, serie = false, nome = Vod.nomeLimpo(f.nome)) })
        }
        FichaAcoes.ondeAssistir(this, acoes, f.nome, f.ano ?: info?.lancamento?.take(4)?.toIntOrNull(), googleTv)
        FichaAcoes.conviteSimkl(this, acoes)
        // O mesmo filme na outra versão do catálogo (dublado x legendado): abre a ficha dela.
        outraVersao(f)?.let { o ->
            val r = if (Idioma.versaoComPar(o.nome, o.grupo, f.nome, f.grupo) == Idioma.Versao.LEGENDADA) R.string.idioma_versao_legendada else R.string.idioma_versao_dublada
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(r), marca = "versao") { abrirFicha(o) })
        }
        // Ideia 23: os outros filmes com o mesmo nome-base (sequências, dublado e legendado) viram uma categoria na grade.
        colecaoFicha?.takeIf { it.first === f && it.second.size > 1 }?.second?.let { lista ->
            // O número conta FILMES, não versões: Duna, Duna LEG e Duna 4K são um só (Sala, 17/11/2026: "Coleção 6" ao
            // lado de "Duna · 2 de 3 na sua lista" confundia). A categoria aberta continua com todas as versões.
            val filmes = FilmesFiltro.quantosFilmes(lista)
            FichaAcoes.adicionar(acoes, FichaAcoes.acao(this, getString(R.string.filmes_colecao), filmes.toString(), "colecao") { abrirColecao(lista) })
        }
        val sinopse = info?.sinopse?.takeIf { it.isNotEmpty() } ?: ext?.detalhe?.sinopse?.takeIf { it.isNotEmpty() }
        val acaoSinopse = sinopse?.let { txt ->
            FichaAcoes.acao(this, getString(R.string.series_sinopse_mais), marca = "sinopse") { FichaAcoes.mostrarSinopse(this, Vod.nomeLimpo(f.nome), txt) }
                .also { FichaAcoes.adicionar(acoes, it); it.visibility = View.GONE }
        }
        // Achado pela busca do nome (a lista não mandou o id): "Não é este filme" refaz a busca sem ele.
        if (ext?.tmdb != null && f.tmdb == null && info?.tmdb == null) FichaAcoes.adicionar(acoes,
            FichaAcoes.acao(this, getString(R.string.ext_nao_e_este), marca = "naoeeste") {
                externo = null; sagaExterna = null
                Fundo.rodar { InfoExternaCache.recusar(this, f.chave); buscarExterno(f, fichaInfo) }
                desenharFicha(f, fichaInfo, manterFoco = false)
            })
        linha.addView(colAcoes, LinearLayout.LayoutParams(dp(FichaAcoes.LARGURA_DP), -1))

        // ---- informações (direita): título e dados ao lado do pôster, sinopse embaixo
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(36), 0, 0, 0) }
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(TextView(this).apply { text = Vod.nomeTela(f.nome, f.ano); textSize = Estilo.Leitura.TITULO; typeface = fonteMedia; setTextColor(papel); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        val dados = listOfNotNull(
            (f.ano?.toString() ?: info?.lancamento?.take(4)?.takeIf { it.isNotEmpty() }),
            info?.duracaoMin?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
            (ext?.let { InfoExterna.notasDe(it).media } ?: f.nota)?.let { "★ %.1f".format(Locale.getDefault(), it) },
            // Gêneros do TMDB primeiro (no idioma da TV); os do servidor costumam vir em inglês.
            ext?.detalhe?.generos?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: info?.genero?.ifEmpty { null } ?: PlayerLista.nomeExibido(f.grupo).ifEmpty { null },
            rotuloVersao(Idioma.versao(f.nome, f.grupo)),
        ).joinToString("   ·   ")
        texto.addView(TextView(this).apply { text = dados; textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPaddingRelative(0, dp(10), 0, 0) })
        // Nota de fora (Trakt): "Trakt 78%", em âmbar como a estrela.
        ext?.let { InfoExterna.notasDe(it) }?.takeIf { !it.vazia }?.let { n ->
            texto.addView(TextView(this).apply { text = InfoExterna.textoNotas(n); textSize = Estilo.Leitura.DADOS; setTextColor(ambar); setPaddingRelative(0, dp(6), 0, 0) })
            // Atribuição da nota do Simkl na ficha (17/11/2026): "Dados de Simkl (simkl.com)", discreta.
            if (n.simkl != null) texto.addView(TextView(this).apply { text = getString(R.string.sync_fonte_simkl); textSize = Estilo.Leitura.DICA; setTextColor(suave); setPaddingRelative(0, dp(2), 0, 0) })
        }
        // Ideia 82: "Também em: Netflix · Prime Video" (TMDB/JustWatch). DESLIGADO (OndeAssistir.JUSTWATCH_LIBERADO): licença.
        (f.tmdb ?: info?.tmdb ?: ext?.tmdb)?.takeIf { OndeAssistir.JUSTWATCH_LIBERADO && InfoExternaCache.temTmdb() }?.let { id ->
            val linhaOnde = TextView(this).apply { textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPaddingRelative(0, dp(6), 0, 0); visibility = View.GONE; maxLines = 2 }
            texto.addView(linhaOnde)
            Fundo.rodar { InfoExternaCache.provedores(id, serie = false).takeIf { it.isNotEmpty() }?.let { p ->
                tela.post { linhaOnde.text = getString(R.string.onde_tambem_em, p.joinToString(" · ")); linhaOnde.visibility = View.VISIBLE }
            } }
        }
        sagaExterna?.takeIf { it.first === f }?.second?.let { (nome, tem, total) ->
            texto.addView(TextView(this).apply { text = getString(R.string.ext_saga, nome, tem, total); textSize = Estilo.Leitura.DADOS; setTextColor(suave); setPaddingRelative(0, dp(6), 0, 0) })
        }
        // Começou e não terminou: barra de onde parou + "faltam 1 h 01 · termina às 22:41" (13/11/2026).
        if (Vod.deveRetomar(pos) && pos!!.duracao > 0) {
            val resta = (pos.duracao - pos.ms).coerceAtLeast(0)
            texto.addView(BarraTempo.Linha(this).apply {
                mostrar(BarraTempo.fracao(pos.ms, pos.duracao), BarraTempo.textoFaltam(context, resta, System.currentTimeMillis() + resta))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        topo.addView(texto, LinearLayout.LayoutParams(0, -2, 1f))
        val capa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Estilo.BORDA) }
            clipToOutline = true
        }
        PlayerLogos.carregar(this, f.capa, capa, lado = 600)
        topo.addView(capa, LinearLayout.LayoutParams(dp(140), dp(210)).apply { marginStart = dp(24) })
        direita.addView(topo)

        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(0, dp(16), 0, 0) }
        if (info == null) corpo.addView(TextView(this).apply { text = getString(R.string.filmes_ficha_carregando); textSize = Estilo.Leitura.AVISO; setTextColor(apagado) })
        val txtSinopse = sinopse?.let {
            TextView(this).apply { text = it; textSize = Estilo.Leitura.SINOPSE; setTextColor(papel); setLineSpacing(0f, 1.25f); maxLines = 5; ellipsize = TextUtils.TruncateAt.END }
                .also { t -> corpo.addView(t) }
        }
        // Direção: a do TMDB. A do servidor só quando o TMDB já respondeu sem ela (ou não há TMDB): antes ela aparecia
        // primeiro e trocava ao chegar o TMDB, às vezes errada (Sala, 17/11/2026: Duna mostrou "Cliff Lanning").
        val tmdbPendente = ext == null && InfoExternaCache.temFicha()
        (ext?.detalhe?.diretor?.takeIf { it.isNotEmpty() } ?: info?.diretor?.takeIf { it.isNotEmpty() && !tmdbPendente })?.let { corpo.addView(rotuloValor(getString(R.string.filmes_direcao), it).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
        (info?.elenco?.takeIf { it.isNotEmpty() } ?: ext?.detalhe?.elenco?.takeIf { it.isNotEmpty() }?.joinToString(", "))
            ?.let { corpo.addView(rotuloValor(getString(R.string.filmes_elenco), it).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }) }
        direita.addView(corpo, LinearLayout.LayoutParams(-1, 0, 1f))
        linha.addView(direita, LinearLayout.LayoutParams(0, -1, 1f))

        painelFicha.addView(linha, FrameLayout.LayoutParams(-1, -1))
        FundoFicha.por(this, painelFicha, info?.fundo)   // a cena que a lista manda, atrás da ficha (18/11/2026)
        painelFicha.visibility = View.VISIBLE
        // "Sinopse inteira" só aparece quando a sinopse não coube (dá para saber só depois de desenhada).
        if (txtSinopse != null && acaoSinopse != null) txtSinopse.post { if (FichaAcoes.cortada(txtSinopse)) acaoSinopse.visibility = View.VISIBLE }
        (focoAntes?.let { t -> acoes.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE } } ?: primeiro).requestFocus()
        if (telaDeTras.visibility != View.INVISIBLE) telaDeTras.visibility = View.INVISIBLE   // o foco já está na ficha
    }

    private fun abrirColecao(lista: List<Filme>) {
        colecao = Vod.nomeLimpo(lista.first().nome) to lista
        chave = COLECAO
        fecharFicha()
        recalcular(ModoGrade.NOVA) { if (naGrade.isNotEmpty()) grade.requestFocus() }
    }

    // ---------------------------------------------------------------- filtro combinado e sorteio (ideia 23)

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
        nomeListaFiltro(), filtro.genero?.let { nomeGenero(it) }, nomeEpoca(filtro), filtro.notaMin?.let { nomeNota(it) },
        if (filtro.naoVistos) getString(R.string.filmes_filtro_nao_vistos_curto) else null,
    ).joinToString(" · ")

    /**
     * As listas de fora como opção do Filtro: chave da linha da coluna ("x:…") e nome. Só as que têm filme no catálogo
     * (as mesmas linhas da coluna, já em ordem alfabética): listas de séries escolhidas no MDBList apareciam aqui com 0
     * filmes (visto na Sala, 19/11/2026).
     */
    private fun listasDoFiltro(): List<Pair<String, String>> = itens.filter { it.chave.startsWith("x:") }.map { it.chave to it.nome }

    private fun nomeListaFiltro(): String? = filtro.lista?.let { k -> listasDoFiltro().firstOrNull { it.first == k }?.second ?: getString(R.string.pp_carregando) }

    private fun escolherLista() {
        val opcoes = listOf<Pair<String, String>?>(null) + listasDoFiltro()
        val marcada = opcoes.indexOfFirst { it?.first == filtro.lista }.coerceAtLeast(0)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo),
            opcoes.map { it?.second ?: getString(R.string.filmes_filtro_qualquer) }, listOf(marcada)) { i ->
            aplicarFiltro(filtro.copy(lista = opcoes[i]?.first)); menuFiltro()
        }
    }

    private fun aplicarFiltro(novo: FilmesFiltro.Criterios) {
        filtro = novo
        recalcular(ModoGrade.NOVA)   // a cor do "Filtro" na coluna muda junto
    }

    /**
     * O Filtro único (14/11/2026): junta Ordem, o filtro combinado, Sortear e "Mostrar ocultas" (que antes eram quatro
     * linhas da coluna). Mexe só na grade da direita; a lista de categorias não muda.
     */
    private fun menuFiltroUnico() {
        val opcoes = ArrayList<Pair<CharSequence, () -> Unit>>()
        opcoes += getString(R.string.coluna_filtro_ordem, nomeOrdem(ordem)) to { escolherOrdem() }
        opcoes += (if (filtro.ativo) getString(R.string.coluna_filtro_combinado_ativo, resumoFiltro()) else getString(R.string.coluna_filtro_combinado)) to { menuFiltro() }
        if (filtro.ativo) opcoes += getString(R.string.coluna_filtro_limpar) to { aplicarFiltro(FilmesFiltro.Criterios()) }
        opcoes += getString(R.string.coluna_filtro_sortear) to { sortear() }
        if (podePularLetra()) opcoes += getString(R.string.coluna_letra) to { pularParaLetra() }
        if (ocultas.isNotEmpty()) opcoes += JanelaIcones.comIcone(this, IconePlayer.Tipo.OCULTO,
            getString(if (mostrarOcultas) R.string.coluna_filtro_ocultas_esconder else R.string.coluna_filtro_ocultas_mostrar)) to { alternarOcultas() }
        Estilo.lista(this, getString(R.string.coluna_filtro_titulo), opcoes.map { it.first }) { i -> opcoes[i].second() }
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
        // "Lista" (19/11/2026, pedido do Paulo) só aparece com alguma lista de fora escolhida; vai por último antes de Limpar.
        val temListas = listasDoFiltro().isNotEmpty() || filtro.lista != null
        if (temListas) linhas += getString(R.string.filmes_filtro_lista, nomeListaFiltro() ?: qualquer)
        if (filtro.ativo) linhas += getString(R.string.filmes_filtro_limpar)
        Estilo.lista(this, getString(R.string.filmes_filtro_titulo), linhas) { i ->
            when {
                i == 0 -> escolherGenero(pronto.second)
                i == 1 -> escolherEpoca(pronto.third)
                i == 2 -> escolherNota()
                i == 3 -> { aplicarFiltro(filtro.copy(naoVistos = !filtro.naoVistos)); menuFiltro() }
                i == 4 && temListas -> escolherLista()
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
            busca = LegendaExterna.Busca(LegendaExterna.nomeDeBusca(f.nome), f.ano,
                tmdb = f.tmdb ?: externo?.takeIf { it.first === f }?.second?.tmdb, versao = Idioma.versao(f.nome, f.grupo))) { i ->
            // Zero tela preta: abre sobre a cena do filme (ou o pôster) com o ano e o nome, não sobre o preto.
            VodActivity.comCortina(i, cenas.firstOrNull() ?: capa.ifEmpty { null }, f.ano?.toString().orEmpty(), Vod.nomeLimpo(f.nome))
            // A outra versão (dublada/legendada) vai junto: o ▼ do player troca no mesmo ponto (18/11/2026).
            outraVersao(f)?.let { o -> VodActivity.comOutraVersao(i, o.fonteId, o.id, o.extensao, Vod.nomeLimpo(o.nome), Idioma.versaoComPar(o.nome, o.grupo, f.nome, f.grupo)) } ?: i
        }
    }

    private fun botao(texto: String, marca: String, aoApertar: () -> Unit) = TextView(this).apply {
        text = texto; textSize = Estilo.Leitura.BOTAO; typeface = fonteMedia; tag = marca
        setPaddingRelative(dp(26), dp(12), dp(26), dp(12))
        isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(papel) })
            // Sem foco: mais apagado (6% + fio, texto a 70%), para o botão branco com foco saltar aos olhos a 3 m (15/11/2026).
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Estilo.SUPERFICIE); setStroke(dp(1), Estilo.BORDA) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.FUNDO, Estilo.TEXTO_2)))
        setOnClickListener { aoApertar() }
    }

    private fun rotuloValor(r: String, v: String) = TextView(this).apply {
        text = "$r: $v"; textSize = Estilo.Leitura.CREDITOS; setTextColor(suave); setPaddingRelative(0, dp(12), 0, 0); setLineSpacing(0f, 1.15f)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (painelFicha.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { fecharFicha(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && !coluna.temFoco() && itens.isNotEmpty()) { coluna.focar(); return true }
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
        text = t; textSize = Estilo.Leitura.ROTULO; typeface = Fontes.nunito(600); letterSpacing = 0.06f; setTextColor(Estilo.TEXTO_3)
        setPaddingRelative(dp(10), dp(4), dp(10), dp(8)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * Ponto onde parou, em minutos ("19 min", "1 h 05 min"). Antes era "19:26", que de longe parece a hora do dia
     * (crítica de design 19/11/2026). Arredonda para baixo: volta um pouco antes, nunca depois.
     */
    private fun ponto(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min < 60) "$min min" else String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60)
    }

    private fun vidro(raio: Int) = GradientDrawable().apply { cornerRadius = dp(raio).toFloat(); setColor(0xD9121528.toInt()); setStroke(dp(1), Estilo.SUPERFICIE_ALTA) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

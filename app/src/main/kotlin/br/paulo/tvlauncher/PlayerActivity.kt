package br.paulo.tvlauncher

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, passo 2 (06/11/2026): a TV ao vivo em tela cheia (esboço F da versão 2: reprodução com mini-guia).
 *
 * Roda no processo ":player" (manifesto), separado da HOME: se o vídeo travar ou gastar memória demais, o Android
 * derruba só o player, e a HOME continua de pé. O motor é o Media3 (ExoPlayer), o mesmo do YouTube e de muitos
 * players de IPTV. A tela é toda em código (Views), como o resto do launcher.
 *
 * Controle:
 *   ▲ ▼ (ou CH+ CH-)  troca de canal (dentro da categoria escolhida na lista, se o canal for dela)
 *   OK                 abre a lista completa (PlayerListaTela: categorias, canais com logo e guia, detalhe)
 *   ◀                  volta ao canal anterior  0 a 9    digita o número do canal (do celular ou de controle com números)
 *   ▶                  mostra a faixa do canal; ▶ de novo abre as opções (TV de volta, proporção da imagem)
 *   VOLTAR             fecha o que estiver aberto; sem nada aberto, sai
 * Na TV de volta (programa que já passou): ◀ ▶ voltam/avançam 30 s, OK abre as opções, VOLTAR e ▲▼ voltam ao ao vivo.
 * Na lista: ▲ ▼ escolhem, ◀ ▶ trocam de coluna, OK assiste, OK segurado favorita.
 * (06/11/2026: a lista completa substituiu o mini-guia de 7 linhas.)
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_CANAL = "canal"   // id do canal para abrir (vazio = o último assistido)
        private const val ESCONDER_INFO_MS = 5_000L
        private const val ESPERA_NUMERO_MS = 1_500L
        private const val SEGURAR_FAVORITO_MS = 2_000L   // segurar OK este tempo põe/tira dos favoritos
    }

    private val tela = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var atual: Canal? = null
    private var anterior: Canal? = null
    private var tentativas = 0
    private var digitado = ""
    private var favoritos: Set<String> = emptySet()
    private var perfilCarregado = ""   // de que perfil são os [favoritos] na memória (ideia 11)
    private var recentes: List<String> = emptyList()
    private var categoria = PlayerLista.TODOS   // a escolhida na lista: o ▲▼ zapeia dentro dela
    private var escondidos: Set<String> = emptySet()   // grupos que o usuário escondeu
    private var liberados: Set<String> = emptySet()    // grupos adultos que ele liberou (começam ocultos)
    private var parental = ControleParental.Regras()   // canais bloqueados (Configurações > Controle parental)
    private var filtro = PlayerFiltro.Filtro()
    private var visiveis: List<Canal> = emptyList()    // canais depois do filtro: é o que a lista, o ▲▼ e o número usam
    private var ultimaBusca = ""
    private lateinit var janelas: PlayerJanelas
    private var visivel = false   // entre onStart e onStop: fora disso nada toca (nem a nova tentativa atrasada)
    private var modoImagem = PlayerImagem.Modo.AJUSTAR
    // TV de volta: o programa do passado que está tocando no lugar do ao vivo (null = ao vivo) e o que veio do servidor
    private var arquivo: TvDeVolta.Programa? = null
    private var arquivoLista: List<TvDeVolta.Programa> = emptyList()
    private var arquivoFuso: java.util.TimeZone = java.util.TimeZone.getDefault()
    private var arquivoCanal: String? = null   // de qual canal é a lista acima
    // Pausa curta no ao vivo (ideia 18): só na memória do player; trocar de canal ou sair descarta (PausaAoVivo)
    private val pausa = PausaCurta()

    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val suave = Estilo.TEXTO_2
    private val ambar = Estilo.ATENCAO
    private val fonteMedia = Fontes.manrope(500)

    private lateinit var video: SurfaceView
    private lateinit var info: LinearLayout
    private lateinit var infoNumero: TextView
    private lateinit var infoTitulo: TextView
    private lateinit var infoLinha: TextView
    private lateinit var infoHora: TextView
    private lateinit var infoProgresso: ProgressBar
    private lateinit var vivo: TextView
    private lateinit var lista: PlayerListaTela
    private lateinit var grade: PlayerGradeView
    private lateinit var numero: TextView
    private lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a TV não dorme no meio do jogo
        modoImagem = PlayerImagem.modo(this)
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        raiz.addView(montarInfo(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(Estilo.MARGEM_LATERAL), 0, dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL)) })
        janelas = PlayerJanelas(this)
        lista = PlayerListaTela(this, aoAssistir = { c, chave -> escolherNaLista(c, chave) }, aoFavoritar = { alternarFavorito(it) },
            aoBuscar = { buscar() }, aoFiltros = { abrirFiltros() }, aoOcultarGrupo = { alternarOculto(it) },
            aoGuia = { cs, nome -> abrirGrade(cs, nome) })
        raiz.addView(lista.raiz, FrameLayout.LayoutParams(-1, -1))
        grade = PlayerGradeView(this, aoAssistir = { c -> grade.fechar(); lista.fechar(); if (c.id != atual?.id) sintonizar(c) else mostrarInfo() },
            aoFechar = { grade.fechar(); if (lista.aberta) lista.voltarFoco() })
        raiz.addView(grade, FrameLayout.LayoutParams(-1, -1))
        numero = TextView(this).apply {
            textSize = 56f; typeface = Fontes.manrope(600); setTextColor(papel); background = vidro(Estilo.RAIO)
            setPadding(dp(22), dp(6), dp(22), dp(10)); visibility = View.GONE
        }
        raiz.addView(numero, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(Estilo.MARGEM_LATERAL), dp(28), 0, 0) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.player_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)
        carregar()
    }

    override fun onStart() {
        super.onStart()
        visivel = true
        if (canais.isEmpty()) return   // 1ª abertura: o carregar() sintoniza
        recarregarGuiaSeVelho()   // singleTask: voltar à frente não passa pelo onCreate, e o guia podia ter vencido
        // Voltou para o player: relê o controle parental (pode ter mudado nas Configurações) e retoma o canal, se ele
        // ainda puder tocar; um canal que ficou bloqueado não volta a tocar sem o PIN.
        parental = PlayerArmazem.parental(this)
        // Perfis de verdade (ideia 11): trocou de perfil com o player atrás, os favoritos na memória são do outro perfil.
        val perfil = Perfil.chaveDados(this)
        if (perfil != perfilCarregado) { perfilCarregado = perfil; favoritos = PlayerArmazem.favoritos(this); lista.atualizarFavoritos(favoritos) }
        refiltrar()
        val c = atual?.let { a -> visiveis.firstOrNull { it.id == a.id } } ?: visiveis.firstOrNull()
        if (c != null) sintonizar(c, guardarAnterior = false) else { atual = null; mostrarAviso(getString(R.string.player_sem_canais)) }
    }

    /**
     * Player já aberto e chegou um pedido de canal (voz ou Claude: CanalTv.tocar). Como a tela é singleTask, o pedido
     * chega aqui e não no onCreate. Só troca para canal visível: bloqueado ou de categoria oculta não toca sem o PIN.
     */
    override fun onNewIntent(novo: android.content.Intent) {
        super.onNewIntent(novo)
        setIntent(novo)   // se a lista ainda está carregando, o carregar() lê o canal pedido daqui
        if (canais.isNotEmpty()) recarregarGuiaSeVelho()   // o novo pedido não passa pelo onStart: confere o guia aqui (player-12)
        val id = novo.getStringExtra(EXTRA_CANAL)?.ifEmpty { null } ?: return
        if (canais.isNotEmpty() && !tocarPedido(id)) mostrarAviso(getString(R.string.player_bloqueado))
    }

    /**
     * Canal pedido de fora (Claude/voz): procura sem os filtros de qualidade/país (só categoria oculta e controle parental
     * valem) e, se achar, limpa esses filtros para a lista e o ▲▼ continuarem no canal. False = não pode tocar.
     */
    private fun tocarPedido(id: String): Boolean {
        val c = PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(ocultas = filtro.ocultas, parental = parental)).firstOrNull { it.id == id }
            ?: return false
        if (filtro.ativo) { filtro = filtro.copy(qualidade = null, pais = null); refiltrar() }
        sintonizar(c)
        return true
    }

    override fun onStop() {
        super.onStop()
        visivel = false
        // O PIN vale só enquanto o player está na frente: saiu (HOME, outro app, tela apagou), tranca de novo.
        if (filtro.mostrarOcultas) filtro = filtro.copy(mostrarOcultas = false)
        // Saiu da tela (HOME, outro app): solta o vídeo na hora (e a pausa curta junto: ela não sobrevive a sair).
        player?.release(); player = null
        pausa.descartar()   // a pausa curta é só memória do player: saiu, acabou
    }

    override fun onDestroy() {
        tela.removeCallbacksAndMessages(null)
        disco.shutdown()   // não "Now": deixa terminar a última gravação (canal, recentes)
        super.onDestroy()
    }

    /**
     * Gravações no disco fora da thread da tela, em ordem (13/11/2026). Antes cada troca de canal gravava o ultimo.json e o
     * recentes.json (escrever + renomear) na thread que atende o controle; com a memória da TV apertada o disco disputa
     * com a troca de páginas e isso chegava a dezenas de ms. Uma thread só: a última gravação de um arquivo vence.
     */
    private val disco = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "player-disco").apply { isDaemon = true } }
    private fun gravar(tarefa: () -> Unit) {
        try { disco.execute { runCatching(tarefa).onFailure { android.util.Log.w("TvLauncher.Player", "gravar falhou: ${it.javaClass.simpleName}") } } }
        catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    // ---------------------------------------------------------------- carga (fora da thread da tela)

    private fun carregar() {
        recarregandoGuia = true   // o guia já vem logo abaixo: a troca de canal não dispara outro download junto
        Fundo.rodar {
            val lidos = PlayerCarga.canais(this)   // "lidos", não "lista": lista é a tela de canais
            PlayerArmazem.migrarIdsAntigos(this, lidos)   // player-8: favoritos com a URL como id viram o id estável
            val perfil = Perfil.chaveDados(this)
            val fav = PlayerArmazem.favoritos(this)
            val rec = PlayerArmazem.recentes(this)
            val cat = PlayerArmazem.categoria(this) ?: PlayerLista.TODOS
            val esc = PlayerArmazem.gruposEscondidos(this)
            val lib = PlayerArmazem.gruposLiberados(this)
            val par = PlayerArmazem.parental(this)
            tela.post {
                if (isDestroyed) return@post
                canais = lidos
                favoritos = fav; perfilCarregado = perfil; recentes = rec; categoria = cat; escondidos = esc; liberados = lib; parental = par
                refiltrar()
                if (visiveis.isEmpty()) { mostrarAviso(getString(R.string.player_sem_canais)); return@post }
                // Canais lidos: some o "Carregando os canais…" (13/11/2026, visto na Sala: ficava na tela por minutos em
                // cima da lista quando o canal não chegava a tocar). Se o canal demorar, o aviso dele aparece em 1,5 s.
                esconderAviso()
                // Abre no último canal só se ele ainda passa no filtro (um canal de categoria oculta não abre sem PIN).
                // Canal pedido de fora que não pode tocar: avisa em vez de cair em outro canal.
                val externo = intent.getStringExtra(EXTRA_CANAL)?.ifEmpty { null }
                if (externo != null) { if (!tocarPedido(externo)) mostrarAviso(getString(R.string.player_bloqueado)); return@post }
                val pedido = PlayerArmazem.ultimoCanal(this)
                sintonizar(visiveis.firstOrNull { it.id == pedido } ?: visiveis.first(), guardarAnterior = false)
            }
            // O guia vem depois: pode levar alguns segundos (arquivo grande) e o canal já está tocando.
            val g = PlayerCarga.guia(this, lidos)
            tela.post { recarregandoGuia = false; if (!isDestroyed) usarGuia(g) }
        }
    }

    // A PlayerActivity é singleTask: ao voltar para a frente (onStart) ela não passa pelo carregar(), e o guia
    // de horas atrás ficava na tela. Aqui: se o guia do disco venceu, baixa de novo FORA da thread da tela e troca.
    // Também na troca de canal: quem deixa o player ligado a noite toda recebe o guia novo no próximo zapping.
    private var recarregandoGuia = false
    private fun recarregarGuiaSeVelho() {
        if (recarregandoGuia || canais.isEmpty() || !PlayerCarga.guiaVelho(this)) return
        recarregandoGuia = true
        val lidos = canais
        Fundo.rodar {
            val g = runCatching { PlayerCarga.guia(this, lidos) }.getOrNull()
            tela.post {
                recarregandoGuia = false
                if (g != null && !isDestroyed) usarGuia(g)
            }
        }
    }

    // Revisão de 10/11/2026: canal fora do cache não vai ao disco na thread da tela. O Guia lê em bloco em segundo plano
    // e avisa; o redesenho espera 100 ms para juntar vários avisos (rolar rápido a lista) num só.
    private val redesenharGuia = Runnable { if (!isDestroyed) { atualizarInfo(); lista.atualizarGuia(guia); grade.atualizarGuia(guia) } }
    private fun usarGuia(g: Guia) {
        g.naTela = { Looper.myLooper() === Looper.getMainLooper() }
        g.aoCarregar = { tela.removeCallbacks(redesenharGuia); tela.postDelayed(redesenharGuia, 100L) }
        guia = g; atualizarInfo(); lista.atualizarGuia(g); grade.atualizarGuia(g)
    }

    // ---------------------------------------------------------------- vídeo

    private fun sintonizar(c: Canal, guardarAnterior: Boolean = true) {
        if (guardarAnterior && atual != null && atual?.id != c.id) anterior = atual
        atual = c
        arquivo = null   // trocar de canal (ou voltar ao player) é sempre ao vivo
        tentativas = 0
        gravarUltimo(c)
        tocar(c)
        mostrarInfo()
        recarregarGuiaSeVelho()
        // Zapping: o guia dos vizinhos (▲▼) já vai para o cache, para a faixa aparecer completa no próximo canal.
        val i = visiveis.indexOfFirst { it.id == c.id }
        if (i >= 0) guia.precarregar(listOfNotNull(visiveis.getOrNull(i - 1), visiveis.getOrNull(i + 1)).map { it.guiaId })
    }

    private fun tocar(c: Canal, url: String = c.url) {
        pausa.descartar(); tela.removeCallbacks(pausaEstourou)   // mídia nova: o que estava pausado na memória vai embora
        if (!visivel) return
        // Trava final do controle parental: canal bloqueado não toca sem o PIN, venha de onde vier o pedido.
        if (ControleParental.bloqueado(c, parental) && !filtro.mostrarOcultas) { mostrarAviso(getString(R.string.player_bloqueado)); return }
        if (!ControleParental.bloqueado(c, parental)) lembrarRecente(c)   // bloqueado não vai para os recentes
        val p = player ?: ExoPlayer.Builder(this).setLoadControl(pausa.novaCarga()).build().also { novo ->
            novo.setVideoSurfaceView(video)
            novo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            novo.addListener(ouvinte)
            player = novo
        }
        // Algumas listas exigem um User-Agent próprio por canal: por isso a fábrica é montada a cada troca.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.agenteUsuario.ifEmpty { "TvLauncher-Player/1.0" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val item = MediaItem.Builder().setUri(url).apply {
            // .m3u8 = HLS. Sem extensão conhecida, o Media3 descobre sozinho pelo conteúdo (ex.: .ts do Xtream).
            if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private val ouvinte = object : Player.Listener {
        override fun onPlaybackStateChanged(estado: Int) {
            when (estado) {
                Player.STATE_READY -> { tentativas = 0; esconderAviso() }
                Player.STATE_BUFFERING -> if (aviso.visibility != View.VISIBLE) tela.postDelayed(avisoCarregando, 1_500)
                Player.STATE_ENDED -> if (arquivo != null) fimDoArquivo()
                else -> {}
            }
        }

        override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
            tamanhoVideo = v
            PlayerImagem.aplicar(video, modoImagem, v.width, v.height, v.pixelWidthHeightRatio)
        }

        override fun onPlayerError(erro: PlaybackException) {
            val p = player ?: return
            // Ficou para trás na janela ao vivo (rede engasgou): pula para o "agora" sem incomodar ninguém.
            if (erro.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                if (pausa.ativa) { pausa.descartar(); p.playWhenReady = true; mostrarAvisoBreve(getString(R.string.pausa_voltou)) }   // perdeu o atraso
                p.seekToDefaultPosition(); p.prepare(); return
            }
            val c = atual ?: return
            if (tentativas < 3) {
                tentativas++
                mostrarAviso(getString(R.string.player_tentando, c.numero))
                val prog = arquivo
                tela.postDelayed({
                    if (atual?.id != c.id || arquivo !== prog) return@postDelayed
                    if (prog != null) tocarArquivo(prog, p.currentPosition) else tocar(c)
                }, 2_000L shl (tentativas - 1))   // 2, 4, 8 s
            } else {
                mostrarAviso(getString(R.string.player_sem_sinal, c.numero))
            }
        }
    }

    private val avisoCarregando = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING && !lista.aberta && !grade.aberta) mostrarAviso(getString(R.string.player_carregando_canal))
    }

    // ---------------------------------------------------------------- controle

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (grade.aberta) return super.onKeyDown(keyCode, event)   // a grade trata as dela; o que sobra (volume) segue
        if (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == KeyEvent.KEYCODE_TV_DATA_SERVICE) {
            // Tecla GUIA do controle (quando há): a grade da categoria escolhida, direto do vídeo.
            val onde = PlayerLista.filtrar(visiveis, categoria, favoritos, recentes).ifEmpty { visiveis }
            abrirGrade(onde, getString(R.string.player_acao_guia)); return true
        }
        if (lista.aberta) {
            if (lista.tecla(keyCode)) return true
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) lista.fechar()   // número digitado: some a lista e troca
            else return super.onKeyDown(keyCode, event)
        }
        if (arquivo != null && teclaArquivo(keyCode, event)) return true
        if (arquivo == null && teclaPausa(keyCode)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zapear(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapear(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Só chega aqui com um número digitado (o resto do OK é tratado em dispatchKeyEvent).
                if (digitado.isNotEmpty()) confirmarNumero()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                anterior?.let { sintonizar(it) } ?: mostrarInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                // 1º ▶ mostra a faixa; com ela na tela, o 2º abre as opções (o controle do Chromecast não tem MENU)
                if (info.visibility == View.VISIBLE) opcoes() else mostrarInfo()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_REWIND -> { opcoes(); return true }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    digitado.isNotEmpty() -> { digitado = ""; numero.visibility = View.GONE }
                    info.visibility == View.VISIBLE -> info.visibility = View.GONE
                    else -> finish()
                }
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digitar((keyCode - KeyEvent.KEYCODE_0).toString()); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun ehOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    // OK no vídeo ou num canal da lista (pedido do dono, 06/11/2026): toque curto = abrir a lista / assistir;
    // segurar 2 s = pôr nos favoritos, ou tirar se já for favorito. Tratado aqui, antes da ListView, porque o
    // "segurar" do Android dispara em ~0,5 s e não dá para mudar esse tempo na ListView.
    private var okAlvo = 0          // 0 = nada; 1 = vídeo; 2 = canal em foco na lista
    private var okSegurou = false   // já favoritou nesta apertada: soltar não faz mais nada

    /** Soltou ◀ ▶ no passado depois de segurar: o vídeo pula uma vez só, para onde a faixa mostrou. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (arquivo != null) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val ir = avanco.soltou()
                if (ir >= 0) { player?.seekTo(ir); mostrarInfo() }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val nosso = ehOk(e.keyCode) && !grade.aberta && digitado.isEmpty() && (!lista.aberta || lista.canaisComFoco())
        if (!nosso) {
            if (ehOk(e.keyCode) && e.action == KeyEvent.ACTION_UP && okAlvo != 0) okAlvo = 0
            return super.dispatchKeyEvent(e)
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) { okSegurou = false; okAlvo = if (lista.aberta) 2 else 1 }
                else if (!okSegurou && okAlvo != 0 && e.eventTime - e.downTime >= SEGURAR_FAVORITO_MS) {
                    okSegurou = true
                    (if (okAlvo == 2) lista.canalEmFoco() else atual)?.let { alternarFavorito(it) }
                }
            }
            KeyEvent.ACTION_UP -> {
                val alvo = okAlvo
                okAlvo = 0
                if (!okSegurou && !e.isCanceled) when (alvo) {
                    1 -> if (arquivo != null) opcoes() else if (pausa.pausado) continuarPausa() else abrirLista()
                    2 -> lista.assistirEmFoco()
                }
            }
        }
        return true
    }

    /** ▲ sobe o número do canal (como num controle de TV comum), dentro da categoria escolhida na lista. */
    private fun zapear(passo: Int) {
        val onde = PlayerLista.listaDeZapping(visiveis, categoria, favoritos, recentes, atual)
        PlayerDados.vizinho(onde, atual, passo)?.let { sintonizar(it) }
    }

    private fun digitar(d: String) {
        if (digitado.length >= 5) return
        digitado += d
        numero.text = "${digitado}_"
        numero.visibility = View.VISIBLE
        tela.removeCallbacks(confirmarNumeroDepois)
        tela.postDelayed(confirmarNumeroDepois, ESPERA_NUMERO_MS)
    }

    private val confirmarNumeroDepois = Runnable { confirmarNumero() }

    private fun confirmarNumero() {
        tela.removeCallbacks(confirmarNumeroDepois)
        val n = digitado.toIntOrNull()
        digitado = ""
        numero.visibility = View.GONE
        val c = n?.let { PlayerDados.porNumero(visiveis, it) }
        if (c != null) sintonizar(c) else if (n != null) mostrarAvisoBreve(getString(R.string.player_canal_nao_existe, n))
    }

    // ---------------------------------------------------------------- faixa de informação (embaixo)

    private fun montarInfo(): View {
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = vidro(Estilo.RAIO)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            visibility = View.GONE
        }
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoNumero = TextView(this).apply {
            textSize = 22f; typeface = Fontes.manrope(600); setTextColor(Estilo.FUNDO); gravity = Gravity.CENTER
            background = Estilo.fundo(context, ambar, Estilo.RAIO_PEQUENO)
            // Largura conforme o número: canal 1655 quebrava em "165 / 5" na caixa fixa de 48 dp (visto na Sala, 14/11/2026).
            maxLines = 1; minWidth = dp(48); setPadding(dp(8), 0, dp(8), 0)
        }
        linha.addView(infoNumero, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(14) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoTitulo = Estilo.texto(this, Estilo.Papel.TITULO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoLinha = TextView(this).apply { textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(suave); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        textos.addView(infoTitulo); textos.addView(infoLinha)
        linha.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        // "● AO VIVO" em texto vermelho (o mesmo da HOME), sem a etiqueta colorida; ao lado, "faltam X min · hora".
        vivo = Estilo.texto(this, Estilo.Papel.SECAO, "● " + getString(R.string.player_ao_vivo)).apply { setTextColor(Estilo.ERRO) }
        linha.addView(vivo, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        infoHora = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { textSize = Estilo.Leitura.ITEM_DETALHE }
        linha.addView(infoHora)
        info.addView(linha)
        infoProgresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Estilo.ERRO)
            progressBackgroundTintList = ColorStateList.valueOf(Estilo.VIDRO_BORDA)
        }
        info.addView(infoProgresso, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        info.addView(TextView(this).apply {
            text = getString(R.string.player_teclas); textSize = 14f; setTextColor(Estilo.TEXTO_3)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        return info
    }

    private val esconderInfo = Runnable { info.visibility = View.GONE }

    private fun mostrarInfo() {
        // Com a lista ou a grade aberta (ex.: voltou de Filmes e o canal retomou), a faixa do canal ficaria por cima delas.
        if (lista.aberta || grade.aberta) return
        atualizarInfo()
        info.visibility = View.VISIBLE
        tela.removeCallbacks(esconderInfo)
        tela.postDelayed(esconderInfo, ESCONDER_INFO_MS)
    }

    // O que já foi gravado no ultimo.json (canal + início do 1º programa): só regrava quando muda.
    private var gravado = ""

    /** O último canal e os próximos programas dele, para o destaque da HOME (que roda em outro processo). */
    private fun gravarUltimo(c: Canal) {
        // O "Continuar" da HOME não mostra canal bloqueado nem de categoria oculta (antes só olhava o bloqueado, e um
        // canal adulto aberto com o PIN ia parar na HOME com nome e programa).
        if (!PlayerFiltro.podeIrParaHome(c, filtro.ocultas, parental)) return
        val programas = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, System.currentTimeMillis(), 4) }.orEmpty()
        val marca = "${c.id}|${programas.firstOrNull()?.inicio}|${programas.size}"
        if (marca == gravado) return
        gravado = marca
        gravar { PlayerArmazem.definirUltimoCanal(this, c, programas) }
    }

    private fun atualizarInfo() {
        val c = atual ?: return
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
        arquivo?.let { a -> infoDoArquivo(c, a, hora); return }
        vivo.text = "● " + getString(R.string.player_ao_vivo)
        vivo.setTextColor(Estilo.ERRO)
        if (pausa.ativa) {   // pausado ou atrasado: âmbar, como o passado do TV de volta
            vivo.text = if (pausa.pausado) getString(R.string.pausa_selo)
                else PausaAoVivo.textoAtraso(pausa.atrasoMs, getString(R.string.pausa_atrasado_min), getString(R.string.pausa_atrasado_seg))
            vivo.setTextColor(Estilo.ATENCAO)
        }
        gravarUltimo(c)   // o guia pode ter acabado de carregar, ou o programa ter mudado
        val agora = System.currentTimeMillis()
        val prog = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, 2) }.orEmpty()
        val atualProg = prog.firstOrNull()?.takeIf { agora >= it.inicio }
        infoNumero.text = c.numero.toString()
        infoTitulo.text = atualProg?.titulo ?: c.nome
        val partes = mutableListOf(c.nome)
        atualProg?.let { partes += "${hora.format(Date(it.inicio))} – ${hora.format(Date(it.fim))}" }
        prog.getOrNull(if (atualProg != null) 1 else 0)?.let { partes += getString(R.string.player_a_seguir, it.titulo) }
        infoLinha.text = partes.joinToString("  ·  ")
        // Quanto falta do programa de agora, como no card TV da HOME.
        infoHora.text = atualProg?.let { getString(R.string.home_faltam, minutosTexto(((it.fim - agora + 59_999) / 60_000).toInt())) + "  ·  " }
            .orEmpty() + hora.format(Date(agora))
        infoProgresso.visibility = if (atualProg != null) View.VISIBLE else View.INVISIBLE
        atualProg?.let { infoProgresso.progress = (guia.progresso(it, agora) * 1000).toInt() }
    }

    // ---------------------------------------------------------------- opções, proporção e TV de volta

    private var tamanhoVideo: androidx.media3.common.VideoSize? = null

    /** Opções do canal: TV de volta (se o canal tiver), proporção da imagem e, no passado, voltar ao ao vivo. */
    private fun opcoes() {
        val c = atual ?: return
        info.visibility = View.GONE
        val itens = ArrayList<Pair<String, () -> Unit>>()
        if (arquivo != null) {
            val p = player
            if (p != null) itens += getString(if (p.isPlaying) R.string.tvv_pausar else R.string.tvv_continuar) to {
                p.playWhenReady = !p.isPlaying; mostrarInfo()
            }
            itens += getString(R.string.tvv_ao_vivo) to { sintonizar(c, guardarAnterior = false) }
        } else if (player != null) {
            if (pausa.ativa) itens += getString(R.string.pausa_ao_vivo) to { voltarAoVivo() }   // 1º: ▶ e OK = ao vivo
            itens += getString(if (pausa.pausado) R.string.pausa_continuar else R.string.pausa_pausar) to { alternarPausa() }
        }
        val origem = if (c.arquivoDias > 0) TvDeVolta.origem(c.url) else null
        if (origem != null) itens += getString(R.string.tvv_abrir) to { abrirTvDeVolta(c, origem) }
        itens += getString(R.string.imagem_opcao, PlayerImagem.rotulo(this, modoImagem)) to { escolherImagem() }
        Estilo.lista(this, c.nome, itens.map { it.first }) { i -> itens[i].second() }
    }

    private fun escolherImagem() = PlayerImagem.escolher(this, modoImagem) { m ->
        modoImagem = m
        tamanhoVideo?.let { v -> PlayerImagem.aplicar(video, modoImagem, v.width, v.height, v.pixelWidthHeightRatio) }
    }

    /** Baixa (fora da tela) os programas guardados do canal e mostra a lista: o mais novo primeiro. */
    private fun abrirTvDeVolta(c: Canal, origem: TvDeVolta.Origem) {
        if (arquivoCanal == c.id && arquivoLista.isNotEmpty()) { listarArquivo(c); return }
        mostrarAviso(getString(R.string.tvv_carregando))
        Fundo.rodar {
            val r = PlayerCarga.tvDeVolta(origem)
            tela.post {
                if (isDestroyed || atual?.id != c.id) return@post
                esconderAviso()
                if (r == null) { mostrarAvisoBreve(getString(R.string.tvv_sem_resposta)); return@post }
                arquivoLista = r.first; arquivoFuso = r.second; arquivoCanal = c.id
                listarArquivo(c)
            }
        }
    }

    private fun listarArquivo(c: Canal) {
        val agora = System.currentTimeMillis()
        val lista = TvDeVolta.assistiveis(arquivoLista, agora, c.arquivoDias).take(400)
        if (lista.isEmpty()) { mostrarAvisoBreve(getString(R.string.tvv_vazio)); return }
        val rotulos = lista.map { p ->
            if (TvDeVolta.passandoAgora(p, agora)) getString(R.string.tvv_do_inicio, p.titulo)
            else "${diaCurto(p.inicio)}  ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.inicio))}   ${p.titulo}"
        }
        Estilo.lista(this, getString(R.string.tvv_titulo, c.nome), rotulos) { i -> tocarArquivo(lista[i], 0) }
    }

    /** "Hoje", "Ontem" ou "seg 10/11". */
    private fun diaCurto(ms: Long): String = when (Datas.diasAte(ms, System.currentTimeMillis())) {
        0L -> getString(R.string.tvv_hoje)
        -1L -> getString(R.string.tvv_ontem)
        else -> SimpleDateFormat("EEE dd/MM", Locale.getDefault()).format(Date(ms))
    }

    /** Toca o programa do passado no lugar do ao vivo, a partir de `posicao` ms do começo dele. */
    private fun tocarArquivo(p: TvDeVolta.Programa, posicao: Long) {
        val c = atual ?: return
        val o = TvDeVolta.origem(c.url) ?: return
        arquivo = p
        tocar(c, TvDeVolta.urlArquivo(o, p.inicio, p.fim, arquivoFuso))
        if (posicao > 0) player?.seekTo(posicao)
        mostrarInfo()
    }

    /** Acabou o programa do passado: emenda o seguinte; se o seguinte é o ao vivo, volta para ele. */
    private fun fimDoArquivo() {
        val c = atual ?: return
        val a = arquivo ?: return
        val prox = TvDeVolta.seguinte(arquivoLista, a, System.currentTimeMillis())
        if (prox != null) tocarArquivo(prox, 0) else { sintonizar(c, guardarAnterior = false); mostrarAvisoBreve(getString(R.string.tvv_voltou)) }
    }

    private val avanco = AvancoSegurado()   // ◀ ▶ segurados no passado: posição escolhida enquanto a tecla está apertada

    /** Teclas no passado: ◀ ▶ andam 30 s (segurado acelera aos poucos, ver AvancoSegurado); ▲ ▼ e VOLTAR voltam ao ao vivo
     *  (o ▲▼ ainda troca de canal, que é ao vivo). */
    private fun teclaArquivo(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val passo = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 30_000L else -30_000L
                val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                val ir = avanco.apertou(p.currentPosition, dur, passo, event.repeatCount, event.eventTime, event.downTime)
                if (ir >= 0) p.seekTo(ir)
                mostrarInfo()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = !p.isPlaying; mostrarInfo() }
            KeyEvent.KEYCODE_BACK -> {
                if (digitado.isNotEmpty() || info.visibility == View.VISIBLE) return false
                atual?.let { sintonizar(it, guardarAnterior = false) }
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- pausa curta no ao vivo (ideia 18)

    /** Teclas no ao vivo pausado ou atrasado: ⏯ pausa/continua; ▶▶ volta ao vivo; ▶ abre as opções direto (Ao vivo). */
    private fun teclaPausa(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> alternarPausa()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (pausa.ativa) voltarAoVivo() else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pausa.ativa) opcoes() else return false
            else -> return false
        }
        return true
    }

    private fun alternarPausa() {
        val p = player ?: return
        if (pausa.pausado) { continuarPausa(); return }
        if (!p.isPlaying) return   // carregando ou sem sinal: nada para segurar
        val estoura = pausa.pausar(p)
        tela.removeCallbacks(pausaEstourou)
        tela.postDelayed(pausaEstourou, estoura)
        mostrarInfo()
    }

    private fun continuarPausa() {
        val p = player ?: return
        tela.removeCallbacks(pausaEstourou)
        if (pausa.continuar(p)) mostrarInfo() else { voltarAoVivo(); mostrarAvisoBreve(getString(R.string.pausa_voltou)) }
    }

    /** Ao vivo de novo, no mesmo canal: recomeça a mídia (o tocar descarta o que estava na memória). */
    private fun voltarAoVivo() { atual?.let { tocar(it); mostrarInfo() } }

    /** Pausado até o buffer chegar ao teto: para de baixar e solta a memória já; ao continuar, volta ao vivo e avisa. */
    private val pausaEstourou = Runnable { if (pausa.pausado) player?.stop() }

    private fun infoDoArquivo(c: Canal, a: TvDeVolta.Programa, hora: SimpleDateFormat) {
        vivo.text = getString(R.string.tvv_selo)
        vivo.setTextColor(Estilo.ATENCAO)   // passado: âmbar, para não confundir com o vermelho do ao vivo
        infoNumero.text = c.numero.toString()
        infoTitulo.text = a.titulo
        infoLinha.text = listOf(c.nome, "${diaCurto(a.inicio)} ${hora.format(Date(a.inicio))} – ${hora.format(Date(a.fim))}",
            getString(R.string.tvv_teclas)).joinToString("  ·  ")
        val pos = avanco.alvo.takeIf { it >= 0 } ?: player?.currentPosition ?: 0   // segurando ◀ ▶: mostra para onde vai
        infoHora.text = hora.format(Date(a.inicio + pos))
        infoProgresso.visibility = View.VISIBLE
        infoProgresso.progress = ((pos.toDouble() / (a.fim - a.inicio).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
    }

    // ---------------------------------------------------------------- lista completa (PlayerListaTela)

    private fun abrirLista(focarCategorias: Boolean = false) {
        if (canais.isEmpty()) return
        info.visibility = View.GONE
        lista.abrir(visiveis, guia, favoritos, recentes, categoria, atual, PlayerFiltro.resumo(filtro),
            if (filtro.mostrarOcultas) filtro.ocultas else emptySet(), focarCategorias)
    }

    private fun abrirGrade(canaisDaGrade: List<Canal>, nome: String) {
        if (canaisDaGrade.isEmpty()) return
        info.visibility = View.GONE
        grade.abrir(canaisDaGrade, guia, nome, atual)
    }

    /** Recalcula as ocultas e a lista visível (depois de carregar, mudar filtro ou esconder um grupo). */
    private fun refiltrar() {
        filtro = filtro.copy(ocultas = PlayerFiltro.ocultasEfetivas(canais, escondidos, liberados), parental = parental)
        visiveis = PlayerFiltro.aplicar(canais, filtro)
    }

    // ---------------------------------------------------------------- busca e filtros

    private fun buscar() {
        janelas.buscar(ultimaBusca) { termo ->
            if (termo.isEmpty()) return@buscar
            ultimaBusca = termo
            // Fora da thread da tela e com UMA consulta para os títulos de agora (antes: uma por canal, na tela; com
            // milhares de canais travava).
            val (g, onde) = guia to visiveis
            Fundo.rodar {
                val titulos = g.titulosAgora(System.currentTimeMillis())
                val achados = PlayerFiltro.buscar(onde, termo, { c -> titulos[c.guiaId] })
                tela.post {
                    if (isDestroyed) return@post
                    lista.mostrarBusca(termo, achados)
                    if (achados.isEmpty()) mostrarAvisoBreve(getString(R.string.player_busca_nada, termo))
                }
            }
        }
    }

    private fun abrirFiltros() {
        janelas.filtros(if (filtro.mostrarOcultas) canais else visiveisSemFiltroDeNome(), filtro, filtro.ocultas.isNotEmpty() || filtro.parental.ativo,
            aoMudar = { novo -> aplicarFiltro(novo) },
            aoPedirOcultas = { janelas.pedirPin(aoLiberar = { aplicarFiltro(filtro.copy(mostrarOcultas = true)) }, aviso = { mostrarAvisoBreve(it) }) })
    }

    /** Canais que as opções de filtro consideram: tudo menos as categorias ocultas (sem PIN, elas não contam). */
    private fun visiveisSemFiltroDeNome(): List<Canal> = PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(ocultas = filtro.ocultas, parental = filtro.parental))

    private fun aplicarFiltro(novo: PlayerFiltro.Filtro) {
        filtro = novo
        refiltrar()
        abrirLista(focarCategorias = true)   // reabre com o filtro novo, no mesmo lugar
    }

    /** OK segurado numa categoria: esconde; se ela já estava oculta (aparecendo pelo PIN), volta a mostrar. */
    private fun alternarOculto(grupo: String) {
        val oculto = grupo in filtro.ocultas
        if (oculto) {
            escondidos = escondidos - grupo
            if (PlayerFiltro.ehAdulto(grupo)) liberados = liberados + grupo
        } else {
            escondidos = escondidos + grupo
            liberados = liberados - grupo
            if (categoria == PlayerLista.chaveDoGrupo(grupo)) categoria = PlayerLista.TODOS
        }
        val e = escondidos; val l = liberados
        gravar { PlayerArmazem.definirGruposEscondidos(this, e); PlayerArmazem.definirGruposLiberados(this, l) }
        refiltrar()
        abrirLista(focarCategorias = true)
        mostrarAvisoBreve(getString(if (oculto) R.string.player_grupo_mostrado else R.string.player_grupo_oculto,
            grupo.ifEmpty { getString(R.string.player_cat_outros) }))
    }

    private fun escolherNaLista(c: Canal, chave: String) {
        lista.fechar()
        if (chave != categoria) { categoria = chave; PlayerArmazem.definirCategoria(this, chave) }
        if (c.id != atual?.id) sintonizar(c) else mostrarInfo()
    }

    private fun alternarFavorito(c: Canal) {
        val antes = favoritos
        favoritos = PlayerLista.alternarFavorito(favoritos, c.id)
        if (favoritos === antes) { mostrarAvisoBreve(getString(R.string.player_favoritos_cheio)); return }
        val virou = c.id in favoritos
        val fav = favoritos
        gravar { PlayerArmazem.definirFavoritos(this, fav) }
        lista.atualizarFavoritos(favoritos)
        mostrarAvisoBreve(getString(if (virou) R.string.player_favorito_pos else R.string.player_favorito_tirou, c.nome))
    }

    /** Recentes: só grava quando muda o 1º (as novas tentativas do mesmo canal não escrevem no disco). */
    private fun lembrarRecente(c: Canal) {
        if (recentes.firstOrNull() == c.id) return
        recentes = PlayerLista.registrarRecente(recentes, c.id)
        val rec = recentes
        gravar { PlayerArmazem.definirRecentes(this, rec) }
    }

    // ---------------------------------------------------------------- avisos

    private fun mostrarAviso(t: String) { aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun esconderAviso() { tela.removeCallbacks(avisoCarregando); aviso.visibility = View.GONE }
    private fun mostrarAvisoBreve(t: String) { mostrarAviso(t); tela.postDelayed({ esconderAviso() }, 2_500) }

    // ---------------------------------------------------------------- aparência

    /**
     * O vidro do sistema (mesmo fio claro e raio da HOME), mas com a tinta mais escura: aqui ele fica em cima de VÍDEO, que
     * pode ser uma cena clara (neve, estúdio branco); com os 55% da HOME o texto branco ficaria difícil de ler.
     */
    private fun vidro(raio: Int) = Estilo.fundo(this, Estilo.alfa(Estilo.FUNDO, 0xD9), raio, Estilo.VIDRO_BORDA)

    /** "19 min" ou "1 h 05 min". */
    private fun minutosTexto(m: Int) = if (m < 60) "$m min" else "${m / 60} h %02d min".format(m % 60)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

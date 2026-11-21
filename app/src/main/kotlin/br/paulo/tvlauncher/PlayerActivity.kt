package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import br.paulo.tvlauncher.alarm.Cronometros
import br.paulo.tvlauncher.alarm.Datas
import br.paulo.tvlauncher.family.ControleParental
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.RetrospectivaAno
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.home.tocandoAgora
import br.paulo.tvlauncher.input.AvancoSegurado
import br.paulo.tvlauncher.input.EfeitosSom
import br.paulo.tvlauncher.listen.SomNoFone
import br.paulo.tvlauncher.network.EntreTvs
import br.paulo.tvlauncher.observer.Falhas
import br.paulo.tvlauncher.observer.FalhasTv
import br.paulo.tvlauncher.observer.MedidasTv
import br.paulo.tvlauncher.observer.Placar
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.Canal
import br.paulo.tvlauncher.player.CanalReserva
import br.paulo.tvlauncher.player.FonteGravacao
import br.paulo.tvlauncher.player.FontesPlayer
import br.paulo.tvlauncher.player.GravacaoDestino
import br.paulo.tvlauncher.player.GravacaoRegras
import br.paulo.tvlauncher.player.Guia
import br.paulo.tvlauncher.player.GuiaConsulta
import br.paulo.tvlauncher.player.JuntoRegras
import br.paulo.tvlauncher.player.PausaAoVivo
import br.paulo.tvlauncher.player.PausaCurta
import br.paulo.tvlauncher.player.PlayerAcessivel
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerBar
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerDados
import br.paulo.tvlauncher.player.PlayerEngine
import br.paulo.tvlauncher.player.PlayerFiltro
import br.paulo.tvlauncher.player.PlayerGradeView
import br.paulo.tvlauncher.player.PlayerImagem
import br.paulo.tvlauncher.player.PlayerJanelas
import br.paulo.tvlauncher.player.PlayerLista
import br.paulo.tvlauncher.player.PlayerListaTela
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.PlayerMenu
import br.paulo.tvlauncher.player.Programa
import br.paulo.tvlauncher.player.QualidadeAuto
import br.paulo.tvlauncher.player.QualidadeTv
import br.paulo.tvlauncher.player.SessaoMidia
import br.paulo.tvlauncher.player.TunnelGuard
import br.paulo.tvlauncher.player.VariantesCanal
import br.paulo.tvlauncher.player.Xtream
import br.paulo.tvlauncher.player.Zapeio
import br.paulo.tvlauncher.player.alternarSom
import br.paulo.tvlauncher.player.escolherImagem
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.lembrarRecente
import br.paulo.tvlauncher.settings.mostrarBusca
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
        /** Elevação 20.4: o cronômetro da instalação só é conferido uma vez por processo (o arquivo decide o resto). */
        @Volatile private var instalacaoConferida = false
        const val EXTRA_CANAL = "canal"   // id do canal para abrir (vazio = o último assistido)
        const val EXTRA_CANAL_NOME = "canal_nome"   // nome normalizado (JuntoRegras.normalizarCanal), vindo de outra TV da casa
        private const val ESCONDER_INFO_MS = 5_000L
        private const val ESPERA_NUMERO_MS = 1_500L
        private const val SEGURAR_FAVORITO_MS = 2_000L   // segurar OK este tempo põe/tira dos favoritos
    }

    private val tela = Handler(Looper.getMainLooper())
    /** Túnel com volta automática (PlayerEngine): se prender ou falhar, retoma o canal (ou o programa do passado) no modo comum. */
    private val tunel = TunnelGuard(this, tela, "TvLauncher.Player") { p ->
        val a = arquivo
        if (a != null) tocarArquivo(a, p.currentPosition) else (tocando ?: atual)?.let { tocar(it) }
    }
    private var player: ExoPlayer? = null
    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var atual: Canal? = null
    private var anterior: Canal? = null
    private var tentativas = 0
    /** Elevação 5.2: ids e endereços que já caíram nesta sequência de reservas (zera quando a pessoa troca de canal). */
    private val reservaTentados = HashSet<String>()
    private var trocandoPorReserva = false
    private var avisoReservaAte = 0L   // o aviso da reserva fica legível mesmo depois que o canal novo começa
    private var digitado = ""
    private var favoritos: Set<String> = emptySet()
    private var perfilCarregado = ""   // de que perfil são os [favoritos] na memória (ideia 11)
    private var recentes: List<String> = emptyList()
    /** Modo simples da HOME (ModoSimples): a lista abre em Favoritos, com menos linhas e letra maior. */
    private val simples by lazy { ModoSimples.ligado(this) }
    private val efeitos = EfeitosSom()   // volume parecido e voz mais clara (PlayerAcessivel)
    private var categoria = PlayerLista.TODOS   // a escolhida na lista: o ▲▼ zapeia dentro dela
    private var escondidos: Set<String> = emptySet()   // grupos que o usuário escondeu
    private var liberados: Set<String> = emptySet()    // grupos adultos que ele liberou (começam ocultos)
    private var parental = ControleParental.Regras()   // canais bloqueados (Configurações > Controle parental)
    private var filtro = PlayerFiltro.Filtro()
    private var visiveis: List<Canal> = emptyList()    // canais depois do filtro: é o que a lista, o ▲▼ e o número usam
    // Mesmo canal em várias qualidades (18/11/2026): a lista mostra um só; aqui ficam as variantes de cada um.
    private var familias = VariantesCanal.Grupos(emptyList(), emptyMap(), emptyMap())
    private var tocando: Canal? = null   // o que está tocando: a [atual] com o endereço da variante escolhida
    private var nivelTocando: VariantesCanal.Nivel? = null
    private val tentadas = HashSet<String>()   // endereços que já falharam neste canal (erro): pula para outra variante
    private val travadas = QualidadeAuto.Travadas()
    private var jaTocou = false   // o canal já chegou a tocar (só depois disso uma espera conta como travada)
    private var travouEm = 0L     // quando começou a travada de agora (0 = não está travado)
    private val nivelDaTela by lazy {
        val modos = runCatching { @Suppress("DEPRECATION") windowManager.defaultDisplay.supportedModes }.getOrNull()
        QualidadeAuto.nivelDaTela(modos?.maxOfOrNull { minOf(it.physicalHeight, it.physicalWidth) } ?: 0)
    }
    private val medidor by lazy { androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this) }
    // A cada medida de rede do Media3 (um pedaço HLS baixado, um canal fechado): guarda a maior das últimas 24 h.
    private val ouvirBanda = androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener { ms, bytes, _ ->
        if (ms >= 500 && bytes > 0) { val bps = bytes * 8_000L / ms; val app = applicationContext; gravar { QualidadeTv.medir(app, bps) } }
    }
    private var ultimaBusca = ""
    private lateinit var janelas: PlayerJanelas
    private var visivel = false   // entre onStart e onStop: fora disso nada toca (nem a nova tentativa atrasada)
    private var modoImagem = PlayerImagem.Modo.AJUSTAR
    // TV de volta: o programa do passado que está tocando no lugar do ao vivo (null = ao vivo) e o que veio do servidor
    private val sessao by lazy { SessaoMidia(this, "tv", soControle = false) }
    private var arquivo: TvDeVolta.Programa? = null
    private var arquivoLista: List<TvDeVolta.Programa> = emptyList()
    private var arquivoFuso: java.util.TimeZone = java.util.TimeZone.getDefault()
    private var arquivoCanal: String? = null   // de qual canal é a lista acima
    // Pausa curta no ao vivo (ideia 18): só na memória do player; trocar de canal ou sair descarta (PausaAoVivo)
    private val pausa = PausaCurta()

    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val ambar = Estilo.ATENCAO

    private lateinit var video: SurfaceView
    private lateinit var info: LinearLayout
    private lateinit var infoNumero: TextView
    private lateinit var infoLogo: android.widget.ImageView   // elevação 5.1: o logo aparece no instante da tecla
    /** Elevação 5.1 (placar, pilar 2): tecla até o primeiro quadro do canal. */
    private val cronCanal = Cronometros.TrocaCanal()
    private lateinit var infoTitulo: TextView
    private lateinit var infoLinha: TextView
    private lateinit var infoHora: TextView
    private lateinit var infoTeclas: TextView   // a linha de teclas muda no pausado e no passado (as teclas mudam)
    private lateinit var infoProgresso: ProgressBar
    private lateinit var vivo: TextView
    private lateinit var lista: PlayerListaTela
    private lateinit var grade: PlayerGradeView
    private lateinit var numero: TextView
    private lateinit var aviso: TextView
    private lateinit var seloGravando: TextView   // "● GRAVANDO até 22:05" fixo no canto enquanto grava este canal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a TV não dorme no meio do jogo
        modoImagem = PlayerImagem.modo(this)
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        raiz.addView(montarInfo(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(Estilo.MARGEM_LATERAL), 0, dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL)) })
        // Selo de gravação (pedido do dono, 15/11/2026): fica no canto de cima mesmo com a faixa escondida. Entra antes da
        // lista e do guia, que cobrem o selo quando abertos.
        seloGravando = TextView(this).apply {
            textSize = 20f; typeface = Fontes.nunito(700); setTextColor(Estilo.ERRO); background = vidro(Estilo.RAIO)
            setPaddingRelative(dp(18), dp(8), dp(18), dp(10)); visibility = View.GONE
        }
        raiz.addView(seloGravando, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(Estilo.MARGEM_LATERAL), 0) })
        janelas = PlayerJanelas(this)
        medidor.addEventListener(tela, ouvirBanda)
        // Sessão junto (degrau 4): canal ao vivo = "mesmo canal" + reações; quem troca, a outra TV acompanha.
        junto = JuntoNoPlayer(this, aplicar = { _, _ -> }, posicao = { 0L }, tocandoAgora = { player?.isPlaying == true },
            trocarCanal = { nome -> juntoCanal(nome) }).also { it.ligar() }
        intent.getStringExtra(EXTRA_CANAL_NOME)?.let { juntoPendente = it }
        lista = PlayerListaTela(this, aoAssistir = { c, chave -> escolherNaLista(c, chave) }, aoFavoritar = { alternarFavorito(it) }, simples = simples,
            aoBuscar = { buscar() }, aoFiltros = { abrirFiltros() }, aoOcultarGrupo = { alternarOculto(it) },
            aoGuia = { cs, nome -> abrirGrade(cs, nome) },
            aoGravacoes = { startActivity(android.content.Intent(this, GravacoesActivity::class.java)) })
        raiz.addView(lista.raiz, FrameLayout.LayoutParams(-1, -1))
        grade = PlayerGradeView(this, aoAssistir = { c -> grade.fechar(); lista.fechar(); if (c.id != atual?.id) sintonizar(c) else mostrarInfo() },
            aoFechar = { grade.fechar(); if (lista.aberta) lista.voltarFoco() })
        raiz.addView(grade, FrameLayout.LayoutParams(-1, -1))
        numero = TextView(this).apply {
            textSize = 56f; typeface = Fontes.nunito(600); setTextColor(papel); background = vidro(Estilo.RAIO)
            setPaddingRelative(dp(22), dp(6), dp(22), dp(10)); visibility = View.GONE
        }
        raiz.addView(numero, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(Estilo.MARGEM_LATERAL), dp(28), 0, 0) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPaddingRelative(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.player_carregando)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)
        Gravacoes.ouvir(mudouGravacao)
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
        // Gravando: volta para o canal que grava (outro pediria uma 2ª conexão que a lista não dá).
        val c = Gravacoes.atual?.let { g -> visiveis.firstOrNull { it.id == familias.representante(g.canalId) } }
            ?: atual?.let { a -> visiveis.firstOrNull { it.id == a.id } } ?: visiveis.firstOrNull()
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
        val achado = PlayerFiltro.aplicar(canais, PlayerFiltro.Filtro(ocultas = filtro.ocultas, parental = parental)).firstOrNull { it.id == id }
            ?: return false
        if (filtro.ativo) { filtro = filtro.copy(qualidade = null, pais = null); refiltrar() }
        // O pedido pode ser de uma variante ("Globo 4K"): toca o canal que a representa (a qualidade é escolhida aqui).
        val c = visiveis.firstOrNull { it.id == familias.representante(achado.id) } ?: achado
        // Pedido de fora com a lista/guia aberta (visto na Sala 16/11/2026: "Troque sozinho na hora" trocou por baixo da
        // lista, que seguia mostrando o canal antigo como "assistindo"; um OK ali voltava para ele): fecha antes.
        grade.fechar(); lista.fechar()
        sintonizar(c)
        return true
    }

    override fun onStop() {
        super.onStop()
        visivel = false
        cronCanal.cancelar()
        // O PIN vale só enquanto o player está na frente: saiu (HOME, outro app, tela apagou), tranca de novo.
        if (filtro.mostrarOcultas) filtro = filtro.copy(mostrarOcultas = false)
        // Saiu da tela (HOME, outro app): solta o vídeo na hora (e a pausa curta junto: ela não sobrevive a sair).
        contarCanal(null)   // ideia 92: fecha os minutos do canal que estava tocando
        efeitos.soltar()
        sessao.soltar(); tunel.release()
        player?.release(); player = null
        pausa.descartar()   // a pausa curta é só memória do player: saiu, acabou
    }

    override fun onDestroy() {
        junto?.desligar()
        runCatching { medidor.removeEventListener(ouvirBanda) }
        Gravacoes.esquecer(mudouGravacao)
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
                favoritos = fav; perfilCarregado = perfil; recentes = rec
                // Modo simples: abre sempre em Favoritos (sem eles, Recentes), e o ▲▼ zapeia só por ali.
                categoria = if (simples) PlayerAcessivel.entradaSimples(listOf(PlayerLista.FAVORITOS to fav.size, PlayerLista.RECENTES to rec.size), PlayerLista.TODOS) else cat
                 escondidos = esc; liberados = lib; parental = par
                refiltrar()
                if (visiveis.isEmpty()) { mostrarAviso(getString(R.string.player_sem_canais)); return@post }
                // Canais lidos: some o "Carregando os canais…" (13/11/2026, visto na Sala: ficava na tela por minutos em
                // cima da lista quando o canal não chegava a tocar). Se o canal demorar, o aviso dele aparece em 1,5 s.
                esconderAviso()
                // Abre no último canal só se ele ainda passa no filtro (um canal de categoria oculta não abre sem PIN).
                // Canal pedido de fora que não pode tocar: avisa em vez de cair em outro canal.
                val externo = intent.getStringExtra(EXTRA_CANAL)?.ifEmpty { null }
                if (externo != null) { if (!tocarPedido(externo)) mostrarAviso(getString(R.string.player_bloqueado)); return@post }
                val pedido = (Gravacoes.atual?.canalId ?: PlayerArmazem.ultimoCanal(this))?.let { familias.representante(it) }   // gravando: abre no que grava
                sintonizar(visiveis.firstOrNull { it.id == pedido } ?: visiveis.first(), guardarAnterior = false)
                juntoPendente?.let { juntoPendente = null; juntoCanal(it) }
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

    private fun sintonizar(c: Canal, guardarAnterior: Boolean = true, teclaEm: Long = android.os.SystemClock.elapsedRealtime()) {
        // Gravando outro canal: a lista só deixa 1 conexão. Pergunta antes de trocar (docs/gravacao.md, item 4).
        Gravacoes.atual?.let { g -> if (g.canalId != c.id && visivel) { conflitoGravacao(c, g); return } }
        if (guardarAnterior && atual != null && atual?.id != c.id) anterior = atual
        atual = c
        cronCanal.tecla(c.id, teclaEm)
        if (!trocandoPorReserva) reservaTentados.clear()
        contarCanal(c)
        junto?.let { j -> j.tocandoTitulo(JuntoRegras.chave(JuntoRegras.Tipo.CANAL, null, nome = c.nome)); j.mudou(true, 0) }
        arquivo = null; atualizarSelo()   // trocar de canal (ou voltar ao player) é sempre ao vivo
        tentativas = 0
        tentadas.clear(); travadas.zerar()
        gravarUltimo(c)
        tocar(variante(c))
        mostrarInfo()
        recarregarGuiaSeVelho()
        // Zapping: o guia dos vizinhos (▲▼) já vai para o cache, para a faixa aparecer completa no próximo canal.
        val i = visiveis.indexOfFirst { it.id == c.id }
        if (i >= 0) guia.precarregar(listOfNotNull(visiveis.getOrNull(i - 1), visiveis.getOrNull(i + 1)).map { it.guiaId })
    }

    private fun tocar(c: Canal, url: String = c.url) {
        pausa.descartar(); tela.removeCallbacks(pausaEstourou)   // mídia nova: o que estava pausado na memória vai embora
        jaTocou = false; travouEm = 0L; tela.removeCallbacks(travadaLonga)
        if (!visivel) { cronCanal.cancelar(); return }
        // Trava final do controle parental: canal bloqueado não toca sem o PIN, venha de onde vier o pedido.
        if (ControleParental.bloqueado(c, parental) && !filtro.mostrarOcultas) { cronCanal.cancelar(); mostrarAviso(getString(R.string.player_bloqueado)); return }
        if (!ControleParental.bloqueado(c, parental)) lembrarRecente(c)   // bloqueado não vai para os recentes
        // Gravando: o canal que grava é lido da gravação (sem 2ª conexão); qualquer outra coisa esperaria uma conexão livre.
        val g = Gravacoes.atual
        if (g != null && !(url == c.url && g.canalId == c.id)) { cronCanal.cancelar(); player?.stop(); mostrarAviso(getString(R.string.grv_ocupado, g.canalNome)); return }
        deGravacao = g != null
        // Motor único (PlayerEngine, 20/11/2026): o mesmo dos filmes e séries, com a carga da pausa ao vivo.
        val p = player ?: PlayerEngine.build(this, EfeitosSom.fabrica(this)) { setLoadControl(pausa.novaCarga()) }.also { novo ->
            novo.setVideoSurfaceView(video)
            tunel.request(novo, allowed = true)   // túnel com volta automática, como nos filmes
            efeitos.aplicar(novo.audioSessionId, EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
            novo.addListener(ouvinte)
            player = novo
            sessao.ligar(novo)   // o sistema, o Gemini e o MCP passam a ver o canal (só leitura: SessaoMidia)
        }
        // Algumas listas exigem um User-Agent próprio por canal: por isso a fábrica é montada a cada troca.
        val dados = PlayerEngine.data(this, userAgent = c.agenteUsuario, readTimeoutMs = 15_000)
        val item = MediaItem.Builder().setUri(if (deGravacao) FonteGravacao.URI else url).apply {
            // A gravação é sempre MPEG-TS (o gravador emenda os pedaços do HLS).
            if (deGravacao) setMimeType(MimeTypes.VIDEO_MP2T)
            else if (PlayerEngine.isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8)
            // O que a sessão de mídia mostra: o canal e, se o guia souber, o programa de agora.
            val agora = System.currentTimeMillis()
            val prog = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, 1) }?.firstOrNull()?.takeIf { agora >= it.inicio }
            setMediaMetadata(MediaMetadata.Builder().setTitle(c.nome).setArtist(prog?.titulo).setStation(c.nome).build())
        }.build()
        // FontesPlayer: o mesmo dos filmes (formato que o Media3 não reconhece vai para o libavformat).
        p.setMediaSource(FontesPlayer.fabrica(dados).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private val ouvinte = object : Player.Listener {
        override fun onPlaybackStateChanged(estado: Int) {
            player?.let { tunel.onState(it, estado) }
            when (estado) {
                Player.STATE_READY -> {
                    tentativas = 0; if (android.os.SystemClock.elapsedRealtime() >= avisoReservaAte) esconderAviso()
                    tela.removeCallbacks(travadaLonga)
                    jaTocou = true
                    // Por último: se a travada fizer descer de qualidade, o tocar() novo zera o jaTocou de novo.
                    if (travouEm > 0) { val dur = android.os.SystemClock.elapsedRealtime() - travouEm; travouEm = 0L; travou(dur) }
                }
                Player.STATE_BUFFERING -> {
                    if (aviso.visibility != View.VISIBLE) tela.postDelayed(avisoCarregando, 1_500)
                    // Já estava tocando e parou para carregar: é uma travada (conta para baixar a qualidade).
                    if (jaTocou && travouEm == 0L && !pausa.ativa && arquivo == null && !deGravacao) {
                        travouEm = android.os.SystemClock.elapsedRealtime()
                        tela.postDelayed(travadaLonga, QualidadeAuto.TRAVADA_LONGA_MS)
                    }
                }
                Player.STATE_ENDED -> if (arquivo != null) fimDoArquivo()
                else -> {}
            }
        }

        override fun onRenderedFirstFrame() {
            // Placar (elevação 5.1 e 20.4): só o ao vivo conta (o passado do TV de volta é outra conversa com o servidor).
            if (arquivo != null) { cronCanal.cancelar(); return }
            val ms = cronCanal.primeiroQuadro(atual?.id, android.os.SystemClock.elapsedRealtime()) ?: return
            MedidasTv.anotar(this@PlayerActivity, Placar.Chave.CANAL, ms)
            if (!instalacaoConferida) { instalacaoConferida = true; MedidasTv.primeiroCanalTocou(this@PlayerActivity) }
        }

        override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
            tamanhoVideo = v
            // A resolução que chegou de verdade: o "4K" do nome pode ser 1080 (ou o "SD", 720). Fica lembrada.
            val t = tocando
            if (t != null && arquivo == null && !deGravacao && familias.variantes.containsKey(t.id)) QualidadeAuto.nivelPelaAltura(v.height)?.let { n ->
                nivelTocando = n
                val app = applicationContext; val url = t.url
                gravar { QualidadeTv.definirReal(app, url, n) }
            }
            PlayerImagem.aplicar(video, modoImagem, v.width, v.height, v.pixelWidthHeightRatio)
        }

        override fun onPlayerError(erro: PlaybackException) {
            val p = player ?: return
            if (tunel.onError(p, erro.errorCode, erro.errorCodeName)) return   // era o túnel: já seguiu no modo comum
            // Ficou para trás na janela ao vivo (rede engasgou): pula para o "agora" sem incomodar ninguém.
            if (erro.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                if (!pausa.ativa && arquivo == null && travou(QualidadeAuto.TRAVADA_LONGA_MS)) return   // a rede não acompanha: desceu
                if (pausa.ativa) { pausa.descartar(); p.playWhenReady = true; mostrarAvisoBreve(getString(R.string.pausa_voltou)) }   // perdeu o atraso
                p.seekToDefaultPosition(); p.prepare(); return
            }
            val c = atual ?: return
            // Mesmo canal em outra variante: tenta a próxima antes de esperar (o "4K" fora do ar não deixa sem canal).
            if (arquivo == null && !deGravacao) {
                tocando?.let { tentadas += it.url }
                val outra = variante(c, tentadas)
                if (outra.url !in tentadas) { tocar(outra); return }
            }
            if (tentativas < 3) {
                tentativas++
                mostrarAviso(getString(R.string.player_tentando, c.numero))
                val prog = arquivo
                tela.postDelayed({
                    if (atual?.id != c.id || arquivo !== prog) return@postDelayed
                    if (prog != null) tocarArquivo(prog, p.currentPosition) else { tentadas.clear(); tocar(variante(c)) }
                }, 2_000L shl (tentativas - 1))   // 2, 4, 8 s
            } else if (trocarPorReserva(c)) {
                return
            } else {
                cronCanal.cancelar()
                mostrarAviso(getString(R.string.player_sem_sinal, c.numero))
                avisarFalha(c, erro)
                explicarSemSinal(c)
            }
        }
    }

    // ---------------------------------------------------------------- qualidade automática (18/11/2026)

    /**
     * A variante de [c] para tocar agora ([QualidadeAuto]): escolha manual do canal, tela, teto das travadas e banda.
     * Canal sem variantes = ele mesmo. Devolve [c] com o endereço da variante (o id e o nome continuam os do canal).
     */
    private fun variante(c: Canal, pular: Set<String> = emptySet()): Canal {
        val fam = familias.variantes[c.id]
        if (fam == null) { tocando = c; nivelTocando = null; return c }
        val opcoes = fam.filter { it.url !in pular }.ifEmpty { fam }
        val niveis = opcoes.map { QualidadeTv.real(this, it.url) ?: VariantesCanal.nivel(it.nome) }
        val i = QualidadeAuto.escolher(niveis, QualidadeTv.manual(this, c.id), nivelDaTela, QualidadeTv.teto(this), QualidadeTv.banda(this))
        val v = opcoes[i]
        nivelTocando = niveis[i]
        return c.copy(url = v.url, agenteUsuario = v.agenteUsuario.ifEmpty { c.agenteUsuario }).also { tocando = it }
    }

    /** O que está tocando do canal [c] (a variante), ou ele mesmo. */
    private fun emCurso(c: Canal): Canal = tocando?.takeIf { it.id == c.id } ?: c

    private val travadaLonga = Runnable {
        if (travouEm > 0 && player?.playbackState == Player.STATE_BUFFERING) { travouEm = 0L; travou(QualidadeAuto.TRAVADA_LONGA_MS) }
    }

    /**
     * Uma travada de [ms]. Se já for séria ([QualidadeAuto.Travadas]), baixa o teto um nível abaixo do que toca e
     * troca para a variante menor na hora (a imagem já estava parada: a troca passa despercebida). True = trocou.
     */
    private fun travou(ms: Long): Boolean {
        val c = atual ?: return false
        val n = nivelTocando ?: return false
        val fam = familias.variantes[c.id] ?: return false
        if (QualidadeTv.manual(this, c.id) != null) return false   // escolha do usuário: não mexe
        if (!travadas.registrar(android.os.SystemClock.elapsedRealtime(), ms)) return false
        if (fam.none { (QualidadeTv.real(this, it.url) ?: VariantesCanal.nivel(it.nome)) < n }) return false   // já está na menor
        QualidadeTv.definirTeto(this, QualidadeAuto.abaixo(n))
        val antes = tocando?.url
        val nova = variante(c)
        if (nova.url == antes) return false
        android.util.Log.i("TvLauncher.Player", "qualidade: travou, desceu para ${nivelTocando?.name}")
        tocar(nova)
        return true
    }

    /** Opções > Qualidade: Automática ou uma das variantes do canal (fica guardada para este canal). */
    private fun escolherQualidade(c: Canal) {
        val fam = familias.variantes[c.id] ?: return
        val niveis = fam.map { QualidadeTv.real(this, it.url) ?: VariantesCanal.nivel(it.nome) }.distinct()
        val manual = QualidadeTv.manual(this, c.id)
        val nomes = listOf(getString(R.string.qual_auto)) + niveis.map { rotuloNivel(it) }
        val marcada = if (manual == null) 0 else niveis.indexOf(manual) + 1
        Estilo.lista(this, getString(R.string.qual_titulo), nomes, listOf(marcada).filter { it >= 0 }, focoNaMarcada = true) { i ->
            QualidadeTv.definirManual(this, c.id, if (i == 0) null else niveis[i - 1])
            val antes = tocando?.url
            val nova = variante(c)
            if (nova.url != antes && visivel && arquivo == null && Gravacoes.atual == null) tocar(nova)
            mostrarAvisoBreve(getString(R.string.qual_menu, nomes[i]))
        }
    }

    private fun rotuloNivel(n: VariantesCanal.Nivel) = n.rotulo.ifEmpty { getString(R.string.qual_padrao) }

    /**
     * O canal não voltou: a conta explica? (vencida, recusada, todas as telas em uso). Pergunta ao servidor fora da
     * thread da tela e troca o aviso pelo motivo de verdade. Lista M3U comum: nada a perguntar.
     */
    private fun explicarSemSinal(c: Canal) {
        val origem = TvDeVolta.origem(emCurso(c).url) ?: return
        Fundo.rodar {
            val s = PlayerCarga.situacaoDaConta(origem.conta)
            val m = Xtream.motivoSemSinal(s, System.currentTimeMillis() / 1000) ?: return@rodar
            tela.post {
                if (isDestroyed || atual?.id != c.id || player?.playbackState == Player.STATE_READY) return@post
                mostrarAviso(when (m) {
                    Xtream.Motivo.VENCIDA -> getString(R.string.player_conta_vencida, SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date((s?.expiraEm ?: 0) * 1000)))
                    Xtream.Motivo.INATIVA -> getString(R.string.player_conta_recusada)
                    Xtream.Motivo.TELAS_CHEIAS -> getString(R.string.player_telas_cheias, s?.telasEmUso ?: 0, s?.telasMax ?: 0)
                })
            }
        }
    }

    // ---------------------------------------------------------------- guia curto do servidor (reserva do XMLTV)

    private val guiaCurto = HashMap<String, Pair<Long, List<Programa>>>()   // canal -> (quando pediu, programas)

    /** Programas de agora em diante pelo guia; sem guia para o canal, pelo guia curto do servidor (Xtream). */
    private fun programasDe(c: Canal, agora: Long, quantos: Int): List<Programa> {
        val doGuia = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.proximos(it, agora, quantos) }.orEmpty()
        if (doGuia.isNotEmpty() || recarregandoGuia) return doGuia
        val cache = guiaCurto[c.id]
        if (cache == null || agora - cache.first > 30 * 60_000L) pedirGuiaCurto(c, agora)
        return GuiaConsulta.proximos(cache?.second.orEmpty(), agora, quantos)
    }

    private fun pedirGuiaCurto(c: Canal, agora: Long) {
        val origem = TvDeVolta.origem(emCurso(c).url) ?: return
        if (guiaCurto.size > 200) guiaCurto.clear()
        guiaCurto[c.id] = agora to (guiaCurto[c.id]?.second.orEmpty())   // marca já: não pede duas vezes
        Fundo.rodar {
            val lidos = PlayerCarga.guiaCurto(origem) ?: return@rodar
            val programas = lidos.map { Programa("", it.inicio, it.fim, it.titulo, it.descricao) }
            tela.post {
                if (isDestroyed) return@post
                guiaCurto[c.id] = agora to programas
                if (programas.isNotEmpty() && atual?.id == c.id) atualizarInfo()
            }
        }
    }

    /**
     * Elevação 2.3: o canal não voltou nem na reserva. Uma frase, a causa e um botão (lista vencida -> conta; canal
     * caído -> ver outro), em vez de só "sem sinal". Uma caixa por canal (zapear por canais caídos não empilha caixas).
     */
    private var falhaAvisadaEm: String? = null
    private fun avisarFalha(c: Canal, erro: PlaybackException) {
        if (falhaAvisadaEm == c.id || Gravacoes.atual != null) return
        falhaAvisadaEm = c.id
        var x: Throwable? = erro
        var codigo: Int? = null
        while (x != null && codigo == null) { codigo = (x as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode; x = x.cause }
        Fundo.rodar("falha-canal") {
            val agora = System.currentTimeMillis()
            val v = runCatching { PlayerArmazem.vencimentos(this) }.getOrDefault(emptyList())
            val tipo = Falhas.doPlayer(codigo, v.isNotEmpty() && v.all { it.second * 1000 < agora })
            tela.post {
                if (isDestroyed || atual?.id != c.id) return@post
                FalhasTv.mostrar(this, tipo) { abrirLista() }
            }
        }
    }

    /**
     * Elevação 5.2: caiu 3 vezes, toca a próxima variante do mesmo canal (HD/SD/Alt, ou o de outra conta) que ainda
     * não falhou e avisa em uma linha. Nunca no passado (TV de volta) nem gravando (a conexão é da gravação).
     */
    private fun trocarPorReserva(c: Canal): Boolean {
        if (arquivo != null || Gravacoes.atual != null) return false
        reservaTentados += c.id; reservaTentados += c.url
        val alt = CanalReserva.proxima(visiveis, c, reservaTentados) { !ControleParental.bloqueado(it, parental) } ?: return false
        trocandoPorReserva = true
        try { sintonizar(alt, guardarAnterior = false) } finally { trocandoPorReserva = false }
        avisoReservaAte = android.os.SystemClock.elapsedRealtime() + 5_000
        mostrarAvisoBreve(getString(R.string.elv_reserva, c.nome, alt.nome), 5_000)
        return true
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
            // Elevação 5.14: troca ao SOLTAR; segurando, a faixa passa pelos canais sem trocar o vídeo (onKeyUp).
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zapeio.apertou(+1, event.repeatCount, event.eventTime)?.let { previa(it) }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapeio.apertou(-1, event.repeatCount, event.eventTime)?.let { previa(it) }; return true }
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
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (grade.aberta || lista.aberta || arquivo != null) { zapeio.cancelar(); return super.onKeyUp(keyCode, event) }
                val segurou = zapeio.segurando
                val p = zapeio.soltou()
                // O cronômetro da troca (5.1) começa na tecla DESCENDO, não ao soltar: o número do placar é o que a pessoa sente.
                val desde = android.os.SystemClock.elapsedRealtime() - (android.os.SystemClock.uptimeMillis() - event.downTime)
                if (p != null) zapear(p, if (segurou) android.os.SystemClock.elapsedRealtime() else desde)
                else if (segurou) mostrarInfo()   // voltou ao mesmo canal segurando: a faixa volta a ser a dele
                return true
            }
        }
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
    private fun zapear(passo: Int, teclaEm: Long = android.os.SystemClock.elapsedRealtime()) {
        val onde = PlayerLista.listaDeZapping(visiveis, categoria, favoritos, recentes, atual)
        PlayerDados.vizinho(onde, atual, passo)?.let { sintonizar(it, teclaEm = teclaEm) }
    }

    /** Elevação 5.14: a faixa mostra o canal [passo] à frente, sem trocar o vídeo. */
    private val zapeio = Zapeio()
    private fun previa(passo: Int) {
        if (lista.aberta || grade.aberta) return
        val onde = PlayerLista.listaDeZapping(visiveis, categoria, favoritos, recentes, atual)
        val c = PlayerDados.vizinho(onde, atual, passo) ?: return
        atualizarInfo(previa = c)
        info.visibility = View.VISIBLE
        tela.removeCallbacks(esconderInfo)
        tela.postDelayed(esconderInfo, ESCONDER_INFO_MS)
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
        // B9 (20/11/2026): a mesma peça dos filmes e séries (PlayerBar). Aqui entram número e logo do canal no começo da
        // linha e, à direita, "● AO VIVO" (vermelho, como na HOME) e "faltam X min · hora".
        val b = PlayerBar.montar(this, getString(R.string.player_teclas))
        info = b.raiz.apply { visibility = View.GONE }
        val linha = b.linha
        infoNumero = TextView(this).apply {
            textSize = 22f; typeface = Fontes.nunito(600); setTextColor(Estilo.FUNDO); gravity = Gravity.CENTER
            background = Estilo.fundo(context, ambar, Estilo.RAIO_PEQUENO)
            // Largura conforme o número: canal 1655 quebrava em "165 / 5" na caixa fixa de 48 dp (visto na Sala, 14/11/2026).
            maxLines = 1; minWidth = dp(48); setPaddingRelative(dp(8), 0, dp(8), 0)
        }
        linha.addView(infoNumero, 0, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(14) })
        infoLogo = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        linha.addView(infoLogo, 1, LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginEnd = dp(14) })
        infoTitulo = b.titulo
        infoLinha = b.detalhe
        vivo = b.estado.apply { text = "● " + getString(R.string.player_ao_vivo); setTextColor(Estilo.ERRO); visibility = View.VISIBLE }
        (vivo.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
        infoHora = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { textSize = Estilo.Leitura.ITEM_DETALHE }
        linha.addView(infoHora)
        infoProgresso = b.progresso
        infoTeclas = b.teclas
        return info
    }

    /** Mostra o selo fixo só no ao vivo do canal que está gravando (no vídeo do passado ou noutro canal, some). */
    private fun atualizarSelo() {
        val g = Gravacoes.atual?.takeIf { arquivo == null && it.canalId == atual?.id && !it.parar.get() }
        seloGravando.text = g?.let { "● " + getString(R.string.grv_selo, Gravacoes.hora(it.fim)) }.orEmpty()
        seloGravando.visibility = if (g != null) View.VISIBLE else View.GONE
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
    /**
     * Ideia 92 ("Sua TV em 2026"): soma os minutos do canal que estava tocando quando troca de canal ou sai do player.
     * Canal bloqueado ou de categoria oculta não conta (mesma regra do "Continuar" da HOME).
     */
    private var vendoCanal: Canal? = null
    private var vendoDesde = 0L
    private fun contarCanal(novo: Canal?) {
        val agora = android.os.SystemClock.elapsedRealtime()
        val antes = vendoCanal
        if (antes != null && antes.id != novo?.id) {
            val min = (agora - vendoDesde) / 60_000
            if (min > 0 && PlayerFiltro.podeIrParaHome(antes, filtro.ocultas, parental)) {
                val app = applicationContext; val nome = antes.nome
                gravar { RetrospectivaAno.somarCanal(app, nome, min) }
            }
        }
        if (novo == null || antes?.id != novo.id) { vendoCanal = novo; vendoDesde = agora }
    }

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

    private fun atualizarInfo(previa: Canal? = null) {
        atualizarSelo()
        val c = previa ?: atual ?: return
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
        // Pausado, a linha dizia "OK lista de canais", mas o OK continua (visto na Sala, 17/11/2026).
        // No passado (TV de volta) as teclas já vão na linha de baixo (tvv_teclas): a linha fixa some.
        infoTeclas.visibility = if (arquivo != null) View.GONE else View.VISIBLE
        infoTeclas.setText(if (pausa.pausado) R.string.player_teclas_pausa else R.string.player_teclas)
        if (previa == null) arquivo?.let { a -> infoDoArquivo(c, a, hora); return }
        vivo.text = "● " + getString(R.string.player_ao_vivo)
        vivo.setTextColor(Estilo.ERRO)
        if (previa != null) { vivo.text = getString(R.string.elv_zap_solte); vivo.setTextColor(Estilo.ACENTO); infoTeclas.visibility = View.GONE }
        Gravacoes.atual?.takeIf { it.canalId == c.id }?.let { vivo.text = "● " + getString(R.string.grv_selo, Gravacoes.hora(it.fim)) }
        if (previa == null && pausa.ativa) {   // pausado ou atrasado: âmbar, como o passado do TV de volta
            vivo.text = if (pausa.pausado) getString(R.string.pausa_selo)
                else PausaAoVivo.textoAtraso(pausa.atrasoMs, getString(R.string.pausa_atrasado_min), getString(R.string.pausa_atrasado_seg))
            vivo.setTextColor(Estilo.ATENCAO)
        }
        if (previa == null) gravarUltimo(c)   // o guia pode ter acabado de carregar, ou o programa ter mudado
        val agora = System.currentTimeMillis()
        val prog = programasDe(c, agora, 2)
        val atualProg = prog.firstOrNull()?.takeIf { agora >= it.inicio }
        infoNumero.text = c.numero.toString()
        // Logo do canal já na tecla (elevação 5.1): do cache em memória na hora; baixado, entra quando chegar.
        if (infoLogo.tag != c.logo) PlayerLogos.carregar(this, c.logo, infoLogo)
        infoLogo.visibility = if (c.logo.isEmpty()) View.GONE else View.VISIBLE
        infoTitulo.text = atualProg?.titulo ?: c.nome
        // Sem programa agora o título já é o nome do canal: não repetir embaixo ("TV Globo HD / TV Globo HD").
        val partes = if (atualProg != null) mutableListOf(c.nome) else mutableListOf()
        atualProg?.let { partes += "${hora.format(Date(it.inicio))} – ${hora.format(Date(it.fim))}" }
        prog.getOrNull(if (atualProg != null) 1 else 0)?.let { partes += getString(R.string.player_a_seguir, it.titulo) }
        // Canal com variantes: a qualidade que está tocando ("Full HD"; com "manual" quando o usuário escolheu).
        if (familias.variantes.containsKey(c.id)) nivelTocando?.let { n ->
            partes += rotuloNivel(n) + if (QualidadeTv.manual(this, c.id) != null) "" else " · " + getString(R.string.qual_auto_curto)
        }
        infoLinha.text = partes.joinToString("  ·  ")
        infoLinha.visibility = if (partes.isEmpty()) View.GONE else View.VISIBLE
        // Quanto falta do programa de agora, como no card TV da HOME.
        infoHora.text = atualProg?.let { BarraTempo.faltam(this, minutosTexto(((it.fim - agora + 59_999) / 60_000).toInt())) + "  ·  " }
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
        if (origem != null && Gravacoes.atual == null) itens += getString(R.string.tvv_abrir) to { abrirTvDeVolta(c, origem) }
        if (arquivo == null) opcoesGravacao(c, itens)
        // Mesma regra do menu dos filmes (PlayerMenu, 20/11/2026): ações fecham; o que abre outra lista ou liga/desliga
        // mostra à direita o valor de agora, e o ✓ some daqui (antes marcava as chaves ligadas, igual a uma escolha).
        PlayerMenu.abrir(this, c.nome) {
            val menu = ArrayList<PlayerMenu.Item>()
            itens.forEach { (nome, acao) -> menu += PlayerMenu.acao(nome, acao = acao) }
            if (arquivo == null && familias.variantes.containsKey(c.id)) {
                val manual = QualidadeTv.manual(this, c.id)
                menu += PlayerMenu.abre(getString(R.string.pm_qualidade), manual?.let { rotuloNivel(it) } ?: getString(R.string.qual_auto)) { escolherQualidade(c) }
            }
            menu += PlayerMenu.abre(getString(R.string.pm_proporcao), PlayerImagem.rotulo(this, modoImagem)) { escolherImagem() }
            // Para quem ouve pior (14/11/2026). Só aparece se o aparelho tem o efeito.
            if (EfeitosSom.disponivel()) {
                menu += PlayerMenu.chave(this, getString(R.string.acess_volume), EfeitosSom.volumeIgual(this)) { alternarSom(c, PlayerAcessivel.VOLUME_IGUAL) }
                menu += PlayerMenu.chave(this, getString(R.string.acess_voz), EfeitosSom.vozClara(this)) { alternarSom(c, PlayerAcessivel.VOZ_CLARA) }
            }
            menu += PlayerMenu.abre(getString(R.string.fone_menu), getString(if (SomNoFone.ligado) R.string.pm_ligado else R.string.pm_desligado)) { SomNoFone.janela(this) }
            if (EntreTvs.ligado(this)) {
                menu += PlayerMenu.acao(getString(R.string.junto_menu)) { junto?.menu(c.nome, JuntoTvsProvider.tvs(this)) }
                menu += PlayerMenu.acao(getString(R.string.cont_menu)) { junto?.enviarPara(c.nome, JuntoTvsProvider.tvs(this), 0L, null) }
            }
            menu
        }
    }

    // ---------------------------------------------------------------- sessão junto (degrau 4)

    private var junto: JuntoNoPlayer? = null
    private var juntoPendente: String? = null   // canal pedido pela outra TV antes da lista carregar

    /** A outra TV está no canal [nome] (normalizado): procura pelo nome aqui (o id muda de lista para lista). */
    private fun juntoCanal(nome: String) {
        if (visiveis.isEmpty()) { juntoPendente = nome; return }
        val c = visiveis.firstOrNull { JuntoRegras.normalizarCanal(it.nome) == nome }
            // A outra TV pode estar na variante "Globo HD": aqui ela virou "Globo" (sem o marcador de qualidade).
            ?: VariantesCanal.chave(nome).takeIf { it.isNotEmpty() }?.let { k -> visiveis.firstOrNull { VariantesCanal.chave(it.nome) == k } }
        if (c == null) { mostrarAviso(getString(R.string.junto_sem_canal)); return }
        if (c.id != atual?.id) { grade.fechar(); lista.fechar(); sintonizar(c) }
    }

    /**
     * Liga/desliga volume parecido ou voz mais clara. Se mudar entre "som direto para a TV" e "som decodificado aqui"
     * (EfeitosSom.fabrica), o player é refeito e o canal volta a tocar; senão, só o efeito é trocado.
     */
    private fun alternarSom(c: Canal, chave: String) {
        val antes = PlayerAcessivel.precisaEfeito(EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
        val ligou = EfeitosSom.alternar(this, chave)
        val depois = PlayerAcessivel.precisaEfeito(EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
        mostrarAvisoBreve(getString(when (chave) {
            PlayerAcessivel.VOLUME_IGUAL -> if (ligou) R.string.acess_volume_ligado else R.string.acess_volume_desligado
            else -> if (ligou) R.string.acess_voz_ligado else R.string.acess_voz_desligado
        }))
        val p = player ?: return
        if (antes != depois && arquivo == null) {
            efeitos.soltar(); pausa.descartar(); sessao.soltar(); tunel.release(); p.release(); player = null
            tocar(emCurso(c))
        } else efeitos.aplicar(p.audioSessionId, EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
    }

    // ---------------------------------------------------------------- gravação (GravacaoServico, 14/11/2026)

    private var deGravacao = false   // o vídeo de agora vem da gravação (gravando://), não do canal direto
    private var depoisDeParar: Canal? = null   // "Parar gravação e ver X": sintoniza X quando a gravação soltar a conexão

    /** A gravação começou, esticou ou acabou: troca a fonte do vídeo (gravação ⇄ canal direto) e o selo. */
    private val mudouGravacao: () -> Unit = mudou@{
        if (isDestroyed) return@mudou
        depoisDeParar?.let { alvo -> if (Gravacoes.atual == null) { depoisDeParar = null; sintonizar(alvo) }; return@mudou }
        val c = atual ?: return@mudou
        if (visivel && arquivo == null && Gravacoes.gravando(c.id) != deGravacao) tocar(emCurso(c))
        atualizarInfo()
    }

    private fun opcoesGravacao(c: Canal, itens: MutableList<Pair<String, () -> Unit>>) {
        val g = Gravacoes.atual
        val agora = System.currentTimeMillis()
        if (g?.canalId == c.id) {
            itens += getString(R.string.grv_parar_ate, Gravacoes.hora(g.fim)) to { Gravacoes.parar(this) }
            itens += getString(R.string.grv_mais_1h) to { Gravacoes.gravarAgora(this, emCurso(c), g.programa, g.fim + 60 * 60_000L); mostrarAvisoBreve(getString(R.string.grv_ate, g.canalNome, Gravacoes.hora(g.fim + 60 * 60_000L))) }
            return
        }
        if (g != null) return
        val p = c.guiaId.takeIf { it.isNotEmpty() }?.let { guia.agora(it, agora) }
        if (p != null && p.fim > agora) itens += getString(R.string.grv_programa, p.titulo, Gravacoes.hora(p.fim)) to {
            gravar(c, p.titulo, p.fim + GravacaoRegras.FOLGA_DEPOIS_MS)
        }
        itens += getString(R.string.grv_1h) to { gravar(c, "", agora + 60 * 60_000L) }
        itens += getString(R.string.grv_2h) to { gravar(c, "", agora + 2 * 60 * 60_000L) }
    }

    private fun gravar(c: Canal, programa: String, fim: Long) {
        // 20/11/2026: com mais de um lugar (pendrives, pastas do Na rede), pergunta onde.
        GravacaoDestino.perguntar(this) { onde ->
            if (onde == null) {
                // Diz o motivo de verdade: pendrive em NTFS ou cheio, em vez de só "sem lugar" (15/11/2026)
                mostrarAvisoBreve(GravacaoDestino.problemaPendrive(this) ?: getString(R.string.grv_sem_destino), 10_000)
            } else if (!isDestroyed) gravarEm(c, programa, fim, onde.id)
        }
    }

    private fun gravarEm(c: Canal, programa: String, fim: Long, destino: String) {
        pausa.descartar()
        player?.stop()   // solta a conexão: daqui em diante quem baixa o canal é o gravador, e o player lê dele
        Gravacoes.gravarAgora(this, emCurso(c), programa, fim, destino)   // grava a variante que está tocando
        mostrarAviso(getString(R.string.grv_comecando))
        // Não começou (pendrive sem permissão, NAS fora, canal fora do ar): volta ao canal direto e avisa.
        tela.postDelayed({
            if (!isDestroyed && visivel && atual?.id == c.id && arquivo == null && Gravacoes.atual == null) {
                tocar(emCurso(c)); mostrarAvisoBreve(getString(R.string.grv_nao_comecou))
            }
        }, 20_000)
    }

    private fun conflitoGravacao(c: Canal, g: Gravacoes.Sessao) {
        val gc = canais.firstOrNull { it.id == g.canalId }
        val itens = ArrayList<Pair<String, () -> Unit>>()
        if (gc != null) itens += getString(R.string.grv_ver, g.canalNome) to { if (atual?.id != gc.id) sintonizar(gc) }
        itens += getString(R.string.grv_parar_e_ver, c.nome) to {
            depoisDeParar = c
            mostrarAviso(getString(R.string.player_carregando_canal))
            Gravacoes.parar(this)
        }
        Estilo.lista(this, getString(R.string.grv_conflito, g.canalNome, Gravacoes.hora(g.fim)), itens.map { it.first }) { i -> itens[i].second() }
    }

    private fun escolherImagem() = PlayerImagem.escolher(this, modoImagem, voltar = { opcoes() }) { m ->
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
        arquivo = p; atualizarSelo()
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
    private fun voltarAoVivo() { atual?.let { tocar(emCurso(it)); mostrarInfo() } }

    /** Pausado até o buffer chegar ao teto: para de baixar e solta a memória já; ao continuar, volta ao vivo e avisa. */
    private val pausaEstourou = Runnable { if (pausa.pausado) player?.stop() }

    private fun infoDoArquivo(c: Canal, a: TvDeVolta.Programa, hora: SimpleDateFormat) {
        vivo.text = getString(R.string.tvv_selo)
        vivo.setTextColor(Estilo.ATENCAO)   // passado: âmbar, para não confundir com o vermelho do ao vivo
        infoNumero.text = c.numero.toString()
        infoTitulo.text = a.titulo
        infoLinha.text = listOf(c.nome, "${diaCurto(a.inicio)} ${hora.format(Date(a.inicio))} – ${hora.format(Date(a.fim))}",
            getString(R.string.tvv_teclas)).joinToString("  ·  ")
        infoLinha.visibility = View.VISIBLE
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
        familias = VariantesCanal.agrupar(PlayerFiltro.aplicar(canais, filtro))
        visiveis = familias.canais
        // Favoritos e recentes de uma variante que virou "o mesmo canal" passam para ele (senão sumiriam da lista).
        if (familias.apelido.isNotEmpty()) {
            val fav = VariantesCanal.migrarIds(favoritos, familias).toSet()
            if (fav != favoritos) { favoritos = fav; gravar { PlayerArmazem.definirFavoritos(this, fav) } }
            val rec = VariantesCanal.migrarIds(recentes, familias)
            if (rec != recentes) { recentes = rec; gravar { PlayerArmazem.definirRecentes(this, rec) } }
            atual?.let { a -> if (a.id in familias.apelido) atual = visiveis.firstOrNull { it.id == familias.representante(a.id) } ?: a }
        }
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

    // Um só "esconder depois" por vez: o de um aviso breve anterior (ex.: "favorito") apagava um aviso novo e importante
    // ("canal bloqueado", "sem sinal") antes da hora. Cada aviso novo cancela o esconder pendente.
    private val esconderAvisoDepois = Runnable { esconderAviso() }
    private fun mostrarAviso(t: String) { tela.removeCallbacks(esconderAvisoDepois); aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun esconderAviso() { tela.removeCallbacks(avisoCarregando); tela.removeCallbacks(esconderAvisoDepois); aviso.visibility = View.GONE }
    private fun mostrarAvisoBreve(t: String, ms: Long = 2_500) { mostrarAviso(t); tela.postDelayed(esconderAvisoDepois, ms) }

    // ---------------------------------------------------------------- aparência

    /**
     * O vidro do sistema (mesmo fio claro e raio da HOME), mas com a tinta mais escura: aqui ele fica em cima de VÍDEO, que
     * pode ser uma cena clara (neve, estúdio branco); com os 55% da HOME o texto branco ficaria difícil de ler.
     */
    private fun vidro(raio: Int) = Estilo.fundo(this, Estilo.alfa(Estilo.FUNDO, 0xD9), raio, Estilo.VIDRO_BORDA)

    /** "19 min" ou "1 h 05 min". */
    private fun minutosTexto(m: Int) = BarraTempo.duracao(m * 60_000L - 59_999L)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import br.paulo.tvlauncher.alarm.Cronometros
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.home.tocandoAgora
import br.paulo.tvlauncher.input.AvancoSegurado
import br.paulo.tvlauncher.input.EfeitosSom
import br.paulo.tvlauncher.input.SpeechTap
import br.paulo.tvlauncher.listen.SomNoFone
import br.paulo.tvlauncher.network.EntreTvs
import br.paulo.tvlauncher.observer.MedidasTv
import br.paulo.tvlauncher.observer.Placar
import br.paulo.tvlauncher.player.AberturaProntaTv
import br.paulo.tvlauncher.player.AberturaSerie
import br.paulo.tvlauncher.player.AddonsProtocolo
import br.paulo.tvlauncher.player.AssAjustes
import br.paulo.tvlauncher.player.Capitulos
import br.paulo.tvlauncher.player.Conforto
import br.paulo.tvlauncher.player.ConfortoRegras
import br.paulo.tvlauncher.player.CortinaVideo
import br.paulo.tvlauncher.player.EpisodeSegments
import br.paulo.tvlauncher.player.EstiloLegenda
import br.paulo.tvlauncher.player.Filme
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.FontesMidia
import br.paulo.tvlauncher.player.FontesPlayer
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.InfoTecnica
import br.paulo.tvlauncher.player.JuntoRegras
import br.paulo.tvlauncher.player.LegendaAoLado
import br.paulo.tvlauncher.player.LegendaBaixar
import br.paulo.tvlauncher.player.LegendaExterna
import br.paulo.tvlauncher.player.LegendaImagem
import br.paulo.tvlauncher.player.LegendaTraduzida
import br.paulo.tvlauncher.player.MemoriaSerie
import br.paulo.tvlauncher.player.MemoriaSerieTv
import br.paulo.tvlauncher.player.MixDialogo
import br.paulo.tvlauncher.player.PlayerAcessivel
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerBar
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerEngine
import br.paulo.tvlauncher.player.PlayerImagem
import br.paulo.tvlauncher.player.PlayerMenu
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.RenderizadoresPlayer
import br.paulo.tvlauncher.player.SegundaLegenda
import br.paulo.tvlauncher.player.Series
import br.paulo.tvlauncher.player.ServicosSync
import br.paulo.tvlauncher.player.SessaoMidia
import br.paulo.tvlauncher.player.SincronizarFilmes
import br.paulo.tvlauncher.player.SondaMidia
import br.paulo.tvlauncher.player.SubtitleSync
import br.paulo.tvlauncher.player.TaxaQuadros
import br.paulo.tvlauncher.player.TunnelGuard
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.acharAoLado
import br.paulo.tvlauncher.player.alternarInfo
import br.paulo.tvlauncher.player.alternarSom
import br.paulo.tvlauncher.player.apagarAbertura
import br.paulo.tvlauncher.player.aplicarMemoriaSerie
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.player.avisoBreve
import br.paulo.tvlauncher.player.buscaLegenda
import br.paulo.tvlauncher.player.buscarLegenda
import br.paulo.tvlauncher.player.contarFora
import br.paulo.tvlauncher.player.continuarDeFora
import br.paulo.tvlauncher.player.escolherAtraso
import br.paulo.tvlauncher.player.escolherImagem
import br.paulo.tvlauncher.player.escolherTamanhoLegenda
import br.paulo.tvlauncher.player.escolherVelocidade
import br.paulo.tvlauncher.player.esconderPilula
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.player.lembrarSerie
import br.paulo.tvlauncher.player.marcarAbertura
import br.paulo.tvlauncher.player.mostrarProximo
import br.paulo.tvlauncher.player.nomeEstiloLegenda
import br.paulo.tvlauncher.player.pintarLegenda
import br.paulo.tvlauncher.player.pintarProximo
import br.paulo.tvlauncher.player.prepararProximo
import br.paulo.tvlauncher.player.recarregarComLegenda
import br.paulo.tvlauncher.player.rotuloAtraso
import br.paulo.tvlauncher.player.rotuloTamanho
import br.paulo.tvlauncher.player.salvar
import br.paulo.tvlauncher.player.talvezLegendaAuto
import br.paulo.tvlauncher.player.terminar
import br.paulo.tvlauncher.player.vigiarAbertura
import java.util.Locale

/**
 * Player universal, filmes e séries (06/11/2026): toca um vídeo sob demanda (filme ou episódio), com pausa, avançar,
 * voltar, escolha de áudio/legenda e "continuar de onde parou".
 *
 * Roda no processo ":player", como a TV ao vivo. Recebe só os ids (conta, filme, extensão), e o endereço com a senha é
 * montado aqui dentro: senha não viaja em Intent.
 *
 * Controle:
 *   OK / ⏯          pausa e continua             ◀ ▶ (ou ⏪ ⏩)  volta 10 s / avança 30 s (segurado acelera)
 *   ▼                áudio, legenda, velocidade e proporção da imagem      VOLTAR  sai (a posição fica salva)
 *   OK com "Pular abertura" na tela: pula a abertura marcada na série (ideia 24, AberturaSerie)
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VodActivity : Activity() {
    companion object {
        /** Códigos ISO 639-2 que não dizem idioma: indefinido, vários, sem fala, outros. */
        private val SEM_IDIOMA = setOf("und", "mul", "zxx", "mis")
        internal const val EXTRA_FONTE = "fonte"
        private const val EXTRA_ID = "id"
        private const val EXTRA_EXT = "ext"
        internal const val EXTRA_TITULO = "titulo"
        private const val EXTRA_TIPO = "tipo"
        private const val EXTRA_URL = "url"
        private const val EXTRA_CHAVE_POS = "chave_pos"     // addons: a posição guardada por esta chave, não pelo endereço
        private const val EXTRA_CABECALHOS = "cabecalhos"   // addons: User-Agent/Referer que o addon pediu
        // De quem é a escolha de áudio (Idioma): o filme, ou a SÉRIE inteira (o próximo episódio segue a mesma escolha).
        private const val EXTRA_IDIOMA = "idioma"
        // O que procurar no OpenSubtitles (LegendaExterna): nome limpo, ano, e temporada/episódio nas séries.
        internal const val EXTRA_LEG_NOME = "leg_nome"
        internal const val EXTRA_LEG_ANO = "leg_ano"
        internal const val EXTRA_LEG_TEMP = "leg_temp"
        internal const val EXTRA_LEG_EP = "leg_ep"
        internal const val EXTRA_LEG_VERSAO = "leg_versao"
        internal const val EXTRA_TMDB = "tmdb"
        internal const val LEGENDA_AUTO = "legenda_auto"   // prefs "player" (ideia 89): baixar a legenda sozinho
        internal const val LEGENDA_TRADUZIR = "legenda_traduzir"   // ideia 90: sem legenda no idioma da TV, traduzir a inglesa
        internal val ATRASOS = longArrayOf(-3000, -2000, -1500, -1000, -500, 0, 500, 1000, 1500, 2000, 3000)

        // A mesma obra na outra versão (dublada/legendada), que na lista de IPTV é OUTRO arquivo (18/11/2026).
        private const val EXTRA_ALT_FONTE = "alt_fonte"
        private const val EXTRA_ALT_ID = "alt_id"
        private const val EXTRA_ALT_EXT = "alt_ext"
        private const val EXTRA_ALT_TITULO = "alt_titulo"
        private const val EXTRA_ALT_VERSAO = "alt_versao"

        /**
         * "Áudios que não mudam" (Paulo, 18/11/2026): visto na Sala, os filmes da lista têm UMA faixa de áudio só ("Duna":
         * AAC estéreo; "Sem Deixar Vestígios LEG": inglês). O dublado e o legendado são arquivos diferentes. Com isto o ▼
         * do player oferece a outra versão e troca na hora, no mesmo ponto do filme.
         */
        fun comOutraVersao(i: Intent, fonteId: String, id: String, extensao: String, titulo: String, versao: Idioma.Versao): Intent =
            i.putExtra(EXTRA_ALT_FONTE, fonteId).putExtra(EXTRA_ALT_ID, id).putExtra(EXTRA_ALT_EXT, extensao)
                .putExtra(EXTRA_ALT_TITULO, titulo).putExtra(EXTRA_ALT_VERSAO, versao.name)

        /** Põe no Intent o que a busca de legenda precisa (sem nada, a busca usa o título da tela). */
        fun comBusca(i: Intent, b: LegendaExterna.Busca?): Intent = if (b == null) i else i.putExtra(EXTRA_LEG_NOME, b.nome)
            .putExtra(EXTRA_LEG_ANO, b.ano ?: 0).putExtra(EXTRA_LEG_TEMP, b.temporada ?: 0).putExtra(EXTRA_LEG_EP, b.episodio ?: 0).putExtra(EXTRA_TMDB, b.tmdb ?: 0)
            .putExtra(EXTRA_LEG_VERSAO, b.versao.name)
        const val TIPO_FILME = "filme"
        const val TIPO_EPISODIO = "episodio"
        const val RESULTADO_TERMINOU = "terminou"
        /** Alguma tecla foi apertada durante o vídeo (ideia 19: conta dos episódios seguidos sem ninguém mexer). */
        const val RESULTADO_TECLA = "tecla"
        /** "Desligar quando este acabar" estava marcado: a tela apagou e a série não segue para o próximo. */
        const val RESULTADO_DESLIGOU = "desligou"
        /** O "Próximo episódio" dos créditos já foi escolhido (OK ou contagem no player): a série começa o próximo sem contar de novo. */
        const val RESULTADO_PROXIMO_JA = "proximo_ja"
        /**
         * Segundos da contagem do "Próximo episódio" nos créditos (Conforto.contagem, lido pela tela de séries no processo
         * principal); ausente = não há próximo episódio, 0 = só oferece, sem começar sozinho.
         */
        const val EXTRA_PROXIMO_CONTAGEM = "proximo_contagem"
        /** Proposta C, item 11: o episódio seguinte ("id|temporada|número", mesma fonte), para preparar antes do fim. */
        const val EXTRA_PROXIMO_EP = "proximo_ep"
        /** Cortina de abertura (zero tela preta, 20/11/2026): imagem e linha de cima enquanto o primeiro quadro não chega. */
        const val EXTRA_CORTINA_IMAGEM = "cortina_imagem"
        const val EXTRA_CORTINA_LINHA = "cortina_linha"
        const val EXTRA_CORTINA_TITULO = "cortina_titulo"

        /** Põe a cortina de abertura no pedido (imagem pode faltar: aí só o título sobre o fundo). */
        fun comCortina(i: Intent, imagem: String?, linha: String, titulo: String): Intent =
            i.putExtra(EXTRA_CORTINA_IMAGEM, imagem).putExtra(EXTRA_CORTINA_LINHA, linha).putExtra(EXTRA_CORTINA_TITULO, titulo)
        private const val SALVAR_MS = 15_000L
        private const val ESCONDER_MS = 4_000L

        fun abrir(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean = false,
                  busca: LegendaExterna.Busca? = null, mexer: (Intent) -> Intent = { it }) {
            ctx.startActivity(mexer(comBusca(intent(ctx, tipo, fonteId, id, extensao, titulo, doComeco), busca)))
        }

        /** Mídia da rede (07/11/2026): toca um endereço da rede (http do DLNA, smb://, jellyfin://, plex://, webdav://; ver FontesMidia). */
        fun abrirUrl(ctx: Context, url: String, titulo: String) {
            ctx.startActivity(Intent(ctx, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo))
        }

        /**
         * Fonte de um addon (16/11/2026, AddonsActivity): endereço http(s) direto, a posição guardada pelo id do vídeo no
         * addon ([chavePos], ex.: "addon:tt123:1:2"; o endereço muda a cada pedido) e os cabeçalhos que o addon pediu
         * (só os de [AddonsProtocolo.cabecalhoPermitido]). Com resultado: o fim do episódio decide o próximo.
         */
        fun abrirAddon(act: Activity, codigo: Int, url: String, titulo: String, chavePos: String, episodio: Boolean,
                       cabecalhos: Map<String, String>) {
            val b = android.os.Bundle()
            cabecalhos.filterKeys { AddonsProtocolo.cabecalhoPermitido(it) }.forEach { (k, v) -> b.putString(k, v) }
            act.startActivityForResult(Intent(act, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, if (episodio) TIPO_EPISODIO else TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo)
                .putExtra(EXTRA_CHAVE_POS, chavePos).putExtra(EXTRA_CABECALHOS, b), codigo)
        }

        /** "Continuar de onde parou?" vindo de outra TV da casa (degrau 4): o mesmo endereço, a partir de [posMs]. */
        fun abrirUrlEm(ctx: Context, url: String, titulo: String, posMs: Long) {
            ctx.startActivity(Intent(ctx, VodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TIPO, TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo).putExtra("inicio_ms", posMs))
        }

        /** Endereço da rede com resultado (ideia 29: a pasta do "Na rede" emenda o próximo arquivo). */
        fun abrirUrlComResultado(act: Activity, codigo: Int, url: String, titulo: String) {
            act.startActivityForResult(Intent(act, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo), codigo)
        }

        /** Para quem quer saber se o vídeo foi até o fim (séries: próximo episódio). */
        fun abrirComResultado(act: Activity, codigo: Int, tipo: String, fonteId: String, id: String, extensao: String, titulo: String,
                              doComeco: Boolean = false, chaveIdioma: String = "", busca: LegendaExterna.Busca? = null,
                              extra: (Intent) -> Intent = { it }) {
            act.startActivityForResult(extra(comBusca(intent(act, tipo, fonteId, id, extensao, titulo, doComeco).putExtra(EXTRA_IDIOMA, chaveIdioma), busca)), codigo)
        }

        private fun intent(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean) =
            Intent(ctx, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, tipo).putExtra(EXTRA_FONTE, fonteId).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_EXT, extensao).putExtra(EXTRA_TITULO, titulo).putExtra("do_comeco", doComeco)
                // Aberto de fora de uma tela (pedido do catálogo de ações, 14/11/2026): o Android exige tarefa nova.
                .apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        /**
         * Trailer no app do YouTube (11/11/2026; 18/11/2026: TrailerTv). Aceita qualquer forma de link do YouTube
         * (TrailerLink.codigo). Antes de abrir, avisa o processo principal (VigiaTrailer): VOLTAR ou o fim do trailer
         * fecham o YouTube e devolvem esta ficha. Sem o app do YouTube para TV, o Android escolhe quem abre o link.
         */
        fun abrirTrailer(ctx: Context, link: String) {
            val codigo = TrailerLink.codigo(link) ?: return
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TrailerLink.link(codigo))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val noApp = Intent(i).setPackage(TrailerLink.YOUTUBE_TV)
            val temApp = runCatching { ctx.packageManager.getPackageInfo(TrailerLink.YOUTUBE_TV, 0) }.isSuccess
            if (temApp) TrailerReceiver.avisar(ctx, (ctx as? Activity)?.javaClass?.name)
            runCatching { ctx.startActivity(if (temApp) noApp else i) }.recoverCatching { ctx.startActivity(i) }
                .onFailure { android.widget.Toast.makeText(ctx, R.string.trailer_sem_app, android.widget.Toast.LENGTH_SHORT).show() }
        }

        /** Chave da posição salva (a mesma que Filme.chave / Episodio.chave usam). */
        fun chave(tipo: String, fonteId: String, id: String) = if (tipo == TIPO_EPISODIO) "$fonteId:e$id" else "$fonteId:f$id"
    }

    internal val tela = Handler(Looper.getMainLooper())
    internal var player: ExoPlayer? = null
    internal var url: String? = null
    internal var chave = ""
    internal var retomarEm = 0L
    private var visivel = false
    internal var legendaDecidida = false
    private var quadrosDecididos = false   // ideia 86: o modo da tela (24 Hz para filme) já foi pedido para este vídeo
    // Preferência de áudio deste título (Idioma), lida do disco fora da thread da tela antes de o vídeo começar.
    @Volatile internal var prefIdioma = Idioma.Pref.DUBLADO
    /** 16.2: audiodescrição e legenda para surdos (lidas com a preferência de idioma). */
    @Volatile internal var prefAcesso = Idioma.Prefs()
    /** Segundo idioma de áudio/legenda (Conforto, 16/11/2026), já sem o idioma da TV; null = nenhum. */
    @Volatile private var segundoIdioma: String? = null
    internal var chaveIdioma = ""
    // Ideia 25: idioma do sistema da TV ("pt", "en", "es"): "dublado" e a legenda automática seguem ele.
    internal val idiomaTv by lazy { IdiomaPlayer.idiomaDaTv() }
    // Legenda baixada da internet para este vídeo (LegendaBaixar), se houver, e o atraso aplicado nela.
    internal var legendaArquivo: java.io.File? = null
    internal var legendaTraduzida = false   // ideia 90: a legenda baixada é tradução feita na TV (rótulo da faixa)
    internal val pararTraducao = java.util.concurrent.atomic.AtomicBoolean(false)
    // Legendas na mesma pasta do vídeo ("Filme.srt", "Filme.pt-BR.ass"): endereço + o que o nome diz (LegendaAoLado).
    internal var legendasAoLado: List<Pair<String, LegendaAoLado.Achada>> = emptyList()
    internal var atrasoLegenda = 0L
    // Legenda que se acerta sozinha (SubtitleSync + SpeechTap, 20/11/2026): para quando a pessoa escolhe o atraso à mão,
    // depois de 2 acertos ou quando a conta diz que a legenda já está no lugar.
    internal var atrasoManual = false
    internal var acertosAuto = 0
    private var acertando = false
    private var tiquesLegenda = 0
    private val vigiaLegenda = object : Runnable {
        override fun run() { vigiarLegenda(); tela.postDelayed(this, 5_000) }
    }
    internal lateinit var legenda: TextView
    private lateinit var legendaImg: LegendaImagem.Vista   // PGS, VobSub, DVB e ASS (libass): legenda que chega como imagem
    // Jellyfin, Emby ou Plex: a quem contar onde parou (é assim que o "Continuar assistindo" deles anda).
    internal var relato: FontesMidia.Relato? = null
    internal var avisouInicio = false
    // Cabeçalhos extras do pedido (WebDAV: a senha vai no cabeçalho, nunca no endereço).
    private var cabecalhos: Map<String, String> = emptyMap()
    private lateinit var capaAudio: TextView
    private var cortina: View? = null
    // Ideia 19: tecla apertada neste vídeo, e "desligar quando este acabar" (vale só para este vídeo, nada fica salvo).
    internal var apertouTecla = false
    internal var desligarAoFim = false
    // Ideia 20: novas tentativas depois de erro de rede (zera quando o vídeo volta a tocar).
    internal var tentativas = 0
    internal var tamLegendaSp = FimDoVideo.LEGENDA_PADRAO   // ideia 21
    internal val efeitos = EfeitosSom()                      // volume parecido e voz mais clara (PlayerAcessivel)
    internal val assAjustes = AssAjustes()                   // legenda ASS (libass): área do vídeo, tamanho, fontes do MKV
    // Player sem NextLib (15/11/2026): segunda legenda (ideia 6), capítulos (5), miniatura no avanço (3), quadro técnico (8).
    private val segunda = SegundaLegenda()
    private var seletor: SegundaLegenda.Seletor? = null
    internal lateinit var legenda2: TextView
    private var podeSondar = false         // a sonda abre o arquivo de novo: só fora do IPTV (lá o limite é 1 conexão)
    private var sonda: SondaMidia? = null
    private var sondaUrl: String? = null
    private var capitulos: List<Capitulos.Capitulo> = emptyList()
    internal var aberturaDoArquivo = false  // a abertura veio de um capítulo do arquivo (não da marca da série)
    private lateinit var miniatura: ImageView
    private var tamMiniatura = Capitulos.tamanhoMiniatura(0, 0)
    private var miniaturaPedida = Long.MIN_VALUE
    internal lateinit var infoTec: TextView
    private var decodVideo: String? = null
    private var decodAudio: String? = null
    private var perdidos = 0
    // Medidas do player (proposta C, item 14): primeiro quadro, travadas e quadros perdidos (Cronometros.SessaoVod).
    private val medida = Cronometros.SessaoVod()
    private var perdidosMedidos = 0
    // Ideia 24: abertura marcada na série (AberturaSerie) e a pílula "Pular abertura · OK".
    internal var serie = false              // episódio aberto pela tela de séries (com a chave da série)
    /** Elevação 6.4: o que a pessoa escolheu para esta série (legenda, velocidade, atraso da legenda). */
    internal var memSerie: MemoriaSerie.Escolha? = null
    internal var abertura: AberturaSerie.Marca? = null
    internal var avisouPulo = false         // a pílula já apareceu neste trecho (some depois de 8 s e não volta até sair dele)
    internal val pilulaPularPronta get() = ::pilulaPular.isInitialized
    internal lateinit var pilulaPular: TextView
    // Proposta C, etapa 1 (20/11/2026): resumo e créditos (EpisodeSegments). A mesma pílula serve aos três avisos.
    internal var resumo: AberturaSerie.Marca? = null
    private var creditosBanco: Long? = null            // início dos créditos segundo o IntroDB
    internal var pularPara = -1L                        // OK com a pílula de pular: para onde vai
    internal var avisadoTrecho = -1L                    // fim do trecho cuja pílula já apareceu (não repete a cada 8 s)
    internal var pilulaProximo = false                  // a pílula na tela é "Próximo episódio"
    private var proximoDispensado = false              // a pessoa mexeu durante os créditos: deixa os créditos correrem
    internal var proximoResta = -1                      // segundos da contagem na pílula (-1 = sem contagem)
    internal var proximoContagem = -1                   // do intent: -1 = sem próximo episódio
    private var repetindoAte = -1L                     // "O que ele disse?": até onde a legenda fica ligada
    private var paramsAntesRepetir: androidx.media3.common.TrackSelectionParameters? = null

    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    internal val coral = Estilo.ACENTO   // era 0xFF5A4E, um vermelho-rosado fora da marca (B9, 20/11/2026)

    private lateinit var video: SurfaceView
    /**
     * Vídeo girado (teste do Paulo, 20/11/2026): vídeo de celular gravado em pé vem 1920x1080 com "girar 90°" no arquivo.
     * Na SurfaceView do Chromecast o quadro vai para a camada de vídeo do chip (Amlogic), que não gira: o som tocava e a
     * imagem não aparecia. Só nesse caso o vídeo passa para uma TextureView (a GPU desenha o quadro já girado). Os outros
     * vídeos ficam na SurfaceView, que é mais leve e a única que mostra HDR.
     */
    private var textura: android.view.TextureView? = null
    /** A vista que mostra o vídeo agora (a TextureView quando o vídeo é girado). */
    internal fun areaVideo(): View = textura ?: video
    private lateinit var barra: LinearLayout
    private lateinit var titulo: TextView
    internal val sessao by lazy { SessaoMidia(this, "vod", soControle = true) }
    private lateinit var tempo: TextView
    private lateinit var estadoBarra: TextView
    private lateinit var progresso: ProgressBar
    internal lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        medida.abriu(android.os.SystemClock.elapsedRealtime())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: TIPO_FILME
        val fonte = intent.getStringExtra(EXTRA_FONTE).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        // Endereço da rede (ver FontesMidia: nenhum leva senha nem token). A posição salva usa o próprio endereço como chave.
        val direto = intent.getStringExtra(EXTRA_URL)?.takeIf { FontesMidia.aceito(it) }
        val chavePos = intent.getStringExtra(EXTRA_CHAVE_POS)?.takeIf { it.startsWith("addon:") && direto != null }
        chave = chavePos ?: if (direto != null) "rede:${direto.hashCode()}" else chave(tipo, fonte, id)
        val cabAddon = intent.getBundleExtra(EXTRA_CABECALHOS)?.let { b ->
            b.keySet().filter { AddonsProtocolo.cabecalhoPermitido(it) }.mapNotNull { k -> b.getString(k)?.let { k to it } }.toMap()
        }.orEmpty()
        chaveIdioma = intent.getStringExtra(EXTRA_IDIOMA)?.takeIf { it.isNotEmpty() } ?: chave
        serie = tipo == TIPO_EPISODIO && !intent.getStringExtra(EXTRA_IDIOMA).isNullOrEmpty()
        proximoContagem = if (serie && intent.hasExtra(EXTRA_PROXIMO_CONTAGEM)) intent.getIntExtra(EXTRA_PROXIMO_CONTAGEM, -1) else -1
        ligarJunto(direto)
        podeSondar = direto != null

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        modoImagem = PlayerImagem.modo(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        // Zero tela preta (estudo A, 20/11/2026): até o primeiro quadro, a cena do episódio (ou só o título) em vez do
        // preto com "Carregando". Fica por baixo da barra e dos avisos; some com um esmaecer curto quando o vídeo aparece.
        cortina = CortinaVideo.montar(this, intent.getStringExtra(EXTRA_CORTINA_IMAGEM), intent.getStringExtra(EXTRA_CORTINA_LINHA),
            intent.getStringExtra(EXTRA_CORTINA_TITULO) ?: intent.getStringExtra(EXTRA_TITULO).orEmpty())
        raiz.addView(cortina, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(montarBarra(intent.getStringExtra(EXTRA_TITULO).orEmpty()),
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(26)) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPaddingRelative(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.vod_carregando)
            if (cortina != null) visibility = View.GONE   // a cortina já diz que está abrindo
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // Zero tela preta (estudo A, 20/11/2026): até o primeiro quadro, a cena do episódio (ou só o título) em vez do
        // preto com "Carregando". Some com um esmaecer curto quando o vídeo aparece.
        // Legenda em imagem (14/11/2026): desenhada dentro da área do vídeo, por baixo da barra e dos avisos.
        legendaImg = LegendaImagem.Vista(this) { areaVideo().let { v -> android.graphics.Rect(v.left, v.top, v.right, v.bottom) } }
        raiz.addView(legendaImg, 1, FrameLayout.LayoutParams(-1, -1))
        video.addOnLayoutChangeListener(mudouArea)
        // Legenda (11/11/2026): sem o media3-ui ninguém desenhava as falas, e a legenda escolhida no ▼ não aparecia.
        // Texto branco com contorno escuro, embaixo e centrado; acima da barra quando ela aparece.
        legenda = TextView(this).apply {
            textSize = FimDoVideo.LEGENDA_PADRAO.toFloat(); setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPaddingRelative(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x8C000000.toInt()) }
        }
        raiz.addView(legenda, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(96), 0, dp(96), dp(56)) })
        // Música não tem imagem: sem isto a tela ficava toda preta. Aparece só quando o arquivo não tem faixa de vídeo.
        capaAudio = TextView(this).apply {
            textSize = 30f; setTextColor(papel); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = "♪\n" + intent.getStringExtra(EXTRA_TITULO).orEmpty()
        }
        raiz.addView(capaAudio, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(64), 0, dp(64), dp(80)) })
        // Ideia 24: pílula "Pular abertura · OK", embaixo à direita, acima da barra.
        pilulaPular = TextView(this).apply {
            textSize = 18f; setTextColor(papel); visibility = View.GONE; text = getString(R.string.abertura_pular)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPaddingRelative(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xD9121528.toInt()); setStroke(dp(2), papel) }
        }
        raiz.addView(pilulaPular, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(170)) })
        // Ideia 6: a segunda legenda fica em cima (a primeira continua embaixo), com o mesmo estilo.
        legenda2 = TextView(this).apply {
            textSize = FimDoVideo.LEGENDA_PADRAO.toFloat(); setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPaddingRelative(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x8C000000.toInt()) }
        }
        raiz.addView(legenda2, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(96), dp(40), dp(96), 0) })
        // Ideia 3: miniatura acima da barra enquanto ◀ ▶ estão segurados (anda junto com o ponto da barra).
        miniatura = ImageView(this).apply {
            visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_XY
            background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), coral) }
            setPaddingRelative(dp(2), dp(2), dp(2), dp(2))
        }
        raiz.addView(miniatura, FrameLayout.LayoutParams(dp(tamMiniatura.first), dp(tamMiniatura.second), Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(32), 0, 0, dp(176)) })
        // Ideia 8: quadro técnico, em cima à esquerda.
        infoTec = TextView(this).apply {
            textSize = Estilo.Leitura.DICA; setTextColor(papel); visibility = View.GONE; typeface = Typeface.MONOSPACE
            setPaddingRelative(dp(14), dp(10), dp(14), dp(10)); background = vidro(12)
        }
        raiz.addView(infoTec, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(32), dp(28), 0, 0) })
        setContentView(raiz)

        // O endereço (com a senha) e a posição salva saem do disco: fora da thread da tela.
        Fundo.rodar {
            // O endereço com o token (ou o cabeçalho com a senha) só nasce aqui, na hora de tocar.
            val tocavel = direto?.let { FontesMidia.resolver(this, it) }
            // Lista M3U (ideia 26): o endereço vem do cache da lista, pelo id da entrada; com o User-Agent que ela pedir.
            val m3u = if (direto == null && PlayerArmazem.fontes(this).none { it.id == fonte && it.xtream != null })
                PlayerCarga.urlM3u(this, fonte, id) else null
            val endereco = tocavel?.url ?: m3u?.first ?: if (direto != null) null else PlayerArmazem.fontes(this).firstOrNull { it.id == fonte }?.xtream?.let { conta ->
                if (tipo == TIPO_EPISODIO) Series.urlEpisodio(conta, id, ext) else Vod.urlFilme(conta, Filme(id, fonte, "", extensao = ext))
            }
            prefAcesso = IdiomaPlayer.prefs(this)
            prefIdioma = prefAcesso.para(chaveIdioma)
            segundoIdioma = ConfortoRegras.segundoUtil(Conforto.segundoIdioma(this), idiomaTv)
            // Ideia 90: tradução que ficou pela metade (o player fechou no meio) é refeita; a metade não fica valendo.
            if (LegendaTraduzida.pelaMetade(this, chave)) LegendaBaixar.apagar(this, chave)
            val leg = LegendaBaixar.guardada(this, chave)
            val trad = leg != null && LegendaTraduzida.ehTraducao(this, chave)
            val aoLado = direto?.let { runCatching { acharAoLado(it) }.getOrNull() }.orEmpty()
            var atraso = getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE).getLong("atraso:$chave", 0L)
            val mem = if (serie) MemoriaSerieTv.ler(this, chaveIdioma) else null
            // Elevação 6.4: episódio novo da série, com legenda baixada e sem atraso próprio: vale o atraso da série.
            var legFinal = leg
            if (leg != null && atraso == 0L) mem?.atrasoLegendaMs?.takeIf { it != 0L }?.let { ms ->
                LegendaBaixar.ajustar(this, chave, ms)?.let { f ->
                    legFinal = f; atraso = ms
                    getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE).edit().putLong("atraso:$chave", ms).apply()
                }
            }
            // Ideia 21: tamanho da legenda escolhido no ▼ (vale para todos os vídeos).
            val tamLeg = FimDoVideo.tamanhoLegenda(getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE).getInt("legenda_sp", FimDoVideo.LEGENDA_PADRAO))
            val pos = intent.getLongExtra("inicio_ms", -1L).takeIf { it >= 0 }
                ?: if (intent.getBooleanExtra("do_comeco", false)) 0L else Vod.inicio(PlayerArmazem.posicoes(this)[chave], episodio = tipo == TIPO_EPISODIO)
            val ab = if (serie) AberturaSerie.deTexto(getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE)
                .getString(AberturaSerie.chave(chaveIdioma), null)) else null
            tela.post {
                if (isDestroyed) return@post
                if (endereco == null) { mostrarAviso(getString(R.string.vod_sem_conta)); return@post }
                SpeechTap.zerar()
                url = endereco; retomarEm = pos; legendaArquivo = legFinal; legendaTraduzida = trad; atrasoLegenda = atraso; legendasAoLado = aoLado
                memSerie = mem
                tamLegendaSp = tamLeg; legenda.textSize = tamLeg.toFloat(); legenda2.textSize = tamLeg.toFloat(); assAjustes.escala(FimDoVideo.escalaAss(tamLeg))
                pintarLegenda()
                abertura = ab
                relato = tocavel?.relato
                cabecalhos = tocavel?.cabecalhos?.takeIf { it.isNotEmpty() } ?: cabAddon.takeIf { it.isNotEmpty() } ?: m3u?.second?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty()
                tocar()
                buscarAberturaPronta(ab)
            }
        }
    }

    /**
     * Elevação 6.3: a abertura deste episódio marcada pela comunidade (IntroDB). Chega depois do vídeo começar (o
     * pedido nunca atrasa o play) e vale antes da marcação manual da série, que é uma só para todos os episódios e
     * fica de reserva. Se a pessoa marcou à mão enquanto o pedido ia, vale o que ela marcou.
     */
    private fun buscarAberturaPronta(manual: AberturaSerie.Marca?) {
        if (!serie) return
        val b = runCatching { buscaLegenda() }.getOrNull() ?: return
        val tmdb = b.tmdb ?: return
        if (!b.eEpisodio) return
        Fundo.rodar("abertura-pronta") {
            val t = runCatching { AberturaProntaTv.buscar(this, tmdb, b.temporada!!, b.episodio!!) }.getOrNull() ?: return@rodar
            tela.post {
                if (isDestroyed) return@post
                t.resumo?.let { resumo = it }
                t.creditos?.let { creditosBanco = it }
                // a pessoa marcou a abertura enquanto isso: vale a dela
                if (t.abertura != null && (abertura === manual || aberturaDoArquivo)) { abertura = t.abertura; aberturaDoArquivo = false }
                vigiarAbertura()
            }
        }
    }

    override fun onStart() { super.onStart(); visivel = true; if (url != null && player == null) tocar() }

    override fun onStop() {
        TaxaQuadros.soltar(this); quadrosDecididos = false   // a HOME e o resto voltam ao modo de sempre
        avanco.cancelar()
        tela.removeCallbacks(vigiaAbertura); esconderPilula()
        tela.removeCallbacks(tiqueInfo); esconderMiniatura()
        // Os relógios da tela param junto (o tocar() da volta liga de novo): sem isso, com a barra à mostra, o :player
        // acordava a cada 1 s e o salvar a cada 15 s, com a tela atrás de outro app.
        tela.removeCallbacks(salvarDepois); tela.removeCallbacks(tique); tela.removeCallbacks(esconderBarra)
        tela.removeCallbacks(vigiaLegenda); SpeechTap.ligado = false
        super.onStop()
        visivel = false
        salvar()
        medirTrecho()
        player?.let { avisar(FontesMidia.Momento.PAROU, it.currentPosition) }
        avisouInicio = false
        efeitos.soltar()
        sessao.soltar(); tunel.release()
        player?.let { retomarEm = it.currentPosition; it.release() }
        player = null
    }

    override fun onDestroy() {
        junto?.desligar()
        pararTraducao.set(true); tela.removeCallbacksAndMessages(null)
        sonda?.fechar(); sonda = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- vídeo

    internal fun tocar() {
        val u = url ?: return
        if (!visivel) return
        // Motor único (PlayerEngine): o mesmo da TV ao vivo. Fontes (FontesPlayer): ASS crua para o libass; PGS/VobSub/DVB
        // com o Media3; AVI/WMV/RMVB/FLV antigo pelo libavformat.
        val dados = PlayerEngine.data(this, headers = cabecalhos)
        val fontes = FontesPlayer.fabrica(dados)
        val sel = SegundaLegenda.Seletor(this, segunda).also { seletor = it }
        val saida2 = androidx.media3.exoplayer.text.TextOutput { cg -> mostrarSegunda(cg) }
        val p = PlayerEngine.build(this, EfeitosSom.fabrica(this, assAjustes, segunda to saida2)) {
            setMediaSourceFactory(fontes).setTrackSelector(sel).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000)
        }
        p.addAnalyticsListener(analise)
        if (podeSondar && sondaUrl != u) sondar(u, dados)
        textura?.let { p.setVideoTextureView(it) } ?: p.setVideoSurfaceView(video)
        efeitos.aplicar(p.audioSessionId, EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
        // Dublado: já pede o áudio no idioma da TV (ideia 25; "pt" na TV em português). Original: o áudio e a legenda são
        // escolhidos quando as faixas chegam (onTracksChanged + IdiomaPlayer.aplicar). Nos dois casos, a legenda no idioma
        // da TV só liga se o áudio não for desse idioma.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*(if (prefIdioma == Idioma.Pref.DUBLADO) listOfNotNull(idiomaTv, segundoIdioma) else emptyList()).toTypedArray())
            .build()
        // Modo túnel com volta automática (TunnelGuard): não vale com a TextureView (efeitos de imagem).
        tunel.request(p, allowed = textura == null)
        legendaDecidida = false   // player novo (voltou de outra tela): as faixas são escolhidas de novo
        p.addListener(ouvinte)
        SpeechTap.proximaPosicao(retomarEm)
        p.setMediaItem(item(u), retomarEm)
        p.prepare()
        p.playWhenReady = true
        tela.removeCallbacks(vigiaLegenda); tela.postDelayed(vigiaLegenda, 5_000)
        player = p
        sessao.ligar(p)   // o sistema, o Gemini e o MCP passam a ver o filme (SessaoMidia)
        tela.removeCallbacks(salvarDepois); tela.postDelayed(salvarDepois, SALVAR_MS)
        mostrarBarra()
        vigiarAbertura()
        if (EfeitosSom.ligado(this, InfoTecnica.PREF)) { tela.removeCallbacks(tiqueInfo); tela.post(tiqueInfo) }
    }

    /** Refaz o player no mesmo ponto (troca de diálogo alto ou de atraso do áudio: a fábrica de renderizadores muda). */
    private fun refazerPlayer() {
        val p = player ?: return
        efeitos.soltar()
        sessao.soltar(); retomarEm = p.currentPosition; p.release(); player = null
        tocar()
    }

    // ---------------------------------------------------------------- ideias 3, 5 e 6 (player sem NextLib)

    /** Abre o arquivo à parte (SondaMidia): capítulos, fontes anexadas do MKV e, depois, as miniaturas. */
    private fun sondar(u: String, dados: androidx.media3.datasource.DataSource.Factory) {
        sonda?.fechar()
        sondaUrl = u; capitulos = emptyList()
        val s = SondaMidia(android.net.Uri.parse(u), dados)
        sonda = s
        s.abrir { caps, fontes, vl, va ->
            fontes.forEach { (nome, bytes) -> assAjustes.fonte(nome, bytes) }
            tela.post {
                if (isDestroyed || sonda !== s) return@post
                capitulos = caps
                tamMiniatura = Capitulos.tamanhoMiniatura(vl, va)
                miniatura.layoutParams = (miniatura.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(tamMiniatura.first); height = dp(tamMiniatura.second)
                }
                // Ideia 5: sem abertura marcada na série, vale o capítulo "Opening"/"Abertura" do próprio arquivo.
                if (abertura == null) Capitulos.abertura(caps)?.let { c ->
                    val m = AberturaSerie.Marca(c.inicioMs, c.fimMs)
                    if (AberturaSerie.valida(m)) { abertura = m; aberturaDoArquivo = true }
                }
                if (resumo == null) resumo = EpisodeSegments.resumoDoCapitulo(caps)
                vigiarAbertura()
            }
        }
    }

    /** Pede a miniatura do ponto escolhido com ◀ ▶ segurados (no máximo uma a cada 2 s de vídeo). */
    private fun pedirMiniatura(alvo: Long, dur: Long) {
        val s = sonda ?: return
        if (alvo < 0 || dur <= 0) { miniatura.visibility = View.GONE; return }
        val larguraBarra = progresso.width.takeIf { it > 0 } ?: return
        val x = dp(20) + (larguraBarra.toLong() * alvo / dur).toInt() - dp(tamMiniatura.first) / 2
        miniatura.translationX = x.coerceIn(0, (barra.width - dp(tamMiniatura.first)).coerceAtLeast(0)).toFloat()
        if (kotlin.math.abs(alvo - miniaturaPedida) < 2_000) return
        miniaturaPedida = alvo
        val (l, a) = tamMiniatura
        s.miniatura(alvo, l, a) { _, bmp ->
            tela.post {
                if (isDestroyed || avanco.alvo < 0) return@post
                miniatura.setImageBitmap(bmp); miniatura.visibility = View.VISIBLE
            }
        }
    }

    private fun esconderMiniatura() {
        miniaturaPedida = Long.MIN_VALUE
        if (::miniatura.isInitialized) { miniatura.visibility = View.GONE; miniatura.setImageDrawable(null) }
    }

    /** ⏭ ⏮ do controle: próximo capítulo / começo deste (ou o anterior, se acabou de começar). */
    private fun pularCapitulo(proximo: Boolean): Boolean {
        val p = player ?: return false
        if (capitulos.size < 2) return false
        val ir = if (proximo) Capitulos.proximo(capitulos, p.currentPosition) else Capitulos.anterior(capitulos, p.currentPosition)
        if (ir < 0) return true
        p.seekTo(ir); mostrarBarra()
        avisoBreve(rotuloCapitulo(capitulos.indexOfLast { it.inicioMs <= ir }.coerceAtLeast(0)))
        return true
    }

    private fun rotuloCapitulo(i: Int): String {
        val c = capitulos[i]
        return "${relogio(c.inicioMs)} · " + (c.titulo ?: getString(R.string.vod_capitulo_n, i + 1))
    }

    private fun escolherCapitulo(voltar: (() -> Unit)? = null) {
        val p = player ?: return
        val atual = capitulos.indexOfLast { it.inicioMs <= p.currentPosition }
        Estilo.lista(this, getString(R.string.vod_capitulos_titulo), capitulos.indices.map { rotuloCapitulo(it) },
            listOfNotNull(atual.takeIf { it >= 0 }), aoVoltar = voltar) { i -> p.seekTo(capitulos[i].inicioMs); mostrarBarra() }
    }

    private fun mostrarSegunda(cg: CueGroup) {
        val t = cg.cues.mapNotNull { it.text }.joinToString("\n")
        legenda2.text = t
        legenda2.visibility = if (t.isBlank() || segunda.id == null) View.GONE else View.VISIBLE
    }

    /** Segunda legenda: as faixas de texto (menos a que já está embaixo e as ASS) ou "Desligada". */
    /** Nome da segunda legenda ligada, ou "Desligada". */
    private fun nomeSegunda(): String {
        val id = segunda.id ?: return getString(R.string.vod_segunda_desligada)
        val p = player ?: return getString(R.string.vod_segunda_desligada)
        for (g in p.currentTracks.groups) for (i in 0 until g.length) g.getTrackFormat(i).takeIf { it.id == id }?.let { return nomeFaixa(it) }
        return getString(R.string.vod_segunda_desligada)
    }

    private fun escolherSegunda(voltar: (() -> Unit)? = null) {
        val p = player ?: return
        val faixas = ArrayList<Pair<String, String>>()   // id, rótulo
        for (grupo in p.currentTracks.groups) {
            for (i in 0 until grupo.length) {
                val f = grupo.getTrackFormat(i)
                val id = f.id ?: continue
                if (!SegundaLegenda.aceita(f) || (grupo.type == C.TRACK_TYPE_TEXT && grupo.isTrackSelected(i))) continue
                faixas += id to nomeFaixa(f)
            }
        }
        val rotulos = listOf(getString(R.string.vod_segunda_desligada)) + faixas.map { it.second }
        val marcada = faixas.indexOfFirst { it.first == segunda.id }.let { if (it < 0) 0 else it + 1 }
        Estilo.lista(this, getString(R.string.vod_segunda_titulo), rotulos, listOf(marcada), aoVoltar = voltar) { i ->
            segunda.id = if (i == 0) null else faixas[i - 1].first
            if (segunda.id == null) legenda2.visibility = View.GONE
            seletor?.atualizar()
        }
    }

    private fun nomeFaixa(f: androidx.media3.common.Format): String {
        // "und"/"mul"/"zxx" (idioma não marcado no arquivo) apareciam crus: "Áudio: und" na Sala (16/11/2026).
        val idioma = f.language?.takeUnless { it.isBlank() || it.lowercase() in SEM_IDIOMA }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } }
            // Áudio sem idioma: o formato diz mais que "Faixa" ("Áudio: Faixa" na Sala, 17/11/2026), ex. "AAC estéreo".
            ?: f.takeIf { it.channelCount > 0 }?.let {
                InfoTecnica.formato(it.sampleMimeType, it.codecs) + " " + InfoTecnica.canais(it.channelCount, rotulosInfo)
            }
            ?: getString(R.string.vod_faixa)
        return listOfNotNull(idioma, f.label?.takeIf { it.isNotBlank() && it != idioma }).joinToString(" · ")
    }

    // ---------------------------------------------------------------- ideias 2 e 4: diálogo e atraso do áudio

    private fun alternarDialogo() {
        val ligou = EfeitosSom.alternar(this, MixDialogo.PREF)
        avisoBreve(getString(if (ligou) R.string.vod_dialogo_ligado else R.string.vod_dialogo_desligado))
        refazerPlayer()
    }

    private fun rotuloAtrasoAudio(ms: Int) = if (ms == 0) getString(R.string.vod_atraso_audio_zero)
        else getString(if (ms > 0) R.string.vod_atraso_audio_mais else R.string.vod_atraso_audio_menos, kotlin.math.abs(ms))

    private fun escolherAtrasoAudio(voltar: (() -> Unit)? = null) {
        val v = RenderizadoresPlayer.ATRASOS
        val atual = EfeitosSom.atrasoMs(this)
        val titulo = getString(R.string.vod_atraso_audio_titulo, EfeitosSom.caixaBluetooth(this) ?: getString(R.string.vod_atraso_audio_tv))
        Estilo.lista(this, titulo, v.map { rotuloAtrasoAudio(it) }, listOf(v.indexOf(atual).coerceAtLeast(0)), aoVoltar = voltar) { i ->
            if (v[i] == atual) return@lista
            EfeitosSom.gravarAtraso(this, v[i])
            refazerPlayer()
        }
    }

    private val mudouArea = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->   // trocou Ajustar/Zoom
        assAjustes.area(v.width, v.height)
        legendaImg.invalidate()
    }

    /** Ver [textura]: põe uma TextureView no lugar da SurfaceView e passa o player para ela. */
    /** O túnel desligou com o player parado ou preso: recarrega no mesmo ponto, no modo comum. */
    private val tunel = TunnelGuard(this, tela, "TvLauncher.Vod") { p ->
        val pos = p.currentPosition
        SpeechTap.proximaPosicao(pos); url?.let { p.setMediaItem(item(it), pos); p.prepare() }
    }

    private fun usarTextura() {
        if (textura != null) return
        player?.let { if (tunel.on) tunel.turnOff(it, reloadNow = it.playerError != null) }   // túnel só desenha numa SurfaceView
        val raiz = video.parent as? FrameLayout ?: return
        val t = android.view.TextureView(this)
        raiz.addView(t, raiz.indexOfChild(video), FrameLayout.LayoutParams(video.layoutParams as FrameLayout.LayoutParams))
        t.addOnLayoutChangeListener(mudouArea)
        textura = t
        player?.setVideoTextureView(t)
        video.visibility = View.GONE
        tamanhoVideo?.let { v -> PlayerImagem.aplicar(t, modoImagem, v.width, v.height, v.pixelWidthHeightRatio) }
    }

    // ---------------------------------------------------------------- ideia 8: quadro técnico

    internal val analise = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, nome: String, inicio: Long, duracao: Long) { decodVideo = nome }
        override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, nome: String, inicio: Long, duracao: Long) { decodAudio = nome }
        override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, n: Int, ms: Long) { perdidos += n }
        override fun onVideoInputFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format,
                                               r: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            if (f.rotationDegrees != 0) usarTextura()
        }
    }

    internal val tiqueInfo: Runnable = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || !EfeitosSom.ligado(this@VodActivity, InfoTecnica.PREF)) { infoTec.visibility = View.GONE; return }
            val v = p.videoFormat; val a = p.audioFormat
            val e = InfoTecnica.Estado(
                v?.sampleMimeType, v?.codecs, v?.width ?: 0, v?.height ?: 0, v?.frameRate ?: 0f, decodVideo, perdidos,
                a?.sampleMimeType, a?.codecs, a?.channelCount ?: 0, a?.sampleRate ?: 0, decodAudio,
                listOfNotNull(v?.bitrate, a?.bitrate).filter { it > 0 }.sum(),
                (p.bufferedPosition - p.currentPosition).coerceAtLeast(0), FfNativo.versaoFfmpeg,
                EfeitosSom.atrasoMs(this@VodActivity), EfeitosSom.dialogoAlto(this@VodActivity))
            infoTec.text = InfoTecnica.linhas(e, rotulosInfo).joinToString("\n")
            infoTec.visibility = View.VISIBLE
            // A segunda legenda (em cima) desce para não ficar embaixo do quadro (visto na Sala).
            legenda2.translationY = (infoTec.height + dp(12)).toFloat()
            tela.postDelayed(this, 1_000)
        }
    }

    internal val rotulosInfo by lazy {
        InfoTecnica.Rotulos(getString(R.string.info_video), getString(R.string.info_audio), getString(R.string.info_perdidos),
            getString(R.string.info_taxa), getString(R.string.info_buffer), getString(R.string.info_atraso),
            getString(R.string.info_dialogo), getString(R.string.info_chip), "FFmpeg", getString(R.string.info_qps),
            getString(R.string.info_mono), getString(R.string.info_estereo))
    }





    internal val ouvinte = object : Player.Listener {
        override fun onPositionDiscontinuity(velha: Player.PositionInfo, nova: Player.PositionInfo, motivo: Int) {
            if (motivo == Player.DISCONTINUITY_REASON_SEEK) { medida.pulou(); SpeechTap.proximaPosicao(nova.positionMs); player?.let { junto?.mudou(it.isPlaying || it.playWhenReady, nova.positionMs) } }
        }
        override fun onRenderedFirstFrame() {
            medida.primeiroQuadro(android.os.SystemClock.elapsedRealtime())?.let { MedidasTv.anotar(this@VodActivity, Placar.Chave.VOD, it) }
            tirarCortina()
        }
        override fun onCues(cues: CueGroup) {
            legendaImg.mostrar(cues.cues)
            val t = cues.cues.filter { it.bitmap == null }.mapNotNull { it.text }.joinToString("\n")
            legenda.text = t
            legenda.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        }
        override fun onPlaybackStateChanged(estado: Int) {
            player?.let { tunel.onState(it, estado) }
            when (estado) {
                Player.STATE_READY -> {
                    esconderAviso()
                    medida.pronto()
                    tentativas = 0
                    if (!avisouInicio) {
                        avisouInicio = true; avisar(FontesMidia.Momento.COMECOU, player?.currentPosition ?: 0)
                        continuarDeFora()
                    }
                }
                Player.STATE_BUFFERING -> {
                    medida.carregando(player?.playWhenReady == true); if (cortina == null) mostrarAviso(getString(R.string.vod_carregando))
                }
                Player.STATE_ENDED -> terminar(proximoJa = false)
                else -> {}
            }
            atualizarBarra()
        }
        override fun onIsPlayingChanged(tocando: Boolean) {
            medida.tocando(tocando, android.os.SystemClock.elapsedRealtime())
            Gatilhos.avisarDoPlayer(this@VodActivity, tocando); atualizarBarra(); if (!tocando) mostrarBarra(fixa = true) else mostrarBarra()
            player?.let { junto?.mudou(tocando, it.currentPosition) }
            // Trakt/Simkl: começou ou voltou a tocar = "vendo agora"; pausou no meio = pausa (o fim é tratado no avisar).
            val p = player ?: return
            if (avisouInicio && p.playbackState == Player.STATE_READY) contarFora(if (tocando) ServicosSync.Momento.COMECOU else ServicosSync.Momento.PAUSOU, p.currentPosition)
        }
        override fun onPlayerError(erro: PlaybackException) {
            // Ideia 20: erro de rede tenta de novo no mesmo ponto (2, 4 e 8 s), como a TV ao vivo; o resto mostra a mensagem.
            val p = player ?: return
            if (tunel.onError(p, erro.errorCode, erro.errorCodeName)) return   // era o túnel: já seguiu no modo comum
            val espera = FimDoVideo.esperaAntes(tentativas + 1, FimDoVideo.erroDeRede(erro.errorCode))
            if (espera == null) { tirarCortina(); mostrarAviso(getString(R.string.vod_erro)); return }
            tentativas++
            mostrarAviso(getString(R.string.vod_tentando, tentativas, FimDoVideo.TENTATIVAS))
            val pos = p.currentPosition
            tela.postDelayed({
                if (player !== p || !visivel) return@postDelayed
                SpeechTap.proximaPosicao(pos)
                p.setMediaItem(item(url ?: return@postDelayed), pos)
                p.prepare()
            }, espera)
        }
        override fun onVideoSizeChanged(t: androidx.media3.common.VideoSize) {
            assAjustes.video(t.width, t.height)
            tamanhoVideo = t
            PlayerImagem.aplicar(areaVideo(), modoImagem, t.width, t.height, t.pixelWidthHeightRatio)
        }

        override fun onTracksChanged(faixas: Tracks) {
            // Ideia 86: a taxa de quadros só se sabe quando a faixa de vídeo chega.
            if (!quadrosDecididos) player?.videoFormat?.frameRate?.takeIf { it > 0f }?.let { fps ->
                quadrosDecididos = true
                TaxaQuadros.aplicar(this@VodActivity, fps)
            }
            if (faixas.groups.isNotEmpty()) {
                val soAudio = faixas.groups.none { it.type == C.TRACK_TYPE_VIDEO }
                capaAudio.visibility = if (soAudio) View.VISIBLE else View.GONE
                if (soAudio) tirarCortina()   // música: não vai haver primeiro quadro
            }
            if (legendaDecidida || faixas.groups.none { it.type == C.TRACK_TYPE_AUDIO }) return
            legendaDecidida = true
            val p = player ?: return
            IdiomaPlayer.aplicar(faixas, p.trackSelectionParameters, prefIdioma, idiomaTv, segundoIdioma, prefAcesso)?.let { p.trackSelectionParameters = it }
            aplicarMemoriaSerie(p, faixas)
            talvezLegendaAuto(faixas)
        }
    }



    internal val salvarDepois: Runnable = object : Runnable {
        override fun run() { salvar(); prepararProximo(); tela.postDelayed(this, SALVAR_MS) }
    }

    /**
     * Proposta C, item 11 (pré-carregar): perto do fim do episódio, deixa pronto o que o seguinte vai precisar, para
     * ele abrir já com tudo. O VÍDEO não: o IPTV aceita uma conexão só, e abrir a segunda derrubaria este.
     *  - as marcas do IntroDB (abertura, resumo, créditos) do seguinte, que ficam no cache;
     *  - a legenda: só se ESTE episódio precisou de uma baixada (a série é no idioma de fora), pelo nome, sem o hash
     *    do arquivo (ler pedaços do vídeo seguinte seria a segunda conexão). A legenda que se acerta pela fala e o
     *    atraso da série cuidam do resto. Gasta um download do OpenSubtitles só nesse caso.
     */
    internal var preparouProximo = false


    /** Grava onde parou. Terminou = posição igual à duração (sai do "continuar assistindo"). */
    /** Saiu pela troca de versão: a posição MUDOU de arquivo (ver [trocarVersao]); o onStop não grava de volta. */
    internal var saiuPelaTroca = false
    internal var concluido = false   // terminar() já gravou o fim: o onStop não grava a posição dos créditos por cima



    // ---------------------------------------------------------------- controle

    private val avanco = AvancoSegurado()   // ◀ ▶ segurados: posição escolhida enquanto a tecla está apertada

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player
        apertouTecla = true   // ideia 19: alguém está mexendo, então não é hora de perguntar "ainda está assistindo?"
        // Nos créditos, outra tecla que não o OK = quer ver os créditos: a pílula some e não volta neste trecho.
        if (pilulaProximo && keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_BACK) {
            proximoDispensado = true; esconderPilula()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                // Ideia 24: com a pílula "Pular abertura" na tela, o OK pula a abertura (sem pausar). Fora dela, pausa.
                val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                if (ok && ::pilulaPular.isInitialized && pilulaPular.visibility == View.VISIBLE) {
                    if (event.repeatCount == 0) {
                        if (pilulaProximo) terminar(proximoJa = true)
                        else if (pularPara >= 0) { p?.seekTo(pularPara); esconderPilula(); mostrarBarra() }
                    }
                    return true
                }
                if (event.repeatCount == 0) p?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { pular(p, -10_000L, event); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { pular(p, +30_000L, event); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> { mostrarBarra(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS -> { if (event.repeatCount == 0) escolherFaixas(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0 && pularCapitulo(true)) return true
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) { if (!pularCapitulo(false)) repetirFala(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Toque: pula o passo na hora. Segurado: a barra anda acelerando aos poucos e o vídeo pula ao soltar (AvancoSegurado). */
    private fun pular(p: ExoPlayer?, passo: Long, event: KeyEvent) {
        p ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val ir = avanco.apertou(p.currentPosition, dur, passo, event.repeatCount, event.eventTime, event.downTime)
        if (ir >= 0) p.seekTo(ir)
        mostrarBarra()
        pedirMiniatura(avanco.alvo, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val ir = avanco.soltou()
                esconderMiniatura()
                if (ir >= 0) { player?.seekTo(ir); mostrarBarra() }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * ▼ do player: Áudio e legenda (20/11/2026, "bonito, mas complicado de usar"). A lista de cima tem poucos itens, cada
     * um dizendo à direita o valor de agora; o resto fica uma lista abaixo. Regras do ✓ e das chaves em [PlayerMenu].
     */
    private fun escolherFaixas() {
        player ?: return
        PlayerMenu.abrir(this, getString(R.string.vod_faixas)) {
            val itens = ArrayList<PlayerMenu.Item>()
            itens += PlayerMenu.abre(getString(R.string.vod_audio), faixaEscolhida(C.TRACK_TYPE_AUDIO) ?: "") { menuAudio() }
            itens += PlayerMenu.abre(getString(R.string.vod_legenda), faixaEscolhida(C.TRACK_TYPE_TEXT) ?: getString(R.string.vod_segunda_desligada)) { menuLegenda() }
            itemVersao()?.let { itens += it }
            itens += PlayerMenu.abre(getString(R.string.pm_imagem_som), null) { menuImagemSom() }
            itens += PlayerMenu.abre(getString(R.string.pm_mais), null) { menuMais() }
            itens
        }
    }

    /** Nome da faixa de áudio ou de legenda que está tocando (null = nenhuma). */
    private fun faixaEscolhida(tipo: Int): String? {
        val p = player ?: return null
        for (g in p.currentTracks.groups) {
            if (g.type != tipo) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return nomeFaixa(g.getTrackFormat(i))
        }
        return null
    }

    /** A outra versão do filme ou do episódio (dublada/legendada): é outro arquivo, troca no mesmo ponto (18/11/2026). */
    private fun itemVersao(): PlayerMenu.Item? = outraVersao()?.let { (v, _) ->
        PlayerMenu.acao(getString(if (v == Idioma.Versao.LEGENDADA) R.string.pm_versao_legendada else R.string.pm_versao_dublada),
            getString(R.string.pm_versao_info)) { trocarVersao() }
    }

    /** Faixas de um tipo, uma por linha, com o ✓ na que está tocando. */
    private fun faixas(tipo: Int, aoEscolher: (androidx.media3.common.Format, androidx.media3.common.TrackGroup, Int) -> Unit): List<PlayerMenu.Item> {
        val p = player ?: return emptyList()
        val itens = ArrayList<PlayerMenu.Item>()
        for (grupo in p.currentTracks.groups) {
            if (grupo.type != tipo) continue
            for (i in 0 until grupo.length) {
                if (!grupo.isTrackSupported(i)) continue
                val f = grupo.getTrackFormat(i); val g = grupo.mediaTrackGroup; val indice = i
                itens += PlayerMenu.escolha(nomeFaixa(f), grupo.isTrackSelected(i)) { aoEscolher(f, g, indice) }
            }
        }
        return itens
    }

    private fun usarFaixa(tipo: Int, g: androidx.media3.common.TrackGroup, indice: Int) {
        val p = player ?: return
        if (tipo == C.TRACK_TYPE_TEXT) { paramsAntesRepetir = null; repetindoAte = -1L }   // escolha da pessoa vence
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(tipo, false)
            .setOverrideForType(TrackSelectionOverride(g, indice)).build()
    }

    private fun menuAudio(): Unit = PlayerMenu.abrir(this, getString(R.string.vod_audio), voltar = { escolherFaixas() }) {
        faixas(C.TRACK_TYPE_AUDIO) { f, g, i ->
            // Trocar o áudio na mão vira a escolha deste filme (ou da série toda): da próxima vez já abre assim.
            Idioma.prefDaEscolha(IdiomaPlayer.faixa(f), idiomaTv)?.let { pref ->
                prefIdioma = pref
                Fundo.rodar { IdiomaPlayer.lembrar(this, chaveIdioma, pref) }
            }
            usarFaixa(C.TRACK_TYPE_AUDIO, g, i)
        } + listOfNotNull(itemVersao())
    }

    private fun menuLegenda(): Unit = PlayerMenu.abrir(this, getString(R.string.vod_legenda), voltar = { escolherFaixas() }) {
        val p = player
        val itens = ArrayList<PlayerMenu.Item>()
        // Passada na TV (12/11): sem legenda ligada, nada da lista aparecia marcado na parte da legenda.
        val comLegenda = legendaArquivo != null || p?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } == true
        itens += PlayerMenu.escolha(getString(R.string.vod_segunda_desligada), !comLegenda) { desligarLegenda() }
        itens += faixas(C.TRACK_TYPE_TEXT) { f, g, i ->
            if (f.id != null && f.id == segunda.id) { segunda.id = null; legenda2.visibility = View.GONE; seletor?.atualizar() }
            Idioma.normalizar(f.language)?.let { l -> lembrarSerie { it.copy(legenda = l) } }
            usarFaixa(C.TRACK_TYPE_TEXT, g, i)
        }
        // "O que ele disse?" (proposta C, etapa 1): volta 10 s com legenda só nesse trecho.
        if (!comLegenda && temLegendaNoArquivo()) itens += PlayerMenu.acao(getString(R.string.seg_repetir), getString(R.string.seg_repetir_info)) { repetirFala() }
        // Legenda da internet (OpenSubtitles, com a conta do dono).
        itens += PlayerMenu.acao(getString(R.string.legenda_buscar)) { buscarLegenda() }
        itens += PlayerMenu.abre(getString(R.string.pm_leg_ajustes), null) { menuAjustesLegenda() }
        itens
    }

    private fun desligarLegenda() {
        val p = player ?: return
        paramsAntesRepetir = null; repetindoAte = -1L
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        lembrarSerie { it.copy(legenda = "") }
        // A baixada também sai: antes ela voltava na próxima vez que o filme abria (Sala, 17/11/2026). O "já tentei a
        // automática" fica, então ela não é baixada de novo sozinha; "Buscar legenda" continua trazendo outra.
        if (legendaArquivo != null) {
            val k = chave
            legendaArquivo = null; legendaTraduzida = false
            Fundo.rodar { LegendaBaixar.apagar(this, k) }
        }
    }

    private fun temLegendaNoArquivo() = player?.currentTracks?.groups?.any { g ->
        g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSupported(it) } } == true

    /**
     * "O que ele disse?" (proposta C, etapa 1, 20/11/2026): volta 10 s e, se a legenda estava desligada, liga a do idioma
     * da TV (senão a primeira) só até o ponto em que estava; depois volta como era. Com legenda já ligada, só volta.
     */
    private fun repetirFala() {
        val p = player ?: return
        val agora = p.currentPosition
        val comLegenda = legendaArquivo != null || p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        p.seekTo(EpisodeSegments.repetirDe(agora))
        mostrarBarra()
        if (comLegenda) return
        val grupos = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val escolha = grupos.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
            .let { l -> l.firstOrNull { (g, i) -> Idioma.normalizar(g.getTrackFormat(i).language) == idiomaTv } ?: l.firstOrNull() }
        if (escolha == null) { avisoBreve(getString(R.string.seg_sem_legenda)); return }
        if (paramsAntesRepetir == null) paramsAntesRepetir = p.trackSelectionParameters
        repetindoAte = maxOf(repetindoAte, agora)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(escolha.first.mediaTrackGroup, escolha.second)).build()
        tela.removeCallbacks(vigiaRepetir); tela.postDelayed(vigiaRepetir, 500)
    }

    /** Desliga a legenda do "O que ele disse?" quando o vídeo passa do ponto em que a pessoa estava. */
    private val vigiaRepetir: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.currentPosition < repetindoAte) { tela.postDelayed(this, 500); return }
            paramsAntesRepetir?.let { p.trackSelectionParameters = it }
            paramsAntesRepetir = null; repetindoAte = -1L
        }
    }

    private fun estiloLegenda() =
        EstiloLegenda.de(EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_FUNDO), EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_AMARELA))

    private fun rotuloPosicao() =
        getString(if (EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_ALTO)) R.string.elv_leg_em_cima else R.string.elv_leg_embaixo)

    /** Como a legenda aparece e de onde vem quando falta (vale para todos os vídeos). */
    private fun menuAjustesLegenda() {
        val voltar = { menuAjustesLegenda() }
        PlayerMenu.abrir(this, getString(R.string.pm_leg_ajustes), voltar = { menuLegenda() }) {
            val p = player
            val prefs = getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE)
            val itens = ArrayList<PlayerMenu.Item>()
            itens += PlayerMenu.abre(getString(R.string.pm_leg_tamanho), rotuloTamanho(tamLegendaSp)) { escolherTamanhoLegenda(voltar) }
            // Elevação 6.5: três estilos prontos (as mesmas duas chaves de antes); cada OK passa para o próximo.
            itens += PlayerMenu.Item(getString(R.string.pm_leg_estilo), PlayerMenu.Tipo.CHAVE, nomeEstiloLegenda(estiloLegenda())) {
                val novo = EstiloLegenda.proximo(estiloLegenda())
                EfeitosSom.definir(this, PlayerAcessivel.LEGENDA_FUNDO, novo.fundoPreto)
                EfeitosSom.definir(this, PlayerAcessivel.LEGENDA_AMARELA, novo.amarela)
                pintarLegenda()
            }
            itens += PlayerMenu.Item(getString(R.string.pm_leg_posicao), PlayerMenu.Tipo.CHAVE, rotuloPosicao()) {
                EfeitosSom.alternar(this, PlayerAcessivel.LEGENDA_ALTO); pintarLegenda()
            }
            if (legendaArquivo != null)
                itens += PlayerMenu.abre(getString(R.string.pm_leg_atraso), rotuloAtraso(atrasoLegenda)) { escolherAtraso(voltar) }
            // Ideia 6: segunda legenda (em cima). Só aparece se houver outra faixa de texto para mostrar.
            if (p != null && p.currentTracks.groups.any { g -> (0 until g.length).any { SegundaLegenda.aceita(g.getTrackFormat(it)) } })
                itens += PlayerMenu.abre(getString(R.string.vod_segunda_opcao), nomeSegunda()) { escolherSegunda(voltar) }
            val auto = prefs.getBoolean(LEGENDA_AUTO, true)
            itens += PlayerMenu.chave(this, getString(R.string.pm_leg_auto), auto) {
                prefs.edit().putBoolean(LEGENDA_AUTO, !auto).apply()
            }
            if (LegendaTraduzida.suporta(idiomaTv)) {
                val trad = prefs.getBoolean(LEGENDA_TRADUZIR, true)
                itens += PlayerMenu.chave(this, getString(R.string.pm_leg_traduzir), trad) {
                    prefs.edit().putBoolean(LEGENDA_TRADUZIR, !trad).apply()
                    if (trad) pararTraducao.set(true)
                }
            }
            itens
        }
    }

    /** Velocidade, imagem e os ajustes de som (para quem ouve pior, caixas de som, Bluetooth). */
    private fun menuImagemSom() {
        val voltar = { menuImagemSom() }
        PlayerMenu.abrir(this, getString(R.string.pm_imagem_som), voltar = { escolherFaixas() }) {
            val p = player
            val itens = ArrayList<PlayerMenu.Item>()
            // 11/11/2026 (visto no 1 Play): velocidade e proporção da imagem, no mesmo ▼ para não criar outra tecla
            if (p != null) itens += PlayerMenu.abre(getString(R.string.pm_velocidade), PlayerImagem.rotuloVelocidade(p.playbackParameters.speed)) { escolherVelocidade(voltar) }
            itens += PlayerMenu.abre(getString(R.string.pm_proporcao), PlayerImagem.rotulo(this, modoImagem)) { escolherImagem(voltar) }
            // Para quem enxerga e ouve pior (14/11/2026): valem para todos os vídeos e canais.
            if (EfeitosSom.disponivel()) {
                itens += PlayerMenu.chave(this, getString(R.string.acess_volume), EfeitosSom.volumeIgual(this)) { alternarSom(PlayerAcessivel.VOLUME_IGUAL) }
                itens += PlayerMenu.chave(this, getString(R.string.acess_voz), EfeitosSom.vozClara(this)) { alternarSom(PlayerAcessivel.VOZ_CLARA) }
            }
            // Ideia 2: diálogo mais alto (5.1/7.1 tocando em 2 caixas). Ideia 4: atraso do áudio, um por saída de som.
            if (FfNativo.disponivel) itens += PlayerMenu.chave(this, getString(R.string.vod_dialogo_opcao), EfeitosSom.dialogoAlto(this)) { alternarDialogo() }
            itens += PlayerMenu.abre(getString(R.string.pm_atraso_audio), rotuloAtrasoAudio(EfeitosSom.atrasoMs(this))) { escolherAtrasoAudio(voltar) }
            itens += PlayerMenu.chave(this, getString(R.string.pm_quadros), TaxaQuadros.ligada(this)) {
                val ligou = TaxaQuadros.alternar(this)
                if (ligou) player?.videoFormat?.frameRate?.takeIf { it > 0f }?.let { TaxaQuadros.aplicar(this, it) } else TaxaQuadros.soltar(this)
            }
            itens += PlayerMenu.abre(getString(R.string.fone_menu), getString(if (SomNoFone.ligado) R.string.pm_ligado else R.string.pm_desligado)) { SomNoFone.janela(this) }
            itens
        }
    }

    /** O que não é áudio, legenda nem imagem: capítulos, elenco, abertura, desligar no fim, outras TVs. */
    private fun menuMais(): Unit = PlayerMenu.abrir(this, getString(R.string.pm_mais), voltar = { escolherFaixas() }) {
        val p = player
        val itens = ArrayList<PlayerMenu.Item>()
        // Ideia 5: capítulos do arquivo (MKV, MP4, WMV). ⏭ ⏮ do controle também pulam entre eles.
        if (capitulos.size >= 2) itens += PlayerMenu.abre(getString(R.string.vod_capitulos_opcao, capitulos.size), null) { escolherCapitulo({ menuMais() }) }
        // Ideia 84 "Quem é esse ator?": o elenco por cima do vídeo (pausa; volta com VOLTAR).
        if (p != null) buscaLegenda().tmdb?.takeIf { ElencoActivity.disponivel(it) }?.let { id ->
            itens += PlayerMenu.acao(getString(R.string.elenco_opcao_player)) {
                p.pause(); ElencoActivity.abrir(this, id, serie, intent.getStringExtra(EXTRA_TITULO).orEmpty().substringBefore(" · "))
            }
        }
        itens += PlayerMenu.chave(this, getString(R.string.vod_desligar_ao_fim), desligarAoFim) { desligarAoFim = !desligarAoFim }
        // Ideia 8: informações técnicas por cima do vídeo.
        itens += PlayerMenu.chave(this, getString(R.string.vod_info_opcao), EfeitosSom.ligado(this, InfoTecnica.PREF)) { alternarInfo() }
        // Ideia 24: marcar a abertura da série (vale para os próximos episódios). Só em episódio com a chave da série.
        if (serie && p != null) {
            val agora = relogio(p.currentPosition)
            itens += PlayerMenu.acao(getString(R.string.abertura_marcar_inicio, agora)) { marcarAbertura(inicio = true) }
            itens += PlayerMenu.acao(getString(R.string.abertura_marcar_fim, agora)) { marcarAbertura(inicio = false) }
            if (abertura != null) itens += PlayerMenu.acao(getString(R.string.abertura_apagar)) { apagarAbertura() }
        }
        if (EntreTvs.ligado(this) && p != null) {
            val titulo = intent.getStringExtra(EXTRA_TITULO).orEmpty()
            itens += PlayerMenu.acao(getString(R.string.junto_menu)) { junto?.menu(titulo, JuntoTvsProvider.tvs(this)) }
            itens += PlayerMenu.acao(getString(R.string.cont_menu)) {
                junto?.enviarPara(titulo, JuntoTvsProvider.tvs(this), p.currentPosition, intent.getStringExtra(EXTRA_URL))
            }
        }
        itens
    }

    // ---------------------------------------------------------------- outra versão (dublada/legendada)

    private fun outraVersao(): Pair<Idioma.Versao, String>? {
        val id = intent.getStringExtra(EXTRA_ALT_ID)?.takeIf { it.isNotEmpty() } ?: return null
        val v = intent.getStringExtra(EXTRA_ALT_VERSAO)?.let { n -> Idioma.Versao.entries.firstOrNull { it.name == n } } ?: return null
        if (v == Idioma.Versao.DESCONHECIDA) return null
        return v to id
    }

    /** Abre a outra versão no mesmo ponto e fecha esta. A escolha vira a preferência deste filme (as duas chaves). */
    private fun trocarVersao() {
        val (v, altId) = outraVersao() ?: return
        val p = player ?: return
        val pos = p.currentPosition
        // A posição vai junto para a outra versão e sai desta (debug 20/11/2026): antes ficava nas duas, a grade e o
        // Continuar mostravam o mesmo filme duas vezes e retomar pela versão velha voltava ao ponto antigo.
        val altFonte = intent.getStringExtra(EXTRA_ALT_FONTE).orEmpty()
        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: TIPO_FILME
        val chaveVelha = chave
        if (chave(tipo, altFonte, altId) != chaveVelha) {
            saiuPelaTroca = true
            val nova = Posicao(pos, p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L, System.currentTimeMillis())
            val chaveNova = chave(tipo, altFonte, altId)
            Fundo.rodar("trocar-versao-posicao") {
                PlayerArmazem.definirPosicao(this, chaveVelha, null)
                if (pos > 0) PlayerArmazem.definirPosicao(this, chaveNova, nova)
            }
        } else salvar()
        val pref = if (v == Idioma.Versao.LEGENDADA) Idioma.Pref.ORIGINAL else Idioma.Pref.DUBLADO
        // Episódio: só a chave da série (uma por episódio trocado enchia o limite de 500 e expulsava escolhas reais).
        val chaves = if (tipo == TIPO_EPISODIO) listOf(chaveIdioma) else listOf(chaveIdioma, chave(tipo, altFonte, altId)).distinct()
        Fundo.rodar("trocar-versao") { chaves.forEach { IdiomaPlayer.lembrar(this, it, pref) } }
        val atual = intent.getStringExtra(EXTRA_LEG_VERSAO)?.let { n -> Idioma.Versao.entries.firstOrNull { it.name == n } } ?: Idioma.Versao.DESCONHECIDA
        val nova = Intent(intent)
            .putExtra(EXTRA_FONTE, altFonte).putExtra(EXTRA_ID, altId)
            .putExtra(EXTRA_EXT, intent.getStringExtra(EXTRA_ALT_EXT).orEmpty())
            .putExtra(EXTRA_TITULO, intent.getStringExtra(EXTRA_ALT_TITULO).orEmpty())
            .putExtra(EXTRA_LEG_VERSAO, v.name)
            .putExtra("inicio_ms", pos).putExtra("do_comeco", false)
            .putExtra(EXTRA_ALT_FONTE, intent.getStringExtra(EXTRA_FONTE).orEmpty()).putExtra(EXTRA_ALT_ID, intent.getStringExtra(EXTRA_ID).orEmpty())
            .putExtra(EXTRA_ALT_EXT, intent.getStringExtra(EXTRA_EXT).orEmpty())
            .putExtra(EXTRA_ALT_TITULO, intent.getStringExtra(EXTRA_TITULO).orEmpty()).putExtra(EXTRA_ALT_VERSAO, atual.name)
        if (tipo == TIPO_EPISODIO) {
            // Episódio: a chave de idioma é a da série (a escolha vale para os próximos), e o "terminou" desta nova
            // VodActivity tem de chegar à SeriesActivity para o próximo episódio seguir.
            nova.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        } else nova.removeExtra(EXTRA_IDIOMA)   // filme: a chave de idioma é a do próprio arquivo
        startActivity(nova)
        finish()
    }

    // ---------------------------------------------------------------- sessão junto (degrau 4, SessaoJunto.kt)

    private var junto: JuntoNoPlayer? = null

    /** Liga a ponte com a outra TV: a chave diz o que toca aqui; pausa e pulos de lá chegam pelo [JuntoNoPlayer.aplicar]. */
    private fun ligarJunto(direto: String?) {
        val b = buscaLegenda()
        junto = JuntoNoPlayer(this, aplicar = { tocando, alvo ->
            player?.let { p -> if (alvo != null) p.seekTo(alvo); p.playWhenReady = tocando; mostrarBarra() }
        }, posicao = { player?.currentPosition ?: 0L }, tocandoAgora = { player?.isPlaying == true }).also {
            it.tocandoTitulo(JuntoRegras.chaveVod(b.tmdb, b.temporada, b.episodio, direto ?: intent.getStringExtra(EXTRA_TITULO)))
            it.ligar()
        }
    }

    // ---------------------------------------------------------------- ideia 24: pular abertura






    internal val vigiaAbertura: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val creditos = if (proximoContagem >= 0 && !desligarAoFim) EpisodeSegments.inicioCreditos(creditosBanco, capitulos, dur) else null
            if (EpisodeSegments.mostrarProximo(creditos, pos, dur)) {
                if (!proximoDispensado && !pilulaProximo) mostrarProximo()
            } else {
                if (pilulaProximo) esconderPilula()
                proximoDispensado = false   // saiu dos créditos (voltou no filme): pode oferecer de novo
                val m = if (serie || aberturaDoArquivo) EpisodeSegments.pularAgora(resumo, abertura, pos) else null
                if (m == null) { avisouPulo = false; avisadoTrecho = -1L; if (pularPara >= 0) esconderPilula() }
                else if (avisadoTrecho != m.fim) {
                    // uma vez por trecho: some depois de 8 s e só volta quando entrar em outro trecho
                    avisouPulo = true; avisadoTrecho = m.fim; pularPara = m.fim
                    pilulaPular.text = getString(if (m === resumo) R.string.seg_pular_resumo else R.string.abertura_pular)
                    pilulaPular.visibility = View.VISIBLE
                    tela.removeCallbacks(esconderPilulaDepois); tela.postDelayed(esconderPilulaDepois, AberturaSerie.AVISO_MS)
                }
            }
            tela.postDelayed(this, 500)
        }
    }



    internal val contaProximo: Runnable = object : Runnable {
        override fun run() {
            if (!pilulaProximo || proximoResta <= 0) return
            if (player?.isPlaying != true) { tela.postDelayed(this, 1_000); return }   // pausado: a contagem espera
            proximoResta--
            if (proximoResta <= 0) { terminar(proximoJa = true); return }
            pintarProximo()
            tela.postDelayed(this, 1_000)
        }
    }

    internal val esconderPilulaDepois = Runnable { esconderPilula() }



    // ---------------------------------------------------------------- Trakt/Simkl (fatia 3)

    /** O que contar ao Trakt/Simkl: o filme ou a série + episódio, pelo id TMDB. Null = sem id (nada vai). */
    internal val alvoFora: Pair<SincronizarFilmes.Item, SincronizarFilmes.Ep?>? by lazy {
        val b = buscaLegenda()
        val id = b.tmdb ?: return@lazy null
        val ep = if (b.eEpisodio) SincronizarFilmes.Ep(b.temporada!!, b.episodio!!) else null
        SincronizarFilmes.Item(if (ep == null) SincronizarFilmes.Tipo.FILME else SincronizarFilmes.Tipo.SERIE, SincronizarFilmes.Ids(tmdb = id),
            if (ep == null) b.nome else "", if (ep == null) b.ano else null) to ep
    }

    /** "Vendo agora", pausa e fim, fora da tela. ServicosSync decide se pode (opção ligada, sem visita nem infantil). */
    internal var paradoFora = false   // já contou o fim: a pausa que o Android manda ao soltar o player não vale



    internal val esconderAvisoDepois = Runnable { esconderAviso() }


    internal var autoTentada = false














    internal var modoImagem = PlayerImagem.Modo.AJUSTAR
    internal var tamanhoVideo: androidx.media3.common.VideoSize? = null

    // ---------------------------------------------------------------- barra de baixo

    private fun montarBarra(t: String): View {
        // B9: a mesma peça da TV ao vivo (PlayerBar). Título em cima, tempo e "termina às" embaixo dele, PAUSADO à direita.
        val b = PlayerBar.montar(this, getString(R.string.vod_teclas))
        barra = b.raiz; titulo = b.titulo; tempo = b.detalhe; estadoBarra = b.estado; progresso = b.progresso
        titulo.text = t
        estadoBarra.setTextColor(Estilo.ATENCAO)
        return barra
    }

    private val esconderBarra = Runnable { if (player?.isPlaying == true) { barra.visibility = View.GONE; posicionarLegenda() } }

    /**
     * A legenda fica a 56 dp do fundo; a barra ocupa ~130 dp. Sem isto, com a barra na tela (pausa, ◀ ▶) a fala ficava
     * por cima da barra (o comentário do onCreate prometia "acima da barra", mas nada a subia).
     */
    private fun posicionarLegenda() {
        if (!::legenda.isInitialized) return
        if (barra.visibility != View.VISIBLE) { legenda.translationY = 0f; return }
        barra.post {
            if (barra.visibility != View.VISIBLE) return@post
            // topo da barra (altura + margem de 26 dp) mais uma folga de 12 dp, menos onde a legenda já está (56 dp)
            legenda.translationY = -(barra.height + dp(26) + dp(12) - dp(56)).coerceAtLeast(0).toFloat()
        }
    }
    private val tique: Runnable = object : Runnable {
        override fun run() { atualizarBarra(); if (barra.visibility == View.VISIBLE) tela.postDelayed(this, 1_000) }
    }

    private fun mostrarBarra(fixa: Boolean = false) {
        barra.visibility = View.VISIBLE
        posicionarLegenda()
        atualizarBarra()
        tela.removeCallbacks(tique); tela.postDelayed(tique, 1_000)
        tela.removeCallbacks(esconderBarra)
        if (!fixa) tela.postDelayed(esconderBarra, ESCONDER_MS)
    }

    private fun atualizarBarra() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = avanco.alvo.takeIf { it >= 0 } ?: p.currentPosition   // segurando ◀ ▶: mostra para onde vai
        // Pausado: o selo da TV ao vivo ("⏸ PAUSADO · OK continua"), à direita, em vez do ⏸ solto no começo do tempo.
        val pausado = !p.isPlaying && p.playbackState == Player.STATE_READY
        estadoBarra.visibility = if (pausado) View.VISIBLE else View.GONE
        if (pausado) estadoBarra.setText(R.string.pausa_selo)
        // "termina às 22:40" (como Plex e Apple TV; proposta C, etapa 1): na velocidade atual, no formato 12/24 h da TV.
        val fim = dur?.let { EpisodeSegments.terminaEm(System.currentTimeMillis(), pos, it, p.playbackParameters.speed) }
        val termina = fim?.let { "   ·   " + getString(R.string.seg_termina, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(it))) }.orEmpty()
        tempo.text = (dur?.let { "${relogio(pos)} / ${relogio(it)}   −${relogio(it - pos)}" } ?: relogio(pos)) + termina
        progresso.progress = dur?.let { (pos * 1000 / it).toInt() } ?: 0
        progresso.secondaryProgress = dur?.let { (p.bufferedPosition * 1000 / it).toInt() } ?: 0
    }

    /**
     * A cada 5 s: liga o ouvido ([SpeechTap]) só com legenda baixada; confere o relógio dele contra o do player; e a cada
     * minuto tenta o acerto ([SubtitleSync]). Com encaixe seguro, refaz a legenda no novo atraso e avisa.
     */
    private fun vigiarLegenda() {
        val p = player ?: return
        val arq = legendaArquivo
        SpeechTap.ligado = arq != null && !atrasoManual && acertosAuto < 2
        if (!SpeechTap.ligado || !p.isPlaying) return
        SpeechTap.relogioMs()?.let { r -> if (r - p.currentPosition !in -500L..5_000L) SpeechTap.invalidar() }
        if (++tiquesLegenda % 12 != 0 || acertando || arq == null) return
        acertando = true
        val k = chave; val atual = atrasoLegenda
        Fundo.rodar("legenda-acerta") {
            val r = runCatching {
                val (inicio, e) = SpeechTap.trecho(SubtitleSync.JANELA_ANALISE_MS) ?: return@runCatching null
                SubtitleSync.ajustar(SubtitleSync.marcarFala(e), inicio, SubtitleSync.falasDoSrt(arq.readText()))
            }.getOrNull()
            val total = atual + (r?.atrasoMs ?: 0L)
            val f = if (SubtitleSync.aplicar(r)) LegendaBaixar.ajustar(this, k, total) else null
            if (f != null) getSharedPreferences(Prefs.PLAYER, MODE_PRIVATE).edit().putLong("atraso:$k", total).apply()
            tela.post {
                acertando = false
                if (isDestroyed || atrasoManual || legendaArquivo?.path != arq.path || atrasoLegenda != atual) return@post
                if (r != null) android.util.Log.i("TvLauncher.Legenda", " ajuste ${r.atrasoMs} ms, confiança ${"%.1f".format(Locale.ROOT, r.confianca)}")
                if (r != null && !SubtitleSync.aplicar(r)) { acertosAuto = 2; return@post }   // já está no lugar: para de escutar
                if (f == null) return@post
                acertosAuto++
                legendaArquivo = f; atrasoLegenda = total
                avisoBreve(getString(R.string.legenda_acertada, rotuloAtraso(total)))
                recarregarComLegenda()
            }
        }
    }

    /** Fecha o trecho medido (a tela saiu da frente ou o vídeo acabou) e grava as taxas, se tocou o bastante. */
    private fun medirTrecho() {
        val r = medida.fechar(android.os.SystemClock.elapsedRealtime(), perdidos - perdidosMedidos)
        perdidosMedidos = perdidos
        r ?: return
        MedidasTv.anotar(this, Placar.Chave.VOD_TRAVADAS, r.travadasPorHora)
        MedidasTv.anotar(this, Placar.Chave.VOD_PERDIDOS, r.perdidosPorMin)
    }

    internal fun relogio(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    internal fun mostrarAviso(t: String) { aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun tirarCortina() { cortina?.let { CortinaVideo.tirar(it) }; cortina = null }
    internal fun esconderAviso() { aviso.visibility = View.GONE }

    internal fun vidro(raio: Int) = GradientDrawable().apply {
        cornerRadius = dp(raio).toFloat(); setColor(0xD9121528.toInt()); setStroke(dp(1), Estilo.SUPERFICIE_ALTA)
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

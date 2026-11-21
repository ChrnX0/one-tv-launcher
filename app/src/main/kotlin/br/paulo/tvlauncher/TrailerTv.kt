package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.privilege.JanelaNaFrente

/**
 * Trailer no YouTube (18/11/2026, Paulo: "trailers que abrem errado no YouTube ou não abrem, e se abrem, ao sair fica
 * aberto o aplicativo do YouTube"). Parte pura (sem Android), testada em TrailerTvTest.
 *
 * Visto na Sala (v462950): VOLTAR no trailer leva para a tela inicial do YouTube (recomendados), não para a ficha; o
 * YouTube fica aberto por trás. `am force-stop` do YouTube devolve a ficha na hora (a ficha está na tarefa de baixo).
 */
object TrailerLink {
    const val YOUTUBE_TV = "com.google.android.youtube.tv"

    private val reId = Regex("[A-Za-z0-9_-]{11}")
    private val reLink = Regex(
        """^(?:https?://)?(?:(?:www|m|music)\.)?(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:[^#]*&)?v=|embed/|v/|shorts/|live/|e/)|youtu\.be/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""",
        RegexOption.IGNORE_CASE)

    /**
     * O código do vídeo (11 caracteres) em qualquer forma que os servidores mandam: só o código, watch?v=, youtu.be/,
     * embed/, shorts/, com ou sem https e www. Antes só watch?v= e youtu.be passavam: "embed/..." (comum no Xtream)
     * sumia com o botão Trailer. Qualquer outra coisa = null (não abrimos endereço desconhecido).
     */
    fun codigo(valor: String?): String? {
        val v = valor?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.matches(reId)) return v
        return reLink.find(v)?.groupValues?.get(1)
    }

    fun link(codigo: String) = "https://www.youtube.com/watch?v=$codigo"

    /**
     * Uma janela nova quer dizer que a pessoa saiu do YouTube? Só uma TELA de verdade (Activity). Janelas por cima que
     * não tiram o YouTube da frente também geram o evento: os nossos avisos (serviço de acessibilidade: classe
     * "android.widget.FrameLayout"), diálogos e toasts ("android.app.Dialog", "android.widget.Toast"...). Visto na Sala
     * (18/11/2026): o aviso "o Claude olhou a tela" fechava o trailer 2 s depois de abrir.
     */
    fun saiuDoYoutube(nosso: String, pacote: String, classe: String): Boolean {
        if (classe.startsWith("android.widget.") || classe.startsWith("android.view.") || classe.startsWith("android.app.Dialog") ||
            classe.startsWith("android.app.AlertDialog") || classe.startsWith("android.inputmethodservice.")) return false
        // nosso pacote: só uma Activity nossa (a ficha voltando, a HOME); o resto são janelas nossas por cima
        if (pacote == nosso) return classe.startsWith("$nosso.") && classe.endsWith("Activity")
        return true
    }

    /** O link normalizado, ou "" quando não é um vídeo do YouTube. */
    fun normalizar(valor: String?): String = codigo(valor)?.let { link(it) } ?: ""

    /**
     * Qual trailer abrir: o do TMDB primeiro (escolhido pelo id do filme, no idioma da TV, oficial), depois o do servidor
     * de IPTV (muitas vezes de outro filme com nome parecido, ou um vídeo apagado: "abre errado"/"não abre").
     */
    fun escolher(tmdb: String?, servidor: String?): String? =
        codigo(tmdb)?.let { link(it) } ?: codigo(servidor)?.let { link(it) }

    /**
     * O trailer acabou? (o YouTube emenda o próximo vídeo sozinho). Só depois de ter tocado: parou de vez, deu erro, ou o
     * título mudou (outro vídeo começou).
     */
    fun acabou(tocou: Boolean, tituloInicial: String?, estado: Int, tituloAgora: String?): Boolean {
        if (!tocou) return false
        if (estado == PlaybackState.STATE_STOPPED || estado == PlaybackState.STATE_ERROR || estado == PlaybackState.STATE_NONE) return true
        return !tituloInicial.isNullOrBlank() && !tituloAgora.isNullOrBlank() && tituloAgora != tituloInicial
    }
}

/**
 * O lado do processo principal (onde mora o serviço de acessibilidade). O player (processo :player) avisa por
 * [TrailerReceiver] que abriu um trailer; daí em diante, enquanto o YouTube estiver na frente:
 * - VOLTAR (toque curto) fecha o YouTube e devolve a ficha de onde o trailer saiu (segurar continua abrindo o painel);
 * - o fim do trailer (sessão de mídia do YouTube, quando o acesso às notificações está ligado) faz o mesmo;
 * - se a pessoa sair por outro caminho (HOME, outro app), o YouTube é fechado do mesmo jeito.
 */
object VigiaTrailer {
    private const val TAG = "TvLauncher.Trailer"
    /** Depois disso a sessão vale como encerrada (alguém ficou no YouTube por conta própria). */
    private const val PRAZO_MS = 30 * 60_000L
    private const val OLHAR_MS = 2_000L
    private const val ESPERA_YOUTUBE_MS = 15_000L
    private const val CONFIRMA_MS = 1_200L

    private class Sessao(val inicio: Long, val origem: String?) {
        var viuYoutube = false
        var tocou = false
        var titulo: String? = null
    }

    @Volatile private var sessao: Sessao? = null
    private val principal = Handler(Looper.getMainLooper())
    private var olhando: Runnable? = null

    fun iniciar(ctx: Context, origem: String?) {
        sessao = Sessao(SystemClock.uptimeMillis(), origem?.takeIf { it.startsWith(AppIdentity.NAMESPACE + ".") })
            .apply { viuYoutube = JanelaNaFrente.pacote == TrailerLink.YOUTUBE_TV }
        olhar(ctx.applicationContext)
        Log.i(TAG, "trailer aberto")
    }

    /** O trailer está na frente: VOLTAR é nosso. */
    fun emCurso(): Boolean {
        val s = sessao ?: return false
        if (vencida(s)) { sessao = null; return false }
        return JanelaNaFrente.pacote == TrailerLink.YOUTUBE_TV
    }

    /** Passou do prazo, ou o YouTube nem chegou a abrir em [ESPERA_YOUTUBE_MS] (o pedido falhou). */
    private fun vencida(s: Sessao): Boolean {
        val passou = SystemClock.uptimeMillis() - s.inicio
        return passou > PRAZO_MS || (!s.viuYoutube && passou > ESPERA_YOUTUBE_MS)
    }

    /** Cada janela que abre (serviço de acessibilidade). */
    fun janela(ctx: Context, pacote: CharSequence?, classe: CharSequence? = null) {
        val s = sessao ?: return
        val p = pacote?.toString() ?: return
        if (p == "android" || p == "com.android.systemui") return
        // Painel rápido (VOLTAR segurado) e o nosso protetor por cima do trailer não contam como "saiu do YouTube".
        val c = classe?.toString().orEmpty()
        if (p == ctx.packageName && (c.contains("PainelRapido") || c.contains("Protetor") || c.contains("Dream"))) return
        if (p == TrailerLink.YOUTUBE_TV) { s.viuYoutube = true; return }
        if (!s.viuYoutube || !TrailerLink.saiuDoYoutube(ctx.packageName, p, c)) return
        // Saiu do YouTube por outro caminho (HOME, outro app, a ficha voltou)? Confere de novo daqui a pouco: visto na Sala
        // (v463053), um aviso por cima (janela do serviço de acessibilidade, pacote nosso) fechava o trailer em 2 s.
        Log.i(TAG, "janela fora do YouTube: $p/$c")
        principal.postDelayed({
            if (sessao === s && JanelaNaFrente.pacote != TrailerLink.YOUTUBE_TV) encerrar(ctx, trazer = false)
        }, CONFIRMA_MS)
    }

    /** VOLTAR no trailer ou fim do trailer: fecha o YouTube e traz a ficha. */
    fun voltar(ctx: Context) = encerrar(ctx, trazer = true)

    private fun encerrar(ctx: Context, trazer: Boolean) {
        val s = sessao ?: return
        sessao = null
        olhando?.let { principal.removeCallbacks(it) }; olhando = null
        val app = ctx.applicationContext
        Log.i(TAG, "trailer encerrado (trazer=$trazer)")
        Fundo.rodar("trailer-fechar") {
            // Com o nosso shell: force-stop. Visto na Sala: a ficha (por baixo) aparece na hora e o YouTube sai da memória.
            val feito = GuardiaoShizuku.comando(app, "am force-stop ${TrailerLink.YOUTUBE_TV}", 8_000) != null
            if (feito) return@rodar
            // Sem shell (aparelho sem o pareamento): traz a ficha por cima e mata o YouTube já em segundo plano.
            if (trazer) principal.post { trazerOrigem(app, s.origem) }
            principal.postDelayed({
                runCatching { app.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(TrailerLink.YOUTUBE_TV) }
            }, 1_500)
        }
    }

    private fun trazerOrigem(ctx: Context, origem: String?) {
        val i = if (origem != null) Intent().setClassName(ctx, origem) else Intent(ctx, MainActivity::class.java)
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { Log.w(TAG, "não trouxe a ficha: $it") }
    }

    /** Olha a sessão de mídia do YouTube a cada 2 s: o trailer acabou -> volta. Sem acesso às notificações, só o VOLTAR. */
    private fun olhar(app: Context) {
        olhando?.let { principal.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val s = sessao ?: return
                if (vencida(s)) { sessao = null; return }
                val c = controlador(app)
                if (c != null) {
                    val estado = c.playbackState?.state ?: PlaybackState.STATE_NONE
                    val titulo = c.metadata?.description?.title?.toString()
                    if (estado == PlaybackState.STATE_PLAYING && !s.tocou) { s.tocou = true; s.titulo = titulo }
                    if (s.tocou && s.titulo.isNullOrBlank()) s.titulo = titulo
                    if (JanelaNaFrente.pacote == TrailerLink.YOUTUBE_TV && TrailerLink.acabou(s.tocou, s.titulo, estado, titulo)) {
                        voltar(app); return
                    }
                } else if (s.tocou && JanelaNaFrente.pacote == TrailerLink.YOUTUBE_TV) {
                    // A sessão do YouTube sumiu depois de tocar (ele a solta quando o vídeo acaba e volta ao menu).
                    voltar(app); return
                }
                principal.postDelayed(this, OLHAR_MS)
            }
        }
        olhando = r
        principal.postDelayed(r, OLHAR_MS)
    }

    private fun controlador(ctx: Context): MediaController? = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(ctx, OuvinteNotificacoes::class.java))
            ?.firstOrNull { it.packageName == TrailerLink.YOUTUBE_TV }
    } catch (_: SecurityException) { null } catch (_: Exception) { null }
}

/** O player (processo :player) avisa que abriu um trailer. Não exportado. */
class TrailerReceiver : BroadcastReceiver() {
    companion object {
        const val ACAO = "br.paulo.tvlauncher.TRAILER_ABERTO"
        fun avisar(ctx: Context, origem: String?) {
            runCatching {
                ctx.sendBroadcast(Intent(ACAO).setComponent(ComponentName(ctx, TrailerReceiver::class.java)).putExtra("origem", origem))
            }
        }
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACAO) return
        VigiaTrailer.iniciar(ctx, i.getStringExtra("origem"))
    }
}

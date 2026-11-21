package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Fundo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.PlaceholderSurface
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.observer.MedidasTv
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Elevação 6.1: toca a matriz de amostras no APARELHO DE BANCADA (só se marcado: MedidasTv.bancada), uma por vez, sem
 * tela (superfície de reserva do Media3) e sem som (volume 0), com a mesma fábrica de renderizadores do player
 * ([RenderizadoresPlayer]: chip primeiro, FFmpeg de reserva). Comandos adb: `EXTRAS --es o matriz --es base <url>` e
 * `EXTRAS --es o matriz_resultado`. Nunca roda sozinho e nunca na Sala.
 */
@OptIn(UnstableApi::class)
object MatrizAmostrasTv {
    private const val TAG = "TvLauncher.Matriz"
    private const val LIMITE_MS = 25_000L     // por amostra: abrir + tocar
    @Volatile private var rodando = false
    @Volatile private var andamento = ""

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "matriz-resultado.txt")

    fun aparelho(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    fun iniciar(ctx: Context, base: String?): String {
        if (!MedidasTv.bancada(ctx)) return "este aparelho não é bancada (nunca rodar na Sala)"
        if (base.isNullOrBlank() || MatrizAmostras.url(base, "matriz.json") == null) return "falta --es base http://<pc>:8000/"
        if (rodando) return "rodando: $andamento"
        rodando = true; andamento = "lendo a matriz"
        val app = ctx.applicationContext
        Fundo.rodar("matriz") { try { rodar(app, base) } catch (e: Throwable) { Log.w(TAG, "matriz: $e"); andamento = "erro: ${e.javaClass.simpleName}" } finally { rodando = false } }
        return "começou"
    }

    fun resultado(ctx: Context): String = if (rodando) "rodando: $andamento"
        else runCatching { arquivo(ctx).readText() }.getOrDefault("ainda não rodou")

    private fun baixar(url: String): String =
        Http.getText(url, connectMs = 5_000, readMs = 10_000) ?: throw java.io.IOException("matriz não veio: $url")

    private fun rodar(ctx: Context, base: String) {
        val lista = MatrizAmostras.ler(baixar(MatrizAmostras.url(base, "matriz.json")!!))
        val linha = HandlerThread("matriz-player").apply { start() }
        val h = Handler(linha.looper)
        val superficie = runCatching { PlaceholderSurface.newInstance(ctx, false) }.getOrNull()
        val resultados = ArrayList<MatrizAmostras.Resultado>()
        try {
            lista.forEachIndexed { i, a ->
                andamento = "${i + 1} de ${lista.size}: ${a.id}"
                resultados += MatrizAmostras.veredito(a, tocar(ctx, h, superficie, base, a))
            }
        } finally {
            superficie?.release()
            linha.quitSafely()
        }
        val texto = MatrizAmostras.texto(resultados, aparelho())
        arquivo(ctx).writeText(texto + "\n\n" + MatrizAmostras.json(resultados, aparelho()).toString(1))
        Log.i(TAG, texto.lineSequence().first())
    }

    private fun tocar(ctx: Context, h: Handler, sup: android.view.Surface?, base: String, a: MatrizAmostras.Amostra): MatrizAmostras.Visto {
        val url = MatrizAmostras.url(base, a.arquivo) ?: return MatrizAmostras.Visto(erro = "endereço inválido")
        var visto = MatrizAmostras.Visto()
        val fim = CountDownLatch(1)
        h.post {
            val p = PlayerEngine.build(ctx, RenderizadoresPlayer(ctx), audioFocus = false) { setLooper(h.looper) }   // teste por adb: não tira o som de ninguém
            val decs = LinkedHashSet<String>()
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, nome: String, inicio: Long, dur: Long) { decs += nome }
                override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, nome: String, inicio: Long, dur: Long) { decs += nome }
            })
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) visto = visto.copy(pronto = true) }
                override fun onRenderedFirstFrame() { visto = visto.copy(primeiroQuadro = true) }
                override fun onPlayerError(e: PlaybackException) { visto = visto.copy(erro = e.errorCodeName.removePrefix("ERROR_CODE_").lowercase()) }
                override fun onTracksChanged(t: Tracks) {
                    visto = visto.copy(legendasVistas = t.groups.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported })
                }
            })
            val item = MediaItem.Builder().setUri(url).apply {
                a.legendaExterna?.let { leg -> MatrizAmostras.url(base, leg)?.let { u ->
                    setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
                } }
            }.build()
            if (sup != null) p.setVideoSurface(sup)
            p.volume = 0f
            p.setMediaItem(item); p.prepare(); p.play()
            val comeco = System.currentTimeMillis()
            val conferir = object : Runnable {
                override fun run() {
                    val pos = p.currentPosition
                    val acabou = visto.erro != null || pos >= MatrizAmostras.MIN_ANDOU_MS + 500 || p.playbackState == Player.STATE_ENDED ||
                        System.currentTimeMillis() - comeco > LIMITE_MS
                    if (!acabou) { h.postDelayed(this, 250); return }
                    visto = visto.copy(posicaoMs = if (p.playbackState == Player.STATE_ENDED) maxOf(pos, p.duration) else pos,
                        decodificadores = decs.joinToString(" + "))
                    p.release()
                    fim.countDown()
                }
            }
            h.postDelayed(conferir, 250)
        }
        if (!fim.await(LIMITE_MS + 10_000, TimeUnit.MILLISECONDS)) return visto.copy(erro = visto.erro ?: "travou")
        return visto
    }
}

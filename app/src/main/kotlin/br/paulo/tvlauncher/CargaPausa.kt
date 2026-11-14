package br.paulo.tvlauncher

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Carga do player da TV ao vivo com espaço para a pausa curta (ideia 18; conta e teto em PausaAoVivo).
 *
 * É o DefaultLoadControl do Media3 com o buffer à frente podendo ir até o teto da pausa (2 min e tetoBytes), mas
 * que só passa dos 50 s de sempre enquanto `segurando` (pausado ou tocando atrasado). Fora disso o uso de memória é
 * o mesmo de antes, inclusive no "TV de volta". Nada vai para o disco: o alocador é o de memória do Media3.
 * Todos os métodos que o DefaultLoadControl implementa são repassados à mão (não confiar em delegação de default).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CargaPausa(val tetoBytes: Long) : LoadControl {
    @Volatile var segurando = false   // escrito na thread da tela, lido na thread de reprodução do Media3

    private val base: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(PausaAoVivo.NORMAL_MS.toInt(), PausaAoVivo.TETO_MS.toInt(),
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        .setTargetBufferBytes(tetoBytes.toInt())
        .build()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val quer = base.shouldContinueLoading(parameters)   // sempre chama: o DefaultLoadControl guarda estado aqui
        return quer && (segurando || parameters.bufferedDurationUs < PausaAoVivo.NORMAL_MS * 1000)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = base.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

/**
 * Estado da pausa curta do canal que está tocando (ideia 18). A PlayerActivity só chama: novaCarga (ao criar o
 * player), descartar (a cada tocar: troca de canal, nova tentativa, TV de volta, voltar ao vivo), pausar e continuar.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PausaCurta {
    private var carga: CargaPausa? = null
    private var pausadoEm = 0L       // elapsedRealtime da pausa; 0 = não está pausado
    private var bufferNaPausa = 0L   // quanto já havia à frente na memória quando pausou
    private var tetoMs = PausaAoVivo.TETO_MS
    var atrasoMs = 0L; private set   // quanto o que toca está atrás do ao vivo (0 = ao vivo)

    val pausado get() = pausadoEm > 0
    val ativa get() = pausado || atrasoMs > 0

    /** LoadControl do player novo, com o teto de bytes calculado pelo heap real deste processo. */
    fun novaCarga(): CargaPausa = CargaPausa(PausaAoVivo.tetoBytes(Runtime.getRuntime().maxMemory())).also { carga = it }

    fun descartar() { pausadoEm = 0; atrasoMs = 0; carga?.segurando = false }

    /** Pausa e devolve em quantos ms, ainda pausado, o buffer chega ao teto (a tela solta a memória nessa hora). */
    fun pausar(p: androidx.media3.exoplayer.ExoPlayer): Long {
        val c = carga ?: return 0
        tetoMs = PausaAoVivo.tetoMs(PausaAoVivo.bitrate(p.videoFormat?.bitrate ?: 0, p.audioFormat?.bitrate ?: 0), c.tetoBytes)
        bufferNaPausa = p.totalBufferedDuration
        pausadoEm = android.os.SystemClock.elapsedRealtime()
        c.segurando = true
        p.playWhenReady = false
        return PausaAoVivo.estouraEm(bufferNaPausa, tetoMs)
    }

    /** Continua atrasado (true) ou, se a pausa não coube na memória, descarta (false: a tela volta ao ao vivo). */
    fun continuar(p: androidx.media3.exoplayer.ExoPlayer): Boolean {
        val pausou = android.os.SystemClock.elapsedRealtime() - pausadoEm
        val d = PausaAoVivo.decidir(pausou, bufferNaPausa, p.totalBufferedDuration, tetoMs)
        if (d == PausaAoVivo.Decisao.VOLTAR_AO_VIVO) { descartar(); return false }
        pausadoEm = 0
        atrasoMs += pausou
        p.playWhenReady = true
        return true
    }
}

package br.paulo.tvlauncher.input

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.listen.CorrenteComFone
import br.paulo.tvlauncher.listen.ProcessadorFone
import br.paulo.tvlauncher.player.AssAjustes
import br.paulo.tvlauncher.player.MixDialogo
import br.paulo.tvlauncher.player.PlayerAcessivel
import br.paulo.tvlauncher.player.ProcessadorDialogo
import br.paulo.tvlauncher.player.RenderizadoresPlayer
import br.paulo.tvlauncher.player.SegundaLegenda

/**
 * Volume parecido e voz mais clara (14/11/2026) num efeito só do Android, o DynamicsProcessing, preso à sessão de áudio
 * do player: equalizador da voz (PlayerAcessivel.FAIXAS_VOZ) -> compressor (PlayerAcessivel.COMPRESSOR) -> limitador.
 *
 * Por que funciona no Chromecast da Sala, onde o volume do Android é fixo (saída HDMI 25/25): o efeito mexe no SINAL
 * digital dentro do mixer, antes de sair pelo HDMI; não mexe no volume. Conferido em 14/11 (dumpsys media.audio_flinger):
 * a biblioteca dynamics_processing está carregada e o áudio do player passa pelo mixer em PCM 16 bits.
 *
 * Cuidado: áudio Dolby/DTS enviado direto para a TV (passthrough) não passa pelo mixer, e nenhum efeito age nele. Por
 * isso, com alguma chave ligada, o player decodifica tudo em PCM ([fabrica]).
 */
class EfeitosSom {
    private var efeito: DynamicsProcessing? = null
    private var sessao = 0

    /** Prende (ou troca) o efeito na sessão de áudio do player, conforme as chaves. Desligadas: solta tudo. */
    fun aplicar(sessaoAudio: Int, volumeIgual: Boolean, vozClara: Boolean) {
        soltar()
        if (!PlayerAcessivel.precisaEfeito(volumeIgual, vozClara) || sessaoAudio == 0 || !disponivel()) return
        try {
            val nVoz = PlayerAcessivel.FAIXAS_VOZ.size
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CANAIS,
                vozClara, nVoz, volumeIgual, 1, false, 0, true).build()
            val dp = DynamicsProcessing(0, sessaoAudio, cfg)
            if (vozClara) {
                val eq = DynamicsProcessing.Eq(true, true, nVoz)
                PlayerAcessivel.FAIXAS_VOZ.forEachIndexed { i, f -> eq.setBand(i, DynamicsProcessing.EqBand(true, f.ateHz, f.ganhoDb)) }
                dp.setPreEqAllChannelsTo(eq)
            }
            if (volumeIgual) {
                val c = PlayerAcessivel.COMPRESSOR
                val mbc = DynamicsProcessing.Mbc(true, true, 1)
                mbc.setBand(0, DynamicsProcessing.MbcBand(true, 20_000f, c.ataqueMs, c.soltarMs, c.razao, c.limiarDb,
                    c.joelhoDb, -90f, 1f, 0f, c.ganhoDepoisDb))
                dp.setMbcAllChannelsTo(mbc)
            }
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, PlayerAcessivel.LIMITE_DB, 0f))
            dp.enabled = true
            efeito = dp; sessao = sessaoAudio
            Log.i(TAG, "efeito ligado: sessão $sessaoAudio volume=$volumeIgual voz=$vozClara ativo=${dp.enabled}")
        } catch (e: Exception) {
            Log.w(TAG, "efeito não pôde ser criado: $e")   // aparelho sem o efeito ou sessão já fechada: som normal
            soltar()
        }
    }

    fun soltar() {
        try { efeito?.release() } catch (_: Exception) {}
        efeito = null; sessao = 0
    }

    companion object {
        private const val TAG = "TvLauncher.Som"
        private const val CANAIS = 2   // o mixer da Sala é estéreo (AudioOut_D, 2 canais); 5.1 chega já mixado

        /** O aparelho tem o DynamicsProcessing (Android 9+ com a biblioteca)? Sem ele, as duas chaves nem aparecem. */
        fun disponivel(): Boolean = try {
            AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING } == true
        } catch (_: Exception) { false }

        /**
         * As chaves de PlayerAcessivel. MODE_MULTI_PROCESS: as Configurações gravam no processo principal e o player roda
         * no processo :player; sem isso, um player já aberto continuaria com o valor velho guardado na memória.
         */
        @Suppress("DEPRECATION")
        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PlayerAcessivel.PREFS, Context.MODE_MULTI_PROCESS)
        fun ligado(ctx: Context, chave: String) = prefs(ctx).getBoolean(chave, false)
        /** Inverte a chave e devolve o valor novo. */
        fun alternar(ctx: Context, chave: String): Boolean = (!ligado(ctx, chave)).also { prefs(ctx).edit().putBoolean(chave, it).apply() }
        fun definir(ctx: Context, chave: String, valor: Boolean) = prefs(ctx).edit().putBoolean(chave, valor).apply()
        fun volumeIgual(ctx: Context) = ligado(ctx, PlayerAcessivel.VOLUME_IGUAL)
        fun vozClara(ctx: Context) = ligado(ctx, PlayerAcessivel.VOZ_CLARA)

        fun dialogoAlto(ctx: Context) = ligado(ctx, MixDialogo.PREF)
        /** Nome da caixa Bluetooth ligada (a saída que costuma atrasar o som), ou null. */
        fun caixaBluetooth(ctx: Context): String? = try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.productName?.toString()
        } catch (_: Exception) { null }
        fun atrasoMs(ctx: Context) = prefs(ctx).getInt(RenderizadoresPlayer.chaveAtraso(caixaBluetooth(ctx)), 0)
        fun gravarAtraso(ctx: Context, ms: Int) = prefs(ctx).edit().putInt(RenderizadoresPlayer.chaveAtraso(caixaBluetooth(ctx)), ms).apply()

        /**
         * Fábrica de renderizadores do player: com volume parecido, voz clara ou diálogo mais alto ligados, o áudio sempre
         * vira PCM no aparelho (sem passthrough de Dolby/DTS para a TV), senão o efeito não alcançaria o som.
         * [ass] = ajustes da legenda ASS (só a tela de filmes passa; a TV ao vivo não desenha ASS).
         */
        @OptIn(UnstableApi::class)
        fun fabrica(ctx: Context, ass: AssAjustes? = null, segunda: Pair<SegundaLegenda, androidx.media3.exoplayer.text.TextOutput>? = null): DefaultRenderersFactory {
            val dialogo = dialogoAlto(ctx)
            val pcm = PlayerAcessivel.precisaEfeito(volumeIgual(ctx), vozClara(ctx)) || dialogo
            // RenderizadoresPlayer (15/11/2026, FFmpeg LGPL + libass, no lugar da NextLib): a TV não tem decodificador de
            // Dolby (AC3/E-AC3), DTS, TrueHD nem MP2 (conferido em dumpsys media.player). O chip da TV continua primeiro;
            // o FFmpeg só entra no que a TV não toca (ou quando o PCM acima é obrigatório). Vídeo em software fica como
            // último recurso (a Sala, 32 bits, não aguenta 1080p assim). A ASS sai completa pelo libass.
            return object : RenderizadoresPlayer(ctx, atrasoMs(ctx), ass, segunda) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
                    val b = DefaultAudioSink.Builder(context).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(CorrenteComFone(DefaultAudioSink.DefaultAudioProcessorChain(SpeechTap(), ProcessadorDialogo(dialogo)), ProcessadorFone()))
                    return if (!pcm) b.setEnableFloatOutput(enableFloatOutput).build()
                    else b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).setEnableFloatOutput(false).build()
                }
            }
        }
    }
}

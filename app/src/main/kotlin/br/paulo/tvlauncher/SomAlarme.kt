package br.paulo.tvlauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Os sons do despertador: 3 arquivos WAV dentro do app (assets/alarmes/), gerados por nós.
 * Não dependem de toques do sistema (esta TV não tem nenhum).
 *
 * Toca como USAGE_MEDIA de propósito: no `dumpsys audio` desta TV o stream de ALARME sai pelo "speaker" (volume 1 de 7,
 * sem alto-falante nenhum), enquanto a MÍDIA sai pelo HDMI (volume 7). Por isso o bipe antigo saía mudo.
 */
object SomAlarme {
    /**
     * O 4º "som" (ideia 30, 14/11/2026): acordar com o último canal da TV ao vivo (uma rádio da lista também serve). A tela
     * do alarme toca só o áudio do canal, com a mesma rampa; se o canal não abrir em 20 s, cai no som suave.
     */
    const val CANAL = 3
    val NOMES: List<String> get() = listOf(txt(R.string.som_suave), txt(R.string.som_classico), txt(R.string.som_sino), txt(R.string.som_canal))
    private val ARQUIVOS = listOf("alarmes/suave.wav", "alarmes/classico.wav", "alarmes/sino.wav")

    /** Rampa de volume: segundos para ir do mínimo ao máximo (0 = já toca no máximo). */
    val RAMPAS = listOf(0, 60, 120)
    fun rampaTexto(seg: Int) = when (seg) { 0 -> txt(R.string.som_direto); 60 -> txt(R.string.som_rampa1); else -> txt(R.string.som_rampa2) }

    /** Cria o player em loop, ainda não iniciado (chame start()). null se o arquivo falhar. [CANAL] = o suave (prévia e plano B). */
    fun criar(ctx: Context, som: Int): MediaPlayer? = try {
        val afd = ctx.assets.openFd(ARQUIVOS[if (som == CANAL) 0 else som.coerceIn(0, ARQUIVOS.size - 1)])
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            isLooping = true
            prepare()
        }
    } catch (e: Exception) {
        Log.w("TvLauncher.Alarme", "som $som falhou: $e")
        null
    }
}

package br.paulo.tvlauncher.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Fontes de mídia do player de filmes (15/11/2026), no lugar do FfmpegSubtitleExtractorsFactory/withFfmpegSubtitles
 * da NextLib:
 *  - legenda ASS dentro do arquivo: o Media3 a entrega crua ao AssRenderer (LeitoresLegendaSemAss);
 *  - legenda ASS de fora (.ass ao lado do vídeo ou baixada): o arquivo inteiro vira uma amostra só, que o AssRenderer
 *    reconhece pelo "[Script Info]" e passa ao libass;
 *  - formatos antigos (AVI, WMV, RMVB...): o FfExtrator entra por último, só quando o Media3 não reconhece o arquivo.
 */
@OptIn(UnstableApi::class)
object FontesPlayer {
    fun fabrica(dados: DataSource.Factory): MediaSource.Factory {
        val extratores = FfExtratores(DefaultExtractorsFactory(), dados)
        val base = DefaultMediaSourceFactory(dados, extratores).setSubtitleParserFactory(LeitoresLegendaSemAss())
        return ComAssDeFora(base, dados)
    }

    private class ComAssDeFora(private val base: MediaSource.Factory, private val dados: DataSource.Factory) : ForwardingMediaSourceFactory(base) {
        override fun createMediaSource(item: MediaItem): MediaSource {
            val todas = item.localConfiguration?.subtitleConfigurations.orEmpty()
            val (ass, outras) = todas.partition { it.mimeType == MimeTypes.TEXT_SSA }
            if (ass.isEmpty()) return base.createMediaSource(item)
            val principal = base.createMediaSource(item.buildUpon().setSubtitleConfigurations(outras).build())
            val fora = ass.map { SingleSampleMediaSource.Factory(dados).createMediaSource(it, C.TIME_UNSET) }
            return MergingMediaSource(principal, *fora.toTypedArray())
        }
    }
}

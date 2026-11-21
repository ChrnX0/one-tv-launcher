package br.paulo.tvlauncher.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

/**
 * Ideia 6 (15/11/2026): duas legendas ao mesmo tempo (por exemplo, português embaixo e a original em cima).
 *
 * O Media3 escolhe UMA faixa de legenda por vez. O truque: um segundo renderizador de texto que se apresenta com um
 * tipo próprio ([TIPO]) e só aceita a faixa escolhida como segunda ([SegundaLegenda.id]). Assim o mapeamento manda
 * essa faixa para ele, a escolha normal continua com a primeira legenda, e o [Seletor] liga a segunda à parte.
 * ASS não entra como segunda (o libass desenha a tela inteira; duas ASS se sobreporiam).
 */
@OptIn(UnstableApi::class)
class SegundaLegenda {
    companion object {
        /** Tipo de faixa próprio (acima dos do Media3): as regras de "legenda" do seletor não mexem nele. */
        const val TIPO = C.TRACK_TYPE_CUSTOM_BASE + 6

        /** Formatos aceitos como segunda legenda: texto (SRT, WebVTT, TTML, CEA) e imagem que o Media3 lê (PGS, VobSub, DVB). */
        fun aceita(f: Format): Boolean {
            // Legenda já lida na extração chega como "application/x-media3-cues" com o tipo original em `codecs`.
            val original = if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f.sampleMimeType
            return original != MimeTypes.TEXT_SSA && (MimeTypes.isText(original) || f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES)
        }
    }

    /** id (Format.id) da faixa escolhida como segunda, ou null = desligada. Lido pela thread do player. */
    @Volatile var id: String? = null

    /** O renderizador da segunda legenda: um TextRenderer comum por dentro, com outro "tipo" e filtro por id. */
    fun renderizador(saida: TextOutput, looper: Looper): Renderer = Renderizador(TextRenderer(saida, looper))

    private inner class Renderizador(private val texto: TextRenderer) : ForwardingRenderer(texto) {
        private val capacidades = object : RendererCapabilities by texto {
            override fun getTrackType() = TIPO
            override fun getName() = "SegundaLegenda"
            override fun supportsFormat(f: Format): Int {
                val alvo = id
                return if (alvo != null && f.id == alvo && aceita(f)) texto.supportsFormat(f)
                else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
        }
        override fun getTrackType() = TIPO
        override fun getCapabilities(): RendererCapabilities = capacidades
    }

    /** Seletor de faixas que, além da escolha normal, liga a segunda legenda no renderizador dela. */
    class Seletor(ctx: Context, private val segunda: SegundaLegenda) : DefaultTrackSelector(ctx) {
        /** Pede uma nova escolha de faixas (depois de mudar [SegundaLegenda.id]). */
        fun atualizar() = invalidate(parameters)

        override fun selectAllTracks(definicoes: Array<ExoTrackSelection.Definition?>, info: MappingTrackSelector.MappedTrackInfo,
                                     suportes: Array<Array<IntArray>>, adaptacao: IntArray, params: Parameters) {
            super.selectAllTracks(definicoes, info, suportes, adaptacao, params)
            val alvo = segunda.id ?: return
            for (r in 0 until info.rendererCount) {
                if (info.getRendererType(r) != TIPO) continue
                val grupos = info.getTrackGroups(r)
                for (g in 0 until grupos.length) {
                    val grupo = grupos[g]
                    for (t in 0 until grupo.length) {
                        if (grupo.getFormat(t).id == alvo && suportes[r][g][t] and 0b111 == C.FORMAT_HANDLED) {
                            definicoes[r] = ExoTrackSelection.Definition(grupo, t)
                            return
                        }
                    }
                }
            }
        }
    }
}

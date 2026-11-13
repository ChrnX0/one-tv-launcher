package br.paulo.tvlauncher

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import java.io.File

/**
 * A parte Android do Idioma (11/11/2026): onde a preferência mora e como ela vira escolha de faixa no Media3.
 * A decisão em si (qual faixa, qual versão) é do Idioma, puro e testado.
 */
object IdiomaPlayer {
    private const val ARQUIVO = "idioma.json"
    /** Id da faixa de legenda baixada (LegendaBaixar), para reconhecê-la entre as faixas do vídeo. */
    const val ID_EXTERNA = "legenda-baixada"

    // Arquivo (e não SharedPreferences): as Configurações gravam no processo da HOME e o player lê no ":player".
    // SharedPreferences guarda cópia em memória por processo e não veria a troca.
    fun prefs(ctx: Context): Idioma.Prefs =
        Idioma.deJson(runCatching { File(PlayerArmazem.pasta(ctx), ARQUIVO).readText() }.getOrNull())

    @Synchronized
    fun gravar(ctx: Context, p: Idioma.Prefs) {
        runCatching {
            val tmp = File(PlayerArmazem.pasta(ctx), "$ARQUIVO.tmp")
            tmp.writeText(Idioma.paraJson(p))
            tmp.renameTo(File(PlayerArmazem.pasta(ctx), ARQUIVO))
        }
    }

    fun definirPadrao(ctx: Context, pref: Idioma.Pref) = gravar(ctx, prefs(ctx).copy(padrao = pref))

    fun lembrar(ctx: Context, chave: String, pref: Idioma.Pref) = gravar(ctx, Idioma.lembrar(prefs(ctx), chave, pref))

    fun rotulo(ctx: Context, p: Idioma.Pref): String =
        ctx.getString(if (p == Idioma.Pref.ORIGINAL) R.string.idioma_original else R.string.idioma_dublado)

    fun faixa(f: Format) = Idioma.Faixa(f.language, padrao = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
        forcada = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0, rotulo = f.label, externa = f.id?.contains(ID_EXTERNA) == true)

    /** Faixas de um tipo, achatadas (grupo + índice dentro do grupo), só as que o aparelho toca. */
    private fun faixas(t: Tracks, tipo: Int): List<Pair<Tracks.Group, Int>> =
        t.groups.filter { it.type == tipo }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

    /**
     * Aplica a preferência quando as faixas chegam: escolhe o áudio e liga (ou não) a legenda em português.
     * Devolve os parâmetros novos, ou null se não há o que mudar.
     */
    fun aplicar(t: Tracks, atual: TrackSelectionParameters, pref: Idioma.Pref): TrackSelectionParameters? {
        val audios = faixas(t, C.TRACK_TYPE_AUDIO)
        val b = atual.buildUpon()
        var mudou = false
        val escolhido = Idioma.escolherAudio(audios.map { (g, i) -> faixa(g.getTrackFormat(i)) }, pref)?.let { audios[it] }
        if (escolhido != null && !escolhido.first.isTrackSelected(escolhido.second)) {
            b.setOverrideForType(TrackSelectionOverride(escolhido.first.mediaTrackGroup, escolhido.second)); mudou = true
        }
        val audio = escolhido ?: audios.firstOrNull { (g, i) -> g.isTrackSelected(i) }
        // Sem faixa de áudio identificada (ainda), não mexe na legenda: decidir às cegas ligaria legenda em filme dublado.
        if (audio == null) return if (mudou) b.build() else null
        val audioPt = Idioma.ePortugues(faixa(audio.first.getTrackFormat(audio.second)))
        val textos = faixas(t, C.TRACK_TYPE_TEXT)
        val leg = Idioma.escolherLegenda(textos.map { (g, i) -> faixa(g.getTrackFormat(i)) }, audioPt)?.let { textos[it] }
        if (leg != null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(leg.first.mediaTrackGroup, leg.second))
            mudou = true
        }
        return if (mudou) b.build() else null
    }
}

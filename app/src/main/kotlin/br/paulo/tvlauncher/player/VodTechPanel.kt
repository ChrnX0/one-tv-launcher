package br.paulo.tvlauncher.player

import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.VodActivity
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_FONTE
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_PROXIMO_EP
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_TITULO
import br.paulo.tvlauncher.VodActivity.Companion.TIPO_EPISODIO
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.EfeitosSom
import br.paulo.tvlauncher.input.SpeechTap
import br.paulo.tvlauncher.panel.Pastas

/** Player de filmes/séries: quadro técnico (ideia 8). Saiu da VodActivity na refatoração de 21/11/2026. */

internal fun VodActivity.alternarInfo() {
    val ligou = EfeitosSom.alternar(this, InfoTecnica.PREF)
    tela.removeCallbacks(tiqueInfo)
    if (ligou) tela.post(tiqueInfo) else { infoTec.visibility = View.GONE; legenda2.translationY = 0f }
}

/** O vídeo e, se houver, a legenda baixada junto (o Media3 a trata como mais uma faixa de legenda). */
internal fun VodActivity.item(u: String): MediaItem {
    val b = MediaItem.Builder().setUri(u)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITULO).orEmpty()).build())
    val legendas = ArrayList<MediaItem.SubtitleConfiguration>()
    legendaArquivo?.let { f ->
        legendas += MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(f))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(Idioma.tagLegendaBaixada(idiomaTv)).setId(IdiomaPlayer.ID_EXTERNA)
            .apply { if (legendaTraduzida) setLabel(getString(R.string.legenda_trad_rotulo)) }
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
    }
    // As da pasta entram como faixas comuns: o ▼ mostra todas, e a escolha automática (IdiomaPlayer) vale para elas.
    legendasAoLado.forEachIndexed { i, (endereco, a) ->
        legendas += MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(endereco)).setMimeType(a.mime)
            .setLanguage(a.idioma).setLabel(a.nome).setId("aolado:$i").build()
    }
    if (legendas.isNotEmpty()) b.setSubtitleConfigurations(legendas)
    return b.build()
}

/**
 * Legendas na mesma pasta do vídeo (pastas da rede, nuvem e pendrive). Fora da thread da tela: lista a pasta uma vez.
 * O endereço volta no mesmo formato do vídeo (arq://, smb:// ou file://), que a FontePastas já sabe abrir.
 */
internal fun VodActivity.acharAoLado(u: String): List<Pair<String, LegendaAoLado.Achada>> {
    Pastas.lerUri(u)?.let { (id, caminho) ->
        val conta = MidiaArmazem.conta(this, id) ?: return emptyList()
        val pasta = caminho.substringBeforeLast('/', "")
        val nomes = Pastas.abrir(conta).use { p -> p.listar(pasta).filter { !it.pasta }.map { it.nome } }
        return LegendaAoLado.achar(caminho.substringAfterLast('/'), nomes).map { a -> Pastas.uri(conta, MidiaContas.juntar(pasta, a.nome)) to a }
    }
    if (Pastas.arquivoLocalPermitido(u)) {
        val f = java.io.File(android.net.Uri.parse(u).path ?: return emptyList())
        val dir = f.parentFile ?: return emptyList()
        val nomes = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        return LegendaAoLado.achar(f.name, nomes).map { a -> android.net.Uri.fromFile(java.io.File(dir, a.nome)).toString() to a }
    }
    return emptyList()
}

/** Troca a legenda com o vídeo andando: refaz o item no mesmo ponto (o Media3 não aceita faixa nova no meio). */
internal fun VodActivity.recarregarComLegenda() {
    val p = player ?: return
    val u = url ?: return
    val pos = p.currentPosition
    legendaDecidida = false
    SpeechTap.proximaPosicao(pos)
    p.setMediaItem(item(u), pos)
    p.prepare()
}

/** Elevação 6.4: a velocidade e a legenda que a pessoa escolheu nesta série voltam sozinhas no episódio seguinte. */
internal fun VodActivity.aplicarMemoriaSerie(p: ExoPlayer, faixas: Tracks) {
    val m = memSerie ?: return
    MemoriaSerie.velocidadeValida(m.velocidade, PlayerImagem.VELOCIDADES.toList())?.let { if (it != p.playbackParameters.speed) p.setPlaybackSpeed(it) }
    if (legendaArquivo != null) return   // legenda baixada para este episódio: ela manda
    val textos = faixas.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
    when (val i = MemoriaSerie.legenda(m, textos.map { (g, j) -> g.getTrackFormat(j).language })) {
        null -> {}
        -1 -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else -> { val (g, j) = textos[i]
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, j)).build() }
    }
}

internal fun VodActivity.lembrarSerie(f: (MemoriaSerie.Escolha) -> MemoriaSerie.Escolha) {
    if (!serie) return
    val k = chaveIdioma
    memSerie = f(memSerie ?: MemoriaSerie.Escolha())
    Fundo.rodar("memoria-serie") { MemoriaSerieTv.lembrar(this, k, f) }
}

internal fun VodActivity.prepararProximo() {
    if (preparouProximo || !serie) return
    val p = player ?: return
    if (!FimDoVideo.horaDePreparar(p.currentPosition, p.duration)) return
    preparouProximo = true
    val (id, t, e) = FimDoVideo.lerProximo(intent.getStringExtra(EXTRA_PROXIMO_EP)) ?: return
    val fonte = intent.getStringExtra(EXTRA_FONTE).orEmpty()
    val b = runCatching { buscaLegenda() }.getOrNull() ?: return
    val k = VodActivity.chave(TIPO_EPISODIO, fonte, id)
    val legenda = legendaArquivo != null && !legendaTraduzida
    Fundo.rodar("proximo-ep") {
        b.tmdb?.let { runCatching { AberturaProntaTv.buscar(this, it, t, e) } }
        if (legenda && LegendaBaixar.guardada(this, k) == null && !LegendaBaixar.tentouAuto(this, k) && LegendaBaixar.contaEmUso(this) != null) {
            val r = LegendaBaixar.automatica(this, k, null, b.copy(temporada = t, episodio = e))
            android.util.Log.i("TvLauncher.Vod", "próximo episódio T${t}E$e: legenda ${if (r.isSuccess) "pronta" else "não achada"}")
        }
    }
}

internal fun VodActivity.salvar(terminou: Boolean = false) {
    if (saiuPelaTroca || (concluido && !terminou)) return
    val p = player ?: return
    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    val ms = if (terminou) dur else p.currentPosition
    if (ms <= 0 && !terminou) return
    if (!terminou) avisar(FontesMidia.Momento.TOCANDO, ms, pausado = !p.isPlaying)
    val agora = System.currentTimeMillis()
    // definirPosicao é @Synchronized: o salvar(terminou) do fim do vídeo e o do onStop logo depois correm em threads
    // diferentes do Fundo; ler-trocar-gravar sem trava deixava um apagar o outro.
    Fundo.rodar { PlayerArmazem.definirPosicao(this, chave, Posicao(ms, dur, agora)) }
}

/** Conta ao servidor (Jellyfin, Emby, Plex), se o vídeo é de lá, onde está. Fora da thread da tela; erro é ignorado. */
internal fun VodActivity.avisar(momento: FontesMidia.Momento, ms: Long, pausado: Boolean = false) {
    if (momento == FontesMidia.Momento.PAROU && !avisouInicio) return
    val dur = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    FontesMidia.informar(this, relato, momento, ms, dur, pausado)
    if (momento == FontesMidia.Momento.PAROU) contarFora(ServicosSync.Momento.PAROU, ms)
}

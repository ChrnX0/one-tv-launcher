package br.paulo.tvlauncher.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Tracks
import br.paulo.tvlauncher.Desligamento
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Gatilhos
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.VodActivity
import br.paulo.tvlauncher.VodActivity.Companion.ATRASOS
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_LEG_ANO
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_LEG_EP
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_LEG_NOME
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_LEG_TEMP
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_LEG_VERSAO
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_TITULO
import br.paulo.tvlauncher.VodActivity.Companion.EXTRA_TMDB
import br.paulo.tvlauncher.VodActivity.Companion.LEGENDA_AUTO
import br.paulo.tvlauncher.VodActivity.Companion.LEGENDA_TRADUZIR
import br.paulo.tvlauncher.VodActivity.Companion.RESULTADO_DESLIGOU
import br.paulo.tvlauncher.VodActivity.Companion.RESULTADO_PROXIMO_JA
import br.paulo.tvlauncher.VodActivity.Companion.RESULTADO_TECLA
import br.paulo.tvlauncher.VodActivity.Companion.RESULTADO_TERMINOU
import br.paulo.tvlauncher.input.EfeitosSom
import java.util.Locale

/** Player de filmes/séries: pular abertura (ideia 24) e Trakt/Simkl (fatia 3). Saiu da VodActivity na refatoração de 21/11/2026. */

/** Marca o início ou o fim da abertura na posição atual e guarda na série (prefs "player", "abertura:<série>"). */
internal fun VodActivity.marcarAbertura(inicio: Boolean) {
    val pos = player?.currentPosition ?: return
    val nova = if (inicio) AberturaSerie.marcarInicio(abertura, pos) else AberturaSerie.marcarFim(abertura, pos)
    if (nova == null) { avisoBreve(getString(R.string.abertura_falta_inicio)); return }
    if (!inicio && !AberturaSerie.valida(nova)) { avisoBreve(getString(R.string.abertura_invalida)); return }
    abertura = nova; aberturaDoArquivo = false
    gravarAbertura(nova)
    avisouPulo = true; avisadoTrecho = nova.fim   // quem acabou de marcar está no trecho: não oferece pular agora
    avisoBreve(if (AberturaSerie.valida(nova)) getString(R.string.abertura_salva, relogio(nova.ini), relogio(nova.fim))
        else getString(R.string.abertura_inicio_marcado, relogio(nova.ini)))
    vigiarAbertura()
}

internal fun VodActivity.apagarAbertura() {
    abertura = null
    gravarAbertura(null)
    tela.removeCallbacks(vigiaAbertura); esconderPilula()
    avisoBreve(getString(R.string.abertura_apagada))
}

internal fun VodActivity.gravarAbertura(m: AberturaSerie.Marca?) {
    val k = AberturaSerie.chave(chaveIdioma)
    Fundo.rodar {
        val e = getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).edit()
        if (m == null) e.remove(k) else e.putString(k, AberturaSerie.paraTexto(m))
        e.apply()
    }
}

/**
 * O vídeo acabou, ou a pessoa escolheu "Próximo episódio" nos créditos ([proximoJa]: a tela de séries começa o
 * próximo sem contar de novo). Nos dois casos o episódio conta como visto.
 */
internal fun VodActivity.terminar(proximoJa: Boolean) {
    if (isFinishing) return
    esconderPilula()
    salvar(terminou = true)
    concluido = true
    Gatilhos.avisarDoPlayer(this@terminar, false, acabou = true)   // elevação 10.6: filme acabou
    // Posição = duração: o servidor marca como visto e tira do "Continuar assistindo".
    player?.let { p -> avisar(FontesMidia.Momento.PAROU, p.duration.takeIf { it > 0 } ?: p.currentPosition) }
    avisouInicio = false   // o fim já foi avisado; o onStop não repete
    // A tela de séries usa isto para decidir o próximo episódio (FimDoVideo.depois).
    setResult(android.app.Activity.RESULT_OK, Intent().putExtra(RESULTADO_TERMINOU, true).putExtra(RESULTADO_TECLA, apertouTecla)
        .putExtra(RESULTADO_DESLIGOU, desligarAoFim).putExtra(RESULTADO_PROXIMO_JA, proximoJa))
    // Ideia 19: "desligar quando este acabar". Este processo (:player) não tem o serviço de acessibilidade;
    // o apagarTela cai no lockNow do Device Owner, que só apaga a tela (nada se perde).
    if (desligarAoFim) Desligamento.apagarTela(applicationContext)
    player?.pause()
    finish()
    // Próximo episódio já escolhido: sai sem animação, para a ficha da série não aparecer entre os dois (a série
    // cobre a tela com o mesmo fundo até o próximo player abrir).
    @Suppress("DEPRECATION") if (proximoJa) overridePendingTransition(0, 0)
}

/**
 * Confere a posição duas vezes por segundo enquanto há algo a oferecer: pular o resumo ou a abertura, ou, nos
 * créditos de um episódio com próximo, "Próximo episódio" (proposta C, etapa 1, 20/11/2026).
 */
internal fun VodActivity.vigiarAbertura() {
    tela.removeCallbacks(vigiaAbertura)
    val pular = (serie || aberturaDoArquivo) && (AberturaSerie.valida(abertura) || AberturaSerie.valida(resumo))
    if (pular || proximoContagem >= 0) tela.post(vigiaAbertura)
}

/** "Próximo episódio" nos créditos, com a contagem das Configurações (0 = só oferece). */
internal fun VodActivity.mostrarProximo() {
    pilulaProximo = true; pularPara = -1L
    tela.removeCallbacks(esconderPilulaDepois)
    proximoResta = if (proximoContagem > 0) proximoContagem else -1
    pintarProximo()
    pilulaPular.visibility = View.VISIBLE
    if (proximoResta > 0) tela.postDelayed(contaProximo, 1_000)
}

internal fun VodActivity.pintarProximo() {
    pilulaPular.text = if (proximoResta > 0) getString(R.string.seg_proximo_em, proximoResta) else getString(R.string.seg_proximo)
}

internal fun VodActivity.esconderPilula() {
    tela.removeCallbacks(esconderPilulaDepois); tela.removeCallbacks(contaProximo)
    pilulaProximo = false; pularPara = -1L
    if (pilulaPularPronta) pilulaPular.visibility = View.GONE
}

internal fun VodActivity.buscaLegenda(): LegendaExterna.Busca {
    val nome = intent.getStringExtra(EXTRA_LEG_NOME)?.takeIf { it.isNotBlank() }
        ?: LegendaExterna.nomeDeBusca(intent.getStringExtra(EXTRA_TITULO).orEmpty().substringBefore(" · "))
    fun n(k: String) = intent.getIntExtra(k, 0).takeIf { it > 0 }
    val versao = intent.getStringExtra(EXTRA_LEG_VERSAO)?.let { v -> Idioma.Versao.entries.firstOrNull { it.name == v } }
        ?: Idioma.Versao.DESCONHECIDA
    return LegendaExterna.Busca(nome, n(EXTRA_LEG_ANO), n(EXTRA_LEG_TEMP), n(EXTRA_LEG_EP), n(EXTRA_TMDB), versao = versao)
}

internal fun VodActivity.contarFora(m: ServicosSync.Momento, ms: Long) {
    val (item, ep) = alvoFora ?: return
    when (m) {
        ServicosSync.Momento.PAROU -> { if (paradoFora) return; paradoFora = true }
        ServicosSync.Momento.PAUSOU -> if (paradoFora) return
        ServicosSync.Momento.COMECOU -> paradoFora = false
    }
    val dur = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    val concluido = Vod.finalizado(Posicao(ms, dur, 0), episodio = ep != null)
    val app = applicationContext
    Fundo.rodar("contar-fora") { ServicosSync.assistindo(app, item, ep, m, ms, dur, concluido) }
}

/** Começou do zero aqui, mas parou no meio no celular: pula para lá (uma vez, no começo). */
internal fun VodActivity.continuarDeFora() {
    val (item, ep) = alvoFora ?: return
    if (retomarEm > 0) return
    val app = applicationContext; val ch = chave
    Fundo.rodar("pausa-fora") {
        val prog = ServicosSync.pausaDeFora(app, item.ids.tmdb ?: return@rodar, ep, PlayerArmazem.posicoes(app)[ch]) ?: return@rodar
        tela.post {
            val p = player ?: return@post
            val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return@post
            if (p.currentPosition > 30_000) return@post   // a pessoa já andou: não mexe
            p.seekTo(SincronizarFilmes.posicaoDaPausa(prog, dur))
            avisoBreve(getString(R.string.sync_continuando))
        }
    }
}

internal fun VodActivity.avisoBreve(t: String) {
    mostrarAviso(t); tela.removeCallbacks(esconderAvisoDepois); tela.postDelayed(esconderAvisoDepois, 4_000)
}

/**
 * Ideia 89: áudio que não é do idioma da TV e nenhuma legenda nele -> baixa sozinha a do OpenSubtitles (a que bate com
 * o arquivo, pelo hash, quando o servidor deixa ler pedaços) e liga, com um aviso. Uma tentativa por vídeo a cada 7
 * dias; desliga no menu ▼. Sem conta/chave, ou com legenda já guardada ou ao lado do vídeo, não faz nada.
 */
internal fun VodActivity.talvezLegendaAuto(t: Tracks) {
    if (autoTentada || legendaArquivo != null || legendasAoLado.isNotEmpty()) return
    if (!getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).getBoolean(LEGENDA_AUTO, true)) return
    if (!IdiomaPlayer.faltaLegenda(t, prefIdioma, idiomaTv, buscaLegenda().versao, prefAcesso.audiodescricao)) return
    autoTentada = true
    val k = chave; val u = url; val b = buscaLegenda()
    if (b.nome.isBlank()) return
    val pos = player?.currentPosition ?: 0L
    Fundo.rodar("legenda-auto") {
        if (LegendaBaixar.contaEmUso(this) == null) return@rodar
        // Tradução pela metade da última vez: vai direto para a tradução (a busca já foi feita e não achou).
        if (!LegendaTraduzida.pelaMetade(this, k)) {
            if (LegendaBaixar.tentouAuto(this, k)) return@rodar
            val r = LegendaBaixar.automatica(this, k, u, b)
            if (r.isSuccess) {
                tela.post {
                    if (isDestroyed || chave != k || legendaArquivo != null) return@post
                    r.onSuccess { (f, bate) ->
                        legendaArquivo = f; legendaTraduzida = false; atrasoLegenda = 0; atrasoManual = false; acertosAuto = 0
                        LegendaTraduzida.esquecer(this, k)
                        avisoBreve(getString(if (bate) R.string.legenda_auto_pronta else R.string.legenda_auto_pronta_nome) + " · " + LegendaExterna.CREDITO)
                        recarregarComLegenda()
                    }
                }
                return@rodar
            }
        }
        traduzirLegenda(k, u, b, pos)
    }
}

/**
 * Ideia 90: nenhuma legenda no idioma da TV. Baixa a inglesa e traduz aqui mesmo: os 10 min a partir de [pos]
 * primeiro (a legenda aparece em uns 20 s), o resto à frente da cena. Bloqueia: chamar fora da thread da tela.
 */
internal fun VodActivity.traduzirLegenda(k: String, u: String?, b: LegendaExterna.Busca, pos: Long) {
    if (!getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).getBoolean(LEGENDA_TRADUZIR, true)) return
    if (!LegendaTraduzida.suporta(idiomaTv) || pararTraducao.get()) return
    val origem = LegendaTraduzida.origem(this, k, u, b).getOrElse { return }
    tela.post { if (!isDestroyed && chave == k && legendaArquivo == null) avisoBreve(getString(R.string.legenda_trad_traduzindo)) }
    fun usar(f: java.io.File, aviso: Int) = tela.post {
        if (isDestroyed || chave != k || pararTraducao.get()) return@post
        if (legendaArquivo != null && !legendaTraduzida) return@post   // a pessoa já escolheu outra
        legendaArquivo = f; legendaTraduzida = true; atrasoLegenda = 0; atrasoManual = false; acertosAuto = 0
        avisoBreve(getString(aviso) + " · " + LegendaExterna.CREDITO)   // a original veio do OpenSubtitles
        recarregarComLegenda()
    }
    LegendaTraduzida.traduzir(this, k, origem, idiomaTv, pos, pararTraducao,
        aoPrimeiro = { f -> usar(f, R.string.legenda_trad_parcial) },
        aoFim = { f -> usar(f, R.string.legenda_trad_pronta) })
        .onFailure { android.util.Log.w("TvLauncher.Traducao", "não traduzi: ${it.javaClass.simpleName}") }
}

/** Procura no OpenSubtitles e mostra a lista (a pessoa escolhe: cada download gasta do limite diário da conta). */
internal fun VodActivity.buscarLegenda() {
    val b = buscaLegenda()
    mostrarAviso(getString(R.string.legenda_procurando))
    Fundo.rodar {
        val r = LegendaBaixar.buscar(this, b)
        tela.post {
            if (isDestroyed) return@post
            esconderAviso()
            r.onFailure { avisoBreve((it as? LegendaBaixar.Falha)?.message ?: getString(R.string.legenda_erro)) }
            r.onSuccess { lista ->
                if (lista.isEmpty()) { avisoBreve(getString(R.string.legenda_nenhuma)); return@onSuccess }
                // Nome do lançamento em cima; idioma, downloads e "feita para este arquivo" na linha de baixo (como a cruz)
                val mil = android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(),
                    android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
                val rotulos = lista.map { c ->
                    Estilo.comInfo(LegendaExterna.nomeLegivel(c.nome), listOfNotNull(
                        if (c.bateHash) "✓ " + getString(R.string.legenda_bate) else null,
                        LegendaExterna.nomeIdioma(c.idioma), "↓ " + mil.format(c.baixadas.toLong()),
                        if (c.surdos) getString(R.string.legenda_surdos) else null).joinToString(" · "))
                }
                val nome = intent.getStringExtra(EXTRA_TITULO)?.substringBefore(" · ")?.takeIf { it.isNotBlank() } ?: b.nome
                Estilo.lista(this, getString(R.string.legenda_escolher, nome), rotulos, rodape = LegendaExterna.CREDITO) { i -> baixarLegenda(lista[i]) }
            }
        }
    }
}

internal fun VodActivity.baixarLegenda(c: LegendaExterna.Candidata) {
    mostrarAviso(getString(R.string.legenda_baixando))
    val k = chave
    Fundo.rodar {
        val r = LegendaBaixar.baixar(this, k, c)
        getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).edit().remove("atraso:$k").apply()
        tela.post {
            if (isDestroyed) return@post
            r.onFailure { avisoBreve((it as? LegendaBaixar.Falha)?.message ?: getString(R.string.legenda_erro)) }
            r.onSuccess { f ->
                pararTraducao.set(true); LegendaTraduzida.esquecer(this, k)   // a escolhida pela pessoa vale mais que a tradução
                legendaArquivo = f; legendaTraduzida = false; atrasoLegenda = 0; atrasoManual = false; acertosAuto = 0; avisoBreve(getString(R.string.legenda_pronta) + " · " + LegendaExterna.CREDITO); recarregarComLegenda()
            }
        }
    }
}

internal fun VodActivity.rotuloAtraso(ms: Long): String = when {
    ms == 0L -> getString(R.string.legenda_atraso_zero)
    else -> (if (ms > 0) "+" else "−") + "%.1f s".format(Locale.getDefault(), kotlin.math.abs(ms) / 1000.0)
}

/** "Atraso" positivo: a fala aparece depois (para legenda adiantada em relação à voz). */
internal fun VodActivity.escolherAtraso(voltar: (() -> Unit)? = null) {
    val atual = ATRASOS.indexOfFirst { it == atrasoLegenda }
    Estilo.lista(this, getString(R.string.legenda_atraso_titulo), ATRASOS.map { rotuloAtraso(it) }, listOf(atual), aoVoltar = voltar) { i ->
        val ms = ATRASOS[i]; val k = chave
        atrasoManual = true   // a pessoa escolheu: o acerto automático não mexe mais neste vídeo
        Fundo.rodar {
            val f = LegendaBaixar.ajustar(this, k, ms)
            getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).edit().putLong("atraso:$k", ms).apply()
            tela.post { lembrarSerie { it.copy(atrasoLegendaMs = ms) } }
            tela.post { if (!isDestroyed && f != null) { legendaArquivo = f; atrasoLegenda = ms; recarregarComLegenda() } }
        }
    }
}

internal fun VodActivity.escolherVelocidade(voltar: (() -> Unit)? = null) {
    val p = player ?: return
    val v = PlayerImagem.VELOCIDADES
    Estilo.lista(this, getString(R.string.velocidade_titulo), v.map { PlayerImagem.rotuloVelocidade(it) },
        listOf(v.indexOfFirst { it == p.playbackParameters.speed }), aoVoltar = voltar) { i -> p.setPlaybackSpeed(v[i]); lembrarSerie { it.copy(velocidade = v[i]) } }
}

/** Cores da legenda conforme as chaves (PlayerAcessivel.legenda): padrão = branca com fundo leve, como antes. */
internal fun VodActivity.pintarLegenda() {
    val c = PlayerAcessivel.legenda(EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_FUNDO), EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_AMARELA))
    legenda.setTextColor(c.texto)
    (legenda.background as? GradientDrawable)?.setColor(c.fundo)
    if (c.sombra) legenda.setShadowLayer(8f, 0f, 2f, Color.BLACK) else legenda.setShadowLayer(0f, 0f, 0f, 0)
    // Elevação 6.5: em cima ou embaixo (a legenda em imagem, PGS/ASS, segue a posição que o próprio arquivo manda).
    val alto = EfeitosSom.ligado(this, PlayerAcessivel.LEGENDA_ALTO)
    (legenda.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
        lp.gravity = (if (alto) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        lp.setMargins(dp(96), if (alto) dp(56) else 0, dp(96), if (alto) 0 else dp(56))
        legenda.layoutParams = lp
    }
}

internal fun VodActivity.nomeEstiloLegenda(e: EstiloLegenda.Estilo) = getString(when (e) {
    EstiloLegenda.Estilo.PADRAO -> R.string.elv_leg_padrao
    EstiloLegenda.Estilo.ALTO_CONTRASTE -> R.string.elv_leg_contraste
    EstiloLegenda.Estilo.AMARELA -> R.string.elv_leg_amarela
})

/**
 * Liga/desliga volume parecido ou voz mais clara. Se mudar entre "som direto para a TV" e "som decodificado aqui"
 * (EfeitosSom.fabrica), o player é refeito no mesmo ponto; senão, só o efeito é trocado.
 */
internal fun VodActivity.alternarSom(chave: String) {
    val antes = PlayerAcessivel.precisaEfeito(EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
    val ligou = EfeitosSom.alternar(this, chave)
    val depois = PlayerAcessivel.precisaEfeito(EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
    avisoBreve(getString(when (chave) {
        PlayerAcessivel.VOLUME_IGUAL -> if (ligou) R.string.acess_volume_ligado else R.string.acess_volume_desligado
        else -> if (ligou) R.string.acess_voz_ligado else R.string.acess_voz_desligado
    }))
    val p = player ?: return
    if (antes != depois) {
        efeitos.soltar()
        sessao.soltar(); retomarEm = p.currentPosition; p.release(); player = null
        tocar()
    } else efeitos.aplicar(p.audioSessionId, EfeitosSom.volumeIgual(this), EfeitosSom.vozClara(this))
}

internal fun VodActivity.rotuloTamanho(sp: Int): String {
    val i = FimDoVideo.TAMANHOS_LEGENDA.indexOf(sp).takeIf { it >= 0 } ?: 1
    return resources.getStringArray(R.array.vod_legenda_tamanhos)[i]
}

internal fun VodActivity.escolherTamanhoLegenda(voltar: (() -> Unit)? = null) {
    val t = FimDoVideo.TAMANHOS_LEGENDA
    val atual = t.indexOf(tamLegendaSp)
    Estilo.lista(this, getString(R.string.vod_legenda_tamanho_titulo), resources.getStringArray(R.array.vod_legenda_tamanhos).toList(),
        listOf(atual), aoVoltar = voltar) { i ->
        tamLegendaSp = t[i]; legenda.textSize = t[i].toFloat(); legenda2.textSize = t[i].toFloat(); assAjustes.escala(FimDoVideo.escalaAss(t[i]))
        getSharedPreferences(Prefs.PLAYER, android.content.Context.MODE_PRIVATE).edit().putInt("legenda_sp", t[i]).apply()
    }
}

internal fun VodActivity.escolherImagem(voltar: (() -> Unit)? = null) = PlayerImagem.escolher(this, modoImagem, voltar) { m ->
    modoImagem = m
    tamanhoVideo?.let { t -> PlayerImagem.aplicar(areaVideo(), modoImagem, t.width, t.height, t.pixelWidthHeightRatio) }
}

package br.paulo.tvlauncher.listen

import android.app.Activity
import android.os.SystemClock
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.OuvirServico
import br.paulo.tvlauncher.R
import java.util.Locale

/**
 * O menu "Som" do Ouvir (21/11/2026), o mesmo no player de podcast, na prateleira Tocando dos Podcasts e na da Rádio:
 * um lugar só para velocidade, pular silêncios, voz clara, timer para dormir e os ajustes deste podcast.
 *
 * Por quê: cada ajuste era um botão solto, e os novos (voz clara, abertura/fim por podcast) não cabiam na fileira.
 * Para quê: quem ouve acha tudo do som no mesmo menu, com o valor de agora à direita de cada linha.
 * Como: [Estilo.lista] com valores; escolher uma linha muda ou abre a lista daquela opção e volta ao menu no mesmo lugar.
 */
object MenuSom {

    private enum class Linha { VELOCIDADE, SILENCIO, VOZ, DORMIR, PROPRIO, ABERTURA, FIM }

    /** Abre o menu. [aoMudar] redesenha a tela de quem chamou depois de cada mudança. */
    fun abrir(a: Activity, foco: Int = 0, aoMudar: () -> Unit) {
        val s = OuvirServico.instancia ?: return
        val e = s.episodio
        val linhas = if (e != null) Linha.entries.toList() else listOf(Linha.VOZ, Linha.DORMIR)
        val ef = OuvirServico.efetivo(a, e?.feed)
        val proprio = e?.let { OuvirServico.ajuste(a, it.feed) } != null
        fun liga(b: Boolean) = a.getString(if (b) R.string.pod_ligado else R.string.pod_desligado).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        fun segs(n: Int) = if (n <= 0) liga(false) else a.getString(R.string.som_segundos, n)
        val dormirMin = PodcastPlayerLogic.minutosParaDormir(s.dormirAte, SystemClock.elapsedRealtime())
        val itens = linhas.map { l ->
            when (l) {
                Linha.VELOCIDADE -> a.getString(R.string.pod_velocidade)
                Linha.SILENCIO -> a.getString(R.string.pod_silencio)
                Linha.VOZ -> a.getString(R.string.som_voz_clara)
                Linha.DORMIR -> a.getString(R.string.pl_dormir)
                Linha.PROPRIO -> a.getString(R.string.som_so_este)
                Linha.ABERTURA -> a.getString(R.string.som_abertura)
                Linha.FIM -> a.getString(R.string.som_fim)
            } as CharSequence
        }
        val valores = linhas.map { l ->
            when (l) {
                Linha.VELOCIDADE -> PodcastPlayerLogic.rotuloVelocidade(ef.velocidade)
                Linha.SILENCIO -> liga(ef.pularSilencio)
                Linha.VOZ -> liga(ef.vozClara)
                Linha.DORMIR -> if (dormirMin > 0) a.getString(R.string.pl_dorme_em, dormirMin) else liga(false)
                Linha.PROPRIO -> liga(proprio)
                Linha.ABERTURA -> segs(ef.aberturaS)
                Linha.FIM -> segs(ef.fimS)
            } as CharSequence?
        }
        val titulo = if (e != null) a.getString(R.string.som_titulo_pod, e.podcast) else a.getString(R.string.som_titulo)
        Estilo.lista(a, titulo, itens, focoInicial = foco.coerceIn(0, itens.size - 1), valores = valores,
            rodape = if (e != null) a.getString(if (proprio) R.string.som_rodape_proprio else R.string.som_rodape_geral, e.podcast) else null) { i ->
            val sv = OuvirServico.instancia ?: return@lista
            val volta = { aoMudar(); abrir(a, i, aoMudar) }
            when (linhas[i]) {
                Linha.VELOCIDADE -> Estilo.lista(a, a.getString(R.string.pod_velocidade), OuvirServico.VELOCIDADES.map { PodcastPlayerLogic.rotuloVelocidade(it) },
                    listOf(OuvirServico.VELOCIDADES.indexOf(ef.velocidade)), aoVoltar = { abrir(a, i, aoMudar) }) { j ->
                    sv.definirVelocidade(OuvirServico.VELOCIDADES[j]); volta()
                }
                Linha.SILENCIO -> { sv.definirPularSilencio(!ef.pularSilencio); volta() }
                Linha.VOZ -> { sv.definirVozClara(!ef.vozClara); volta() }
                Linha.DORMIR -> {
                    val ops = PodcastPlayerLogic.DORMIR_MIN.map { if (it == 0) liga(false) else a.getString(R.string.pl_dormir_min, it) as CharSequence }
                    Estilo.lista(a, a.getString(R.string.pl_dormir), ops, if (dormirMin == 0) listOf(0) else emptyList(), aoVoltar = { abrir(a, i, aoMudar) }) { j ->
                        sv.definirDormir(PodcastPlayerLogic.DORMIR_MIN[j] * 60_000L); volta()
                    }
                }
                Linha.PROPRIO -> { sv.definirProprio(!proprio); volta() }
                Linha.ABERTURA, Linha.FIM -> {
                    val atual = if (linhas[i] == Linha.ABERTURA) ef.aberturaS else ef.fimS
                    Estilo.lista(a, itens[i], AjustesPodcast.PULAR_S.map { segs(it) as CharSequence }, listOf(AjustesPodcast.PULAR_S.indexOf(atual)),
                        aoVoltar = { abrir(a, i, aoMudar) }) { j ->
                        val n = AjustesPodcast.PULAR_S[j]
                        if (linhas[i] == Linha.ABERTURA) sv.definirAberturaFim(aberturaS = n) else sv.definirAberturaFim(fimS = n)
                        volta()
                    }
                }
            }
        }
    }
}

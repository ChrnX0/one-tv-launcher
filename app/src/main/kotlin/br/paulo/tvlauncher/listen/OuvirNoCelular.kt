package br.paulo.tvlauncher.listen

import android.content.Context
import org.json.JSONObject

/**
 * "Ouvir no celular" (proposta C do Ouvir, 21/11/2026): o painel web continua no celular o que a TV tocou por último.
 *
 * Por quê: o Pocket Casts e o Spotify passam o episódio de um aparelho para o outro no mesmo ponto; na casa, quem estava
 * ouvindo o noticiário na sala quer seguir ouvindo na cozinha.
 * Para quê: no painel (aba Controle, "Tocando"), um botão toca no celular a mesma rádio, ou o mesmo episódio do mesmo
 * segundo em que a TV parou.
 * Como: lê o que o ":player" já grava (a última rádio e as posições dos podcasts), sem falar com o player: o painel
 * roda em outro processo. O celular toca direto do endereço da rádio/podcast (CSP media-src do painel).
 */
object OuvirNoCelular {

    /** O mais recente entre a última rádio e o último episódio tocado. Pura: testada em OuvirNoCelularTest. */
    fun escolher(radio: Pair<Radios.Radio, Long>?, posicao: Podcasts.Posicao?, ep: PodcastFeed.Episodio?, capa: String): JSONObject {
        val o = JSONObject()
        val podcastEm = if (posicao != null && ep != null && !ep.video) posicao.em else -1L
        val radioEm = radio?.second ?: -1L
        when {
            podcastEm < 0 && radioEm < 0 -> o.put("nada", true)
            podcastEm >= radioEm -> o.put("tipo", "podcast").put("nome", ep!!.titulo).put("de", posicao!!.podcast)
                .put("url", ep.url).put("ms", if (posicao.ouvido) 0L else posicao.ms).put("capa", capa).put("em", podcastEm)
            else -> o.put("tipo", "radio").put("nome", radio!!.first.nome).put("url", radio.first.url)
                .put("capa", radio.first.logo).put("em", radioEm)
        }
        return o
    }

    fun json(ctx: Context): JSONObject {
        val radio = Radios.ultima(ctx)
        val pos = Podcasts.posicoes(ctx).values.maxByOrNull { it.em }
        val feed = pos?.let { Podcasts.feed(ctx, it.feed) }
        val ep = feed?.episodios?.firstOrNull { it.guid == pos.guid }?.takeIf { Podcasts.visivel(it, Podcasts.protegido(ctx)) }
        return escolher(radio, pos, ep, feed?.capa.orEmpty())
    }
}

package br.paulo.tvlauncher.alarm

import android.content.Context
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios

/**
 * Acordar com rádio ou podcast (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o despertador já acordava com o último canal da TV; quem escuta rádio de manhã (ou o noticiário em podcast)
 * queria acordar com ela, como num rádio-relógio.
 * Para quê: dois sons a mais no alarme: "Rádio" (a última que tocou, senão a 1ª favorita) e "Podcast" (o episódio mais
 * novo ainda não ouvido das assinaturas, respeitando o controle dos pais).
 * Como: a tela do alarme toca o endereço com o mesmo motor e a mesma rampa do canal; se não abrir em 20 s, cai no som
 * suave. A escolha é pura (testada em AcordarOuvindoTest); a leitura dos arquivos e da playlist fica em [fonte].
 */
object AcordarOuvindo {

    /** A rádio para acordar: a última que tocou; sem ela, a primeira favorita. */
    fun radio(ultima: Radios.Radio?, favoritas: List<Radios.Radio>): Radios.Radio? = ultima ?: favoritas.firstOrNull()

    /** O episódio para acordar: o mais novo nunca começado, sem o explícito quando a TV está protegida. */
    fun episodio(novos: List<Pair<String, PodcastFeed.Episodio>>, protegido: Boolean): PodcastFeed.Episodio? =
        novos.map { it.second }.filter { Podcasts.visivel(it, protegido) && it.url.isNotEmpty() }.maxByOrNull { it.dataMs }

    /** O endereço a tocar para o som [som] (fora da thread da tela: lê arquivos e pode abrir uma playlist). */
    fun fonte(ctx: Context, som: Int): String? = runCatching {
        when (som) {
            SomAlarme.RADIO -> radio(Radios.ultima(ctx)?.first, Radios.favoritas(ctx))?.url?.let { u ->
                // .pls/.m3u: o player não abre playlist; pega o 1º endereço dela (mesmo cuidado do colar no painel).
                if (!Radios.pareceplaylist(u)) u
                else {
                    val ips = java.net.InetAddress.getAllByName(java.net.URL(u).host).toList()
                    if (ips.any { Radios.destinoProibido(it) }) null
                    else Http.getText(u, maxBytes = 64 * 1024)?.let { Radios.daPlaylist(it) }
                }
            }
            SomAlarme.PODCAST -> episodio(Podcasts.novos(Podcasts.feeds(ctx), Podcasts.posicoes(ctx), System.currentTimeMillis()),
                Podcasts.protegido(ctx))?.url
            else -> null
        }
    }.getOrNull()
}

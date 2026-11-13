package br.paulo.tvlauncher

import android.content.Context
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * O ponto único entre as fontes de mídia da rede e o player (10/11/2026).
 *
 * Os endereços que circulam pelo app (Intent, posições salvas) NUNCA levam senha nem token:
 *   smb://<conta>/<arquivo>          pasta compartilhada (o player abre pelo Smb.Fonte)
 *   jellyfin://<conta>/<item>[/audio] Jellyfin e Emby
 *   plex://<conta>/<item>?p=<arquivo> Plex
 *   webdav://<conta>/<caminho>        WebDAV / Nextcloud
 *   http(s)://...                     DLNA e links diretos
 * [resolver] troca o endereço pelo de verdade (com o token, ou com o cabeçalho de senha) só na hora de tocar.
 * [informar] conta ao servidor onde a pessoa parou, para o "Continuar assistindo" dele andar junto com o nosso.
 */
object FontesMidia {
    /** O que o player precisa: endereço, cabeçalhos extras do pedido e (se houver) a quem contar o progresso. */
    data class Tocavel(val url: String, val cabecalhos: Map<String, String> = emptyMap(), val relato: Relato? = null)

    /** A quem contar o progresso. */
    sealed class Relato {
        class Jelly(val conta: ContaMidia, val item: String) : Relato()
        class PlexItem(val conta: ContaMidia, val item: String) : Relato()
    }

    enum class Momento { COMECOU, TOCANDO, PAROU }

    val ESQUEMAS = listOf("http://", "https://", "smb://", "jellyfin://", "plex://", "webdav://")

    fun aceito(u: String) = ESQUEMAS.any { u.startsWith(it) }

    /** Lê o disco (contas cifradas): chamar fora da thread da tela. null = a conta foi removida ou o endereço é inválido. */
    fun resolver(ctx: Context, u: String): Tocavel? {
        Jellyfin.lerUri(u)?.let { a ->
            val c = MidiaArmazem.conta(ctx, a.conta) ?: return null
            return Tocavel(Jellyfin.urlTocar(c, a.item, a.audio), relato = Relato.Jelly(c, a.item))
        }
        Plex.lerUri(u)?.let { a ->
            val c = MidiaArmazem.conta(ctx, a.conta) ?: return null
            return Tocavel(Plex.urlTocar(c, a.parte), relato = Relato.PlexItem(c, a.item))
        }
        WebDav.lerUri(u)?.let { a ->
            val c = MidiaArmazem.conta(ctx, a.conta) ?: return null
            return Tocavel(WebDav.urlTocar(c, a.caminho) ?: return null, WebDav.cabecalhoAuth(c))
        }
        if (u.startsWith("jellyfin://") || u.startsWith("plex://") || u.startsWith("webdav://")) return null
        return if (aceito(u)) Tocavel(u) else null
    }

    /** Conta ao servidor (Jellyfin, Emby, Plex). Erro de rede é ignorado: o vídeo não pode parar por isso. Já em thread. */
    fun informar(ctx: Context, r: Relato?, momento: Momento, ms: Long, duracao: Long, pausado: Boolean) {
        r ?: return
        val aparelho = runCatching { PainelVizinhos.idDaTv(ctx) }.getOrDefault("tv")
        Fundo.rodar {
            runCatching {
                when (r) {
                    is Relato.Jelly -> Jellyfin.informar(r.conta, aparelho, r.item, when (momento) {
                        Momento.COMECOU -> Jellyfin.Momento.COMECOU
                        Momento.TOCANDO -> Jellyfin.Momento.TOCANDO
                        Momento.PAROU -> Jellyfin.Momento.PAROU
                    }, ms, pausado)
                    is Relato.PlexItem -> Plex.informar(r.conta, aparelho, r.item, when {
                        momento == Momento.PAROU -> Plex.Estado.PAROU
                        pausado -> Plex.Estado.PAUSADO
                        else -> Plex.Estado.TOCANDO
                    }, ms, duracao)
                }
            }
        }
    }

    // ---------------------------------------------------------------- links diretos (conta LINK)

    /**
     * Lista M3U "de canais/arquivos" (várias entradas #EXTINF) vira pasta; já uma lista HLS (.m3u8 de UM vídeo, com
     * #EXT-X-...) é o próprio vídeo e vai direto para o player.
     */
    fun ehListaDeEntradas(texto: String): Boolean {
        val t = texto.removePrefix("﻿").trimStart()
        if (!t.startsWith("#EXTM3U", ignoreCase = true)) return false
        if (t.contains("#EXT-X-TARGETDURATION") || t.contains("#EXT-X-STREAM-INF") || t.contains("#EXT-X-MEDIA-SEQUENCE")) return false
        return t.contains("#EXTINF", ignoreCase = true)
    }

    /** Baixa o começo do link (no máximo [max] bytes; vídeo ao vivo nunca termina) e diz se é uma lista de entradas. */
    fun linkEhLista(url: String, max: Int = 64 * 1024): Boolean = runCatching { ehListaDeEntradas(baixarTexto(url, max, cortar = true)) }.getOrDefault(false)

    /** As entradas de uma lista M3U (o mesmo leitor das listas de TV), como itens tocáveis. */
    fun listarLink(c: ContaMidia): List<Upnp.Entrada> {
        val texto = baixarTexto(c.servidor, 4 * 1024 * 1024, cortar = false)
        if (!ehListaDeEntradas(texto)) return listOf(Upnp.Entrada(c.servidor, c.nome, false, c.servidor, Upnp.Tipo.VIDEO, null))
        return M3u.ler(texto).map { Upnp.Entrada(it.url, it.nome, false, it.url, MidiaContas.tipoPorNome(it.url.substringBefore('?')).let { t ->
            if (t == Upnp.Tipo.OUTRO) Upnp.Tipo.VIDEO else t }, null) }
    }

    private fun baixarTexto(url: String, max: Int, cortar: Boolean): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 10000
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("http ${c.responseCode}")
            return c.inputStream.use { ler(it, max, cortar) }
        } finally { c.disconnect() }
    }

    private fun ler(i: InputStream, max: Int, cortar: Boolean): String {
        val saida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (saida.size() < max) {
            val n = i.read(buf, 0, minOf(buf.size, max - saida.size())); if (n < 0) break
            saida.write(buf, 0, n)
        }
        if (!cortar && saida.size() >= max && i.read() >= 0) throw java.io.IOException("lista grande demais")
        return saida.toString("UTF-8")
    }
}

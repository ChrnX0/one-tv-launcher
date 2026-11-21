package br.paulo.tvlauncher.listen

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * O texto da fala de um episódio (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o Apple Podcasts mostra a transcrição sincronizada e o Pocket Casts deixa buscar nela; na TV da sala ela vale
 * mais ainda: a família acompanha com o som baixo, e quem ouve mal lê em letra grande.
 * Para quê: o "Texto da fala" do player, a busca dentro da fala e, depois, pular propaganda pelo que foi dito.
 * Como: lê os formatos do `<podcast:transcript>` (Podcasting 2.0): JSON ({"segments":[…]}), WebVTT e SRT. Texto sem
 * tempo (HTML, texto puro) não sincroniza e fica de fora. Lógica pura, testada em TranscricaoTest.
 */
object Transcricao {
    const val MAX_TRECHOS = 20_000

    data class Trecho(val inicioMs: Long, val fimMs: Long, val texto: String, val quem: String = "")

    /** Dos formatos que o episódio oferece, o melhor para sincronizar (JSON > VTT > SRT), preferindo a língua da TV. */
    fun escolher(l: List<PodcastFeed.Transcricao>, idioma: String = Locale.getDefault().language): PodcastFeed.Transcricao? {
        fun nota(t: PodcastFeed.Transcricao): Int {
            val tipo = t.tipo.lowercase()
            val formato = when {
                "json" in tipo -> 3
                "vtt" in tipo -> 2
                "srt" in tipo || "subrip" in tipo -> 1
                else -> 0
            }
            if (formato == 0) return 0
            val lingua = if (t.idioma.isEmpty() || t.idioma.lowercase().startsWith(idioma.lowercase())) 10 else 0
            return lingua + formato
        }
        return l.filter { nota(it) > 0 }.maxByOrNull { nota(it) }
    }

    /** Lê o texto conforme o tipo (ou pelo conteúdo, quando o tipo não diz). Formato desconhecido: vazio. */
    fun ler(texto: String?, tipo: String = ""): List<Trecho> {
        if (texto.isNullOrBlank()) return emptyList()
        val t = texto.trimStart('﻿', ' ', '\n', '\r', '\t')
        val l = when {
            "json" in tipo || t.startsWith("{") -> doJson(t)
            t.startsWith("WEBVTT") || "vtt" in tipo -> doVtt(t)
            else -> doSrt(t)
        }
        return juntar(l)
    }

    private fun doJson(t: String): List<Trecho> = runCatching {
        val a = JSONObject(t).optJSONArray("segments") ?: return emptyList()
        (0 until minOf(a.length(), MAX_TRECHOS)).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ini = o.optDouble("startTime", Double.NaN); val fim = o.optDouble("endTime", Double.NaN)
            if (ini.isNaN()) return@mapNotNull null
            Trecho((ini * 1000).toLong(), if (fim.isNaN()) (ini * 1000).toLong() else (fim * 1000).toLong(),
                o.optString("body"), o.optString("speaker"))
        }
    }.getOrDefault(emptyList())

    private val SETA = Regex("""([\d:.,]+)\s*-->\s*([\d:.,]+)""")
    private val TAG = Regex("<[^>]+>")
    private val VOZ = Regex("""^<v\s+([^>]+)>""")

    /** VTT e SRT têm o mesmo jeito: bloco = (número) + "início --> fim" + linhas de texto até a linha em branco. */
    private fun blocos(t: String): List<Trecho> {
        val out = ArrayList<Trecho>()
        for (b in t.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))) {
            val linhas = b.lines().filter { it.isNotBlank() }
            val i = linhas.indexOfFirst { SETA.containsMatchIn(it) }
            if (i < 0) continue
            val m = SETA.find(linhas[i])!!
            val ini = Capitulos.tempoMs(m.groupValues[1].replace(',', '.')) ?: continue
            val fim = Capitulos.tempoMs(m.groupValues[2].replace(',', '.')) ?: ini
            val corpo = linhas.drop(i + 1)
            val quem = corpo.firstOrNull()?.let { VOZ.find(it)?.groupValues?.get(1)?.trim() }.orEmpty()
            out += Trecho(ini, fim, corpo.joinToString(" ") { TAG.replace(it, "") }, quem)
            if (out.size >= MAX_TRECHOS) break
        }
        return out
    }
    private fun doVtt(t: String) = blocos(t)
    private fun doSrt(t: String) = blocos(t)

    /**
     * Limpa e junta: tira espaços repetidos e trechos vazios, põe em ordem, e junta pedaços curtos seguidos da mesma
     * pessoa (transcrição palavra por palavra viraria uma palavra por tela) até ~90 letras ou uma pausa de 1,5 s.
     */
    private fun juntar(l: List<Trecho>): List<Trecho> {
        val ok = l.map { it.copy(texto = it.texto.replace(Regex("\\s+"), " ").trim()) }.filter { it.texto.isNotEmpty() }.sortedBy { it.inicioMs }
        val out = ArrayList<Trecho>()
        for (x in ok) {
            val u = out.lastOrNull()
            if (u != null && u.quem == x.quem && u.texto.length + x.texto.length < 90 && x.inicioMs - u.fimMs < 1_500 &&
                !u.texto.endsWith('.') && !u.texto.endsWith('?') && !u.texto.endsWith('!')) {
                out[out.size - 1] = u.copy(fimMs = maxOf(u.fimMs, x.fimMs), texto = u.texto + " " + x.texto)
            } else out += x
        }
        return out
    }

    /** Junta trechos novos (de um pedaço gerado na nuvem) aos que já havia, em ordem e sem repetir o mesmo começo. */
    fun unir(a: List<Trecho>, b: List<Trecho>): List<Trecho> = (a + b).sortedBy { it.inicioMs }.distinctBy { it.inicioMs }.take(MAX_TRECHOS)

    /** O trecho falado em [posMs] (o último que começou), ou -1 antes do primeiro. Busca binária: chamado 4x por segundo. */
    fun atual(l: List<Trecho>, posMs: Long): Int {
        var lo = 0; var hi = l.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (l[m].inicioMs <= posMs) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }

    /** Sem acento e em minúsculas, para a busca achar "vacina" em "Vacinação" e "São" em "sao". */
    fun normalizar(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Os trechos que têm todas as palavras de [busca] (até [max]). */
    fun buscar(l: List<Trecho>, busca: String, max: Int = 50): List<Int> {
        val palavras = normalizar(busca).split(Regex("\\s+")).filter { it.length >= 2 }
        if (palavras.isEmpty()) return emptyList()
        return l.indices.filter { i -> val t = normalizar(l[i].texto); palavras.all { it in t } }.take(max)
    }
}

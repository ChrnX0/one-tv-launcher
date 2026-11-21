package br.paulo.tvlauncher.player

import java.util.Locale

/**
 * Legenda que mora ao lado do vídeo (14/11/2026), como o Kodi e o VLC acham: "Filme.mkv" + "Filme.srt",
 * "Filme.pt-BR.srt", "Filme.eng.ass". Vale para as pastas da rede (SMB, FTP, SFTP, NFS, OneDrive, Dropbox) e o pendrive.
 * Só a escolha dos nomes mora aqui (pura, testada em LegendaAoLadoTest); quem lista a pasta é a VodActivity.
 */
object LegendaAoLado {
    /** Uma legenda achada: o nome do arquivo, o tipo (MIME do Media3) e o idioma do nome, se houver ("pt-BR", "en"). */
    data class Achada(val nome: String, val mime: String, val idioma: String?)

    private val TIPOS = mapOf(
        "srt" to "application/x-subrip",   // MimeTypes.APPLICATION_SUBRIP
        "ass" to "text/x-ssa",             // MimeTypes.TEXT_SSA
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",               // MimeTypes.TEXT_VTT
    )

    /** Mais que isto numa pasta é sinal de que não são legendas deste vídeo (pasta de legendas de uma série inteira). */
    const val MAX = 6

    // ISO 639-2 (3 letras) que aparecem em nomes de legenda -> 639-1, que é como o Media3 e a TV falam.
    private val TRES = mapOf("por" to "pt", "pob" to "pt-BR", "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de", "ita" to "it", "jpn" to "ja")

    fun achar(video: String, naPasta: List<String>): List<Achada> {
        val base = video.substringBeforeLast('.', video)
        if (base.isEmpty()) return emptyList()
        return naPasta.mapNotNull { n ->
            val ext = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = TIPOS[ext] ?: return@mapNotNull null
            val semExt = n.substringBeforeLast('.')
            when {
                semExt.equals(base, ignoreCase = true) -> Achada(n, mime, null)
                semExt.length > base.length + 1 && semExt.startsWith(base, ignoreCase = true) && semExt[base.length] == '.' ->
                    Achada(n, mime, idioma(semExt.substring(base.length + 1)))
                else -> null
            }
        }.sortedBy { it.nome.lowercase(Locale.ROOT) }.take(MAX)
    }

    /** "pt-BR", "pt_br", "por", "eng", "forced"... -> idioma em 639-1, ou null se não for um idioma. */
    fun idioma(marca: String): String? {
        val m = marca.substringBefore('.').lowercase(Locale.ROOT).replace('_', '-')
        TRES[m]?.let { return it }
        val partes = m.split('-')
        if (partes[0].length != 2 || !partes[0].all { it in 'a'..'z' }) return null
        val pais = partes.getOrNull(1)?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }?.uppercase(Locale.ROOT)
        return if (pais != null) "${partes[0]}-$pais" else partes[0]
    }
}

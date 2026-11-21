package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.Vod
import java.text.Normalizer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * As contas rápidas de títulos (20/11/2026) dão EXATAMENTE o mesmo que as expressões regulares de antes. A chave do
 * título é guardada em caches e cruzada com listas de fora: mudar uma letra nela perderia dados. Aqui as fórmulas
 * antigas estão copiadas como referência e comparadas em dezenas de milhares de nomes montados com os casos difíceis
 * (colchetes, parênteses, marcas no fim, prefixo de país, espaços estranhos, acentos, outras escritas, emoji).
 */
class IdiomaRapidoTest {
    // ---- referência: o código de antes, como estava
    private val reMarcas = Regex("""[\[(]\s*(l|leg|legendado|d|dub|dublado|dual|dual audio|nacional)\s*[\])]|[\s\-|:]+(legendado|leg|dublado|dub)\s*$""",
        RegexOption.IGNORE_CASE)
    private val reLeg = Regex("""[\[(]\s*(l|leg|legendado)\s*[\])]|(^|[\s\-|:])(legendado|leg)\s*$""", RegexOption.IGNORE_CASE)
    private val reDub = Regex("""[\[(]\s*(d|dub|dublado)\s*[\])]|(^|[\s\-|:])(dublado|dub)\s*$""", RegexOption.IGNORE_CASE)
    private val reEspacos = Regex("""\s{2,}""")
    private val reAno = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val rePrefixoPais = Regex("""^\s*[\[(|]?[A-Z]{2,3}[\])|]?\s*[:|\-]\s*""")
    private val reQualidade = Regex("""(\s+(4k|uhd|fhd|hd|hdr|hdr10|dv|sdr|1080p|720p|2160p|hevc|h265|x265))+$""")

    private fun semAcentoAntes(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
    private fun nomeLimpoAntes(nome: String) =
        nome.replace(reAno, "").replace(rePrefixoPais, "").replace(reEspacos, " ").trim().ifEmpty { nome.trim() }
    private fun chaveAntes(nome: String) =
        semAcentoAntes(nomeLimpoAntes(nome.replace(reMarcas, " ")).replace(reMarcas, " ")).lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
    private fun versaoAntes(nome: String, grupo: String): Idioma.Versao {
        val n = nome.trim()
        if (reLeg.containsMatchIn(n)) return Idioma.Versao.LEGENDADA
        if (reDub.containsMatchIn(n)) return Idioma.Versao.DUBLADA
        val g = semAcentoAntes(grupo).lowercase()
        return when { "legendad" in g -> Idioma.Versao.LEGENDADA; "dublad" in g -> Idioma.Versao.DUBLADA; else -> Idioma.Versao.DESCONHECIDA }
    }
    private fun semMarcaAntes(nome: String) = nome.replace(reMarcas, " ").replace(reEspacos, " ").trim().ifEmpty { nome }
    private fun chaveNomeAnoAntes(nome: String, ano: Int?) = chaveAntes(nome).replace(reQualidade, "") + "|" + (ano ?: 0)

    // ---- nomes difíceis
    private val pedacos = listOf(
        "Duna", "duna", "PARTE", "Dois", "Coração", "AÇÃO", "Ñandú", "Über", "straße", "Æon", "Øresund", "İstanbul", "ıi", "ſ", "K",
        "Ǆemal", "Ångström", "naïve", "Ḟoo", "Ελληνικά", "Русский", "日本語", "한국어", "é", "🎬", "Ȫ", "ɐ",
        "LEG", "Leg", "leg", "legendado", "LEGENDADO", "DUB", "Dub", "dublado", "Dublado", "dual", "DUAL AUDIO", "nacional", "L", "D",
        "BR", "PT", "EUA", "4K", "4k", "HD", "UHD", "hdr10", "1080p", "x265", "HEVC", "dv",
        "1999", "2024", "1888", "20245", "19", "(", ")", "[", "]", "|", ":", "-", "–", "—", ".", ",", "&", "'", "!", "?",
        " ", " ", " ", "  ", "\t", " ", "\u0085", " ", "　", "\n",
    )

    private fun nomes(n: Int): List<String> {
        val r = Random(20261120)
        val fixos = listOf(
            "Duna: Parte 2 (2024) [L]", "Duna - Parte 2 (2024)", "O Sonho da Liberdade Leg", "Custe o que Custar LEG ",
            "BR: Filme (2020)", "|PT| Outro (1999) 4K", "[EN] - X", "Else (2023) (Dublado)", "Else (2023) - Legendado",
            "  ", "", "LEG", "(2024)", "Ação  Total", "Filme\u0085\u0085Dois", "Título leg\u0085", "nome dub\t",
            "A [ dual audio ] B", "(nacional)", "K-Pop: Caçadoras", "Mulher-Maravilha 1984 (2020) HD", "Oppenheimer (2023) 4K",
        )
        return fixos + List(n) {
            val partes = r.nextInt(1, 9)
            buildString { repeat(partes) { append(pedacos[r.nextInt(pedacos.size)]); if (r.nextInt(3) > 0) append(' ') } }
        }
    }

    @Test fun chaveTituloIgualAntes() {
        for (s in nomes(40_000)) {
            assertEquals("chave de <$s>", chaveAntes(s), Idioma.chaveTitulo(s))
            assertEquals("chave (memória) de <$s>", chaveAntes(s), Idioma.chaveTitulo(s))
        }
    }

    @Test fun versaoSemMarcaENomeLimpoIgualAntes() {
        val ns = nomes(30_000)
        val grupos = listOf("Filmes | Legendados", "Séries Dubladas", "FILMES LEGENDADOS", "Ação", "Lançamentos 2026", "", "Coração Dublád")
        for ((i, s) in ns.withIndex()) {
            val g = grupos[i % grupos.size]
            assertEquals("versao de <$s>/<$g>", versaoAntes(s, g), Idioma.versao(s, g))
            assertEquals("semMarca de <$s>", semMarcaAntes(s), Idioma.semMarca(s))
            assertEquals("nomeLimpo de <$s>", nomeLimpoAntes(s), Vod.nomeLimpo(s))
            assertEquals("chaveNomeAno de <$s>", chaveNomeAnoAntes(s, 2000 + i % 30), InfoExterna.chaveNomeAno(s, 2000 + i % 30))
        }
    }

    @Test fun semAcentoIgualAntes() {
        // Todas as letras até U+024F, sozinhas e em pares, mais textos misturados.
        for (c in 0 until 0x300) {
            val s = c.toChar().toString()
            assertEquals("U+%04X".format(c), semAcentoAntes(s), Idioma.semAcento(s))
            assertEquals("U+%04X em texto".format(c), semAcentoAntes("a${s}b$s"), Idioma.semAcento("a${s}b$s"))
        }
        for (s in nomes(20_000)) assertEquals("semAcento de <$s>", semAcentoAntes(s), Idioma.semAcento(s))
    }
}

package br.paulo.tvlauncher.player

import android.content.Context

/**
 * Confortos de filmes e séries vindos do estudo do Stremio (16/11/2026, thread "Estudar o Stremio para inspirar a Sofaly"):
 *
 *  - Esconder spoiler: o resumo de episódio ainda não visto fica escondido na ficha da série.
 *  - Segundo idioma: se o vídeo não tem áudio (no "Dublado") ou legenda no idioma da TV, usa este (ex.: TV em português,
 *    segundo idioma inglês: filme coreano sem legenda em português liga a legenda em inglês).
 *  - Contagem do próximo episódio ajustável (antes fixa em 10 s), com "não começar sozinho".
 *
 * Moram no prefs "player" com MODE_MULTI_PROCESS, como a [TaxaQuadros]: as Configurações gravam no processo da HOME e as
 * telas de Filmes/Séries leem no ":player". As escolhas puras (opções válidas, o que mostrar) ficam em [ConfortoRegras].
 */
object Conforto {
    private const val SPOILER = "sem_spoiler"
    private const val SEGUNDO = "segundo_idioma"
    private const val CONTAGEM = "contagem_proximo_s"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PlayerAcessivel.PREFS, Context.MODE_MULTI_PROCESS)

    fun semSpoiler(ctx: Context): Boolean = prefs(ctx).getBoolean(SPOILER, false)
    fun alternarSpoiler(ctx: Context): Boolean = (!semSpoiler(ctx)).also { prefs(ctx).edit().putBoolean(SPOILER, it).apply() }

    /** Código de 2 letras ("en") ou null (nenhum). */
    fun segundoIdioma(ctx: Context): String? = ConfortoRegras.segundoValido(prefs(ctx).getString(SEGUNDO, null))
    fun definirSegundo(ctx: Context, codigo: String?) = prefs(ctx).edit().putString(SEGUNDO, codigo.orEmpty()).apply()

    /** Segundos da contagem antes do próximo episódio; 0 = não começa sozinho. */
    fun contagem(ctx: Context): Int = ConfortoRegras.contagemValida(prefs(ctx).getInt(CONTAGEM, FimDoVideo.CONTAGEM_S))
    fun definirContagem(ctx: Context, s: Int) = prefs(ctx).edit().putInt(CONTAGEM, ConfortoRegras.contagemValida(s)).apply()

    /** Nome do idioma no idioma da TV ("Inglês", "English"), com a primeira letra maiúscula. */
    fun nomeIdioma(codigo: String): String {
        val l = java.util.Locale.getDefault()
        return java.util.Locale.forLanguageTag(codigo).getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }
}

/** Regras puras do [Conforto] (sem Android; ConfortoRegrasTest). */
object ConfortoRegras {
    /** Opções da contagem do próximo episódio, em segundos. 0 = não começar sozinho (volta à ficha no fim). */
    val CONTAGENS = intArrayOf(0, 5, 10, 20, 30)

    /** Idiomas oferecidos como segundo idioma: os mais comuns em áudio e legenda de filmes e séries. */
    val SEGUNDOS = listOf("en", "es", "pt", "fr", "de", "it", "ja", "ko")

    fun contagemValida(s: Int): Int = if (s in CONTAGENS) s else FimDoVideo.CONTAGEM_S

    fun segundoValido(c: String?): String? = c?.trim()?.lowercase()?.takeIf { it in SEGUNDOS }

    /** O segundo idioma só faz sentido se for diferente do idioma da TV. */
    fun segundoUtil(segundo: String?, idiomaTv: String): String? = segundo?.takeIf { it != idiomaTv }

    /**
     * Esconder o resumo deste episódio? Só com a chave ligada e se o episódio nunca foi aberto. Episódio começado ou visto
     * mostra o resumo: a pessoa já sabe o começo.
     */
    fun esconderResumo(ligado: Boolean, comecado: Boolean): Boolean = ligado && !comecado
}

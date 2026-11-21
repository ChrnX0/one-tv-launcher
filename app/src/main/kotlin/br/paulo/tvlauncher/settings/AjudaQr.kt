package br.paulo.tvlauncher.settings

import android.content.Context

/**
 * Ajuda por QR (degrau 2, 16/11/2026): o link do assunto do manual no site, para ler no celular (letra do tamanho que a
 * pessoa quiser, sem segurar o controle). O texto do site é o mesmo do app (scripts/gerar-ajuda-site.py, AjudaSiteTest).
 * Puro, menos [link] com Context.
 */
object AjudaQr {
    const val PAGINA = "https://chrnx0.github.io/sofaly/help.html"

    /** [idioma]: tag curta do idioma da TV ("pt", "pt-PT", "in"...). O site usa "id" para indonésio. */
    fun link(idioma: String, topico: String): String {
        val l = when {
            idioma.equals("in", true) || idioma.startsWith("in-", true) -> "id"
            idioma.startsWith("pt-PT", true) -> "pt-PT"
            else -> idioma.substringBefore('-').lowercase()
        }
        return "$PAGINA?l=$l&t=$topico"
    }

    fun link(ctx: Context, topico: String): String {
        val loc = ctx.resources.configuration.locales[0]
        val tag = if (loc.language == "pt" && loc.country == "PT") "pt-PT" else loc.language
        return link(tag, topico)
    }

    /**
     * O assunto do manual de cada aba das Configurações (a ordem de ConfigLauncherActivity.abas): Início, Conta,
     * Família, Energia, Casa, Remoto, Sistema, Ajuda, Avançado. null = a própria Ajuda.
     */
    private val POR_ABA = listOf("inicio", "tv", "familia", "protetor", "casa", "celular", "sistema", null, "sistema")

    fun topicoDaAba(aba: Int): String? = POR_ABA.getOrNull(aba)
}

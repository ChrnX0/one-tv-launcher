package br.paulo.tvlauncher.settings

import android.content.Context
import br.paulo.tvlauncher.Estilo

/**
 * Elevação 16.3 (17/11/2026): o tema de alto contraste. Desligado de fábrica; liga em Configurações, ao lado do
 * tamanho do texto. O LauncherApp aplica antes do onCreate de cada tela ([aplicar]); as cores moram em Estilo.
 * Desfazer: a mesma linha desliga.
 */
object AltoContraste {
    const val CHAVE = "alto_contraste"
    /** O mínimo do tema (WCAG AAA) para texto sobre o fundo; conferido em AltoContrasteTest. */
    const val MINIMO = 7.0

    fun ligado(ctx: Context): Boolean = Extras.numero(ctx, CHAVE, 0) == 1
    fun definir(ctx: Context, sim: Boolean) { Extras.definirNumero(ctx, CHAVE, if (sim) 1 else 0); Estilo.altoContraste = sim }
    fun aplicar(ctx: Context) { Estilo.altoContraste = ligado(ctx) }
}

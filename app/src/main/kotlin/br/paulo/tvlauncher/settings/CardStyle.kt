package br.paulo.tvlauncher.settings

import android.content.Context
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.Estilo

/**
 * Cor de destaque, cantos, brilho do foco e tamanho da cruz (21/11/2026, item 1 do plano para passar o Projectivy).
 *
 * Porquê: o Projectivy deixa mexer em cantos, cor e brilho do foco; a Sofaly tinha tudo fixo.
 * Para quê: cada casa ajusta a cara da tela sem sair do sistema de design (as opções são poucas e todas legíveis a 3 m).
 * Como: as escolhas moram aqui e viram números em Estilo ([aplicar]); toda tela que pede Estilo.ACENTO / Estilo.RAIO já
 * muda sozinha. O foco continua BRANCO (a coisa mais óbvia da tela, regra do Estilo): "cor do foco" aqui é a cor do
 * destaque (ponto do escolhido, progresso, "ligado") e do brilho atrás do ícone escolhido. Nunca rosa (regra do Paulo).
 * Liga com: alto contraste (cada cor tem a versão AAA), o painel de prévia (FineTuneActivity) e o QR do visual.
 */
object CardStyle {
    const val KEY_ACCENT = "card_accent"
    const val KEY_CORNERS = "card_corners"
    const val KEY_GLOW = "card_glow"
    const val KEY_SIZE = "cross_size"

    /** [base] no escuro, [high] no alto contraste (≥ 7:1 sobre o fundo), [onLight] sobre o foco branco. */
    enum class Accent(val base: Int, val high: Int, val onLight: Int) {
        ORANGE(DesignTokens.ORANGE, DesignTokens.ORANGE_HIGH, DesignTokens.ORANGE_ON_LIGHT),
        AMBER(DesignTokens.ATTENTION, 0xFFFFD27A.toInt(), 0xFF7A4F00.toInt()),
        TEAL(DesignTokens.OK, 0xFFA6EBDA.toInt(), 0xFF155C4D.toInt()),
        SKY(0xFF7AB8F5.toInt(), 0xFFA8D2FF.toInt(), 0xFF14528F.toInt()),
        LILAC(0xFF9A9DFB.toInt(), 0xFFC0C2FF.toInt(), 0xFF3B3FB8.toInt()),
    }
    /** Multiplica os raios do Estilo (8 e 16 dp). */
    enum class Corners(val scale: Float) { SQUARE(0.25f), DEFAULT(1f), ROUND(1.75f) }
    /** Opacidade do brilho atrás do ícone escolhido da cruz. */
    enum class Glow(val alpha: Float) { OFF(0f), SOFT(0.35f), STRONG(0.65f) }
    /** Escala da cruz inteira; pouca variação de propósito (a cruz já foi medida para não cortar nada). */
    enum class Size(val scale: Float) { SMALL(0.92f), DEFAULT(1f), LARGE(1.08f) }

    fun accent(ctx: Context) = Accent.entries.getOrElse(Extras.numero(ctx, KEY_ACCENT, 0)) { Accent.ORANGE }
    fun corners(ctx: Context) = Corners.entries.getOrElse(Extras.numero(ctx, KEY_CORNERS, 1)) { Corners.DEFAULT }
    fun glow(ctx: Context) = Glow.entries.getOrElse(Extras.numero(ctx, KEY_GLOW, 0)) { Glow.OFF }
    fun size(ctx: Context) = Size.entries.getOrElse(Extras.numero(ctx, KEY_SIZE, 1)) { Size.DEFAULT }

    /** Próximo valor (para frente ou para trás), grava e aplica. Genérico para os 4 ajustes. */
    inline fun <reified E : Enum<E>> next(ctx: Context, key: String, atual: E, passo: Int = 1): E {
        val todos = enumValues<E>()
        val n = todos[Math.floorMod(atual.ordinal + passo, todos.size)]
        Extras.definirNumero(ctx, key, n.ordinal)
        aplicar(ctx)
        return n
    }

    /** Passa as escolhas para o Estilo. O LauncherApp chama antes de cada tela (como o alto contraste). */
    fun aplicar(ctx: Context) {
        val a = accent(ctx)
        Estilo.acentoBase = a.base
        Estilo.acentoAlto = a.high
        Estilo.acentoNoFocoBase = a.onLight
        Estilo.escalaRaio = corners(ctx).scale
    }

    /** O que muda a cara da HOME (MainActivity.assinaturaDoLayout). */
    fun assinatura(ctx: Context) = "${accent(ctx)}|${corners(ctx)}|${glow(ctx)}|${size(ctx)}"

    /** Raio em dp para um raio base e uma escala; nunca menos de 2 dp. Puro (CardStyleTest). */
    fun raio(base: Int, escala: Float) = (base * escala + 0.5f).toInt().coerceAtLeast(2)
}

package br.paulo.tvlauncher.home

import android.content.Context
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.settings.Extras

/**
 * Ajustes da tela inicial em cruz (HomeCruz), a ÚNICA HOME desde 21/11/2026: o PR que apagou a antiga (fileira/grade,
 * tamanho e transparência dos cartões, pilhas, mover) levou junto as chaves dela. Cada ajuste mora em Extras.numero.
 */
object LayoutHome {
    /**
     * Como os APPS aparecem na cruz (18/11/2026: o dono não gostou dos ícones quadrados). [BANNER] = a arte 16:9 de TV
     * do app com cantos arredondados (sem banner: o ícone num retângulo da cor dele); [REDONDO] = o ícone recortado em
     * círculo; [SOLTO] = só o traço do ícone, sem fundo (sem traço legível: o redondo).
     */
    enum class IconeAppCruz { BANNER, REDONDO, SOLTO }
    const val CHAVE_APPS_ICONE = "cruz_apps_icone"
    fun iconeAppCruz(ctx: Context) = iconeAppCruz(Extras.numero(ctx, CHAVE_APPS_ICONE, IconeAppCruz.BANNER.ordinal))
    fun iconeAppCruz(valor: Int) = IconeAppCruz.entries.getOrElse(valor) { IconeAppCruz.BANNER }
    fun proximoIconeAppCruz(ctx: Context) = IconeAppCruz.entries.let { it[(iconeAppCruz(ctx).ordinal + 1) % it.size] }
        .also { Extras.definirNumero(ctx, CHAVE_APPS_ICONE, it.ordinal) }

    /** Estilo dos ícones da cruz: 0 = Objeto, 1 = Símbolo, 2 = Duotom, 3 = Linha (padrão desde 18/11/2026; o número é a ordem do enum). */
    const val CHAVE_ICONES = "cruz_icones"
    fun iconesCruz(ctx: Context) = iconesCruz(Extras.numero(ctx, CHAVE_ICONES, IconeCruz.Estilo.LINHA.ordinal))
    fun iconesCruz(valor: Int) = IconeCruz.Estilo.entries.getOrElse(valor) { IconeCruz.Estilo.LINHA }
    fun definirIconesCruz(ctx: Context, e: IconeCruz.Estilo) = Extras.definirNumero(ctx, CHAVE_ICONES, e.ordinal)
    fun proximoIconesCruz(ctx: Context) = IconeCruz.Estilo.entries.let { it[(iconesCruz(ctx).ordinal + 1) % it.size] }
        .also { definirIconesCruz(ctx, it) }

    /**
     * Barra de cima na cruz (17/11/2026, Paulo: "quero todas as opções"):
     * - [BarraCruz.ILHA] (PADRÃO desde 18/11/2026, a mistura A+B+D do estudo da barra, ver BarraIlha): recolhe como a Recua;
     *   no centro a pílula com a hora cresce com o que está acontecendo; no canto, uma linha sobre a categoria em foco;
     * - [BarraCruz.RECUA] (padrão até a Ilha; o Vidro foi padrão por algumas horas em 18/11/2026 e o dono preferiu a que recolhe): com a cruz em foco, só hora e tempo, pequenos; ▲ traz a barra inteira e a cruz desce;
     * - [BarraCruz.LEVE]: a barra sempre à mostra, sem as cápsulas de vidro;
     * - [BarraCruz.RELOGIO]: como a Recua, e um relógio grande com data e tempo no canto livre de baixo;
     * - [BarraCruz.HOJE]: a barra de cápsulas de sempre;
     * - [BarraCruz.VIDRO] (onda 3, 18/11/2026): a barra sempre à mostra numa faixa só de vidro parado.
     * A ordem é a guardada nas preferências (o número): opção nova entra no FIM.
     */
    enum class BarraCruz { RECUA, LEVE, RELOGIO, HOJE, VIDRO, ILHA }
    const val CHAVE_BARRA = "cruz_barra"
    fun barraCruz(ctx: Context) = barraCruz(Extras.numero(ctx, CHAVE_BARRA, BarraCruz.ILHA.ordinal))
    fun barraCruz(valor: Int) = BarraCruz.entries.getOrElse(valor) { BarraCruz.ILHA }
    fun definirBarraCruz(ctx: Context, b: BarraCruz) = Extras.definirNumero(ctx, CHAVE_BARRA, b.ordinal)
    fun proximaBarraCruz(ctx: Context) = BarraCruz.entries.let { it[(barraCruz(ctx).ordinal + 1) % it.size] }
        .also { definirBarraCruz(ctx, it) }

    /** Arte do item em foco atrás da cruz (capa à direita, sobre o fundo escolhido em Tema). Ligada de fábrica. */
    const val CHAVE_ARTE = "cruz_arte"
    fun arteCruz(ctx: Context) = Extras.numero(ctx, CHAVE_ARTE, 1) != 0
    fun definirArteCruz(ctx: Context, sim: Boolean) = Extras.definirNumero(ctx, CHAVE_ARTE, if (sim) 1 else 0)
}

package br.paulo.tvlauncher

import android.content.Context

/**
 * Layout flexível da HOME (degrau 1 contra o Projectivy, 15/11/2026): tamanho dos cartões de app e cartões
 * transparentes. Os dois ficam desligados de fábrica: a HOME padrão continua idêntica.
 *
 * Tamanho: o cartão 16:9 cresce e a linha de apps desce o mesmo tanto (a tela tem 540 dp; o Grande termina em ~522).
 * Os atalhos do Continuar (TV, Séries, Filmes) crescem junto, para cada um continuar em cima de um app.
 * Transparente: fora do foco o cartão fica a [ALFA_TRANSPARENTE] e o fundo aparece por ele; o focado fica inteiro.
 * Grade: os apps em fileiras (GradeApps) em vez de uma fila que rola de lado. A ficha do card em foco sai, o Continuar
 * sobe para logo abaixo do topo e a grade ocupa o resto da tela, rolando na vertical.
 * As contas são puras (LayoutHomeTest).
 */
object LayoutHome {
    const val CHAVE = "tamanho_cartoes"
    val OPCOES = listOf(100, 115)
    const val BASE_L = 112
    const val BASE_A = 63
    const val ALFA_TRANSPARENTE = 0.72f

    fun valido(v: Int) = if (v in OPCOES) v else 100
    fun proximo(atual: Int) = OPCOES[(OPCOES.indexOf(atual) + 1).mod(OPCOES.size)]

    /** Largura e altura (dp) do cartão de app para o tamanho [pct]; mantém 16:9. */
    fun largura(pct: Int) = BASE_L * valido(pct) / 100
    fun altura(pct: Int) = largura(pct) * 9 / 16

    /** Quanto a linha de apps (e a caixa do Continuar) cresce em altura, em dp. */
    fun acrescimo(pct: Int) = altura(pct) - BASE_A

    // ---- modo grade (posições em dp, tela de 960 x 540)
    const val CHAVE_GRADE = "home_em_grade"
    const val TELA_L = 960
    const val TELA_A = 540
    /** Onde o Continuar começa no modo grade: logo abaixo das pílulas do topo. */
    const val TOPO_CONTINUAR_GRADE = 104

    /** Quantas colunas cabem entre as margens [margem] com cartões de passo [passo] dp. Pelo menos 3. */
    fun colunas(passo: Int, margem: Int) = ((TELA_L - 2 * margem) / passo).coerceAtLeast(3)

    fun grade(ctx: Context) = Extras.numero(ctx, CHAVE_GRADE, 0) == 1
    fun definirGrade(ctx: Context, sim: Boolean) = Extras.definirNumero(ctx, CHAVE_GRADE, if (sim) 1 else 0)

    fun tamanho(ctx: Context) = valido(Extras.numero(ctx, CHAVE, 100))
    fun definirTamanho(ctx: Context, v: Int) = Extras.definirNumero(ctx, CHAVE, valido(v))
    fun transparente(ctx: Context) = Extras.ligado(ctx, Extras.Chave.CARTOES_TRANSPARENTES)

    // ---- tela inicial em cruz (17/11/2026): a ÚNICA tela inicial (dono, 19:41Z: "aposenta a antiga"). Não há ajuste.
    // A HOME antiga (fileira/grade) só volta por adb, como saída de emergência enquanto a Cruz não passa no teste de
    // desempenho na TV: `EXTRAS --es o home_antiga --ez ligar true`. Depois do aceite, um PR próprio apaga a antiga.
    const val CHAVE_JEITO = "home_jeito"
    const val JEITO_ANTIGA = 2

    /** true = Cruz (sempre, salvo a saída de emergência por adb). */
    fun cruz(ctx: Context): Boolean = cruz(Extras.numero(ctx, CHAVE_JEITO, 0))
    fun cruz(valorGuardado: Int): Boolean = valorGuardado != JEITO_ANTIGA

    fun definirAntiga(ctx: Context, sim: Boolean) = Extras.definirNumero(ctx, CHAVE_JEITO, if (sim) JEITO_ANTIGA else 0)

    /** Estilo dos ícones da cruz: 0 = Objeto (padrão), 1 = Símbolo, 2 = Duotom (o número é a ordem do enum). */
    const val CHAVE_ICONES = "cruz_icones"
    fun iconesCruz(ctx: Context) = iconesCruz(Extras.numero(ctx, CHAVE_ICONES, 0))
    fun iconesCruz(valor: Int) = IconeCruz.Estilo.entries.getOrElse(valor) { IconeCruz.Estilo.OBJETO }
    fun definirIconesCruz(ctx: Context, e: IconeCruz.Estilo) = Extras.definirNumero(ctx, CHAVE_ICONES, e.ordinal)
    fun proximoIconesCruz(ctx: Context) = IconeCruz.Estilo.entries.let { it[(iconesCruz(ctx).ordinal + 1) % it.size] }
        .also { definirIconesCruz(ctx, it) }

    /**
     * Barra de cima na cruz (17/11/2026, Paulo: "quero todas as opções"):
     * - [BarraCruz.RECUA] (padrão até 18/11/2026): com a cruz em foco, só hora e tempo, pequenos; ▲ traz a barra inteira e a cruz desce;
     * - [BarraCruz.LEVE]: a barra sempre à mostra, sem as cápsulas de vidro;
     * - [BarraCruz.RELOGIO]: como a Recua, e um relógio grande com data e tempo no canto livre de baixo;
     * - [BarraCruz.HOJE]: a barra de cápsulas de sempre;
     * - [BarraCruz.VIDRO] (padrão desde a onda 3, 18/11/2026): a barra sempre à mostra numa faixa só de vidro parado.
     * A ordem é a guardada nas preferências (o número): opção nova entra no FIM.
     */
    enum class BarraCruz { RECUA, LEVE, RELOGIO, HOJE, VIDRO }
    const val CHAVE_BARRA = "cruz_barra"
    fun barraCruz(ctx: Context) = barraCruz(Extras.numero(ctx, CHAVE_BARRA, BarraCruz.VIDRO.ordinal))
    fun barraCruz(valor: Int) = BarraCruz.entries.getOrElse(valor) { BarraCruz.VIDRO }
    fun definirBarraCruz(ctx: Context, b: BarraCruz) = Extras.definirNumero(ctx, CHAVE_BARRA, b.ordinal)
    fun proximaBarraCruz(ctx: Context) = BarraCruz.entries.let { it[(barraCruz(ctx).ordinal + 1) % it.size] }
        .also { definirBarraCruz(ctx, it) }

    /** Arte do item em foco atrás da cruz (capa à direita, sobre o fundo escolhido em Tema). Ligada de fábrica. */
    const val CHAVE_ARTE = "cruz_arte"
    fun arteCruz(ctx: Context) = Extras.numero(ctx, CHAVE_ARTE, 1) != 0
    fun definirArteCruz(ctx: Context, sim: Boolean) = Extras.definirNumero(ctx, CHAVE_ARTE, if (sim) 1 else 0)
}

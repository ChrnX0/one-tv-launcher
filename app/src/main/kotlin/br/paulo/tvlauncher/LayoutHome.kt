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
}

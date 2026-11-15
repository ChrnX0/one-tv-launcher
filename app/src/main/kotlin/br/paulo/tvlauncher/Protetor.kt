package br.paulo.tvlauncher

import android.content.Context

/**
 * Estilo do nosso protetor de tela (10/11/2026). "galeria" (padrão): a pintura do dia do tema Galeria com hora, data,
 * clima, céu e avisos por cima ([ProtetorGaleria]). "classico": o protetor de 03/11 (relógio com degradê e 4 quadrados).
 * Gravado nas prefs "launcher", que entram no backup.
 */
object Protetor {
    const val CHAVE = "protetor_estilo"
    const val GALERIA = "galeria"
    const val CLASSICO = "classico"

    /**
     * Sempre Galeria (dono, 11/11/2026: o protetor do Escuro tem que ser IGUAL ao da Galeria, só com o wallpaper escuro).
     * A pref antiga "classico" (vinda de um backup ou da opção que existia nas Configurações) é ignorada.
     */
    @Suppress("UNUSED_PARAMETER")
    fun estilo(ctx: Context): String = GALERIA

    fun definir(ctx: Context, estilo: String) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(CHAVE, normalizar(estilo)).apply()
    }

    /**
     * O estilo dos widgets de baixo do protetor (15/11/2026, dono: "quero poder escolher eu o estilo do widget nas
     * configs"). Os três mostram a mesma coisa (agora, próximas horas, amanhã), só muda o desenho:
     * FAIXA (padrão): uma faixa de vidro com as próximas horas numa curva; CARTOES: quatro cartões de vidro (inclui sol,
     * lua, umidade e chuva de hoje); SOLTO: sem caixa, a temperatura grande e o tempo dito numa frase.
     * Substitui a escolha antiga "próximas horas / resumo do dia" (12/11), que dividia a informação em dois widgets.
     * Prefs "launcher" (entram no backup). Valor antigo ("horas", "resumo") ou desconhecido vira o padrão.
     */
    const val CHAVE_WIDGET = "protetor_widget"
    const val WIDGET_FAIXA = "faixa"
    const val WIDGET_CARTOES = "cartoes"
    const val WIDGET_SOLTO = "solto"
    val WIDGETS = listOf(WIDGET_FAIXA, WIDGET_CARTOES, WIDGET_SOLTO)

    fun widget(ctx: Context): String =
        normalizarWidget(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE_WIDGET, null))

    fun definirWidget(ctx: Context, v: String) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(CHAVE_WIDGET, normalizarWidget(v)).apply()
    }

    /** O próximo da lista (a linha das Configurações troca com OK). */
    fun proximoWidget(atual: String): String = WIDGETS[(WIDGETS.indexOf(normalizarWidget(atual)) + 1) % WIDGETS.size]

    fun normalizarWidget(v: String?): String = if (v in WIDGETS) v!! else WIDGET_FAIXA

    /** Valor desconhecido ou vazio (backup antigo, digitação) vira o padrão. */
    fun normalizar(v: String?): String = if (v == CLASSICO) CLASSICO else GALERIA
}

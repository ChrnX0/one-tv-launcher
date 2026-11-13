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
     * O que a linha de baixo do protetor mostra (12/11/2026, dono gostou das duas: "coloca como opção"). HORAS (padrão):
     * o tempo de agora e as próximas horas de 2 em 2. RESUMO: o tempo de agora e o resumo do dia (sol, lua, umidade,
     * chuva de hoje, amanhã). As duas sem caixa, soltas sobre a pintura. Prefs "launcher" (entram no backup).
     */
    const val CHAVE_BARRA = "protetor_barra"
    const val BARRA_HORAS = "horas"
    const val BARRA_RESUMO = "resumo"

    fun barra(ctx: Context): String =
        if (ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE_BARRA, null) == BARRA_RESUMO) BARRA_RESUMO else BARRA_HORAS

    fun definirBarra(ctx: Context, v: String) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(CHAVE_BARRA, if (v == BARRA_RESUMO) BARRA_RESUMO else BARRA_HORAS).apply()
    }

    /** Valor desconhecido ou vazio (backup antigo, digitação) vira o padrão. */
    fun normalizar(v: String?): String = if (v == CLASSICO) CLASSICO else GALERIA
}

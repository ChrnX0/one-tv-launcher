package br.paulo.tvlauncher

import android.content.Context

/**
 * O que as ideias de 12/11/2026 fazem quando o processo principal nasce (LauncherApp, fora da thread da tela):
 *  - reagenda os lembretes da grade (atualizar o app apaga os alarmes dele);
 *  - versão nova desde a última abertura: aviso no celular (tipo VERSAO_NOVA, desligado por padrão);
 *  - erro novo gravado pelo RegistroErros desde o último aviso: aviso no celular, sem detalhe (o detalhe fica no painel
 *    > Saúde, filtrado; o aviso não pode levar texto de erro, que às vezes tem endereço ou nome).
 */
object IdeiasInicio {
    private const val PREFS = "ideias_12_11"

    fun aoIniciar(ctx: Context) {
        val app = ctx.applicationContext
        LembretesTv.agendar(app)
        Presenca.agendar(app)
        Retrospectiva.agendar(app)
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versao = BuildConfig.VERSION_CODE.toLong()
        val antes = p.getLong("versao", 0L)
        if (antes != 0L && antes != versao)
            Avisos.enviar(app, Avisos.Tipo.VERSAO_NOVA, "Launcher atualizado", "Versão ${BuildConfig.VERSION_NAME} instalada na TV.")
        val erros = RegistroErros.pasta(app).listFiles { f -> f.name.startsWith("erro-") && f.name.endsWith(".txt") }.orEmpty()
        val visto = p.getLong("erro_visto", 0L)
        val novos = erros.count { it.lastModified() > visto }
        if (visto != 0L && novos > 0)
            Avisos.enviar(app, Avisos.Tipo.ERRO_NOVO, "Erro no launcher",
                if (novos == 1) "1 erro novo. Veja em Painel > Saúde." else "$novos erros novos. Veja em Painel > Saúde.")
        p.edit().putLong("versao", versao)
            .putLong("erro_visto", maxOf(visto, erros.maxOfOrNull { it.lastModified() } ?: 0L, if (visto == 0L) 1L else 0L))
            .apply()
    }
}

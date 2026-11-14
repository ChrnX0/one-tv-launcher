package br.paulo.tvlauncher

/**
 * Frases traduzidas para os `object` que não recebem um Context (IconesClima, Lua...). Usa o Context do app, guardado
 * em [LauncherApp.instancia] logo que o processo do launcher abre. O idioma vem do aparelho: inglês é o padrão
 * (res/values), português fica em res/values-pt (vale para pt-BR e pt-PT).
 */
fun txt(id: Int, vararg args: Any): String = LauncherApp.instancia.getString(id, *args)

/** Plural traduzido (res/values/plurals): "1 minute" / "5 minutes", "1 minuto" / "5 minutos". */
fun txtN(id: Int, n: Int): String = LauncherApp.instancia.resources.getQuantityString(id, n, n)

/** "10 minutos", "1 hora e 30 minutos", "45 segundos", no idioma da TV. Para avisos e confirmações na tela. */
fun duracaoFalada(segundos: Int): String {
    val h = segundos / 3600; val m = (segundos % 3600) / 60; val s = segundos % 60
    val partes = mutableListOf<String>()
    if (h > 0) partes += txtN(R.plurals.dur_horas, h)
    if (m > 0) partes += txtN(R.plurals.dur_minutos, m)
    if (s > 0) partes += txtN(R.plurals.dur_segundos, s)
    return partes.joinToString(txt(R.string.dur_e)).ifEmpty { txtN(R.plurals.dur_segundos, 0) }
}

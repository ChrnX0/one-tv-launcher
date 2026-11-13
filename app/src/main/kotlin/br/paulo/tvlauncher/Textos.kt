package br.paulo.tvlauncher

/**
 * Frases traduzidas para os `object` que não recebem um Context (IconesClima, Lua...). Usa o Context do app, guardado
 * em [LauncherApp.instancia] logo que o processo do launcher abre. O idioma vem do aparelho: inglês é o padrão
 * (res/values), português fica em res/values-pt (vale para pt-BR e pt-PT).
 */
fun txt(id: Int, vararg args: Any): String = LauncherApp.instancia.getString(id, *args)

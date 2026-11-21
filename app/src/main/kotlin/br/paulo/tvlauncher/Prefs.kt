package br.paulo.tvlauncher

/**
 * Nomes dos arquivos de SharedPreferences usados em mais de um lugar (refatoração de 21/11/2026). Antes cada arquivo
 * escrevia o nome à mão ("launcher" em 33 lugares); um erro de digitação criava um arquivo novo, vazio, sem aviso.
 * Os VALORES nunca mudam: são os arquivos já gravados nas TVs (trocar = perder os ajustes de quem já usa).
 */
object Prefs {
    const val LAUNCHER = "launcher"
    const val PLAYER = "player"
    const val CLIMA = "clima"
    const val TIMER = "timer"
    const val PRESENCA = "presenca"
    const val EXTRAS = "extras"
    const val DESPERTADOR = "despertador"
    const val CONFIG_PIN = "config_pin"
}

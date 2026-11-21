package br.paulo.tvlauncher.network

/**
 * Insistir em ligar a TV pelo HDMI-CEC enquanto o alarme toca (18/11/2026, Paulo: "o alarme tocou e não ligou a TV").
 *
 * O que o log da Sala mostrou: a LG foi desligada pelo controle dela às 15:25:15, o alarme tocou às 15:26:00, o
 * Chromecast acordou e mandou o "One Touch Play" (o pedido "liga e me mostra"), mas a LG não respondeu
 * ("Failed to complete One Touch Play"). O Android tenta UMA vez só. Agora o alarme repete o pedido algumas vezes
 * pelo nosso shell (`cmd hdmi_control onetouchplay`), enquanto ninguém apertou nada.
 */
object TvWakeRetry {
    /** Quando repetir, em ms depois de a tela do alarme abrir. A 1ª tentativa do Android leva ~20 s para desistir. */
    val ESPERAS_MS = longArrayOf(25_000, 50_000, 90_000, 150_000, 240_000)

    const val COMANDO = "cmd hdmi_control onetouchplay"

    /** A próxima espera depois da tentativa [feitas] (0 = nenhuma ainda), ou null quando acabaram. */
    fun proxima(feitas: Int): Long? = ESPERAS_MS.getOrNull(feitas)?.let { it - (if (feitas == 0) 0 else ESPERAS_MS[feitas - 1]) }

    /** Vale tentar de novo? Só enquanto toca, sem ninguém ter mexido no controle (quem mexeu já está vendo a TV). */
    fun tentar(tocando: Boolean, alguemMexeu: Boolean): Boolean = tocando && !alguemMexeu
}

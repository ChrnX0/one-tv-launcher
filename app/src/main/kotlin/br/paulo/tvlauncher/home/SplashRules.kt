package br.paulo.tvlauncher.home

/**
 * Quando a abertura com a logo aparece (18/11/2026): as REGRAS puras (SplashRulesTest). O desenho é o [SplashOverlay].
 *
 * "Quando a TV liga" no Chromecast quase nunca é um boot: a TV acorda do repouso. Então a logo aparece:
 *  - no boot: o processo da HOME nasceu há pouco desde que o aparelho ligou ([BOOT_WINDOW_MS]);
 *  - ao acordar: a tela acendeu depois de dormir pelo menos [MIN_SLEEP_MS] com a HOME na frente.
 * Uma piscada de tela (protetor fechando, 30 s apagada) não conta, e a opção desligada nas Configurações nunca mostra.
 * A HOME já está desenhada por baixo: a logo só cobre por [HOLD_MS] e some, então "liga e está pronta" continua valendo.
 */
object SplashRules {
    const val PREF = "brand_splash"
    /** Processo da HOME aberto até 3 min depois de ligar o aparelho = boot (o Chromecast leva ~40 s até a HOME). */
    const val BOOT_WINDOW_MS = 3 * 60_000L
    /** Dormiu menos que isto (tela apagou e acendeu logo): não é "ligar a TV". */
    const val MIN_SLEEP_MS = 5 * 60_000L
    /** Tempo parada na tela, e o esmaecimento depois: ~1,5 s no total (pedido: 1 a 2 s). */
    const val HOLD_MS = 1100L
    const val FADE_MS = 400L

    fun onBoot(enabled: Boolean, sinceBootMs: Long): Boolean = enabled && sinceBootMs in 0..BOOT_WINDOW_MS

    fun onWake(enabled: Boolean, sleptMs: Long, homeInFront: Boolean): Boolean =
        enabled && homeInFront && sleptMs >= MIN_SLEEP_MS
}

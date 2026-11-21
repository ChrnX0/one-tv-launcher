package br.paulo.tvlauncher.home

import android.content.Context

/**
 * O "mundo" de cada tela (18/11/2026, identidade visual): as categorias da cruz já têm cor (Assistir laranja, Ouvir
 * turquesa, Jogar violeta) e a HOME já tinge a arte de fundo com ela. Agora a cor continua DENTRO do mundo: Filmes,
 * Séries, TV, Na rede... abrem com o brilho quente de Assistir; Rádio e Podcasts com o turquesa de Ouvir; Jogos com o
 * violeta. Quem entra numa tela sabe onde está pela cor do ar, sem ler nada (pensado também para quem enxerga pouco).
 *
 * É só o brilho de cima, à esquerda, do grafite padrão ([desenharFundoEscuro]); o resto da tela (texto, foco branco,
 * acento laranja) não muda. As cores são as mesmas da HOME, mais escuras para não brigar com o texto.
 */
enum class World(val glow: Int) {
    /** Configurações, Casa e o resto: o brilho frio de sempre. */
    NEUTRAL(0x4A7388),
    // Assistir usa o azul da cruz (crítica de design 19/11/2026, decisão 4): o brilho marrom-alaranjado de Filmes e
    // Séries saiu; o laranja fica só para marca, foco e progresso (decisão 1). O mundo continua existindo para o futuro.
    WATCH(0x4A7388),
    LISTEN(0x2A9A8E),
    PLAY(0x5257C8);

    companion object {
        private val WATCH_SCREENS = setOf(
            "FilmesActivity", "SeriesActivity", "VodActivity", "PlayerActivity", "MidiaRedeActivity", "ServicosFilmesActivity",
            "BloqueioCanaisActivity", "AddonsActivity", "GravacoesActivity", "ElencoActivity",
        )
        private val LISTEN_SCREENS = setOf("RadioActivity", "PodcastsActivity", "PodcastPlayerActivity")
        private val PLAY_SCREENS = setOf("JogosActivity")

        /** O mundo da tela [ctx] pelo nome da classe dela (o que não é tela conhecida fica no neutro). */
        fun of(ctx: Context?): World = ofScreen(ctx?.javaClass?.simpleName.orEmpty())

        fun ofScreen(name: String): World = when (name) {
            in WATCH_SCREENS -> WATCH
            in LISTEN_SCREENS -> LISTEN
            in PLAY_SCREENS -> PLAY
            else -> NEUTRAL
        }
    }
}

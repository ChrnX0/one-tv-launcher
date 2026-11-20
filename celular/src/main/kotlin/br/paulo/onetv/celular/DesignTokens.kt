// GERADO por scripts/design-tokens.py a partir de design/tokens.json. Não edite à mão: mude o JSON e rode o script.
package br.paulo.onetv.celular

/** Os tokens da marca, iguais na TV, no painel e no celular (o porquê de cada um está em design/tokens.json). */
object DesignTokens {
    /** Fundo da marca (Noite). Toda superfície escura começa aqui. */
    const val NIGHT = 0xFF121528.toInt()
    /** Pé do degradê do fundo e tela de descanso de madrugada: mais escuro para a sala ficar escura. */
    const val NIGHT_DEEP = 0xFF0B0D18.toInt()
    /** Texto da marca (Creme). Nunca branco puro em bloco grande: cansa a vista. */
    const val CREAM = 0xFFFFF3E6.toInt()
    /** Fundo do tema claro (painel e celular no modo claro). */
    const val PAPER = 0xFFFFF8F0.toInt()
    /** O ÚNICO acento: ligado, valor escolhido, progresso e o logo. Nunca botão comum, link ou enfeite. */
    const val ORANGE = 0xFFFF6A2B.toInt()
    /** O acento sobre fundo claro (contraste 4,5:1). */
    const val ORANGE_ON_LIGHT = 0xFFCE3D00.toInt()
    /** O acento no alto contraste (7:1 sobre o Noite). */
    const val ORANGE_HIGH = 0xFFFF9A3C.toInt()
    /** Foco e botão principal: branco, a coisa mais óbvia da tela, igual na TV e no celular. */
    const val FOCUS = 0xFFFFFFFF.toInt()
    /** Algo pede um olhar (chuva, vencimento). Não é acento. */
    const val ATTENTION = 0xFFF5B544.toInt()
    /** Erro e ao vivo. */
    const val ERROR = 0xFFFF5252.toInt()
    /** Estado que está bem (conectado, feito). Nunca ação. */
    const val OK = 0xFF7FD6C2.toInt()
    /** Assistir (TV, Filmes, Séries): a família quente da marca. */
    const val WORLD_WATCH = 0xFFFF7A45.toInt()
    /** Ouvir (Rádio, Podcasts): turquesa. */
    const val WORLD_LISTEN = 0xFF45C2B1.toInt()
    /** Jogar: violeta-azulado. */
    const val WORLD_PLAY = 0xFF8E92FA.toInt()
    /** Aplicativos: verde-folha. */
    const val WORLD_APPS = 0xFF9CD27E.toInt()
    /** Continuar: âmbar, a cor de 'retomar de onde parou'. */
    const val WORLD_CONTINUE = 0xFFF2B84B.toInt()
    /** Do computador: verde-água, o ícone de pasta. */
    const val WORLD_NETWORK = 0xFF6FC3B2.toInt()
    /** Rotina da casa (painel e Configurações): Família e Tela e energia. Azul calmo, a única cor de área que não é de um mundo da cruz; mesma luminosidade dos mundos. */
    const val AREA_HOME = 0xFF7AB8F5.toInt()
    /** Arredondada, lê bem do sofá; é a família do logo. */
    const val FONT_TITLE = "Nunito"
    /** Texto corrido no painel e no celular, onde se lê de perto; a TV usa Nunito em tudo (3 m de distância). */
    const val FONT_TEXT = "Manrope"
    /** Teclas, chips, caixas pequenas. */
    const val RADIUS_SMALL = 8
    /** Cartões e painéis. */
    const val RADIUS_MEDIUM = 16
    /** Botões e a linha em foco. */
    const val RADIUS_PILL = 999
    /** Toda margem e vão é múltiplo de 8 (4 para detalhe fino). */
    const val SPACE = 8
    /** Atraso entre itens que entram um depois do outro. */
    const val MS_STAGGER = 40L
    /** Atraso de intenção (dica), passo de tremida. */
    const val MS_MICRO = 80L
    /** Foco andando, fechar, troca de texto: o controle tem que parecer instantâneo. */
    const val MS_QUICK = 150L
    /** Abrir: uma lista, um painel, uma página entrando. */
    const val MS_FAST = 250L
    /** Um painel ou aviso fechando com calma. */
    const val MS_MEDIUM = 350L
    /** Um painel grande abrindo; conteúdo que aparece depois de carregar. */
    const val MS_SLOW = 400L
    /** Todo abrir, fechar e deslizar: começa rápido e pousa macio (smooth-out do transitions.dev). */
    val EASE_OUT = floatArrayOf(0.22f, 1f, 0.36f, 1f)
}

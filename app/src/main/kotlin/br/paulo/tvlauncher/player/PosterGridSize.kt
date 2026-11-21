package br.paulo.tvlauncher.player

/**
 * Tamanho dos pôsteres da grade de Filmes (19/11/2026, pedido do Paulo: "tem que ter um uso melhor do espaço da tela").
 *
 * Antes: 5 colunas fixas, pôster de 180 dp e o nome em cima de cada um. Na TV (960x540 dp) cabia UMA fileira inteira
 * e só os nomes da fileira de baixo apareciam, cortados. Agora o nome sai de cima do pôster (o título do filme em foco
 * já aparece grande na ficha rápida, e o pôster traz o nome na arte) e o tamanho é calculado pelo espaço que a grade
 * tem de verdade: [FILEIRAS] fileiras inteiras, e quantas colunas couberem com o pôster na proporção 2:3.
 *
 * Lógica pura (só números, em dp), para testar sem Android. Quem chama converte para pixels.
 */
object PosterGridSize {
    const val FILEIRAS = 2
    /** Espaço do card fora o pôster: 4 em cima + barra de progresso (5 + 5) + 6 embaixo. */
    const val EXTRA_CARD = 20
    /** Folga lateral do card (4 + 4): o pôster fica dentro dela. */
    const val FOLGA_LADO = 8
    const val VAO_COLUNAS = 12
    const val VAO_FILEIRAS = 12
    /** Abaixo disto o pôster fica pequeno demais para reconhecer de longe: aí vai uma fileira só, maior. */
    const val POSTER_MINIMO = 110
    const val POSTER_MAXIMO = 240
    const val COLUNAS_MIN = 4
    const val COLUNAS_MAX = 8

    /** [alturaPoster] e [alturaCard] em dp; [colunas] da grade. */
    data class Medida(val colunas: Int, val alturaPoster: Int, val alturaCard: Int)

    /** [largura] e [altura]: a área útil da grade em dp (sem o padding dela). */
    fun calcular(largura: Int, altura: Int): Medida {
        var fileiras = FILEIRAS
        var poster = (altura - VAO_FILEIRAS * (fileiras - 1)) / fileiras - EXTRA_CARD
        if (poster < POSTER_MINIMO) { fileiras = 1; poster = altura - EXTRA_CARD }
        poster = poster.coerceIn(POSTER_MINIMO, POSTER_MAXIMO)
        // Largura que o pôster 2:3 pede; a coluna real estica um pouco (STRETCH_COLUMN_WIDTH) e o recorte central absorve.
        val coluna = poster * 2 / 3 + FOLGA_LADO
        val colunas = ((largura + VAO_COLUNAS) / (coluna + VAO_COLUNAS)).coerceIn(COLUNAS_MIN, COLUNAS_MAX)
        return Medida(colunas, poster, poster + EXTRA_CARD)
    }
}

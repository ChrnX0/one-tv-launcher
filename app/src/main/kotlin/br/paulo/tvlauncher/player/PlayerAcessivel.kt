package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.input.EfeitosSom

/**
 * Player para quem enxerga e ouve pior (14/11/2026, aprovado pelo Paulo): legenda fácil de ler, volume parecido entre
 * canais e filmes, voz mais clara e o modo simples no player. Aqui só as regras puras (PlayerAcessivelTest); quem aplica
 * o som é [EfeitosSom], quem desenha a legenda é a VodActivity.
 *
 * As chaves ficam nas prefs "player" (as mesmas do tamanho da legenda). Tudo começa DESLIGADO: a TV só muda quando o
 * dono escolhe no menu ▼ do player ou nas Configurações.
 */
object PlayerAcessivel {
    const val PREFS = Prefs.PLAYER
    const val LEGENDA_FUNDO = "legenda_fundo"       // fundo preto opaco atrás da letra
    const val LEGENDA_AMARELA = "legenda_amarela"   // letra amarela no lugar da branca
    const val LEGENDA_ALTO = "legenda_alto"         // elevação 6.5: legenda em cima (placar de jogo, letreiro embaixo)
    const val VOLUME_IGUAL = "volume_igual"         // compressor: o baixo sobe, o alto não passa do limite
    const val VOZ_CLARA = "voz_clara"               // equalizador que realça a faixa da fala

    // ---------------------------------------------------------------- 1: legenda

    /** Cores da legenda: texto, fundo (ARGB) e se leva sombra (sem fundo opaco, a sombra separa a letra do vídeo). */
    data class Legenda(val texto: Int, val fundo: Int, val sombra: Boolean)

    const val BRANCO = 0xFFFFFFFF.toInt()
    const val AMARELO = 0xFFFFE34D.toInt()          // amarelo claro: o mais legível sobre preto para quem enxerga pouco
    const val FUNDO_LEVE = 0x8C000000.toInt()       // o de antes (55% de preto)
    const val FUNDO_PRETO = 0xFF000000.toInt()

    fun legenda(fundoPreto: Boolean, amarela: Boolean) =
        Legenda(if (amarela) AMARELO else BRANCO, if (fundoPreto) FUNDO_PRETO else FUNDO_LEVE, sombra = !fundoPreto)

    // ---------------------------------------------------------------- 2 e 3: som

    /** Uma faixa do equalizador: vale até [ateHz] (a anterior termina onde esta começa), com [ganhoDb]. */
    data class Faixa(val ateHz: Float, val ganhoDb: Float)

    /**
     * Voz mais clara: a fala mora entre ~300 Hz e ~4 kHz, e a inteligibilidade (consoantes) entre 1 e 4 kHz. Grave de
     * explosão e trilha sobe menos, a faixa das consoantes sobe 6 dB, o agudo de cima fica como está.
     */
    val FAIXAS_VOZ = listOf(
        Faixa(120f, -6f), Faixa(300f, -3f), Faixa(1_000f, 0f), Faixa(4_000f, 6f), Faixa(8_000f, 3f), Faixa(20_000f, 0f))

    /** Compressor de uma faixa só (o sinal inteiro) para o volume parecido. */
    data class Compressor(val ataqueMs: Float, val soltarMs: Float, val razao: Float, val limiarDb: Float,
                          val joelhoDb: Float, val ganhoDepoisDb: Float)

    /**
     * Volume parecido: acima de -30 dBFS o som é comprimido 4:1 e depois tudo sobe 14 dB. Um canal normal (média perto
     * de -12 dBFS) fica onde estava; um mixado 10 dB mais baixo sobe ~8 dB e chega a 2,5 dB dos outros.
     */
    val COMPRESSOR = Compressor(ataqueMs = 10f, soltarMs = 300f, razao = 4f, limiarDb = -30f, joelhoDb = 6f, ganhoDepoisDb = 14f)

    /** Limitador no fim da corrente: com o ganho e o equalizador, nada passa de -1 dBFS (sem estalo de saturação). */
    val LIMITE_DB = -1f

    /** Nível de saída de um sinal em [entradaDb] dBFS depois do compressor (sem joelho): a regra que o efeito segue. */
    fun saidaComprimida(entradaDb: Float, c: Compressor = COMPRESSOR): Float {
        val comprimido = if (entradaDb <= c.limiarDb) entradaDb else c.limiarDb + (entradaDb - c.limiarDb) / c.razao
        return minOf(comprimido + c.ganhoDepoisDb, LIMITE_DB)
    }

    /** Precisa do efeito de som? Com as duas chaves desligadas, nenhum efeito é criado (som idêntico ao de antes). */
    fun precisaEfeito(volumeIgual: Boolean, vozClara: Boolean) = volumeIgual || vozClara

    // ---------------------------------------------------------------- 4: modo simples no player

    /**
     * Bloco fixo do modo simples: só as linhas de uso diário (Buscar, Favoritos/Interesses, Continuar/Recentes), na
     * ordem de sempre. Filtro, Guia e Assistidos saem: são as que mais confundem quem só quer ver.
     */
    fun <T> blocoSimples(acoes: List<T>, chave: (T) -> String, manter: Set<String>): List<T> = acoes.filter { chave(it) in manter }

    /**
     * Onde a tela abre no modo simples: a primeira das [preferidas] que tiver algo (Favoritos na TV, Continuar em
     * Filmes e Séries), e não a última categoria usada. Nada com conteúdo: [padrao] (Todos).
     */
    fun entradaSimples(preferidas: List<Pair<String, Int>>, padrao: String): String =
        preferidas.firstOrNull { it.second > 0 }?.first ?: padrao

    /** Letra ainda maior no modo simples (multiplica os tamanhos de Estilo.Leitura na coluna e nas linhas). */
    const val ESCALA_SIMPLES = 1.2f
    fun escala(simples: Boolean) = if (simples) ESCALA_SIMPLES else 1f
}

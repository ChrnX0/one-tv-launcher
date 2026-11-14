package br.paulo.tvlauncher

import android.view.KeyEvent

/**
 * Protetor que responde ao controle (ideia 28, 14/11/2026). Antes qualquer tecla fechava (isInteractive = false).
 * Agora: ◀ ▶ andam pelos cartões da Galeria, OK dispensa o aviso escolhido ou pausa/retoma a música, as teclas de
 * volume e de mídia agem sem fechar, e qualquer outra tecla fecha como antes. Lógica pura (teste JVM: TeclasProtetorTest).
 */
object TeclasProtetor {
    /** O que a tecla faz no protetor. */
    enum class Acao { ANTERIOR, PROXIMO, DISPENSAR, ALTERNAR_MUSICA, DEIXAR_PASSAR, FECHAR }

    /** Que cartão está escolhido: recado e notificação podem ser dispensados; "tocando agora" pausa/retoma. */
    enum class Tipo { RECADO, NOTIFICACAO, TOCANDO, OUTRO }

    private val OK = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private val VOLUME = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
    private val MIDIA = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK)

    /**
     * [cartoes] = quantos cartões navegáveis há na tela; [escolhido] = tipo do cartão marcado (null = nenhum);
     * [haMusica] = existe uma sessão de mídia com título (tocando ou pausada).
     */
    fun acao(tecla: Int, cartoes: Int, escolhido: Tipo?, haMusica: Boolean): Acao = when {
        tecla in VOLUME -> Acao.DEIXAR_PASSAR                       // volume não acorda a TV
        tecla in MIDIA -> if (haMusica) Acao.ALTERNAR_MUSICA else Acao.DEIXAR_PASSAR
        tecla == KeyEvent.KEYCODE_DPAD_LEFT && cartoes > 0 -> Acao.ANTERIOR
        tecla == KeyEvent.KEYCODE_DPAD_RIGHT && cartoes > 0 -> Acao.PROXIMO
        tecla in OK -> when (escolhido) {
            Tipo.RECADO, Tipo.NOTIFICACAO -> Acao.DISPENSAR
            Tipo.TOCANDO -> Acao.ALTERNAR_MUSICA
            Tipo.OUTRO -> Acao.FECHAR
            null -> if (haMusica) Acao.ALTERNAR_MUSICA else Acao.FECHAR
        }
        else -> Acao.FECHAR
    }

    /**
     * Novo índice marcado depois de ◀ (delta -1) ou ▶ (+1). -1 = nenhum. A partir de "nenhum", ▶ marca o primeiro e
     * ◀ o último; nas pontas para (não dá a volta: com 2 ou 3 cartões, dar a volta confunde mais do que ajuda).
     */
    fun mover(atual: Int, delta: Int, total: Int): Int {
        if (total <= 0) return -1
        if (atual !in 0 until total) return if (delta > 0) 0 else total - 1
        return (atual + delta).coerceIn(0, total - 1)
    }

    /** Primeiro índice da janela de [visiveis] cartões que mantém [escolhido] à vista (a coluna mostra só 3). */
    fun inicioJanela(escolhido: Int, total: Int, visiveis: Int): Int {
        if (total <= visiveis || escolhido < 0) return 0
        return (escolhido - visiveis + 1).coerceAtLeast(0).coerceAtMost(total - visiveis)
    }

    /** Quanto tempo a marca fica sem tecla nenhuma antes de sumir (volta ao protetor "limpo"). */
    const val MARCA_MS = 10_000L
}

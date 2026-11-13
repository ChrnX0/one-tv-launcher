package br.paulo.tvlauncher

/**
 * Avançar/voltar no vídeo com ◀ ▶ (filmes, séries e a "TV de volta").
 *
 * Toque simples: pula o passo de sempre (ex.: +30 s), na hora.
 * Tecla segurada: o Android repete a tecla ~20 vezes por segundo. Antes, cada repetição pulava 30 s (e depois 2 min),
 * ou seja, 10 a 40 MINUTOS de vídeo por segundo de dedo: impossível parar no ponto certo (relato do Paulo, 13/11/2026).
 * Agora a conta é por TEMPO segurando, não por repetição: começa devagar e acelera em degraus até um teto.
 * Enquanto segura, só a barra anda (o "alvo"); o vídeo pula uma vez, ao soltar. Assim o servidor (IPTV/Jellyfin/SMB)
 * não recebe 20 pedidos de pulo por segundo, e o número na barra fica estável para a pessoa ler.
 *
 * Sem nada de Android aqui dentro, para o teste rodar na JVM (AvancoSeguradoTest).
 */
class AvancoSegurado {
    /** Posição escolhida enquanto a tecla está segurada; -1 = nenhuma (a barra mostra a posição real do vídeo). */
    var alvo = -1L
        private set
    private var ultimoEvento = 0L

    /**
     * Tecla ◀ ou ▶ desceu (ou repetiu).
     * [passo] = pulo do toque simples, com sinal (+30_000 ou -10_000). [repeticao] = KeyEvent.repeatCount.
     * [agora] e [apertouEm] = KeyEvent.eventTime e downTime (relógio do Android, em ms).
     * Devolve a posição para pular JÁ (toque simples) ou -1 (segurando: só o [alvo] mudou).
     */
    fun apertou(posicao: Long, duracao: Long, passo: Long, repeticao: Int, agora: Long, apertouEm: Long): Long {
        if (repeticao == 0) {
            alvo = -1L
            ultimoEvento = agora
            return limitar(posicao + passo, duracao)
        }
        val base = if (alvo >= 0) alvo else posicao
        val sinal = if (passo >= 0) 1 else -1
        alvo = limitar(base + sinal * deslocamento(agora - apertouEm, agora - ultimoEvento), duracao)
        ultimoEvento = agora
        return -1L
    }

    /** Soltou a tecla: devolve para onde pular (ou -1 se foi só um toque, que já pulou). */
    fun soltou(): Long = alvo.also { alvo = -1L }

    /** Outra tecla ou a tela saiu: esquece o alvo sem pular. */
    fun cancelar() { alvo = -1L }

    companion object {
        /** Teto: 5 minutos de vídeo por segundo segurando (um filme de 2 h atravessa em ~30 s). */
        const val TETO_MS_POR_S = 300_000L

        /**
         * Quantos ms de vídeo andam por segundo de tecla segurada, conforme há quanto tempo está segurando.
         * Degraus: 20 s/s nos 2 primeiros segundos (dá para ajustar alguns minutos com precisão), depois 40, 80, 150
         * e o teto de 300 s/s a partir de 9 s. Ex.: segurar 5 s anda ~3 min; 10 s ~17 min; 20 s ~1 h 07.
         */
        fun velocidade(segurandoMs: Long): Long = when {
            segurandoMs < 2_000 -> 20_000L
            segurandoMs < 4_000 -> 40_000L
            segurandoMs < 6_000 -> 80_000L
            segurandoMs < 9_000 -> 150_000L
            else -> TETO_MS_POR_S
        }

        /**
         * Quanto andar nesta repetição: velocidade × tempo desde a repetição anterior.
         * O intervalo é limitado a 200 ms: se a TV engasgar e as repetições atrasarem, não dá um salto grande de uma vez.
         */
        fun deslocamento(segurandoMs: Long, desdeAnteriorMs: Long): Long =
            velocidade(segurandoMs) * desdeAnteriorMs.coerceIn(0L, 200L) / 1_000L

        /** Nunca antes do começo nem depois do fim (para 1 s antes do fim, para não encerrar o vídeo sem querer). */
        fun limitar(posicao: Long, duracao: Long): Long = posicao.coerceIn(0L, (duracao - 1_000L).coerceAtLeast(0L))
    }
}

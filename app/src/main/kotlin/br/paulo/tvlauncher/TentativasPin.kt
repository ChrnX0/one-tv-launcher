package br.paulo.tvlauncher

/**
 * Erros do PIN no player, em Filmes e em Séries (player-11, 10/11/2026). Antes o contador ficava na memória da janela
 * (PlayerJanelas): fechar e abrir o player zerava, e dava para tentar PINs sem fim de 5 em 5. Agora fica guardado
 * (preferência "player_pin", ver PlayerJanelas) e o bloqueio cresce: 5 erros = 1 min de espera, e cada erro a mais
 * dobra (2, 4, 8... até 1 h). Acertar zera. Um dia inteiro sem errar também zera (quem errou ontem não paga hoje).
 *
 * Só lógica pura (sem Android), testada em TentativasPinTest.
 */
object TentativasPin {
    const val ERROS_LIVRES = 5
    const val ESPERA_BASE_MS = 60_000L
    const val ESPERA_MAX_MS = 60 * 60_000L
    const val ESQUECER_MS = 24 * 60 * 60_000L

    /** erros seguidos, até quando está bloqueado e quando foi o último erro (relógio da TV, em ms). */
    data class Estado(val erros: Int = 0, val bloqueadoAte: Long = 0, val ultimoErro: Long = 0)

    /** O estado de agora: esquece os erros velhos e conserta um bloqueio que ficou longo demais (relógio voltou). */
    fun normalizar(e: Estado, agora: Long): Estado = when {
        e.erros > 0 && agora - e.ultimoErro > ESQUECER_MS -> Estado()
        e.bloqueadoAte - agora > ESPERA_MAX_MS -> e.copy(bloqueadoAte = agora + ESPERA_MAX_MS)
        else -> e
    }

    /** Quanto falta para poder tentar de novo (0 = pode tentar já). */
    fun espera(e: Estado, agora: Long): Long = (normalizar(e, agora).bloqueadoAte - agora).coerceAtLeast(0)

    /** Registra um erro. Do 5º erro em diante, bloqueia por 1 min, 2, 4... até 1 h. */
    fun errou(e: Estado, agora: Long): Estado {
        val n = normalizar(e, agora).erros + 1
        if (n < ERROS_LIVRES) return Estado(n, 0, agora)
        val dobras = (n - ERROS_LIVRES).coerceAtMost(10)
        val ms = (ESPERA_BASE_MS shl dobras).coerceAtMost(ESPERA_MAX_MS)
        return Estado(n, agora + ms, agora)
    }

    /** Acertou: zera tudo. */
    fun acertou(): Estado = Estado()

    /** Minutos para mostrar na tela (arredonda para cima: 10 s de espera aparece como "1 min"). */
    fun minutos(esperaMs: Long): Int = ((esperaMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
}

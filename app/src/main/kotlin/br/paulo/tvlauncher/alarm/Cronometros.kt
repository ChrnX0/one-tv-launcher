package br.paulo.tvlauncher.alarm

/**
 * Cronômetros da onda 0 da elevação (17/11/2026), só a conta (CronometrosTest):
 *  - [TrocaCanal] (5.1): da tecla até o primeiro quadro do canal novo;
 *  - [Instalacao] (20.4): da primeira abertura do launcher até o primeiro canal tocando;
 *  - [SessaoVod] (players, proposta C, item 14, 20/11/2026): filmes e séries, da tela aberta ao primeiro quadro, e as
 *    travadas e quadros perdidos por tempo de vídeo tocando.
 */
object Cronometros {
    /**
     * Uma troca por vez: a tecla de um canal novo esquece a anterior (zapear rápido mede só o canal em que parou).
     * Relógio monotônico (elapsedRealtime) passado de fora, para o teste.
     */
    class TrocaCanal(private val limiteMs: Long = LIMITE_MS) {
        private var canal: String? = null
        private var desde = 0L

        fun tecla(id: String, agora: Long) { canal = id; desde = agora }

        /** Desistiu (erro, saiu do player, canal bloqueado): esta troca não entra no placar. */
        fun cancelar() { canal = null }

        /** O primeiro quadro do canal [id] apareceu: devolve o tempo (uma vez só) ou null se não havia troca dele. */
        fun primeiroQuadro(id: String?, agora: Long): Long? {
            val c = canal ?: return null
            canal = null
            if (id != c) return null
            val ms = agora - desde
            return ms.takeIf { it in 0..limiteMs }
        }

        val medindo get() = canal != null
    }

    /** Mais que isso não é "troca lenta", é canal que não abriu (vai para o 5.2/2.3, não para a mediana). */
    const val LIMITE_MS = 30_000L

    object Instalacao {
        /** Uma instalação que passou de um dia foi deixada no meio: o número não diz nada sobre os 5 minutos. */
        const val LIMITE_MS = 24 * 60 * 60_000L

        /** null = não dá para medir (sem início, já medida, relógio voltou ou ficou parada no meio). */
        fun duracao(inicio: Long, fim: Long, jaMedida: Boolean): Long? {
            if (inicio <= 0 || jaMedida) return null
            val d = fim - inicio
            return d.takeIf { it in 1..LIMITE_MS }
        }
    }

    /**
     * Um filme ou episódio aberto. Relógio monotônico de fora (teste). Travada = o vídeo já tinha aparecido, a pessoa
     * queria ver (playWhenReady) e ele parou para carregar SEM ter sido pulado: carregar depois de ◀ ▶ não é travada.
     */
    class SessaoVod {
        private var abriuEm = 0L
        private var apareceu = false
        private var pulou = false
        private var tocandoDesde = 0L
        private var tocadoMs = 0L
        var travadas = 0
            private set

        fun abriu(agora: Long) { if (abriuEm == 0L) abriuEm = agora }

        /** O primeiro quadro: o tempo desde [abriu], uma vez só por sessão (voltar de outra tela não conta de novo). */
        fun primeiroQuadro(agora: Long): Long? {
            if (apareceu || abriuEm == 0L) return null
            apareceu = true
            return (agora - abriuEm).takeIf { it in 0..LIMITE_MS }
        }

        fun pulou() { pulou = true }

        fun carregando(querTocar: Boolean) {
            if (apareceu && querTocar && !pulou) travadas++
            pulou = false
        }

        fun pronto() { pulou = false }

        fun tocando(sim: Boolean, agora: Long) {
            if (sim) { if (tocandoDesde == 0L) tocandoDesde = agora }
            else if (tocandoDesde > 0L) { tocadoMs += (agora - tocandoDesde).coerceAtLeast(0); tocandoDesde = 0L }
        }

        /**
         * Fecha o trecho (a tela saiu da frente) e devolve as taxas, ou null se tocou menos de [MIN_TOCADO_MS] (2 min de
         * vídeo com uma travada viraria "30 por hora"). Zera as contas: a volta à tela começa um trecho novo.
         */
        fun fechar(agora: Long, perdidos: Int): Resumo? {
            tocando(false, agora)
            val t = tocadoMs
            val r = if (t < MIN_TOCADO_MS) null else Resumo(travadas * 3_600_000L / t, perdidos.coerceAtLeast(0) * 60_000L / t)
            tocadoMs = 0L; travadas = 0
            return r
        }
    }

    /** [travadasPorHora] e [perdidosPorMin] de vídeo tocando (arredondados para baixo). */
    data class Resumo(val travadasPorHora: Long, val perdidosPorMin: Long)

    const val MIN_TOCADO_MS = 5 * 60_000L
}

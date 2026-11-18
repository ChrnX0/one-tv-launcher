package br.paulo.tvlauncher

/**
 * Cronômetros da onda 0 da elevação (17/11/2026), só a conta (CronometrosTest):
 *  - [TrocaCanal] (5.1): da tecla até o primeiro quadro do canal novo;
 *  - [Instalacao] (20.4): da primeira abertura do launcher até o primeiro canal tocando.
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
}

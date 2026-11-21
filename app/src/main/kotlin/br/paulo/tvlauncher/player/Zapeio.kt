package br.paulo.tvlauncher.player

/**
 * Zapear sem sair (elevação 5.14, onda 1, 17/11/2026): a conta do ▲▼ segurado, sem Android (ZapeioTest).
 *
 * Toque curto em ▲ ou ▼: troca um canal ao soltar (como antes; a diferença é o tempo de soltar a tecla, ~0,1 s).
 * Segurando: a faixa passa pelos canais (nome, programa, quanto falta) SEM trocar o vídeo; ao soltar, troca uma vez
 * só, no canal que a faixa mostrava. A repetição do controle (~20 por segundo) andaria rápido demais para ler: anda
 * no máximo um canal a cada [intervaloMs].
 */
class Zapeio(private val intervaloMs: Long = INTERVALO_MS) {
    companion object { const val INTERVALO_MS = 300L }

    private var passos = 0
    private var ultimo = 0L
    /** true depois que a faixa começou a andar (a tecla está segurada). */
    var segurando = false
        private set

    /**
     * Tecla descendo ([repeticao] = KeyEvent.repeatCount). Devolve quantos canais a faixa deve mostrar à frente do
     * atual (null = nada a mostrar agora: toque simples ou repetição cedo demais).
     */
    fun apertou(passo: Int, repeticao: Int, agora: Long): Int? {
        if (repeticao == 0) { passos = passo; ultimo = agora; segurando = false; return null }
        // Primeira repetição: a faixa mostra o canal que o toque já ia trocar (o próximo), sem andar mais um.
        if (!segurando) { segurando = true; ultimo = agora; return passos }
        if (agora - ultimo < intervaloMs) return null
        // Mudou de direção segurando (soltou ▲ e já segurou ▼): continua do ponto em que a faixa está.
        passos += passo; ultimo = agora
        return passos
    }

    /** Tecla subiu: quantos canais trocar (null = nada; 0 = voltou ao mesmo canal segurando). */
    fun soltou(): Int? {
        val p = passos
        passos = 0; segurando = false
        return p.takeIf { it != 0 }
    }

    /** Outra coisa aconteceu (lista abriu, saiu do player): esquece. */
    fun cancelar() { passos = 0; segurando = false }
}

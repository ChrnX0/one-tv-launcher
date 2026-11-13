package br.paulo.tvlauncher

/**
 * Quanto tempo um aviso da TV continua como cartão no protetor de tela (12/11/2026, dono: "mostrar notificações
 * persistentes apenas para coisas que precisem de atenção e deixar as outras, tipo 'o Claude olhou a tela', apenas com
 * um timer limitado para logo sumir").
 *
 * Antes, TODO aviso (até a confirmação de 5 s "O Claude olhou a tela") virava cartão no protetor por 6 h.
 * Agora cada aviso diz a sua classe. Puro (teste JVM em AvisoRegrasTest).
 */
object AvisoRegras {
    enum class Fica(val minutos: Int) {
        /** Confirmação e informação do momento (Claude olhou a tela, timer, hora de dormir, resumo da noite): some logo. */
        PASSAGEIRO(2),
        /** Previsão de chuva: vale até a chuva passar, não o dia todo. */
        CHUVA(2 * 60),
        /** Pede atenção de alguém (recado, despertador que falhou, campainha, aparelho novo na rede, atualização). */
        ATENCAO(6 * 60),
    }

    /** Milissegundos que o cartão ainda fica no protetor (0 = já saiu). Classe desconhecida (aviso antigo) = passageiro. */
    fun restanteMs(em: Long, fica: String?, agora: Long): Long {
        val min = Fica.values().firstOrNull { it.name == fica }?.minutos ?: Fica.PASSAGEIRO.minutos
        val passou = agora - em
        if (passou < 0) return 0   // relógio voltou: não arrisca um cartão preso
        return (min * 60_000L - passou).coerceAtLeast(0)
    }
}

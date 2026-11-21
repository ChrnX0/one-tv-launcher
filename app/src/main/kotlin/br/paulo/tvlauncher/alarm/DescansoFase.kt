package br.paulo.tvlauncher.alarm

import br.paulo.tvlauncher.DesignTokens

/**
 * A tela de descanso muda com a hora do dia (estudo global 20/11/2026, ideia 9). Lógica pura, testada em DescansoFaseTest.
 *
 * Por quê: era a mesma tela às 4 h e às 15 h, com o logo laranja parado no mesmo canto por horas (o único laranja da
 * tela, e em TV OLED imagem parada por horas marca a tela).
 * Para quê: de madrugada a TV não ilumina a sala; de manhã responde "como está o dia?" de relance; à tarde é quadro.
 * Como: cada fase diz se o logo aparece, quanto a informação acende, quanto véu escuro e quanto tom quente vão por cima,
 * e se o resumo do dia (saudação, agenda, tempo) fica sempre à mostra.
 */
enum class DescansoFase(
    /** O logo Sofaly no canto. Madrugada: some (não é ação nem progresso, e fica parado horas). */
    val logo: Boolean,
    /** Opacidade das informações (hora, data, avisos). */
    val brilhoInfo: Float,
    /** Véu preto por cima do fundo: 0 = nenhum. Madrugada escurece a pintura. */
    val veu: Int,
    /** Tom âmbar por cima de tudo (luz quente, que atrapalha menos o sono): 0 = nenhum. */
    val quente: Int,
    /** O resumo do dia fica à mostra mesmo no modo "só a hora". */
    val resumoSempre: Boolean,
) {
    MADRUGADA(logo = false, brilhoInfo = 0.6f, veu = 0xB3, quente = 0x22, resumoSempre = false),
    MANHA(logo = true, brilhoInfo = 1f, veu = 0, quente = 0, resumoSempre = true),
    TARDE(logo = true, brilhoInfo = 1f, veu = 0, quente = 0, resumoSempre = false),
    NOITE(logo = true, brilhoInfo = 0.85f, veu = 0x4D, quente = 0x14, resumoSempre = false);

    companion object {
        /** 23 h às 5 h madrugada; 6 h às 10 h manhã; 11 h às 17 h tarde; 18 h às 22 h noite. */
        fun de(hora: Int): DescansoFase = when (hora) {
            in 6..10 -> MANHA
            in 11..17 -> TARDE
            in 18..22 -> NOITE
            else -> MADRUGADA
        }

        /** Só para o teste de tela: fixa a hora (null = a hora de verdade). */
        @Volatile var horaFixa: Int? = null

        fun agora(): DescansoFase = de(horaFixa ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))

        /** O âmbar do tom quente (a cor de "attention" dos tokens, que já é quente) com a opacidade da fase. */
        fun corQuente(f: DescansoFase): Int = (f.quente shl 24) or (DesignTokens.ATTENTION and 0xFFFFFF)
    }
}

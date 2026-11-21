package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.player.VariantesCanal.Nivel

/**
 * Qual variante do canal tocar (pedido do Paulo, 18/11/2026: "escolha a resolução ideal sem o usuário perceber, como o
 * YouTube"). Só lógica pura, testada em QualidadeAutoTest; quem mede e troca é a PlayerActivity.
 *
 * Como decide (do mais forte para o mais fraco):
 *  1. escolha manual do usuário para aquele canal (Opções > Qualidade) vence tudo;
 *  2. nunca acima do que a TELA mostra: 4K numa TV Full HD só gasta internet (a Sala é Full HD);
 *  3. nunca acima do [teto] que as travadas deixaram (cai um nível a cada travada séria; volta a subir sozinho depois de
 *     [TETO_MS] sem travar, na PRÓXIMA troca de canal, para não recarregar o vídeo no meio do programa);
 *  4. 4K só com banda medida que aguente ([BANDA_UHD]); sem medida, começa em Full HD.
 *
 * Por que não sobe no meio do programa: trocar de variante é abrir outro endereço (1 a 2 s de tela preta). Descer no
 * meio vale (a imagem já estava travando); subir no meio seria um "piscar" à toa. Lista que manda UM endereço HLS com
 * várias qualidades dentro já troca sem piscar: isso o Media3 faz sozinho.
 */
object QualidadeAuto {
    /** Banda (bits/s) para liberar 4K: os 4K de IPTV pedem 15 a 25 Mbit/s; com folga. */
    const val BANDA_UHD = 25_000_000L
    /** Uma travada mais longa que isso já conta como séria (uma só basta para descer). */
    const val TRAVADA_LONGA_MS = 4_000L
    /** Duas travadas curtas dentro desta janela também contam. */
    const val JANELA_TRAVADAS_MS = 60_000L
    /** Quanto tempo o teto vale sem travar de novo: depois, sobe um nível. */
    const val TETO_MS = 30 * 60_000L

    /**
     * Índice da variante (a lista vem da MELHOR para a pior, como [VariantesCanal.agrupar] entrega).
     * @param manual nível escolhido pelo usuário para este canal (null = automático)
     * @param tela o maior nível que a tela mostra
     * @param teto o maior nível que as travadas deixam (null = sem teto)
     * @param banda maior banda já medida recentemente, em bits/s (null = não medida)
     */
    fun escolher(niveis: List<Nivel>, manual: Nivel? = null, tela: Nivel = Nivel.FHD, teto: Nivel? = null, banda: Long? = null): Int {
        if (niveis.isEmpty()) return -1
        if (manual != null) {
            niveis.indexOf(manual).takeIf { it >= 0 }?.let { return it }
            // O nível sumiu da lista: o mais perto abaixo; não tem, o mais fraco que houver.
            return melhorAte(niveis, manual)
        }
        var limite = minOf(tela, teto ?: Nivel.UHD)
        if (limite == Nivel.UHD && (banda == null || banda < BANDA_UHD)) limite = Nivel.FHD
        return melhorAte(niveis, limite)
    }

    /**
     * A melhor que cabe no [limite] (empate: a 1ª, que é a principal da lista); nenhuma cabe (só tem 4K e a tela é HD),
     * a mais fraca. Não supõe a lista ordenada: o nível pode ter sido corrigido pela resolução real do vídeo.
     */
    private fun melhorAte(niveis: List<Nivel>, limite: Nivel): Int {
        var melhor = -1
        for (i in niveis.indices) if (niveis[i] <= limite && (melhor < 0 || niveis[i] > niveis[melhor])) melhor = i
        if (melhor >= 0) return melhor
        var fraca = 0
        for (i in niveis.indices) if (niveis[i] < niveis[fraca]) fraca = i
        return fraca
    }

    /** Nível pela altura em pixels (da tela ou do vídeo que chegou). 0 = não sabe. */
    fun nivelPelaAltura(alturaPx: Int): Nivel? = when {
        alturaPx >= 2000 -> Nivel.UHD
        alturaPx >= 1000 -> Nivel.FHD
        alturaPx >= 700 -> Nivel.HD
        alturaPx > 0 -> Nivel.SD
        else -> null
    }

    /** Nível máximo da tela pela altura física do melhor modo que ela aceita (não sabe = Full HD, o comum). */
    fun nivelDaTela(alturaPx: Int): Nivel = nivelPelaAltura(alturaPx) ?: Nivel.FHD

    /** Um nível abaixo do que está tocando (SEM_MARCA fica abaixo de HD; abaixo de SD não há). */
    fun abaixo(n: Nivel): Nivel = Nivel.entries[(n.ordinal - 1).coerceAtLeast(0)]

    /** O teto em vigor: some depois de [TETO_MS] sem travar (e então sobe um nível de cada vez). */
    fun tetoAgora(teto: Nivel?, desde: Long, agora: Long): Nivel? {
        if (teto == null) return null
        val passos = ((agora - desde) / TETO_MS).toInt()
        if (passos <= 0) return teto
        val sobe = teto.ordinal + passos
        return if (sobe >= Nivel.UHD.ordinal) null else Nivel.entries[sobe]
    }

    /**
     * Conta as travadas de um canal (só depois de ele já ter começado a tocar). Devolve true quando é hora de descer:
     * uma travada longa, ou duas curtas em [JANELA_TRAVADAS_MS].
     */
    class Travadas {
        private val curtas = ArrayDeque<Long>()
        fun zerar() = curtas.clear()
        fun registrar(agora: Long, duracaoMs: Long): Boolean {
            if (duracaoMs >= TRAVADA_LONGA_MS) { curtas.clear(); return true }
            while (curtas.isNotEmpty() && agora - curtas.first() > JANELA_TRAVADAS_MS) curtas.removeFirst()
            curtas.addLast(agora)
            if (curtas.size >= 2) { curtas.clear(); return true }
            return false
        }
    }
}

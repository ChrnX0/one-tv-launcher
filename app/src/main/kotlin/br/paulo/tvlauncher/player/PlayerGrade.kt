package br.paulo.tvlauncher.player

/**
 * Player universal, guia em grade (06/11/2026): a matemática da grade (canais × horários). Só lógica pura, testada em
 * PlayerGradeTest. Quem desenha é a PlayerGradeView.
 *
 * O cursor da grade é um INSTANTE (ms), não uma coluna: ◀ ▶ pulam de programa em programa da linha, e ▲ ▼ trocam de
 * canal mantendo o instante, caindo no programa que passa naquele horário no canal novo (como nas grades de TV a cabo).
 * O passado não interessa (não há "assistir de novo"): o cursor e a janela nunca vão antes de agora.
 */
object PlayerGrade {
    /**
     * Elevação 16.1: o que o leitor de tela fala na célula do guia (a grade é desenhada à mão e não tem views).
     * "5, Globo. Jornal Nacional, 20:30 a 21:15" ou "5, Globo. Sem guia".
     */
    fun falaLeitor(numero: Int, canal: String, programa: String?, inicio: String?, fim: String?, semGuia: String): String =
        "$numero, $canal. " + if (programa.isNullOrBlank()) semGuia else listOfNotNull(programa, if (inicio != null && fim != null) "$inicio – $fim" else null).joinToString(", ")

    /** 16.8: no árabe a régua do tempo anda para a esquerda; a seta da direita volta no tempo. */
    fun passoDaSeta(direita: Boolean, rtl: Boolean): Int = if (direita != rtl) +1 else -1

    const val FAIXA = 30 * 60_000L           // largura de uma coluna da régua (30 min)
    const val JANELA = 3 * 60 * 60_000L      // quanto tempo cabe na tela (3 h)
    const val ALCANCE = 14 * 60 * 60_000L    // até onde dá para ir (o guia guarda 14 h à frente desde a auditoria de 10/11/2026)

    /** Começo da faixa de 30 min que contém o instante. Os fusos do Brasil são horas cheias: 21:47 -> 21:30 local. */
    fun inicioFaixa(instante: Long): Long = instante - Math.floorMod(instante, FAIXA)

    /** O programa no ar em `instante`, ou null (buraco no guia). */
    fun programaEm(ps: List<Programa>, instante: Long): Programa? = ps.firstOrNull { instante >= it.inicio && instante < it.fim }

    /** Programas que aparecem (mesmo que em parte) na janela [ini, ini + JANELA). */
    fun naJanela(ps: List<Programa>, ini: Long): List<Programa> = ps.filter { it.fim > ini && it.inicio < ini + JANELA }

    /**
     * ◀ ▶: o novo instante do cursor. Para a direita, o começo do próximo programa; para a esquerda, o do anterior
     * (mas nunca antes de `agora`). Linha sem guia, ou fim do guia: anda uma faixa de 30 min.
     */
    fun mover(ps: List<Programa>, foco: Long, passo: Int, agora: Long): Long {
        val atual = programaEm(ps, foco)
        val novo = if (passo > 0) {
            val limite = atual?.fim ?: (foco + 1)
            ps.firstOrNull { it.inicio >= limite }?.inicio ?: (inicioFaixa(foco) + FAIXA)
        } else {
            val limite = atual?.inicio ?: foco
            ps.lastOrNull { it.fim <= limite }?.inicio ?: (foco - FAIXA)
        }
        return novo.coerceIn(agora, agora + ALCANCE)
    }

    /** O começo da janela para o cursor ficar visível: só anda quando o cursor sai dela, e nunca antes da faixa de agora. */
    fun ajustarJanela(ini: Long, foco: Long, agora: Long): Long {
        val minimo = inicioFaixa(agora)
        val novo = when {
            foco < ini -> inicioFaixa(foco)
            foco >= ini + JANELA -> inicioFaixa(foco) - JANELA + FAIXA * 2   // o programa novo entra com folga à direita
            else -> ini
        }
        return novo.coerceAtLeast(minimo)
    }

    /** ▲ ▼: a linha nova (para no primeiro e no último canal, sem dar a volta: numa grade a volta desorienta). */
    fun moverLinha(linha: Int, passo: Int, total: Int): Int = if (total == 0) 0 else (linha + passo).coerceIn(0, total - 1)

    fun ajustarTopo(topo: Int, linha: Int, visiveis: Int, total: Int): Int {
        if (total <= visiveis) return 0
        val t = when {
            linha < topo -> linha
            linha >= topo + visiveis -> linha - visiveis + 1
            else -> topo
        }
        return t.coerceIn(0, total - visiveis)
    }
}

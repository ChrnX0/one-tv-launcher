package br.paulo.tvlauncher

import java.util.TimeZone

/**
 * "Alarme à prova de falha" (10/11/2026): as regras, só a conta, sem nada do Android, para testar no computador
 * (AlarmePerdidoTest). Quem usa: AlarmeGarantia (a parte que mexe na TV).
 *
 * A ideia: cada vez que o Despertador agenda, ele anota o instante "devido" (o próximo toque). Se depois esse instante
 * passou e ninguém tocou (a TV estava fora da tomada, reiniciou, ou o relógio pulou para frente), o alarme foi perdido.
 * Até [TOLERANCIA_MS] de atraso ainda vale a pena tocar (ex.: a TV reiniciou às 06:29 e voltou às 06:31); depois disso
 * tocar não ajuda mais (acordar a casa às 9 por um alarme das 6:30) e o certo é avisar que não tocou.
 */
object AlarmePerdido {
    /** Até 15 min de atraso o alarme ainda toca; mais que isso vira aviso de "não tocou". */
    const val TOLERANCIA_MS = 15 * 60_000L
    /** Só avisa de alarmes das últimas 24 h: de uma semana atrás ninguém quer saber. */
    const val JANELA_MS = 24 * 60 * 60_000L

    /** O mínimo de um alarme que a conta precisa (o Despertador.Alarme tem mais coisa, mas é do Android). */
    data class Item(val id: Int, val hora: Int, val minuto: Int, val dias: Int, val ativo: Boolean)

    /** Um toque que deveria ter acontecido: qual alarme e quando. */
    data class Toque(val id: Int, val hora: Int, val minuto: Int, val em: Long)

    /**
     * O que fazer agora:
     * [tocarAgora] = o toque devido que ainda está dentro da tolerância (tocar já, o Android perdeu o agendamento);
     * [perdidos] = os que passaram da hora e não tocaram (para o aviso), do mais cedo para o mais tarde;
     * [desligar] = alarmes de "uma vez só" cujo horário passou sem tocar (não podem ir para amanhã sozinhos).
     */
    data class Avaliacao(val tocarAgora: Toque?, val perdidos: List<Toque>, val desligar: Set<Int>) {
        val vazia get() = tocarAgora == null && perdidos.isEmpty() && desligar.isEmpty()
    }

    private val NADA = Avaliacao(null, emptyList(), emptySet())

    /**
     * [devidoEm]/[devidoId]: o toque anotado no último agendamento (0 = nada anotado). [resolvidoAte]: o último toque
     * já tratado (tocou, foi aberto agora ou já foi avisado); o que for até ele não conta de novo.
     * [itens]: os alarmes como estão agora. [agora]: o relógio da TV.
     */
    fun avaliar(
        itens: List<Item>, devidoEm: Long, devidoId: Int, resolvidoAte: Long, agora: Long,
        fuso: TimeZone = TimeZone.getDefault(),
    ): Avaliacao {
        // Nada anotado, já tratado, ou ainda no futuro (o relógio pode ter voltado): nada a fazer.
        if (devidoEm <= 0 || devidoEm <= resolvidoAte || devidoEm > agora) return NADA
        val tocarAgora = if (agora - devidoEm <= TOLERANCIA_MS) itens.firstOrNull { it.id == devidoId && it.ativo }
            ?.let { Toque(it.id, it.hora, it.minuto, devidoEm) } else null
        val limitePerdido = agora - TOLERANCIA_MS   // daqui para trás, passou da hora de tocar
        val perdidos = ArrayList<Toque>()
        val desligar = HashSet<Int>()
        for (a in itens) {
            if (!a.ativo) continue
            // Começa no devido (o primeiro toque que estava "em aberto"), mas não antes da janela de aviso: com a TV
            // desligada um mês, não precisamos andar 30 dias, só ver as últimas 24 h. "- 1": o próprio instante conta.
            val inicio = if (a.dias == 0) devidoEm - 1 else maxOf(devidoEm, agora - JANELA_MS) - 1
            var t = ProximoAlarme.calcular(a.hora, a.minuto, a.dias, inicio, fuso)
            var voltas = 0
            while (t != null && t < limitePerdido && voltas++ < 16) {
                if (agora - t <= JANELA_MS) perdidos.add(Toque(a.id, a.hora, a.minuto, t))
                if (a.dias == 0) { desligar.add(a.id); break }   // uma vez só: perdeu a vez, desliga
                t = ProximoAlarme.calcular(a.hora, a.minuto, a.dias, t, fuso)
            }
        }
        // O toque devido que não é um horário do alarme (a SONECA: devido 6:40 de um alarme das 6:30) não aparece no
        // laço acima, que só anda pelos horários. Passou da tolerância sem tocar: avisa e, se o alarme é de uma vez só,
        // desliga (senão ele ficaria ligado e tocaria amanhã sozinho).
        if (devidoEm < limitePerdido && perdidos.none { it.id == devidoId && it.em == devidoEm }) {
            itens.firstOrNull { it.id == devidoId && it.ativo }?.let { a ->
                if (agora - devidoEm <= JANELA_MS) perdidos.add(Toque(a.id, a.hora, a.minuto, devidoEm))
                if (a.dias == 0) desligar.add(a.id)
            }
        }
        // Um aviso por alarme (o mais recente), em ordem de horário.
        val unicos = perdidos.groupBy { it.id }.map { (_, l) -> l.maxByOrNull { it.em }!! }.sortedBy { it.em }
        return Avaliacao(tocarAgora, unicos, desligar)
    }

    /** "6:30" (sem o zero da frente: é texto para ler numa frase, não o relógio). */
    fun horaFalada(hora: Int, minuto: Int) = "%d:%02d".format(hora, minuto)

    /** "6:30", "6:30 e 7:00", "6:00, 6:30 e 7:00". Repetidos (dois alarmes no mesmo horário) aparecem uma vez só. */
    fun juntarHoras(toques: List<Toque>, e: String = "e"): String {
        val horas = toques.sortedBy { it.em }.map { horaFalada(it.hora, it.minuto) }.distinct()
        return when (horas.size) {
            0 -> ""
            1 -> horas[0]
            else -> horas.dropLast(1).joinToString(", ") + " $e " + horas.last()
        }
    }
}

/**
 * Volume garantido do alarme (10/11/2026): a conta pura, testada em AlarmePerdidoTest.
 * O som do alarme sai pelo fluxo de MÍDIA (STREAM_MUSIC: ver SomAlarme, é o que sai pelo HDMI nesta TV), então é esse
 * volume que sobe. [minimoPct] = porcentagem do volume máximo (0 = não mexe).
 */
object VolumeAlarme {
    /** Opções do botão "Volume mínimo" (0 = não mexer no volume da TV). */
    val OPCOES = listOf(0, 30, 50, 70, 100)
    const val PADRAO = 50

    /** Para quanto subir o volume, ou null se já está alto o bastante (nunca abaixa: quem deixou alto quer alto). */
    fun alvo(atual: Int, max: Int, minimoPct: Int): Int? {
        if (max <= 0 || minimoPct <= 0) return null
        // Arredonda para cima: 50% de 15 é 7,5 -> 8 (o "mínimo" não pode ficar abaixo do pedido).
        val minimo = ((max * minimoPct.coerceIn(0, 100) + 99) / 100).coerceIn(1, max)
        return if (atual < minimo) minimo else null
    }

    /**
     * Ao parar o alarme: devolver o volume antigo? Só se ninguém mexeu durante o alarme ([agora] ainda é o que nós
     * pusemos, [definido]). Se a pessoa ajustou o volume com o controle, respeita a escolha dela (null = não mexe).
     */
    fun devolver(agora: Int, definido: Int?, original: Int): Int? =
        if (definido != null && agora == definido && agora != original) original else null
}

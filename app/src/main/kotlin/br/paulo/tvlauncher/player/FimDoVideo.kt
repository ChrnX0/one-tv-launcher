package br.paulo.tvlauncher.player

/**
 * Regras puras do player de filmes e séries (ideias 19, 20, 21 e 22 do estudo de 13/11/2026). Sem Android: testadas na JVM
 * (FimDoVideoTest). As telas (VodActivity, SeriesActivity, PlayerCarga) só perguntam aqui o que fazer.
 */
object FimDoVideo {

    // ---------------------------------------------------------------- 19: fim de episódio com modos

    /** Segundos da contagem antes do próximo episódio (dá tempo de apertar VOLTAR). */
    const val CONTAGEM_S = 10

    /** Episódios seguidos tocados sozinhos, sem nenhuma tecla, antes de perguntar "ainda está assistindo?". */
    const val SEGUIDOS_MAX = 3

    enum class Passo {
        /** Nada a fazer: volta para a ficha (filme, fim da série, ou a pessoa saiu antes do fim). */
        NADA,
        /** Mostra "próximo episódio em 10 s" e começa sozinho se ninguém cancelar. */
        PROXIMO_CONTANDO,
        /** Para e pergunta "ainda está assistindo?": nada começa sem resposta. */
        PERGUNTAR,
        /** "Desligar quando este acabar" estava marcado: apaga a tela e não segue para o próximo. */
        DESLIGAR,
    }

    /**
     * O que fazer quando um vídeo para.
     * [terminou]: foi até o fim (não foi o VOLTAR). [temProximo]: a série tem episódio seguinte.
     * [desligarAoFim]: a pessoa marcou "desligar quando este acabar". [seguidosSemTecla]: já contando este episódio.
     * [contagem]: segundos da contagem escolhidos nas Configurações ([Conforto.contagem]); 0 = não seguir sozinho.
     */
    fun depois(terminou: Boolean, temProximo: Boolean, desligarAoFim: Boolean, seguidosSemTecla: Int,
               contagem: Int = CONTAGEM_S): Passo = when {
        !terminou -> Passo.NADA
        desligarAoFim -> Passo.DESLIGAR
        !temProximo -> Passo.NADA
        // [contagem] = 0 (Configurações, 16/11/2026): "não começar sozinho"; volta à ficha no próximo episódio.
        contagem <= 0 -> Passo.NADA
        seguidosSemTecla >= SEGUIDOS_MAX -> Passo.PERGUNTAR
        else -> Passo.PROXIMO_CONTANDO
    }

    /**
     * Conta de episódios seguidos sem tecla. Qualquer tecla durante o episódio zera; episódio que terminou sem tecla soma 1.
     * Quem saiu antes do fim (VOLTAR) também zera: apertou tecla.
     */
    fun seguidos(anterior: Int, apertouTecla: Boolean): Int = if (apertouTecla) 0 else anterior + 1

    // ---------------------------------------------------------------- 20: nova tentativa quando o vídeo falha

    /** Quantas vezes tentar de novo antes de mostrar o erro (igual à TV ao vivo). */
    const val TENTATIVAS = 3

    /**
     * Espera antes da tentativa número [tentativa] (1, 2, 3): 2, 4 e 8 s. Null = desistir e mostrar a mensagem.
     * Só vale para erro de rede ([deRede]); arquivo estragado ou formato que a TV não toca não melhora tentando de novo.
     */
    fun esperaAntes(tentativa: Int, deRede: Boolean): Long? =
        if (!deRede || tentativa < 1 || tentativa > TENTATIVAS) null else 2_000L shl (tentativa - 1)

    /**
     * Erros do Media3 que vêm da rede: a família 2xxx (entrada e saída: sem conexão, tempo esgotado, servidor recusou)
     * e o 1003 (tempo esgotado genérico). Os números são os de PlaybackException, copiados para o teste não precisar do Media3.
     */
    fun erroDeRede(codigo: Int): Boolean = codigo in 2000..2999 || codigo == 1003

    /**
     * Modo túnel (proposta C, item 12, 20/11/2026): erros de decodificador (4xxx) e de saída de áudio (5xxx) podem ser
     * o túnel falhando neste aparelho. Com o túnel ligado, eles fazem o player voltar ao modo comum no mesmo ponto.
     */
    fun erroDoTunel(codigo: Int): Boolean = codigo in 4000..4999 || codigo in 5000..5999

    /** Hora de preparar o episódio seguinte: faltando 10 min, e nunca antes da metade (episódio curto). */
    fun horaDePreparar(posMs: Long, duracaoMs: Long): Boolean =
        duracaoMs > 0 && posMs >= maxOf(duracaoMs - 10 * 60_000L, duracaoMs / 2)

    /** "id|temporada|número" -> (id, temporada, número); qualquer coisa fora disso = null. */
    fun lerProximo(s: String?): Triple<String, Int, Int>? {
        val p = s?.split('|')?.takeIf { it.size == 3 } ?: return null
        val t = p[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val e = p[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return if (p[0].isBlank()) null else Triple(p[0], t, e)
    }

    /** Quanto tempo "carregando" com o túnel ligado antes de desconfiar dele. */
    const val TUNEL_PRESO_MS = 8_000L

    /** Carregando com mais de 10 s já baixados à frente não é rede lenta: é o decodificador preso (túnel). */
    fun tunelPreso(baixadoAFrenteMs: Long): Boolean = baixadoAFrenteMs >= 10_000

    // ---------------------------------------------------------------- 21: legenda com tamanho ajustável

    /** Tamanhos da legenda em sp (pequena, normal, grande, enorme). O normal é o de antes (30 sp). */
    val TAMANHOS_LEGENDA = intArrayOf(24, 30, 38, 46)
    const val LEGENDA_PADRAO = 30

    /** O tamanho salvo, se ainda for um dos válidos; senão o padrão (valor velho ou estragado). */
    fun tamanhoLegenda(salvo: Int): Int = if (salvo in TAMANHOS_LEGENDA) salvo else LEGENDA_PADRAO

    /**
     * Ideia 7 (15/11/2026): o mesmo "Tamanho da legenda" vale para a ASS (desenhada pelo libass com a letra do arquivo).
     * Normal = como o arquivo pediu; os outros tamanhos aumentam ou diminuem na mesma proporção da legenda comum.
     */
    fun escalaAss(sp: Int): Double = tamanhoLegenda(sp).toDouble() / LEGENDA_PADRAO

    // ---------------------------------------------------------------- 22: ficha guardada em disco

    /** Quantas fichas ficam no disco (as mais recentes). Cada uma tem de 2 a 60 KB. */
    const val FICHAS_MAX = 150

    /** Nome do arquivo da ficha: só letras, números, ponto, traço e sublinhado (o id vem do servidor). */
    fun nomeFicha(fonteId: String, serie: Boolean, id: String): String {
        fun limpo(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
        return "${limpo(fonteId)}.${if (serie) "s" else "f"}${limpo(id)}.json"
    }

    /** Arquivos a apagar para sobrarem [max]: os mais velhos por data de modificação. [arquivos] = nome para data. */
    fun podar(arquivos: Map<String, Long>, max: Int = FICHAS_MAX): List<String> =
        if (arquivos.size <= max) emptyList()
        else arquivos.entries.sortedByDescending { it.value }.drop(max).map { it.key }
}

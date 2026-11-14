package br.paulo.tvlauncher

import java.text.Normalizer

/**
 * Ideia 56 (14/11/2026): digitar com acento pelo celular e pelo Claude. O `input text` do Android só digita ASCII
 * ("ação" chegava "acao"). Texto com acento agora vai pelo serviço de acessibilidade, que ESCREVE no campo em foco
 * (ACTION_SET_TEXT, DigitarNaTv.kt). Texto só ASCII continua pelo `input text`, que já funcionava em todo app.
 *
 * Puro (sem Android): as regras de qual caminho usar, o que aceitar e como juntar com o que o campo já tem.
 */
object TextoNaTv {
    /**
     * Texto que o painel aceita para o caminho da acessibilidade: letras de qualquer língua (com acento), números,
     * espaço e . , @ _ - (os mesmos símbolos do caminho ASCII), até [max]. Sem shell no meio, mas a mesma lista curta:
     * o painel digita busca e login, não código.
     */
    fun validoPainel(t: String?, max: Int = 100): Boolean =
        t != null && t.length in 1..max && t.all { it.isLetterOrDigit() || it in " .,@_-" || Character.getType(it).let { ty ->
            ty == Character.NON_SPACING_MARK.toInt() || ty == Character.COMBINING_SPACING_MARK.toInt() } }

    /** Para o MCP: qualquer texto imprimível (sem caractere de controle) até [max]. */
    fun validoMcp(t: String?, max: Int): Boolean = t != null && t.length in 1..max && t.none { Character.isISOControl(it) }

    /** Tem algo fora do ASCII imprimível? Então precisa da acessibilidade. */
    fun precisaAcessibilidade(t: String): Boolean = t.any { it.code < 0x20 || it.code > 0x7E }

    /** Plano B sem acessibilidade (serviço desligado ou nenhum campo em foco): "ação" -> "acao". O que sobrar fora do ASCII sai. */
    fun semAcento(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").filter { it.code in 0x20..0x7E }

    /**
     * O texto final do campo: o que já está lá + o novo. [mostrandoDica] = o campo mostra só a dica cinza ("Buscar"),
     * que o Android devolve como se fosse o texto: aí o campo conta como vazio. Normalizado em NFC ("a" + acento = "á").
     */
    fun juntar(atual: CharSequence?, mostrandoDica: Boolean, novo: String): String =
        Normalizer.normalize((if (mostrandoDica || atual == null) "" else atual.toString()) + novo, Normalizer.Form.NFC)
}

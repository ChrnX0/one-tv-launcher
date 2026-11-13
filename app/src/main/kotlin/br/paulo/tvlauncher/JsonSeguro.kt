package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Checagem e leitura SEGURA de JSON que chega de fora (auditoria de 10/11/2026, achado painel-1).
 *
 * Por que existe: o org.json do Android é TOLERANTE (aceita string com aspa simples, comentários (barra-asterisco, barra-barra e #), palavra
 * sem aspas) e RECURSIVO: cada "[" ou "{" desce um nível na pilha. Um corpo com dezenas de milhares de "[[[[" estoura a
 * pilha com StackOverflowError, que é Error (não Exception): nenhum `catch (e: Exception)` pega, e o processo do launcher
 * cai. A checagem antiga (ChecagensPainel.jsonRaso) só conhecia a aspa dupla, então `'"'[[[[...` ou um comentário com aspa dentro seguido de `[[[[...` a
 * enganavam (ela achava que o resto era texto) e um nível negativo (`]]]]` antes) também passava.
 *
 * Aqui a regra é a do JSON ESTRITO: fora de string só vale o que o JSON de verdade permite. Assim o que passa por
 * [valido] é lido pelo org.json do mesmo jeito que foi contado aqui, e a profundidade nunca passa de [MAX_PROFUNDIDADE].
 * Puro (sem Android), com teste JVM (JsonSeguroTest). Pronto para o painel adotar também.
 */
object JsonSeguro {
    /** Níveis de [ e { um dentro do outro. Pedidos de verdade (MCP, OAuth, painel) usam poucos. */
    const val MAX_PROFUNDIDADE = 32

    // Fora de string: espaço, a pontuação do JSON e números (com sinal, ponto e expoente). Letras só em true/false/null
    // (o org.json aceitaria palavra solta sem aspas; o JSON estrito, não).
    private const val PERMITIDOS_FORA = " \t\r\n{}[]:,\"0123456789-+."
    private val PALAVRAS = listOf("true", "false", "null")

    /**
     * O texto é JSON estrito, bem fechado e com no máximo [max] níveis? Não monta nada: só conta, em um passo.
     * Recusa: aspa simples, comentários, # e qualquer caractere fora da lista acima (fora de string); fechar o que não
     * foi aberto (nível negativo) ou fechar com o par errado ("[}"); string sem fim; controle cru dentro de string;
     * texto vazio.
     */
    fun valido(texto: String, max: Int = MAX_PROFUNDIDADE): Boolean {
        if (texto.isBlank()) return false
        val abertos = CharArray(max)   // pilha do que está aberto, para conferir o par ao fechar
        var nivel = 0
        var emString = false
        var escapado = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i++]
            if (emString) {
                when {
                    escapado -> escapado = false
                    c == '\\' -> escapado = true
                    c == '"' -> emString = false
                    c < ' ' -> return false            // JSON estrito não deixa quebra de linha crua dentro de string
                }
                continue
            }
            if (c == 'e' || c == 'E') {
                // "e" só como expoente de número (1e5, 2.5E-3): logo depois de um algarismo ou do ponto.
                if (i < 2 || texto[i - 2] !in "0123456789.") return false
                continue
            }
            if (c in 'a'..'z') {
                // Outra letra fora de string: só a palavra inteira true/false/null.
                val palavra = PALAVRAS.firstOrNull { texto.startsWith(it, i - 1) } ?: return false
                i += palavra.length - 1
                continue
            }
            if (c !in PERMITIDOS_FORA) return false
            when (c) {
                '"' -> emString = true
                '[', '{' -> {
                    if (nivel >= max) return false
                    abertos[nivel++] = c
                }
                ']', '}' -> {
                    if (nivel == 0) return false       // fechou o que não abriu: nível negativo
                    val abriu = abertos[--nivel]
                    if ((c == ']' && abriu != '[') || (c == '}' && abriu != '{')) return false
                }
            }
        }
        return !emString && nivel == 0
    }

    /**
     * Para um corpo de pedido que PODE não ser JSON (ex.: o painel, que confere todo POST antes da rota, e clientes de
     * fora como o Home Assistant mandando texto ou form-urlencoded). Pula espaço e comentários (barra-asterisco, barra-barra e #) como o
     * org.json faz antes de ler; se o 1º caractere que sobra é "{" ou "[", o org.json desceria níveis: só passa se for
     * [valido] ("]" ou "}" no início também vai para o [valido], que recusa). Qualquer outra coisa passa, porque o org.json não desce nenhum nível nela (lê um valor solto ou recusa).
     * Troca consciente: JSON frouxo que começa com "{" ou "[" (aspa simples, chave sem aspas) passa a ser recusado.
     * Revisão do fuzz (10/11/2026): trocar o jsonRaso por [valido] direto no painel recusaria corpo que não é JSON.
     */
    fun corpoAceitavel(texto: String, max: Int = MAX_PROFUNDIDADE): Boolean {
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                c <= ' ' -> i++
                c == '#' || texto.startsWith("//", i) -> i = texto.indexOfAny(charArrayOf('\n', '\r'), i).let { if (it < 0) texto.length else it + 1 }
                texto.startsWith("/*", i) -> {
                    val fim = texto.indexOf("*/", i + 2)
                    if (fim < 0) return true          // comentário sem fim: o org.json recusa antes de abrir qualquer nível
                    i = fim + 2
                }
                else -> return if (c in "[{]}") valido(texto, max) else true   // ] ou } no início: nível negativo, recusa
            }
        }
        return true                                  // vazio ou só espaço/comentário: nada para descer
    }

    /**
     * Monta o JSON com [montar] (ex.: `{ JSONObject(it) }`) só se [valido]. Null se for inválido, se o org.json
     * reclamar, ou se mesmo assim a pilha estourar: o StackOverflowError é pego AQUI, para nunca derrubar o processo.
     */
    fun <T> ler(texto: String, max: Int = MAX_PROFUNDIDADE, montar: (String) -> T): T? {
        if (!valido(texto, max)) return null
        return try { montar(texto) } catch (e: Exception) { null } catch (e: StackOverflowError) { null }
    }

    /** Atalho para o caso comum: um objeto JSON ({...}). */
    fun objeto(texto: String, max: Int = MAX_PROFUNDIDADE): JSONObject? = ler(texto, max) { JSONObject(it) }
}

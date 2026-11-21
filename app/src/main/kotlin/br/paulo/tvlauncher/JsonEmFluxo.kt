package br.paulo.tvlauncher

import java.io.IOException
import java.io.Reader
import org.json.JSONObject

/**
 * Lê um array JSON de objetos (`[{...},{...}]`) um objeto por vez, sem montar o texto inteiro nem a árvore inteira na
 * memória. Para os catálogos do Xtream (filmes, séries, canais), que chegam a dezenas de MB: antes eram lidos inteiros
 * (bytes + String + JSONArray, 3 cópias) no processo do player, e acima de 30 MB davam erro toda vez.
 *
 * Pura (sem Android): o android.util.JsonReader não roda nos testes da JVM.
 *
 * Quem consome para quando quiser (ex.: chegou no máximo de filmes) e o resto do fluxo nem é lido. Se o fluxo acabar
 * antes do `]` final (conexão caiu no meio), ou não começar com `[`, a sequência lança [IOException]: quem chama trata
 * como falha e NÃO troca o cache bom por uma lista pela metade.
 */
object JsonEmFluxo {
    /** Um objeto maior que isto é pulado (nenhum item de catálogo chega perto; protege a memória de um servidor ruim). */
    const val MAX_OBJETO = 1024 * 1024

    fun objetos(entrada: Reader): Sequence<JSONObject> = sequence {
        val r = if (entrada is java.io.BufferedReader) entrada else entrada.buffered(64 * 1024)
        var c = pularEspacos(r)
        if (c == 0xFEFF) c = pularEspacos(r)   // BOM
        if (c != '['.code) throw IOException("não é uma lista JSON")
        val sb = StringBuilder()
        while (true) {
            c = pularEspacos(r)
            when (c) {
                -1 -> throw IOException("a lista acabou no meio")
                ']'.code -> return@sequence
                ','.code -> continue
            }
            // Um valor do array: copia até fechar (objeto), ou até a vírgula/colchete (número, null, texto solto).
            sb.setLength(0)
            val r2 = copiarValor(r, c, sb)
            if (c == '{'.code && (r2 and GRANDE) == 0) {
                val o = try { JSONObject(sb.toString()) } catch (_: Exception) { null }
                if (o != null) yield(o)
            }
            if ((r2 and FIM) != 0) return@sequence   // um valor solto que já terminou no "]"
        }
    }

    private const val GRANDE = 1
    private const val FIM = 2

    private fun pularEspacos(r: Reader): Int {
        while (true) {
            val c = r.read()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
        }
    }

    /**
     * Copia um valor começando em [primeiro]. Devolve [GRANDE] se passou de [MAX_OBJETO] (lido até o fim, descartado)
     * e/ou [FIM] se um valor solto terminou no "]" do array.
     */
    private fun copiarValor(r: Reader, primeiro: Int, sb: StringBuilder): Int {
        var grande = false
        fun por(c: Int) { if (!grande) { sb.append(c.toChar()); if (sb.length > MAX_OBJETO) { grande = true; sb.setLength(0) } } }
        fun res(fim: Boolean) = (if (grande) GRANDE else 0) or (if (fim) FIM else 0)
        if (primeiro != '{'.code && primeiro != '['.code && primeiro != '"'.code) {
            // Escalar (número, true, null): até a vírgula ou o fim do array.
            var c = primeiro
            while (true) {
                if (c == -1) throw IOException("a lista acabou no meio")
                if (c == ','.code) return res(false)
                if (c == ']'.code) return res(true)
                por(c); c = r.read()
            }
        }
        var profundidade = 0
        var emTexto = false
        var escape = false
        var c = primeiro
        while (true) {
            if (c == -1) throw IOException("a lista acabou no meio")
            por(c)
            if (emTexto) {
                if (escape) escape = false
                else if (c == '\\'.code) escape = true
                else if (c == '"'.code) { emTexto = false; if (profundidade == 0) return res(false) }
            } else when (c) {
                '"'.code -> emTexto = true
                '{'.code, '['.code -> profundidade++
                '}'.code, ']'.code -> { profundidade--; if (profundidade == 0) return res(false) }
            }
            c = r.read()
        }
    }
}

package br.paulo.tvlauncher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preenche a página do passo a passo do https (assets/painel/certificado.html) no servidor: a CSP do painel proíbe
 * script inline, e assim a página funciona até no navegador mais simples. Lógica pura, testada na JVM.
 *
 * No modelo: {{CHAVE}} vira o valor (sempre escapado para HTML) e <!--se:x-->…<!--fim:x--> some quando a condição x
 * é falsa. Condições: pronto, naopronto, comip (o IP atual está no certificado), vencendo (faltam menos de 60 dias).
 */
object PaginaCertificado {
    fun preencher(modelo: String, estado: JSONObject, nome: String): String {
        val pronto = estado.optBoolean("pronto") && estado.optBoolean("ligado")
        val dias = estado.optLong("diasRestantes", Long.MAX_VALUE)
        val condicoes = mapOf(
            "pronto" to pronto,
            "naopronto" to !pronto,
            "comip" to (pronto && estado.optBoolean("ipNoCertificado")),
            "vencendo" to (pronto && dias < 60),
        )
        var html = modelo
        for ((nomeCond, vale) in condicoes) {
            val bloco = Regex("<!--se:$nomeCond-->(.*?)<!--fim:$nomeCond-->", RegexOption.DOT_MATCHES_ALL)
            html = bloco.replace(html) { if (vale) it.groupValues[1] else "" }
        }
        val validoAte = estado.optLong("validoAte", 0L).takeIf { it > 0 }
            ?.let { SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR")).format(Date(it)) } ?: ""
        val valores = mapOf(
            "ENDERECO" to estado.optString("endereco", "https://$nome:${estado.optInt("porta", 8444)}/"),
            "ENDERECO_IP" to (if (estado.isNull("enderecoIp")) "" else estado.optString("enderecoIp", "")),
            "IMPRESSAO" to estado.optString("impressao", ""),
            "VALIDO_ATE" to validoAte,
            "DIAS" to (if (dias == Long.MAX_VALUE) "" else dias.toString()),
            "NOME" to nome,
        )
        for ((k, v) in valores) html = html.replace("{{$k}}", escapar(v))
        return html
    }

    fun escapar(t: String): String = buildString(t.length) {
        for (ch in t) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch)
        }
    }
}

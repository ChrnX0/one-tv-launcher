package br.paulo.tvlauncher.panel

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
 *
 * 16/11/2026: o passo a passo do sistema de quem abriu a página ([sistema], pelo User-Agent) vem primeiro e aberto
 * ({{PRIMEIRO_X}} vira a classe "primeiro" e {{ABERTO_X}} vira "open"); os outros ficam fechados embaixo.
 */
object PaginaCertificado {
    enum class Sistema(val rotulo: String) { ANDROID("Android"), IOS("iPhone ou iPad"), WINDOWS("Windows"), MAC("Mac"), OUTRO("outro") }

    /**
     * O sistema pelo User-Agent. A ordem importa: o Android e o iPhone também dizem "Linux"/"Mac OS X" no texto. O iPad
     * novo se apresenta como Mac: cai em MAC, e o passo do iPhone continua logo abaixo.
     */
    fun sistema(agente: String?): Sistema {
        val a = agente ?: return Sistema.OUTRO
        return when {
            a.contains("Android") -> Sistema.ANDROID
            a.contains("iPhone") || a.contains("iPad") || a.contains("iPod") -> Sistema.IOS
            a.contains("Windows") -> Sistema.WINDOWS
            a.contains("Macintosh") || a.contains("Mac OS X") -> Sistema.MAC
            else -> Sistema.OUTRO
        }
    }

    fun preencher(modelo: String, estado: JSONObject, nome: String, agente: String? = null): String {
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
        val meu = sistema(agente)
        val porSistema = Sistema.entries.filter { it != Sistema.OUTRO }.flatMap { so ->
            listOf("PRIMEIRO_${so.name}" to (if (so == meu) "primeiro" else ""), "ABERTO_${so.name}" to (if (so == meu) "open" else ""))
        } + ("SISTEMA" to (if (meu == Sistema.OUTRO) "não reconhecido (abra o seu abaixo)" else meu.rotulo))
        for ((k, v) in valores + porSistema) html = html.replace("{{$k}}", escapar(v))
        return html
    }

    fun escapar(t: String): String = buildString(t.length) {
        for (ch in t) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch)
        }
    }
}

package br.paulo.tvlauncher.home

import br.paulo.tvlauncher.alarm.Lembretes
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.Planos
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cartões próprios da tela inicial (Paulo, 18/11/2026: "no nosso site pq a gente mesmo não cria isso?"). Os exemplos
 * que a página de desenvolvedores dava para plugins de terceiros viraram funções nossas, cada uma no lugar da cruz que
 * faz sentido (regra do Paulo, 18/11 14:59: a pílula de cima é do SISTEMA; o canto de baixo, dos nossos apps):
 *  - Contagem regressiva -> pílula de cima nos últimos [JANELA_CONTAGEM] dias ("Faltam 3 dias para as férias");
 *  - Lembrete da casa    -> pílula de cima no dia ("Hoje: lixo reciclável"), repete por dia da semana;
 *  - Status do serviço   -> pílula de cima SÓ quando cai ("Servidor de casa fora do ar");
 *  - Cotação do dia      -> barra de cima aberta, ao lado do clima (Frankfurter: grátis, sem chave, uso comercial ok);
 *  - Placar do seu time  -> linha do item TV da cruz (o próximo jogo do "Meu time", pelo guia);
 *  - Fase da lua         -> já existia (Previsão, protetor, ícone do clima à noite); aqui só ganhou a chave liga/desliga.
 * Cada um tem uma chave em [Extras.Chave] (CARTAO_*). Plano: [Planos.Recurso.CARTOES_HOME] (Pro), menos a lua e o
 * próximo jogo, que já eram do Básico.
 *
 * Só a lógica pura mora aqui (HomeCardsTest); a parte Android é o [HomeCardsTv].
 */
object HomeCards {
    const val MAX_ITENS = 20
    const val MAX_NOME = 40
    /** A contagem aparece na pílula quando faltam até isto de dias (e no próprio dia). */
    const val JANELA_CONTAGEM = 7L

    private fun limpar(t: String?, max: Int = MAX_NOME): String =
        (t ?: "").filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(max)

    private fun mesmo(a: String, b: String) = Lembretes.normalizar(a) == Lembretes.normalizar(b)

    // ---------------------------------------------------------------- contagem regressiva

    data class Contagem(val nome: String, val data: LocalDate)

    /** Contagem válida ou null (nome vazio, data que não existe). */
    fun criarContagem(nome: String?, ano: Int, mes: Int, dia: Int): Contagem? {
        val n = limpar(nome)
        if (n.isEmpty()) return null
        val d = runCatching { LocalDate.of(ano, mes, dia) }.getOrNull() ?: return null
        if (ano !in 2000..2100) return null
        return Contagem(n, d)
    }

    /** Põe (ou troca a de mesmo nome); as que já passaram saem; em ordem de data. */
    fun adicionarContagem(lista: List<Contagem>, c: Contagem, hoje: LocalDate): List<Contagem> =
        (lista.filterNot { mesmo(it.nome, c.nome) } + c).filter { !it.data.isBefore(hoje) }
            .sortedWith(compareBy({ it.data }, { it.nome })).take(MAX_ITENS)

    fun removerContagem(lista: List<Contagem>, nome: String?): List<Contagem> = lista.filterNot { mesmo(it.nome, limpar(nome)) }

    /** O que a pílula diz: nome (null = feriado) e quantos dias faltam (0 = hoje). */
    data class Falta(val nome: String?, val dias: Long)

    /**
     * A contagem da pílula: a da pessoa mais perto dentro da janela; sem nenhuma da pessoa na janela, o próximo
     * [feriado] (se vier e estiver na janela). Feriado no fim de semana também conta: a pessoa vê "faltam 2 dias".
     */
    fun contagemDaPilula(lista: List<Contagem>, hoje: LocalDate, feriado: LocalDate? = null): Falta? {
        fun dias(d: LocalDate) = ChronoUnit.DAYS.between(hoje, d)
        lista.map { it to dias(it.data) }.filter { it.second in 0..JANELA_CONTAGEM }.minByOrNull { it.second }
            ?.let { return Falta(it.first.nome, it.second) }
        return feriado?.let { dias(it) }?.takeIf { it in 0..JANELA_CONTAGEM }?.let { Falta(null, it) }
    }

    fun contagensJson(lista: List<Contagem>): JSONArray = JSONArray().apply {
        lista.forEach { put(JSONObject().put("nome", it.nome).put("data", it.data.toString())) }
    }

    fun contagensDeJson(t: String?): List<Contagem> {
        val a = runCatching { JSONArray(t ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val d = runCatching { LocalDate.parse(o.optString("data")) }.getOrNull() ?: return@mapNotNull null
            criarContagem(o.optString("nome"), d.year, d.monthValue, d.dayOfMonth)
        }.take(MAX_ITENS)
    }

    // ---------------------------------------------------------------- lembrete da casa

    /**
     * "Lixo reciclável" às terças e sextas. [dias] = bits de segunda (1) a domingo (64). [minuto] = hora do aviso na
     * tela em minutos do dia, ou null (só a pílula, o dia todo).
     */
    data class LembreteCasa(val texto: String, val dias: Int, val minuto: Int? = null) {
        fun cai(d: LocalDate) = dias and bit(d.dayOfWeek) != 0
    }

    fun bit(d: DayOfWeek) = 1 shl (d.value - 1)
    const val TODOS_OS_DIAS = 127

    fun criarLembrete(texto: String?, dias: Int, minuto: Int?): LembreteCasa? {
        val t = limpar(texto, 60)
        if (t.isEmpty() || dias and TODOS_OS_DIAS == 0) return null
        return LembreteCasa(t, dias and TODOS_OS_DIAS, minuto?.takeIf { it in 0 until 24 * 60 })
    }

    fun adicionarLembrete(lista: List<LembreteCasa>, l: LembreteCasa): List<LembreteCasa> =
        (lista.filterNot { mesmo(it.texto, l.texto) } + l).sortedWith(compareBy({ it.minuto ?: -1 }, { it.texto })).take(MAX_ITENS)

    fun removerLembrete(lista: List<LembreteCasa>, texto: String?): List<LembreteCasa> = lista.filterNot { mesmo(it.texto, limpar(texto, 60)) }

    /** Os lembretes de [hoje], na ordem da lista (sem hora primeiro, depois pela hora). */
    fun lembretesDeHoje(lista: List<LembreteCasa>, hoje: LocalDate): List<LembreteCasa> = lista.filter { it.cai(hoje) }

    /**
     * Os lembretes com hora que já deviam ter avisado hoje até [minutoAgora] e ainda não avisaram. [avisados] = chaves
     * "AAAA-MM-DD|texto" dos que já avisaram (quem chama guarda). Avisa no máximo [ATRASO_MAX_MIN] depois da hora: a TV
     * ligada às 22h não mostra o lembrete das 7h.
     */
    fun lembretesParaAvisar(lista: List<LembreteCasa>, hoje: LocalDate, minutoAgora: Int, avisados: Set<String>): List<LembreteCasa> =
        lembretesDeHoje(lista, hoje).filter { l ->
            val m = l.minuto ?: return@filter false
            minutoAgora in m..(m + ATRASO_MAX_MIN) && chaveAviso(hoje, l) !in avisados
        }

    const val ATRASO_MAX_MIN = 60
    fun chaveAviso(hoje: LocalDate, l: LembreteCasa) = "$hoje|${Lembretes.normalizar(l.texto)}"

    fun lembretesJson(lista: List<LembreteCasa>): JSONArray = JSONArray().apply {
        lista.forEach { l -> put(JSONObject().put("texto", l.texto).put("dias", l.dias).apply { l.minuto?.let { put("minuto", it) } }) }
    }

    fun lembretesDeJson(t: String?): List<LembreteCasa> {
        val a = runCatching { JSONArray(t ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            criarLembrete(o.optString("texto"), o.optInt("dias"), if (o.has("minuto")) o.optInt("minuto") else null)
        }.take(MAX_ITENS)
    }

    // ---------------------------------------------------------------- status do serviço

    data class Servico(val nome: String, val url: String)

    /** Só http/https, com host, sem usuário:senha no endereço (a senha iria parar nas prefs e no backup). */
    fun criarServico(nome: String?, url: String?): Servico? {
        val n = limpar(nome)
        val u = (url ?: "").trim()
        if (n.isEmpty() || u.length > 300) return null
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) return null
        return Servico(n, u)
    }

    fun adicionarServico(lista: List<Servico>, s: Servico): List<Servico> = (lista.filterNot { mesmo(it.nome, s.nome) } + s).take(MAX_ITENS)
    fun removerServico(lista: List<Servico>, nome: String?): List<Servico> = lista.filterNot { mesmo(it.nome, limpar(nome)) }

    /** Respondeu = qualquer resposta HTTP abaixo de 500 (um 401 ou 404 ainda é o servidor de pé). */
    fun respondeu(codigo: Int?): Boolean = codigo != null && codigo in 100..499

    /** Quantas falhas seguidas antes de avisar (uma queda de 1 teste é Wi-Fi piscando, não o servidor). */
    const val FALHAS_PARA_AVISAR = 2

    /** Nova contagem de falhas seguidas de um serviço depois de um teste. */
    fun falhas(antes: Int, ok: Boolean): Int = if (ok) 0 else (antes + 1).coerceAtMost(99)

    /** Os nomes fora do ar (na ordem da lista), pelas falhas seguidas de cada um. */
    fun foraDoAr(lista: List<Servico>, falhas: Map<String, Int>): List<String> =
        lista.filter { (falhas[it.nome] ?: 0) >= FALHAS_PARA_AVISAR }.map { it.nome }

    fun servicosJson(lista: List<Servico>, falhas: Map<String, Int> = emptyMap()): JSONArray = JSONArray().apply {
        lista.forEach { put(JSONObject().put("nome", it.nome).put("url", it.url).put("fora", (falhas[it.nome] ?: 0) >= FALHAS_PARA_AVISAR)) }
    }

    fun servicosDeJson(t: String?): List<Servico> {
        val a = runCatching { JSONArray(t ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { criarServico(it.optString("nome"), it.optString("url")) } }.take(MAX_ITENS)
    }

    // ---------------------------------------------------------------- cotação do dia

    /** Moedas e ativos que a Frankfurter cobre e que oferecemos (as outras ficam fora para a lista caber na tela). */
    val ATIVOS = listOf("USD", "EUR", "GBP", "JPY", "CNY", "ARS", "CAD", "CHF", "AUD", "MXN", "INR", "BRL", "BTC", "ETH")
    const val MAX_COTACOES = 4

    /** As moedas de fábrica para quem usa [casa] (a moeda do país da TV): dólar, euro e bitcoin, tirando a própria. */
    fun padrao(casa: String): List<String> = listOf("USD", "EUR", "BTC").filter { it != casa }.let { if (it.size < 3) it + "GBP" else it }

    /** Lista escolhida limpa: só [ATIVOS], sem repetir, sem a própria [casa], até [MAX_COTACOES]. Vazia = [padrao]. */
    fun escolhidas(texto: String?, casa: String): List<String> {
        val l = (texto ?: "").split(',', ' ', ';').map { it.trim().uppercase() }.filter { it in ATIVOS && it != casa }.distinct().take(MAX_COTACOES)
        return l.ifEmpty { padrao(casa) }
    }

    /** A moeda da casa: a do país, se a Frankfurter cobre; senão o dólar. */
    fun moedaDaCasa(codigoDoPais: String?): String = codigoDoPais?.uppercase()?.takeIf { it in ATIVOS && it.length == 3 } ?: "USD"

    data class Cotacao(val codigo: String, val valor: Double, val data: String)

    /**
     * Resposta de `GET /v2/rates?base=<casa>&quotes=A,B` ([{"date","base","quote","rate"}]) -> quanto 1 unidade de cada
     * moeda vale na moeda da casa (o inverso do rate). Linha estranha (rate 0, negativo, base trocada) é ignorada.
     */
    fun lerCotacoes(json: String?, casa: String): List<Cotacao> {
        val a = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val r = o.optDouble("rate", Double.NaN)
            if (!(r > 0.0) || !r.isFinite() || !o.optString("base").equals(casa, ignoreCase = true)) return@mapNotNull null
            Cotacao(o.optString("quote").uppercase(), 1.0 / r, o.optString("date"))
        }
    }

    /** Guarda as cotações (cache da TV): "USD=5.78@2026-11-18;BTC=373264.0@2026-11-17". */
    fun cotacoesParaTexto(l: List<Cotacao>) = l.joinToString(";") { "${it.codigo}=${it.valor}@${it.data}" }
    fun cotacoesDeTexto(t: String?): List<Cotacao> = (t ?: "").split(';').mapNotNull { p ->
        val cod = p.substringBefore('=', "").takeIf { it.length in 3..4 } ?: return@mapNotNull null
        val v = p.substringAfter('=').substringBefore('@').toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        Cotacao(cod, v, p.substringAfter('@', ""))
    }

    /** Busca de novo a cada 3 h (as fontes publicam uma ou poucas vezes por dia). */
    const val VALIDADE_COTACAO_MS = 3 * 3_600_000L

    /**
     * "1 234,56" / "373 mil" / "1,2 mi" no jeito da [locale]: valor grande vira mil/milhão (o bitcoin em reais não cabe
     * inteiro na pílula), pequeno fica com 2 casas. [mil]/[milhao] = os sufixos traduzidos.
     */
    fun valorCurto(v: Double, locale: java.util.Locale, mil: String, milhao: String): String {
        val f = java.text.NumberFormat.getNumberInstance(locale)
        return when {
            v >= 1_000_000 -> { f.maximumFractionDigits = 1; f.minimumFractionDigits = 0; f.format(v / 1_000_000) + " " + milhao }
            v >= 10_000 -> { f.maximumFractionDigits = 0; f.format(v / 1_000) + " " + mil }
            v >= 100 -> { f.maximumFractionDigits = 0; f.format(v) }
            else -> { f.maximumFractionDigits = 2; f.minimumFractionDigits = 2; f.format(v) }
        }
    }

    /** Símbolo curto para a pílula: US$, €, £, ¥, ₿, Ξ, ouro; o resto, o código. */
    fun simbolo(codigo: String): String = when (codigo) {
        "USD" -> "US$"; "EUR" -> "€"; "GBP" -> "£"; "JPY", "CNY" -> "¥"; "BTC" -> "₿"; "ETH" -> "Ξ"; "BRL" -> "R$"
        else -> codigo
    }
}

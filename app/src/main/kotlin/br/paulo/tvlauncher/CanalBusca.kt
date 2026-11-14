package br.paulo.tvlauncher

import org.json.JSONObject
import java.io.Reader
import java.util.Calendar
import java.util.TimeZone

/**
 * "Coloca na Globo" e "o que está passando na Globo?" (10/11/2026, pedido do dono), pela voz e pelo Claude (MCP).
 * Só lógica pura (sem Android), testada em CanalBuscaTest. Quem lê os arquivos do player e abre o canal é o CanalTv.
 *
 * O problema: a pessoa fala "globo", mas na lista de IPTV o canal se chama "BR: TV Globo HD". Por isso a busca compara
 * uma CHAVE do nome, sem acento, sem caixa e sem as palavras que não identificam o canal (TV, HD, FHD, canal, o país
 * no começo...). E tolera um erro pequeno de digitação/reconhecimento de voz ("glbo", "sportv" x "sport tv").
 */
object CanalBusca {
    const val MAX_TERMO = 60

    /** Palavras que aparecem nos nomes de canal mas não dizem QUAL canal é. */
    private val RUIDO = setOf("tv", "canal", "hd", "fhd", "uhd", "sd", "4k", "8k", "h264", "h265", "hevc", "full", "fullhd",
        "ao", "vivo", "live", "alt", "backup", "o", "a", "no", "na", "numero")

    /** Número falado por extenso: o reconhecimento de voz às vezes escreve "dois" em vez de "2". */
    private val NUMEROS = mapOf("um" to "1", "uma" to "1", "dois" to "2", "duas" to "2", "tres" to "3", "quatro" to "4",
        "cinco" to "5", "seis" to "6", "sete" to "7", "oito" to "8", "nove" to "9", "dez" to "10")

    /** Um canal encontrado e o quanto o nome bateu (maior = melhor). */
    class Achado(val canal: Canal, val nota: Int)

    /** Chave de comparação de um texto: "BR: TV Globo HD" -> "globo"; "SporTV 2" -> "sportv 2". */
    fun chave(texto: String, pais: String? = null): String {
        var palavras = PlayerFiltro.normalizar(texto).split(' ').filter { it.isNotEmpty() }.map { NUMEROS[it] ?: it }
        // "BR: Globo" e "[PT] RTP 1": o país do começo não ajuda a achar o canal.
        if (pais != null && palavras.firstOrNull() == pais.lowercase()) palavras = palavras.drop(1)
        val limpas = palavras.filter { it !in RUIDO }
        // Um canal que só tem ruído no nome ("TV HD") fica com o nome inteiro, para não virar texto vazio.
        return (limpas.ifEmpty { palavras }).joinToString(" ")
    }

    /** Distância de edição (letras trocadas, faltando ou sobrando). Os textos aqui são curtos: o cálculo simples basta. */
    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        var anterior = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val linha = IntArray(b.length + 1)
            linha[0] = i
            for (j in 1..b.length) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                linha[j] = minOf(linha[j - 1] + 1, anterior[j] + 1, anterior[j - 1] + custo)
            }
            anterior = linha
        }
        return anterior[b.length]
    }

    /** Quantos erros aceitar: palavra curta tem de vir certa ("sbt" não pode virar "sic"); longa aceita 1 ou 2. */
    private fun tolerancia(tamanho: Int): Int = when { tamanho <= 3 -> 0; tamanho <= 6 -> 1; else -> 2 }

    /** O quanto o termo (já em chave) bate com o nome (já em chave). 0 = não bate. */
    fun nota(termo: String, nome: String): Int {
        if (termo.isEmpty() || nome.isEmpty()) return 0
        if (termo == nome) return 1000
        // Mesmo nome com ou sem espaço ("globo news" -> "GloboNews"): vale quase como igual. Sem isto, "globo news" caía
        // no "TV Globo" (o termo começa com "globo ", nota 700) antes do GloboNews (visto na Sala em 11/11/2026).
        if (termo.replace(" ", "") == nome.replace(" ", "")) return 950
        val pt = termo.split(' '); val pn = nome.split(' ')
        if (nome.startsWith("$termo ")) return 800            // "globo" -> "globo news"
        if (termo.startsWith("$nome ")) return 700            // "globo sp" -> "globo"
        if (pt.all { it in pn }) return 600                   // todas as palavras, em outra ordem
        // Pedaço de palavra: só no começo ("band" -> "bandeirantes"), ou no meio com 4+ letras. Senão "sic" casaria
        // com "music box" e "classic movies" (palavra curta tem de vir certa).
        if (pt.all { p -> pn.any { it.startsWith(p) || (p.length >= 4 && it.contains(p)) } }) return 500
        // Erro pequeno: o nome inteiro ("glbo" -> "globo"; "sport tv" -> "sportv") ou palavra por palavra.
        val inteiro = distancia(termo.replace(" ", ""), nome.replace(" ", ""))
        if (inteiro <= tolerancia(termo.length)) return 400 - 50 * inteiro
        var soma = 0
        for (p in pt) {
            val melhor = pn.minOf { distancia(p, it) }
            if (melhor > tolerancia(p.length)) return 0
            soma += melhor
        }
        return 300 - 10 * soma
    }

    /**
     * Os canais que batem com o que a pessoa disse, do melhor para o pior. Número ("5", "canal 5", "cinco") busca pelo
     * número do canal; se nenhum canal tiver aquele número, tenta como nome. Empate: o nome com menos palavras sobrando,
     * depois o número menor (o canal "principal" costuma vir antes na lista).
     */
    fun buscar(canais: List<Canal>, termo: String, max: Int = 5): List<Achado> {
        val t = chave(termo.take(MAX_TERMO))
        if (t.isEmpty()) return emptyList()
        if (t.all { it.isDigit() } && t.length <= 6) {
            val n = t.toInt()
            val porNumero = canais.filter { it.numero == n }
            if (porNumero.isNotEmpty()) return porNumero.take(max).map { Achado(it, 1000) }
        }
        val palavrasTermo = t.split(' ').size
        return canais.asSequence()
            .map { c -> val k = chave(c.nome, PlayerFiltro.pais(c)); Triple(c, nota(t, k), k.split(' ').size) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Canal, Int, Int>> { it.second }
                .thenBy { Math.abs(it.third - palavrasTermo) }.thenBy { it.first.numero })
            .take(max).map { Achado(it.first, it.second) }.toList()
    }

    /** O resultado de procurar um canal para tocar (ou ver o guia). */
    sealed class Escolha {
        class Achou(val canal: Canal) : Escolha()
        object NaoAchou : Escolha()
        object Bloqueado : Escolha()
    }

    /**
     * Escolhe o canal. [permitido] = não está bloqueado pelo controle parental nem em categoria oculta. Se o melhor
     * resultado estiver bloqueado e não houver outro permitido tão bom quanto ele, a resposta é [Escolha.Bloqueado]:
     * não toca e também não cai num canal parecido (pedir "Playboy" e ganhar outro canal seria pior).
     */
    fun escolher(canais: List<Canal>, termo: String, permitido: (Canal) -> Boolean): Escolha {
        val achados = buscar(canais, termo, max = 50)
        val melhor = achados.firstOrNull() ?: return Escolha.NaoAchou
        val c = achados.firstOrNull { it.nota == melhor.nota && permitido(it.canal) } ?: return Escolha.Bloqueado
        return Escolha.Achou(c.canal)
    }

    // ---------------------------------------------------------------- o que passa agora

    private fun hora(ms: Long, fuso: TimeZone): String = Calendar.getInstance(fuso).run {
        timeInMillis = ms
        "%02d:%02d".format(get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))
    }

    /** "TV Globo HD agora: Jornal Nacional (até 21:30). Depois: Novela (21:30)." Sem guia: diz isso. */
    fun textoAgora(c: Canal, guia: Guia, agora: Long, fuso: TimeZone): String {
        val atual = if (c.guiaId.isEmpty()) null else guia.agora(c.guiaId, agora)
        val depois = if (c.guiaId.isEmpty()) null
            else guia.proximos(c.guiaId, agora, 3).firstOrNull { it.inicio >= (atual?.fim ?: agora) }
        val nome = "${c.nome} (${c.numero})"
        // Ideia 72: no idioma da TV; sem Context (testes do computador), o português de sempre.
        fun t(id: Int, pt: String, vararg a: Any) = ComandosIa.tp(id, pt, *a)
        if (atual == null && depois == null) return t(R.string.l72_guia_vazio, "%1${'$'}s: sem programação no guia.", nome)
        val partes = ArrayList<String>()
        partes += if (atual != null) t(R.string.l72_guia_agora, "%1${'$'}s agora: %2${'$'}s (até %3${'$'}s)", nome, atual.titulo, hora(atual.fim, fuso))
            else t(R.string.l72_guia_nada_agora, "%1${'$'}s: nada no guia agora", nome)
        if (depois != null) partes += t(R.string.l72_guia_depois, "Depois: %1${'$'}s (%2${'$'}s)", depois.titulo, hora(depois.inicio, fuso))
        return partes.joinToString(". ") + "."
    }

    /**
     * Lê do cache do guia (o JSON do PlayerDados.programasParaJson: uma lista de objetos pequenos) só os programas dos
     * canais pedidos. Lido em fluxo, um objeto por vez: o arquivo pode ter dezenas de MB, e carregar tudo no processo
     * da HOME para responder sobre UM canal pesaria na TV de 2 GB. Arquivo com defeito: fica com o que deu para ler.
     */
    fun programasDoCache(leitor: Reader, guiaIds: Set<String>): List<Programa> {
        val saida = ArrayList<Programa>()
        if (guiaIds.isEmpty()) return saida
        val obj = StringBuilder()
        // Filtro barato antes do JSONObject: o objeto só interessa se tiver "c":"<id> pedido" (mesmo escape do gravador).
        val marcas = guiaIds.map { "\"c\":" + JSONObject.quote(it) }
        val buf = CharArray(8192)
        var n = 0; var pos = 0
        var nivel = 0; var emTexto = false; var escape = false
        while (true) {
            if (pos >= n) { n = leitor.read(buf); pos = 0; if (n < 0) break; if (n == 0) continue }   // lido em blocos
            val ch = buf[pos++]
            if (nivel > 0) obj.append(ch)
            if (emTexto) {
                when { escape -> escape = false; ch == '\\' -> escape = true; ch == '"' -> emTexto = false }
                continue
            }
            when (ch) {
                '"' -> if (nivel > 0) emTexto = true
                '{' -> { if (nivel == 0) { obj.setLength(0); obj.append(ch) }; nivel++ }
                '}' -> if (nivel > 0) {
                    nivel--
                    if (nivel == 0) {
                        val txt = obj.toString()
                        if (marcas.any { txt.contains(it) }) runCatching { JSONObject(txt) }.getOrNull()?.let { o ->
                            val c = o.optString("c"); val ini = o.optLong("i"); val fim = o.optLong("f")
                            if (c in guiaIds && fim > ini) saida += Programa(c, ini, fim, o.optString("t"), o.optString("d"), o.optString("k"))
                        }
                        obj.setLength(0)
                    }
                }
            }
            if (obj.length > 64 * 1024) { obj.setLength(0); nivel = 0; emTexto = false }   // objeto absurdo: descarta
        }
        return saida
    }

    // ---------------------------------------------------------------- resolver as ações do catálogo

    /** O que a TV sabe dos canais na hora de resolver um pedido. [guia] é lido só se alguma ação precisar. */
    class Dados(val canais: List<Canal>, val permitido: (Canal) -> Boolean, val guia: (Set<String>) -> Guia)

    /**
     * Troca as ações "tocar_canal"/"o_que_passa" ainda sem canal (como saem do ComandosIa.conferir) pelas resolvidas:
     * canal escolhido (ou recusa) e, no "o que passa", o texto do guia. [dados] null = TV ao vivo sem lista de canais.
     */
    fun resolver(plano: ComandosIa.Plano, dados: Dados?, agora: Long, fuso: TimeZone): ComandosIa.Plano {
        if (plano.acoes.none { it is ComandosIa.Acao.TocarCanal || it is ComandosIa.Acao.OQuePassa }) return plano
        val acoes = ArrayList<ComandosIa.Acao>()
        val recusadas = ArrayList(plano.recusadas)
        var guia: Guia? = null
        // Cada pedido é procurado UMA vez (a busca compara com todos os canais): o guia usa as mesmas escolhas.
        val escolhas = HashMap<String, Escolha>()
        fun escolha(pedido: String) = escolhas.getOrPut(pedido) { escolher(dados!!.canais, pedido, dados.permitido) }
        for (a in plano.acoes) {
            val (tipo, pedido) = when (a) {
                is ComandosIa.Acao.TocarCanal -> "tocar_canal" to a.pedido
                is ComandosIa.Acao.OQuePassa -> "o_que_passa" to a.pedido
                else -> { acoes += a; continue }
            }
            if (dados == null || dados.canais.isEmpty()) {
                recusadas += "$tipo: a TV ao vivo ainda não tem a lista de canais guardada (abra a TV ao vivo uma vez)"; continue
            }
            when (val e = escolha(pedido)) {
                Escolha.NaoAchou -> recusadas += "$tipo: não achei o canal \"${pedido.take(40)}\""
                // Não diz o nome do canal bloqueado nem o que passa nele: é o que o controle parental esconde.
                Escolha.Bloqueado -> recusadas += "$tipo: canal bloqueado (controle parental ou categoria oculta); só com o PIN no player"
                is Escolha.Achou -> acoes += if (a is ComandosIa.Acao.TocarCanal) {
                    ComandosIa.Acao.TocarCanal(pedido, e.canal.id, e.canal.nome, e.canal.numero)
                } else {
                    // O guia é lido uma vez só, já com os canais de todos os "o que passa" deste pedido.
                    val g = guia ?: dados.guia(pedidosGuia(plano) { escolha(it) }).also { guia = it }
                    ComandosIa.Acao.OQuePassa(pedido, textoAgora(e.canal, g, agora, fuso))
                }
            }
        }
        return ComandosIa.Plano(acoes, plano.resposta, recusadas)
    }

    /** Os guiaIds de todos os "o que passa" do pedido, para ler o cache do guia UMA vez só. */
    private fun pedidosGuia(plano: ComandosIa.Plano, escolha: (String) -> Escolha): Set<String> =
        plano.acoes.filterIsInstance<ComandosIa.Acao.OQuePassa>().mapNotNull { a ->
            (escolha(a.pedido) as? Escolha.Achou)?.canal?.guiaId?.ifEmpty { null }
        }.toSet()

    // ---------------------------------------------------------------- frases de voz

    /** O que a frase falada pede. */
    sealed class PedidoVoz(val canal: String) {
        class Tocar(canal: String) : PedidoVoz(canal)
        class OQuePassa(canal: String) : PedidoVoz(canal)
    }

    private val reOQuePassa = Regex("""^(?:o ?que|que|qual(?: e)?(?: o)? programa)\s+(?:(?:e que|que|esta|ta|vai)\s+)?(?:passando|passa|tem|vai passar|ta passando|esta passando)\s+(?:agora\s+)?(?:(?:no|na|em|nos|nas)\s+)?(.+)$""")
    private val reTocar = Regex("""^(?:(?:eu\s+)?(?:quero\s+)?(?:assistir|ver|colocar|coloca|coloque|poe|ponha|bota|botar|muda|mudar|mude|troca|trocar|troque|sintoniza|sintonizar|liga|ligar|tocar|toca|passa|passar|vai)\s+)((?:(?:no|na|o|a|para|pra|pro|em)\s+)*)(.+)$""")
    private val reFinal = Regex("""\s+(?:agora|por favor|pra mim|para mim|ai|now|please|right now|for me)$""")
    private val reComeco = Regex("""^(?:ok google|hey google|ei google|google)\s+""")
    // Ideia 72 (14/11/2026): as mesmas frases em inglês, ao lado do português (TV em inglês; modelo: CaixaComando).
    // "what's" chega normalizado como "what s".
    private val reOQuePassaEn = Regex("""^(?:what s|whats|what is)\s+(?:on|playing|showing)(?:\s+(?:now|right now))?(?:\s+on)?\s+(.+)$""")
    private val reTocarEn = Regex("""^(?:(?:i want to|i wanna|let s)\s+)?(?:watch|put on|put|turn on|switch to|change to|turn to|tune in to|tune to|play|go to)\s+((?:(?:the|on|to)\s+)*)(.+)$""")

    /**
     * Entende "assistir/colocar no canal X" e "o que está passando na X". [semVerboTocar]: a frase sem verbo (o
     * Assistente manda só "Globo" no "tocar Globo no app") vale como "tocar". Null = não é pedido de canal.
     */
    fun interpretarFrase(frase: String, semVerboTocar: Boolean = false): PedidoVoz? {
        var f = PlayerFiltro.normalizar(frase.take(200)).replace(reComeco, "")
        repeat(2) { f = f.replace(reFinal, "") }
        if (f.isEmpty()) return null
        // "canal 5" -> "5": a palavra "canal" não ajuda a achar (a busca também a ignora, mas o texto fica mais limpo).
        fun limpar(t: String) = t.trim().removePrefix("the ").removePrefix("canal ").removePrefix("channel ").trim().ifEmpty { null }
        reOQuePassa.find(f)?.let { m -> return limpar(m.groupValues[1])?.let { PedidoVoz.OQuePassa(it) } }
        reTocar.find(f)?.let { m -> return limpar(m.groupValues[2])?.let { PedidoVoz.Tocar(it) } }
        reOQuePassaEn.find(f)?.let { m -> return limpar(m.groupValues[1])?.let { PedidoVoz.OQuePassa(it) } }
        reTocarEn.find(f)?.let { m -> return limpar(m.groupValues[2])?.let { PedidoVoz.Tocar(it) } }
        return if (semVerboTocar) limpar(f)?.let { PedidoVoz.Tocar(it) } else null
    }

    /** O JSON do catálogo (formato do ComandosIa) para um pedido de voz. */
    fun acoesDaVoz(p: PedidoVoz): String = org.json.JSONObject()
        .put("acoes", org.json.JSONArray().put(JSONObject()
            .put("tipo", if (p is PedidoVoz.OQuePassa) "o_que_passa" else "tocar_canal").put("canal", p.canal)))
        .put("resposta", "").toString()
}

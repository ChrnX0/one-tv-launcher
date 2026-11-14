package br.paulo.tvlauncher

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Player universal, passo 1 (06/11/2026): o guia de programação (EPG) no formato XMLTV, o padrão das listas de IPTV.
 *
 * O arquivo costuma ser enorme (dezenas de MB, às vezes .gz). Por isso é lido em fluxo (XmlPullParser: um elemento
 * por vez, sem carregar o arquivo inteiro na memória) e só guarda o que interessa: os canais da lista do usuário,
 * numa janela de tempo curta. O parser vem de fora (na TV, android.util.Xml.newPullParser(); nos testes, o kxml2),
 * para esta classe rodar sem Android.
 */
data class Programa(
    val guiaId: String,      // o "channel" do XMLTV = o tvg-id do canal na lista
    val inicio: Long,        // milissegundos desde 1970 (UTC)
    val fim: Long,
    val titulo: String,
    val descricao: String = "",
    val categoria: String = "",
)

object Xmltv {
    /**
     * Teto de programas guardados (memória). Com a janela de 15 h do PlayerCarga (1 h atrás, 14 h à frente) e ~1 programa
     * por hora, cabem ~4 mil canais com guia; com a janela antiga de 28 h só cabiam ~2 mil, e os canais do fim do
     * arquivo ficavam sem guia nenhum.
     */
    const val MAX_PROGRAMAS = 60_000

    /**
     * Teto quando o guia vai para o banco (GuiaBanco), e não para a memória: aí o limite é só o disco (~300 bytes por
     * programa = ~45 MB no pior caso). Com ele, lista de muitos milhares de canais não perde mais o guia do fim do arquivo.
     */
    const val MAX_PROGRAMAS_BANCO = 150_000

    /** Tamanho máximo da descrição de um programa: a faixa de informação mostra poucas linhas, e cada letra ocupa memória. */
    const val MAX_DESCRICAO = 300

    /** Abre o fluxo já descompactando se for .gz (os 2 primeiros bytes de um gzip são 1F 8B), seja qual for o nome. */
    fun abrir(entrada: InputStream): InputStream {
        val b = BufferedInputStream(entrada)
        b.mark(2)
        val p1 = b.read(); val p2 = b.read()
        b.reset()
        return if (p1 == 0x1f && p2 == 0x8b) GZIPInputStream(b) else b
    }

    /**
     * Lê os programas.
     * @param canais tvg-ids que interessam (vazio = todos)
     * @param desde / ate janela de tempo em ms: só entra programa que termina depois de `desde` e começa antes de `ate`
     */
    /** Mensagem do IOException de quem corta o download no teto de tamanho (PlayerCarga.abrir). */
    const val TETO = "arquivo grande demais"

    fun ler(
        parser: XmlPullParser,
        entrada: InputStream,
        canais: Set<String> = emptySet(),
        desde: Long = Long.MIN_VALUE,
        ate: Long = Long.MAX_VALUE,
    ): List<Programa> {
        val saida = ArrayList<Programa>()
        lerEmFluxo(parser, entrada, canais, desde, ate, MAX_PROGRAMAS) { saida += it }
        saida.sortWith(compareBy({ it.guiaId }, { it.inicio }))
        return saida
    }

    /**
     * O mesmo que [ler], mas entrega cada programa na hora em que ele termina de ser lido (aoLer), sem montar a lista.
     * É o que o PlayerCarga usa para gravar o guia direto no banco (GuiaBanco): a memória fica do tamanho de UM
     * programa, e não de dezenas de milhares (era o pico que pesava na TV de 2 GB). Devolve quantos programas entregou.
     * Mesmas regras de erro do [ler]: arquivo com defeito no meio ou corte no teto ficam com o que já foi entregue.
     */
    fun lerEmFluxo(
        parser: XmlPullParser,
        entrada: InputStream,
        canais: Set<String> = emptySet(),
        desde: Long = Long.MIN_VALUE,
        ate: Long = Long.MAX_VALUE,
        max: Int = MAX_PROGRAMAS,
        aoLer: (Programa) -> Unit,
    ): Int {
        // DOCDECL desligado de propósito: um guia baixado da internet não pode puxar entidades externas (ataque XXE).
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        // Modo tolerante do kxml (o parser do Android): aceita "&" solto num título ("Tom & Jerry"), comum em guia de IPTV.
        // Sem isso, um único "&" derrubava o guia inteiro. Outro parser que não conheça a opção só ignora.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(entrada, null)   // null = respeita o encoding que o próprio arquivo declara
        var lidos = 0
        try {
            var evento = parser.eventType
            while (evento != XmlPullParser.END_DOCUMENT && lidos < max) {
                if (evento == XmlPullParser.START_TAG && parser.name == "programme") {
                    val canal = parser.getAttributeValue(null, "channel").orEmpty()
                    val inicio = data(parser.getAttributeValue(null, "start"))
                    val fim = data(parser.getAttributeValue(null, "stop"))
                    val quer = (canais.isEmpty() || canal in canais) && inicio != null && fim != null && fim > inicio &&
                        fim > desde && inicio < ate
                    if (quer) lerPrograma(parser, canal, inicio!!, fim!!)?.let { aoLer(it); lidos++ } else pularElemento(parser)
                }
                evento = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // Arquivo com defeito no meio: fica com o que já leu (antes jogava fora tudo, e o guia sumia).
            // Sem nada lido, o erro sobe: aí o PlayerCarga sabe que este guia não serve.
            if (lidos == 0) throw e
        } catch (e: IOException) {
            // Passou do teto de tamanho: fica com o que já leu (baixar de novo daria o mesmo corte).
            // Rede que caiu no meio NÃO: o erro sobe para o PlayerCarga tentar de novo (senão um guia pela metade
            // valeria como cache "fresco" por 6 h e os canais do fim do arquivo ficariam sem guia).
            if (lidos == 0 || e.message != TETO) throw e
        }
        return lidos
    }

    /**
     * Segundo guia (ideia 15): lê um XMLTV EXTRA e entrega só os programas dos canais que a [mescla] traduz para canais
     * nossos (os que ficaram sem programação no guia principal), já com o guiaId NOSSO. Os <channel> (com os
     * display-name) vêm antes dos <programme> no padrão XMLTV: cada um passa pela mescla na hora, nada fica guardado
     * além da tradução dos canais que casaram. Mesmas regras de erro e de teto do [lerEmFluxo].
     */
    fun lerExtraEmFluxo(
        parser: XmlPullParser,
        entrada: InputStream,
        mescla: GuiaExtra.Mescla,
        desde: Long,
        ate: Long,
        max: Int,
        aoLer: (Programa) -> Unit,
    ): Int {
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(entrada, null)
        var lidos = 0
        try {
            var evento = parser.eventType
            while (evento != XmlPullParser.END_DOCUMENT && lidos < max) {
                if (evento == XmlPullParser.START_TAG && parser.name == "channel") {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    mescla.canal(id, lerNomes(parser))
                } else if (evento == XmlPullParser.START_TAG && parser.name == "programme") {
                    val destinos = mescla.destinos(parser.getAttributeValue(null, "channel").orEmpty())
                    val inicio = data(parser.getAttributeValue(null, "start"))
                    val fim = data(parser.getAttributeValue(null, "stop"))
                    val quer = destinos.isNotEmpty() && inicio != null && fim != null && fim > inicio && fim > desde && inicio < ate
                    if (quer) lerPrograma(parser, destinos[0], inicio!!, fim!!)?.let { p ->
                        for (d in destinos) { if (lidos >= max) break; aoLer(if (d == p.guiaId) p else p.copy(guiaId = d)); lidos++ }
                    } else pularElemento(parser)
                }
                evento = parser.next()
            }
        } catch (e: XmlPullParserException) {
            if (lidos == 0) throw e
        } catch (e: IOException) {
            if (lidos == 0 || e.message != TETO) throw e
        }
        return lidos
    }

    /** Os display-name de dentro de um <channel> (no máximo 5); fica parado no </channel>. */
    private fun lerNomes(p: XmlPullParser): List<String> {
        val nomes = ArrayList<String>(2)
        val profundidade = p.depth
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == profundidade) && e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                if (p.name == "display-name") p.nextText().trim().let { if (it.isNotEmpty() && nomes.size < 5) nomes += it.take(80) }
                else pularElemento(p)
            }
            e = p.next()
        }
        return nomes
    }

    /** Lê title/desc/category de dentro de um <programme>; fica parado no </programme>. */
    private fun lerPrograma(p: XmlPullParser, canal: String, inicio: Long, fim: Long): Programa? {
        var titulo = ""; var desc = ""; var cat = ""
        val profundidade = p.depth
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == profundidade) && e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    // O 1º título/descrição vence (o XMLTV pode repetir um por idioma).
                    "title" -> p.nextText().trim().let { if (titulo.isEmpty()) titulo = it }
                    "desc" -> p.nextText().trim().let { if (desc.isEmpty()) desc = it.take(MAX_DESCRICAO) }
                    "category" -> p.nextText().trim().let { if (cat.isEmpty()) cat = it }
                    else -> pularElemento(p)
                }
            }
            e = p.next()
        }
        return if (titulo.isEmpty()) null else Programa(canal, inicio, fim, titulo, desc, cat)
    }

    private fun pularElemento(p: XmlPullParser) {
        var nivel = 1
        while (nivel > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> nivel++
                XmlPullParser.END_TAG -> nivel--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Data do XMLTV: "20261105215500 -0300" (fuso opcional; sem fuso = UTC, como manda o formato).
     * Feito à mão em vez de SimpleDateFormat: é chamado milhares de vezes e o SimpleDateFormat é lento e não é seguro
     * entre threads.
     */
    fun data(texto: String?): Long? {
        val t = texto?.trim() ?: return null
        if (t.length < 12 || !t.substring(0, 12).all { it.isDigit() }) return null
        val ano = t.substring(0, 4).toInt(); val mes = t.substring(4, 6).toInt(); val dia = t.substring(6, 8).toInt()
        val hora = t.substring(8, 10).toInt(); val min = t.substring(10, 12).toInt()
        val seg = if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) t.substring(12, 14).toInt() else 0
        if (mes !in 1..12 || dia !in 1..31 || hora > 23 || min > 59 || seg > 59) return null
        var offsetMin = 0
        val fuso = t.substringAfter(' ', "").trim()
        if (fuso.length == 5 && (fuso[0] == '+' || fuso[0] == '-') && fuso.substring(1).all { it.isDigit() }) {
            offsetMin = fuso.substring(1, 3).toInt() * 60 + fuso.substring(3, 5).toInt()
            if (fuso[0] == '-') offsetMin = -offsetMin
        }
        val dias = diasDesde1970(ano, mes, dia)
        return ((dias * 24 + hora) * 60 + min - offsetMin) * 60_000L + seg * 1000L
    }

    /** Dias entre 01/01/1970 e a data (calendário gregoriano). Fórmula de Howard Hinnant ("days_from_civil"). */
    private fun diasDesde1970(ano: Int, mes: Int, dia: Int): Long {
        val a = if (mes <= 2) ano - 1 else ano
        val era = (if (a >= 0) a else a - 399) / 400
        val anoDaEra = a - era * 400
        val diaDoAno = (153 * (mes + (if (mes > 2) -3 else 9)) + 2) / 5 + dia - 1
        val diaDaEra = anoDaEra * 365 + anoDaEra / 4 - anoDaEra / 100 + diaDoAno
        return era * 146097L + diaDaEra - 719468L
    }
}

/**
 * Consultas rápidas no guia: "o que passa agora" e "o que vem depois" num canal.
 *
 * Desde 10/11/2026 os programas não ficam mais todos na memória: vêm de uma [FonteGuia] (na TV, o banco SQLite do
 * GuiaBanco, com índice por canal) só quando um canal é pedido, e os últimos canais consultados ficam num cache pequeno.
 * Assim trocar de canal (zapping) busca só o guia daquele canal, na hora, sem reler nada. As telas usam a mesma API de antes.
 */
class Guia(private val fonte: FonteGuia, maxCanais: Int = 300) {
    /** Guia em memória (testes, guia vazio): a mesma coisa de antes. */
    constructor(programas: List<Programa>) : this(FonteGuiaMemoria(programas))

    /**
     * Diz se quem chama está na thread da tela. A PlayerActivity liga (revisão de 10/11/2026): aí um canal que ainda não
     * está no cache NÃO vai ao disco na hora (lista, grade e faixa desenham na thread da tela, e rolar rápido virava uma
     * consulta por linha). O canal entra numa fila, é lido em bloco em segundo plano e [aoCarregar] avisa para redesenhar.
     * Fora do player (testes, HOME, Painel) fica desligado: a consulta é feita na hora, como antes.
     */
    @Volatile var naTela: () -> Boolean = { false }

    /** Chamado (em segundo plano) quando canais pedidos pela tela chegaram ao cache. Quem liga põe o redesenho na tela. */
    @Volatile var aoCarregar: (() -> Unit)? = null

    /** Onde roda a leitura em bloco. Uma thread curta por rodada (só existe enquanto há fila); os testes trocam. */
    @Volatile var emSegundoPlano: (Runnable) -> Unit = { Thread(it, "guia").start() }

    // Cache "o menos usado sai primeiro" (LinkedHashMap em ordem de acesso): a lista e a grade pedem os mesmos canais
    // muitas vezes por segundo ao desenhar; só a 1ª vez vai ao banco.
    private val cache = object : LinkedHashMap<String, List<Programa>>(64, 0.75f, true) {
        override fun removeEldestEntry(maisVelho: MutableMap.MutableEntry<String, List<Programa>>?) = size > maxCanais
    }
    private val maxFila = (maxCanais / 2).coerceAtLeast(1)
    // Fila dos canais pedidos pela tela (protegida pelo mesmo lock do cache). Passou do tamanho: sai o pedido mais velho
    // (o canal que já saiu da tela ao rolar rápido).
    private val fila = LinkedHashSet<String>()
    private var lendo = false
    private var fechado = false

    /** Todos os programas de um canal, em ordem (lista vazia se o canal não tem guia, ou ainda não chegou). Para o guia em grade. */
    fun doCanal(guiaId: String): List<Programa> {
        synchronized(cache) { cache[guiaId]?.let { return it } }
        if (naTela()) { pedir(listOf(guiaId)); return emptyList() }
        // Falha na leitura (banco fechado, disco) = null: mostra vazio agora, mas NÃO guarda, para tentar de novo depois.
        val lidos = fonte.doCanal(guiaId) ?: return emptyList()
        synchronized(cache) { cache[guiaId] = lidos }
        return lidos
    }

    /** Põe na fila os canais que logo vão aparecer (vizinhos no zapping), para chegarem antes de serem pedidos. */
    fun precarregar(ids: Collection<String>) = pedir(ids.filter { it.isNotEmpty() })

    private fun pedir(ids: Collection<String>) {
        synchronized(cache) {
            if (fechado) return
            for (id in ids) if (!cache.containsKey(id)) { fila.remove(id); fila += id }
            while (fila.size > maxFila) fila.remove(fila.first())
            if (fila.isEmpty() || lendo) return
            lendo = true
        }
        emSegundoPlano(Runnable { lerFila() })
    }

    /** Esvazia a fila em blocos de [LOTE] canais por consulta (uma só ida ao banco por bloco). */
    private fun lerFila() {
        while (true) {
            val lote = synchronized(cache) {
                if (fila.isEmpty() || fechado) { fila.clear(); lendo = false; return }
                fila.take(LOTE).also { fila.removeAll(it.toSet()) }
            }
            // Falha (banco fechado, disco): não guarda nem avisa; a tela pede de novo no próximo desenho.
            val lidos = runCatching { fonte.doCanais(lote) }.getOrNull() ?: continue
            synchronized(cache) { for (id in lote) cache[id] = lidos[id].orEmpty() }
            runCatching { aoCarregar?.invoke() }
        }
    }

    fun agora(guiaId: String, instante: Long): Programa? = GuiaConsulta.agora(doCanal(guiaId), instante)

    /** Os próximos `quantos` programas a partir de `instante` (o atual incluído, se houver). */
    fun proximos(guiaId: String, instante: Long, quantos: Int = 4): List<Programa> =
        GuiaConsulta.proximos(doCanal(guiaId), instante, quantos)

    /** Fração já assistida do programa atual (0..1), para a barrinha de progresso dos cards. */
    fun progresso(p: Programa, instante: Long): Float = GuiaConsulta.progresso(p, instante)

    /**
     * Os canais (guiaId) que têm programa passando em `instante`, numa consulta só. Para contar "N com programação agora"
     * sem fazer uma consulta por canal (com 20 mil canais isso levaria segundos).
     */
    fun comProgramaAgora(instante: Long): Set<String> = fonte.comPrograma(instante) ?: emptySet()

    /**
     * O título do que passa agora em cada canal (guiaId -> título), numa consulta só. Para a busca pelo programa: antes
     * era uma consulta por canal, na thread da tela (com milhares de canais, travava). Disco: chamar FORA da thread da tela.
     */
    fun titulosAgora(instante: Long): Map<String, String> = fonte.titulosAgora(instante) ?: emptyMap()

    /** Solta o banco. Só quando ninguém mais usa este Guia (o PlayerCarga cuida disso). */
    fun fechar() {
        synchronized(cache) { fechado = true; fila.clear() }
        fonte.fechar()
    }

    private companion object { const val LOTE = 50 }
}

/** De onde o [Guia] tira os programas. null = falhou agora (o Guia não guarda no cache). */
interface FonteGuia {
    /** Os programas do canal, em ordem de início. */
    fun doCanal(guiaId: String): List<Programa>?
    /** Os programas de vários canais de uma vez (canal sem guia pode faltar no mapa). */
    fun doCanais(ids: Collection<String>): Map<String, List<Programa>>? {
        val saida = HashMap<String, List<Programa>>()
        for (id in ids) saida[id] = doCanal(id) ?: return null
        return saida
    }
    fun comPrograma(instante: Long): Set<String>?
    /** guiaId -> título do programa no ar em `instante` (o de início mais cedo, se houver dois). */
    fun titulosAgora(instante: Long): Map<String, String>?
    /**
     * Entrega um a um os programas que começam entre [desde] e [ate] (lembretes do "Meu time", 12/11/2026). Em fluxo:
     * nada de lista inteira na memória. false = falhou (ou a fonte não sabe fazer).
     */
    fun programasEntre(desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean = false
    /**
     * Os títulos diferentes dos programas que começam entre [desde] e [ate] e ainda não acabaram em [ate0] (busca única,
     * 12/11/2026: normalizar cada título UMA vez em vez de cada programa a cada tecla). null = a fonte não sabe fazer.
     */
    fun titulosEntre(desde: Long, ate: Long, ate0: Long): Set<String>? {
        val saida = HashSet<String>()
        return if (programasEntre(desde, ate) { if (it.fim > ate0) saida += it.titulo }) saida else null
    }
    /** Os programas entre [desde] e [ate] com um destes títulos exatos. false = falhou. */
    fun programasComTitulos(titulos: Collection<String>, desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean {
        val quero = titulos.toHashSet()
        return programasEntre(desde, ate) { if (it.titulo in quero) aoLer(it) }
    }
    fun fechar() {}
}

/** Programas em memória: para os testes e para o guia vazio. Na TV o guia de verdade vem do banco (GuiaBanco). */
class FonteGuiaMemoria(programas: List<Programa>) : FonteGuia {
    private val porCanal: Map<String, List<Programa>> = programas.groupBy { it.guiaId }.mapValues { (_, l) -> l.sortedBy { it.inicio } }
    override fun doCanal(guiaId: String): List<Programa> = porCanal[guiaId].orEmpty()
    override fun comPrograma(instante: Long): Set<String> =
        porCanal.filterValues { l -> GuiaConsulta.agora(l, instante) != null }.keys
    override fun titulosAgora(instante: Long): Map<String, String> =
        porCanal.mapNotNull { (id, l) -> GuiaConsulta.agora(l, instante)?.let { id to it.titulo } }.toMap()
    override fun programasEntre(desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean {
        for (l in porCanal.values) for (p in l) if (p.inicio in desde..ate) aoLer(p)
        return true
    }
}

/** A lógica das consultas, pura (sem banco), para o teste JVM conferir. A lista vem sempre em ordem de início. */
object GuiaConsulta {
    /** O programa no ar em `instante`: começou e ainda não acabou (o fim é "aberto": às 21:00 já vale o das 21:00). */
    fun agora(programas: List<Programa>, instante: Long): Programa? =
        programas.firstOrNull { instante >= it.inicio && instante < it.fim }

    /** Os próximos `quantos` a partir de `instante`, com o atual na frente. O que já acabou fica de fora. */
    fun proximos(programas: List<Programa>, instante: Long, quantos: Int): List<Programa> =
        programas.asSequence().filter { it.fim > instante }.take(quantos.coerceAtLeast(0)).toList()

    fun progresso(p: Programa, instante: Long): Float =
        ((instante - p.inicio).toFloat() / (p.fim - p.inicio)).coerceIn(0f, 1f)

    /**
     * A janela de tempo que o guia guarda, a partir de agora: 1 h para trás (o programa que começou antes de abrir o
     * player) e 14 h para a frente (a noite toda no guia em grade). Mesma janela de antes da troca para o banco.
     */
    fun janela(agora: Long): Pair<Long, Long> = (agora - 1 * 3_600_000L) to (agora + 14 * 3_600_000L)
}

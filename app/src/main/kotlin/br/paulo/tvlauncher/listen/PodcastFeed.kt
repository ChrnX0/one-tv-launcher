package br.paulo.tvlauncher.listen

import br.paulo.tvlauncher.player.Episodio
import java.text.SimpleDateFormat
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/**
 * Podcasts ("Ouvir" parte 2, 17/11/2026): leitura PURA do feed RSS de um podcast e da lista OPML que os apps de podcast
 * do celular exportam. O parser vem de fora (na TV, android.util.Xml.newPullParser(); nos testes, o kxml2), como no
 * PlayerGuia. Testes: PodcastFeedTest.
 *
 * O feed é publicado pelo próprio autor; tocamos o episódio direto do servidor dele, sem copiar nem regravar.
 * Entende o RSS 2.0 com as marcas do iTunes (duração, capa, conteúdo explícito, tipo do episódio) e do "Podcasting 2.0"
 * (podcast:transcript = transcrição, podcast:chapters = capítulos, podcast:locked).
 *
 * Proteções (feed inválido ou gigante não pode derrubar a tela): no máximo [MAX_EPISODIOS] episódios, textos cortados,
 * endereços só http(s), e quem baixa corta o arquivo em [MAX_BYTES]. Feed cortado (ou com erro no meio) vale com os
 * episódios lidos até ali: feeds reais de podcast antigo passam de 20 MB (medido 17/11/2026: um de 20,7 MB, o Nerdcast
 * com 8,9 MB), quase sempre com o mais novo primeiro. Feed em ordem do mais velho para o mais novo: a leitura vai
 * até o fim (ou o corte) guardando só os [MAX_EPISODIOS] mais novos do que leu; cortado nos 8 MB, os mais novos que
 * vinham depois do corte ficam de fora (limite aceito: o corte protege a memória do aparelho).
 * Cada texto acumulado para em [MAX_ACUMULADO] (um único texto gigante não vira várias cópias grandes na memória).
 * Entidades declaradas no DTD (`<!ENTITY`, o ataque "billion laughs") são recusadas antes do leitor ver: [Entrada].
 */
object PodcastFeed {
    const val MAX_EPISODIOS = 300
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_OPML = 500
    /** Prazo total para baixar um feed (servidor que manda 1 byte a cada tanto não segura a atualização por horas). */
    const val PRAZO_MS = 60_000L
    private const val MAX_TEXTO = 600
    private const val MAX_ACUMULADO = 20_000

    data class Transcricao(val url: String, val tipo: String, val idioma: String)

    data class Episodio(
        val guid: String, val titulo: String, val resumo: String, val dataMs: Long, val duracaoS: Int,
        val url: String, val tipo: String, val tamanho: Long, val capa: String, val explicito: Boolean,
        val temporada: Int, val numero: Int, val transcricoes: List<Transcricao>, val capitulos: String,
    ) {
        /** Vídeo no feed (alguns podcasts publicam o episódio filmado): abre como vídeo no player. */
        val video: Boolean get() = tipo.startsWith("video/") || url.substringBefore('?').lowercase().let { u ->
            u.endsWith(".mp4") || u.endsWith(".m4v") || u.endsWith(".mov") || u.endsWith(".m3u8") && tipo.startsWith("video") }
    }

    data class Feed(val titulo: String, val autor: String, val descricao: String, val capa: String, val link: String,
                    val idioma: String, val explicito: Boolean, val episodios: List<Episodio>,
                    /** Os assuntos que o próprio podcast declara (itunes:category, em inglês: "News", "Comedy"...); [PodcastAssuntos]. */
                    val categorias: List<String> = emptyList())

    /** Um podcast da lista OPML: o endereço do feed e o nome que o app do celular dava a ele. */
    data class ItemOpml(val url: String, val titulo: String)

    // ---------------------------------------------------------------- feed

    /**
     * Lê o feed inteiro de [p] (já com a entrada definida). Null se não for RSS de podcast (sem canal ou sem nenhum
     * episódio tocável). Episódios sem arquivo de áudio/vídeo ficam de fora; repetidos (mesmo guid) também.
     * Ordem: mais novo primeiro.
     */
    fun ler(p: XmlPullParser): Feed? {
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) } catch (_: Exception) {}
        var titulo = ""; var autor = ""; var descricao = ""; var capa = ""; var link = ""; var idioma = ""; var explicito = false
        var temCanal = false
        val categorias = LinkedHashSet<String>()
        val episodios = ArrayList<Episodio>()
        val vistos = HashSet<String>()
        var e: MutableMap<String, String>? = null
        var transcricoes = ArrayList<Transcricao>()
        val pilha = ArrayList<String>()
        var tipo = try { p.eventType } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
        while (tipo != XmlPullParser.END_DOCUMENT) {
            when (tipo) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase()
                    pilha.add(n)
                    when {
                        n == "channel" -> temCanal = true
                        n == "item" -> { e = HashMap(); transcricoes = ArrayList() }
                        n == "enclosure" && e != null -> {
                            if (e["url"].isNullOrEmpty()) {
                                e["url"] = p.getAttributeValue(null, "url").orEmpty().trim()
                                e["tipo"] = p.getAttributeValue(null, "type").orEmpty().trim().lowercase()
                                e["tamanho"] = p.getAttributeValue(null, "length").orEmpty().trim()
                            }
                        }
                        // Assunto do canal: <itunes:category text="Society &amp; Culture"><itunes:category text="Documentary"/>
                        n == "itunes:category" && e == null -> p.getAttributeValue(null, "text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
                            ?.let { if (categorias.size < 8) categorias += it }
                        n == "itunes:image" -> {
                            val h = p.getAttributeValue(null, "href").orEmpty().trim()
                            if (e != null) { if (e["capa"].isNullOrEmpty()) e["capa"] = h } else if (capa.isEmpty()) capa = h
                        }
                        n == "podcast:transcript" && e != null -> {
                            val u = p.getAttributeValue(null, "url").orEmpty().trim()
                            if (urlValida(u)) transcricoes.add(Transcricao(u, p.getAttributeValue(null, "type").orEmpty().lowercase(),
                                p.getAttributeValue(null, "language").orEmpty()))
                        }
                        n == "podcast:chapters" && e != null -> e["capitulos"] = p.getAttributeValue(null, "url").orEmpty().trim()
                        // Capítulos Podlove dentro do próprio feed (<psc:chapter start="00:12:30" title="..."/>), comuns
                        // em podcasts europeus e brasileiros feitos no Podlove: guardados como linhas "início\ttítulo".
                        n == "psc:chapter" && e != null -> {
                            val ini = p.getAttributeValue(null, "start").orEmpty().trim()
                            val tit = p.getAttributeValue(null, "title").orEmpty().replace('\t', ' ').replace('\n', ' ').trim().take(120)
                            val atual = e["psc"].orEmpty()
                            if (ini.isNotEmpty() && tit.isNotEmpty() && atual.count { it == '\n' } < Capitulos.MAX) e["psc"] = atual + ini + "\t" + tit + "\n"
                        }
                        n == "media:content" && e != null && e["url"].isNullOrEmpty() -> {
                            // Alguns feeds só trazem media:content (Media RSS) em vez de enclosure.
                            val u = p.getAttributeValue(null, "url").orEmpty().trim()
                            val t = p.getAttributeValue(null, "type").orEmpty().lowercase()
                            if (t.startsWith("audio/") || t.startsWith("video/")) { e["url"] = u; e["tipo"] = t }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val n = pilha.lastOrNull()
                    val pai = pilha.getOrNull(pilha.size - 2)
                    val t = p.text ?: ""
                    if (n != null && t.isNotBlank()) {
                        val alvo = e
                        if (alvo != null && pilha.contains("item")) {
                            if (pai == "item") when (n) {
                                "title", "guid", "pubdate", "description", "itunes:duration", "itunes:explicit",
                                "itunes:summary", "itunes:season", "itunes:episode", "itunes:title" ->
                                    alvo[n] = juntar(alvo[n] ?: "", t)
                            }
                        } else if (pai == "channel") when (n) {
                            "title" -> titulo = juntar(titulo, t)
                            "itunes:author" -> autor = juntar(autor, t)
                            "description" -> descricao = juntar(descricao, t)
                            "itunes:summary" -> if (descricao.isBlank()) descricao = juntar(descricao, t)
                            "link" -> link = juntar(link, t)
                            "language" -> idioma = juntar(idioma, t)
                            "itunes:explicit" -> explicito = simExplicito(t)
                        } else if (pai == "image" && n == "url" && capa.isEmpty() && pilha.getOrNull(pilha.size - 3) == "channel") capa = t.trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = pilha.removeLastOrNull()
                    if (n == "item") {
                        e?.let { m -> episodio(m, transcricoes, explicito)?.let { ep -> if (vistos.add(ep.guid)) episodios.add(ep) } }
                        e = null
                        // Guarda só os mais novos: a lista não cresce sem fim num feed com milhares de episódios.
                        if (episodios.size >= 2 * MAX_EPISODIOS) {
                            val novos = episodios.sortedByDescending { it.dataMs }.take(MAX_EPISODIOS)
                            episodios.clear(); episodios.addAll(novos)
                        }
                    }
                }
            }
            // Erro no meio (arquivo cortado no limite, XML quebrado): fica com o que já leu.
            tipo = try { p.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
        }
        if (!temCanal || episodios.isEmpty()) return null
        return Feed(limpar(titulo, 200), limpar(autor, 200), limpar(descricao, MAX_TEXTO), capa.takeIf { urlValida(it) }.orEmpty(),
            link.trim().takeIf { urlValida(it) }.orEmpty(), idioma.trim().take(10), explicito,
            episodios.sortedByDescending { it.dataMs }.take(MAX_EPISODIOS), categorias.toList())
    }

    private fun juntar(a: String, b: String) = if (a.length >= MAX_ACUMULADO) a else (a + b).take(MAX_ACUMULADO)

    private fun episodio(m: Map<String, String>, trs: List<Transcricao>, canalExplicito: Boolean): Episodio? {
        val url = m["url"].orEmpty()
        if (!urlValida(url)) return null
        val tipo = m["tipo"].orEmpty()
        // Só áudio/vídeo (ou sem tipo declarado, comum em feeds velhos): PDF, imagem etc. ficam de fora.
        if (tipo.isNotEmpty() && !tipo.startsWith("audio/") && !tipo.startsWith("video/") && tipo != "application/x-mpegurl"
            && tipo != "application/vnd.apple.mpegurl" && tipo != "application/octet-stream") return null
        val titulo = limpar(m["itunes:title"]?.takeIf { it.isNotBlank() } ?: m["title"].orEmpty(), 200)
        if (titulo.isEmpty()) return null
        val guid = m["guid"]?.trim()?.takeIf { it.isNotEmpty() } ?: url
        return Episodio(guid.take(300), titulo, limpar(m["description"]?.takeIf { it.isNotBlank() } ?: m["itunes:summary"].orEmpty(), MAX_TEXTO),
            data(m["pubdate"]), duracao(m["itunes:duration"]), url, tipo, m["tamanho"]?.toLongOrNull() ?: 0L,
            m["capa"].orEmpty().takeIf { urlValida(it) }.orEmpty(),
            m["itunes:explicit"]?.let { simExplicito(it) } ?: canalExplicito,
            m["itunes:season"]?.trim()?.toIntOrNull() ?: 0, m["itunes:episode"]?.trim()?.toIntOrNull() ?: 0,
            trs.take(5), m["capitulos"].orEmpty().takeIf { urlValida(it) }
                ?: m["psc"]?.takeIf { it.isNotBlank() }?.let { Capitulos.PSC + it.trimEnd() }.orEmpty())
    }

    // ---------------------------------------------------------------- entrada protegida

    /** O texto declara entidades no DTD (`<!ENTITY`)? Feed e OPML de verdade não declaram; recusar fecha o "billion laughs". */
    fun temEntidade(t: String) = t.contains("<!ENTITY")

    /**
     * O que o leitor de XML lê da rede, com três proteções:
     *  - acaba em [maxBytes] (feed gigante ou sem fim não enche a memória: o leitor vê o fim e fica com o que leu);
     *  - prazo total [prazoMs] desde o primeiro byte pedido ([relogio] em ms; servidor lento demais = [estourou]);
     *  - `<!ENTITY` em qualquer ponto = [recusou] (o DTD vem antes do conteúdo, então a recusa chega antes da expansão).
     * Estourar ou recusar devolve erro de leitura; quem chama confere [estourou]/[recusou] e trata como erro, não como corte.
     */
    class Entrada(dentro: java.io.InputStream, private var resta: Long, private val prazoMs: Long = PRAZO_MS,
                  private val relogio: () -> Long = { System.nanoTime() / 1_000_000 }) : java.io.FilterInputStream(dentro) {
        private var inicio = -1L
        private var casou = 0
        var estourou = false; private set
        var recusou = false; private set

        private fun conferir() {
            val agora = relogio()
            if (inicio < 0) inicio = agora
            if (agora - inicio > prazoMs) { estourou = true; throw java.io.IOException("prazo do feed estourou") }
            if (recusou) throw java.io.IOException("feed com entidades no DTD")
        }

        // Começo do arquivo (sem os zeros): a declaração `encoding=` vem nele.
        private val cabeca = StringBuilder()

        private fun olhar(c: Int) {
            if (c != 0 && cabeca.length < CABECA) {
                cabeca.append(c.toChar())
                // UTF-7: "<!ENTITY" vira "+ADwAIQ-ENTITY" e passava pela busca acima. Feed de verdade não usa UTF-7.
                if (cabeca.length >= 5 && cabeca.contains("utf-7", ignoreCase = true)) recusou = true
            }
            // Byte zero não conta: em UTF-16/32 "<!ENTITY" vem com zeros no meio e o padrão nunca casava (o parser do
            // Android decodifica UTF-16 e expandiria as entidades). Pulando o zero, a busca vale para os dois.
            if (c == 0) return
            casou = when {
                c == PADRAO[casou].code -> casou + 1
                c == '<'.code -> 1
                else -> 0
            }
            if (casou == PADRAO.length) { recusou = true; casou = 0 }
        }

        override fun read(): Int {
            conferir()
            if (resta <= 0) return -1
            return super.read().also { if (it >= 0) { resta--; olhar(it) } }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            conferir()
            if (resta <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), resta).toInt())
            if (n > 0) { resta -= n; for (i in off until off + n) olhar(b[i].toInt() and 0xFF) }
            if (recusou) throw java.io.IOException("feed com entidades no DTD")
            return n
        }

        private companion object { const val PADRAO = "<!ENTITY"; const val CABECA = 256 }
    }

    // ---------------------------------------------------------------- OPML

    /** Os feeds de uma lista OPML (exportada pelo app de podcast do celular), sem repetir, até [MAX_OPML]. */
    fun lerOpml(p: XmlPullParser): List<ItemOpml> {
        val l = LinkedHashMap<String, ItemOpml>()
        var tipo = p.eventType
        while (tipo != XmlPullParser.END_DOCUMENT && l.size < MAX_OPML) {
            if (tipo == XmlPullParser.START_TAG && p.name.equals("outline", ignoreCase = true)) {
                val u = (p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl")).orEmpty().trim()
                if (urlValida(u) && u !in l) {
                    val t = (p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text")).orEmpty()
                    l[u] = ItemOpml(u, limpar(t, 200))
                }
            }
            // XML quebrado no meio (item 150 de 200): fica com o que já leu, como a leitura do feed (18/11/2026).
            tipo = try { p.next() } catch (_: org.xmlpull.v1.XmlPullParserException) { break } catch (_: java.io.IOException) { break }
        }
        return l.values.toList()
    }

    /** O OPML de volta (exportar as assinaturas para outro app). */
    fun paraOpml(itens: List<ItemOpml>, titulo: String = "Sofaly"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>")
        append(xml(titulo)).append("</title></head>\n<body>\n")
        itens.forEach { append("<outline type=\"rss\" text=\"").append(xml(it.titulo)).append("\" title=\"").append(xml(it.titulo))
            .append("\" xmlUrl=\"").append(xml(it.url)).append("\"/>\n") }
        append("</body>\n</opml>\n")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------------------------------------------------------- pedaços

    fun urlValida(u: String): Boolean = Regex("""^https?://[^\s/?#]+[^\s]*$""", RegexOption.IGNORE_CASE).matches(u) && u.length <= 2000

    /** "yes", "true", "explicit" = explícito (o iTunes já aceitou os três); "no", "clean", "false" = não. */
    fun simExplicito(t: String): Boolean = t.trim().lowercase() in setOf("yes", "true", "explicit")

    /** itunes:duration: "3600", "59:30", "1:02:03" (e "1:02:03.5"). 0 se não entender. */
    fun duracao(t: String?): Int {
        val s = t?.trim()?.substringBefore('.') ?: return 0
        if (s.isEmpty()) return 0
        val partes = s.split(':')
        if (partes.size > 3 || partes.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 6 }) return 0
        return partes.fold(0) { acc, p -> acc * 60 + p.toInt() }.coerceAtMost(48 * 3600)
    }

    private val FORMATOS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "EEE, dd MMM yyyy", "yyyy-MM-dd")

    // Um jogo por thread (SimpleDateFormat não é seguro entre threads): antes eram 9 criados por item, milhares por feed.
    private val formatos = ThreadLocal.withInitial { FORMATOS.map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } } }

    /** pubDate (RFC 822, às vezes ISO 8601 ou sem hora) em ms; 0 se não entender. */
    fun data(t: String?): Long {
        val s = t?.trim()?.replace(Regex("\\s+"), " ") ?: return 0L
        if (s.isEmpty()) return 0L
        for (fmt in formatos.get()!!) {
            val r = runCatching { fmt.parse(s)?.time }.getOrNull()
            if (r != null) return r
        }
        return 0L
    }

    /** Tira HTML, junta espaços, decodifica as entidades comuns e corta em [max]. */
    fun limpar(t: String, max: Int): String {
        val semTag = t.replace(Regex("(?i)<br\\s*/?>|</p>"), " ").replace(Regex("<[^>]{0,500}>"), "")
        val ent = semTag.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: "" }
        val j = ent.replace(Regex("\\s+"), " ").trim()
        return if (j.length <= max) j else j.take(max - 1).trimEnd() + "…"
    }
}

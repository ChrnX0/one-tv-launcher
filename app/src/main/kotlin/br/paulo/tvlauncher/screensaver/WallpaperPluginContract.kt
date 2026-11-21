package br.paulo.tvlauncher.screensaver

import br.paulo.tvlauncher.player.item

/**
 * Plugins de FUNDO compatíveis com o ecossistema que já existe para TV (18/11/2026, thread "Plugins visuais").
 *
 * O contrato é o dos "wallpaper providers" públicos (modelo spocky/projectivy-plugin-wallpaper-provider, Apache 2.0,
 * versão 1, lido em 18/11/2026): um app separado declara um Service exportado com a ação [ACTION] e metadados
 * (apiVersion, uuid, name, settingsActivity, itemsCacheDurationMillis, updateMode). O launcher se liga ao serviço e
 * chama, pela interface Binder [DESCRIPTOR]:
 *   0  List<Wallpaper> getWallpapers(in Event event)
 *   1  String getPreferences()        (não usamos)
 *   2  void setPreferences(String)    (não usamos)
 * Não copiamos o código deles: o pedido e a resposta são escritos à mão ([escreverEvento] / [lerResposta]) no mesmo
 * formato de Parcel que o AIDL + @Parcelize geram. Assim os plugins que já existem funcionam aqui sem mudar nada.
 *
 * Regra do Sofaly: o plugin manda DADOS (endereços de imagem/vídeo, autor); quem desenha é a HOME, com o véu da cruz por
 * cima, então nenhum fundo deixa a tela ilegível. Na tela não aparece o nome do outro launcher: "plugins de fundo".
 *
 * Só a parte pura (WallpaperPluginContractTest). O Android está em [WallpaperPlugins] e [WallpaperPluginView].
 */
object WallpaperPluginContract {
    const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"
    const val DESCRIPTOR = "tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService"
    /** O plugin avisa que mudou (preferências ou dados novos); extra [EXTRA_PROVIDER_ID] = uuid dele. */
    const val ACTION_UPDATED = "tv.projectivy.plugin.action.WALLPAPER_PROVIDER_UPDATED"
    const val EXTRA_PROVIDER_ID = "tv.projectivy.plugin.extra.PROVIDER_ID"
    const val API_VERSION = 1
    const val TX_GET_WALLPAPERS = 1   // IBinder.FIRST_CALL_TRANSACTION + 0

    // Tipos de fundo (WallpaperType)
    const val IMAGE = 0
    const val DRAWABLE = 1
    const val ANIMATED_DRAWABLE = 2
    const val LOTTIE = 3
    const val VIDEO = 4
    const val COLOR = 5

    // Modos de exibição (WallpaperDisplayMode)
    const val MODE_DEFAULT = 0
    const val MODE_CROP = 1
    const val MODE_STRETCH = 2
    const val MODE_BLUR = 3
    const val MODE_FIT_CENTER = 4
    const val MODE_FIT_CENTER_BLURRED_BG = 5

    // Eventos (WallpaperUpdateEventType): bits do updateMode
    const val EV_TIME_ELAPSED = 1
    const val EV_NOW_PLAYING_CHANGED = 2
    const val EV_CARD_FOCUSED = 4
    const val EV_PROGRAM_CARD_FOCUSED = 8
    const val EV_LAUNCHER_IDLE_MODE_CHANGED = 16

    /** No máximo quantos fundos de uma resposta guardamos (o plugin pode mandar centenas; a TV tem 2 GB). */
    const val MAX_ITEMS = 40
    /** Cache que o plugin pede: entre 10 min e 7 dias (0 ou lixo = 12 h, o padrão do modelo deles). */
    const val CACHE_MIN_MS = 10 * 60_000L
    const val CACHE_MAX_MS = 7 * 24 * 3_600_000L
    const val CACHE_PADRAO_MS = 12 * 3_600_000L
    /** De quanto em quanto tempo o fundo troca com a HOME na frente. */
    const val TROCA_MS = 10 * 60_000L
    /** Plugin respondeu lista vazia: pergunta de novo só depois disto (sem laço de pedidos com a HOME aberta). */
    const val VAZIO_MS = 15 * 60_000L
    /** Menor espera de qualquer agendamento (relógio estranho, cache quase vencido). */
    const val ESPERA_MIN_MS = 60_000L

    /** Pede a lista de novo? Com itens: quando o cache do plugin venceu. Vazia: só depois de [VAZIO_MS]. */
    fun precisaPedir(n: Int, idadeMs: Long, cacheMs: Long): Boolean =
        idadeMs < 0 || idadeMs > (if (n == 0) VAZIO_MS else cacheMs)

    /** Quanto esperar até a próxima troca: [TROCA_MS] com 2+ itens, senão até a hora de pedir a lista de novo. */
    fun esperaMs(n: Int, idadeMs: Long, cacheMs: Long): Long {
        val limite = if (n == 0) VAZIO_MS else cacheMs
        val falta = (limite - idadeMs.coerceAtLeast(0)).coerceAtLeast(ESPERA_MIN_MS)
        return if (n > 1) TROCA_MS else falta
    }

    /** Um fundo, como o plugin manda (campos na ordem do Parcel). */
    data class Item(
        val uri: String, val type: Int = IMAGE, val displayMode: Int = MODE_CROP,
        val title: String? = null, val source: String? = null, val author: String? = null, val actionUri: String? = null,
    )

    /** O evento que o launcher manda. [title]/[packageName] só no foco de cartão; [idle] só no ocioso. */
    data class Event(val type: Int, val title: String? = null, val packageName: String? = null,
                     val lightColor: Int = 0, val darkColor: Int = 0, val idle: Boolean = false)

    /** O mínimo de um Parcel que precisamos (o de verdade, ou um falso nos testes). */
    interface Saida { fun int(v: Int); fun string(v: String?) }
    interface Entrada { fun int(): Int; fun string(): String? }

    /**
     * O evento no formato do AIDL: o marcador "não nulo" (1) e depois os campos que o @Parcelize escreve, na ordem do
     * construtor. O lado do plugin lê o 1º int como tipo e escolhe a classe. (TimeElapsed é um `object`: o @Parcelize
     * escreve 1, que por coincidência é o próprio tipo.)
     */
    fun escreverEvento(e: Event, p: Saida) {
        p.int(1)
        when (e.type) {
            EV_CARD_FOCUSED -> { p.int(e.type); p.int(e.lightColor); p.int(e.darkColor); p.string(e.title); p.string(e.packageName) }
            EV_LAUNCHER_IDLE_MODE_CHANGED -> { p.int(e.type); p.int(if (e.idle) 1 else 0) }
            else -> p.int(EV_TIME_ELAPSED)
        }
    }

    /**
     * A resposta de getWallpapers, DEPOIS do readException: a lista tipada (tamanho, -1 = nula; cada item com o
     * marcador 0/1). Itens que não servem ficam de fora ([limpar]); no máximo [MAX_ITEMS].
     */
    fun lerResposta(p: Entrada, permitido: (String) -> Boolean): List<Item> {
        val n = p.int()
        if (n <= 0) return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until minOf(n, 500)) {
            if (p.int() == 0) continue
            val item = Item(p.string() ?: "", p.int(), p.int(), p.string(), p.string(), p.string(), p.string())
            limpar(item, permitido)?.let { if (out.size < MAX_ITEMS) out += it }
        }
        return out
    }

    private fun texto(t: String?, max: Int) = t?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /**
     * Confere um fundo: tipo que sabemos desenhar (Lottie não: precisaria de uma biblioteca só para isso), endereço com
     * esquema aceito, textos curtos e sem controle. Null = descartar.
     */
    fun limpar(i: Item, permitido: (String) -> Boolean): Item? {
        val uri = i.uri.trim()
        if (uri.isEmpty() || uri.length > 4096) return null
        if (i.type == COLOR) return if (cor(uri) != null) i.copy(uri = uri, title = texto(i.title, 80), source = null, author = texto(i.author, 60), actionUri = null) else null
        if (i.type !in listOf(IMAGE, DRAWABLE, ANIMATED_DRAWABLE, VIDEO)) return null
        if (!esquemaAceito(uri) || !permitido(uri)) return null
        return Item(uri, i.type, if (i.displayMode in MODE_DEFAULT..MODE_FIT_CENTER_BLURRED_BG) i.displayMode else MODE_CROP,
            texto(i.title, 80), texto(i.source, 120), texto(i.author, 60), null)
    }

    /**
     * Esquemas aceitos: https/http (TLS normal: nunca desligamos a verificação), content (o provider do plugin dá a
     * permissão) e android.resource (recurso do próprio plugin). file:// não: o launcher não lê arquivos que outro app
     * apontar ([permitido] ainda deixa o chamador recusar mais).
     */
    fun esquemaAceito(uri: String): Boolean {
        val e = uri.substringBefore(':', "").lowercase()
        return e == "https" || e == "http" || e == "content" || e == "android.resource"
    }

    /** "#RRGGBB" ou "#AARRGGBB" (sempre opaca na tela) ou um inteiro em texto. Null = não é cor. */
    fun cor(t: String): Int? {
        val s = t.trim()
        Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").find(s)?.let {
            val h = it.groupValues[1]
            return (h.takeLast(6).toLong(16).toInt()) or 0xFF000000.toInt()
        }
        return s.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..0xFFFFFFFFL }?.toInt()?.or(0xFF000000.toInt())
    }

    /** Duração do cache pedida pelo plugin, presa entre [CACHE_MIN_MS] e [CACHE_MAX_MS]. */
    fun cacheMs(pedido: Long?): Long = when {
        pedido == null || pedido <= 0L -> CACHE_PADRAO_MS
        else -> pedido.coerceIn(CACHE_MIN_MS, CACHE_MAX_MS)
    }

    /** Venceu? (relógio que voltou para trás também conta como vencido) */
    fun vencido(buscadoEm: Long, agora: Long, cacheMs: Long) = agora < buscadoEm || agora - buscadoEm >= cacheMs

    /** Próximo fundo a mostrar: outro que não o atual (sorteado por [sorteio] em 0 until n-1). -1 = lista vazia. */
    fun proximo(n: Int, atual: Int, sorteio: (Int) -> Int): Int = when {
        n <= 0 -> -1
        n == 1 -> 0
        else -> { val k = sorteio(n - 1).coerceIn(0, n - 2); if (atual in 0 until n && k >= atual) k + 1 else k }
    }

    /** "settingsActivity" do manifesto: ".Settings" vira "pacote.Settings". Null = vazio ou estranho. */
    fun classeConfig(pacote: String, valor: String?): String? {
        val v = valor?.trim().orEmpty()
        if (v.isEmpty() || v.length > 200 || !v.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }) return null
        return if (v.startsWith(".")) pacote + v else v
    }

    /** Crédito na tela: "Título · Autor" (o que houver), ou null. */
    fun credito(i: Item): String? = listOfNotNull(i.title, i.author?.let { "© $it" }).joinToString("  ·  ").takeIf { it.isNotEmpty() }

    /** O plugin quer este evento? */
    fun quer(updateMode: Int, evento: Int) = updateMode and evento != 0
}

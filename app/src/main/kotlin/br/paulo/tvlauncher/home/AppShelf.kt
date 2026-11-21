package br.paulo.tvlauncher.home

import android.content.pm.ApplicationInfo
import org.json.JSONObject

/**
 * As prateleiras da tela "Todos os apps" (proposta C, Paulo 20/11/2026: "classificação automática ... como a Play Store
 * classifica").
 *
 * Porquê: os 4 mundos da cruz (Assistir, Ouvir, Jogar, Aplicativos) são largos demais para uma tela que mostra TODOS os
 * apps, e "Aplicativos" ali dentro não diz nada (são todos aplicativos). Para quê: achar um app pelo que ele faz, com os
 * nomes que a pessoa já conhece da loja (Filmes e séries, Música e áudio, Esportes...). Como: o Android não informa a
 * categoria da Play (só a que o desenvolvedor declara, e poucas); ler a página da Play iria contra os termos dela. Então a
 * categoria vem, nesta ordem: 1. a escolha à mão ("Mover para…"); 2. jogo (a mesma regra da tela Jogos); 3. a ficha do
 * pacote (`cat` em fichas-apps.json, escrita no padrão da Play quando a ficha é pesquisada; chega também pela
 * comunidade); 4. as listas de conhecidos de [CategoriaApps]; 5. a categoria declarada ao Android; 6. sistema; 7. Outros.
 * Cada prateleira mora num mundo da cruz ([world]): o que a pessoa move aqui muda lá também, e vice-versa.
 * Pura (sem Android além das constantes): AppShelfTest.
 */
object AppShelf {
    /** O `cat` de cada ficha (APK + comunidade), lido uma vez fora da tela pela HOME. Null até ler. */
    @Volatile var sheetCats: Map<String, String>? = null

    /** Ordem da tela = ordem do enum. [id] é o que fica gravado (ficha `cat`, escolha manual): não mudar. */
    enum class Shelf(val id: String, val world: CruzLogica.Categoria) {
        ENTERTAINMENT("entertainment", CruzLogica.Categoria.ASSISTIR),
        VIDEO("video", CruzLogica.Categoria.ASSISTIR),
        SPORTS("sports", CruzLogica.Categoria.ASSISTIR),
        KIDS("kids", CruzLogica.Categoria.ASSISTIR),
        MUSIC_AUDIO("music_audio", CruzLogica.Categoria.OUVIR),
        NEWS("news", CruzLogica.Categoria.APLICATIVOS),
        GAMES("games", CruzLogica.Categoria.JOGAR),
        EDUCATION("education", CruzLogica.Categoria.APLICATIVOS),
        HEALTH_FITNESS("health_fitness", CruzLogica.Categoria.APLICATIVOS),
        LIFESTYLE("lifestyle", CruzLogica.Categoria.APLICATIVOS),
        TOOLS("tools", CruzLogica.Categoria.APLICATIVOS),
        SYSTEM("system", CruzLogica.Categoria.APLICATIVOS),
        OTHER("other", CruzLogica.Categoria.APLICATIVOS);

        companion object {
            fun of(id: String?): Shelf? = entries.firstOrNull { it.id == id }
        }
    }

    /** A prateleira-padrão de cada mundo: para onde vai um app movido de mundo pela cruz. */
    fun defaultOf(world: CruzLogica.Categoria): Shelf = when (world) {
        CruzLogica.Categoria.ASSISTIR -> Shelf.ENTERTAINMENT
        CruzLogica.Categoria.OUVIR -> Shelf.MUSIC_AUDIO
        CruzLogica.Categoria.JOGAR -> Shelf.GAMES
        else -> Shelf.OTHER
    }

    /** O que o desenvolvedor declarou ao Android (ApplicationInfo.category). -1 / desconhecida = null. */
    fun fromAndroid(category: Int): Shelf? = when (category) {
        ApplicationInfo.CATEGORY_GAME -> Shelf.GAMES
        ApplicationInfo.CATEGORY_AUDIO -> Shelf.MUSIC_AUDIO
        ApplicationInfo.CATEGORY_VIDEO -> Shelf.VIDEO
        ApplicationInfo.CATEGORY_NEWS -> Shelf.NEWS
        ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_ACCESSIBILITY -> Shelf.TOOLS
        ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_MAPS -> Shelf.LIFESTYLE
        else -> null
    }

    /**
     * A prateleira de um app. [manual] = id escolhido à mão (ou null); [sheet] = `cat` da ficha; [manualWorld] = o mundo
     * escolhido à mão na cruz ("Mover para…"), ou null: se a prateleira automática cai em OUTRO mundo, quem manda é a
     * escolha da pessoa, e o app vai para a prateleira-padrão daquele mundo.
     */
    fun decide(pkg: String, manual: String?, isGame: Boolean, sheet: String?, androidCategory: Int, system: Boolean,
               manualWorld: CruzLogica.Categoria?): Shelf {
        Shelf.of(manual)?.let { return it }
        if (isGame) return Shelf.GAMES
        val auto = Shelf.of(sheet)
            ?: when (pkg) { in CategoriaApps.VIDEO -> Shelf.ENTERTAINMENT; in CategoriaApps.AUDIO -> Shelf.MUSIC_AUDIO; else -> null }
            ?: fromAndroid(androidCategory)
            ?: if (system) Shelf.SYSTEM else Shelf.OTHER
        val w = manualWorld?.takeIf { it != CruzLogica.Categoria.CONTINUAR && it != CruzLogica.Categoria.JOGAR }
        return if (w != null && w != auto.world) defaultOf(w) else auto
    }

    /** Um app já classificado, com o peso de uso (aberturas com meia-vida, maior = mais usado). */
    data class Entry(val pkg: String, val name: String, val shelf: Shelf, val use: Double)

    /** Uma prateleira pronta para a tela. */
    data class Row(val shelf: Shelf, val pkgs: List<String>)

    /** Com menos que isto a tela inteira vira uma prateleira só (numa TV com 4 apps, 4 fileiras de 1 é escada, não ajuda). */
    const val MIN_TOTAL = 6

    /**
     * Monta as prateleiras: dentro de cada uma, o mais usado primeiro (empate: nome). Prateleira com UM app só junta em
     * Outros (fileira de um cartão vira escada), menos Sistema, que nunca se mistura. Poucos apps no total: uma fileira
     * só, sem título de categoria ([single] = true).
     */
    fun rows(entries: List<Entry>): Pair<List<Row>, Boolean> {
        val ordem = compareByDescending<Entry> { it.use }.thenBy { it.name.lowercase() }
        if (entries.size < MIN_TOTAL) return listOf(Row(Shelf.OTHER, entries.sortedWith(ordem).map { it.pkg })) to true
        val grupos = entries.groupBy { it.shelf }.toMutableMap()
        val soltos = grupos.filter { (s, l) -> l.size == 1 && s != Shelf.SYSTEM && s != Shelf.OTHER }.keys
        for (s in soltos) grupos[Shelf.OTHER] = (grupos[Shelf.OTHER] ?: emptyList()) + grupos.remove(s)!!
        return Shelf.entries.mapNotNull { s -> grupos[s]?.sortedWith(ordem)?.map { it.pkg }?.let { Row(s, it) } } to false
    }

    // ---------------------------------------------------------------- escolha à mão (prefs "cruz_apps", chave "shelves")

    fun fromJson(t: String?): Map<String, String> = runCatching {
        val o = JSONObject(t ?: "{}")
        o.keys().asSequence().filter { Shelf.of(o.optString(it)) != null }.associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun toJson(m: Map<String, String>): String = JSONObject(m as Map<*, *>).toString()

    /** Lê o `cat` de cada ficha (o JSON inteiro de fichas-apps.json, já juntado com o da comunidade). */
    fun sheetCategories(json: String?): Map<String, String> = runCatching {
        val o = JSONObject(json ?: "{}")
        val m = HashMap<String, String>()
        for (p in o.keys()) o.optJSONObject(p)?.optString("cat")?.takeIf { Shelf.of(it) != null }?.let { m[p] = it }
        m
    }.getOrDefault(emptyMap())
}

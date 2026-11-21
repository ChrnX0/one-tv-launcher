package br.paulo.tvlauncher.home

import android.content.Context
import android.content.pm.ApplicationInfo
import br.paulo.tvlauncher.games.Jogos
import br.paulo.tvlauncher.games.JogosTv
import org.json.JSONObject

/**
 * Em que categoria da cruz cada app mora (pedido do dono, 18/11/2026: "o YouTube eu quero poder mover para Assistir, o
 * Spotify para Ouvir"). Ordem de quem decide:
 *  1. jogo (JogosTv.ehJogo, que já respeita a escolha feita na tela Jogos) -> Jogar;
 *  2. a escolha manual ("Mover para…" no segurar OK) -> a categoria escolhida;
 *  3. automático: a categoria da ficha do pacote (AppShelf, 20/11/2026), o tipo que o app declara à loja (ApplicationInfo.category: vídeo, áudio) ou a lista de conhecidos
 *     abaixo (muitos apps de TV não declaram nada) -> Assistir/Ouvir;
 *  4. o resto -> Aplicativos.
 * Mover para Jogar ou tirar de Jogar passa pela escolha dos Jogos ([Jogos.escolher]): um lugar só decide o que é jogo.
 * A lógica é pura (teste na JVM); só [escolhas]/[escolher] tocam o disco.
 */
object CategoriaApps {
    /** Os destinos possíveis de um app (Continuar não recebe app). */
    val DESTINOS = listOf(CruzLogica.Categoria.ASSISTIR, CruzLogica.Categoria.OUVIR, CruzLogica.Categoria.JOGAR, CruzLogica.Categoria.APLICATIVOS)

    /**
     * Apps de vídeo e de música conhecidos que nem sempre declaram a categoria (pacotes de TV; não conferidos um a um
     * na loja: um pacote errado aqui só deixa de ter efeito).
     */
    val VIDEO = setOf(
        "com.google.android.youtube.tv", "com.google.android.youtube.tvkids", "com.google.android.youtube.tvunplugged",
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow",
        "com.globo.globotv", "com.apple.atve.androidtv.appletv", "com.cbs.ott", "com.paramountplus.android",
        "com.crunchyroll.crunchyroid", "tv.pluto.android", "com.plexapp.android", "org.jellyfin.androidtv", "org.xbmc.kodi",
        "com.stremio.one", "com.mubi", "com.google.android.videos", "com.google.android.apps.youtube.kids",
    )
    val AUDIO = setOf(
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "deezer.android.tv", "com.amazon.music.tv",
        "tunein.player", "com.soundcloud.android", "com.aspiro.tidal", "com.qobuz.music",
    )

    /** Categoria automática (sem escolha manual) de um app que não é jogo. */
    fun automatica(pacote: String, categoriaAndroid: Int, ficha: String? = null): CruzLogica.Categoria = when {
        AppShelf.Shelf.of(ficha) != null -> AppShelf.Shelf.of(ficha)!!.world.takeIf { it != CruzLogica.Categoria.JOGAR } ?: CruzLogica.Categoria.APLICATIVOS
        pacote in VIDEO -> CruzLogica.Categoria.ASSISTIR
        pacote in AUDIO -> CruzLogica.Categoria.OUVIR
        categoriaAndroid == ApplicationInfo.CATEGORY_VIDEO -> CruzLogica.Categoria.ASSISTIR
        categoriaAndroid == ApplicationInfo.CATEGORY_AUDIO -> CruzLogica.Categoria.OUVIR
        else -> CruzLogica.Categoria.APLICATIVOS
    }

    /** Onde o app aparece na cruz. [escolha] = o id da categoria escolhida à mão, ou null (automático). */
    fun destino(pacote: String, categoriaAndroid: Int, ehJogo: Boolean, escolha: String?, ficha: String? = null): CruzLogica.Categoria {
        if (ehJogo) return CruzLogica.Categoria.JOGAR
        val manual = escolha?.let { e -> DESTINOS.firstOrNull { it.id == e && it != CruzLogica.Categoria.JOGAR } }
        return manual ?: automatica(pacote, categoriaAndroid, ficha)
    }

    fun deJson(t: String?): Map<String, String> = runCatching {
        val o = JSONObject(t ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun paraJson(m: Map<String, String>): String = JSONObject(m as Map<*, *>).toString()

    // ---------------------------------------------------------------- disco (prefs "cruz_apps", chave "categorias")

    private const val PREFS = "cruz_apps"
    private const val CHAVE = "categorias"

    fun escolhas(ctx: Context): Map<String, String> =
        deJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE, null))

    /**
     * Move [pacote] para [destino]. Jogar (e sair de Jogar) muda a escolha dos Jogos; o resto fica aqui. Mover de volta
     * para onde o automático já poria apaga a escolha (o app volta a seguir o automático).
     */
    fun escolher(ctx: Context, pacote: String, destino: CruzLogica.Categoria, categoriaAndroid: Int, eraJogo: Boolean, ficha: String? = null) {
        val m = escolhas(ctx).toMutableMap()
        if (destino == CruzLogica.Categoria.JOGAR) {
            m.remove(pacote)
            JogosTv.escolher(ctx, pacote, Jogos.Grupo.TV.name)
        } else {
            if (eraJogo) JogosTv.escolher(ctx, pacote, Jogos.FORA)
            if (automatica(pacote, categoriaAndroid, ficha) == destino) m.remove(pacote) else m[pacote] = destino.id
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE, paraJson(m)).apply()
    }
}

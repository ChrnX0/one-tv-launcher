package br.paulo.tvlauncher.home

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Arte pronta dos conjuntos de ícones da cruz (21/11/2026: Vintage, Joia, Fotorrealista e Vintage 2.0).
 *
 * Por quê: esses conjuntos usam relevo, grão e madeira (filtros de SVG) que o Canvas do Android não tem; por isso vêm
 * desenhados em WebP (fonte em art/icon-sets, gerados por scripts/render-icon-sets.js), 360 x 300 cada.
 * Cada ícone tem duas camadas: `<tipo>.webp` (o que fica parado) e `<tipo>-a.webp` (a parte que se mexe), e
 * `motion.json` diz o movimento e o ponto de giro dessa parte. Para quê: o ícone escolhido ganha uma animação própria,
 * como os da Essencial (a antena procura o sinal, o rolo gira, o ponteiro sintoniza).
 * Como: lê do assets só na 1ª vez que um ícone aparece e guarda num cache por bytes (cabe uma tela de ícones; a TV é
 * 32 bits e tem pouca memória). Sem o arquivo (ou sem assets, num teste), devolve null e o ícone desenha o Objeto.
 */
object IconSetArt {

    /** O movimento da camada de cima: [m] = spin, swing, wiggle, nudge, bob, pulse ou twinkle; ponto de giro em fração do quadro. */
    class Motion(val m: String, val px: Float, val py: Float)

    /** Quem lê os arquivos: o do app (um teste pode trocar). */
    @Volatile var assets: AssetManager? = null
        get() = field ?: runCatching { br.paulo.tvlauncher.LauncherApp.instancia.assets }.getOrNull()

    // ~12 MB: as duas camadas de ~18 ícones de um conjunto (432 KB cada em ARGB_8888)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val faltando = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile private var movimentos: Map<String, Motion>? = null

    /** Nome do arquivo de cada tipo (o mesmo em todos os conjuntos). */
    fun arquivo(t: IconeCruz.Tipo): String = when (t) {
        IconeCruz.Tipo.CONTINUAR -> "continue"
        IconeCruz.Tipo.ASSISTIR -> "watch"
        IconeCruz.Tipo.TV -> "tv"
        IconeCruz.Tipo.OUVIR -> "listen"
        IconeCruz.Tipo.JOGAR -> "play"
        IconeCruz.Tipo.APLICATIVOS -> "apps"
        IconeCruz.Tipo.SERIES -> "series"
        IconeCruz.Tipo.FILMES -> "movies"
        IconeCruz.Tipo.REDE -> "network"
        IconeCruz.Tipo.ADDONS -> "addons"
        IconeCruz.Tipo.RADIO -> "radio"
        IconeCruz.Tipo.PODCASTS -> "podcasts"
        IconeCruz.Tipo.JOGOS -> "games"
        IconeCruz.Tipo.SOFA -> "sofa"
        IconeCruz.Tipo.TODOS_APPS -> "all-apps"
        IconeCruz.Tipo.PLUGIN -> "plugin"
        IconeCruz.Tipo.DORMIR -> "sleep"
        IconeCruz.Tipo.CENTRAL -> "hub"
    }

    /** A camada parada ([movel] = false) ou a que se mexe, ou null sem arte. */
    fun camada(pasta: String, t: IconeCruz.Tipo, movel: Boolean): Bitmap? {
        val chave = "icon-sets/$pasta/${arquivo(t)}${if (movel) "-a" else ""}.webp"
        cache.get(chave)?.let { return it }
        if (chave in faltando) return null
        val am = assets ?: return null
        val bmp = runCatching { am.open(chave).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (bmp == null) { faltando += chave; return null }
        cache.put(chave, bmp)
        return bmp
    }

    fun movimento(pasta: String, t: IconeCruz.Tipo): Motion? {
        val mapa = movimentos ?: carregarMovimentos()?.also { movimentos = it } ?: return null
        return mapa["$pasta/${arquivo(t)}"]
    }

    private fun carregarMovimentos(): Map<String, Motion>? {
        val am = assets ?: return null
        val texto = runCatching { am.open("icon-sets/motion.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        return lerMovimentos(texto)
    }

    /** O motion.json: {"vintage/tv": {"m": "swing", "px": 0.5, "py": 0.3}, ...}. Separado para o teste puro. */
    fun lerMovimentos(json: String): Map<String, Motion> {
        val o = org.json.JSONObject(json)
        val r = HashMap<String, Motion>()
        for (k in o.keys()) {
            val v = o.getJSONObject(k)
            r[k] = Motion(v.getString("m"), v.getDouble("px").toFloat(), v.getDouble("py").toFloat())
        }
        return r
    }

    /** Pouca memória: solta tudo (volta do disco no próximo desenho). */
    fun soltar() = cache.evictAll()
}

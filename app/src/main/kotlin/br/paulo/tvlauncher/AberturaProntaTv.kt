package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * A parte de rede da abertura pronta (elevação 6.3; regras e termos em AberturaPronta.kt). Um pedido por episódio
 * aberto, com a resposta guardada nas prefs "player" (processo :player). Fora da thread da tela.
 */
object AberturaProntaTv {
    private const val TAG = "TvLauncher.Abertura"

    fun buscar(ctx: Context, tmdbSerie: Int, temporada: Int, episodio: Int): AberturaSerie.Marca? {
        val imdb = InfoExternaCache.imdbPorTmdb(ctx, tmdbSerie) ?: return null   // sem o id: nada a perguntar
        val url = AberturaPronta.url(imdb, temporada, episodio) ?: return null
        val prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        val k = "abertura_pronta:$imdb:$temporada:$episodio"
        val agora = System.currentTimeMillis()
        AberturaPronta.doCache(prefs.getString(k, null), agora)?.let { return it.first }
        val resposta = Endereco.httpGet(url) ?: return null   // sem rede: tenta na próxima vez (nada guardado)
        val m = AberturaPronta.ler(resposta)
        prefs.edit().putString(k, AberturaPronta.paraCache(m, agora)).apply()
        Log.i(TAG, "IntroDB $imdb s${temporada}e$episodio: ${m ?: "sem marcação"}")
        return m
    }
}

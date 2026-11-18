package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * A parte de rede da abertura pronta (elevação 6.3; regras e termos em AberturaPronta.kt). Um pedido por episódio
 * aberto, com a resposta guardada nas prefs "player" (processo :player). Fora da thread da tela.
 */
object AberturaProntaTv {
    private const val TAG = "TvLauncher.Abertura"
    private const val PREFS = "abertura_pronta"   // só cache; fora do backup (não está em BackupCompleto.PREFS_EXTRAS)
    private const val MAX_CACHE = 500
    @Volatile private var limpou = false

    /** Uma vez por processo: tira as chaves velhas "abertura_pronta:*" das prefs "player". */
    private fun limparAntigas(ctx: Context) {
        if (limpou) return
        limpou = true
        val p = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        val velhas = p.all.keys.filter { it.startsWith("abertura_pronta:") }
        if (velhas.isNotEmpty()) p.edit().apply { velhas.forEach { remove(it) } }.apply()
    }

    fun buscar(ctx: Context, tmdbSerie: Int, temporada: Int, episodio: Int): AberturaSerie.Marca? {
        val imdb = InfoExternaCache.imdbPorTmdb(ctx, tmdbSerie) ?: return null   // sem o id: nada a perguntar
        val url = AberturaPronta.url(imdb, temporada, episodio) ?: return null
        // Chave nas Configurações (Sistema > Mais opções), ligada de fábrica: desligada, nada vai ao IntroDB.
        if (!Extras.ligadoAgora(ctx, Extras.Chave.ABERTURA_PRONTA)) return null
        // Modo visita: o que a visita assiste não sai da TV nem para o IntroDB.
        if (runCatching { ModoVisita.ativo(ctx) }.getOrDefault(false)) return null
        // Prefs próprias (rodada 15): nas "player" cresciam uma chave por episódio para sempre, na memória de cada processo
        // e no backup completo. Cache: passou de [MAX_CACHE], começa de novo (perguntar de novo é barato).
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.all.size > MAX_CACHE) prefs.edit().clear().apply()
        limparAntigas(ctx)
        val k = "$imdb:$temporada:$episodio"
        val agora = System.currentTimeMillis()
        AberturaPronta.doCache(prefs.getString(k, null), agora)?.let { return it.first }
        val resposta = Endereco.httpGet(url) ?: return null   // sem rede: tenta na próxima vez (nada guardado)
        val m = AberturaPronta.ler(resposta)
        prefs.edit().putString(k, AberturaPronta.paraCache(m, agora)).apply()
        Log.i(TAG, "IntroDB $imdb s${temporada}e$episodio: ${m ?: "sem marcação"}")
        return m
    }
}

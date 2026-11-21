package br.paulo.tvlauncher.player

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.settings.Extras

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
        val p = ctx.getSharedPreferences(Prefs.PLAYER, Context.MODE_PRIVATE)
        val velhas = p.all.keys.filter { it.startsWith("abertura_pronta:") }
        if (velhas.isNotEmpty()) p.edit().apply { velhas.forEach { remove(it) } }.apply()
    }

    /**
     * Abertura, resumo e créditos do episódio (EpisodeSegments). Guarda a resposta crua do IntroDB (cerca de 400 bytes):
     * desde 20/11/2026 ela serve para os três trechos, não só para a abertura. "-" = não tem marcação (pergunta de novo
     * depois de [AberturaPronta.REPERGUNTAR_MS]).
     */
    fun buscar(ctx: Context, tmdbSerie: Int, temporada: Int, episodio: Int): EpisodeSegments.Trechos? {
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
        val k = "v2:$imdb:$temporada:$episodio"
        val agora = System.currentTimeMillis()
        prefs.getString(k, null)?.let { guardado ->
            val em = guardado.substringBefore('|').toLongOrNull()
            val corpo = guardado.substringAfter('|', "")
            if (em != null && corpo.isNotEmpty() && (corpo != "-" || agora - em < AberturaPronta.REPERGUNTAR_MS))
                return if (corpo == "-") null else EpisodeSegments.lerIntroDb(corpo)
        }
        val resposta = Http.getTextOrNull(url) ?: return null   // sem rede: tenta na próxima vez (nada guardado)
        val t = EpisodeSegments.lerIntroDb(resposta)
        val vazio = t == null || (t.abertura == null && t.resumo == null && t.creditos == null)
        prefs.edit().putString(k, "$agora|" + if (vazio) "-" else resposta.replace('\n', ' ')).apply()
        Log.i(TAG, "IntroDB $imdb s${temporada}e$episodio: ${t ?: "sem marcação"}")
        return t?.takeUnless { vazio }
    }
}

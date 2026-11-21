package br.paulo.tvlauncher.home

import android.content.Context
import android.media.tv.TvContract
import br.paulo.tvlauncher.player.Programa
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Continuar assistindo" dos outros apps pelo Watch Next do Android (degrau 1 contra o Projectivy, 15/11/2026).
 *
 * Estado: SÓ o leitor e o diagnóstico (EXTRAS --es o watchnext). A fileira na HOME (desligada de fábrica, decisão do
 * Paulo) espera um aparelho mostrar dados: na Sala (Google TV) a tabela vem vazia, porque no Google TV os apps
 * publicam pelo Engage SDK, que só o launcher do Google lê (medido em 10/11/2026). Em Android TV puro (Shield, Mi
 * Stick) a tabela deve ter dados; se o Android só devolver as linhas do próprio app (sem ACCESS_ALL_EPG_DATA, que é
 * de sistema), a fileira não tem como existir e o item sai do plano. Medir antes de desenhar.
 */
object WatchNextTv {
    class Programa(val pacote: String, val titulo: String, val tipo: Int, val posicaoMs: Long, val duracaoMs: Long, val imagem: String?)

    fun ler(ctx: Context, max: Int = 20): Result<List<Programa>> = runCatching {
        val l = ArrayList<Programa>()
        ctx.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
            fun s(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
            fun n(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
            while (c.moveToNext() && l.size < max) {
                l += Programa(s(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME) ?: "?", s(TvContract.WatchNextPrograms.COLUMN_TITLE) ?: "",
                    n(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE).toInt(),
                    n(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS), n(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS),
                    s(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI))
            }
        }
        l
    }

    /** Canais de prévia (as fileiras que cada app publica): pacote e nome. Mesma regra de permissão do Watch Next. */
    fun canais(ctx: Context, max: Int = 50): Result<List<Pair<String, String>>> = runCatching {
        val l = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf(TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext() && l.size < max) l += (c.getString(0) ?: "?") to (c.getString(1) ?: "")
        }
        l
    }

    /** Programas dos canais de prévia (os cartões de cada fileira): pacote, título e se tem imagem. Só para medir. */
    fun programas(ctx: Context, max: Int = 200): Result<List<Triple<String, String, Boolean>>> = runCatching {
        val l = ArrayList<Triple<String, String, Boolean>>()
        ctx.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, arrayOf(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI), null, null, null)?.use { c ->
            while (c.moveToNext() && l.size < max) l += Triple(c.getString(0) ?: "?", c.getString(1) ?: "", !c.getString(2).isNullOrEmpty())
        }
        l
    }

    /**
     * Para o adb e o relatório de compatibilidade: quantos, de quais apps, ou o erro (ex.: SecurityException).
     * 21/11/2026: também os canais e se a READ_TV_LISTINGS (Android 11+) está concedida.
     */
    fun diagnostico(ctx: Context): JSONObject = ler(ctx).fold(
        { l -> JSONObject().put("ok", true).put("quantos", l.size).put("apps", JSONArray(l.map { it.pacote }.distinct()))
            .put("titulos", JSONArray(l.take(5).map { it.titulo })) },
        { e -> JSONObject().put("ok", false).put("erro", e.javaClass.simpleName + ": " + (e.message ?: "")) })
        .put("read_tv_listings", ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
        .put("programas", programas(ctx).fold({ p -> JSONObject().put("quantos", p.size).put("com_imagem", p.count { it.third })
            .put("por_app", JSONObject(p.groupingBy { it.first }.eachCount())).put("exemplos", JSONArray(p.take(6).map { "${it.first}: ${it.second}" })) },
            { e -> e.javaClass.simpleName + ": " + (e.message ?: "") }))
        .put("canais", canais(ctx).fold({ c -> JSONArray(c.map { "${it.first}: ${it.second}" }) }, { e -> e.javaClass.simpleName + ": " + (e.message ?: "") }))
}

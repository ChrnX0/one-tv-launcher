package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Gatilhos
import br.paulo.tvlauncher.player.PlayerCarga
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * O que os OUTROS apps de mídia tocaram por último, para o "Continuar" da cruz (pedido do dono, 18/11/2026: "continuar
 * do YouTube, YouTube Music, Spotify, Netflix, HBO, Prime").
 *
 * O caminho oficial (Watch Next) vem vazio no Google TV (medido na Sala em 10/11/2026: lá os apps publicam pelo Engage
 * SDK, que só o launcher do Google lê). Então a fonte é a sessão de mídia que o app abre ao tocar (a mesma da pílula
 * "Tocando agora", lida com o acesso às notificações): o título, quem canta / o episódio, a capa, a posição e a
 * duração que o APP decidir mandar. Guarda o último de cada app ([MAX] apps), só na TV (noBackupFilesDir: é histórico de
 * quem assiste; não vai no backup nem sai da TV).
 *
 * Limite honesto: o que aparece depende do app. Sem título (ou título = nome do app), não entra.
 */
object MidiaRecente {
    private const val TAG = "TvLauncher.MidiaRecente"
    const val MAX = 6
    /** Some do Continuar depois de 30 dias sem tocar. */
    const val VALIDADE_MS = 30L * 24 * 3600 * 1000
    /** Lado maior da capa guardada (a cruz mostra o ícone a ~112 dp e a arte de fundo borrada). */
    const val CAPA_PX = 320

    data class Registro(val pacote: String, val titulo: String, val sub: String, val posMs: Long, val durMs: Long, val quando: Long)

    // ---------------------------------------------------------------- puro

    /** [r] entra no topo e tira o registro anterior do mesmo app; fica no máximo [max]. */
    fun juntar(lista: List<Registro>, r: Registro, max: Int = MAX): List<Registro> =
        (listOf(r) + lista.filter { it.pacote != r.pacote }).take(max)

    /** Título que vale guardar: não vazio e não é só o nome do app (alguns apps mandam isso quando não há nada). */
    fun tituloUtil(titulo: String?, nomeApp: String): Boolean =
        !titulo.isNullOrBlank() && !titulo.trim().equals(nomeApp.trim(), ignoreCase = true)

    /** Os que valem agora, do mais novo para o mais velho. */
    fun validos(lista: List<Registro>, agora: Long): List<Registro> =
        lista.filter { agora - it.quando in 0..VALIDADE_MS }.sortedByDescending { it.quando }

    /** Fração já vista (0..1), ou null quando o app não manda duração (rádio ao vivo, alguns apps de vídeo). */
    fun progresso(r: Registro): Float? = if (r.durMs > 0 && r.posMs > 0) (r.posMs.toFloat() / r.durMs).coerceIn(0f, 1f) else null

    fun paraJson(l: List<Registro>): String = JSONArray().apply {
        for (r in l) put(JSONObject().put("p", r.pacote).put("t", r.titulo).put("s", r.sub).put("pos", r.posMs).put("dur", r.durMs).put("q", r.quando))
    }.toString()

    fun deJson(t: String?): List<Registro> = runCatching {
        val a = JSONArray(t ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optString("p"); val ti = o.optString("t")
            if (p.isEmpty() || ti.isEmpty()) null
            else Registro(p, ti, o.optString("s"), o.optLong("pos"), o.optLong("dur"), o.optLong("q"))
        }
    }.getOrDefault(emptyList())

    /** Posição de agora a partir do estado (o app só atualiza a posição de vez em quando; o resto é o tempo que passou). */
    fun posicaoAgora(posicao: Long, atualizadaEm: Long, velocidade: Float, tocando: Boolean, agoraElapsed: Long): Long =
        if (!tocando || atualizadaEm <= 0) posicao.coerceAtLeast(0)
        else (posicao + ((agoraElapsed - atualizadaEm) * velocidade).toLong()).coerceAtLeast(0)

    // ---------------------------------------------------------------- disco

    private fun pasta(ctx: Context) = File(ctx.noBackupFilesDir, "midia_recente").apply { mkdirs() }
    private fun arquivo(ctx: Context) = File(pasta(ctx), "recentes.json")
    private fun arqCapa(ctx: Context, pacote: String) = File(pasta(ctx), "$pacote.jpg")
    private val trava = Any()

    fun ler(ctx: Context): List<Registro> = synchronized(trava) { deJson(runCatching { arquivo(ctx).readText() }.getOrNull()) }

    fun capa(ctx: Context, pacote: String): Bitmap? = runCatching {
        arqCapa(ctx, pacote).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }.getOrNull()

    /** Esquece um app (desinstalado ou o dono tirou do Continuar). */
    fun esquecer(ctx: Context, pacote: String) = synchronized(trava) {
        PlayerCarga.gravarAtomico(arquivo(ctx), paraJson(ler(ctx).filter { it.pacote != pacote }))
        arqCapa(ctx, pacote).delete()
    }

    // ---------------------------------------------------------------- sessão de mídia -> registro

    /** Último título guardado por app nesta execução: só regrava a capa quando o título muda. */
    private val tituloDaCapa = HashMap<String, String>()
    private val ultimaEscrita = HashMap<String, Pair<String, Long>>()

    /**
     * Anota a sessão [c] (chamado pelos callbacks em [Gatilhos], na thread principal; o disco vai para o [Fundo]).
     * Só tocando ou pausado (parado/erro não conta), e só com título útil.
     */
    fun anotar(ctx: Context, c: MediaController) {
        val app = ctx.applicationContext
        val st = c.playbackState ?: return
        val tocando = st.state == PlaybackState.STATE_PLAYING
        if (!tocando && st.state != PlaybackState.STATE_PAUSED) return
        val md = c.metadata ?: return
        val pacote = c.packageName ?: return
        if (pacote == app.packageName) return
        val titulo = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (titulo.isNullOrBlank()) return
        // Mesmo título, mesmo estado, há menos de 30 s: nada novo para o disco (o app pode mandar a posição toda hora).
        // Conferido ANTES do nome do app (chamada ao sistema) e da posição: é o caminho que roda quase sempre.
        val chaveEscrita = titulo.trim() + "|" + tocando
        val ultima = ultimaEscrita[pacote]
        if (ultima != null && ultima.first == chaveEscrita && SystemClock.elapsedRealtime() - ultima.second < 30_000) return
        val nomeApp = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pacote, 0)).toString() }.getOrDefault("")
        if (!tituloUtil(titulo, nomeApp)) return
        val sub = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM)
            .firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.isNotBlank() && it != titulo } } ?: ""
        val pos = posicaoAgora(st.position, st.lastPositionUpdateTime, st.playbackSpeed.takeIf { it > 0f } ?: 1f, tocando, SystemClock.elapsedRealtime())
        val r = Registro(pacote, titulo.trim(), sub.trim(), pos, md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0), System.currentTimeMillis())
        // A capa só é copiada quando o título muda (a mesma música/episódio não regrava a cada pausa). Título novo sem
        // capa apaga a velha (senão o episódio novo apareceria com a arte do anterior).
        ultimaEscrita[pacote] = chaveEscrita to SystemClock.elapsedRealtime()
        val mudou = tituloDaCapa[pacote] != r.titulo
        tituloDaCapa[pacote] = r.titulo
        val capa = if (mudou) listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { k -> md.getBitmap(k) }?.let { reduzir(it) } else null
        Fundo.rodar("midia-recente") {
            runCatching {
                synchronized(trava) {
                    if (mudou) {
                        // Grava ao lado e troca de uma vez: a cruz pode ler a capa enquanto ela é escrita (JPEG pela metade).
                        val destino = arqCapa(app, pacote)
                        if (capa != null) {
                            val tmp = File(destino.path + ".tmp")
                            tmp.outputStream().use { capa.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            if (!tmp.renameTo(destino)) { tmp.delete(); destino.delete() }
                        } else destino.delete()
                    }
                    PlayerCarga.gravarAtomico(arquivo(app), paraJson(juntar(ler(app), r)))
                }
            }.onFailure { Log.w(TAG, "não gravou: ${it.javaClass.simpleName}") }
        }
    }

    /** Cópia nossa, pequena: o bitmap da sessão é do outro app e pode ser enorme. */
    private fun reduzir(b: Bitmap): Bitmap? = runCatching {
        val maior = maxOf(b.width, b.height)
        if (maior <= CAPA_PX) b.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(b, b.width * CAPA_PX / maior, b.height * CAPA_PX / maior, true)
    }.getOrNull()
}

package br.paulo.tvlauncher.games

import android.content.Context
import br.paulo.tvlauncher.Http
import java.net.HttpURLConnection
import java.net.URL

/**
 * A parte Android de "Sua turma" (regra pura em [TurmaDiscord]): guarda o ID do servidor e o webhook nas prefs "jogos"
 * (as mesmas da tela Jogos, que já vão no backup cifrado), busca o widget e manda o chamado.
 *
 * Nada aqui roda sozinho: só a tela Jogos aberta busca a turma (no máximo uma vez por minuto; o próprio Discord só
 * atualiza o widget de tempos em tempos). Sem servidor configurado, a TV nunca fala com o Discord.
 */
object TurmaDiscordTv {
    private const val PREFS = "jogos"
    private const val CHAVE_ID = "turma_id"
    private const val CHAVE_WEBHOOK = "turma_webhook"
    private const val INTERVALO_MS = 60_000L
    private const val ESPERA_CHAMADO_MS = 60_000L   // um chamado por minuto: o botão não vira spam no canal

    /** O que a tela mostra: a turma, o widget desligado, sem servidor configurado ou sem conseguir falar com o Discord. */
    sealed class Estado {
        object SemServidor : Estado()
        object Desligado : Estado()
        object SemRede : Estado()
        class Pronta(val turma: TurmaDiscord.Turma) : Estado()
    }

    @Volatile private var ultimo: Pair<Long, Estado>? = null   // (quando, o quê) da última busca, para voltar à tela sem esperar
    @Volatile private var ultimoChamado = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun servidor(ctx: Context): String = prefs(ctx).getString(CHAVE_ID, "") ?: ""
    fun temWebhook(ctx: Context): Boolean = (prefs(ctx).getString(CHAVE_WEBHOOK, "") ?: "").isNotEmpty()

    /** Guarda o ID do servidor ("" apaga). false = não é um ID do Discord. */
    fun guardarServidor(ctx: Context, id: String): Boolean {
        val t = id.trim()
        if (t.isNotEmpty() && !TurmaDiscord.idValido(t)) return false
        prefs(ctx).edit().putString(CHAVE_ID, t).apply()
        ultimo = null
        return true
    }

    /** Guarda o webhook do chamado ("" apaga). false = não é um webhook de canal do Discord. */
    fun guardarWebhook(ctx: Context, url: String): Boolean {
        val t = url.trim()
        if (t.isNotEmpty() && !TurmaDiscord.webhookValido(t)) return false
        prefs(ctx).edit().putString(CHAVE_WEBHOOK, t).apply()
        return true
    }

    /** O último estado conhecido, sem rede (para a tela abrir já desenhada). null = nunca buscou. */
    fun guardado(ctx: Context): Estado? = if (servidor(ctx).isEmpty()) Estado.SemServidor else ultimo?.second

    /** Busca a turma. Fora da thread da tela. Repete o último resultado se ele tem menos de um minuto. */
    fun buscar(ctx: Context): Estado {
        val id = servidor(ctx)
        if (id.isEmpty()) return Estado.SemServidor
        ultimo?.let { (quando, e) -> if (System.currentTimeMillis() - quando < INTERVALO_MS) return e }
        val e = try {
            val c = (URL(TurmaDiscord.urlWidget(id)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000
                setRequestProperty("User-Agent", Http.USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                // 403 com código 50004 = widget desligado; o corpo de erro também é JSON.
                val corpo = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                    ?.use { e -> lerAte(e, 512 * 1024) } ?: ""   // teto na leitura (antes lia tudo e cortava depois)
                Estado.Pronta(TurmaDiscord.ler(corpo))
            } finally { c.disconnect() }
        } catch (_: TurmaDiscord.WidgetDesligado) {
            Estado.Desligado
        } catch (_: Exception) {
            // Sem rede, servidor que não existe, resposta estranha: a tela mostra "sem conexão" e tenta no próximo minuto.
            Estado.SemRede
        }
        ultimo = System.currentTimeMillis() to e
        return e
    }

    /** Para o teste de tela (JogosTurmaTelaTest): a turma como se tivesse acabado de chegar do Discord. */
    @androidx.annotation.VisibleForTesting
    internal fun lembrar(e: Estado) { ultimo = System.currentTimeMillis() to e }

    enum class Chamado { ENVIADO, ESPERE, SEM_WEBHOOK, FALHOU }

    /** Manda [texto] no canal do webhook, com o nome da TV. Fora da thread da tela. */
    fun chamar(ctx: Context, texto: String): Chamado {
        val url = prefs(ctx).getString(CHAVE_WEBHOOK, "") ?: ""
        if (url.isEmpty() || !TurmaDiscord.webhookValido(url)) return Chamado.SEM_WEBHOOK
        val agora = System.currentTimeMillis()
        if (agora - ultimoChamado < ESPERA_CHAMADO_MS) return Chamado.ESPERE
        val nomeTv = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull() ?: ""
        return try {
            val c = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 6000; readTimeout = 8000
                instanceFollowRedirects = false   // o POST vai só ao endereço validado
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", Http.USER_AGENT)
            }
            try {
                c.outputStream.use { it.write(TurmaDiscord.corpoChamado(texto, nomeTv).toByteArray()) }
                if (c.responseCode in 200..299) { ultimoChamado = agora; Chamado.ENVIADO } else Chamado.FALHOU
            } finally { c.disconnect() }
        } catch (_: Exception) { Chamado.FALHOU }
    }

    /** Lê no máximo [teto] bytes (readNBytes é do Android 13; o mínimo aqui é o 10). */
    private fun lerAte(e: java.io.InputStream, teto: Int): String {
        val b = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (b.size() < teto) { val n = e.read(buf, 0, minOf(buf.size, teto - b.size())); if (n < 0) break; b.write(buf, 0, n) }
        return b.toString("UTF-8")
    }
}

package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.privilege.Debloat
import java.io.File
import org.json.JSONObject

/**
 * Ideia 70: baixa, uma vez por semana, `comunidade/fichas-apps.json` e `comunidade/limpeza.json` da main do
 * repositório do projeto ([Config.ATUALIZACAO_REPO]) por HTTPS (TLS normal) e guarda em `files/comunidade/`. Sem
 * rede ou com arquivo inválido, fica o que já havia (e, na falta, só o que vem no APK). Regras em [FichasComunidade].
 */
object ComunidadeApps {
    private const val TAG = "TvLauncher.Comunidade"
    private const val PREFS = "comunidade_apps"

    private fun pasta(ctx: Context) = File(ctx.filesDir, "comunidade")
    private fun url(arq: String) = "https://raw.githubusercontent.com/${Config.ATUALIZACAO_REPO}/main/comunidade/$arq"

    @Volatile private var limpezaCache: List<Debloat.Item>? = null

    /** As fichas de fora já conferidas (vazio se não houver). Lê do disco; chamar fora da thread da tela. */
    fun fichas(ctx: Context): JSONObject =
        runCatching { File(pasta(ctx), "fichas-apps.json").readText() }.getOrNull()?.let { FichasComunidade.validarFichas(it) } ?: JSONObject()

    /** O JSON das fichas do APK junto com o de fora. */
    fun fichasJuntas(ctx: Context): String {
        val apk = ctx.assets.open("fichas-apps.json").use { String(it.readBytes(), Charsets.UTF_8) }
        val fora = fichas(ctx)
        return if (fora.length() == 0) apk else FichasComunidade.juntar(JSONObject(apk), fora).toString()
    }

    fun limpeza(ctx: Context): List<Debloat.Item> = limpezaCache ?: runCatching {
        File(pasta(ctx), "limpeza.json").readText()
    }.getOrNull().let { j ->
        if (j == null) emptyList() else FichasComunidade.validarLimpeza(j, Debloat.NUNCA, Debloat.LISTA.map { it.pacote }.toSet())
    }.also { limpezaCache = it }

    /** Busca se já passou uma semana. Não espera: roda numa thread. */
    fun talvezAtualizar(ctx: Context) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        if (!FichasComunidade.deveBuscar(p.getLong("ultima", 0L), agora)) return
        p.edit().putLong("ultima", agora).apply()
        Fundo.rodar("comunidade") {
            pasta(app).mkdirs()
            baixar("fichas-apps.json")?.takeIf { FichasComunidade.validarFichas(it) != null }?.let { gravar(app, "fichas-apps.json", it) }
            baixar("limpeza.json")?.takeIf { runCatching { org.json.JSONArray(it) }.isSuccess }?.let { gravar(app, "limpeza.json", it); limpezaCache = null }
        }
    }

    private fun gravar(ctx: Context, nome: String, texto: String) {
        val tmp = File(pasta(ctx), "$nome.tmp")
        tmp.writeText(texto); tmp.renameTo(File(pasta(ctx), nome))
    }

    /** O arquivo da comunidade, ou null (sem rede, outro código, ou maior que FichasComunidade.MAX_BYTES). */
    private fun baixar(arq: String): String? = try {
        Http.getText(url(arq), connectMs = 15_000, readMs = 30_000, maxBytes = FichasComunidade.MAX_BYTES, followRedirects = false)
    } catch (e: Exception) { Log.w(TAG, "$arq: ${e.javaClass.simpleName}"); null }
}

package br.paulo.tvlauncher.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.privilege.Congelar
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Pacote de ícones CONGELADO (pedido do Paulo, 18/11/2026: "ao instalar o pacote de ícone, ele seja congelado, não fique
 * na memória ... só quando for trocar o ícone a gente dá uma descongelada").
 *
 * Como funciona:
 *  1. Os ícones que a HOME usa são copiados para dentro do launcher (PNG em filesDir/icon-cache/<pacote>/), na hora em
 *     que são lidos pela 1ª vez. Daí em diante a HOME desenha da cópia e não precisa mais do pacote.
 *  2. Depois de escolher um pacote (ou um ícone dele), [congelarDepois] lê os ícones de todos os apps da TV (para a cópia
 *     ficar completa) e congela o pacote pelo shell próprio (`pm disable-user`, [Congelar]): some da lista de apps e não
 *     roda. Os dados ficam e `pm enable` devolve igual.
 *  3. Para escolher outro ícone, [recursos] tenta ler o pacote congelado mesmo (um app desativado continua com o APK no
 *     aparelho). Se o Android não deixar, descongela ([abrir]) e quem abriu chama [congelarDepois] ao terminar.
 * Sem shell (build da Play, ou shell fora do ar): não congela; a cópia vale do mesmo jeito.
 * Não verificado na TV: se um pacote desativado deixa ler os recursos (por isso o plano B de descongelar).
 */
object IconPackFreezer {
    private const val TAG = "TvLauncher.Icones"
    private const val PREFS = "icon_pack_frozen"
    private const val LADO = 192   // px da cópia: o maior ícone da cruz (112 dp) numa TV xhdpi cabe com folga
    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "IconPackFreezer").apply { isDaemon = true } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pacotes que NÓS congelamos (pacote -> nome): a lista de pacotes não os acha mais pela busca normal. */
    fun congelados(ctx: Context): List<Pair<String, String>> = prefs(ctx).all.mapNotNull { (k, v) ->
        if (!instalado(ctx, k)) null else k to (v as? String ?: k)
    }

    private fun instalado(ctx: Context, p: String) =
        runCatching { ctx.packageManager.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS); true }.getOrDefault(false)

    /** Os recursos do pacote, mesmo desativado; null se não deu. */
    fun recursos(ctx: Context, pacote: String): Resources? = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pacote, PackageManager.MATCH_DISABLED_COMPONENTS)
        ctx.packageManager.getResourcesForApplication(ai)
    }.getOrNull()

    // ---------------------------------------------------------------- cópia dos ícones

    private fun arquivo(ctx: Context, pacote: String, nome: String) = File(File(ctx.filesDir, "icon-cache/$pacote"), "$nome.png")

    /** A cópia guardada do ícone [nome] do pacote, ou null. */
    fun copia(ctx: Context, pacote: String, nome: String): Drawable? {
        val f = arquivo(ctx, pacote, nome)
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(ctx.resources, it) }
    }

    /** Guarda a cópia (PNG [LADO] x [LADO]). Silencioso se falhar: na próxima vez lê do pacote de novo. */
    fun guardar(ctx: Context, pacote: String, nome: String, d: Drawable) {
        runCatching {
            val bmp = Bitmap.createBitmap(LADO, LADO, Bitmap.Config.ARGB_8888)
            d.mutate().setBounds(0, 0, LADO, LADO); d.draw(Canvas(bmp))
            val f = arquivo(ctx, pacote, nome).apply { parentFile?.mkdirs() }
            val tmp = File(f.path + ".part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            tmp.renameTo(f)
        }
    }

    /** O appfilter já lido, guardado em JSON: a HOME acha o ícone de cada app mesmo com o pacote congelado. */
    fun guardarMapa(ctx: Context, pacote: String, m: PacoteIconesRegras.Mapa) {
        runCatching {
            val f = File(File(ctx.filesDir, "icon-cache/$pacote"), "appfilter.json").apply { parentFile?.mkdirs() }
            f.writeText(JSONObject().put("c", JSONObject(m.porComponente as Map<*, *>)).put("p", JSONObject(m.porPacote as Map<*, *>)).toString())
        }
    }

    fun mapaGuardado(ctx: Context, pacote: String): PacoteIconesRegras.Mapa? = runCatching {
        val j = JSONObject(File(File(ctx.filesDir, "icon-cache/$pacote"), "appfilter.json").readText())
        fun mapa(o: JSONObject) = o.keys().asSequence().associateWith { o.getString(it) }
        PacoteIconesRegras.Mapa(mapa(j.getJSONObject("c")), mapa(j.getJSONObject("p")))
    }.getOrNull()

    // ---------------------------------------------------------------- congelar e descongelar

    /** Pode congelar aqui? Precisa do shell próprio (não existe no build da Play). */
    private fun podeCongelar() = !BuildConfig.PLAY

    /**
     * Descongela [pacote] para ler (só se fomos nós que congelamos e o pacote não deixa ler congelado). BLOQUEIA até
     * ~20 s: fora da thread da tela. Quem chama deve chamar [congelarDepois] quando terminar.
     */
    fun abrir(ctx: Context, pacote: String): Boolean {
        if (pacote !in prefs(ctx).all.keys || !Congelar.congelado(ctx, pacote)) return true
        val ok = Congelar.definir(ctx, pacote, false)
        Log.i(TAG, "descongelar $pacote para ler: $ok")
        return ok
    }

    /**
     * Completa a cópia dos ícones de [pacote] (todos os apps da TV que ele cobre, mais os trocados à mão) e o congela.
     * Roda numa fila própria, uma coisa por vez.
     */
    fun congelarDepois(ctx: Context, pacote: String) {
        if (pacote.isEmpty() || pacote.startsWith(PacoteIcones.EMBUTIDO) || pacote == ctx.packageName) return
        val app = ctx.applicationContext
        fila.execute {
            try {
                val mapa = PacoteIcones.mapaDeQualquer(app, pacote)
                if (mapa != null) {
                    val pm = app.packageManager
                    val apps = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
                    for (ri in apps) mapa.drawable(ri.activityInfo.packageName, ri.activityInfo.name)?.let { PacoteIcones.desenho(app, pacote, it) }
                }
                // Ícones trocados à mão que vêm deste pacote também.
                app.getSharedPreferences("custom_icons", Context.MODE_PRIVATE).all.values.forEach { v ->
                    CustomIconsRules.ler(v as? String)?.takeIf { it.first == pacote }?.let { PacoteIcones.desenho(app, pacote, it.second) }
                }
                val nome = PacoteIcones.nome(app, pacote)
                if (!podeCongelar()) return@execute
                if (Congelar.definir(app, pacote, true)) prefs(app).edit().putString(pacote, nome).apply()
                Log.i(TAG, "pacote $pacote congelado: ${Congelar.congelado(app, pacote)}")
            } catch (e: Exception) {
                Log.w(TAG, "congelar $pacote: $e")
            }
        }
    }

    /** Descongela de vez (a pessoa pediu para usar o app do pacote, ou desinstalar). */
    fun soltar(ctx: Context, pacote: String) {
        val app = ctx.applicationContext
        fila.execute {
            if (Congelar.definir(app, pacote, false)) prefs(app).edit().remove(pacote).apply()
        }
    }
}

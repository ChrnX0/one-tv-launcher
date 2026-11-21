package br.paulo.tvlauncher.screensaver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import br.paulo.tvlauncher.PluginsTv
import br.paulo.tvlauncher.settings.Tema
import br.paulo.tvlauncher.txt
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parte Android dos plugins de fundo ([WallpaperPluginContract]): achar os instalados, guardar o OK do dono por
 * (pacote, certificado) como nos cartões ([PluginsTv]), escolher um, e buscar os fundos dele com prazo e cache.
 *
 * Segurança, na mesma ordem dos cartões: (1) nada é chamado sem o OK do dono, dado com o PIN parental em
 * Configurações › Aparência › Plugins; (2) o OK vale só para o certificado daquele momento (APK trocado = desligado);
 * (3) toda chamada roda fora da thread da tela, com prazo; (4) o launcher só manda ao plugin o evento do contrato
 * (tempo, foco de um item com nome e pacote, ocioso) e nunca dados de contas, IPTV ou painel.
 * Plano: Recurso.PLUGINS (Pro), como os cartões.
 */
object WallpaperPlugins {
    private const val TAG = "TvLauncher.Fundo"
    private const val PREFS = "wallpaper_plugins"
    private const val CHOSEN = "chosen"
    private const val CACHE = "wallpaper_plugin_cache.json"
    private const val PRAZO_LIGAR_MS = 4_000L
    /** O plugin pode baixar da internet ao responder: prazo maior no pedido de tempo, curto no foco (a pessoa está andando). */
    const val PRAZO_TEMPO_MS = 25_000L
    const val PRAZO_FOCO_MS = 3_000L

    data class Provider(
        val pacote: String, val classe: String, val nome: String, val uuid: String?, val apiVersion: Int,
        val settings: String?, val cacheMs: Long, val updateMode: Int,
    ) {
        val id get() = "$pacote/$classe"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Plugins de fundo instalados (qualquer serviço exportado que responde a [WallpaperPluginContract.ACTION]). */
    fun instalados(ctx: Context): List<Provider> {
        val pm = ctx.packageManager
        return runCatching { pm.queryIntentServices(Intent(WallpaperPluginContract.ACTION), PackageManager.GET_META_DATA) }
            .getOrDefault(emptyList()).mapNotNull { r ->
                val s = r.serviceInfo ?: return@mapNotNull null
                if (!s.exported || s.packageName == ctx.packageName) return@mapNotNull null
                val md = s.metaData
                fun txt(k: String) = md?.get(k)?.toString()
                val nome = txt("name")?.takeIf { it.isNotBlank() }
                    ?: runCatching { s.loadLabel(pm).toString() }.getOrNull() ?: s.packageName
                Provider(s.packageName, s.name, nome.take(40), txt("uuid"), txt("apiVersion")?.toIntOrNull() ?: 1,
                    WallpaperPluginContract.classeConfig(s.packageName, txt("settingsActivity")),
                    WallpaperPluginContract.cacheMs(txt("itemsCacheDurationMillis")?.toLongOrNull()),
                    txt("updateMode")?.toIntOrNull() ?: WallpaperPluginContract.EV_TIME_ELAPSED)
            }.distinctBy { it.id }.sortedBy { it.nome.lowercase() }
    }

    fun permitido(ctx: Context, pacote: String): Boolean {
        val ok = prefs(ctx).getString("ok:$pacote", null) ?: return false
        return PluginCartao.permitido(mapOf(pacote to ok), pacote, PluginsTv.certificado(ctx, pacote))
    }

    /** O escolhido como fundo ("pacote/classe"), só pelas preferências (barato: o Tema.atual chama em todo onResume). */
    fun escolhidoId(ctx: Context): String? = prefs(ctx).getString(CHOSEN, null)

    /** O escolhido, instalado e ainda com o OK válido; ou null. Lê o PackageManager: fora da thread da tela se der. */
    fun escolhido(ctx: Context): Provider? {
        val id = escolhidoId(ctx) ?: return null
        return instalados(ctx).firstOrNull { it.id == id }?.takeIf { permitido(ctx, it.pacote) }
    }

    /** Usa [p] como fundo: guarda o OK com o certificado de agora e põe o tema. False = sem certificado legível. */
    fun usar(ctx: Context, p: Provider): Boolean {
        val cert = PluginsTv.certificado(ctx, p.pacote) ?: return false
        prefs(ctx).edit().putString("ok:${p.pacote}", cert).putString(CHOSEN, p.id).apply()
        limparCache(ctx)
        Tema.definir(ctx, Tema.PLUGIN)
        return true
    }

    /** Para de usar (e tira o OK): a HOME volta ao Escuro. */
    /** O escolhido não existe mais (ou não passa no OK): tira a escolha e volta o tema ao Escuro. */
    fun esquecer(ctx: Context) {
        if (Tema.atual(ctx) == Tema.PLUGIN) Tema.definir(ctx, Tema.ESCURO)   // antes de tirar a escolha (atual() olha ela)
        prefs(ctx).edit().remove(CHOSEN).apply()
        limparCache(ctx)
    }

    fun parar(ctx: Context, p: Provider) {
        val e = prefs(ctx).edit().remove("ok:${p.pacote}")
        if (escolhidoId(ctx) == p.id) { e.remove(CHOSEN); if (Tema.atual(ctx) == Tema.PLUGIN) Tema.definir(ctx, Tema.ESCURO) }
        e.apply()
        limparCache(ctx)
    }

    /** Abre a tela de ajustes do próprio plugin (a atividade que ele declarou), ou o app dele. */
    fun configurar(ctx: Context, p: Provider): Boolean {
        val i = p.settings?.let { Intent().setComponent(ComponentName(p.pacote, it)) }
            ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(p.pacote) ?: ctx.packageManager.getLaunchIntentForPackage(p.pacote)
            ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- busca e cache

    private val chamador = Executors.newCachedThreadPool { r -> Thread(r, "PluginFundo").apply { isDaemon = true } }

    /**
     * Liga ao serviço, pede os fundos para [evento] e desliga. Bloqueia até [prazoMs]: chamar FORA da thread da tela.
     * Null = falhou (sem serviço, prazo, exceção); lista vazia = o plugin não tem nada agora.
     */
    fun buscar(ctx: Context, p: Provider, evento: WallpaperPluginContract.Event, prazoMs: Long): List<WallpaperPluginContract.Item>? {
        if (!permitido(ctx, p.pacote)) return null
        val app = ctx.applicationContext
        val ligado = CountDownLatch(1)
        var binder: IBinder? = null
        val conexao = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) { binder = b; ligado.countDown() }
            override fun onServiceDisconnected(n: ComponentName?) {}
            override fun onNullBinding(n: ComponentName?) { ligado.countDown() }
        }
        val intent = Intent(WallpaperPluginContract.ACTION).setComponent(ComponentName(p.pacote, p.classe))
        if (!runCatching { app.bindService(intent, conexao, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
            runCatching { app.unbindService(conexao) }
            return null
        }
        try {
            if (!ligado.await(PRAZO_LIGAR_MS, TimeUnit.MILLISECONDS)) return null
            val b = binder ?: return null
            val f = chamador.submit<List<WallpaperPluginContract.Item>?> { chamar(b, evento) }
            return runCatching { f.get(prazoMs, TimeUnit.MILLISECONDS) }.getOrElse { f.cancel(true); null }
        } catch (e: Exception) {
            Log.w(TAG, "${p.pacote}: $e"); return null
        } finally {
            runCatching { app.unbindService(conexao) }
        }
    }

    private fun chamar(b: IBinder, evento: WallpaperPluginContract.Event): List<WallpaperPluginContract.Item>? {
        val dados = Parcel.obtain(); val resposta = Parcel.obtain()
        return try {
            dados.writeInterfaceToken(WallpaperPluginContract.DESCRIPTOR)
            WallpaperPluginContract.escreverEvento(evento, object : WallpaperPluginContract.Saida {
                override fun int(v: Int) = dados.writeInt(v)
                override fun string(v: String?) = dados.writeString(v)
            })
            if (!b.transact(WallpaperPluginContract.TX_GET_WALLPAPERS, dados, resposta, 0)) return null
            resposta.readException()
            WallpaperPluginContract.lerResposta(object : WallpaperPluginContract.Entrada {
                override fun int() = resposta.readInt()
                override fun string() = resposta.readString()
            }) { true }
        } catch (e: Exception) {
            Log.w(TAG, "getWallpapers: $e"); null
        } finally { dados.recycle(); resposta.recycle() }
    }

    /** Fundos guardados de [p] ainda dentro do prazo do cache, ou null. */
    fun doCache(ctx: Context, p: Provider, agora: Long = System.currentTimeMillis()): List<WallpaperPluginContract.Item>? = runCatching {
        val j = JSONObject(File(ctx.filesDir, CACHE).readText())
        if (j.optString("id") != p.id || WallpaperPluginContract.vencido(j.optLong("em"), agora, p.cacheMs)) return null
        val a = j.getJSONArray("itens")
        (0 until a.length()).mapNotNull { k ->
            val o = a.getJSONObject(k)
            WallpaperPluginContract.limpar(WallpaperPluginContract.Item(o.optString("uri"), o.optInt("type"), o.optInt("mode"),
                o.optString("title").ifEmpty { null }, o.optString("source").ifEmpty { null }, o.optString("author").ifEmpty { null }), { true })
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun guardarCache(ctx: Context, p: Provider, itens: List<WallpaperPluginContract.Item>) {
        runCatching {
            val a = JSONArray()
            itens.forEach { i ->
                a.put(JSONObject().put("uri", i.uri).put("type", i.type).put("mode", i.displayMode)
                    .put("title", i.title ?: "").put("source", i.source ?: "").put("author", i.author ?: ""))
            }
            File(ctx.filesDir, CACHE).writeText(JSONObject().put("id", p.id).put("em", System.currentTimeMillis()).put("itens", a).toString())
        }
    }

    fun limparCache(ctx: Context) { runCatching { File(ctx.filesDir, CACHE).delete() } }
}

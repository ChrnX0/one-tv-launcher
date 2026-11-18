package br.paulo.tvlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * A parte do Android dos pacotes de ícones ([PacoteIconesRegras]). Desligado de fábrica: prefs "pacote_icones",
 * chave "pacote" vazia = ícones dos próprios apps.
 *
 * Custo: o appfilter é lido uma vez por pacote escolhido e fica em memória ([mapa]); cada ícone é 1 getDrawable no
 * pacote. A HOME já guarda o ícone pronto por APK (cacheApps na MainActivity), então isto só roda quando um app muda
 * ou o pacote de ícones troca (a [versao] entra na chave do cache).
 */
object PacoteIcones {
    private const val PREFS = "pacote_icones"
    @Volatile private var mapa: Pair<String, PacoteIconesRegras.Mapa>? = null

    fun escolhido(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pacote", "") ?: ""

    fun escolher(ctx: Context, pacote: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pacote", pacote).apply()
        mapa = null
    }

    /** Muda quando o pacote escolhido muda: a HOME usa na chave do cache de ícones. */
    fun versao(ctx: Context): String = escolhido(ctx)

    /**
     * Pacotes que já vêm no launcher (Paulo, 18/11/2026: quase não há pacote de ícones na Play Store da TV). Redesenham o
     * ícone de cada app no estilo da cruz: Símbolo (o traço do app, claro, sem fundo) e Duotom (o traço com um halo da cor
     * de destaque). O traço vem da camada monocromática do ícone (Android 13+) ou é recortado do próprio ícone.
     */
    const val EMBUTIDO = "embutido:"
    enum class Embutido(val id: String, val nome: Int) {
        SIMBOLO(EMBUTIDO + "simbolo", R.string.cruz_simbolo),
        DUOTOM(EMBUTIDO + "duotom", R.string.cruz_duotom)
    }

    /** O pacote embutido escolhido, ou null (nenhum ou um pacote instalado). */
    fun embutido(ctx: Context): Embutido? = escolhido(ctx).let { e -> Embutido.entries.firstOrNull { it.id == e } }

    /** Pacotes de ícones: os embutidos primeiro, depois os instalados (pacote -> nome), em ordem de nome. */
    fun instalados(ctx: Context): List<Pair<String, String>> =
        Embutido.entries.map { it.id to ctx.getString(it.nome) } + instaladosDeFora(ctx)

    private fun instaladosDeFora(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val vistos = LinkedHashMap<String, String>()
        for (acao in PacoteIconesRegras.ACOES) {
            val l = runCatching { pm.queryIntentActivities(Intent(acao), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
            for (ri in l) {
                val p = ri.activityInfo.packageName
                if (p !in vistos) vistos[p] = runCatching { ri.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault(p)
            }
        }
        return vistos.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    fun nome(ctx: Context, pacote: String): String =
        Embutido.entries.firstOrNull { it.id == pacote }?.let { ctx.getString(it.nome) } ?: runCatching { ctx.packageManager.getApplicationInfo(pacote, 0).loadLabel(ctx.packageManager).toString() }.getOrDefault(pacote)

    @SuppressLint("DiscouragedApi")   // getIdentifier é o único jeito: o nome do recurso vem do appfilter de outro app
    private fun carregar(ctx: Context, pacote: String): PacoteIconesRegras.Mapa? = runCatching {
        val res = ctx.packageManager.getResourcesForApplication(pacote)
        val id = res.getIdentifier("appfilter", "xml", pacote)
        if (id != 0) res.getXml(id).use { PacoteIconesRegras.ler(it) }
        else res.assets.open("appfilter.xml").use { s ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(s, null)
            PacoteIconesRegras.ler(p)
        }
    }.getOrNull()

    private fun mapaDe(ctx: Context, pacote: String): PacoteIconesRegras.Mapa? {
        mapa?.let { (p, m) -> if (p == pacote) return m }
        val m = carregar(ctx, pacote) ?: return null
        mapa = pacote to m
        return m
    }

    /**
     * Ícone do pacote escolhido para o app, ou null (sem pacote escolhido, app fora do pacote ou pacote desinstalado:
     * quem chama usa o ícone do próprio app).
     */
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    fun icone(ctx: Context, pacoteApp: String, classe: String?): Drawable? {
        val escolhido = escolhido(ctx)
        if (escolhido.isEmpty() || escolhido.startsWith(EMBUTIDO)) return null
        val m = mapaDe(ctx, escolhido) ?: return null
        val nome = m.drawable(pacoteApp, classe) ?: return null
        return runCatching {
            val res = ctx.packageManager.getResourcesForApplication(escolhido)
            val id = res.getIdentifier(nome, "drawable", escolhido).takeIf { it != 0 }
                ?: res.getIdentifier(nome, "mipmap", escolhido).takeIf { it != 0 } ?: return null
            res.getDrawable(id, null)
        }.getOrNull()
    }
}

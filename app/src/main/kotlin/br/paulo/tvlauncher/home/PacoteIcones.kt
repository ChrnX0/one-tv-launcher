package br.paulo.tvlauncher.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Xml
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.screensaver.PluginCatalog
import br.paulo.tvlauncher.settings.Planos
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
        // Regra do Paulo (18/11/2026): o pacote escolhido tem os ícones copiados para dentro do launcher e fica congelado.
        IconPackFreezer.congelarDepois(ctx, pacote)
    }

    /** Muda quando o pacote escolhido ou um ícone trocado à mão muda: a HOME usa na chave do cache de ícones. */
    fun versao(ctx: Context): String = escolhido(ctx) + "|" + CustomIcons.versao(ctx) + "|" + Planos.permite(ctx, Planos.Recurso.PLUGINS)

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

    /** Só os pacotes instalados de outros desenvolvedores (pacote -> nome): a lista do "Trocar ícone". */
    fun deFora(ctx: Context): List<Pair<String, String>> = instaladosDeFora(ctx)

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
                if (p !in vistos) vistos[p] = PluginCatalog.nomeDoPacote(p) ?: runCatching { ri.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault(p)
            }
        }
        // Os que nós congelamos (IconPackFreezer) não respondem mais à busca: entram pela lista guardada.
        for ((p, n) in IconPackFreezer.congelados(ctx)) if (p !in vistos) vistos[p] = PluginCatalog.nomeDoPacote(p) ?: n
        return vistos.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    fun nome(ctx: Context, pacote: String): String =
        Embutido.entries.firstOrNull { it.id == pacote }?.let { ctx.getString(it.nome) } ?: PluginCatalog.nomeDoPacote(pacote) ?: runCatching { ctx.packageManager.getApplicationInfo(pacote, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(ctx.packageManager).toString() }.getOrDefault(pacote)

    @SuppressLint("DiscouragedApi")   // getIdentifier é o único jeito: o nome do recurso vem do appfilter de outro app
    private fun carregar(ctx: Context, pacote: String): PacoteIconesRegras.Mapa? = runCatching {
        // Congelado e o Android não deixa ler: o mapa guardado na última leitura (IconPackFreezer).
        val res = IconPackFreezer.recursos(ctx, pacote) ?: return IconPackFreezer.mapaGuardado(ctx, pacote)
        val id = res.getIdentifier("appfilter", "xml", pacote)
        val m = if (id != 0) res.getXml(id).use { PacoteIconesRegras.ler(it) }
        else res.assets.open("appfilter.xml").use { s ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(s, null)
            PacoteIconesRegras.ler(p)
        }
        IconPackFreezer.guardarMapa(ctx, pacote, m)
        m
    }.getOrNull() ?: IconPackFreezer.mapaGuardado(ctx, pacote)

    /** O mapa de qualquer pacote (o "Trocar ícone" abre um que não é o escolhido): não mexe no guardado da HOME. */
    fun mapaDeQualquer(ctx: Context, pacote: String): PacoteIconesRegras.Mapa? =
        mapa?.takeIf { it.first == pacote }?.second ?: carregar(ctx, pacote)

    /**
     * Um ícone pelo nome do recurso, de qualquer pacote instalado; null = não achou. Primeiro a cópia guardada no launcher
     * (o pacote pode estar congelado); senão lê do pacote e guarda a cópia para a próxima vez.
     */
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    /** [guardar] = falso no seletor "Trocar ícone": ver a grade não grava um PNG por ícone exibido (debug 20/11/2026). */
    fun desenho(ctx: Context, pacote: String, nome: String, guardar: Boolean = true): Drawable? {
        if (pacote.startsWith(EMBUTIDO) || !PacoteIconesRegras.drawableValido(nome)) return null
        IconPackFreezer.copia(ctx, pacote, nome)?.let { return it }
        return runCatching {
            val res = IconPackFreezer.recursos(ctx, pacote) ?: return null
            val id = res.getIdentifier(nome, "drawable", pacote).takeIf { it != 0 }
                ?: res.getIdentifier(nome, "mipmap", pacote).takeIf { it != 0 } ?: return null
            res.getDrawable(id, null)?.also { d ->
                if (guardar) IconPackFreezer.guardar(ctx, pacote, nome, d.constantState?.newDrawable(ctx.resources) ?: d)
            }
        }.getOrNull()
    }

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
        return desenho(ctx, escolhido, nome)
    }
}

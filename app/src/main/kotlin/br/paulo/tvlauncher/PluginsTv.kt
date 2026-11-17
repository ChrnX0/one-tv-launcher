package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Plugins de terceiros na HOME (degrau 3, 16/11/2026). O contrato e as regras puras estão em [PluginCartao]; aqui é a
 * parte Android: achar os plugins instalados, guardar o OK do dono por (pacote, certificado) e ler o cartão de cada um.
 *
 * Segurança, em ordem: (1) nada aparece sem o OK do dono, dado nesta TV com o PIN parental (Configurações › Aparência ›
 * Plugins); (2) o OK vale só para o certificado que assinou o plugin quando o dono aceitou, então um APK trocado por
 * outro de mesmo nome perde o lugar; (3) cada leitura tem 2 s de prazo, fora da thread da tela, para um plugin lento não
 * travar a HOME; (4) o launcher só RECEBE dados do plugin, nunca manda nada para ele.
 */
object PluginsTv {
    private const val PREFS = "plugins"
    private const val PRAZO_MS = 2_000L

    data class Instalado(val pacote: String, val autoridade: String, val nome: String)

    /** Plugins instalados (qualquer app com um provider que responde a [PluginCartao.ACAO]). */
    fun instalados(ctx: Context): List<Instalado> {
        val pm = ctx.packageManager
        return runCatching { pm.queryIntentContentProviders(Intent(PluginCartao.ACAO), 0) }.getOrDefault(emptyList())
            .mapNotNull { r ->
                val p = r.providerInfo ?: return@mapNotNull null
                if (!p.exported || p.packageName == ctx.packageName) return@mapNotNull null
                // Um provider pode ter várias autoridades ("a;b"): usamos a primeira.
                val aut = p.authority?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Instalado(p.packageName, aut, runCatching { p.applicationInfo.loadLabel(pm).toString() }.getOrDefault(p.packageName))
            }.distinctBy { it.pacote }.sortedBy { it.nome.lowercase() }
    }

    /** SHA-256 (hex) do certificado que assina [pacote], ou null (app sumiu ou tem mais de um assinante). */
    fun certificado(ctx: Context, pacote: String): String? = runCatching {
        val info = ctx.packageManager.getPackageInfo(pacote, PackageManager.GET_SIGNING_CERTIFICATES)
        val s = info.signingInfo ?: return null
        if (s.hasMultipleSigners()) return null
        val cert = s.signingCertificateHistory?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun permitidos(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun permitido(ctx: Context, pacote: String): Boolean = PluginCartao.permitido(permitidos(ctx), pacote, certificado(ctx, pacote))

    /** Liga (com o certificado de agora) ou desliga um plugin. Ligar só vale até [PluginCartao.MAX_PLUGINS]. */
    fun definir(ctx: Context, pacote: String, ligado: Boolean): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!ligado) { prefs.edit().remove(pacote).apply(); return true }
        val cert = certificado(ctx, pacote) ?: return false
        if (permitidos(ctx).count { it.key != pacote } >= PluginCartao.MAX_PLUGINS) return false
        prefs.edit().putString(pacote, cert).apply()
        return true
    }

    private val leitor = Executors.newCachedThreadPool { r -> Thread(r, "Plugin").apply { isDaemon = true } }

    /** Os cartões dos plugins permitidos (no máximo 3). Bloqueia até 2 s por plugin: chamar FORA da thread da tela. */
    fun cartoes(ctx: Context): List<PluginCartao.Cartao> {
        val ok = permitidos(ctx)
        if (ok.isEmpty() || !Planos.permite(ctx, Planos.Recurso.PLUGINS)) return emptyList()   // plano: Pro
        return instalados(ctx).filter { PluginCartao.permitido(ok, it.pacote, certificado(ctx, it.pacote)) }
            .take(PluginCartao.MAX_PLUGINS).mapNotNull { p ->
                val f = leitor.submit<PluginCartao.Cartao?> { ler(ctx, p) }
                runCatching { f.get(PRAZO_MS, TimeUnit.MILLISECONDS) }.getOrElse { f.cancel(true); null }
            }
    }

    private fun ler(ctx: Context, p: Instalado): PluginCartao.Cartao? = runCatching {
        val uri = Uri.parse("content://${p.autoridade}/${PluginCartao.CAMINHO}")
        ctx.contentResolver.query(uri, PluginCartao.COLUNAS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val linha = PluginCartao.COLUNAS.associateWith { col ->
                val i = c.getColumnIndex(col)
                if (i < 0) null else runCatching { c.getString(i) }.getOrNull()
            }
            PluginCartao.limpar(p.pacote, linha)
        }
    }.getOrNull()

    /** Abre a ação do cartão, sempre presa ao pacote do plugin (sem componente nem seletor), ou o próprio app. */
    fun abrir(ctx: Context, c: PluginCartao.Cartao) {
        val i = c.acao?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }?.apply {
            component = null; selector = null; setPackage(c.pacote)
        } ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(c.pacote) ?: ctx.packageManager.getLaunchIntentForPackage(c.pacote)
        ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}

/** Configurações › Aparência › Plugins: liga e desliga cada plugin instalado (o OK é dado aqui, depois do PIN). */
class PluginsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.plg_titulo), getString(R.string.plg_det))
        val todos = PluginsTv.instalados(this)
        if (todos.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.plg_nenhum)))
        for (p in todos) {
            val valor = Estilo.valor(this, "", seta = false)
            fun pintar() { valor.text = getString(if (PluginsTv.permitido(this, p.pacote)) R.string.plg_ligado else R.string.plg_desligado) }
            pintar()
            coluna.addView(Estilo.linhaOpcao(this, p.nome, p.pacote, valor).apply {
                setOnClickListener {
                    val novo = !PluginsTv.permitido(this@PluginsActivity, p.pacote)
                    if (novo && !PlanosTv.pedir(this@PluginsActivity, Planos.Recurso.PLUGINS)) return@setOnClickListener   // ligar: Pro
                    if (!PluginsTv.definir(this@PluginsActivity, p.pacote, novo))
                        android.widget.Toast.makeText(this@PluginsActivity, getString(R.string.plg_limite, PluginCartao.MAX_PLUGINS), android.widget.Toast.LENGTH_LONG).show()
                    pintar()
                }
            })
        }
        coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.plg_seguranca)).apply { setPadding(0, 24, 0, 0) })
        setContentView(raiz)
        coluna.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }
}

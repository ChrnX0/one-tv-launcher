package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.player.LojaCurada
import br.paulo.tvlauncher.screensaver.PluginCartao
import br.paulo.tvlauncher.screensaver.PluginCatalog
import br.paulo.tvlauncher.screensaver.WallpaperPlugins
import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.settings.Tema
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
        if (ok.isEmpty()) return emptyList()
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

/**
 * Configurações › Aparência › Plugins (o PIN parental já foi pedido para entrar). Uma tela só, em quatro grupos:
 * FUNDOS (plugins de fundo instalados: usar, ajustes do plugin, parar), ÍCONES (pacotes de ícones de outros
 * desenvolvedores; ficam congelados, IconPackFreezer), CARTÕES (o OK de cada cartão) e CONSEGUIR PLUGINS (a vitrine
 * PluginCatalog: Play Store, instalar do Release oficial ou QR). Pacotes e fundos de terceiros = plano Pro.
 */
class PluginsActivity : Activity() {
    private val trabalho = Executors.newSingleThreadExecutor { r -> Thread(r, "Plugins").apply { isDaemon = true } }
    private var aberto: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        montar()
    }

    override fun onDestroy() { super.onDestroy(); aberto?.dismiss(); trabalho.shutdown() }

    /** Volta de outro app (ajustes do plugin, Play Store): a lista pode ter mudado. */
    override fun onRestart() { super.onRestart(); montar() }

    private fun pro(): Boolean {
        if (Planos.permite(this, Planos.Recurso.PLUGINS)) return true
        startActivity(Intent(this, ConhecerProActivity::class.java))
        return false
    }

    private fun montar() {
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.plg_titulo), getString(R.string.wp_det))
        val fundos = WallpaperPlugins.instalados(this)
        val pacotes = PacoteIcones.deFora(this)
        val cartoes = PluginsTv.instalados(this)

        // ---- Fundos
        coluna.addView(Estilo.secao(this, getString(R.string.wp_secao_fundos)))
        if (fundos.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.wp_nenhum_fundo)))
        val escolhido = WallpaperPlugins.escolhidoId(this)
        for (f in fundos) {
            val emUso = f.id == escolhido && Tema.atual(this) == Tema.PLUGIN && WallpaperPlugins.permitido(this, f.pacote)
            val valor = Estilo.valor(this, if (emUso) getString(R.string.wp_em_uso) else "", seta = !emUso)
            coluna.addView(Estilo.linhaOpcao(this, f.nome, getString(R.string.wp_tipo_fundo), valor).apply {
                setOnClickListener { menuFundo(f, emUso) }
            }, Estilo.lpLinha(this))
        }

        // ---- Ícones
        coluna.addView(Estilo.secao(this, getString(R.string.wp_secao_icones)).apply { setPaddingRelative(0, Estilo.dp(context, 24), 0, 0) })
        if (pacotes.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.wp_nenhum_icone)))
        for ((pac, nome) in pacotes) {
            val emUso = PacoteIcones.escolhido(this) == pac
            val valor = Estilo.valor(this, if (emUso) getString(R.string.wp_em_uso) else getString(R.string.wp_usar_icones), seta = !emUso)
            coluna.addView(Estilo.linhaOpcao(this, nome, getString(R.string.wp_icones_det), valor).apply {
                setOnClickListener {
                    if (!pro()) return@setOnClickListener
                    PacoteIcones.escolher(this@PluginsActivity, if (emUso) "" else pac)   // de novo no que está em uso = desliga
                    montar()
                }
            }, Estilo.lpLinha(this))
        }

        // ---- Cartões
        coluna.addView(Estilo.secao(this, getString(R.string.wp_secao_cartoes)).apply { setPaddingRelative(0, Estilo.dp(context, 24), 0, 0) })
        if (cartoes.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.plg_nenhum)))
        for (p in cartoes) {
            val valor = Estilo.valor(this, "", seta = false)
            fun pintar() { valor.text = getString(if (PluginsTv.permitido(this, p.pacote)) R.string.plg_ligado else R.string.plg_desligado) }
            pintar()
            coluna.addView(Estilo.linhaOpcao(this, p.nome, p.pacote, valor).apply {
                setOnClickListener {
                    val novo = !PluginsTv.permitido(this@PluginsActivity, p.pacote)
                    if (!PluginsTv.definir(this@PluginsActivity, p.pacote, novo))
                        android.widget.Toast.makeText(this@PluginsActivity, getString(R.string.plg_limite, PluginCartao.MAX_PLUGINS), android.widget.Toast.LENGTH_LONG).show()
                    pintar()
                }
            }, Estilo.lpLinha(this))
        }

        // ---- Conseguir plugins (vitrine)
        coluna.addView(Estilo.secao(this, getString(R.string.wp_secao_descobrir)).apply { setPaddingRelative(0, Estilo.dp(context, 24), 0, 0) })
        val instalados = (fundos.map { it.pacote } + pacotes.map { it.first }).toSet()
        for (e in PluginCatalog.LISTA) {
            val pac = e.pacote ?: e.lojaId?.let { LojaCurada.pacoteGuardado(this, it) }
            val ja = pac != null && (pac in instalados || instaladoNoSistema(pac))
            val tipo = getString(if (e.tipo == PluginCatalog.Tipo.FUNDO) R.string.wp_tipo_fundo else R.string.wp_tipo_icones)
            val valor = Estilo.valor(this, if (ja) getString(R.string.wp_instalado) else "", seta = !ja)
            coluna.addView(Estilo.linhaOpcao(this, e.nome, "$tipo · ${getString(e.descricao)}", valor).apply {
                setOnClickListener { if (ja) montar() else oferecer(e) }
            }, Estilo.lpLinha(this))
        }
        coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.plg_seguranca)).apply { setPaddingRelative(0, 24, 0, 0) })
        setContentView(raiz)
        coluna.getChildAt(1)?.takeIf { it.isFocusable }?.requestFocus() ?: run {
            for (k in 0 until coluna.childCount) if (coluna.getChildAt(k).isFocusable) { coluna.getChildAt(k).requestFocus(); break }
        }
    }

    private fun instaladoNoSistema(p: String) =
        runCatching { packageManager.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS); true }.getOrDefault(false)

    /** Fundo instalado: usar como fundo, ajustes do próprio plugin, parar de usar. */
    private fun menuFundo(f: WallpaperPlugins.Provider, emUso: Boolean) {
        val opcoes = mutableListOf<Pair<String, () -> Unit>>()
        if (!emUso) opcoes += getString(R.string.wp_usar) to {
            if (pro()) {
                if (WallpaperPlugins.usar(this, f)) montar()
                else android.widget.Toast.makeText(this, getString(R.string.wp_sem_cert), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (f.settings != null || emUso) opcoes += getString(R.string.wp_configurar) to { WallpaperPlugins.configurar(this, f); Unit }
        if (emUso) opcoes += getString(R.string.wp_parar) to { WallpaperPlugins.parar(this, f); montar() }
        aberto = Estilo.lista(this, f.nome, opcoes.map { it.first }) { i -> opcoes[i].second() }
    }

    /** Plugin da vitrine que não está na TV: Play Store, instalar do Release oficial (APK do site) e o QR da página. */
    private fun oferecer(e: PluginCatalog.Entrada) {
        val temPlay = runCatching { packageManager.getPackageInfo("com.android.vending", 0); true }.getOrDefault(false)
        val acao = PluginCatalog.acao(e, BuildConfig.PLAY, temPlay)
        val d = android.app.Dialog(this)
        val botoes = mutableListOf(Estilo.botao(this, getString(R.string.vq_fechar), primario = false).apply { setOnClickListener { d.dismiss() } })
        when (acao) {
            PluginCatalog.Acao.PLAY -> botoes += Estilo.botao(this, getString(R.string.wp_abrir_play)).apply {
                setOnClickListener {
                    d.dismiss()
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${e.play}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PluginCatalog.Acao.INSTALAR -> botoes += Estilo.botao(this, getString(R.string.wp_instalar)).apply {
                setOnClickListener {
                    if (!pro()) return@setOnClickListener
                    isEnabled = false
                    text = getString(R.string.wp_instalando)
                    val id = e.lojaId ?: return@setOnClickListener
                    trabalho.execute {
                        val r = LojaCurada.instalar(applicationContext, id)
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            d.dismiss()
                            val ok = !r.startsWith("erro:")
                            android.widget.Toast.makeText(this@PluginsActivity, if (ok) getString(R.string.wp_instalou, e.nome)
                                else getString(R.string.wp_nao_instalou, r.removePrefix("erro:").trim()), android.widget.Toast.LENGTH_LONG).show()
                            montar()
                        }
                    }
                }
            }
            PluginCatalog.Acao.SO_QR -> {}
        }
        val tela = Estilo.dialogo(this, getString(if (e.tipo == PluginCatalog.Tipo.FUNDO) R.string.wp_tipo_fundo else R.string.wp_tipo_icones),
            e.nome, getString(e.descricao) + "\n\n" + getString(R.string.wp_qr), botoes)
        PluginCatalog.pagina(e)?.let { link ->
            val lado = Estilo.dp(this, 220)
            val qr = android.widget.ImageView(this).apply { setImageBitmap(runCatching { QrCodigo.bitmap(link, lado) }.getOrNull()) }
            (tela.getChildAt(0) as? android.widget.LinearLayout)?.addView(qr, 0, android.widget.LinearLayout.LayoutParams(lado, lado).apply {
                bottomMargin = Estilo.dp(this@PluginsActivity, 16)
            })
        }
        Estilo.vestir(d, tela)
        d.show()
        aberto = d
        botoes.last().requestFocus()
    }
}

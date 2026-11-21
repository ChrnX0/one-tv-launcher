package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.observer.QuedasSeguidas

/**
 * A HOME mínima do modo de segurança (degrau 2, 16/11/2026). Abre no lugar da HOME quando ela caiu 3 vezes seguidas
 * (ModoSeguro / QuedasSeguidas). De propósito, quase nada: nenhuma rede, nenhuma imagem, nenhum cartão. Só o
 * necessário para a pessoa continuar usando a TV e sair do problema:
 *  - procurar uma versão corrigida (o Atualizador baixa e instala na hora, se houver);
 *  - tentar a tela inicial normal de novo;
 *  - a lista de apps instalados (abrir a Netflix etc. continua funcionando);
 *  - as Configurações do Android e a tela de Diagnóstico (onde está o erro).
 * BACK não sai daqui: esta tela É a tela inicial enquanto o modo durar.
 */
class HomeSeguraActivity : Activity() {
    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        montar()
    }

    override fun onResume() {
        super.onResume()
        // Versão nova chegou (o Atualizador instalou) ou alguém saiu do modo pelo adb: volta à HOME de verdade.
        if (!ModoSeguro.ativo(this)) abrirHomeNormal()
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() { /* é a tela inicial: não há para onde voltar */ }

    private fun montar() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.seg_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.seg_texto, QuedasSeguidas.QUEDAS)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        estado = Estilo.texto(this, Estilo.Papel.LEGENDA, "")
        raiz.addView(estado, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(8), dp(8), dp(8), dp(8)); clipChildren = false; clipToPadding = false
        }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })

        val procurar = linha(getString(R.string.seg_procurar), getString(R.string.seg_procurar_det)) { procurarVersao() }
        lista.addView(procurar)
        lista.addView(linha(getString(R.string.seg_normal), getString(R.string.seg_normal_det)) {
            ModoSeguro.sair(this); abrirHomeNormal()
        })
        lista.addView(linha(getString(R.string.seg_config_tv), null) { abrir(Intent(Settings.ACTION_SETTINGS)) })
        lista.addView(linha(getString(R.string.seg_erro), getString(R.string.seg_erro_det)) {
            abrir(Intent(this, DiagnosticoActivity::class.java))
        })
        lista.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.seg_apps)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); bottomMargin = dp(8) })
        for ((nome, abrirApp) in appsInstalados()) lista.addView(linha(nome, null) { abrir(abrirApp) })
        setContentView(raiz)
        procurar.requestFocus()
    }

    /** Os apps com ícone na HOME da TV (leanback), em ordem alfabética. Erro aqui = lista vazia, a tela segue. */
    private fun appsInstalados(): List<Pair<String, Intent>> = try {
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { r ->
                r.loadLabel(pm).toString() to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            .distinctBy { it.first }
            .sortedBy { it.first.lowercase() }
    } catch (_: Exception) { emptyList() }

    private fun procurarVersao() {
        estado.text = getString(R.string.seg_procurando)
        Atualizador.verificar(this, instalar = true) { r -> runOnUiThread { if (!isDestroyed) estado.text = r } }
    }

    private fun abrirHomeNormal() {
        abrir(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun abrir(i: Intent) = try { startActivity(i) } catch (_: Exception) {}

    private fun linha(titulo: String, detalhe: String?, aoClicar: () -> Unit) = Estilo.linhaOpcao(this, titulo, detalhe).apply {
        setOnClickListener { aoClicar() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

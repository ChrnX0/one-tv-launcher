package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.home.SofalyWordmark
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Novidades

/**
 * "Sobre a Sofaly" (18/11/2026): a logo grande, o slogan, a versão e dois botões (o que mudou e as licenças). Aberta
 * pela linha do mesmo nome em Configurações > Sistema > Sobre. Mesmo fundo e margens das outras telas.
 */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        fun dp(v: Int) = Estilo.dp(this, v)
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(context) }
        val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        coluna.addView(SofalyWordmark(this, dp(80).toFloat()), LinearLayout.LayoutParams(-2, -2))
        coluna.addView(TextView(this).apply {
            text = getString(R.string.brand_slogan)
            setTextColor(Estilo.TEXTO)   // slogan em branco, acima do cinza da versão (crítica de design 19/11/2026)
            typeface = Fontes.nunito(context, 400)   // estava dentro do comentário acima e nunca valia (debug 20/11/2026)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val nome = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        coluna.addView(TextView(this).apply {
            text = getString(R.string.brand_version, listOfNotNull(nome, "(${BuildConfig.VERSION_CODE})").joinToString(" "))
            setTextColor(Estilo.TEXTO_3); typeface = Fontes.nunito(context, 400)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E3) })
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        val novidades = Estilo.botao(this, getString(R.string.ver_novidades)).apply { setOnClickListener { Novidades.mostrar(this@AboutActivity) } }
        val licencas = Estilo.botao(this, getString(R.string.cfg_licencas)).apply {
            setOnClickListener { startActivity(Intent(this@AboutActivity, LicencasActivity::class.java)) }
        }
        botoes.addView(novidades, LinearLayout.LayoutParams(-2, -2))
        botoes.addView(licencas, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
        coluna.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E3 + 8) })
        coluna.clipChildren = false
        raiz.clipChildren = false
        raiz.addView(coluna, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(raiz)
        novidades.requestFocus()
    }
}

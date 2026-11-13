package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Licenças e créditos" (05/11/2026): mostra assets/licencas.txt numa tela rolável. Aberta pela última linha das
 * Configurações do launcher. O texto mora num arquivo (e não aqui no código) para ser o mesmo que o repositório público
 * mostra e para dar para atualizar sem mexer em Kotlin.
 */
class LicencasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val texto = try { assets.open("licencas.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { getString(R.string.lic_sem_arquivo) }
        fun dp(v: Int) = Estilo.dp(this, v)
        // Mesmas margens, fundo e título das outras telas (sistema de design).
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cfg_licencas)))
        val corpo = Estilo.texto(this, Estilo.Papel.LEGENDA, texto).apply {
            setTextColor(Estilo.TEXTO_2)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        // A ScrollView recebe o foco: as setas do controle rolam o texto, BACK fecha.
        val rolagem = ScrollView(this).apply { isFocusable = true; background = Estilo.superficie(context, false); addView(corpo) }
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(Estilo.E2) })
        setContentView(raiz)
        rolagem.requestFocus()
    }
}

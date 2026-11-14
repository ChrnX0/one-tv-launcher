package br.paulo.tvlauncher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Moldura da HOME nas telas de lista (coerência visual, parte 1, 12/11/2026; proposta B aprovada pelo dono): a hora e
 * a data no MESMO lugar e no mesmo desenho da barra da HOME (alto à direita, hora de 22 sp e a data em duas linhas ao
 * lado). Quem sai da HOME para uma lista vê a hora parada no mesmo ponto e sabe que continua no mesmo sistema.
 *
 * Como: um único gancho no ciclo de vida das telas (registrado no LauncherApp) põe o relógio por cima do conteúdo das
 * telas da lista [TELAS]. Nenhuma tela precisa mudar. O relógio não pega foco (a navegação não muda) e fica fora das
 * telas que já têm hora própria ou que usam o alto à direita (Calendário, Previsão, catálogos, player, diálogos).
 */
object Moldura {
    /** As telas de lista (o "miolo lista" da proposta B). Nome curto da classe. */
    private val TELAS = setOf(
        "AlarmesActivity", "BackupNoturnoActivity", "BoasVindasActivity", "BuscaActivity", "CasaActivity", "CenasActivity",
        "ConectarClaudeActivity", "ConfigLauncherActivity", "DiagnosticoActivity", "DnsActivity", "EntrarPeloQrActivity",
        "ExtrasActivity", "FotoPerfilActivity", "LicencasActivity", "ModoSimplesActivity", "RotinasActivity", "AutomacoesActivity", "TeclasActivity", "TesteTeclasActivity",
        "MidiaRedeActivity",   // parte 2: Na rede já usa a margem e o título do sistema
    )
    private const val MARCA = "moldura-relogio"

    // A mesma régua da barra da HOME (MainActivity.TOPO_PALCO e ALTURA_TOPO): a hora não pula na troca de tela.
    private const val TOPO = 28
    private const val ALTURA = 48

    fun instalar(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Started e Resumed: a tela já fez o setContentView; e quem refaz a tela inteira (setContentView de novo,
            // ex.: Boas-vindas depois de uma permissão) apaga o relógio, que volta na próxima retomada.
            override fun onActivityStarted(a: Activity) = colocar(a)
            override fun onActivityResumed(a: Activity) = colocar(a)
            override fun onActivityCreated(a: Activity, salvo: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun colocar(a: Activity) {
        if (a.javaClass.simpleName !in TELAS || a.isFinishing) return
        val conteudo = a.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        if (conteudo.childCount == 0 || conteudo.findViewWithTag<android.view.View>(MARCA) != null) return
        conteudo.addView(relogio(a), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Estilo.dp(a, ALTURA),
            Gravity.TOP or Gravity.END).apply {
            topMargin = Estilo.dp(a, TOPO)
            rightMargin = Estilo.dp(a, Estilo.MARGEM_LATERAL)
        })
    }

    /** Hora (22 sp) e a data em duas linhas (12 sp), como na HOME. Só desenho: sem foco, sem clique. */
    fun relogio(a: Activity) = LinearLayout(a).apply {
        tag = MARCA
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO   // o MCP e o leitor de tela pulam
        addView(RelogioTela(a, data = false).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Estilo.TEXTO)
            typeface = Estilo.fonte(500)
            letterSpacing = -0.01f
            includeFontPadding = false
        })
        // 26 dp entre a hora e a data: o mesmo vão da HOME (14 do fim da pílula da hora + 12 do começo da da data).
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            for (fmt in listOf(R.string.fmt_data_dia, R.string.fmt_data_mes)) addView(RelogioTela(a, data = true).apply {
                format12Hour = a.getString(fmt); format24Hour = a.getString(fmt)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                setTextColor(Estilo.TEXTO_2)
                typeface = Estilo.fonte(500)
                includeFontPadding = false
                setLineSpacing(0f, 1.1f)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Estilo.dp(a, 26)
        })
    }
}

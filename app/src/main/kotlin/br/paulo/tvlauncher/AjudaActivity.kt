package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Um assunto do manual (14/11/2026), aberto pela aba "Ajuda" das Configurações. Tela de LER a 3 m do sofá: letra
 * maior que a das listas (20 sp, e cresce com o "Tamanho do texto"), ◀ ▶ trocam de assunto sem voltar ao menu e
 * ▲ ▼ rolam. Os assuntos e as regras estão em [Manual]; os textos, em res/values(-pt)/strings_ajuda.xml.
 */
class AjudaActivity : Activity() {
    companion object {
        const val EXTRA_TOPICO = "topico"   // índice em Manual.TOPICOS

        /** O texto de [id]/[parte] no idioma da TV. O ManualTest garante que o recurso existe nos dois idiomas. */
        fun texto(ctx: Context, id: String, parte: Manual.Parte): String {
            val res = ctx.resources.getIdentifier(Manual.recurso(id, parte), "string", ctx.packageName)
            return if (res != 0) ctx.getString(res) else ""
        }
    }

    private var atual = 0
    private lateinit var tTitulo: TextView
    private lateinit var tResumo: TextView
    private lateinit var tCorpo: TextView
    private lateinit var rolagem: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        atual = (savedInstanceState?.getInt(EXTRA_TOPICO) ?: intent.getIntExtra(EXTRA_TOPICO, 0)).coerceIn(0, Manual.TOPICOS.size - 1)
        fun dp(v: Int) = Estilo.dp(this, v)
        // Mesmas margens, fundo e título das outras telas (sistema de design), como a de Licenças.
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL - 7))
        }
        tTitulo = Estilo.tituloTela(this, "")
        tResumo = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setPadding(0, dp(4), 0, 0) }
        raiz.addView(tTitulo)
        raiz.addView(tResumo)
        // Corpo: cor cheia (não a apagada das legendas) e entrelinha folgada, para ler de longe.
        tCorpo = Estilo.texto(this, Estilo.Papel.CORPO).apply {
            textSize = 20f
            setTextColor(Estilo.TEXTO)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(28), dp(20), dp(28), dp(24))
        }
        // A ScrollView recebe o foco: ▲ ▼ rolam; ◀ ▶ trocam o assunto (dispatchKeyEvent); VOLTAR fecha.
        rolagem = ScrollView(this).apply {
            isFocusable = true; isVerticalScrollBarEnabled = true; isSmoothScrollingEnabled = true
            background = Estilo.superficie(context, false)
            addView(tCorpo)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(Estilo.E2) })
        raiz.addView(Estilo.barraDicas(this, "◀ ▶" to getString(R.string.aj_dica_assunto), "▲ ▼" to getString(R.string.aj_dica_rolar),
            getString(R.string.cl_tecla_voltar) to getString(R.string.cl_dica_sair)).apply { setPadding(0, dp(12), 0, 0) })
        setContentView(raiz)
        mostrar()
        rolagem.requestFocus()
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putInt(EXTRA_TOPICO, atual)
    }

    private fun mostrar() {
        val id = Manual.TOPICOS[atual]
        tTitulo.text = texto(this, id, Manual.Parte.TITULO)
        tResumo.text = getString(R.string.aj_posicao, atual + 1, Manual.TOPICOS.size) + "  ·  " + texto(this, id, Manual.Parte.RESUMO)
        tCorpo.text = texto(this, id, Manual.Parte.TEXTO)
        rolagem.scrollTo(0, 0)
        UsoLocal.contar(this, "ajuda:$id")   // ideia 67: que assuntos são lidos (só nesta TV)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                atual = Manual.vizinho(atual, if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                mostrar()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Rola um pedaço de cada vez (40% da altura), em vez de linha a linha: menos cliques para ler tudo.
                val passo = (rolagem.height * 0.4f).toInt().coerceAtLeast(Estilo.dp(this, 120))
                rolagem.smoothScrollBy(0, if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -passo else passo)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}

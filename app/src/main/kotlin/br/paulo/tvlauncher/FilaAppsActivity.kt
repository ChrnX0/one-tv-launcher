package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.home.abrir

/**
 * Elevação 19.7: "Seus apps voltando". A TV nova recebeu da antiga a lista de apps; os que faltam vêm um por vez:
 * "Abrir na Play Store" leva à página do app (a pessoa toca em Instalar), e ao voltar esta tela já mostra o próximo.
 * Instalar sem a pessoa tocar não dá (a Play Store não deixa nenhum app fazer isso), por isso é uma FILA guiada.
 * "Pular" tira um app da fila; "Depois" fecha e a fila continua em Configurações › Sistema.
 */
class FilaAppsActivity : Activity() {
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var nome: TextView
    private lateinit var abrir: TextView
    private lateinit var pular: TextView
    private lateinit var depois: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false   // o botão com foco cresce: não cortar a borda dele
        }
        titulo = Estilo.tituloTela(this, getString(R.string.mtv_fila_titulo))
        texto = Estilo.texto(this, Estilo.Papel.CORPO).apply { setTextColor(Estilo.TEXTO_2); setPaddingRelative(0, dp(16), 0, 0); setLineSpacing(0f, 1.2f) }
        nome = Estilo.texto(this, Estilo.Papel.TITULO).apply { setPaddingRelative(0, dp(24), 0, 0) }
        col.addView(titulo); col.addView(texto); col.addView(nome)
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) }
        abrir = Estilo.botao(this, getString(R.string.mtv_fila_abrir)).apply { setOnClickListener { abrirProximo() } }
        pular = Estilo.botao(this, getString(R.string.mtv_fila_pular), primario = false).apply {
            setOnClickListener { MudarDeTv.proximo(this@FilaAppsActivity)?.let { MudarDeTv.pular(this@FilaAppsActivity, it.pacote) }; mostrar() }
        }
        depois = Estilo.botao(this, getString(R.string.mtv_fila_depois), primario = false).apply { setOnClickListener { finish() } }
        botoes.addView(abrir, lp()); botoes.addView(pular, lp()); botoes.addView(depois, lp())
        col.addView(botoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) })
        setContentView(col)
        abrir.requestFocus()
    }

    /** Voltou da Play Store (instalou ou não): mostra o próximo. */
    override fun onResume() { super.onResume(); mostrar() }

    private fun mostrar() {
        val prox = MudarDeTv.proximo(this)
        val total = MudarDeTv.fila(this).size
        // Sem fila nenhuma (esta TV não recebeu apps de outra), "Seus apps voltaram" seria mentira: a tela não tem
        // porquê aqui e fecha (teste de 21/11/2026, regra da inteligência global).
        if (total == 0) { finish(); return }
        if (prox == null) {
            // Um estado só no fim (crítica de design 19/11/2026): o título dizia "voltando" e o texto "todos estão aqui".
            titulo.text = getString(R.string.mtv_fila_pronto)
            texto.text = getString(R.string.mtv_fila_fim)
            nome.text = ""
            abrir.text = getString(R.string.mtv_ir_inicio)
            abrir.setOnClickListener { MudarDeTv.limpar(this); finish() }
            pular.visibility = android.view.View.GONE
            depois.visibility = android.view.View.GONE   // acabou: só "Fechar"
            return
        }
        val faltam = MudarDeTv.faltam(this)
        texto.text = getString(R.string.mtv_fila_texto, total - faltam + 1, total)
        nome.text = prox.nome
    }

    private fun abrirProximo() {
        val prox = MudarDeTv.proximo(this) ?: return
        if (!MudarDeTv.abrirNaLoja(this, prox.pacote)) AvisoTv.rapido(this, getString(R.string.mtv_fila_titulo), getString(R.string.jogos_sem_loja))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

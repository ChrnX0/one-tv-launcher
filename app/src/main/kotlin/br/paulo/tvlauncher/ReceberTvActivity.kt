package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.backup.MudarDeTvRegras
import br.paulo.tvlauncher.panel.QrCodigo

/**
 * Elevação 19.7: "Trazer de outra TV", na TV NOVA. Mostra o QR (e o mesmo código em letras grandes, para digitar) que o
 * dono lê no painel da TV antiga. Enquanto esta tela está aberta a porta aceita UM envio cifrado ([MudarDeTv]);
 * saiu da tela, o segredo e a chave morrem. Confere a cada 1 s: chegou -> "Pronto" e o launcher reinicia; depois a
 * HOME abre a fila dos apps ([FilaAppsActivity]).
 */
class ReceberTvActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var codigo: TextView
    private lateinit var qr: ImageView
    private lateinit var outro: TextView
    private var convite: MudarDeTvRegras.Convite? = null
    private var terminou = false

    private val volta = object : Runnable {
        override fun run() { conferir(); if (!terminou) ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        // Acordada (o protetor tiraria a tela e mataria o segredo) e fora de foto de tela (o código não vaza pelo painel).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
        }
        // O botão com foco cresce um pouco (Estilo.botao): sem isto a borda esquerda dele saía cortada.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        raiz.clipChildren = false; raiz.clipToPadding = false
        titulo = Estilo.tituloTela(this, getString(R.string.mtv_titulo))
        texto = Estilo.texto(this, Estilo.Papel.CORPO).apply { setTextColor(Estilo.TEXTO_2); setPaddingRelative(0, dp(16), dp(32), 0); setLineSpacing(0f, 1.2f) }
        codigo = Estilo.texto(this, Estilo.Papel.TITULO).apply { setPaddingRelative(0, dp(16), 0, 0); typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(titulo); col.addView(texto); col.addView(codigo)
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        outro = Estilo.botao(this, getString(R.string.qr_gerar_outro)).apply { setOnClickListener { gerar() } }
        botoes.addView(outro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        val fechar = Estilo.botao(this, getString(R.string.qr_fechar)).apply { setOnClickListener { finish() } }
        botoes.addView(fechar)
        col.addView(botoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        raiz.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = getString(R.string.mtv_qr_desc) }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(320), dp(320)))
        setContentView(raiz)
        fechar.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        gerar()
        ui.post(volta)
    }

    override fun onPause() {
        ui.removeCallbacks(volta)
        MudarDeTv.parar(this)
        super.onPause()
    }

    private fun gerar() {
        terminou = false
        // Depois de um ERRO o laço parou de se reagendar: "Gerar outro" tem de ligá-lo de novo (sem duplicar).
        ui.removeCallbacks(volta); ui.postDelayed(volta, 1000)
        titulo.text = getString(R.string.mtv_titulo)
        convite = MudarDeTv.iniciar(this)
        val c = convite
        if (c == null) {
            qr.visibility = View.INVISIBLE; codigo.text = ""
            texto.text = getString(R.string.pc_sem_rede)
            outro.text = getString(R.string.tentar_de_novo)   // sem rede não há código para "gerar outro"
            return
        }
        outro.text = getString(R.string.qr_gerar_outro)
        qr.setImageBitmap(try { QrCodigo.bitmap(c.texto(), dp(320)) } catch (e: Exception) { null })
        qr.visibility = View.VISIBLE
        codigo.text = c.ip + "\n" + c.codigo()
        conferir()
    }

    private fun conferir() {
        when (MudarDeTv.estado) {
            MudarDeTv.Estado.RECEBENDO -> { qr.visibility = View.INVISIBLE; codigo.text = ""; texto.text = getString(R.string.mtv_recebendo) }
            MudarDeTv.Estado.PRONTO -> {
                terminou = true
                qr.visibility = View.INVISIBLE; codigo.text = ""
                titulo.text = getString(R.string.qr_pronto)
                texto.text = getString(R.string.mtv_pronto, MudarDeTv.detalhe.ifBlank { "?" })
            }
            MudarDeTv.Estado.ERRO -> {
                terminou = true
                qr.visibility = View.INVISIBLE; codigo.text = ""
                texto.text = getString(R.string.mtv_erro, MudarDeTv.detalhe, getString(R.string.qr_gerar_outro))
                outro.requestFocus()
            }
            else -> {
                if (convite == null) return
                if (!MudarDeTv.recebendo()) {
                    convite = null
                    qr.visibility = View.INVISIBLE; codigo.text = ""
                    texto.text = getString(R.string.mtv_venceu, getString(R.string.qr_gerar_outro))
                    outro.requestFocus()
                    return
                }
                val falta = MudarDeTv.tempoRestanteMs() / 1000
                texto.text = getString(R.string.mtv_instrucoes, "${falta / 60}:${"%02d".format(falta % 60)}")
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

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

/**
 * "Entrar pelo celular" (10/11/2026, pedido do dono): a TV mostra um QR com o endereço do painel e um código de uso
 * único (PainelWeb.linkEntradaQr / CodigoEntrada). O celular lê, abre o painel e já entra, sem digitar o PIN.
 *
 * Por que é seguro: o código vale 2 min e uma vez só, é aleatório (192 bits) e só funciona pela rede da casa (o painel
 * não aceita conexão de fora). A sessão que ele cria não é "elevada": instalar, desinstalar e restaurar backup ainda
 * pedem o PIN. Ao sair desta tela, o código é anulado (um QR esquecido não fica valendo).
 *
 * Confere a cada 1 s: o celular entrou -> avisa e fecha; venceu -> some o QR e oferece gerar outro.
 */
class EntrarPeloQrActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    private lateinit var outro: TextView
    private var geradoEm = 0L       // quando o código atual foi gerado (0 = nenhum)
    private var terminou = false

    private val volta = object : Runnable {
        override fun run() { conferir(); if (!terminou) ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Acordada enquanto o QR está na tela (o protetor tiraria a tela da frente e anularia o código no meio da
        // leitura) e fora de foto de tela (o QR não sai no Controle do painel nem no ver_tela do MCP).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titulo = Estilo.tituloTela(this, "")   // título e texto nos papéis do sistema de design
        texto = Estilo.texto(this, Estilo.Papel.CORPO).apply { setTextColor(Estilo.TEXTO_2); setPadding(0, dp(16), dp(32), 0); setLineSpacing(0f, 1.2f) }
        col.addView(titulo); col.addView(texto)
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outro = botao("Gerar outro") { gerar() }
        botoes.addView(outro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(16) })
        val fechar = botao("Fechar") { finish() }
        botoes.addView(fechar)
        col.addView(botoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        raiz.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(320), dp(320)))
        setContentView(raiz)
        fechar.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        terminou = false
        gerar()
        ui.post(volta)
    }

    override fun onPause() {
        ui.removeCallbacks(volta)
        PainelWeb.codigoEntrada.cancelar()   // saiu da tela: o QR que ficou para trás não vale mais
        super.onPause()
    }

    private fun gerar() {
        terminou = false
        val link = PainelWeb.linkEntradaQr()
        titulo.text = "Entrar pelo celular"
        if (link == null) {
            geradoEm = 0L
            qr.visibility = View.INVISIBLE
            texto.text = "O painel não está no ar.\nLigue em Configurações do launcher > Acesso remoto > Painel (precisa de um PIN)."
            return
        }
        geradoEm = System.currentTimeMillis()
        qr.setImageBitmap(try { QrCodigo.bitmap(link, dp(320)) } catch (e: Exception) { null })
        qr.visibility = View.VISIBLE
        conferir()
    }

    private fun conferir() {
        if (geradoEm == 0L) return
        val c = PainelWeb.codigoEntrada
        if (c.usadoEm >= geradoEm) {
            terminou = true
            qr.visibility = View.INVISIBLE
            titulo.text = "Pronto"
            texto.text = "O celular entrou no painel."
            ui.postDelayed({ if (!isDestroyed && terminou) finish() }, 2500)
            return
        }
        if (!c.ativo()) {
            geradoEm = 0L
            qr.visibility = View.INVISIBLE
            texto.text = "O código venceu (vale 2 min).\nToque em \"Gerar outro\" para mostrar um novo."
            outro.requestFocus()
            return
        }
        val falta = ((geradoEm + CodigoEntrada.VALIDADE_MS - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        texto.text = "Aponte a câmera do celular para o código ao lado. O celular abre o painel e já entra, sem digitar o PIN.\n\n" +
            "O celular precisa estar no Wi-Fi da casa. O código vale uma vez só, por mais ${falta / 60}:${"%02d".format(falta % 60)}.\n" +
            "Instalar, desinstalar e restaurar backup continuam pedindo o PIN."
    }

    /** Botão padrão do sistema de design (pílula; foco = branco). */
    private fun botao(rotulo: String, acao: () -> Unit) = Estilo.botao(this, rotulo).apply { setOnClickListener { acao() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

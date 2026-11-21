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
import br.paulo.tvlauncher.panel.CodigoEntrada
import br.paulo.tvlauncher.panel.DestinoPainel
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.panel.QrCodigo

/**
 * "Entrar pelo celular" (10/11/2026, pedido do dono): a TV mostra um QR com o endereço do painel e um código de uso
 * único (PainelWeb.linkEntradaQr / CodigoEntrada). O celular lê, abre o painel e já entra, sem digitar o PIN.
 *
 * Por que é seguro: o código vale 2 min e uma vez só, é aleatório (192 bits) e só funciona pela rede da casa (o painel
 * não aceita conexão de fora). A sessão que ele cria não é "elevada": instalar, desinstalar e restaurar backup ainda
 * pedem o PIN. Ao sair desta tela, o código é anulado (um QR esquecido não fica valendo).
 *
 * Confere a cada 1 s: o celular entrou -> avisa e fecha; venceu -> some o QR e oferece gerar outro.
 *
 * TV sem PIN (ideia 97, 14/11/2026): a mesma tela vira "Configurar pelo celular". O QR leva a `#primeira=<código>` e o
 * celular abre o assistente de 5 passos, que começa criando o PIN ([PrimeiraConfig]). A porta do painel só fica aberta
 * sem PIN enquanto esta tela está aberta.
 */
class EntrarPeloQrActivity : Activity() {
    companion object {
        /** Ideia 43: nome de um [DestinoPainel]; o QR entra e já abre aquela página do painel. */
        const val EXTRA_DESTINO = "destino"
        /** Título da tela quando abre por um destino ("Casa pelo celular"). */
        const val EXTRA_TITULO = "titulo"
    }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    private lateinit var outro: TextView
    /** Elevação 19.7: TV sem PIN pode, em vez de configurar do zero, trazer tudo de outra TV. */
    private lateinit var deOutra: TextView
    private var geradoEm = 0L       // quando o código atual foi gerado (0 = nenhum)
    private var terminou = false
    /** TV sem PIN: assistente da primeira configuração em vez de "Entrar pelo celular". Decidido a cada gerar(). */
    private var primeira = false
    private val codigo: CodigoEntrada get() = if (primeira) PainelWeb.codigoPrimeira else PainelWeb.codigoEntrada

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
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
        }
        // O botão com foco cresce um pouco (Estilo.botao): sem isto a borda esquerda dele saía cortada.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        raiz.clipChildren = false; raiz.clipToPadding = false
        titulo = Estilo.tituloTela(this, "")   // título e texto nos papéis do sistema de design
        texto = Estilo.texto(this, Estilo.Papel.CORPO).apply { setTextColor(Estilo.TEXTO_2); setPaddingRelative(0, dp(16), dp(32), 0); setLineSpacing(0f, 1.2f) }
        col.addView(titulo); col.addView(texto)
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        outro = botao(getString(R.string.qr_gerar_outro)) { gerar() }
        botoes.addView(outro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        val fechar = botao(getString(R.string.qr_fechar)) { finish() }
        botoes.addView(fechar)
        deOutra = botao(getString(R.string.mtv_titulo)) {
            startActivity(android.content.Intent(this, ReceberTvActivity::class.java)); finish()
        }.apply { visibility = View.GONE }
        botoes.addView(deOutra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
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
        if (primeira) PainelWeb.sairPrimeiraConfig(this)   // e, sem PIN criado, a porta do painel fecha de novo
        super.onPause()
    }

    private fun gerar() {
        terminou = false
        primeira = !PainelArmazem.temPin(this)
        deOutra.visibility = if (primeira) View.VISIBLE else View.GONE
        val link = if (primeira) PainelWeb.linkPrimeiraConfig(this)
            else PainelWeb.linkEntradaQr(DestinoPainel.porNome(intent.getStringExtra(EXTRA_DESTINO)))
        titulo.text = if (primeira) getString(R.string.pc_titulo)
            else intent.getStringExtra(EXTRA_TITULO) ?: getString(R.string.lbl_entrar_celular)
        if (link == null) {
            geradoEm = 0L
            qr.visibility = View.INVISIBLE
            texto.text = getString(if (primeira) R.string.pc_sem_rede else R.string.qr_painel_fora)
            // Sem rede não há código para "gerar outro": o botão diz o que de fato faz (crítica de design 19/11/2026).
            outro.text = getString(R.string.tentar_de_novo)
            return
        }
        outro.text = getString(R.string.qr_gerar_outro)
        geradoEm = System.currentTimeMillis()
        qr.setImageBitmap(try { QrCodigo.bitmap(link, dp(320)) } catch (e: Exception) { null })
        qr.visibility = View.VISIBLE
        conferir()
    }

    private fun conferir() {
        if (geradoEm == 0L) return
        val c = codigo
        if (c.usadoEm >= geradoEm) {
            terminou = true
            qr.visibility = View.INVISIBLE
            titulo.text = getString(R.string.qr_pronto)
            texto.text = getString(if (primeira) R.string.pc_entrou else R.string.qr_entrou)
            ui.postDelayed({ if (!isDestroyed && terminou) finish() }, 2500)
            return
        }
        if (!c.ativo()) {
            geradoEm = 0L
            qr.visibility = View.INVISIBLE
            texto.text = if (primeira) getString(R.string.pc_venceu, getString(R.string.qr_gerar_outro))
                else getString(R.string.qr_venceu, getString(R.string.qr_gerar_outro))
            outro.requestFocus()
            return
        }
        val falta = ((geradoEm + c.validadeMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val tempo = "${falta / 60}:${"%02d".format(falta % 60)}"
        texto.text = if (primeira) getString(R.string.pc_instrucoes, tempo) else getString(R.string.qr_instrucoes, tempo)
    }

    /** Botão padrão do sistema de design (pílula; foco = branco). */
    private fun botao(rotulo: String, acao: () -> Unit) = Estilo.botao(this, rotulo).apply { setOnClickListener { acao() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

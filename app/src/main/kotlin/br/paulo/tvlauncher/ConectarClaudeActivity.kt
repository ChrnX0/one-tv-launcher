package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Claude pela internet": a tela que guia o passo a passo com QR codes (05/11/2026, pedido do dono: sem chave, sem console).
 *
 *  1. Falta o PIN do painel -> pede para definir (o PIN é o login do Claude).
 *  2. Liga o MCP e o túnel.
 *  3. Falta entrar no Tailscale -> QR do login (o celular abre; "Sign in with Google" com a mesma conta da TV).
 *  4. Conta nova sem HTTPS/Funnel -> QR da página do Tailscale que liga os dois.
 *  5. No ar -> QR e texto do endereço do MCP, e como colar no Claude, ChatGPT, Codex etc.
 *
 * Confere o estado a cada 2 s enquanto está aberta. BACK sai; nada fica preso aqui.
 */
class ConectarClaudeActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    private var qrAtual: String? = null

    private val volta = object : Runnable {
        override fun run() { atualizar(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Abrir esta tela é pedir para conectar: liga o MCP (se já tem PIN). Desligar fica nas Configurações.
        if (PainelArmazem.temPin(this) && !McpServidor.ligado(this)) McpServidor.definirLigado(this, true)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Sistema de design: título de tela e texto de explicação (Estilo), sem cor nem fonte soltas.
        titulo = Estilo.tituloTela(this, "")
        texto = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { textSize = 16f; setPadding(0, dp(Estilo.E2), dp(32), 0); setLineSpacing(0f, 1.25f) }
        col.addView(titulo); col.addView(texto)
        val sair = Estilo.botao(this, getString(R.string.qr_fechar)).apply { setOnClickListener { finish() } }
        col.addView(sair, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        raiz.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(320), dp(320)))
        setContentView(raiz)
        sair.requestFocus()
    }

    override fun onResume() { super.onResume(); ui.post(volta) }
    override fun onPause() { ui.removeCallbacks(volta); super.onPause() }

    private fun atualizar() {
        val e = Passo.agora(this)
        titulo.text = e.titulo
        texto.text = e.texto
        if (e.qr != qrAtual) {
            qrAtual = e.qr
            qr.setImageBitmap(e.qr?.let { try { QrCodigo.bitmap(it, dp(320)) } catch (x: Exception) { null } })
            qr.visibility = if (e.qr == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    /** Em que passo estamos. Também usado pelo painel (aba Claude) e pelas Configurações. */
    class Passo(val id: String, val titulo: String, val texto: String, val qr: String?) {
        companion object {
            fun agora(ctx: android.content.Context): Passo {
                val conta = Perfil.contas(ctx).firstOrNull()
                val dicaConta = conta?.let { "\n" + ctx.getString(R.string.cc_dica_conta, it) } ?: ""
                return when {
                    !PainelArmazem.temPin(ctx) -> Passo("pin", ctx.getString(R.string.cc_pin), ctx.getString(R.string.cc_pin_det), null)
                    !TunelTailscale.binario(ctx).exists() -> Passo("sem_tunel", ctx.getString(R.string.cc_sem_tunel), ctx.getString(R.string.cc_sem_tunel_det), null)
                    !McpServidor.ligado(ctx) -> Passo("desligado", ctx.getString(R.string.cc_desligado), ctx.getString(R.string.cc_desligado_det), null)
                    TunelTailscale.urlLogin != null -> Passo("login", ctx.getString(R.string.cc_login), ctx.getString(R.string.cc_login_det, dicaConta),
                        TunelTailscale.urlLogin)
                    TunelTailscale.urlAtivar != null -> Passo("ativar", ctx.getString(R.string.cc_ativar), ctx.getString(R.string.cc_ativar_det), TunelTailscale.urlAtivar)
                    TunelTailscale.rodando() && TunelTailscale.nome(ctx) != null -> {
                        val url = "https://${TunelTailscale.nome(ctx)}/mcp"
                        Passo("pronto", ctx.getString(R.string.cc_pronto),
                            ctx.getString(R.string.cc_pronto_det, url) + "\n\n" +
                                // Com o login Google ligado (LoginGoogle), o PIN não vale mais no login do conector (11/11/2026).
                                ctx.getString(if (LoginGoogle.configurado(ctx)) R.string.cc_pede_google else R.string.cc_pede_pin) + " " +
                                ctx.getString(if (ConfirmacoesTv.modo(ctx, Confirmacoes.Acao.MCP_ACOES) == Confirmacoes.Modo.PERGUNTAR) R.string.cc_acoes_ok else R.string.cc_acoes_na_hora) +
                                (TunelTailscale.conta(ctx)?.let { "\n" + ctx.getString(R.string.cc_conta_ts, it) } ?: ""), url)
                    }
                    else -> Passo("subindo", ctx.getString(R.string.cc_conectando),
                        ctx.getString(R.string.cc_conectando_det) + (TunelTailscale.ultimoErro(ctx)?.let { "\n\n" + ctx.getString(R.string.cc_ultimo_erro, it) } ?: ""), null)
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    /** Superfície padrão do sistema de design (Estilo.superficie); o foco é aplicado por Estilo.focarLinha. */
    private fun vidro(focado: Boolean): GradientDrawable = Estilo.superficie(this, focado)
}

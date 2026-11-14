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
 * "Buscar foto da conta" (Configurações do launcher > Conta): põe a foto da conta Google na bolinha do perfil.
 *
 * É o mesmo login de TV do [LoginGoogle]: a tela mostra um código, a pessoa abre google.com/device no celular, entra
 * e digita. O Google então diz à TV qual conta entrou e o endereço da foto; a TV baixa e guarda ([FotoPerfil]).
 * Este login serve SÓ para a foto: não libera o painel nem o conector (a sessão fica fora da lista do LoginGoogle).
 *
 * Sem o login Google configurado (cada dono cria o seu no Google Cloud), a tela só explica o outro caminho: enviar a
 * foto pelo painel web. BACK sai a qualquer momento e cancela a espera.
 *
 * Com [EXTRA_DRIVE] (13/11/2026) a mesma tela conecta a "Conta do Google Drive" do backup noturno: o login pede também
 * a permissão do Drive e guarda a conta (DriveBackup). Os passos no celular são os mesmos.
 */
class FotoPerfilActivity : Activity() {
    companion object { const val EXTRA_DRIVE = "drive" }
    private val drive by lazy { intent.getBooleanExtra(EXTRA_DRIVE, false) }
    private val finalidade get() = if (drive) LoginGoogle.Finalidade.DRIVE else LoginGoogle.Finalidade.FOTO
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    @Volatile private var sessao: LoginGoogle.Sessao? = null
    private var terminou = false

    /** Olha a cada 2 s se o Google já respondeu (quem pergunta ao Google é a thread do LoginGoogle). */
    private val volta = object : Runnable {
        override fun run() { atualizar(); if (!terminou) ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Estilo.tituloTela(this, getString(if (drive) R.string.drv_titulo else R.string.fp_titulo)))
        texto = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { textSize = 16f; setPadding(0, dp(Estilo.E2), dp(32), 0); setLineSpacing(0f, 1.25f) }
        col.addView(texto)
        val sair = Estilo.botao(this, getString(R.string.fp_fechar)).apply { setOnClickListener { finish() } }
        col.addView(sair, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        raiz.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.INVISIBLE }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
        setContentView(raiz)
        sair.requestFocus()

        if (!LoginGoogle.configurado(this)) { terminou = true; texto.text = getString(if (drive) R.string.drv_sem_google else R.string.fp_sem_google); return }
        texto.text = getString(R.string.fp_pedindo)
        // Pedir o código é uma chamada de rede: o Android proíbe rede na thread da tela, então vai numa thread à parte.
        Thread({
            val r = runCatching { LoginGoogle.iniciar(applicationContext, emptyMap(), finalidade) }
            ui.post {
                if (isDestroyed) { r.getOrNull()?.let { cancelar(it) }; return@post }
                val s = r.getOrNull()
                if (s == null) { terminou = true; texto.text = getString(R.string.fp_falha, r.exceptionOrNull()?.message ?: "?"); return@post }
                sessao = s
                qr.setImageBitmap(runCatching { QrCodigo.bitmap(s.urlVerificacao, dp(280)) }.getOrNull())
                qr.visibility = View.VISIBLE
                ui.post(volta)
            }
        }, "FotoPerfil").apply { isDaemon = true; start() }
    }

    private fun atualizar() {
        val s = sessao ?: return
        when (s.estado) {
            LoginGoogle.Estado.ESPERANDO -> texto.text = getString(if (drive) R.string.drv_passos else R.string.fp_passos, s.urlVerificacao, s.codigoUsuario)
            LoginGoogle.Estado.OK -> if (drive) fim(getString(R.string.drv_ok, s.email ?: "")) else fim(getString(if (s.fotoGravada) R.string.fp_ok else R.string.fp_sem_foto, s.email ?: ""))
            else -> fim(getString(R.string.fp_falha, s.mensagem))
        }
    }

    private fun fim(mensagem: String) { terminou = true; texto.text = mensagem; qr.visibility = View.INVISIBLE }

    /** Marca a sessão como encerrada: a thread do LoginGoogle para de perguntar ao Google. */
    private fun cancelar(s: LoginGoogle.Sessao) {
        if (s.estado == LoginGoogle.Estado.ESPERANDO) s.estado = LoginGoogle.Estado.ERRO
    }

    override fun onDestroy() {
        ui.removeCallbacks(volta)
        sessao?.let { cancelar(it) }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

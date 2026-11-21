package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.network.WifiVisita
import br.paulo.tvlauncher.panel.QrCodigo

/**
 * Boas-vindas do modo hóspede (degrau 2, 16/11/2026). Aparece uma vez por estadia, na primeira HOME depois de ligar o
 * modo ou de encerrar a estadia anterior (ModoHospede.talvezBoasVindas). À esquerda, as regras da casa que o dono
 * escreveu e o aviso de que os logins são apagados na saída; à direita, o QR do Wi-Fi das visitas (se configurado).
 * Letra grande (lida do sofá). OK ou BACK fecham.
 */
class HospedeBoasVindasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(Estilo.tituloTela(this, getString(R.string.hos_bv_titulo)))
        val regras = ModoHospede.regras(this).trim()
        if (regras.isNotEmpty()) {
            texto.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.hos_bv_regras)),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
            texto.addView(Estilo.texto(this, Estilo.Papel.CORPO, regras).apply { textSize = 22f; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        texto.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.hos_bv_privacidade)).apply { textSize = 18f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val ok = Estilo.linhaOpcao(this, getString(R.string.hos_bv_ok)).apply { setOnClickListener { finish() } }
        texto.addView(ok, LinearLayout.LayoutParams(dp(260), -2).apply { topMargin = dp(24) })
        raiz.addView(texto, LinearLayout.LayoutParams(0, -2, 1f))

        val qr = WifiVisita.textoQr(this)
        if (qr != null) {
            val lado = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            coluna.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.hos_bv_wifi)).apply { gravity = Gravity.CENTER })
            coluna.addView(ImageView(this).apply { setImageBitmap(QrCodigo.bitmap(qr, lado)) },
                LinearLayout.LayoutParams(lado, lado).apply { topMargin = dp(12) })
            val senha = WifiVisita.senhaVisivel(this)
            coluna.addView(Estilo.texto(this, Estilo.Papel.CORPO,
                WifiVisita.rede(this) + if (senha.isNotEmpty()) "\n" + getString(R.string.l51_wifi_senha) + ": " + senha else "").apply {
                gravity = Gravity.CENTER; textSize = 20f
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            raiz.addView(coluna, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(40) })
        }
        setContentView(raiz)
        ok.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Ideia 54: o Wi-Fi das visitas (rede e senha digitadas uma vez pelo dono) e a caixa com o QR. Guardado nas prefs do
 * modo visita ("modo_visita"), que vão no backup completo cifrado e NÃO no backup comum em JSON aberto.
 * Onde aparece: Configurações > Visitas (configurar e mostrar) e, com o modo visita ligado, o OK no Wi-Fi do painel
 * rápido mostra o QR em vez de abrir as configurações de rede (visita não precisa mexer no Wi-Fi da TV).
 */
object WifiVisita {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("modo_visita", Context.MODE_PRIVATE)

    fun rede(ctx: Context): String = prefs(ctx).getString("wifi_rede", "") ?: ""
    private fun senha(ctx: Context): String = prefs(ctx).getString("wifi_senha", "") ?: ""
    fun configurado(ctx: Context): Boolean = rede(ctx).isNotEmpty()

    /** Rede vazia apaga. False se os valores não servem (WifiVisitaQr.valido). */
    fun definir(ctx: Context, rede: String, senha: String): Boolean {
        if (rede.isBlank()) { prefs(ctx).edit().remove("wifi_rede").remove("wifi_senha").apply(); return true }
        if (!WifiVisitaQr.valido(rede, senha)) return false
        prefs(ctx).edit().putString("wifi_rede", rede).putString("wifi_senha", senha).apply()
        return true
    }

    /** Caixa com rede e senha (teclado da TV). [fim] recebe true se gravou. */
    fun pedir(a: Activity, fim: (Boolean) -> Unit) {
        fun campo(dica: Int, valor: String, senha: Boolean) = EditText(a).apply {
            hint = a.getString(dica); setText(valor); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or (if (senha) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else 0)
            filters = arrayOf(android.text.InputFilter.LengthFilter(WifiVisitaQr.MAX))
        }
        val r = campo(R.string.l51_wifi_rede, rede(a), false)
        val s = campo(R.string.l51_wifi_senha, senha(a), true)
        val caixa = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0); addView(r); addView(s) }
        CaixaTv(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(a.getString(R.string.l51_wifi_titulo))
            .setMessage(a.getString(R.string.l51_wifi_dica))
            .setView(caixa)
            .setPositiveButton("OK") { _, _ ->
                val ok = definir(a, r.text.toString().trim(), s.text.toString())
                if (!ok) AvisoTv.rapido(a, a.getString(R.string.l51_wifi_titulo), a.getString(R.string.l51_wifi_invalido), 5)
                fim(ok)
            }
            .setNegativeButton(a.getString(R.string.cl_cancelar), null)
            .show()
        r.requestFocus()
    }

    /** A caixa com o QR, o nome da rede e a senha escrita (para quem não tem câmera). */
    fun mostrarQr(a: Activity) {
        if (!configurado(a)) return
        val lado = (a.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val img = ImageView(a).apply { setImageBitmap(QrCodigo.bitmap(WifiVisitaQr.texto(rede(a), senha(a)), lado)) }
        val legenda = Estilo.texto(a, Estilo.Papel.CORPO,
            rede(a) + (senha(a).takeIf { it.isNotEmpty() }?.let { "\n" + a.getString(R.string.l51_wifi_senha) + ": " + it } ?: ""))
            .apply { gravity = Gravity.CENTER }
        val caixa = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 8, 24, 0)
            addView(img, LinearLayout.LayoutParams(lado, lado)); addView(legenda)
        }
        CaixaTv(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(a.getString(R.string.l51_wifi_qr_titulo))
            .setView(caixa)
            .setPositiveButton("OK", null)
            .show()
    }
}

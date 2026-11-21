package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Fundo
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.PinNaTela
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.panel.QrCodigo

/**
 * "Adicionar add-on" na própria TV (18/11/2026, pedido do Paulo: "não encontrei a parte de instalar add-ons").
 * Antes só o painel web instalava, e na TV nada indicava isso. Agora a caixa mostra:
 *  - um QR que abre o painel já no cartão Addons (`#addons`), para colar o endereço no celular (digitar no controle é ruim);
 *  - "Digitar endereço", para quem prefere o teclado da TV ou o microfone do controle.
 * Pela TV valem as mesmas travas do painel: PIN de novo, aceite dos termos e as recusas de [AddonsProtocolo]
 * (torrent/P2P, adulto, precisa configurar).
 */
object AddonInstallDialog {

    /** [aoMudar] roda na thread da tela quando um addon entrou (para a tela atualizar a contagem/lista). */
    fun mostrar(tela: Activity, aoMudar: () -> Unit) {
        fun dp(v: Int) = (v * tela.resources.displayMetrics.density).toInt()
        val corpo = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        corpo.addView(Estilo.texto(tela, Estilo.Papel.LEGENDA, tela.getString(R.string.ad_add_texto)).apply {
            textSize = Estilo.Leitura.ITEM_DETALHE; setLineSpacing(0f, 1.15f)
        })
        // Endereço do painel: null quando o painel está desligado ou sem rede; aí só sobra digitar.
        PainelWeb.endereco()?.let { end ->
            val qr = runCatching { QrCodigo.bitmap(end + "#addons", dp(200)) }.getOrNull()
            if (qr != null) corpo.addView(ImageView(tela).apply { setImageBitmap(qr) },
                LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(12) })
            corpo.addView(Estilo.texto(tela, Estilo.Papel.LEGENDA, tela.getString(R.string.ad_add_qr, end.removePrefix("http://").trimEnd('/'))).apply {
                textSize = Estilo.Leitura.ITEM_DETALHE; gravity = Gravity.CENTER
            })
        } ?: corpo.addView(Estilo.texto(tela, Estilo.Papel.LEGENDA, tela.getString(R.string.ad_add_sem_painel)).apply {
            textSize = Estilo.Leitura.ITEM_DETALHE
        })
        CaixaTv(tela).setTitle(tela.getString(R.string.ad_add_titulo)).setView(corpo)
            .setPositiveButton(tela.getString(R.string.ad_add_digitar)) { _, _ ->
                PinNaTela.pedir(tela, tela.getString(R.string.ad_add_titulo)) { termos(tela) { campo(tela, null, "", aoMudar) } }
            }
            .setNegativeButton(tela.getString(R.string.sv_fechar), null)
            .show()
    }

    /** Um add-on da lista curada: PIN, termos e instala direto (sem digitar nada). */
    fun instalarSugerido(tela: Activity, s: AddonsTv.Sugerido, aoMudar: () -> Unit) {
        PinNaTela.pedir(tela, s.nome) { termos(tela) { instalar(tela, s.e.url, aoMudar) { msg -> aviso(tela, s.nome, msg) } } }
    }

    /** Termos uma vez por TV (o mesmo aceite do painel, prefs `addons`); depois [seguir]. */
    private fun termos(tela: Activity, seguir: () -> Unit) {
        if (AddonsTv.aceitou(tela)) { seguir(); return }
        CaixaTv(tela).setTitle(tela.getString(R.string.ad_add_termos_titulo)).setMessage(tela.getString(R.string.ad_add_termos))
            .setPositiveButton(tela.getString(R.string.ad_add_aceito)) { _, _ -> AddonsTv.aceitar(tela); seguir() }
            .setNegativeButton(tela.getString(R.string.sv_cancelar), null)
            .show()
    }

    private fun aviso(tela: Activity, titulo: String, msg: String) =
        CaixaTv(tela).setTitle(titulo).setMessage(msg).setPositiveButton(tela.getString(android.R.string.ok), null).show()

    /** Instala fora da thread da tela; [falhou] recebe o motivo já em texto. */
    private fun instalar(tela: Activity, url: String, aoMudar: () -> Unit, falhou: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        Fundo.rodar("addon-instalar") {
            val r = AddonsTv.instalar(tela, url)
            ui.post {
                if (tela.isDestroyed) return@post
                if (r is AddonsTv.Resultado.Ok) { aoMudar(); aviso(tela, r.i.m.nome, tela.getString(R.string.ad_add_ok, r.i.m.nome)) }
                else falhou(motivo(tela, r))
            }
        }
    }

    private fun campo(tela: Activity, erro: String?, antes: String, aoMudar: () -> Unit) {
        val edit = EditText(tela).apply {
            hint = "https://…/manifest.json"; setSingleLine(); setText(antes); textSize = Estilo.Leitura.ITEM
            // URI: o teclado mostra "/" e ".com"; sem sugestões (endereço não é palavra).
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        }
        val caixa = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL; setPaddingRelative(Estilo.dp(context, 24), Estilo.dp(context, 8), Estilo.dp(context, 24), 0)
            if (erro != null) addView(Estilo.texto(tela, Estilo.Papel.LEGENDA, erro).apply {
                textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(Estilo.ERRO)
            })
            addView(edit)
        }
        CaixaTv(tela).setTitle(tela.getString(R.string.ad_add_titulo)).setView(caixa)
            .setPositiveButton(tela.getString(R.string.ad_add_instalar)) { _, _ ->
                val url = edit.text.toString().trim()
                instalar(tela, url, aoMudar) { msg -> campo(tela, msg, url, aoMudar) }
            }
            .setNegativeButton(tela.getString(R.string.sv_cancelar), null)
            .show()
        edit.requestFocus()
    }

    /** Mesmos motivos do painel (addons.js MOTIVO_ADDON), em texto da TV. */
    fun motivo(tela: Activity, r: AddonsTv.Resultado): String = tela.getString(when (r) {
        is AddonsTv.Resultado.Recusado -> when (r.motivo) {
            AddonsProtocolo.Motivo.P2P -> R.string.ad_recusa_p2p
            AddonsProtocolo.Motivo.ADULTO -> R.string.ad_recusa_adulto
            AddonsProtocolo.Motivo.PRECISA_CONFIGURAR -> R.string.ad_recusa_configurar
            AddonsProtocolo.Motivo.SEM_RECURSO_UTIL -> R.string.ad_recusa_sem_recurso
            AddonsProtocolo.Motivo.INVALIDO -> R.string.ad_recusa_invalido
            AddonsProtocolo.Motivo.ENDERECO -> R.string.ad_recusa_endereco
        }
        AddonsTv.Resultado.Cheio -> R.string.ad_recusa_cheio
        AddonsTv.Resultado.SemRede -> R.string.ad_recusa_sem_rede
        AddonsTv.Resultado.SemAceite -> R.string.ad_recusa_sem_aceite
        is AddonsTv.Resultado.Ok -> R.string.ad_nada
    })
}

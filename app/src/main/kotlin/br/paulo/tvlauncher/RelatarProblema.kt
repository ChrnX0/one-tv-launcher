package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import java.net.URLEncoder

/**
 * Relatar um problema de dentro do app (degrau 3, 16/11/2026). Configurações › Sistema › "Relatar um problema" mostra
 * um QR: o celular abre o formulário de "issue" do repositório público já preenchido com os dados TÉCNICOS (versão,
 * Android, modelo, uso da TV, quedas da semana e o tipo do último erro). Nada de nome, conta, rede, IP ou cidade, e a
 * tela mostra o texto inteiro que vai junto, antes de a pessoa enviar (ela ainda escreve o que aconteceu).
 * A montagem do texto e do link é pura (RelatarProblemaTest).
 */
object RelatarProblema {
    data class Dados(val versao: String, val codigo: Long, val android: String, val modelo: String, val uso: String,
                     val quedas7d: Int, val travadas7d: Int, val ultimoErro: String?)

    /** O que vai junto, linha por linha (a TV mostra exatamente isto). */
    fun corpo(d: Dados): String = buildString {
        append("**What happened / O que aconteceu:**\n\n\n\n---\n")
        append("- version: ${d.versao} (${d.codigo})\n")
        append("- Android: ${d.android} · ${d.modelo}\n")
        append("- use: ${d.uso}\n")
        append("- crashes/freezes 7d: ${d.quedas7d}/${d.travadas7d}\n")
        if (!d.ultimoErro.isNullOrBlank()) append("- last error: ${tipoDoErro(d.ultimoErro)}\n")
    }

    /**
     * Do resumo de um erro guardado (RegistroErros), só o TIPO (ex.: "IllegalStateException"): a mensagem do erro pode
     * trazer nome de arquivo, endereço ou texto digitado, então não vai.
     */
    fun tipoDoErro(resumo: String): String =
        Regex("""([A-Za-z_$][\w$]*(?:Exception|Error))""").find(resumo)?.groupValues?.get(1) ?: "?"

    fun link(repo: String, d: Dados): String {
        fun c(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")
        return "https://github.com/$repo/issues/new?title=${c("[TV] ")}&labels=tv&body=${c(corpo(d))}"
    }

    fun dados(ctx: Context): Dados {
        val v = VitaisTv.resumo7(ctx)
        return Dados(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.RELEASE ?: "?", Build.MODEL ?: "?",
            UsoTv.atual(ctx).name.lowercase(), v.quedas, v.travadas, RegistroErros.listar(ctx).firstOrNull { it.daVersao(BuildConfig.VERSION_CODE.toLong()) }?.resumo)
    }
}

class RelatarProblemaActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = RelatarProblema.dados(this)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(Estilo.tituloTela(this, getString(R.string.rel_titulo)))
        texto.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.rel_texto)).apply { textSize = 20f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        texto.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.rel_vai_junto)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        // O que vai junto, sem o cabeçalho em branco para a pessoa escrever.
        texto.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, RelatarProblema.corpo(d).substringAfter("---\n").trim()).apply { textSize = 17f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val ok = Estilo.linhaOpcao(this, getString(R.string.fp_fechar)).apply { setOnClickListener { finish() } }   // era "Começar" (texto das boas-vindas do hóspede)
        texto.addView(ok, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(20) })
        raiz.addView(texto, LinearLayout.LayoutParams(0, -2, 1f))
        val lado = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        raiz.addView(ImageView(this).apply { setImageBitmap(QrCodigo.bitmap(RelatarProblema.link(Config.ATUALIZACAO_REPO, d), lado)) },
            LinearLayout.LayoutParams(lado, lado).apply { marginStart = dp(40) })
        setContentView(raiz)
        ok.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

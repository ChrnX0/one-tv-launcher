package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.family.UsoTv
import br.paulo.tvlauncher.observer.RegistroErros
import br.paulo.tvlauncher.observer.VitaisTv
import br.paulo.tvlauncher.panel.QrCodigo
import java.net.URLEncoder

/**
 * Relatar um problema de dentro do app (degrau 3, 16/11/2026). Configurações › Sistema › "Relatar um problema" mostra
 * um QR: o celular abre o formulário de "issue" do repositório público já preenchido com os dados TÉCNICOS (versão,
 * Android, modelo, uso da TV, quedas da semana e o tipo do último erro). Nada de nome, conta, rede, IP ou cidade, e a
 * tela mostra o texto inteiro que vai junto, antes de a pessoa enviar (ela ainda escreve o que aconteceu).
 * A montagem do texto e do link é pura (RelatarProblemaTest).
 *
 * 17/11/2026 (revisão dos degraus, item 2a): quem não é técnico não tem conta no GitHub, e o repositório de código vai
 * fechar. Com um e-mail de suporte no build (BuildConfig.SUPORTE_EMAIL), o QR vira um "mailto:" que abre o app de
 * e-mail do celular já preenchido; sem ele, continua o formulário do GitHub. Quando o servidor da Sofaly existir, o
 * formulário sem conta entra aqui como terceira opção, na frente das duas.
 */
object RelatarProblema {
    data class Dados(val versao: String, val codigo: Long, val android: String, val modelo: String, val uso: String,
                     val quedas7d: Int, val travadas7d: Int, val ultimoErro: String?)

    /** Os nomes das linhas, no idioma da TV (21/11/2026: a tela mostrava "version, use, crashes" em inglês). */
    data class Rotulos(val oQue: String = "What happened", val versao: String = "Version", val uso: String = "Mode",
                       val quedas: String = "Crashes / freezes (7 days)", val erro: String = "Last error")

    fun rotulos(ctx: Context) = Rotulos(ctx.getString(R.string.rel_l_oque), ctx.getString(R.string.rel_l_versao),
        ctx.getString(R.string.rel_l_uso), ctx.getString(R.string.rel_l_quedas), ctx.getString(R.string.rel_l_erro))

    /** O que vai junto, linha por linha (a TV mostra exatamente isto, e o QR leva o mesmo texto). */
    fun corpo(d: Dados, r: Rotulos = Rotulos()): String = buildString {
        append("**${r.oQue}:**\n\n\n\n---\n")
        append("- ${r.versao}: ${d.versao} (${d.codigo})\n")
        append("- Android: ${d.android} · ${d.modelo}\n")
        append("- ${r.uso}: ${d.uso}\n")
        append("- ${r.quedas}: ${d.quedas7d}/${d.travadas7d}\n")
        if (!d.ultimoErro.isNullOrBlank()) append("- ${r.erro}: ${tipoDoErro(d.ultimoErro)}\n")
    }

    /**
     * Do resumo de um erro guardado (RegistroErros), só o TIPO (ex.: "IllegalStateException"): a mensagem do erro pode
     * trazer nome de arquivo, endereço ou texto digitado, então não vai.
     */
    fun tipoDoErro(resumo: String): String =
        Regex("""([A-Za-z_$][\w$]*(?:Exception|Error))""").find(resumo)?.groupValues?.get(1) ?: "?"

    private fun c(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    fun link(repo: String, d: Dados, r: Rotulos = Rotulos()): String =
        "https://github.com/$repo/issues/new?title=${c("[TV] ")}&labels=tv&body=${c(corpo(d, r))}"

    /** E-mail plausível (um @, domínio com ponto, sem espaço nem caractere que quebre o mailto). */
    fun emailValido(e: String) = Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$""").matches(e.trim())

    /** O link do e-mail: assunto com versão e modelo (a caixa de entrada já separa por aparelho), corpo igual ao do GitHub. */
    fun mailto(email: String, d: Dados, r: Rotulos = Rotulos()): String =
        "mailto:${email.trim()}?subject=${c("[TV] ${d.modelo} · ${d.codigo}")}&body=${c(corpo(d, r).replace("**", ""))}"

    /** Qual link vai no QR: e-mail se houver um válido no build; senão o GitHub. */
    fun destino(email: String, repo: String, d: Dados, r: Rotulos = Rotulos()): String =
        if (emailValido(email)) mailto(email, d, r) else link(repo, d, r)

    fun dados(ctx: Context): Dados {
        val v = VitaisTv.resumo7(ctx)
        return Dados(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.RELEASE ?: "?", Build.MODEL ?: "?",
            ctx.getString(when (UsoTv.atual(ctx)) {
                UsoTv.SIMPLES -> R.string.uso_simples; UsoTv.COMPLETO -> R.string.uso_completo; UsoTv.CUIDA -> R.string.uso_cuida
            }), v.quedas, v.travadas, RegistroErros.listar(ctx).firstOrNull { it.daVersao(BuildConfig.VERSION_CODE.toLong()) }?.resumo)
    }
}

class RelatarProblemaActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = RelatarProblema.dados(this)
        val r = RelatarProblema.rotulos(this)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val texto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(Estilo.tituloTela(this, getString(R.string.rel_titulo)))
        texto.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.rel_texto)).apply { textSize = 20f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        texto.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.rel_vai_junto)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        // O que vai junto, sem o cabeçalho em branco para a pessoa escrever.
        texto.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, RelatarProblema.corpo(d, r).substringAfter("---\n").trim()).apply { textSize = 17f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val ok = Estilo.linhaOpcao(this, getString(R.string.fp_fechar)).apply { setOnClickListener { finish() } }   // era "Começar" (texto das boas-vindas do hóspede)
        texto.addView(ok, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(20) })
        raiz.addView(texto, LinearLayout.LayoutParams(0, -2, 1f))
        val lado = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        raiz.addView(ImageView(this).apply { setImageBitmap(QrCodigo.bitmap(RelatarProblema.destino(BuildConfig.SUPORTE_EMAIL, Config.ATUALIZACAO_REPO, d, r), lado)) },
            LinearLayout.LayoutParams(lado, lado).apply { marginStart = dp(40) })
        setContentView(raiz)
        ok.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package br.paulo.tvlauncher.settings

import android.content.Context
import br.paulo.tvlauncher.home.FundoMeuDados
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.screensaver.WallpaperPlugins

/** A parte do Android do [VisualCodigo]: lê o visual desta TV e aplica um recebido. */
object VisualCodigoTv {
    const val PAGINA = "https://chrnx0.github.io/sofaly/visual.html#"

    fun atual(ctx: Context) = VisualCodigo.Visual(
        tema = Tema.atual(ctx).name, texto = TamanhoTexto.atual(ctx), icones = PacoteIcones.escolhido(ctx),
        corDoFundo = Extras.ligado(ctx, Extras.Chave.COR_DO_FUNDO),
        brilho = FundoMeuDados.brilho(ctx), contraste = FundoMeuDados.contraste(ctx),
    )

    fun codigo(ctx: Context) = VisualCodigo.codificar(atual(ctx))
    fun link(ctx: Context) = PAGINA + codigo(ctx)

    /**
     * Aplica o que veio. "Meu fundo" sem arquivo nesta TV fica no Escuro; pacote de ícones não instalado aqui fica sem.
     * Devolve a lista do que mudou (para a resposta do painel/adb), ou null se o código não é válido.
     */
    fun aplicar(ctx: Context, entrada: String): List<String>? {
        val v = VisualCodigo.decodificar(entrada) ?: return null
        val feito = ArrayList<String>()
        v.tema?.let { t ->
            val tema = Tema.valueOf(t).let { if (it == Tema.MEU && FundoMeuDados.arquivo(ctx) == null || it == Tema.PLUGIN && WallpaperPlugins.escolhidoId(ctx) == null) Tema.ESCURO else it }
            Tema.definir(ctx, tema); feito += "tema=${tema.name}"
        }
        v.texto?.let { TamanhoTexto.definir(ctx, it); feito += "texto=$it" }
        v.icones?.let { p ->
            val ok = p.isEmpty() || PacoteIcones.instalados(ctx).any { it.first == p }
            if (ok) { PacoteIcones.escolher(ctx, p); feito += "icones=${p.ifEmpty { "nenhum" }}" } else feito += "icones: $p não instalado"
        }
        v.corDoFundo?.let { Extras.definir(ctx, Extras.Chave.COR_DO_FUNDO, it); feito += "cor_do_fundo=$it" }
        v.brilho?.let { FundoMeuDados.definirBrilho(ctx, it); feito += "brilho=$it" }
        v.contraste?.let { FundoMeuDados.definirContraste(ctx, it); feito += "contraste=$it" }
        return feito
    }
}

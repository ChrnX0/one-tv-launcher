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
        letra = FontChoice.currentId(ctx),
        fino = CHAVES.mapValues { (_, chave) -> Extras.numero(ctx, chave, padrao(chave)) },
    )

    /** Chave curta do código -> chave das preferências (VisualCodigo.FINO). */
    private val CHAVES = linkedMapOf(
        "hf" to ClockStyle.KEY_FORMAT, "hc" to ClockStyle.KEY_COLOR,
        "ac" to CardStyle.KEY_ACCENT, "co" to CardStyle.KEY_CORNERS, "gl" to CardStyle.KEY_GLOW, "sz" to CardStyle.KEY_SIZE,
        "bt" to BackgroundStyle.KEY_TINT, "bc" to BackgroundStyle.KEY_TINT_COLOR, "bb" to BackgroundStyle.KEY_BLUR, "bf" to BackgroundStyle.KEY_FADE,
    )
    /** O padrão de cada chave (o mesmo de quem a lê): cantos, tamanho e transição começam no meio da lista. */
    private fun padrao(chave: String) = if (chave == CardStyle.KEY_CORNERS || chave == CardStyle.KEY_SIZE || chave == BackgroundStyle.KEY_FADE) 1 else 0

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
        v.fino?.forEach { (k, n) -> CHAVES[k]?.let { Extras.definirNumero(ctx, it, n); feito += "$k=$n" } }
        if (v.fino != null) CardStyle.aplicar(ctx)
        // A letra pode precisar baixar: fica com a de antes até o arquivo chegar (FontChoice.choose).
        v.letra?.let { id -> if (FontChoice.FONTS.any { it.id == id }) { FontChoice.choose(ctx, id) {}; feito += "letra=$id" } }
        return feito
    }
}

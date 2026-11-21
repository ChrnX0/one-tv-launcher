package br.paulo.tvlauncher.settings

import android.content.Context
import android.view.View
import br.paulo.tvlauncher.Estilo

/**
 * Fundo mais rico (21/11/2026, item 2 do plano para passar o Projectivy): cor por cima, cor sólida, desfoque,
 * transição entre imagens e "novo fundo agora".
 *
 * Porquê: o Projectivy tem tintura, desfoque e troca de fundo pelo controle; a Sofaly só tinha brilho/contraste no
 * "Meu fundo". Para quê: deixar qualquer fundo (pintura, céu, plugin, foto) mais calmo ou da cor da casa.
 * Como: tudo sobre a view do fundo que já existe (Fundos.criarFundo), sem tema novo:
 *  - a cor por cima é o "foreground" da view; como o fundo já é camada de hardware, ela entra NA camada (0 custo por
 *    quadro). "Sólida" troca o fundo inteiro por uma cor (o mais leve de todos);
 *  - o desfoque é RenderEffect (Android 12+); mais antigo, a linha nem aparece;
 *  - a transição vale onde o fundo troca de imagem sozinho (plugins e Galeria);
 *  - "novo fundo" pula a pintura do dia (Galeria) ou vai para o próximo item (plugin): [proximoFundo].
 * Liga com: painel do Ajuste fino (FineTuneActivity), MainActivity.assinaturaDoLayout, protetor (mesma pintura da Galeria).
 */
object BackgroundStyle {
    const val KEY_TINT = "bg_tint"
    const val KEY_TINT_COLOR = "bg_tint_color"
    const val KEY_BLUR = "bg_blur"
    const val KEY_FADE = "bg_fade"
    const val KEY_GALLERY_SKIP = "bg_gallery_skip"

    enum class Tint(val alpha: Float) { OFF(0f), SOFT(0.22f), STRONG(0.45f), SOLID(1f) }
    /** null = a cor de destaque escolhida (CardStyle). As outras são escuras de propósito: o texto é claro. Nunca rosa. */
    enum class TintColor(val argb: Int?) {
        ACCENT(null), NIGHT(0xFF0E1A33.toInt()), FOREST(0xFF0F2A24.toInt()), EMBER(0xFF3A1A0C.toInt()), GRAPHITE(0xFF1B1E2E.toInt())
    }
    enum class Blur(val dp: Float) { OFF(0f), SOFT(10f), STRONG(24f) }
    enum class Fade(val ms: Long) { CUT(0L), SOFT(800L), SLOW(2000L) }

    fun tint(ctx: Context) = Tint.entries.getOrElse(Extras.numero(ctx, KEY_TINT, 0)) { Tint.OFF }
    fun tintColor(ctx: Context) = TintColor.entries.getOrElse(Extras.numero(ctx, KEY_TINT_COLOR, 0)) { TintColor.ACCENT }
    fun blur(ctx: Context) = Blur.entries.getOrElse(Extras.numero(ctx, KEY_BLUR, 0)) { Blur.OFF }
    fun fade(ctx: Context) = Fade.entries.getOrElse(Extras.numero(ctx, KEY_FADE, 1)) { Fade.SOFT }
    fun fadeMs(ctx: Context) = fade(ctx).ms
    fun gallerySkip(ctx: Context) = Extras.numero(ctx, KEY_GALLERY_SKIP, 0)

    /** O desfoque só existe no Android 12+ (RenderEffect). */
    val blurPossivel get() = android.os.Build.VERSION.SDK_INT >= 31

    fun next(ctx: Context, key: String, atual: Enum<*>, total: Int, passo: Int): Int =
        Math.floorMod(atual.ordinal + passo, total).also { Extras.definirNumero(ctx, key, it) }

    fun assinatura(ctx: Context) = "${tint(ctx)}|${tintColor(ctx)}|${blur(ctx)}"

    /** A cor da tintura, opaca. Destaque escurecido (a 35%) para o texto claro continuar legível. Puro com [destaque]. */
    fun cor(c: TintColor, destaque: Int): Int = c.argb ?: escurecer(destaque, 0.35f)

    /** [cor] com cada canal multiplicado por [f]. Puro (BackgroundStyleTest). */
    fun escurecer(cor: Int, f: Float): Int {
        fun ch(s: Int) = (((cor shr s) and 0xFF) * f + 0.5f).toInt().coerceIn(0, 255) shl s
        return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
    }

    /** Veste a view do fundo: cor por cima e desfoque. Nada quando está tudo desligado. */
    fun vestir(ctx: Context, fundo: View) {
        val t = tint(ctx)
        if (t != Tint.OFF && t != Tint.SOLID)
            fundo.foreground = android.graphics.drawable.ColorDrawable(Estilo.alfa(cor(tintColor(ctx), Estilo.ACENTO), (t.alpha * 255).toInt()))
        val b = blur(ctx)
        if (b != Blur.OFF && blurPossivel) {
            val r = b.dp * ctx.resources.displayMetrics.density
            fundo.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
        }
    }

    /** O fundo de cor sólida: a cor escolhida com um degradê leve (escurece embaixo, onde ficam os textos da cruz). */
    fun solido(ctx: Context): View = View(ctx).apply {
        val c = cor(tintColor(ctx), Estilo.ACENTO)
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(c, escurecer(c, 0.6f)))
    }

    /** Índice da pintura do dia com os pulos de "novo fundo". Puro. */
    fun indiceObra(diaDoAno: Int, pulos: Int, total: Int) = Math.floorMod(diaDoAno + pulos, total)

    /** A HOME põe aqui quem troca o fundo dela agora (null quando não há HOME). Devolve false se o fundo não troca. */
    @Volatile var proximoFundo: (() -> Boolean)? = null
}

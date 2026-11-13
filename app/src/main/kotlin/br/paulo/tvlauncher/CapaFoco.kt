package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/**
 * "Fundo do foco" da HOME recomendada (esboço V3Final, 06/11/2026): a "capa" de quem está em foco aparece no alto, à
 * direita, atrás do topo, e se dissolve no fundo do tema pela esquerda e por baixo (máscara de transparência, não uma
 * caixa escura: o céu/pintura do tema continua aparecendo em volta).
 *
 * - App: o banner de TV dele (a arte 16:9 que todo app de TV tem para a tela do Google); sem banner, a cor do ícone.
 * - TV ao vivo: degradê coral, a cor do "ao vivo".
 * Fica numa camada de hardware: desenha uma vez por troca de foco, não a cada quadro do fundo vivo.
 */
class CapaFoco(ctx: Context) : View(ctx) {
    private var imagem: Bitmap? = null
    private var cores: IntArray? = null
    private val pintaImagem = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pintaMascara = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pintaEscuro = Paint().apply { color = 0x99000000.toInt() }   // véu: o clima e a faixa de 9 dias passam por cima (06/11: 0x59 deixava os dias ilegíveis)
    private val pintaEscuroArte = Paint().apply { color = 0x47000000 }      // véu leve da arte de filme/série: ela é para ser vista
    private val pintaTopo = Paint()                                         // escurece o alto da arte, atrás da hora e da data
    private var arte = false
    private val origem = Rect()
    private val destino = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        alpha = 0f
    }

    /**
     * Tema Galeria (dono, 10/11/2026: "no tema galeria aquele glow tem que sumir"): a pintura já é o fundo; a capa por
     * cima dela brigava com a obra. Desligada, ela nunca aparece.
     */
    var desligada = false
        set(v) { field = v; if (v) { animate().cancel(); alpha = 0f } }

    /**
     * Escala de repouso da capa. A HOME desenha a capa em METADE da resolução e a amplia 2x (MainActivity), então a escala
     * "normal" dela é 2, não 1. Bug de 11/11/2026 (foto do dono, 12/11): o zoom da cena mexia na escala absoluta (1 a
     * 1,1) e, ao parar, punha 1: a capa encolhia pela metade em qualquer card depois de passar por Séries/Filmes com cena.
     * Agora o zoom é sempre relativo a esta base ([escalaZoom]).
     */
    var escalaBase = 1f
        set(v) { field = v; if (!zoom.isStarted) { scaleX = v; scaleY = v } }

    /**
     * Zoom lento da cena dos cards de Assistir (11/11/2026, como no 1-Stream: "fica passando uma cena"): cresce 10% em
     * 14 s, ancorado no canto de cima à direita (a capa mora lá), e volta devagar; repete enquanto o card tiver o foco.
     */
    private val zoom = android.animation.ValueAnimator.ofFloat(1f, 1.1f).apply {
        duration = 14_000
        repeatMode = android.animation.ValueAnimator.REVERSE
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { val e = escalaZoom(escalaBase, it.animatedValue as Float); scaleX = e; scaleY = e }
    }

    private fun movimentar(sim: Boolean) {
        if (sim == zoom.isStarted) return
        if (sim) { pivotX = width.toFloat(); pivotY = 0f; zoom.start() }
        else { zoom.cancel(); scaleX = escalaBase; scaleY = escalaBase }
    }

    /** Para o zoom (HOME saiu da frente). Volta sozinho no próximo foco num card de Assistir. */
    fun parar() = movimentar(false)

    override fun onDetachedFromWindow() { movimentar(false); super.onDetachedFromWindow() }

    /**
     * Mostra uma imagem (banner), ou um degradê de [cores] se não houver imagem. null nos dois esconde.
     * [movimento]: zoom lento (a cena de um card de Assistir); banner de app fica parado.
     * [arte]: a arte do último filme/série (12/11/2026, "fundo que muda com o foco"): véu mais leve, some só no último
     * terço de baixo e escurece o alto (a hora passa por cima). Parada, sem zoom.
     */
    fun mostrar(imagem: Bitmap?, cores: IntArray?, movimento: Boolean = false, arte: Boolean = false) {
        if (desligada) return
        movimentar(movimento && imagem != null)
        if (imagem != null || cores != null) ultima = imagem to cores   // o protetor reaproveita (mesmo processo)
        val modoArte = arte && imagem != null
        if (imagem === this.imagem && cores.contentEquals(this.cores) && modoArte == this.arte && alpha > 0f) return
        this.arte = modoArte
        this.imagem = imagem
        this.cores = cores
        invalidate()
        animate().cancel()
        if (imagem == null && cores == null) { animate().alpha(0f).setDuration(200).start(); return }
        if (!TesteHome.capaAnima) { alpha = OPACIDADE; return }   // só para medir (gancho HOME_TESTE)
        alpha = 0.3f
        animate().alpha(OPACIDADE).setDuration(260).start()
    }

    fun esconder() = mostrar(null, null)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val img = imagem
        val cs = cores
        when {
            img != null -> {
                // centerCrop: preenche a área toda, cortando o que sobra
                val escala = maxOf(w / img.width, h / img.height)
                val vw = (w / escala).toInt(); val vh = (h / escala).toInt()
                val x0 = (img.width - vw) / 2; val y0 = (img.height - vh) / 2
                origem.set(x0, y0, x0 + vw, y0 + vh)
                destino.set(0f, 0f, w, h)
                c.drawBitmap(img, origem, destino, pintaImagem)
                if (arte) {
                    c.drawRect(0f, 0f, w, h, pintaEscuroArte)
                    pintaTopo.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, 0x8C000000.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w, h * 0.3f, pintaTopo)
                } else c.drawRect(0f, 0f, w, h, pintaEscuro)
            }
            cs != null -> {
                pintaImagem.shader = LinearGradient(0f, 0f, w, h, cs, null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, pintaImagem)
                pintaImagem.shader = null
            }
            else -> return
        }
        // Máscara: transparente na borda esquerda (até ~55% da largura) e embaixo: some ANTES da faixa de 9 dias (a capa
        // tem 330 dp e a faixa começa em 252: 252/330 = 76%; visto na Sala, 06/11, o banner ainda passava atrás dos dias).
        // Arte: some mais cedo pela esquerda (45%) e só no último terço de baixo (o teste aprovado pelo dono, 12/11/2026).
        pintaMascara.shader = LinearGradient(0f, 0f, w * (if (arte) 0.45f else 0.55f), 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pintaMascara)
        // De volta ao alto da tela (esboço do dono, 06/11 noite): cheia em cima, some embaixo antes dos apps. A capa tem
        // 400 dp de altura desde 12/11/2026: o banner de app some até 70% (280 dp, antes dos cards), como antes.
        pintaMascara.shader = if (arte) LinearGradient(0f, h * 0.6f, 0f, h, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            else LinearGradient(0f, h * 0.25f, 0f, h * 0.7f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pintaMascara)
        pintaMascara.shader = null
    }

    companion object {
        const val OPACIDADE = 0.8f

        /** Escala durante o zoom: a base (2 na HOME) vezes o fator do zoom (1 a 1,1). Pura, testada em CapaFocoTest. */
        fun escalaZoom(base: Float, fator: Float) = base * fator

        /**
         * A última capa mostrada na HOME. O protetor de tela (tema Escuro) desenha o mesmo fundo da HOME: o grafite e este
         * mesmo brilho, do último card em foco. Roda no mesmo processo, então basta a referência. null = nada ainda
         * (protetor logo depois de ligar a TV): aí vale o coral da TV ao vivo, o card que abre em foco.
         */
        @Volatile var ultima: Pair<Bitmap?, IntArray?>? = null
        val CORES_TV = intArrayOf(0xFFFF7A6E.toInt(), 0xFFC0392B.toInt())

        /** O banner de TV do app (o da atividade leanback, senão o do app). null se o app não tiver. */
        fun banner(pm: PackageManager, pacote: String): Drawable? = try {
            pm.getLeanbackLaunchIntentForPackage(pacote)?.let { pm.getActivityBanner(it) }
                ?: pm.getApplicationBanner(pacote)
        } catch (e: Exception) { null }

        /** Desenha um Drawable num Bitmap do tamanho dele (banner: 320x180 px, ou o que o app trouxer). */
        fun bitmap(d: Drawable, larguraPadrao: Int = 320, alturaPadrao: Int = 180): Bitmap {
            if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: larguraPadrao
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: alturaPadrao
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            return b
        }

        /** Cor média do ícone (reduzido a 1 pixel), escurecida, para o degradê de quem não tem banner. */
        fun corDoIcone(icone: Drawable): IntArray {
            // Uma CÓPIA do ícone: desenhar muda os limites (setBounds) do Drawable, e o original está na tela, noutra thread.
            val copia = icone.constantState?.newDrawable()?.mutate() ?: icone
            val p = Bitmap.createScaledBitmap(bitmap(copia, 48, 48), 1, 1, true).getPixel(0, 0)
            val base = Color.rgb(Color.red(p), Color.green(p), Color.blue(p))
            val escura = Color.rgb(Color.red(p) / 3, Color.green(p) / 3, Color.blue(p) / 3)
            return intArrayOf(base, escura)
        }

        /** Intent para abrir um app pela HOME (o mesmo caminho do card). */
        fun abrirIntent(pm: PackageManager, pacote: String): Intent? =
            pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
    }
}

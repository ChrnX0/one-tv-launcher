package br.paulo.tvlauncher.listen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.player.PlayerLogos

/**
 * A "capa" de um item do Ouvir (redesenho, 18/11/2026): o que fica no lugar do ícone da cruz.
 *  - [icone] (ações e prateleiras: Pausar, Buscar...): o desenho Linha SOLTO, sem quadro atrás, como na cruz;
 *  - senão, um quadro arredondado com a imagem da rádio/podcast ([carregar]: logo ou capa, baixada por [PlayerLogos]) e,
 *    enquanto ela não chega ou se não existe, as iniciais ([monograma]) sobre a cor do item. Nunca um quadro vazio.
 *  - [progresso] (0..1): quanto do episódio já foi ouvido; barra fina no pé, como nas miniaturas da cruz.
 * Logo de rádio quase nunca é quadrado nem grande: fica INTEIRO no meio do quadro ([cobrir] = false). Capa de podcast
 * é quadrada por regra do feed: cobre o quadro todo ([cobrir] = true).
 */
class CapaOuvir(ctx: Context, private val raioDp: Float = 14f) : ImageView(ctx) {

    var icone: Drawable? = null
        set(v) { field?.callback = null; field = v; v?.callback = this; invalidate() }
    var cor: Int = 0xFF45C2B1.toInt()
        set(v) { field = v; invalidate() }
    var monograma: String = ""
        set(v) { field = v; invalidate() }
    var progresso: Float? = null
        set(v) { field = v?.coerceIn(0f, 1f); invalidate() }
    var cobrir: Boolean = false
        set(v) { field = v; scaleType = if (v) ScaleType.CENTER_CROP else ScaleType.FIT_CENTER; ajustarMargem() }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private val fundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val cheia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO }
    private val r = RectF()
    /** O canto arredondado desenhado à mão: o recorte pelo contorno nem sempre vale (camada de GPU, captura de tela). */
    private val canto = Path()
    private var url = ""

    init {
        scaleType = ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (icone != null) o.setEmpty() else o.setRoundRect(0, 0, v.width, v.height, dp(raioDp))
            }
        }
        // Sem recorte pelo contorno: com ícone o contorno é vazio, e recortar por um contorno vazio some com tudo na TV
        // (o Robolectric não recorta, por isso o teste de tela não pegou). O canto arredondado é desenhado à mão em onDraw.
        clipToOutline = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ajustarMargem()
    }

    fun fonte(t: Typeface) { letra.typeface = t; invalidate() }

    /** Baixa e mostra a imagem de [endereco] (vazio = só as iniciais). Chamado quando o item fica visível. */
    fun carregar(endereco: String, lado: Int = 200) {
        if (endereco == url && (drawable != null || endereco.isEmpty())) return
        url = endereco
        if (endereco.isEmpty()) { setImageDrawable(null); return }
        PlayerLogos.carregar(context, endereco, this, lado)
    }

    /** Mostra uma imagem já pronta (ícone de app). A próxima [carregar] troca por outra, mesmo vazia. */
    fun mostrar(d: Drawable) {
        if (drawable === d) return
        url = "#imagem"
        setImageDrawable(d)
    }

    /** O logo inteiro precisa de uma folga do quadro; a capa que cobre, não. */
    private fun ajustarMargem() { val m = if (cobrir) 0 else dp(10f).toInt(); setPaddingRelative(m, m, m, m) }

    override fun verifyDrawable(who: Drawable): Boolean = who === icone || super.verifyDrawable(who)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); invalidateOutline() }

    override fun onDraw(c: Canvas) {
        val ic = icone
        if (ic != null) {
            ic.setBounds(0, 0, width, height)
            ic.draw(c)
            return
        }
        canto.reset()
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        canto.addRoundRect(r, dp(raioDp), dp(raioDp), Path.Direction.CW)
        val salvo = c.save()
        c.clipPath(canto)
        // Quadro: a cor do item escurecida (fundo neutro para qualquer logo) e as iniciais enquanto não há imagem.
        fundo.color = misturar(cor, 0xFF151827.toInt(), 0.72f)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fundo)
        if (drawable == null && monograma.isNotEmpty()) {
            letra.color = misturar(cor, Color.WHITE, 0.35f)
            letra.textSize = height * (if (monograma.codePointCount(0, monograma.length) <= 2 && monograma.any { Character.isSurrogate(it) }) 0.5f else 0.38f)
            val y = height / 2f - (letra.descent() + letra.ascent()) / 2f
            c.drawText(monograma, width / 2f, y, letra)
        }
        super.onDraw(c)
        val pr = progresso
        if (pr == null) { c.restoreToCount(salvo); return }
        val a = dp(5f); val l = width * 0.76f
        r.set((width - l) / 2f, height - a - dp(6f), (width + l) / 2f, height - dp(6f))
        c.drawRoundRect(r, a / 2f, a / 2f, trilho)
        if (pr > 0f) { r.right = r.left + maxOf(a, l * pr); c.drawRoundRect(r, a / 2f, a / 2f, cheia) }
        c.restoreToCount(salvo)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); icone?.setVisible(true, false) }
    override fun onDetachedFromWindow() { icone?.setVisible(false, false); super.onDetachedFromWindow() }

    companion object {
        /** a + (b - a) * t, por canal (alfa fica o de a). */
        fun misturar(a: Int, b: Int, t: Float): Int {
            fun c(sh: Int) = ((a shr sh and 0xFF) + (((b shr sh and 0xFF) - (a shr sh and 0xFF)) * t)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }

        /**
         * As iniciais de um nome para o quadro sem imagem: "Rádio Mar Azul FM" -> "MA" (pula "Rádio", "FM" e afins),
         * "CBN" -> "CB", "99" -> "99". Puro (teste JVM).
         */
        fun iniciais(nome: String): String {
            val vazias = setOf("radio", "rádio", "rádios", "radios", "fm", "am", "web", "the", "a", "o", "os", "as", "de", "da", "do", "das", "dos", "e", "-", "–", "·", "|", "podcast", "&")
            val palavras = nome.split(Regex("[\\s/|·–—-]+")).map { it.trim { ch -> !ch.isLetterOrDigit() } }.filter { it.isNotEmpty() }
            val boas = palavras.filter { it.lowercase() !in vazias }.ifEmpty { palavras }
            if (boas.isEmpty()) return ""
            val r = if (boas.size == 1) boas[0].take(2) else "${boas[0].first()}${boas[1].first()}"
            return r.uppercase()
        }

        /** A bandeira de um país pelo código ISO de 2 letras ("BR" -> 🇧🇷); vazio se o código não serve. Puro. */
        fun bandeira(codigo: String): String {
            val c = codigo.uppercase()
            if (c.length != 2 || c.any { it !in 'A'..'Z' }) return ""
            return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        }
    }
}

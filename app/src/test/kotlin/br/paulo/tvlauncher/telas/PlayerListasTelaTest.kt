package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * As listas do player (18/11/2026, Paulo: "Sabe a tela delas? Tem que ser bem feitinhas, coerentes, UI e UX
 * excepcionais... e todo o sistema tem que ter a mesma leitura visual"). Desenha as janelas de escolha de verdade
 * ([Estilo.lista]) por cima de um "quadro de filme" de mentira, do jeito que a TV mostra (véu, posição e tamanho da
 * janela), e salva em app/build/telas/lista-*.png. Reclama de letra pequena, texto cortado, texto por cima de outro e
 * lista sem foco. Roda com -Ptelas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
class PlayerListasTelaTest {
    private val problemas = mutableListOf<String>()

    // Menu do ▼ desde 20/11/2026 (PlayerMenu): poucos itens; à direita o valor de agora e "›" quando abre outra lista;
    // ✓ só na escolha que está valendo.
    @Test fun audioELegenda() = desenhar("lista-audio-legenda", "Áudio e legenda", listOf(
        "Áudio", "Legenda", Estilo.comInfo("Assistir legendado", "Continua deste ponto"), "Imagem e som", "Mais opções",
    ), focoNaMarcada = false, valores = listOf("Português ›", "Desligada ›", null, "›", "›"))

    @Test fun legenda() = desenhar("lista-legenda", "Legenda", listOf(
        "Desligada", "Português", "Inglês", "Português · Forçada", "Buscar legenda na internet", "Ajustes da legenda",
    ), marcadas = listOf(1), valores = listOf(null, null, null, null, null, "›"))

    @Test fun ajustesDaLegenda() = desenhar("lista-ajustes-legenda", "Ajustes da legenda", listOf(
        "Tamanho", "Estilo", "Posição", "Atraso", "Segunda legenda (em cima)", "Baixar uma quando faltar", "Traduzir na TV quando faltar",
    ), focoNaMarcada = false, valores = listOf("Normal ›", "padrão", "embaixo", "Sem atraso ›", "Desligada ›", "Ligado", "Ligado"))

    @Test fun imagemESom() = desenhar("lista-imagem-som", "Imagem e som", listOf(
        "Velocidade", "Tamanho da imagem", "Volume parecido entre canais", "Voz mais clara", "Diálogo mais alto",
        "Atraso do áudio", "Seguir a cadência do filme", "Som no fone (celular)",
    ), focoNaMarcada = false, valores = listOf("Normal ›", "Ajustar à tela ›", "Ligado", "Ligado", "Desligado", "Nenhum ›", "Ligado", "Desligado ›"))

    // Como a TV mostra desde 18/11/2026: nome do lançamento em cima, idioma e downloads embaixo (Estilo.comInfo).
    @Test fun legendasDaInternet() = desenhar("lista-legendas-internet", "Legendas para \"Duna\"", listOf(
        Estilo.comInfo("Dune 2021 1080p WEBRip DD5.1 x264-SHITBOX", "✓ feita para este arquivo · Português (Brasil) · ↓ 44 mil"),
        Estilo.comInfo("Dune 2021 1080p HMAX WEBRip DDP5.1 Atmos x264-CMRG", "Português (Brasil) · ↓ 25 mil"),
        Estilo.comInfo("Dune 2021 1080p WEBRip x264 AAC5.1-[YTS MX]", "Português (Brasil) · ↓ 16 mil"),
        Estilo.comInfo("Dune 2021 720p/ 1080p WEB-BluRay [Subpack]", "Português (Brasil) · ↓ 8,4 mil · para surdos"),
        Estilo.comInfo("Dune 2021 2160p HMAX WEB-DL DDP5.1 Atmos DV MP4 x265-DVSUX", "Português (Brasil) · ↓ 6,6 mil"),
    ), rodape = "Subtitles provided by OpenSubtitles.com")

    @Test fun temporadas() = desenhar("lista-temporadas", "Trocar de temporada", listOf(
        "Temporada 1   ✓", "Temporada 2   ✓", "Temporada 3   5/10", "Temporada 4", "Temporada 5",
    ), marcadas = listOf(2))

    @Test fun qualidade() = desenhar("lista-qualidade", "Qualidade", listOf(
        "Automática (a melhor que a internet aguenta)", "Full HD · 1080p", "HD · 720p", "SD · 480p",
    ), marcadas = listOf(0))

    @Test fun velocidade() = desenhar("lista-velocidade", "Velocidade", listOf(
        "0,5×", "0,75×", "Normal", "1,25×", "1,5×", "2×",
    ), marcadas = listOf(2))

    private fun desenhar(arquivo: String, titulo: String, itens: List<CharSequence>, marcadas: List<Int> = emptyList(),
                         rodape: String? = null, focoNaMarcada: Boolean = true, valores: List<CharSequence?> = emptyList()) {
        // TV não tem toque: fora do "modo toque", requestFocus funciona como no aparelho (no Robolectric o padrão é toque).
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = Estilo.lista(a, titulo, itens, marcadas, rodape = rodape, focoNaMarcada = focoNaMarcada, valores = valores) {}
        // O "mostrar" (setOnShowListener, que põe o foco) chega por mensagem: deixa a fila andar antes da foto.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dm = a.resources.displayMetrics
        val larg = (960 * dm.density).toInt(); val alt = (540 * dm.density).toInt()
        val foto = Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888)
        val c = Canvas(foto)
        quadroDeFilme(c, larg, alt)
        val w = d.window!!
        val at = w.attributes
        if (at.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) c.drawColor(Color.argb((at.dimAmount * 255).toInt(), 0, 0, 0))
        val raiz = w.decorView
        val lw = when (at.width) { ViewGroup.LayoutParams.MATCH_PARENT -> larg; ViewGroup.LayoutParams.WRAP_CONTENT -> -1; else -> at.width }
        val lh = when (at.height) { ViewGroup.LayoutParams.MATCH_PARENT -> alt; ViewGroup.LayoutParams.WRAP_CONTENT -> -1; else -> at.height }
        raiz.measure(
            if (lw < 0) View.MeasureSpec.makeMeasureSpec(larg, View.MeasureSpec.AT_MOST) else View.MeasureSpec.makeMeasureSpec(lw, View.MeasureSpec.EXACTLY),
            if (lh < 0) View.MeasureSpec.makeMeasureSpec(alt, View.MeasureSpec.AT_MOST) else View.MeasureSpec.makeMeasureSpec(lh, View.MeasureSpec.EXACTLY))
        raiz.layout(0, 0, raiz.measuredWidth, raiz.measuredHeight)
        // O foco pedido no "mostrar" (setOnShowListener) já rodou; a rolagem até o foco também precisa do layout feito.
        raiz.findFocus()?.let { f -> raiz.requestLayout(); raiz.layout(0, 0, raiz.measuredWidth, raiz.measuredHeight); f.requestFocus() }
        val x = when (at.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) { Gravity.LEFT, Gravity.START -> 0; Gravity.RIGHT, Gravity.END -> larg - raiz.measuredWidth; else -> (larg - raiz.measuredWidth) / 2 }
        val y = when (at.gravity and Gravity.VERTICAL_GRAVITY_MASK) { Gravity.TOP -> 0; Gravity.BOTTOM -> alt - raiz.measuredHeight; else -> (alt - raiz.measuredHeight) / 2 }
        // Termina as animações de foco (escala, cor) para a foto mostrar o estado parado.
        android.os.Looper.getMainLooper().let { org.robolectric.Shadows.shadowOf(it).idleFor(java.time.Duration.ofSeconds(2)) }
        c.save(); c.translate(x.toFloat(), y.toFloat()); raiz.draw(c); c.restore()
        File("build/telas").apply { mkdirs() }.let { File(it, "$arquivo.png") }.outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        medir(arquivo, raiz)
        // O crédito (OpenSubtitles pede) tem de aparecer inteiro na tela, mesmo com a lista cheia.
        if (rodape != null) {
            fun achar(v: View): TextView? = if (v is TextView && v.text.toString() == rodape) v
                else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it)) } }
            val t = achar(raiz); val r = Rect()
            if (t == null || !t.getGlobalVisibleRect(r) || r.height() < t.height || t.height == 0) problemas += "$arquivo: o crédito \"$rodape\" não aparece"
        }
        d.dismiss()
        assertTrue(problemas.joinToString("\n"), problemas.isEmpty())
    }

    /** Letra de 16 sp no mínimo (idosos), nada cortado com "…", nenhum texto por cima de outro, e o foco numa linha. */
    private fun medir(chave: String, raiz: View) {
        val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
        val textos = mutableListOf<Pair<TextView, Rect>>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v is TextView && v.text.isNotBlank() && v.width > 0 && v.height > 0) {
                val r = Rect(); if (v.getGlobalVisibleRect(r)) textos += v to r
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        fun rotulo(t: TextView) = t.text.toString().replace('\n', ' ').trim().take(40)
        for ((t, _) in textos) {
            val sp = t.textSize / escala
            if (sp < 16f - 0.05f) problemas += "$chave: letra pequena (${sp}sp) em \"${rotulo(t)}\""
            if (t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } == true) problemas += "$chave: texto cortado \"${rotulo(t)}\""
        }
        for (i in textos.indices) for (j in i + 1 until textos.size) {
            val (t1, r1) = textos[i]; val (t2, r2) = textos[j]
            val x = Rect()
            if (x.setIntersect(r1, r2) && x.width() > 3 && x.height() > 3) problemas += "$chave: \"${rotulo(t1)}\" por cima de \"${rotulo(t2)}\""
        }
        if (raiz.findFocus() == null) problemas += "$chave: nada tem o foco"
    }

    /** Um quadro de filme de mentira (céu de deserto, duna escura, sol), para ver o que a janela deixa à mostra. */
    private fun quadroDeFilme(c: Canvas, l: Int, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, a.toFloat(), intArrayOf(0xFF2B3A55.toInt(), 0xFFC9874A.toInt(), 0xFF7A4A24.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, l.toFloat(), a.toFloat(), p)
        p.shader = null; p.color = 0xFFF3D9A4.toInt(); c.drawCircle(l * 0.28f, a * 0.38f, a * 0.09f, p)
        p.color = 0xFF3A2414.toInt()
        val duna = android.graphics.Path().apply {
            moveTo(0f, a * 0.72f); quadTo(l * 0.3f, a * 0.55f, l * 0.6f, a * 0.74f); quadTo(l * 0.85f, a * 0.86f, l.toFloat(), a * 0.7f)
            lineTo(l.toFloat(), a.toFloat()); lineTo(0f, a.toFloat()); close()
        }
        c.drawPath(duna, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = a * 0.045f; p.textAlign = Paint.Align.CENTER
        p.setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        c.drawText("— Ele vai nos levar ao paraíso.", l / 2f, a * 0.9f, p)
    }
}

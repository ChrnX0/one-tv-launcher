package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/**
 * Padrões de calibração da imagem (10/11/2026, ideia J; li no Projectivy: "Calibration Patterns"). Servem para acertar
 * brilho, contraste, nitidez e "tamanho da imagem" (overscan) da TV uma vez. ◀ ▶ trocam o padrão; VOLTAR sai.
 *
 * O que cada um mostra e o que olhar:
 *  1. Degraus de cinza (0 a 255 em 16 passos): todos têm de ser distintos. Degraus colados = contraste/gama errados.
 *  2. Quase preto (0 a 30): ajuste o BRILHO até ver o degrau 10 e o 0 continuar preto.
 *  3. Quase branco (225 a 255): ajuste o CONTRASTE até ver o 245 separado do 255.
 *  4. Bordas (overscan): molduras a 0%, 2,5% e 5% da borda. A de 0% tem de aparecer inteira; senão, ligue "Tamanho
 *     original"/"Just scan" na TV.
 *  5. Barras de cor a 75%: cores limpas, sem vazar uma na outra.
 *  6. Linhas de 1 pixel: listras finas e nítidas, sem moiré. Borrado = nitidez exagerada ou imagem escalada.
 * Os valores dos padrões estão em [Calibracao] (puro, testado em CalibracaoTest).
 */
class CalibracaoActivity : Activity() {
    private lateinit var tela: PadraoView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tela = PadraoView(this)
        setContentView(tela)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { tela.mudar(+1); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { tela.mudar(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }
}

/** Valores dos padrões, sem nada de Android. */
object Calibracao {
    val NOMES = listOf("Degraus de cinza", "Quase preto", "Quase branco", "Bordas", "Barras de cor 75%", "Linhas de 1 pixel")

    /** [n] níveis de cinza de [de] a [ate], inclusive, igualmente espaçados. */
    fun degraus(de: Int, ate: Int, n: Int): List<Int> =
        if (n <= 1) listOf(de) else (0 until n).map { de + (ate - de) * it / (n - 1) }

    /** Barras SMPTE simplificadas, a 75%: branco, amarelo, ciano, verde, magenta, vermelho, azul. RGB em 0..255. */
    val BARRAS_75 = listOf(
        intArrayOf(191, 191, 191), intArrayOf(191, 191, 0), intArrayOf(0, 191, 191), intArrayOf(0, 191, 0),
        intArrayOf(191, 0, 191), intArrayOf(191, 0, 0), intArrayOf(0, 0, 191),
    )

    /** Recuo de cada moldura de overscan, em fração da tela. */
    val MOLDURAS = listOf(0f, 0.025f, 0.05f)
}

private class PadraoView(ctx: Context) : View(ctx) {
    private var atual = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f * ctx.resources.displayMetrics.scaledDensity }

    fun mudar(d: Int) { atual = (atual + d + Calibracao.NOMES.size) % Calibracao.NOMES.size; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        when (atual) {
            0 -> faixas(c, Calibracao.degraus(0, 255, 16), w, h)
            1 -> faixas(c, Calibracao.degraus(0, 30, 7), w, h, rotulos = true)
            2 -> faixas(c, Calibracao.degraus(225, 255, 7), w, h, rotulos = true)
            3 -> {
                c.drawColor(Color.rgb(40, 40, 40))
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                val cores = listOf(Color.WHITE, Color.YELLOW, Color.RED)
                Calibracao.MOLDURAS.forEachIndexed { i, f ->
                    p.color = cores[i]
                    c.drawRect(w * f + 1.5f, h * f + 1.5f, w * (1 - f) - 1.5f, h * (1 - f) - 1.5f, p)
                }
                p.style = Paint.Style.FILL
            }
            4 -> {
                val n = Calibracao.BARRAS_75.size
                Calibracao.BARRAS_75.forEachIndexed { i, rgb ->
                    p.color = Color.rgb(rgb[0], rgb[1], rgb[2])
                    c.drawRect(w * i / n, 0f, w * (i + 1) / n, h, p)
                }
            }
            5 -> {
                p.isAntiAlias = false; p.strokeWidth = 1f
                // metade de cima: linhas verticais alternadas; de baixo: horizontais. 1 pixel físico cada.
                p.color = Color.WHITE
                var x = 0f; while (x < w) { c.drawLine(x + 0.5f, 0f, x + 0.5f, h / 2, p); x += 2f }
                var y = h / 2; while (y < h) { c.drawLine(0f, y + 0.5f, w, y + 0.5f, p); y += 2f }
                p.isAntiAlias = true
            }
        }
        // Sempre branco: o título fica na faixa de cima (0 a 20% da altura), que é preta em todos os padrões. O preto
        // do padrão 3 sumia no fundo preto.
        txt.color = Color.WHITE
        c.drawText("${atual + 1}/${Calibracao.NOMES.size}  ${Calibracao.NOMES[atual]}   ◀ ▶", w * 0.06f, h * 0.10f, txt)
    }

    private fun faixas(c: Canvas, niveis: List<Int>, w: Float, h: Float, rotulos: Boolean = false) {
        val n = niveis.size
        niveis.forEachIndexed { i, v ->
            p.color = Color.rgb(v, v, v)
            c.drawRect(w * i / n, h * 0.2f, w * (i + 1) / n, h, p)
            if (rotulos) {
                txt.color = if (v < 128) Color.rgb(90, 90, 90) else Color.rgb(160, 160, 160)
                c.drawText("$v", w * i / n + 12f, h * 0.95f, txt)
            }
        }
    }
}

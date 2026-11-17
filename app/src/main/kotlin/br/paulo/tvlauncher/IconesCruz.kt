package br.paulo.tvlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Ícones da tela inicial em cruz (17/11/2026): objeto ou símbolo SOLTO, sem card nem círculo atrás (pedido do dono).
 * Dois estilos, desenhados em código (vetor, sem PNG: nítido em qualquer tamanho e quase sem memória):
 * - [Estilo.OBJETO] (padrão): o objeto ilustrado (uma TV, um rádio, um controle), quadro de 120 x 100;
 * - [Estilo.SIMBOLO]: o símbolo de traço, na cor do grupo, quadro de 24 x 24.
 * Os desenhos vêm da amostra aprovada (home-grupos/amostra-a-b.html). Cores e traço ficam aqui, num lugar só, para o
 * dono refinar depois.
 */
class IconeCruz(private val tipo: Tipo, private val estilo: Estilo = Estilo.OBJETO) : Drawable() {

    enum class Tipo { CONTINUAR, ASSISTIR, OUVIR, JOGAR, APLICATIVOS, TV, SERIES, FILMES, REDE, ADDONS, RADIO, PODCASTS, JOGOS, SOFA, TODOS_APPS, PLUGIN }
    /** [DUOTOM] (17/11/2026, 3º estilo embutido): o traço do Símbolo em branco quente sobre um halo suave na cor do grupo. */
    enum class Estilo { OBJETO, SIMBOLO, DUOTOM }

    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val p = Path()
    private val r = RectF()
    private var corTraco = 0   // cor do símbolo nesta passada (o Duotom desenha duas: o halo na cor do grupo e o traço claro)

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        if (estilo == Estilo.OBJETO) {
            // quadro 120 x 100 centrado, sem deformar
            val esc = minOf(b.width() / 120f, b.height() / 100f)
            c.translate((b.width() - 120f * esc) / 2f, (b.height() - 100f * esc) / 2f)
            c.scale(esc, esc)
            objeto(c)
        } else {
            val esc = minOf(b.width(), b.height()) / 24f
            c.translate((b.width() - 24f * esc) / 2f, (b.height() - 24f * esc) / 2f)
            c.scale(esc, esc)
            if (estilo == Estilo.DUOTOM) {
                // Halo: o mesmo símbolo deslocado 1,3 em 8 direções, numa camada com 60% de opacidade (as passadas não
                // somam entre si). É desenhado uma vez por ícone: a cruz anda movendo as views, sem redesenhar o ícone.
                corTraco = corDoGrupo()
                val n = c.saveLayerAlpha(-3f, -3f, 27f, 27f, 150)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    c.save(); c.translate((1.3 * Math.cos(a)).toFloat(), (1.3 * Math.sin(a)).toFloat()); simbolo(c); c.restore()
                }
                c.restoreToCount(n)
                corTraco = COR_CLARA
            } else corTraco = corDoGrupo()
            simbolo(c)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ objeto (120 x 100)

    private fun cor(v: Long) = v.toInt()
    private fun vert(a: Long, b: Long, y0: Float, y1: Float) { cheio.shader = LinearGradient(0f, y0, 0f, y1, cor(a), cor(b), Shader.TileMode.CLAMP) }
    private fun solido(v: Long, alfa: Float = 1f) { cheio.shader = null; cheio.color = cor(v); cheio.alpha = (alfa * 255).toInt() }
    private fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, raio: Float) { r.set(x, y, x + w, y + h); c.drawRoundRect(r, raio, raio, cheio) }
    private fun linha(c: Canvas, cor: Long, larg: Float, vararg xy: Float) {
        traco.shader = null; traco.color = cor(cor); traco.strokeWidth = larg
        var i = 0
        while (i + 3 < xy.size) { c.drawLine(xy[i], xy[i + 1], xy[i + 2], xy[i + 3], traco); i += 4 }
    }
    private fun play(c: Canvas, x: Float, y: Float, alt: Float, larg: Float) {
        solido(0xFFFFFFFF); p.reset(); p.moveTo(x, y); p.lineTo(x, y + alt); p.lineTo(x + larg, y + alt / 2); p.close(); c.drawPath(p, cheio)
    }

    private fun objeto(c: Canvas) {
        when (tipo) {
            Tipo.ASSISTIR, Tipo.TV -> tv(c)
            Tipo.JOGAR, Tipo.JOGOS -> controle(c)
            Tipo.OUVIR -> fone(c)
            Tipo.CONTINUAR -> continuar(c)
            Tipo.SERIES -> series(c)
            Tipo.FILMES -> claquete(c)
            Tipo.REDE -> pendrive(c)
            Tipo.ADDONS, Tipo.PLUGIN -> peca(c)
            Tipo.RADIO -> radio(c)
            Tipo.PODCASTS -> microfone(c)
            Tipo.SOFA -> sofa(c)
            Tipo.APLICATIVOS, Tipo.TODOS_APPS -> quadradinhos(c)
        }
        cheio.shader = null; cheio.alpha = 255
    }

    private fun tv(c: Canvas) {
        linha(c, 0xFF8A8F9C, 3f, 44f, 8f, 58f, 22f, 78f, 8f, 64f, 22f)
        vert(0xFF555B69, 0xFF23262E, 20f, 84f); rr(c, 12f, 20f, 96f, 64f, 13f)
        cheio.shader = LinearGradient(20f, 28f, 84f, 76f, cor(0xFF3B6EA8), cor(0xFFE0874A), Shader.TileMode.CLAMP); rr(c, 20f, 28f, 64f, 48f, 8f)
        solido(0xFFFFFFFF, .1f); rr(c, 20f, 28f, 64f, 14f, 7f)
        play(c, 45f, 41f, 22f, 18f)
        solido(0xFFFF6A2B); c.drawCircle(96f, 38f, 4.5f, cheio)
        solido(0xFF6B707C); c.drawCircle(96f, 52f, 4.5f, cheio); rr(c, 89f, 62f, 14f, 3f, 1.5f); rr(c, 89f, 69f, 14f, 3f, 1.5f)
        linha(c, 0xFF3A3E48, 5f, 30f, 85f, 24f, 95f, 90f, 85f, 96f, 95f)
    }

    private fun controle(c: Canvas) {
        vert(0xFF7A83F0, 0xFF3A3F9E, 28f, 90f)
        p.reset(); p.moveTo(30f, 28f); p.lineTo(90f, 28f); p.cubicTo(106f, 28f, 114f, 42f, 116f, 60f); p.cubicTo(118f, 80f, 108f, 90f, 98f, 86f)
        p.cubicTo(90f, 83f, 86f, 72f, 78f, 70f); p.lineTo(42f, 70f); p.cubicTo(34f, 72f, 30f, 83f, 22f, 86f); p.cubicTo(12f, 90f, 2f, 80f, 4f, 60f)
        p.cubicTo(6f, 42f, 14f, 28f, 30f, 28f); p.close(); c.drawPath(p, cheio)
        solido(0xFFFFFFFF, .13f)
        p.reset(); p.moveTo(30f, 32f); p.lineTo(90f, 32f); p.cubicTo(100f, 32f, 106f, 38f, 109f, 46f); p.lineTo(11f, 46f); p.cubicTo(14f, 38f, 20f, 32f, 30f, 32f); p.close(); c.drawPath(p, cheio)
        solido(0xFF1E2150); rr(c, 28f, 44f, 8f, 24f, 1f); rr(c, 20f, 52f, 24f, 8f, 1f)
        solido(0xFFFF6A2B); c.drawCircle(86f, 46f, 5f, cheio)
        solido(0xFFFFD166); c.drawCircle(96f, 56f, 5f, cheio)
        solido(0xFF7DD3C0); c.drawCircle(76f, 56f, 5f, cheio)
        solido(0xFFE8ECFF); c.drawCircle(86f, 66f, 5f, cheio)
    }

    private fun fone(c: Canvas) {
        traco.shader = null; traco.color = cor(0xFF2C3036); traco.strokeWidth = 10f
        r.set(24f, 16f, 96f, 88f); c.drawArc(r, 180f, 180f, false, traco); c.drawLine(24f, 52f, 24f, 62f, traco); c.drawLine(96f, 52f, 96f, 62f, traco)
        traco.color = cor(0xFF5A616C); traco.strokeWidth = 3f; c.drawArc(r, 180f, 180f, false, traco)
        vert(0xFF45BDB1, 0xFF1D6E68, 54f, 92f); rr(c, 10f, 54f, 28f, 38f, 12f); rr(c, 82f, 54f, 28f, 38f, 12f)
        solido(0xFF22262C); rr(c, 33f, 58f, 9f, 30f, 4.5f); rr(c, 78f, 58f, 9f, 30f, 4.5f)
        solido(0xFFFFFFFF, .28f); rr(c, 15f, 59f, 6f, 16f, 3f); rr(c, 87f, 59f, 6f, 16f, 3f)
    }

    private fun continuar(c: Canvas) {
        solido(0xFF2A2420); c.drawCircle(60f, 50f, 40f, cheio)
        traco.shader = null; traco.strokeWidth = 7f; traco.color = cor(0xFF3A322D); c.drawCircle(60f, 50f, 40f, traco)
        traco.color = cor(0xFFFF6A2B); r.set(20f, 10f, 100f, 90f); c.drawArc(r, -90f, 223f, false, traco)   // 62% assistido
        vert(0xFFFF8A55, 0xFFD4501B, 23f, 77f); c.drawCircle(60f, 50f, 27f, cheio)
        play(c, 54f, 38f, 24f, 19f)
    }

    private fun series(c: Canvas) {
        solido(0xFF2F3440); rr(c, 30f, 12f, 78f, 54f, 10f)
        solido(0xFF4A5060); rr(c, 12f, 26f, 82f, 60f, 11f)
        vert(0xFF1F7480, 0xFF0D2B32, 33f, 79f); rr(c, 19f, 33f, 68f, 46f, 7f)
        play(c, 46f, 45f, 22f, 18f)
        solido(0xFFFF6A2B); rr(c, 70f, 74f, 40f, 20f, 10f)
        solido(0xFF1A0D06); for (i in 0..2) c.drawCircle(80f + i * 10f, 84f, 2.6f, cheio)   // "…" no lugar de "T1 E4" (sem texto no desenho)
    }

    private fun claquete(c: Canvas) {
        solido(0xFF2B2F38); rr(c, 14f, 40f, 92f, 54f, 7f)
        solido(0xFFECE8E1); rr(c, 14f, 40f, 92f, 12f, 0f)
        linha(c, 0xFF2B2F38, 6f, 26f, 40f, 34f, 52f, 46f, 40f, 54f, 52f, 66f, 40f, 74f, 52f, 86f, 40f, 94f, 52f)
        c.save(); c.rotate(-16f, 14f, 38f)
        solido(0xFFECE8E1); rr(c, 14f, 24f, 92f, 13f, 2f)
        linha(c, 0xFF2B2F38, 6f, 24f, 24f, 33f, 37f, 44f, 24f, 53f, 37f, 64f, 24f, 73f, 37f, 84f, 24f, 93f, 37f)
        c.restore()
        solido(0xFF8A8F9C); c.drawCircle(15f, 39f, 4f, cheio)
        solido(0xFF6B7080); rr(c, 26f, 62f, 44f, 6f, 3f); rr(c, 26f, 75f, 64f, 6f, 3f)
    }

    private fun pendrive(c: Canvas) {
        solido(0xFFCFD2D9); rr(c, 30f, 16f, 34f, 26f, 3f)
        solido(0xFF6B7080); rr(c, 38f, 23f, 6f, 7f, 0f); rr(c, 50f, 23f, 6f, 7f, 0f)
        vert(0xFF5A82BD, 0xFF2A4170, 38f, 94f); rr(c, 22f, 38f, 50f, 56f, 11f)
        solido(0xFFFFFFFF, .85f); rr(c, 30f, 56f, 34f, 18f, 4f)
        solido(0xFFFFFFFF, .18f); rr(c, 26f, 42f, 8f, 30f, 4f)
        traco.shader = null; traco.color = cor(0xFF93B8E8); traco.strokeWidth = 5f
        r.set(68f, 48f, 100f, 80f); c.drawArc(r, -48f, 96f, false, traco)
        r.set(66f, 37f, 120f, 91f); c.drawArc(r, -45f, 90f, false, traco)
    }

    private fun peca(c: Canvas) {
        vert(0xFFFFBE6B, 0xFFD9701B, 20f, 94f)
        p.reset(); p.moveTo(22f, 30f); p.lineTo(44f, 30f); p.arcTo(RectF(44f, 20f, 64f, 40f), 180f, 180f); p.lineTo(88f, 30f); p.lineTo(88f, 54f)
        p.arcTo(RectF(78f, 54f, 98f, 74f), 270f, -180f); p.lineTo(88f, 94f); p.lineTo(22f, 94f); p.close(); c.drawPath(p, cheio)
        solido(0xFFFFFFFF, .2f); rr(c, 26f, 34f, 16f, 10f, 0f)
    }

    private fun radio(c: Canvas) {
        linha(c, 0xFF8A8F9C, 3f, 40f, 30f, 88f, 10f)
        solido(0xFF8A8F9C); c.drawCircle(88f, 10f, 3.5f, cheio)
        vert(0xFFD0773F, 0xFF7D3A1C, 28f, 92f); rr(c, 10f, 28f, 100f, 64f, 13f)
        solido(0xFFFFFFFF, .12f); rr(c, 10f, 28f, 100f, 14f, 7f)
        solido(0xFF3A2418); c.drawCircle(38f, 62f, 21f, cheio)
        traco.shader = null; traco.color = cor(0xFF6A4430); traco.strokeWidth = 2f
        traco.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f); c.drawCircle(38f, 62f, 14f, traco); traco.pathEffect = null
        solido(0xFF6A4430); c.drawCircle(38f, 62f, 6f, cheio)
        solido(0xFFF3E1C4); rr(c, 68f, 44f, 32f, 17f, 3f)
        solido(0xFFFF6A2B); rr(c, 81f, 46f, 2.5f, 13f, 0f)
        solido(0xFF3A2418); c.drawCircle(76f, 76f, 6.5f, cheio); c.drawCircle(93f, 76f, 6.5f, cheio)
    }

    private fun microfone(c: Canvas) {
        vert(0xFFE3E6EC, 0xFF8A909C, 8f, 60f); rr(c, 44f, 8f, 32f, 52f, 16f)
        linha(c, 0xFF6B7080, 2f, 48f, 22f, 72f, 22f, 48f, 30f, 72f, 30f, 48f, 38f, 72f, 38f, 48f, 46f, 72f, 46f)
        traco.shader = null; traco.color = cor(0xFF5A606C); traco.strokeWidth = 6f
        r.set(32f, 14f, 88f, 70f); c.drawArc(r, 0f, 180f, false, traco)
        traco.strokeWidth = 7f; c.drawLine(60f, 70f, 60f, 84f, traco); c.drawLine(42f, 90f, 78f, 90f, traco)
        solido(0xFFFF6A2B); c.drawCircle(92f, 18f, 6f, cheio)
    }

    private fun sofa(c: Canvas) {
        solido(0xFF7A5A45); rr(c, 22f, 30f, 76f, 36f, 11f)
        solido(0xFFFFFFFF, .1f); rr(c, 26f, 34f, 68f, 8f, 4f)
        solido(0xFF8F6C53); rr(c, 8f, 48f, 24f, 38f, 11f); rr(c, 88f, 48f, 24f, 38f, 11f)
        solido(0xFFA68066); rr(c, 27f, 60f, 66f, 22f, 7f)
        linha(c, 0xFF7A5A45, 2f, 60f, 62f, 60f, 80f)
        linha(c, 0xFF3A2A20, 5f, 20f, 86f, 20f, 94f, 100f, 86f, 100f, 94f)
    }

    private fun quadradinhos(c: Canvas) {
        solido(0xFFFF8A55); rr(c, 20f, 10f, 38f, 38f, 10f)
        solido(0xFF7DD3C0); rr(c, 62f, 10f, 38f, 38f, 10f)
        solido(0xFF9AA3FF); rr(c, 20f, 52f, 38f, 38f, 10f)
        solido(0xFFFFD166); rr(c, 62f, 52f, 38f, 38f, 10f)
        if (tipo == Tipo.TODOS_APPS) { solido(0xFF1A1D24, .55f); for (i in 0..2) c.drawCircle(64f + i * 13f, 71f, 3.2f, cheio) }
    }

    // ------------------------------------------------------------------ símbolo (24 x 24, traço na cor do grupo)

    private fun corDoGrupo(): Int = when (tipo) {
        Tipo.OUVIR, Tipo.RADIO, Tipo.PODCASTS -> 0xFF93D6D0.toInt()
        Tipo.JOGAR, Tipo.JOGOS, Tipo.SOFA -> 0xFFB4B9FF.toInt()
        Tipo.APLICATIVOS, Tipo.TODOS_APPS, Tipo.PLUGIN -> 0xFFE8E2DA.toInt()
        else -> 0xFFFFB48E.toInt()   // Continuar e Assistir: o laranja claro da marca (nada rosado)
    }

    private fun simbolo(c: Canvas) {
        traco.shader = null; traco.color = corTraco; traco.strokeWidth = 1.5f; traco.pathEffect = null
        cheio.shader = null; cheio.color = corTraco; cheio.alpha = 255
        when (tipo) {
            Tipo.ASSISTIR, Tipo.TV -> IconeApp(IconeApp.Tipo.TV, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.SERIES -> IconeApp(IconeApp.Tipo.SERIES, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.FILMES -> IconeApp(IconeApp.Tipo.FILMES, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.REDE -> IconeApp(IconeApp.Tipo.REDE, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.JOGAR, Tipo.JOGOS -> IconeApp(IconeApp.Tipo.JOGOS, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.PODCASTS -> IconeApp(IconeApp.Tipo.MICROFONE, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.CONTINUAR -> {
                c.drawCircle(12f, 12f, 9f, traco)
                p.reset(); p.moveTo(10f, 8.5f); p.lineTo(10f, 15.5f); p.lineTo(15.5f, 12f); p.close(); c.drawPath(p, cheio)
            }
            Tipo.OUVIR -> {
                r.set(4f, 4f, 20f, 20f); c.drawArc(r, 180f, 180f, false, traco)
                r.set(3f, 12.5f, 7.5f, 19.5f); c.drawRoundRect(r, 2f, 2f, traco)
                r.set(16.5f, 12.5f, 21f, 19.5f); c.drawRoundRect(r, 2f, 2f, traco)
            }
            Tipo.RADIO -> {
                r.set(3f, 8f, 21f, 20f); c.drawRoundRect(r, 2.5f, 2.5f, traco)
                c.drawLine(7f, 8f, 17f, 3.5f, traco)
                c.drawCircle(8.5f, 14f, 2.6f, traco); c.drawLine(13.5f, 12f, 18f, 12f, traco); c.drawLine(13.5f, 15.5f, 18f, 15.5f, traco)
            }
            Tipo.SOFA -> {
                r.set(5f, 7f, 19f, 13.5f); c.drawRoundRect(r, 2f, 2f, traco)
                r.set(2.5f, 11f, 6.5f, 18f); c.drawRoundRect(r, 1.8f, 1.8f, traco)
                r.set(17.5f, 11f, 21.5f, 18f); c.drawRoundRect(r, 1.8f, 1.8f, traco)
                c.drawLine(6.5f, 15f, 17.5f, 15f, traco); c.drawLine(4.5f, 18f, 4.5f, 20.5f, traco); c.drawLine(19.5f, 18f, 19.5f, 20.5f, traco)
            }
            Tipo.ADDONS, Tipo.PLUGIN -> {
                p.reset(); p.moveTo(4f, 7f); p.lineTo(9f, 7f); p.arcTo(RectF(9f, 4.5f, 14f, 9.5f), 180f, 180f); p.lineTo(18f, 7f); p.lineTo(18f, 11f)
                p.arcTo(RectF(15.5f, 11f, 20.5f, 16f), 270f, -180f); p.lineTo(18f, 20f); p.lineTo(4f, 20f); p.close(); c.drawPath(p, traco)
            }
            Tipo.APLICATIVOS, Tipo.TODOS_APPS -> for (x in listOf(4f, 13.5f)) for (y in listOf(4f, 13.5f)) {
                r.set(x, y, x + 6.5f, y + 6.5f); c.drawRoundRect(r, 1.8f, 1.8f, traco)
            }
        }
    }

    override fun setAlpha(alpha: Int) { cheio.alpha = alpha; traco.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { cheio.colorFilter = cf; traco.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val COR_CLARA = 0xFFF4F1EC.toInt()
    }
}

package br.paulo.tvlauncher.listen

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.MolaViews
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ícones das telas Rádio e Podcasts (redesenho do Ouvir, 18/11/2026), no estilo "Linha" da cruz ([IconeCruz.Estilo.LINHA]):
 * traço fino de 1,25 num quadro de 24 x 24, desenho adulto e CADA ícone na cor dele (tons médios, nada saturado, nada
 * rosado). Vetor em código: nítido em qualquer tamanho, quase sem memória.
 *
 * Alguns têm um gesto próprio quando estão escolhidos ([animar]), como os da cruz: o equalizador do "Tocando" pula, o
 * ponteiro dos "Recentes" dá a volta, o pino do "Perto" quica, a nota dos "Gêneros" sobe, a lupa procura. Parado, o
 * desenho é exatamente o estático. Quem chama confere "Remover animações" ([MolaViews.semAnimacao]).
 */
class IconeOuvir(val tipo: Tipo) : Drawable() {

    enum class Tipo {
        TOCANDO, PAUSAR, CONTINUAR, VOLTAR, AVANCAR, VELOCIDADE, SILENCIO, PARAR, SAIR,
        FAVORITAS, RECENTES, PERTO, PAIS, GENEROS, BUSCAR, EM_ALTA, ADICIONAR, CELULAR, NOVOS, MEUS, EPISODIOS, RADIO, PODCAST,
        /** Timer para dormir do player de podcast (18/11/2026). */
        DORMIR,
        /** Tela Jogos na cruz (18/11/2026): controle, sofá, turma, rede, PC e mais opções. */
        CONTROLE, SOFA, TURMA, REDE, PC, OPCOES,
    }

    companion object {
        /** A cor de cada ícone (a cruz faz o mesmo em [IconeCruz.corLinha]). */
        fun cor(t: Tipo): Int = when (t) {
            Tipo.TOCANDO, Tipo.PAUSAR, Tipo.CONTINUAR -> DesignTokens.WORLD_LISTEN.toLong() and 0xFFFFFFFFL   // a cor do mundo Ouvir (laranja fica para marca/foco/progresso)
            Tipo.VOLTAR, Tipo.AVANCAR -> 0xFF8FB4F0                   // azul acinzentado
            Tipo.VELOCIDADE -> 0xFFE6C46A                             // dourado
            Tipo.SILENCIO -> 0xFF9CD27E                               // verde-folha
            Tipo.PARAR -> 0xFFE8E2DA                                  // creme
            Tipo.SAIR -> 0xFFB9C6E4                                   // luar
            Tipo.DORMIR -> 0xFFB9C6E4                                 // luar
            Tipo.FAVORITAS -> 0xFFF2B84B                              // âmbar
            Tipo.RECENTES -> 0xFFB9C6E4
            Tipo.PERTO -> 0xFF45C2B1                                  // turquesa do Ouvir (laranja = só marca/foco/progresso, 19/11/2026)
            Tipo.PAIS -> 0xFF6FC3B2                                   // verde-água
            Tipo.GENEROS -> 0xFF8E92FA                                // violeta-azulado
            Tipo.BUSCAR -> 0xFFE8E2DA
            Tipo.EM_ALTA -> 0xFFE6C46A   // dourado (era laranja claro)
            Tipo.ADICIONAR -> 0xFF9CD27E
            Tipo.CELULAR -> 0xFF8FB4F0
            Tipo.NOVOS -> 0xFFF2B84B
            Tipo.MEUS, Tipo.EPISODIOS, Tipo.PODCAST -> 0xFF7FB2FF     // o azul dos podcasts (o mesmo da cruz)
            Tipo.RADIO -> 0xFF45C2B1                                  // o turquesa da rádio (o mesmo da cruz)
            Tipo.CONTROLE -> 0xFF8E92FA                               // o violeta do Jogar (o mesmo da cruz)
            Tipo.SOFA -> 0xFFA9B0FF                                   // o lilás do sofá da cruz (era laranja claro)
            Tipo.TURMA -> 0xFF8FB4F0                                  // azul acinzentado
            Tipo.REDE -> 0xFF9CD27E                                   // verde-folha
            Tipo.PC -> 0xFF7FB2FF
            Tipo.OPCOES -> 0xFFE8E2DA                                 // creme
        }.toInt()

        const val TRACO = IconeCruz.TRACO_FINO
        const val DURACAO_MS = 1500L
        const val DESCANSO_MS = 2500L
        /** O "Tocando" não descansa: o equalizador pula enquanto o som sai. */
        private fun descanso(t: Tipo) = if (t == Tipo.TOCANDO) 0L else DESCANSO_MS
        private val COM_GESTO = setOf(Tipo.TOCANDO, Tipo.RECENTES, Tipo.PERTO, Tipo.GENEROS, Tipo.BUSCAR, Tipo.PAIS, Tipo.FAVORITAS, Tipo.NOVOS, Tipo.CONTROLE, Tipo.REDE)
    }

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = TRACO; color = cor(tipo)
    }
    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cor(tipo) }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cor(tipo); textAlign = Paint.Align.CENTER; textSize = 6.2f; isFakeBoldText = true
    }
    private val p = Path()
    private val r = RectF()

    // ---- gesto (o mesmo relógio dos ícones da cruz)
    private var vivo = false
    private var desdeMs = 0L
    private val tique = Runnable { invalidateSelf() }
    /** Só para a prévia (teste de telas): desenha sempre nesta fase, sem relógio. */
    internal var faseFixa: Float? = null

    /** Liga ou desliga o gesto (só os ícones que têm um). [atraso]: quando começa a primeira passada. */
    fun animar(liga: Boolean, atraso: Long = 0L) {
        if (tipo !in COM_GESTO || liga == vivo) return
        vivo = liga
        desdeMs = android.os.SystemClock.uptimeMillis() + atraso
        unscheduleSelf(tique)
        invalidateSelf()
    }

    private fun faseAgora(): Float = faseFixa
        ?: if (!vivo) -1f else IconeCruz.faseEm(android.os.SystemClock.uptimeMillis() - desdeMs, DURACAO_MS, descanso(tipo))

    private fun agendar() {
        if (!vivo || callback == null) return
        val agora = android.os.SystemClock.uptimeMillis()
        unscheduleSelf(tique)
        scheduleSelf(tique, agora + IconeCruz.proximoEm(agora - desdeMs, DURACAO_MS, descanso(tipo)))
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) unscheduleSelf(tique) else if (vivo) invalidateSelf()
        return super.setVisible(visible, restart)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        c.save()
        val esc = minOf(b.width(), b.height()) / 24f
        c.translate(b.left + (b.width() - 24f * esc) / 2f, b.top + (b.height() - 24f * esc) / 2f)
        c.scale(esc, esc)
        desenhar(c, faseAgora())
        c.restore()
        if (faseFixa == null) agendar()
    }

    private fun ret(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, raio: Float) { r.set(x0, y0, x1, y1); c.drawRoundRect(r, raio, raio, traco) }
    private fun tri(c: Canvas, x: Float, y: Float, alt: Float) {
        p.reset(); p.moveTo(x, y); p.lineTo(x, y + alt); p.lineTo(x + alt * 0.86f, y + alt / 2); p.close(); c.drawPath(p, traco)
    }

    /** Arco com a ponta de seta no fim (voltar = anti-horário, avançar = horário) e o número de segundos no meio. */
    private fun pulo(c: Canvas, horario: Boolean, numero: String) {
        r.set(3.5f, 3.5f, 20.5f, 20.5f)
        if (horario) {
            c.drawArc(r, -60f, 300f, false, traco)
            c.drawLine(16.25f, 4.64f, 16.6f, 8.6f, traco); c.drawLine(16.25f, 4.64f, 20.1f, 3.9f, traco)
        } else {
            c.drawArc(r, 240f, -300f, false, traco)
            c.drawLine(7.75f, 4.64f, 7.4f, 8.6f, traco); c.drawLine(7.75f, 4.64f, 3.9f, 3.9f, traco)
        }
        c.drawText(numero, 12f, 14.3f, texto)
    }

    private fun estrela(c: Canvas, cx: Float, cy: Float, raio: Float) {
        p.reset()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) raio else raio * 0.45f
            val x = (cx + rr * cos(a)).toFloat(); val y = (cy + rr * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close(); c.drawPath(p, traco)
    }

    private fun desenhar(c: Canvas, f: Float) {
        val s = IconeCruz.sino(f)
        when (tipo) {
            Tipo.TOCANDO -> {   // equalizador: cinco barras que pulam enquanto o som sai
                val base = floatArrayOf(6f, 11f, 15f, 9f, 12f)
                for (i in 0..4) {
                    val alt = if (f < 0f) base[i] else 4f + 13f * (0.5f + 0.5f * sin(2 * PI * (f * 2 + i * 0.23)).toFloat()).let { it * it }
                    val x = 4f + i * 4f
                    c.drawLine(x, 20f, x, 20f - alt, traco)
                }
            }
            Tipo.PAUSAR -> { ret(c, 6.5f, 4.5f, 10f, 19.5f, 1.2f); ret(c, 14f, 4.5f, 17.5f, 19.5f, 1.2f) }
            Tipo.CONTINUAR -> tri(c, 7.5f, 4.5f, 15f)
            Tipo.VOLTAR -> pulo(c, horario = false, numero = "15")
            Tipo.AVANCAR -> pulo(c, horario = true, numero = "30")
            Tipo.VELOCIDADE -> {   // velocímetro com o ponteiro
                r.set(3f, 5f, 21f, 23f); c.drawArc(r, 180f, 180f, false, traco)
                c.drawLine(3f, 14f, 21f, 14f, traco)
                c.drawLine(12f, 14f, 16.5f, 8.5f, traco); c.drawCircle(12f, 14f, 1.1f, cheio)
            }
            Tipo.SILENCIO -> {   // onda do som com um trecho vazio no meio (o silêncio pulado)
                p.reset(); p.moveTo(2.5f, 12f); p.quadTo(4.5f, 5f, 6.5f, 12f); p.quadTo(8f, 17f, 9.5f, 12f); c.drawPath(p, traco)
                p.reset(); p.moveTo(14.5f, 12f); p.quadTo(16f, 7f, 17.5f, 12f); p.quadTo(19.5f, 19f, 21.5f, 12f); c.drawPath(p, traco)
                c.drawLine(11f, 12f, 11.4f, 12f, traco); c.drawLine(12.8f, 12f, 13.2f, 12f, traco)
            }
            Tipo.PARAR -> ret(c, 5.5f, 5.5f, 18.5f, 18.5f, 2f)
            Tipo.DORMIR -> {   // lua crescente (um círculo menos outro) e uma estrelinha
                p.reset(); p.addCircle(11f, 13f, 8.5f, Path.Direction.CW)
                val corte = Path().apply { addCircle(16.5f, 8.5f, 7f, Path.Direction.CW) }
                p.op(corte, Path.Op.DIFFERENCE); c.drawPath(p, traco)
                c.drawLine(19.5f, 15f, 19.5f, 18f, traco); c.drawLine(18f, 16.5f, 21f, 16.5f, traco)
            }
            Tipo.SAIR -> {   // porta com a seta saindo
                p.reset(); p.moveTo(13f, 4f); p.lineTo(5f, 4f); p.lineTo(5f, 20f); p.lineTo(13f, 20f); c.drawPath(p, traco)
                c.drawLine(10f, 12f, 20.5f, 12f, traco); c.drawLine(17f, 8.5f, 20.5f, 12f, traco); c.drawLine(17f, 15.5f, 20.5f, 12f, traco)
            }
            Tipo.FAVORITAS -> {   // estrela; o gesto acende um brilho na ponta de cima
                estrela(c, 12f, 12.8f, 9f)
                if (s > 0f) { traco.alpha = (255 * s).toInt(); c.drawLine(19f, 3f, 19f, 6f, traco); c.drawLine(17.5f, 4.5f, 20.5f, 4.5f, traco); traco.alpha = 255 }
            }
            Tipo.RECENTES -> {   // relógio; o ponteiro grande dá a volta
                c.drawCircle(12f, 12f, 9f, traco)
                val a = if (f < 0f) 0.0 else 2 * PI * f
                c.drawLine(12f, 12f, (12 + 6 * sin(a)).toFloat(), (12 - 6 * cos(a)).toFloat(), traco)
                c.drawLine(12f, 12f, 15.5f, 13.5f, traco)
            }
            Tipo.PERTO -> {   // pino de mapa que quica
                val dy = -2.2f * s
                p.reset(); p.moveTo(12f, 21f + dy)
                p.cubicTo(7f, 15f + dy, 5f, 12f + dy, 5f, 9.5f + dy); p.cubicTo(5f, 5.5f + dy, 8f, 2.8f + dy, 12f, 2.8f + dy)
                p.cubicTo(16f, 2.8f + dy, 19f, 5.5f + dy, 19f, 9.5f + dy); p.cubicTo(19f, 12f + dy, 17f, 15f + dy, 12f, 21f + dy)
                c.drawPath(p, traco); c.drawCircle(12f, 9.5f + dy, 2.6f, traco)
                if (s > 0f) { traco.alpha = (160 * s).toInt(); c.drawLine(9f, 22f, 15f, 22f, traco); traco.alpha = 255 }
            }
            Tipo.PAIS -> {   // globo; o meridiano gira
                c.drawCircle(12f, 12f, 9f, traco)
                c.drawLine(3f, 12f, 21f, 12f, traco)
                val k = if (f < 0f) 0.5f else (0.5f + 0.45f * cos(2 * PI * f).toFloat())
                r.set(12f - 9f * k * 0.9f, 3f, 12f + 9f * k * 0.9f, 21f); c.drawOval(r, traco)
            }
            Tipo.GENEROS -> {   // nota musical que sobe e volta
                val dy = -2.5f * s
                c.drawCircle(8f, 17.5f + dy, 3f, traco)
                c.drawLine(11f, 17.5f + dy, 11f, 4f + dy, traco)
                p.reset(); p.moveTo(11f, 4f + dy); p.quadTo(15f, 5.5f + dy, 18f, 9f + dy); c.drawPath(p, traco)
            }
            Tipo.BUSCAR -> {   // lupa que dá uma volta pequena, procurando
                val a = if (f < 0f) 0.0 else 2 * PI * f
                val dx = (1.6 * sin(a)).toFloat() * s; val dy = (1.6 * cos(a) - 1.6).toFloat() * s
                c.drawCircle(10.5f + dx, 10.5f + dy, 6.5f, traco)
                c.drawLine(15.3f + dx, 15.3f + dy, 20.5f + dx, 20.5f + dy, traco)
            }
            Tipo.EM_ALTA -> {   // a linha que sobe
                p.reset(); p.moveTo(3f, 18f); p.lineTo(9f, 12f); p.lineTo(13f, 15.5f); p.lineTo(20.5f, 7f); c.drawPath(p, traco)
                c.drawLine(20.5f, 7f, 15.5f, 7f, traco); c.drawLine(20.5f, 7f, 20.5f, 12f, traco)
            }
            Tipo.ADICIONAR -> { c.drawCircle(12f, 12f, 9f, traco); c.drawLine(12f, 7.5f, 12f, 16.5f, traco); c.drawLine(7.5f, 12f, 16.5f, 12f, traco) }
            Tipo.CELULAR -> { ret(c, 7f, 2.5f, 17f, 21.5f, 2.2f); c.drawLine(10.5f, 18.5f, 13.5f, 18.5f, traco) }
            Tipo.NOVOS -> {   // brilho de quatro pontas (o que chegou); o pequeno pisca
                p.reset(); p.moveTo(10f, 3f); p.quadTo(10.6f, 9.4f, 17f, 10f); p.quadTo(10.6f, 10.6f, 10f, 17f)
                p.quadTo(9.4f, 10.6f, 3f, 10f); p.quadTo(9.4f, 9.4f, 10f, 3f); p.close(); c.drawPath(p, traco)
                val k = 1f + 0.5f * s
                c.drawLine(18f, 15f - 2.5f * k, 18f, 15f + 2.5f * k, traco); c.drawLine(18f - 2.5f * k, 15f, 18f + 2.5f * k, 15f, traco)
            }
            Tipo.MEUS -> {   // capas empilhadas
                ret(c, 3f, 8f, 16f, 21f, 1.8f)
                p.reset(); p.moveTo(6.5f, 5.5f); p.lineTo(17f, 5.5f); p.quadTo(18.5f, 5.5f, 18.5f, 7f); p.lineTo(18.5f, 17.5f); c.drawPath(p, traco)
                p.reset(); p.moveTo(10f, 3f); p.lineTo(19.5f, 3f); p.quadTo(21f, 3f, 21f, 4.5f); p.lineTo(21f, 14f); c.drawPath(p, traco)
            }
            Tipo.EPISODIOS -> for (y in floatArrayOf(6f, 12f, 18f)) { c.drawCircle(4.5f, y, 0.9f, cheio); c.drawLine(8f, y, 20f, y, traco) }
            Tipo.RADIO -> {   // o mesmo rádio da cruz
                ret(c, 3f, 8f, 21f, 20f, 1.8f)
                c.drawLine(7f, 8f, 16.5f, 3.5f, traco)
                c.drawCircle(8.8f, 14f, 2.8f, traco); c.drawLine(14f, 12f, 18f, 12f, traco); c.drawLine(14f, 16f, 18f, 16f, traco)
            }
            Tipo.CONTROLE -> {   // controle de videogame; o gesto aperta os dois botões, um depois do outro
                ret(c, 2.5f, 7.5f, 21.5f, 17.5f, 5f)
                c.drawLine(5.5f, 12.5f, 9.5f, 12.5f, traco); c.drawLine(7.5f, 10.5f, 7.5f, 14.5f, traco)
                val k1 = if (f in 0f..0.5f) s else 0f; val k2 = if (f > 0.5f) s else 0f
                c.drawCircle(15.5f, 11.2f, 1.1f - 0.4f * k1, cheio); c.drawCircle(17.8f, 13.6f, 1.1f - 0.4f * k2, cheio)
            }
            Tipo.SOFA -> {   // sofá: encosto, assento com braços e os pés
                ret(c, 5.5f, 5.5f, 18.5f, 12f, 2f)
                ret(c, 2.5f, 10f, 21.5f, 17.5f, 2.2f)
                c.drawLine(6f, 13.5f, 18f, 13.5f, traco)
                c.drawLine(5f, 17.5f, 5f, 19.5f, traco); c.drawLine(19f, 17.5f, 19f, 19.5f, traco)
            }
            Tipo.TURMA -> {   // duas pessoas
                c.drawCircle(9f, 8.5f, 3f, traco)
                r.set(3f, 14f, 15f, 24f); c.drawArc(r, 180f, 180f, false, traco)
                c.drawCircle(16.5f, 9.5f, 2.4f, traco)
                p.reset(); p.moveTo(16.5f, 14.2f); p.quadTo(21f, 14.4f, 21.5f, 19f); c.drawPath(p, traco)
            }
            Tipo.REDE -> {   // sinal de wi-fi; o gesto acende os arcos de dentro para fora
                c.drawCircle(12f, 19f, 1.3f, cheio)
                for (i in 1..3) {
                    val raio = 4.5f * i
                    traco.alpha = if (f < 0f) 255 else (90 + 165 * (if (f * 3.2f >= i - 1) 1f else 0f)).toInt()
                    r.set(12f - raio, 19f - raio, 12f + raio, 19f + raio); c.drawArc(r, 225f, 90f, false, traco)
                }
                traco.alpha = 255
            }
            Tipo.PC -> {   // monitor com o pé
                ret(c, 3f, 4f, 21f, 16f, 1.8f)
                c.drawLine(12f, 16f, 12f, 19.5f, traco); c.drawLine(8f, 20f, 16f, 20f, traco)
            }
            Tipo.OPCOES -> for (x in floatArrayOf(6f, 12f, 18f)) c.drawCircle(x, 12f, 1.6f, cheio)
            Tipo.PODCAST -> {   // o mesmo microfone da cruz
                ret(c, 9f, 3f, 15f, 14f, 3f)
                r.set(5.5f, 6.5f, 18.5f, 17.5f); c.drawArc(r, 0f, 180f, false, traco)
                c.drawLine(12f, 17.5f, 12f, 20.5f, traco); c.drawLine(9f, 20.5f, 15f, 20.5f, traco)
            }
        }
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha; texto.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf; texto.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

package br.paulo.tvlauncher.home

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R

/**
 * Ícones da tela inicial em cruz (17/11/2026): objeto ou símbolo SOLTO, sem card nem círculo atrás (pedido do dono).
 * Dois estilos, desenhados em código (vetor, sem PNG: nítido em qualquer tamanho e quase sem memória):
 * - [Estilo.OBJETO] (padrão): o objeto ilustrado (uma TV, um rádio, um controle), quadro de 120 x 100;
 * - [Estilo.SIMBOLO]: o símbolo de traço, na cor do grupo, quadro de 24 x 24.
 * Os desenhos vêm da amostra aprovada (home-grupos/amostra-a-b.html). Cores e traço ficam aqui, num lugar só, para o
 * dono refinar depois.
 */
class IconeCruz(val tipo: Tipo, val estilo: Estilo = Estilo.OBJETO) : Drawable() {

    enum class Tipo { CONTINUAR, ASSISTIR, OUVIR, JOGAR, APLICATIVOS, TV, SERIES, FILMES, REDE, ADDONS, RADIO, PODCASTS, JOGOS, SOFA, TODOS_APPS, PLUGIN, DORMIR, CENTRAL }
    /**
     * [DUOTOM] (17/11/2026, 3º estilo embutido): o traço do Símbolo em branco quente sobre um halo suave na cor do grupo.
     * [LINHA] (18/11/2026, PADRÃO): o dono achou os desenhos "infantis". Traço fino de uma cor só (creme), desenho adulto
     * (TV de tela plana, rolo de filme), e só o item escolhido fica na cor da marca. Entrou no FIM do enum: o número já
     * gravado nas Extras (0, 1, 2) continua apontando para o mesmo estilo. Na tela se chama "Essencial" (21/11/2026).
     * [VINTAGE], [JOIA], [FOTO] e [VINTAGE2] (21/11/2026, escolhidos pelo Paulo na amostra): arte pronta em WebP
     * ([pasta] em assets/icon-sets, ver [IconSetArt]), cada uma com a parte que se mexe. Também no FIM do enum.
     */
    enum class Estilo(val pasta: String? = null) {
        OBJETO, SIMBOLO, DUOTOM, LINHA, VINTAGE("vintage"), JOIA("jewel"), FOTO("photoreal"), VINTAGE2("vintage2");
        /** Tem animação própria (a Linha desenhada em código; os de arte pela camada que se mexe). */
        val animado: Boolean get() = this == LINHA || pasta != null
    }

    /** Linha: true = item escolhido com a cruz em foco (o ImageView avisa pelo estado "activated"). */
    private var aceso = false

    // ---- animação própria de cada ícone (Linha; 18/11/2026, dono: "sutil, mas atraente; nada de só brilhar, crescer
    // ou girar, mas algo único de cada um"). Toca [DURACAO_MS] e descansa; parada, o desenho é exatamente o estático.
    /** Em ciclo agora (a HomeCruz liga no item escolhido e, devagar, nas categorias dos lados). */
    private var vivo = false
    private var descansoMs = DESCANSO_ESCOLHIDO_MS
    private var desdeMs = 0L
    /** Fase da passada de desenho atual: 0..1 tocando, -1 parado (desenho estático). */
    private var fase = -1f
    private val tique = Runnable { invalidateSelf() }

    /**
     * Liga ou desliga o ciclo. [descanso]: pausa entre duas passadas; [atraso]: quando começa a primeira (as categorias
     * dos lados não tocam juntas). Quem chama já confere "Remover animações" (HomeCruz.semAnimacao).
     */
    fun animar(liga: Boolean, descanso: Long = DESCANSO_ESCOLHIDO_MS, atraso: Long = 0L) {
        if (!estilo.animado) return
        if (liga == vivo && (!liga || descanso == descansoMs)) return
        vivo = liga
        descansoMs = descanso
        desdeMs = android.os.SystemClock.uptimeMillis() + atraso
        unscheduleSelf(tique)
        invalidateSelf()
    }

    /** Só para a prévia (teste de telas): desenha sempre nesta fase, sem relógio. */
    internal var faseFixa: Float? = null

    private fun faseAgora(): Float = faseFixa ?: if (!vivo) -1f else faseEm(android.os.SystemClock.uptimeMillis() - desdeMs, DURACAO_MS, descansoMs)

    /** Próximo quadro: ~30 por segundo tocando; parado no descanso, acorda só quando a próxima passada começa. */
    private fun agendar() {
        if (!vivo || callback == null) return
        val agora = android.os.SystemClock.uptimeMillis()
        unscheduleSelf(tique)
        scheduleSelf(tique, agora + proximoEm(agora - desdeMs, DURACAO_MS, descansoMs))
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) unscheduleSelf(tique) else if (vivo) invalidateSelf()
        return super.setVisible(visible, restart)
    }

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
        if (estilo == Estilo.LINHA) {
            val esc = minOf(b.width(), b.height()) / 24f
            c.translate((b.width() - 24f * esc) / 2f, (b.height() - 24f * esc) / 2f)
            c.scale(esc, esc)
            corTraco = corLinha(tipo)
            fase = faseAgora()
            linhaFina(c)
            if (fase >= 0f) linhaViva(c, fase)
            if (faseFixa == null) agendar()
        } else if (estilo.pasta != null && IconSetArt.camada(estilo.pasta, tipo, false) != null) {
            val esc = minOf(b.width() / 120f, b.height() / 100f)
            c.translate((b.width() - 120f * esc) / 2f, (b.height() - 100f * esc) / 2f)
            c.scale(esc, esc)
            fase = faseAgora()
            arte(c, estilo.pasta, fase)
            if (faseFixa == null) agendar()
        } else if (estilo == Estilo.OBJETO || estilo.pasta != null) {   // sem a arte (arquivo faltando): o Objeto
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

    // ------------------------------------------------------------------ arte pronta (Vintage, Joia, Fotorrealista, Vintage 2.0)

    private val pintura = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val quadro = RectF(0f, 0f, 120f, 100f)

    /**
     * A camada parada e, por cima, a que se mexe em [f] (0..1; -1 parada = o desenho exato da amostra). Cada
     * movimento começa e termina no lugar, sem salto: girar dá uma volta inteira com suavidade; balançar e sacudir
     * oscilam e assentam; empurrar, subir e crescer vão e voltam; piscar apaga e acende duas vezes.
     */
    private fun arte(c: Canvas, pasta: String, f: Float) {
        IconSetArt.camada(pasta, tipo, false)?.let { c.drawBitmap(it, null, quadro, pintura) }
        val a = IconSetArt.camada(pasta, tipo, true) ?: return
        val m = IconSetArt.movimento(pasta, tipo)
        if (f < 0f || m == null) { c.drawBitmap(a, null, quadro, pintura); return }
        val px = m.px * 120f
        val py = m.py * 100f
        val onda = kotlin.math.sin(f * Math.PI * 4).toFloat() * (1f - f)   // vai e volta duas vezes, cada vez menos
        c.save()
        when (m.m) {
            "spin" -> c.rotate(360f * easeInOut(f), px, py)
            "swing" -> c.rotate(12f * onda, px, py)
            "wiggle" -> c.rotate(9f * onda, px, py)
            "nudge" -> c.translate(7f * sino(f), 0f)
            "bob" -> c.translate(0f, -6f * sino(f))
            "pulse" -> { val e = 1f + 0.14f * sino(f); c.scale(e, e, px, py) }
            "twinkle" -> pintura.alpha = (255 * (1f - 0.75f * kotlin.math.abs(kotlin.math.sin(f * Math.PI * 2).toFloat()))).toInt()
        }
        c.drawBitmap(a, null, quadro, pintura)
        pintura.alpha = 255
        c.restore()
    }

    // ------------------------------------------------------------------ objeto (120 x 100)

    // Laranja da marca nos desenhos dos ícones: vem do token (design/tokens.json), não de um número solto.
    private val LARANJA: Long = DesignTokens.ORANGE.toLong() and 0xFFFFFFFFL
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
            Tipo.JOGAR, Tipo.JOGOS, Tipo.CENTRAL -> controle(c)
            Tipo.OUVIR -> fone(c)
            Tipo.CONTINUAR -> continuar(c)
            Tipo.SERIES -> series(c)
            Tipo.FILMES -> claquete(c)
            Tipo.REDE -> usb(c)
            Tipo.ADDONS, Tipo.PLUGIN -> peca(c)
            Tipo.RADIO -> radio(c)
            Tipo.PODCASTS -> microfone(c)
            Tipo.SOFA -> sofa(c)
            Tipo.APLICATIVOS, Tipo.TODOS_APPS -> quadradinhos(c)
            Tipo.DORMIR -> lua(c)
        }
        cheio.shader = null; cheio.alpha = 255
    }

    private fun tv(c: Canvas) {
        linha(c, 0xFF8A8F9C, 3f, 44f, 8f, 58f, 22f, 78f, 8f, 64f, 22f)
        vert(0xFF555B69, 0xFF23262E, 20f, 84f); rr(c, 12f, 20f, 96f, 64f, 13f)
        cheio.shader = LinearGradient(20f, 28f, 84f, 76f, cor(0xFF3B6EA8), cor(0xFFE0874A), Shader.TileMode.CLAMP); rr(c, 20f, 28f, 64f, 48f, 8f)
        solido(0xFFFFFFFF, .1f); rr(c, 20f, 28f, 64f, 14f, 7f)
        play(c, 45f, 41f, 22f, 18f)
        solido(LARANJA); c.drawCircle(96f, 38f, 4.5f, cheio)
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
        solido(LARANJA); c.drawCircle(86f, 46f, 5f, cheio)
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
        traco.color = cor(LARANJA); r.set(20f, 10f, 100f, 90f); c.drawArc(r, -90f, 223f, false, traco)   // 62% assistido
        vert(0xFFFF8A55, 0xFFD4501B, 23f, 77f); c.drawCircle(60f, 50f, 27f, cheio)
        play(c, 54f, 38f, 24f, 19f)
    }

    private fun series(c: Canvas) {
        solido(0xFF2F3440); rr(c, 30f, 12f, 78f, 54f, 10f)
        solido(0xFF4A5060); rr(c, 12f, 26f, 82f, 60f, 11f)
        vert(0xFF1F7480, 0xFF0D2B32, 33f, 79f); rr(c, 19f, 33f, 68f, 46f, 7f)
        play(c, 46f, 45f, 22f, 18f)
        solido(LARANJA); rr(c, 70f, 74f, 40f, 20f, 10f)
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

    /**
     * Símbolo USB ilustrado (Objeto) para "Rede" (pedido do Paulo, 20/11/2026: o nome "Do computador" e a pasta saíram).
     * Tronco com a seta em cima e a bolinha embaixo; um braço termina em círculo, o outro em quadrado.
     */
    private fun usb(c: Canvas) {
        val corTronco = 0xFF5A9BE0
        linha(c, corTronco, 8f, 60f, 92f, 60f, 28f, 60f, 76f, 30f, 58f, 30f, 58f, 30f, 46f, 60f, 64f, 90f, 48f, 90f, 48f, 90f, 38f)
        vert(0xFF8CC0F5, 0xFF3A6FB8, 8f, 106f)
        p.reset(); p.moveTo(60f, 8f); p.lineTo(73f, 30f); p.lineTo(47f, 30f); p.close(); c.drawPath(p, cheio)
        c.drawCircle(30f, 38f, 9f, cheio)
        rr(c, 81f, 21f, 18f, 18f, 3f)
        c.drawCircle(60f, 96f, 11f, cheio)
        solido(0xFFFFFFFF, .25f); c.drawCircle(57f, 93f, 4f, cheio)
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
        solido(LARANJA); rr(c, 81f, 46f, 2.5f, 13f, 0f)
        solido(0xFF3A2418); c.drawCircle(76f, 76f, 6.5f, cheio); c.drawCircle(93f, 76f, 6.5f, cheio)
    }

    private fun microfone(c: Canvas) {
        vert(0xFFE3E6EC, 0xFF8A909C, 8f, 60f); rr(c, 44f, 8f, 32f, 52f, 16f)
        linha(c, 0xFF6B7080, 2f, 48f, 22f, 72f, 22f, 48f, 30f, 72f, 30f, 48f, 38f, 72f, 38f, 48f, 46f, 72f, 46f)
        traco.shader = null; traco.color = cor(0xFF5A606C); traco.strokeWidth = 6f
        r.set(32f, 14f, 88f, 70f); c.drawArc(r, 0f, 180f, false, traco)
        traco.strokeWidth = 7f; c.drawLine(60f, 70f, 60f, 84f, traco); c.drawLine(42f, 90f, 78f, 90f, traco)
        solido(LARANJA); c.drawCircle(92f, 18f, 6f, cheio)
    }

    private fun sofa(c: Canvas) {
        solido(0xFF7A5A45); rr(c, 22f, 30f, 76f, 36f, 11f)
        solido(0xFFFFFFFF, .1f); rr(c, 26f, 34f, 68f, 8f, 4f)
        solido(0xFF8F6C53); rr(c, 8f, 48f, 24f, 38f, 11f); rr(c, 88f, 48f, 24f, 38f, 11f)
        solido(0xFFA68066); rr(c, 27f, 60f, 66f, 22f, 7f)
        linha(c, 0xFF7A5A45, 2f, 60f, 62f, 60f, 80f)
        linha(c, 0xFF3A2A20, 5f, 20f, 86f, 20f, 94f, 100f, 86f, 100f, 94f)
    }

    /** "Dormir em 30 min" (onda 3): lua crescente creme com duas estrelinhas. */
    private fun lua(c: Canvas) {
        val fora = Path().apply { addCircle(52f, 52f, 42f, Path.Direction.CW) }
        val dentro = Path().apply { addCircle(74f, 36f, 34f, Path.Direction.CW) }
        fora.op(dentro, Path.Op.DIFFERENCE)
        vert(0xFFFFEBC4, 0xFFE8A55E, 10f, 94f); c.drawPath(fora, cheio)
        solido(0xFFFFFFFF, .22f); c.drawCircle(26f, 50f, 6f, cheio)
        solido(0xFFFFD166); estrela(c, 96f, 74f, 10f); estrela(c, 108f, 44f, 6.5f)
    }

    private fun estrela(c: Canvas, x: Float, y: Float, raio: Float) {
        p.reset()
        for (i in 0 until 8) {
            val a = i * Math.PI / 4 - Math.PI / 2
            val rr = if (i % 2 == 0) raio else raio * 0.38f
            val px = x + (rr * Math.cos(a)).toFloat(); val py = y + (rr * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close(); c.drawPath(p, cheio)
    }

    private fun quadradinhos(c: Canvas) {
        solido(0xFFFF8A55); rr(c, 20f, 10f, 38f, 38f, 10f)
        solido(0xFF7DD3C0); rr(c, 62f, 10f, 38f, 38f, 10f)
        solido(0xFF9AA3FF); rr(c, 20f, 52f, 38f, 38f, 10f)
        solido(0xFFFFD166); rr(c, 62f, 52f, 38f, 38f, 10f)
        if (tipo == Tipo.TODOS_APPS) { solido(0xFF1A1D24, .55f); for (i in 0..2) c.drawCircle(64f + i * 13f, 71f, 3.2f, cheio) }
    }

    // ------------------------------------------------------------------ símbolo (24 x 24, traço na cor do grupo)

    /**
     * Cor do Símbolo e do Duotom = a MESMA da Linha (crítica de design 19/11/2026, decisão 3): o estilo muda o traço e a
     * luz, nunca o matiz. Antes cada estilo tinha a sua paleta (Ouvir verde-água numa, azul noutra; Jogar violeta ou lavanda).
     */
    private fun corDoGrupo(): Int = corLinha(tipo)

    private fun simbolo(c: Canvas) {
        traco.shader = null; traco.color = corTraco; traco.strokeWidth = 1.5f; traco.pathEffect = null
        cheio.shader = null; cheio.color = corTraco; cheio.alpha = 255
        when (tipo) {
            Tipo.ASSISTIR, Tipo.TV -> IconeApp(IconeApp.Tipo.TV, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.SERIES -> IconeApp(IconeApp.Tipo.SERIES, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.FILMES -> IconeApp(IconeApp.Tipo.FILMES, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.REDE -> IconeApp(IconeApp.Tipo.REDE, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
            Tipo.JOGAR, Tipo.JOGOS, Tipo.CENTRAL -> IconeApp(IconeApp.Tipo.JOGOS, corTraco).also { it.setBounds(0, 0, 24, 24) }.draw(c)
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
            Tipo.DORMIR -> {
                // lua crescente: dois arcos que se encontram nas pontas
                p.reset(); p.moveTo(14.5f, 3.6f)
                p.cubicTo(8f, 4.5f, 3.6f, 9.6f, 4.2f, 14.6f); p.cubicTo(4.9f, 19.6f, 10.6f, 22.4f, 16.6f, 19.6f)
                p.cubicTo(11.4f, 18.6f, 8.6f, 14.4f, 9.6f, 10f); p.cubicTo(10.2f, 7.2f, 12f, 5f, 14.5f, 3.6f); p.close()
                c.drawPath(p, traco)
                c.drawLine(17f, 6f, 21f, 6f, traco); c.drawLine(21f, 6f, 17f, 10f, traco); c.drawLine(17f, 10f, 21f, 10f, traco)
            }
        }
    }

    // ------------------------------------------------------------------ linha (24 x 24, traço fino de uma cor)

    private fun linhaFina(c: Canvas) {
        traco.shader = null; traco.color = corTraco; traco.strokeWidth = TRACO_FINO; traco.pathEffect = null; traco.alpha = 255
        cheio.shader = null; cheio.color = corTraco; cheio.alpha = 255
        fun ret(x0: Float, y0: Float, x1: Float, y1: Float, raio: Float) { r.set(x0, y0, x1, y1); c.drawRoundRect(r, raio, raio, traco) }
        fun tri(x: Float, y: Float, alt: Float) {   // "play" vazado
            p.reset(); p.moveTo(x, y); p.lineTo(x, y + alt); p.lineTo(x + alt * 0.86f, y + alt / 2); p.close(); c.drawPath(p, traco)
        }
        when (tipo) {
            Tipo.CONTINUAR -> {   // seta que dá a volta (retomar) com o play no meio
                r.set(3.5f, 3.5f, 20.5f, 20.5f); c.drawArc(r, -60f, 300f, false, traco)
                c.drawLine(16.25f, 4.64f, 16.6f, 8.6f, traco); c.drawLine(16.25f, 4.64f, 20.1f, 3.9f, traco)
                tri(10.3f, 8.8f, 6.4f)
            }
            Tipo.ASSISTIR, Tipo.TV -> {   // a silhueta EXATA da TV-sofá da logo, só o contorno de fora (18/11/2026, Paulo)
                // Medidas do SVG da marca (quadro 120 x 112) x 0,2, descidas 0,8 para centrar no quadro de 24.
                // Menor que o quadro ([ESCALA_TV]): embaixo do ícone da TV vem a barra do programa, que não pode encostar.
                c.save(); c.scale(ESCALA_TV, ESCALA_TV, 12f, 9f)
                // Antenas saindo do alto da tela (no SVG o pino fica por baixo da moldura cheia; aqui só há contorno).
                if (fase < 0f) { c.drawLine(12f, 6.5f, 7f, 2.9f, traco); c.drawLine(12f, 6.5f, 17.8f, 4.1f, traco) }   // tocando: as da linhaViva
                p.reset()
                p.moveTo(3.4f, 12.6f); p.lineTo(3.4f, 11f); p.quadTo(3.4f, 6.5f, 7.9f, 6.5f); p.lineTo(16.1f, 6.5f)   // tela
                p.quadTo(20.6f, 6.5f, 20.6f, 11f); p.lineTo(20.6f, 12.6f)
                p.lineTo(21.4f, 12.6f); p.quadTo(23.3f, 12.6f, 23.3f, 14.5f); p.lineTo(23.3f, 18.8f)                // braço direito
                p.quadTo(23.3f, 20.7f, 21.8f, 20.7f)
                p.lineTo(22.14f, 22.5f); p.quadTo(22.2f, 23f, 21.72f, 23f); p.lineTo(21.28f, 23f); p.quadTo(20.8f, 23f, 20.6f, 22.56f)   // pé direito
                p.lineTo(19.77f, 20.7f); p.lineTo(4.23f, 20.7f)                                                       // base
                p.lineTo(3.4f, 22.56f); p.quadTo(3.2f, 23f, 2.72f, 23f); p.lineTo(2.28f, 23f); p.quadTo(1.8f, 23f, 1.86f, 22.5f)   // pé esquerdo
                p.lineTo(2.2f, 20.7f); p.quadTo(0.7f, 20.7f, 0.7f, 18.8f); p.lineTo(0.7f, 14.5f); p.quadTo(0.7f, 12.6f, 2.6f, 12.6f)   // braço esquerdo
                p.close()
                c.drawPath(p, traco)
                // O brilhinho do vidro no canto da tela, como na logo (parado; tocando, o da linhaViva atravessa a tela).
                if (fase < 0f) { p.reset(); p.moveTo(7.4f, 13.8f); p.quadTo(7.4f, 11.6f, 9.6f, 11.4f); c.drawPath(p, traco) }
                c.restore()
            }
            Tipo.SERIES -> {   // quadros empilhados (episódios)
                ret(2.5f, 8f, 17.5f, 19.5f, 1.6f)
                p.reset(); p.moveTo(5.5f, 5f); p.lineTo(19f, 5f); p.quadTo(20.5f, 5f, 20.5f, 6.5f); p.lineTo(20.5f, 16.5f); c.drawPath(p, traco)
                tri(8.6f, 11.2f, 5f)
            }
            Tipo.FILMES -> {   // rolo de filme: o quadro com as perfurações dos lados
                ret(3f, 3.5f, 21f, 20.5f, 1.6f)
                c.drawLine(7.5f, 3.5f, 7.5f, 20.5f, traco); c.drawLine(16.5f, 3.5f, 16.5f, 20.5f, traco)
                if (fase < 0f) for (y in floatArrayOf(8f, 12f, 16f)) { c.drawLine(3f, y, 7.5f, y, traco); c.drawLine(16.5f, y, 21f, y, traco) }
            }
            Tipo.REDE -> {   // símbolo USB ("Rede", pedido do Paulo 20/11/2026): tronco, seta, braços com círculo e quadrado
                c.drawLine(12f, 18f, 12f, 5.5f, traco)
                p.reset(); p.moveTo(12f, 2.2f); p.lineTo(14.3f, 5.8f); p.lineTo(9.7f, 5.8f); p.close(); c.drawPath(p, cheio)
                p.reset(); p.moveTo(12f, 15f); p.lineTo(6.5f, 11.8f); p.lineTo(6.5f, 9.4f); c.drawPath(p, traco)
                c.drawCircle(6.5f, 7.8f, 1.6f, cheio)
                p.reset(); p.moveTo(12f, 12.6f); p.lineTo(17.5f, 9.6f); p.lineTo(17.5f, 7.9f); c.drawPath(p, traco)
                r.set(16.1f, 5f, 18.9f, 7.8f); c.drawRect(r, cheio)
                c.drawCircle(12f, 19.8f, 2.1f, cheio)
            }
            Tipo.ADDONS, Tipo.PLUGIN -> {   // peça de encaixe
                p.reset(); p.moveTo(4f, 7f); p.lineTo(9f, 7f); p.arcTo(RectF(9f, 4.5f, 14f, 9.5f), 180f, 180f); p.lineTo(18f, 7f); p.lineTo(18f, 11f)
                p.arcTo(RectF(15.5f, 11f, 20.5f, 16f), 270f, -180f); p.lineTo(18f, 20f); p.lineTo(4f, 20f); p.close(); c.drawPath(p, traco)
            }
            Tipo.OUVIR -> {   // fone: arco e as duas conchas
                r.set(4f, 4f, 20f, 20f); c.drawArc(r, 180f, 180f, false, traco)
                ret(3f, 13f, 7.5f, 20f, 1.6f); ret(16.5f, 13f, 21f, 20f, 1.6f)
            }
            Tipo.RADIO -> {   // rádio: caixa, alça, alto-falante e dial
                ret(3f, 8f, 21f, 20f, 1.8f)
                c.drawLine(7f, 8f, 16.5f, 3.5f, traco)
                c.drawCircle(8.8f, 14f, 2.8f, traco); c.drawLine(14f, 12f, 18f, 12f, traco); c.drawLine(14f, 16f, 18f, 16f, traco)
            }
            Tipo.PODCASTS -> {   // microfone de estúdio
                ret(9f, 3f, 15f, 14f, 3f)
                r.set(5.5f, 6.5f, 18.5f, 17.5f); c.drawArc(r, 0f, 180f, false, traco)
                c.drawLine(12f, 17.5f, 12f, 20.5f, traco); c.drawLine(9f, 20.5f, 15f, 20.5f, traco)
            }
            Tipo.JOGAR, Tipo.JOGOS -> {   // controle
                p.reset()
                p.moveTo(7f, 7f); p.lineTo(17f, 7f)
                p.quadTo(20.5f, 7f, 21.3f, 11f); p.lineTo(22f, 15.5f)
                p.quadTo(22.4f, 18.5f, 20f, 18.5f); p.quadTo(18.4f, 18.5f, 17f, 16.5f); p.lineTo(16f, 15f); p.lineTo(8f, 15f)
                p.lineTo(7f, 16.5f); p.quadTo(5.6f, 18.5f, 4f, 18.5f); p.quadTo(1.6f, 18.5f, 2f, 15.5f)
                p.lineTo(2.7f, 11f); p.quadTo(3.5f, 7f, 7f, 7f); p.close()
                c.drawPath(p, traco)
                c.drawLine(5.6f, 11f, 9.4f, 11f, traco); c.drawLine(7.5f, 9.1f, 7.5f, 12.9f, traco)
                c.drawCircle(15.6f, 11.8f, 0.9f, cheio); c.drawCircle(18f, 10f, 0.9f, cheio)
            }
            Tipo.CENTRAL -> {   // mostrador (o painel de informações da Central de jogos)
                r.set(3f, 5f, 21f, 23f); c.drawArc(r, 150f, 240f, false, traco)
                if (fase < 0f) c.drawLine(12f, 14f, 16f, 9.5f, traco)   // tocando, o ponteiro é o da linhaViva
                c.drawCircle(12f, 14f, 1.3f, cheio)
                c.drawLine(5.5f, 19.5f, 18.5f, 19.5f, traco)
            }
            Tipo.SOFA -> {
                ret(5f, 7f, 19f, 13.5f, 1.8f); ret(2.5f, 11f, 6.5f, 18f, 1.6f); ret(17.5f, 11f, 21.5f, 18f, 1.6f)
                c.drawLine(6.5f, 15f, 17.5f, 15f, traco); c.drawLine(4.5f, 18f, 4.5f, 20.5f, traco); c.drawLine(19.5f, 18f, 19.5f, 20.5f, traco)
            }
            Tipo.APLICATIVOS -> for (x in floatArrayOf(4f, 13.5f)) for (y in floatArrayOf(4f, 13.5f)) ret(x, y, x + 6.5f, y + 6.5f, 1.6f)
            Tipo.TODOS_APPS -> for (x in floatArrayOf(4f, 10.5f, 17f)) for (y in floatArrayOf(4f, 10.5f, 17f)) ret(x, y, x + 3f, y + 3f, 0.8f)
            Tipo.DORMIR -> {   // lua crescente e uma estrela pequena
                p.reset(); p.moveTo(14.5f, 3.6f)
                p.cubicTo(8f, 4.5f, 3.6f, 9.6f, 4.2f, 14.6f); p.cubicTo(4.9f, 19.6f, 10.6f, 22.4f, 16.6f, 19.6f)
                p.cubicTo(11.4f, 18.6f, 8.6f, 14.4f, 9.6f, 10f); p.cubicTo(10.2f, 7.2f, 12f, 5f, 14.5f, 3.6f); p.close()
                c.drawPath(p, traco)
                c.drawLine(18.5f, 5f, 18.5f, 9f, traco); c.drawLine(16.5f, 7f, 20.5f, 7f, traco)
            }
        }
    }

    // Linha: o ImageView marca "activated" no item escolhido (HomeCruz.posicionar); desde 18/11/2026 cada ícone já tem
    // a cor dele e o escolhido ganha a animação (ligada pela HomeCruz), então o estado só redesenha.
    override fun isStateful() = estilo == Estilo.LINHA
    override fun onStateChange(state: IntArray): Boolean {
        val novo = estilo == Estilo.LINHA && state.contains(android.R.attr.state_activated)
        if (novo == aceso) return false
        aceso = novo
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) { cheio.alpha = alpha; traco.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { cheio.colorFilter = cf; traco.colorFilter = cf; pintura.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // ------------------------------------------------------------------ linha viva (a animação de cada ícone)

    /**
     * Desenha, por cima do estático, o que se mexe em [f] (0..1). Cada ícone conta algo dele mesmo: a TV liga (uma
     * linha de varredura desce pela tela), o filme corre na película, o rádio solta ondas pela antena, o fone toca um
     * equalizador, o controle aperta os botões, o ponteiro da Central dá a volta e assenta... Em f = 0 e f = 1 nada
     * aparece além do estático (a passada começa e termina sem salto).
     */
    private fun linhaViva(c: Canvas, f: Float) {
        val sino = sino(f)   // 0 -> 1 -> 0
        fun alfa(a: Float) { traco.alpha = (a.coerceIn(0f, 1f) * 255).toInt(); cheio.alpha = traco.alpha }
        when (tipo) {
            Tipo.CONTINUAR -> {   // a seta corre a volta inteira e o play dá um passo à frente
                val corre = (f / 0.7f).coerceIn(0f, 1f)
                if (corre in 0.001f..0.999f) {
                    val ang = Math.toRadians((-60.0 + 300.0 * corre))
                    alfa(sino(corre)); c.drawCircle(12f + 8.5f * Math.cos(ang).toFloat(), 12f + 8.5f * Math.sin(ang).toFloat(), 1.1f, cheio)
                }
                val passo = sino(((f - 0.6f) / 0.4f).coerceIn(0f, 1f))
                if (passo > 0f) { alfa(passo); c.drawLine(17.6f, 10f, 18.6f, 12f, traco); c.drawLine(18.6f, 12f, 17.6f, 14f, traco) }
            }
            Tipo.ASSISTIR, Tipo.TV -> {   // a TV-sofá mexe as antenas, como quem procura o sinal
                // As antenas balançam (o miolo continua vazio: nada desenhado dentro do contorno).
                c.save(); c.scale(ESCALA_TV, ESCALA_TV, 12f, 9f)
                // As antenas balançam procurando o sinal...
                alfa(1f); c.save(); c.rotate(-12f * sino, 12f, 6.5f)
                c.drawLine(12f, 6.5f, 7f, 2.9f, traco); c.drawLine(12f, 6.5f, 17.8f, 4.1f, traco); c.restore()
                // ...e o brilhinho do vidro atravessa a tela da esquerda para a direita e volta ao canto.
                val dx = 7.6f * sino
                p.reset(); p.moveTo(7.4f + dx, 13.8f); p.quadTo(7.4f + dx, 11.6f, 9.6f + dx, 11.4f); c.drawPath(p, traco)
                c.restore()
            }
            Tipo.SERIES -> {   // o próximo episódio: o quadro de trás sobe e volta
                alfa(sino); val d = 1.4f * sino
                p.reset(); p.moveTo(5.5f + d, 5f - d); p.lineTo(19f + d, 5f - d); p.quadTo(20.5f + d, 5f - d, 20.5f + d, 6.5f - d); p.lineTo(20.5f + d, 16.5f - d)
                c.drawPath(p, traco)
            }
            Tipo.FILMES -> {   // a película corre: as perfurações descem um quadro
                c.save(); r.set(3.6f, 4.1f, 20.4f, 19.9f); c.clipRect(r)
                val off = 4f * easeInOut(f)   // as perfurações estáticas (8, 12, 16) descem um quadro e voltam ao lugar
                for (y in floatArrayOf(4f, 8f, 12f, 16f)) {
                    val yy = y + off
                    alfa(1f); c.drawLine(3.6f, yy, 7.5f, yy, traco); c.drawLine(16.5f, yy, 20.4f, yy, traco)
                }
                c.restore()
            }
            Tipo.REDE -> {   // um sinal sobe pelo tronco até a seta (dado passando)
                val y = 17.5f - 11f * easeInOut(f)
                alfa(sino); c.drawCircle(12f, y, 1.3f, cheio)
            }
            Tipo.ADDONS, Tipo.PLUGIN -> {   // uma peça nova chega e encaixa no dente da direita
                val x = 6f * (1f - easeOut((f / 0.6f).coerceIn(0f, 1f)))
                alfa(sino(((f) / 0.9f).coerceIn(0f, 1f)))
                r.set(18.8f + x, 12.3f, 22f + x, 14.7f); c.drawRoundRect(r, 0.8f, 0.8f, traco)
            }
            Tipo.OUVIR -> {   // o som toca: um equalizador pequeno entre as conchas
                for ((i, x) in floatArrayOf(10f, 12f, 14f).withIndex()) {
                    val h = sino * (1.5f + 2.6f * kotlin.math.abs(kotlin.math.sin((f * 3f + i * 0.33f) * Math.PI.toFloat())))
                    if (h > 0.05f) { alfa(1f); c.drawLine(x, 19.5f, x, 19.5f - h, traco) }
                }
            }
            Tipo.RADIO -> {   // a antena solta ondas
                for (k in 0..1) {
                    val g = ((f - k * 0.3f) / 0.7f).coerceIn(0f, 1f)
                    if (g <= 0f || g >= 1f) continue
                    val raio = 1.5f + 3.5f * g
                    alfa(sino(g)); r.set(16.5f - raio, 3.5f - raio, 16.5f + raio, 3.5f + raio); c.drawArc(r, -80f, 90f, false, traco)
                }
            }
            Tipo.PODCASTS -> {   // alguém fala: o nível sobe na cápsula
                val n = (sino * 3.99f).toInt()
                alfa(1f); for (i in 0 until n) { val y = 11.5f - i * 2.3f; c.drawLine(10.8f, y, 13.2f, y, traco) }
            }
            Tipo.JOGAR, Tipo.JOGOS -> {   // os botões são apertados, um e depois o outro
                val a = sino(((f) / 0.5f).coerceIn(0f, 1f)); val b = sino(((f - 0.5f) / 0.5f).coerceIn(0f, 1f))
                if (a > 0f) { alfa(a); c.drawCircle(15.6f, 11.8f, 1.9f, traco) }
                if (b > 0f) { alfa(b); c.drawCircle(18f, 10f, 1.9f, traco) }
            }
            Tipo.CENTRAL -> {   // o ponteiro vai ao começo do mostrador e volta, assentando
                val ang = if (f < 0.3f) -48f - 112f * easeOut(f / 0.3f) else -160f + 112f * voltaComMola((f - 0.3f) / 0.7f)
                val rad = Math.toRadians(ang.toDouble())
                alfa(1f); c.drawLine(12f, 14f, 12f + 6f * Math.cos(rad).toFloat(), 14f + 6f * Math.sin(rad).toFloat(), traco)
            }
            Tipo.SOFA -> {   // alguém senta: a almofada afunda e volta
                alfa(sino); c.drawLine(8f, 13.5f + 0.9f * sino, 16f, 13.5f + 0.9f * sino, traco)
            }
            Tipo.APLICATIVOS -> {   // os quadros se acendem um depois do outro (Z)
                for ((i, xy) in arrayOf(floatArrayOf(4f, 4f), floatArrayOf(13.5f, 4f), floatArrayOf(4f, 13.5f), floatArrayOf(13.5f, 13.5f)).withIndex()) {
                    val g = sino(((f - i * 0.15f) / 0.55f).coerceIn(0f, 1f))
                    if (g > 0f) { cheio.alpha = (g * 0.55f * 255).toInt(); r.set(xy[0] + 1.6f, xy[1] + 1.6f, xy[0] + 4.9f, xy[1] + 4.9f); c.drawRoundRect(r, 0.8f, 0.8f, cheio) }
                }
            }
            Tipo.TODOS_APPS -> {   // uma onda em diagonal pelos nove
                for ((i, x) in floatArrayOf(4f, 10.5f, 17f).withIndex()) for ((j, y) in floatArrayOf(4f, 10.5f, 17f).withIndex()) {
                    val g = sino(((f - (i + j) * 0.12f) / 0.5f).coerceIn(0f, 1f))
                    if (g > 0f) { cheio.alpha = (g * 0.7f * 255).toInt(); r.set(x, y, x + 3f, y + 3f); c.drawRoundRect(r, 0.8f, 0.8f, cheio) }
                }
            }
            Tipo.DORMIR -> {   // um "z" pequeno sobe da lua e some
                val y = 15.5f - 5f * f; val x = 14.5f + 1.5f * f; val t = 1.6f
                alfa(sino); c.drawLine(x, y, x + t, y, traco); c.drawLine(x + t, y, x, y + t, traco); c.drawLine(x, y + t, x + t, y + t, traco)
            }
        }
        traco.alpha = 255; cheio.alpha = 255
    }

    companion object {
        /** Uma passada da animação, e o descanso entre passadas (item escolhido / categoria dos lados). */
        const val DURACAO_MS = 1500L
        const val DESCANSO_ESCOLHIDO_MS = 2500L
        const val DESCANSO_CATEGORIA_MS = 9000L
        private const val QUADRO_MS = 33L

        /** Fase 0..1 se está tocando em [t] ms desde o começo do ciclo, -1 no descanso ou antes de começar. */
        fun faseEm(t: Long, duracao: Long, descanso: Long): Float {
            if (t < 0) return -1f
            val m = t % (duracao + descanso)
            return if (m < duracao) m.toFloat() / duracao else -1f
        }

        /** Daqui a quantos ms vale redesenhar: um quadro tocando; o fim do descanso (ou do atraso) parado. */
        fun proximoEm(t: Long, duracao: Long, descanso: Long): Long {
            if (t < 0) return -t
            val m = t % (duracao + descanso)
            return if (m < duracao) QUADRO_MS else (duracao + descanso - m).coerceAtLeast(1)
        }

        /** 0 -> 1 -> 0, suave. */
        fun sino(f: Float): Float = if (f <= 0f || f >= 1f) 0f else kotlin.math.sin(f * Math.PI).toFloat()
        private fun easeOut(f: Float) = 1f - (1f - f) * (1f - f)
        private fun easeInOut(f: Float) = if (f < 0.5f) 2f * f * f else 1f - 2f * (1f - f) * (1f - f)
        /** Sobe passando um pouco do ponto e assenta (o ponteiro da Central). */
        private fun voltaComMola(f: Float): Float = 1f + 0.12f * kotlin.math.sin(f * Math.PI * 2).toFloat() * (1f - f) - (1f - f) * (1f - f) * (1f - f)

        /**
         * Linha (18/11/2026, dono: "todos com a mesma cor ficou sem personalidade"): cada ícone com a cor dele, tons
         * adultos (médios, nada saturado de brinquedo, nada rosado) que leem bem no fundo escuro. As categorias e seus
         * itens ficam na mesma família; dentro da coluna, cada item tem o seu tom.
         */
        fun corLinha(t: Tipo): Int = when (t) {
            Tipo.CONTINUAR -> DesignTokens.WORLD_CONTINUE      // âmbar
            Tipo.ASSISTIR, Tipo.TV -> DesignTokens.WORLD_WATCH   // a família quente da marca
            Tipo.SERIES -> 0xFFF59E5B.toInt()         // laranja claro
            Tipo.FILMES -> 0xFFE6C46A.toInt()         // dourado
            Tipo.REDE -> DesignTokens.WORLD_NETWORK           // verde-água
            Tipo.ADDONS, Tipo.PLUGIN -> 0xFF8FB4F0.toInt()   // azul acinzentado
            Tipo.OUVIR, Tipo.RADIO -> DesignTokens.WORLD_LISTEN   // turquesa
            Tipo.PODCASTS -> 0xFF7FB2FF.toInt()       // azul
            Tipo.JOGAR, Tipo.JOGOS, Tipo.CENTRAL -> DesignTokens.WORLD_PLAY   // violeta-azulado
            Tipo.SOFA -> 0xFFA9B0FF.toInt()
            Tipo.APLICATIVOS, Tipo.TODOS_APPS -> DesignTokens.WORLD_APPS   // verde-folha
            Tipo.DORMIR -> 0xFFB9C6E4.toInt()         // luar
        }

        /** A cor clara do traço (Símbolo/Duotom), também nos ícones de app do pacote embutido. */
        const val COR_TRACO = 0xFFFFF3E6.toInt()
        private const val COR_CLARA = 0xFFFFF3E6.toInt()
        /** Linha: o item escolhido acende na cor da marca (vermelho-laranja; nada rosado). */
        val COR_ACESA: Int get() = br.paulo.tvlauncher.Estilo.ACENTO   // segue o alto contraste (estudo global 20/11)
        /** Linha: traço de 1,25 no quadro de 24 (o Símbolo usa 1,5). */
        const val TRACO_FINO = 1.25f
        /** A TV-sofá desenhada menor que o quadro: a barra do programa embaixo do ícone não pode encostar (Paulo, 18/11/2026). */
        const val ESCALA_TV = 0.76f
    }
}

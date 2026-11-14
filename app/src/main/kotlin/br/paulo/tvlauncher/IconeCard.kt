package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Icone pequeno (desenhado em codigo, sem arquivos de imagem) para o lado esquerdo do titulo dos cards do
 * painel rápido (PainelRapidoActivity): mesmo espirito do SinalWifi e do IndicadorPilha, so que aqui um simbolo fixo
 * por [tipo] ("bluetooth", "sistema", "audio", "launcher", "protetor", "timer", "acessibilidade", "fechar"). Traço fino.
 * [cor] muda com o foco (card branco = ícone escuro), pelo [definirCor].
 */
class IconeCard(ctx: Context, private val tipo: String) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFE8EEEA.toInt()
    }
    private val preenchimento = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE8EEEA.toInt()
    }
    private val caminho = Path()
    private val caixa = RectF()

    /** Troca a cor do traço (foco: o card fica branco e o ícone escurece junto com o texto). */
    fun definirCor(cor: Int) {
        tinta.color = cor; preenchimento.color = cor
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (tipo) {
            "bluetooth" -> bluetooth(c, w, h)
            "sistema" -> sistema(c, w, h)
            "audio" -> audio(c, w, h)
            "launcher" -> launcher(c, w, h)
            "protetor" -> protetor(c, w, h)
            "timer" -> timer(c, w, h)
            "acessibilidade" -> acessibilidade(c, w, h)
            "fechar" -> fechar(c, w, h)
            "wifi" -> wifi(c, w, h)
            "dormir" -> dormir(c, w, h)
            "despertador" -> despertador(c, w, h)
            "visita" -> visita(c, w, h)
            "casa" -> casa(c, w, h)
            // Tela Casa (13/11/2026): um ícone por tipo de aparelho do Home Assistant.
            "lampada" -> lampada(c, w, h)
            "tomada" -> tomada(c, w, h)
            "ventilador" -> ventilador(c, w, h)
            "cortina" -> cortina(c, w, h)
            "cena" -> cena(c, w, h)
            "rotina" -> rotina(c, w, h)
            "chave" -> chave(c, w, h)
            // Ideia 51: ar-condicionado, termômetro (temperatura e umidade) e porta (sensores de abrir, movimento, água).
            "ar" -> {
                caixa.set(w * 0.08f, h * 0.18f, w * 0.92f, h * 0.52f); c.drawRoundRect(caixa, w * 0.08f, w * 0.08f, tinta)
                c.drawLine(w * 0.22f, h * 0.42f, w * 0.78f, h * 0.42f, tinta)
                for (x in listOf(0.30f, 0.50f, 0.70f)) c.drawLine(w * x, h * 0.64f, w * (x - 0.04f), h * 0.84f, tinta)
            }
            "termometro" -> {
                caixa.set(w * 0.40f, h * 0.06f, w * 0.60f, h * 0.66f); c.drawRoundRect(caixa, w * 0.10f, w * 0.10f, tinta)
                c.drawCircle(w * 0.5f, h * 0.76f, w * 0.16f, tinta)
                c.drawCircle(w * 0.5f, h * 0.76f, w * 0.07f, preenchimento)
            }
            "camera" -> {   // ideia 52
                caixa.set(w * 0.06f, h * 0.30f, w * 0.68f, h * 0.74f); c.drawRoundRect(caixa, w * 0.06f, w * 0.06f, tinta)
                caminho.reset(); caminho.moveTo(w * 0.68f, h * 0.46f); caminho.lineTo(w * 0.94f, h * 0.32f)
                caminho.lineTo(w * 0.94f, h * 0.72f); caminho.lineTo(w * 0.68f, h * 0.58f); c.drawPath(caminho, tinta)
            }
            "porta" -> {
                caixa.set(w * 0.24f, h * 0.08f, w * 0.76f, h * 0.92f); c.drawRoundRect(caixa, w * 0.04f, w * 0.04f, tinta)
                c.drawCircle(w * 0.64f, h * 0.52f, w * 0.045f, preenchimento)
            }
            // Painel rápido (14/11/2026, ideia 42): filtro noturno e DNS.
            "filtro" -> filtro(c, w, h)
            "dns" -> escudo(c, w, h)
            "pausar" -> { c.drawLine(w * 0.36f, h * 0.22f, w * 0.36f, h * 0.78f, tinta); c.drawLine(w * 0.64f, h * 0.22f, w * 0.64f, h * 0.78f, tinta) }
            "tocar" -> { caminho.reset(); caminho.moveTo(w * 0.30f, h * 0.18f); caminho.lineTo(w * 0.82f, h * 0.5f); caminho.lineTo(w * 0.30f, h * 0.82f); caminho.close(); c.drawPath(caminho, tinta) }
            "proxima" -> {
                caminho.reset(); caminho.moveTo(w * 0.22f, h * 0.20f); caminho.lineTo(w * 0.64f, h * 0.5f); caminho.lineTo(w * 0.22f, h * 0.80f); caminho.close()
                c.drawPath(caminho, tinta); c.drawLine(w * 0.78f, h * 0.20f, w * 0.78f, h * 0.80f, tinta)
            }
        }
    }

    /** Wi-Fi: três arcos e o ponto. */
    private fun wifi(c: Canvas, w: Float, h: Float) {
        for ((i, r) in listOf(0.46f, 0.31f, 0.16f).withIndex()) {
            caixa.set(w * (0.5f - r), h * (0.84f - r), w * (0.5f + r), h * (0.84f + r))
            c.drawArc(caixa, 225f, 90f, false, tinta)
            if (i == 2) c.drawCircle(w * 0.5f, h * 0.84f, w * 0.05f, preenchimento)
        }
    }

    /** Dormir agora: o símbolo de ligar/desligar. */
    private fun dormir(c: Canvas, w: Float, h: Float) {
        caixa.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.90f)
        c.drawArc(caixa, -60f, 300f, false, tinta)
        c.drawLine(w * 0.5f, h * 0.08f, w * 0.5f, h * 0.50f, tinta)
    }

    /** Despertador: o mostrador com os ponteiros e as duas "campainhas". */
    private fun despertador(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.5f, h * 0.56f, w * 0.34f, tinta)
        c.drawLine(w * 0.5f, h * 0.38f, w * 0.5f, h * 0.56f, tinta)
        c.drawLine(w * 0.5f, h * 0.56f, w * 0.64f, h * 0.64f, tinta)
        c.drawLine(w * 0.10f, h * 0.20f, w * 0.26f, h * 0.08f, tinta)
        c.drawLine(w * 0.90f, h * 0.20f, w * 0.74f, h * 0.08f, tinta)
    }

    /** Modo visita: duas pessoas. */
    private fun visita(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.38f, h * 0.30f, w * 0.15f, tinta)
        caixa.set(w * 0.10f, h * 0.58f, w * 0.66f, h * 1.14f)
        c.drawArc(caixa, 180f, 180f, false, tinta)
        caixa.set(w * 0.62f, h * 0.14f, w * 0.86f, h * 0.46f)
        c.drawArc(caixa, -90f, 180f, false, tinta)
        caixa.set(w * 0.62f, h * 0.60f, w * 0.98f, h * 1.10f)
        c.drawArc(caixa, 270f, 90f, false, tinta)
    }

    /** Protetor de tela: sol baixo sobre duas montanhas (o desenho do tile do Google, em linha). */
    private fun protetor(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.74f, h * 0.24f, w * 0.10f, tinta)   // sol no canto (no meio parecia uma cabeça)
        caminho.reset()
        caminho.moveTo(w * 0.04f, h * 0.88f)
        caminho.lineTo(w * 0.38f, h * 0.50f)
        caminho.lineTo(w * 0.58f, h * 0.70f)
        caminho.lineTo(w * 0.72f, h * 0.56f)
        caminho.lineTo(w * 0.96f, h * 0.88f)
        c.drawPath(caminho, tinta)
    }

    /** Timer de suspensão: lua crescente (duas curvas que se encontram nas pontas). */
    private fun timer(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.62f, h * 0.06f)
        caminho.cubicTo(w * 0.02f, h * 0.10f, w * 0.02f, h * 0.92f, w * 0.62f, h * 0.94f)
        caminho.cubicTo(w * 0.76f, h * 0.94f, w * 0.88f, h * 0.86f, w * 0.94f, h * 0.76f)
        caminho.cubicTo(w * 0.52f, h * 0.84f, w * 0.30f, h * 0.30f, w * 0.62f, h * 0.06f)
        c.drawPath(caminho, tinta)
    }

    /** Acessibilidade: o boneco de braços abertos (cabeça, braços, tronco e pernas). */
    private fun acessibilidade(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.5f, h * 0.13f, w * 0.09f, preenchimento)
        c.drawLine(w * 0.10f, h * 0.34f, w * 0.90f, h * 0.34f, tinta)
        c.drawLine(w * 0.5f, h * 0.34f, w * 0.5f, h * 0.62f, tinta)
        c.drawLine(w * 0.5f, h * 0.62f, w * 0.32f, h * 0.94f, tinta)
        c.drawLine(w * 0.5f, h * 0.62f, w * 0.68f, h * 0.94f, tinta)
    }

    /** Dispensar (notificação): um X. */
    private fun fechar(c: Canvas, w: Float, h: Float) {
        c.drawLine(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f, tinta)
        c.drawLine(w * 0.78f, h * 0.22f, w * 0.22f, h * 0.78f, tinta)
    }

    /** O "laço duplo" do simbolo Bluetooth: duas pontas no centro formando um X alongado. */
    private fun bluetooth(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val topo = h * 0.08f; val fundo = h * 0.92f
        val meioCima = h * 0.32f; val meioBaixo = h * 0.68f
        val direita = cx + w * 0.22f; val esquerda = cx - w * 0.22f
        // A runa inteira (antes faltavam a haste e as duas diagonais da esquerda e o ícone virava um ")" na Sala).
        caminho.reset()
        caminho.moveTo(esquerda, meioCima)
        caminho.lineTo(direita, meioBaixo)
        caminho.lineTo(cx, fundo)
        caminho.lineTo(cx, topo)
        caminho.lineTo(direita, meioCima)
        caminho.lineTo(esquerda, meioBaixo)
        c.drawPath(caminho, tinta)
    }

    /** Engrenagem simplificada: anel central com 6 dentes ao redor. */
    private fun sistema(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f
        val rOut = w * 0.46f; val rIn = w * 0.30f
        for (i in 0 until 6) {
            c.save()
            c.rotate(i * 60f, cx, cy)
            caixa.set(cx - w * 0.08f, cy - rOut, cx + w * 0.08f, cy - rIn * 0.82f)
            c.drawRoundRect(caixa, w * 0.03f, w * 0.03f, preenchimento)
            c.restore()
        }
        c.drawCircle(cx, cy, rIn, tinta)
    }

    /** Alto-falante: corpo + cone, com duas ondas de som abrindo para a direita. */
    private fun audio(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.06f, h * 0.38f)
        caminho.lineTo(w * 0.30f, h * 0.38f)
        caminho.lineTo(w * 0.52f, h * 0.14f)
        caminho.lineTo(w * 0.52f, h * 0.86f)
        caminho.lineTo(w * 0.30f, h * 0.62f)
        caminho.lineTo(w * 0.06f, h * 0.62f)
        caminho.close()
        c.drawPath(caminho, tinta)
        caixa.set(w * 0.56f, h * 0.30f, w * 0.80f, h * 0.70f)
        c.drawArc(caixa, -50f, 100f, false, tinta)
        caixa.set(w * 0.68f, h * 0.14f, w * 1.00f, h * 0.86f)
        c.drawArc(caixa, -45f, 90f, false, tinta)
    }

    /** Grade 2x2: quatro quadradinhos arredondados — "nossas configs", o app em si. */
    private fun launcher(c: Canvas, w: Float, h: Float) {
        val gap = w * 0.14f
        val lado = (w - gap) / 2f
        val raio = lado * 0.22f
        for (linha in 0..1) for (coluna in 0..1) {
            val x0 = coluna * (lado + gap); val y0 = linha * (lado + gap)
            caixa.set(x0, y0, x0 + lado, y0 + lado)
            c.drawRoundRect(caixa, raio, raio, preenchimento)
        }
    }

    /** Casa (Google Home): telhado e paredes em traço, com a porta. */
    private fun casa(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.08f, h * 0.48f); caminho.lineTo(w * 0.5f, h * 0.10f); caminho.lineTo(w * 0.92f, h * 0.48f)
        caminho.moveTo(w * 0.20f, h * 0.38f); caminho.lineTo(w * 0.20f, h * 0.88f); caminho.lineTo(w * 0.80f, h * 0.88f); caminho.lineTo(w * 0.80f, h * 0.38f)
        caminho.moveTo(w * 0.42f, h * 0.88f); caminho.lineTo(w * 0.42f, h * 0.62f); caminho.lineTo(w * 0.58f, h * 0.62f); caminho.lineTo(w * 0.58f, h * 0.88f)
        c.drawPath(caminho, tinta)
    }

    /** Luz: bulbo redondo com o pescoço e duas linhas da rosca. */
    private fun lampada(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.38f, h * 0.70f)
        caminho.cubicTo(w * 0.38f, h * 0.58f, w * 0.18f, h * 0.50f, w * 0.18f, h * 0.36f)
        caminho.cubicTo(w * 0.18f, h * 0.16f, w * 0.34f, h * 0.04f, w * 0.5f, h * 0.04f)
        caminho.cubicTo(w * 0.66f, h * 0.04f, w * 0.82f, h * 0.16f, w * 0.82f, h * 0.36f)
        caminho.cubicTo(w * 0.82f, h * 0.50f, w * 0.62f, h * 0.58f, w * 0.62f, h * 0.70f)
        caminho.close()
        c.drawPath(caminho, tinta)
        c.drawLine(w * 0.38f, h * 0.82f, w * 0.62f, h * 0.82f, tinta)
        c.drawLine(w * 0.43f, h * 0.94f, w * 0.57f, h * 0.94f, tinta)
    }

    /** Tomada/interruptor: o plugue com os dois pinos e o fio. */
    private fun tomada(c: Canvas, w: Float, h: Float) {
        c.drawLine(w * 0.36f, h * 0.06f, w * 0.36f, h * 0.28f, tinta)
        c.drawLine(w * 0.64f, h * 0.06f, w * 0.64f, h * 0.28f, tinta)
        caminho.reset()
        caminho.moveTo(w * 0.20f, h * 0.28f); caminho.lineTo(w * 0.80f, h * 0.28f); caminho.lineTo(w * 0.80f, h * 0.48f)
        caminho.cubicTo(w * 0.80f, h * 0.64f, w * 0.66f, h * 0.74f, w * 0.5f, h * 0.74f)
        caminho.cubicTo(w * 0.34f, h * 0.74f, w * 0.20f, h * 0.64f, w * 0.20f, h * 0.48f)
        caminho.close()
        c.drawPath(caminho, tinta)
        c.drawLine(w * 0.5f, h * 0.74f, w * 0.5f, h * 0.96f, tinta)
    }

    /** Ventilador: miolo e três pás curvas. */
    private fun ventilador(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.07f, tinta)
        for (i in 0 until 3) {
            c.save(); c.rotate(i * 120f, w * 0.5f, h * 0.5f)
            caminho.reset()
            caminho.moveTo(w * 0.5f, h * 0.43f)
            caminho.cubicTo(w * 0.40f, h * 0.28f, w * 0.42f, h * 0.06f, w * 0.58f, h * 0.06f)
            caminho.cubicTo(w * 0.72f, h * 0.06f, w * 0.70f, h * 0.30f, w * 0.56f, h * 0.45f)
            c.drawPath(caminho, tinta)
            c.restore()
        }
    }

    /** Cortina/persiana: trilho em cima e três lâminas. */
    private fun cortina(c: Canvas, w: Float, h: Float) {
        c.drawLine(w * 0.08f, h * 0.10f, w * 0.92f, h * 0.10f, tinta)
        caixa.set(w * 0.16f, h * 0.10f, w * 0.84f, h * 0.90f)
        c.drawRoundRect(caixa, w * 0.04f, w * 0.04f, tinta)
        c.drawLine(w * 0.16f, h * 0.36f, w * 0.84f, h * 0.36f, tinta)
        c.drawLine(w * 0.16f, h * 0.62f, w * 0.84f, h * 0.62f, tinta)
    }

    /** Cena: a estrela de quatro pontas (brilho) e uma pequena ao lado. */
    private fun cena(c: Canvas, w: Float, h: Float) {
        fun brilho(cx: Float, cy: Float, r: Float) {
            caminho.reset()
            caminho.moveTo(cx, cy - r)
            caminho.quadTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
            caminho.quadTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
            caminho.quadTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
            caminho.quadTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
            c.drawPath(caminho, tinta)
        }
        brilho(w * 0.42f, h * 0.56f, w * 0.36f)
        brilho(w * 0.82f, h * 0.18f, w * 0.13f)
    }

    /** Rotina (script): o triângulo de "rodar" dentro de um círculo. */
    private fun rotina(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.44f, tinta)
        caminho.reset()
        caminho.moveTo(w * 0.42f, h * 0.32f); caminho.lineTo(w * 0.68f, h * 0.5f); caminho.lineTo(w * 0.42f, h * 0.68f); caminho.close()
        c.drawPath(caminho, tinta)
    }

    /** Chave liga/desliga (input_boolean): a pílula com a bolinha. */
    private fun chave(c: Canvas, w: Float, h: Float) {
        caixa.set(w * 0.04f, h * 0.28f, w * 0.96f, h * 0.72f)
        c.drawRoundRect(caixa, h * 0.22f, h * 0.22f, tinta)
        c.drawCircle(w * 0.70f, h * 0.5f, h * 0.12f, preenchimento)
    }

    /** Filtro noturno: um círculo com a metade de baixo cheia (a tela "esquentada"). */
    private fun filtro(c: Canvas, w: Float, h: Float) {
        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.40f, tinta)
        caixa.set(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f)
        c.drawArc(caixa, 0f, 180f, true, preenchimento)
    }

    /** DNS (o filtro de sites da casa): um escudo. */
    private fun escudo(c: Canvas, w: Float, h: Float) {
        caminho.reset()
        caminho.moveTo(w * 0.5f, h * 0.06f)
        caminho.lineTo(w * 0.86f, h * 0.20f)
        caminho.quadTo(w * 0.86f, h * 0.70f, w * 0.5f, h * 0.94f)
        caminho.quadTo(w * 0.14f, h * 0.70f, w * 0.14f, h * 0.20f)
        caminho.close()
        c.drawPath(caminho, tinta)
    }
}

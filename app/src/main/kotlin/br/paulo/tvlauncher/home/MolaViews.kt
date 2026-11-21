package br.paulo.tvlauncher.home

import android.provider.Settings
import android.view.Choreographer
import android.view.View
import br.paulo.tvlauncher.listen.CruzOuvir

/**
 * O movimento "orgânico" da cruz (dono, 18/11/2026) para quem não é a HomeCruz: cada view com quatro propriedades em mola
 * (x, y, transparência, escala), cada uma com posição E velocidade, num só relógio (Choreographer). Uma tecla no meio do
 * caminho muda o alvo sem frear: a mola continua da velocidade que tinha. É a mesma conta da HomeCruz
 * (aceleração = -w²·(posição - alvo) - 2·ζ·w·velocidade), em arquivo próprio para a cruz do Ouvir ([CruzOuvir]).
 *
 * Só translação, alfa e escala (propriedades do RenderNode): nada mede nem desenha de novo enquanto anda. Com alfa ou
 * escala mudando, a view ganha camada de GPU (o texto é rasterizado uma vez, não a cada quadro) e perde ao parar.
 *
 * [dono] é a view que pede os quadros (enquanto estiver na janela).
 */
class MolaViews(private val dono: View) {

    companion object {
        /** Rigidez (rad/s): ~0,3 s até assentar. Mesmos números da HomeCruz para as duas telas andarem iguais. */
        const val W = 17f
        /** < 1 passa um tiquinho do alvo e volta; 1 = sem passar (para saltos laterais grandes). */
        const val AMORTECE = 0.86f
        const val AMORTECE_SALTO = 1f

        /** "Remover animações" (acessibilidade) ou escala de animação 0: tudo vai direto. */
        fun semAnimacao(v: View) = runCatching {
            Settings.Global.getFloat(v.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    private inner class Mola(val v: View) {
        val pos = FloatArray(4); val vel = FloatArray(4); val alvo = FloatArray(4)
        private var proximo: FloatArray? = null
        private var trocaEm = 0L
        private var camada = false
        private var w = W
        private var zeta = AMORTECE

        fun ler() { pos[0] = v.translationX; pos[1] = v.translationY; pos[2] = v.alpha; pos[3] = v.scaleX }

        fun parar(x: Float, y: Float, alfa: Float, escala: Float) {
            ativas.remove(this); proximo = null
            vel.fill(0f); alvo[0] = x; alvo[1] = y; alvo[2] = alfa; alvo[3] = escala
            pos.indices.forEach { pos[it] = alvo[it] }
            aplicar(); semCamada()
            if (alfa == 0f) v.visibility = View.INVISIBLE
        }

        fun mirar(novo: FloatArray, quando: Long, rigidez: Float, amortece: Float) {
            if (this !in ativas) { ler(); vel.fill(0f); pos.indices.forEach { alvo[it] = pos[it] } }
            proximo = novo; trocaEm = quando; w = rigidez; zeta = amortece
        }

        /** Anda dt segundos; devolve true se ainda está em movimento. */
        fun andar(agora: Long, dt: Float): Boolean {
            proximo?.let { if (agora >= trocaEm) { it.copyInto(alvo); proximo = null } }
            val zw2 = 2f * zeta * w; val ww = w * w
            var t = dt
            while (t > 0f) {   // passos de até 4 ms: estável mesmo se um quadro atrasar
                val h = minOf(t, 0.004f); t -= h
                for (i in 0..3) {
                    val z = if (i == 2) 2f * w else zw2   // alfa sem passar do alvo: piscar chama atenção
                    vel[i] += (-ww * (pos[i] - alvo[i]) - z * vel[i]) * h
                    pos[i] += vel[i] * h
                }
            }
            pos[2] = pos[2].coerceIn(0f, 1f)
            val parado = proximo == null &&
                kotlin.math.abs(pos[0] - alvo[0]) < 0.5f && kotlin.math.abs(pos[1] - alvo[1]) < 0.5f &&
                kotlin.math.abs(pos[2] - alvo[2]) < 0.004f && kotlin.math.abs(pos[3] - alvo[3]) < 0.002f &&
                kotlin.math.abs(vel[0]) < 8f && kotlin.math.abs(vel[1]) < 8f
            if (parado) { pos.indices.forEach { pos[it] = alvo[it] }; vel.fill(0f) }
            if (!parado && (kotlin.math.abs(pos[2] - alvo[2]) > 0.004f || kotlin.math.abs(pos[3] - alvo[3]) > 0.002f)) comCamada()
            aplicar()
            if (parado) { semCamada(); if (alvo[2] == 0f) v.visibility = View.INVISIBLE }
            return !parado
        }

        private fun aplicar() {
            v.translationX = pos[0]; v.translationY = pos[1]; v.alpha = pos[2]; v.scaleX = pos[3]; v.scaleY = pos[3]
        }
        private fun comCamada() { if (!camada && v.isAttachedToWindow) { camada = true; v.setLayerType(View.LAYER_TYPE_HARDWARE, null) } }
        private fun semCamada() { if (camada) { camada = false; v.setLayerType(View.LAYER_TYPE_NONE, null) } }
    }

    private val molas = HashMap<View, Mola>()
    private val ativas = ArrayList<Mola>()
    private var quadroPedido = false
    private var ultimoQuadro = 0L
    private val quadro: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val dt = if (ultimoQuadro == 0L) 1f / 60f else ((t - ultimoQuadro) / 1e9f).coerceIn(0f, 1f / 20f)
            ultimoQuadro = t
            val agora = android.os.SystemClock.uptimeMillis()
            ativas.removeAll { !it.andar(agora, dt) }
            if (ativas.isNotEmpty() && dono.isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
            else quadroPedido = false
        }
    }

    /** Leva [v] até (x, y, alfa, escala): em mola quando [animar], senão direto. [atrasoMs]: a "onda" dos vizinhos. */
    fun mover(v: View, x: Float, y: Float, alfa: Float, escala: Float, animar: Boolean, atrasoMs: Long = 0L,
              rigidez: Float = W, amortece: Float = AMORTECE) {
        val m = molas.getOrPut(v) { Mola(v) }
        if (alfa > 0f) v.visibility = View.VISIBLE
        if (!animar || v.visibility != View.VISIBLE || semAnimacao(dono)) { m.parar(x, y, alfa, escala); return }
        m.mirar(floatArrayOf(x, y, alfa, escala), android.os.SystemClock.uptimeMillis() + atrasoMs, rigidez, amortece)
        if (m !in ativas) ativas += m
        if (!quadroPedido) { quadroPedido = true; ultimoQuadro = 0L; Choreographer.getInstance().postFrameCallback(quadro) }
    }

    /** A view saiu da tela de vez (lista refeita): esquece a mola dela. */
    fun esquecer(v: View) { molas.remove(v)?.let { ativas.remove(it) } }

    /** Fora da tela: tudo direto no lugar (nada fica no meio do caminho). */
    fun pararTudo() {
        Choreographer.getInstance().removeFrameCallback(quadro); quadroPedido = false
        ativas.toList().forEach { it.andar(Long.MAX_VALUE, 5f) }
        ativas.clear()
    }
}

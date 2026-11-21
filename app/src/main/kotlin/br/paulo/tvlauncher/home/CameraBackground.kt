package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.family.RecadoImagem

/**
 * Câmera da casa como fundo (21/11/2026, item 2 do plano para passar o Projectivy: "câmera como fundo, liga com a Casa").
 *
 * Porquê: o Projectivy mostra uma câmera RTSP no fundo; a Sofaly já fala com o Home Assistant (CasaHA) e já vê as
 * câmeras dele. Para quê: ver o quintal, o portão ou o bebê enquanto a TV está na tela inicial; e, com a campainha,
 * ver quem chegou no fundo da tela inicial (MainActivity troca o fundo por 2 min).
 * Como: a foto de agora da câmera pelo Home Assistant (/api/camera_proxy), a cada [INTERVALO_MS], com troca suave entre
 * duas camadas. Só busca com a tela visível; sem rede, fica a última foto (ou o degradê escuro). Sem RTSP direto nesta
 * fase: o HA já converte qualquer câmera (RTSP, ONVIF, nuvem) e guarda a senha dela, que nunca passa pela TV.
 * Custo: 1 foto JPEG a cada 2 s (~100 KB) só com a HOME na frente.
 */
class CameraBackground(ctx: Context, private val camera: String, private val emSegundoPlano: (() -> Unit) -> Unit) :
    FrameLayout(ctx), FundoVivo {

    companion object { const val INTERVALO_MS = 2000L }

    private val camadas = Array(2) { ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f } }
    private var frente = 0
    @Volatile private var buscando = false
    private var falhasSeguidas = 0

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF15182A.toInt(), 0xFF0B0D18.toInt()))
        camadas.forEach { addView(it, LayoutParams(-1, -1)) }
        // Véu embaixo, onde ficam os nomes da cruz (o mesmo cuidado da Galeria).
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x330A0C08, 0x000A0C08, 0xC0080907.toInt()))
        }, LayoutParams(-1, -1))
    }

    private val tique = object : Runnable {
        override fun run() {
            buscar()
            // Câmera fora do ar: espaça as tentativas (2 s, 4 s ... até 30 s) em vez de bater no HA a cada 2 s.
            postDelayed(this, (INTERVALO_MS shl falhasSeguidas.coerceAtMost(4)).coerceAtMost(30_000L))
        }
    }

    private fun buscar() {
        if (buscando) return
        buscando = true
        val app = context.applicationContext
        val pedidoEm = SystemClock.elapsedRealtime()
        emSegundoPlano {
            val bmp: Bitmap? = runCatching { RecadoImagem.decodificar(CasaHA.fotoCamera(app, camera)) }.getOrNull()
            post {
                buscando = false
                if (bmp == null) { falhasSeguidas++; return@post }
                falhasSeguidas = 0
                mostrar(bmp, SystemClock.elapsedRealtime() - pedidoEm)
            }
        }
    }

    private fun mostrar(bmp: Bitmap, demorouMs: Long) {
        val nova = camadas[1 - frente]; val velha = camadas[frente]
        nova.setImageBitmap(bmp)
        // Troca curta (a câmera muda pouco entre fotos); a primeira foto entra com a transição escolhida.
        val ms = if (velha.drawable == null) br.paulo.tvlauncher.settings.BackgroundStyle.fadeMs(context) else minOf(400L, demorouMs)
        nova.animate().cancel(); velha.animate().cancel()
        // Cruzamento (como nos plugins): a nova acende e a velha apaga; a ordem das camadas não muda (véu fica por cima).
        nova.alpha = 0f
        nova.animate().alpha(1f).setDuration(ms).start()
        velha.animate().alpha(0f).setDuration(ms).withEndAction { if (velha !== camadas[frente]) velha.setImageDrawable(null) }.start()
        frente = 1 - frente
    }

    override fun atualizar(e: EstadoCeu) {}   // a câmera não muda com o céu

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        removeCallbacks(tique)
        if (v == View.VISIBLE) post(tique)
    }

    override fun onDetachedFromWindow() { removeCallbacks(tique); super.onDetachedFromWindow() }
}

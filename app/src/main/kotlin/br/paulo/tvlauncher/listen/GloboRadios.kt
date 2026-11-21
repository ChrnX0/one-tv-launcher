package br.paulo.tvlauncher.listen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * O globo das rádios (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o Radio Garden (referência do estudo) mostrou que girar o mundo e "cair" numa rádio de outro país é o jeito
 * mais gostoso de descobrir rádio; nenhum app de TV tem isso no controle remoto.
 * Para quê: ◀ ▶ giram o globo, ▲ ▼ inclinam; a mira no centro pega a rádio mais perto, OK toca, VOLTAR fecha.
 * Como: projeção ortográfica (o globo visto de longe) desenhada no Canvas; cada rádio é um ponto laranja da marca (as
 * rádios desenham os continentes sozinhas: onde tem gente, tem rádio). Sem mapa de costas: nada a baixar além da lista
 * do Radio Browser. A conta é pura e testada em GloboRadiosTest.
 */
object GloboLogica {
    data class Ponto(val x: Double, val y: Double, val frente: Double)

    private fun rad(g: Double) = g * PI / 180

    /** Projeção ortográfica centrada em (lat0, lon0): x, y em -1..1 e "frente" > 0 quando o ponto está do lado visível. */
    fun projetar(lat: Double, lon: Double, lat0: Double, lon0: Double): Ponto {
        val f = rad(lat); val l = rad(lon - lon0); val f0 = rad(lat0)
        return Ponto(cos(f) * sin(l), cos(f0) * sin(f) - sin(f0) * cos(f) * cos(l), sin(f0) * sin(f) + cos(f0) * cos(f) * cos(l))
    }

    /** Distância no globo, em graus. */
    fun distancia(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val c = sin(rad(lat1)) * sin(rad(lat2)) + cos(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon1 - lon2))
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /** A rádio mais perto da mira, até [maxGraus] (longe demais = nenhuma: a mira está no oceano). */
    fun maisPerto(l: List<Radios.Radio>, lat0: Double, lon0: Double, maxGraus: Double = 6.0): Radios.Radio? =
        l.filter { !it.lat.isNaN() }.minByOrNull { distancia(it.lat, it.lon, lat0, lon0) }
            ?.takeIf { distancia(it.lat, it.lon, lat0, lon0) <= maxGraus }

    fun longitude(l: Double): Double { var x = (l + 180) % 360; if (x < 0) x += 360; return x - 180 }

    /** Onde começar: o meio das rádios do país da TV (ou o Brasil, ou 0,0). */
    fun centroDoPais(l: List<Radios.Radio>, pais: String): Pair<Double, Double> {
        val d = l.filter { it.pais.equals(pais, true) && !it.lat.isNaN() }
        if (d.isEmpty()) return 0.0 to 0.0
        return d.map { it.lat }.sorted()[d.size / 2] to d.map { it.lon }.sorted()[d.size / 2]
    }
}

/** A tela do globo, por cima da tela Rádio. [aoTocar] toca a rádio da mira; [aoFechar] tira o globo. */
@SuppressLint("ViewConstructor")
class GloboRadiosView(ctx: Context, private val radios: List<Radios.Radio>, inicio: Pair<Double, Double>,
                      private val aoTocar: (Radios.Radio) -> Unit, private val aoFechar: () -> Unit) : View(ctx) {
    private var lat0 = inicio.first.coerceIn(-60.0, 70.0)
    private var lon0 = inicio.second
    private var alvoLat = lat0
    private var alvoLon = lon0
    private val d = resources.displayMetrics.density
    private val comLugar = radios.filter { !it.lat.isNaN() }
    var mira: Radios.Radio? = null
        private set
    /** Avisado quando a rádio da mira muda (a tela escreve o nome embaixo). */
    var aoMirar: (Radios.Radio?) -> Unit = {}

    private val pPonto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6A2B.toInt(); strokeCap = Paint.Cap.ROUND }
    private val pLinha = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; style = Paint.Style.STROKE; strokeWidth = d }
    private val pMira = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val pEsfera = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pts = FloatArray(comLugar.size * 2)

    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(0xF20B0D1A.toInt()) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = raio(); val cx = w / 2f; val cy = h / 2f
        pEsfera.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.4f, intArrayOf(0xFF1E2A4A.toInt(), 0xFF0E1428.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun raio() = min(width, height) * 0.42f

    override fun onDraw(c: Canvas) {
        // Anda um pedaço até o alvo a cada quadro: o giro fica macio mesmo com a tecla segura.
        lat0 += (alvoLat - lat0) * 0.25; lon0 += GloboLogica.longitude(alvoLon - lon0) * 0.25
        val r = raio(); val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, r, pEsfera)
        // Paralelos e meridianos a cada 30°: dão a forma de bola.
        for (lat in -60..60 step 30) linha(c, cx, cy, r, (0..72).map { lat.toDouble() to it * 5.0 - 180 })
        for (lon in -180 until 180 step 30) linha(c, cx, cy, r, (0..36).map { it * 5.0 - 90 to lon.toDouble() })
        var n = 0
        for (x in comLugar) {
            val p = GloboLogica.projetar(x.lat, x.lon, lat0, lon0)
            if (p.frente <= 0.05) continue
            pts[n++] = cx + (p.x * r).toFloat(); pts[n++] = cy - (p.y * r).toFloat()
        }
        pPonto.strokeWidth = 3.2f * d; pPonto.alpha = 230
        c.drawPoints(pts, 0, n, pPonto)
        val m = GloboLogica.maisPerto(comLugar, lat0, lon0)
        if (m != mira) { mira = m; aoMirar(m) }
        m?.let {
            val p = GloboLogica.projetar(it.lat, it.lon, lat0, lon0)
            pPonto.strokeWidth = 9 * d; pPonto.alpha = 255
            c.drawPoint(cx + (p.x * r).toFloat(), cy - (p.y * r).toFloat(), pPonto)
        }
        c.drawCircle(cx, cy, 14 * d, pMira)
        if (kotlin.math.abs(alvoLat - lat0) > 0.05 || kotlin.math.abs(GloboLogica.longitude(alvoLon - lon0)) > 0.05) postInvalidateOnAnimation()
    }

    private fun linha(c: Canvas, cx: Float, cy: Float, r: Float, l: List<Pair<Double, Double>>) {
        var ax = 0f; var ay = 0f; var tem = false
        for ((la, lo) in l) {
            val p = GloboLogica.projetar(la, lo, lat0, lon0)
            val x = cx + (p.x * r).toFloat(); val y = cy - (p.y * r).toFloat()
            if (p.frente > 0 && tem) c.drawLine(ax, ay, x, y, pLinha)
            tem = p.frente > 0; ax = x; ay = y
        }
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        // Segurar a tecla acelera (como rolar a lista): 4° no toque, até 12° segurando.
        val passo = (4.0 + e.repeatCount).coerceAtMost(12.0)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> alvoLon = GloboLogica.longitude(alvoLon - passo)
            KeyEvent.KEYCODE_DPAD_RIGHT -> alvoLon = GloboLogica.longitude(alvoLon + passo)
            KeyEvent.KEYCODE_DPAD_UP -> alvoLat = (alvoLat + passo).coerceAtMost(75.0)
            KeyEvent.KEYCODE_DPAD_DOWN -> alvoLat = (alvoLat - passo).coerceAtLeast(-60.0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (e.repeatCount == 0) mira?.let(aoTocar); return true }
            KeyEvent.KEYCODE_BACK -> { if (e.repeatCount == 0) aoFechar(); return true }
            else -> return super.onKeyDown(keyCode, e)
        }
        postInvalidateOnAnimation()
        return true
    }
}

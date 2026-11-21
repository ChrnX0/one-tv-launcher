package br.paulo.tvlauncher.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.view.View
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.home.EstadoCeu
import br.paulo.tvlauncher.home.FundoEscuroPronto
import br.paulo.tvlauncher.home.FundoVivo
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * O tema "Céu de verdade" (caminho 2 do protetor que reage ao tempo, 17/11/2026): o mapa da região da pessoa, escuro,
 * com o ponto da cidade e as nuvens de agora por cima. Paulo escolheu (cartão de 17/11 01:33Z): nuvens REAIS do satélite
 * GOES da NOAA nas Américas (dado do governo dos EUA, livre inclusive para app pago), e nuvens desenhadas pelo céu de
 * agora fora delas. Sem radar de chuva (a licença grátis do RainViewer é só para uso pessoal).
 *
 * O tema só troca o FUNDO (pedido de Paulo, 15/11: "não quero que mude nada na HOME"); o que reage ao tempo acontece no
 * protetor. Peças:
 *  - [VistaMapa]: o recorte (pura; testada em CeuDeVerdadeTest);
 *  - [MapaMundo]: costas, países e estados do Natural Earth 1:50m (domínio público) embutidos no APK (assets/mapa/mundo.bin,
 *    ~220 KB, gerado por scripts/montar-mapa.py);
 *  - [NuvensSatelite]: o infravermelho (banda 13) do GOES-Leste/Oeste, já projetado em lat/lon pelo NASA GIBS (WMS
 *    público), a cada 15 min no máximo; vira nuvem branca translúcida;
 *  - [DesenhoCeu]: junta tudo numa imagem em meia resolução (~2 MB), usada pela HOME ([FundoCeu]) e pelo protetor.
 */

/** O recorte do mapa: a cidade fica um pouco à direita e acima do meio (os textos ficam à esquerda); ~9° de altura (~1.000 km). */
class VistaMapa(val lat0: Double, val lon0: Double, val w: Float, val h: Float) {
    /** Pixels por grau de latitude. */
    val k: Float = h / ALTURA_GRAUS
    private val kLon: Float = (k * cos(Math.toRadians(lat0))).toFloat().coerceAtLeast(k * 0.2f)
    val cx = w * 0.62f
    val cy = h * 0.46f

    fun x(lon: Double): Float = cx + ((lon - lon0) * kLon).toFloat()
    fun y(lat: Double): Float = cy - ((lat - lat0) * k).toFloat()
    fun lon(x: Float): Double = lon0 + (x - cx) / kLon
    fun lat(y: Float): Double = lat0 - (y - cy) / k

    /** Caixa à mostra: lonMin, latMin, lonMax, latMax. */
    fun caixa(): DoubleArray = doubleArrayOf(lon(0f), lat(h), lon(w), lat(0f))

    companion object { const val ALTURA_GRAUS = 9f }
}

/** As linhas do Natural Earth, lidas uma vez por processo (centésimos de grau; ver scripts/montar-mapa.py). */
object MapaMundo {
    class Linha(val camada: Int, val lonMin: Short, val latMin: Short, val lonMax: Short, val latMax: Short, val pontos: ShortArray)

    @Volatile private var linhas: List<Linha>? = null

    @Synchronized
    fun linhas(ctx: Context): List<Linha> = linhas ?: try {
        DataInputStream(ctx.assets.open("mapa/mundo.bin").buffered()).use { ler(it) }
    } catch (e: Exception) {
        Log.w("TvLauncher.Ceu", "mapa: $e"); emptyList()
    }.also { linhas = it }

    /** Lê o formato do montar-mapa.py. Público para o teste ler o arquivo de verdade. */
    fun ler(d: DataInputStream): List<Linha> {
        val m = ByteArray(4); d.readFully(m)
        require(String(m, Charsets.US_ASCII) == "MAPA" && d.readUnsignedByte() == 1) { "mapa em formato desconhecido" }
        val n = d.readInt()
        val saida = ArrayList<Linha>(n)
        repeat(n) {
            val camada = d.readUnsignedByte()
            val a = d.readShort(); val b = d.readShort(); val c = d.readShort(); val e = d.readShort()
            val qtd = d.readUnsignedShort()
            val p = ShortArray(qtd * 2)
            var x = d.readShort().toInt(); var y = d.readShort().toInt()
            p[0] = x.toShort(); p[1] = y.toShort()
            for (i in 1 until qtd) {
                val dx = d.readByte().toInt()
                if (dx == -128) { x += d.readShort(); y += d.readShort() } else { x += dx; y += d.readByte() }
                p[2 * i] = x.toShort(); p[2 * i + 1] = y.toShort()
            }
            saida += Linha(camada, a, b, c, e, p)
        }
        return saida
    }
}

/** Nuvens reais do satélite (NOAA GOES pelo NASA GIBS). Fora da thread da tela. */
object NuvensSatelite {
    private const val TAG = "TvLauncher.Ceu"
    private const val VALIDADE_MS = 15 * 60_000L
    const val LARGURA = 480
    const val ALTURA = 270

    /** Qual satélite vê o lugar. null = fora das Américas (sem satélite; o desenho usa nuvens desenhadas). */
    fun camada(lon: Double): String? = when {
        lon >= -115.0 && lon <= -10.0 -> "GOES-East_ABI_Band13_Clean_Infrared"
        lon < -115.0 || lon > 160.0 -> "GOES-West_ABI_Band13_Clean_Infrared"
        else -> null
    }

    /**
     * Opacidade da nuvem (0..255) a partir da cor da imagem do GIBS: cinza = temperatura do topo (escuro = quente =
     * chão ou nuvem baixa; claro = frio = nuvem alta); colorido = topo muito frio (nuvem de tempestade) = nuvem cheia.
     * Puro, testado.
     */
    fun opacidade(r: Int, g: Int, b: Int, a: Int): Int {
        if (a < 16) return 0
        val cinza = max(r, max(g, b)) - min(r, min(g, b)) <= 12
        if (!cinza) return 200   // perto do cinza mais frio: sem degrau entre a borda e o miolo da tempestade
        val v = ((r - 125) / 110.0).coerceIn(0.0, 1.0)
        return (v.pow(1.3) * 190).toInt()
    }

    /** Média 5x5 (caixa), bordas repetidas. Puro. */
    fun borrar(a: IntArray, w: Int, h: Int, k: Int = 2): IntArray {
        val tmp = IntArray(a.size); val out = IntArray(a.size); val n = 2 * k + 1
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; for (d in -k..k) s += a[y * w + (x + d).coerceIn(0, w - 1)]; tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; for (d in -k..k) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]; out[y * w + x] = s / n
        }
        return out
    }

    /** A imagem de nuvens (branca, alfa = nuvem) da [caixa]; guardada em disco por 15 min. null sem satélite ou sem rede. */
    // Synchronized: a HOME (FundoCeu) e o protetor pedem em threads diferentes e gravavam o mesmo .tmp e a mesma chave.
    @Synchronized
    fun buscar(ctx: Context, lon0: Double, caixa: DoubleArray): Bitmap? {
        val camada = camada(lon0) ?: return null
        val arq = File(ctx.cacheDir, "ceu-nuvens.png")
        val chave = File(ctx.cacheDir, "ceu-nuvens.txt")
        val pedido = camada + "|" + caixa.joinToString(",") { String.format(Locale.US, "%.2f", it) }
        if (arq.exists() && System.currentTimeMillis() - arq.lastModified() < VALIDADE_MS &&
            runCatching { chave.readText() }.getOrNull() == pedido) {
            BitmapFactory.decodeFile(arq.path)?.let { return it }
        }
        val url = "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1" +
            "&LAYERS=$camada&SRS=EPSG:4326&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&WIDTH=$LARGURA&HEIGHT=$ALTURA" +
            "&BBOX=" + caixa.joinToString(",") { String.format(Locale.US, "%.3f", it) }
        val bruta = try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
                if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) null
                else c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally { c.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "GIBS: ${e.javaClass.simpleName}"); null }
            // Sem rede: a última que tinha, se for DESTA caixa e de até 6 h (antes: nuvens de outro lugar ou de dias atrás
            // apareciam como "agora").
            ?: return if (arq.exists() && System.currentTimeMillis() - arq.lastModified() < 6 * 3_600_000L &&
                runCatching { chave.readText() }.getOrNull() == pedido) BitmapFactory.decodeFile(arq.path) else null
        val bw = bruta.width; val bh = bruta.height
        val px = IntArray(bw * bh)
        bruta.getPixels(px, 0, bw, 0, 0, bw, bh)
        bruta.recycle()
        val alfa = IntArray(px.size) { i -> val c = px[i]; opacidade((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, (c ushr 24)) }
        // Duas passadas de média 5x5: a imagem do satélite tem bordas duras (pixel de ~2 km); nuvem tem que ser macia.
        val macio = borrar(borrar(alfa, bw, bh), bw, bh)
        for (i in px.indices) px[i] = (macio[i] shl 24) or 0xFFFFFF
        val pronta = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        try {
            val tmp = File(arq.path + ".tmp")
            tmp.outputStream().use { pronta.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(arq); chave.writeText(pedido)
        } catch (e: Exception) { Log.w(TAG, "cache nuvens: $e") }
        return pronta
    }
}

/** Desenha o Céu de verdade inteiro numa imagem. Fora da thread da tela (lê o mapa e pode baixar as nuvens). */
object DesenhoCeu {
    /** Cor do ponto da cidade: o mesmo âmbar da frase do tempo. */
    private const val PONTO = 0xFFF6C453.toInt()

    fun imagem(ctx: Context, w: Int, h: Int, e: EstadoCeu, comSatelite: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val v = VistaMapa(e.lat, e.lon, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val d = ctx.resources.displayMetrics.density * (w / (ctx.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)))

        // Fundo: azul-noite com um leve clarão em volta da cidade.
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF14233A.toInt(), 0xFF0E1A2C.toInt(), 0xFF080F1A.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(v.cx, v.cy, w * 0.45f, intArrayOf(0x2236578A, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Mapa: costa, países, estados (do mais forte ao mais fraco).
        val cx = v.caixa()
        val caixa = intArrayOf((cx[0] * 100).toInt() - 1, (cx[1] * 100).toInt() - 1, (cx[2] * 100).toInt() + 1, (cx[3] * 100).toInt() + 1)
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        val estilos = arrayOf(0x8C4F6E99.toInt() to 1.1f, 0x664F6E99 to 0.9f, 0x3D4F6E99 to 0.7f)
        val caminho = Path()
        for (l in MapaMundo.linhas(ctx)) {
            if (l.lonMax < caixa[0] || l.lonMin > caixa[2] || l.latMax < caixa[1] || l.latMin > caixa[3]) continue
            caminho.reset()
            val pts = l.pontos
            for (i in 0 until pts.size / 2) {
                val x = v.x(pts[2 * i] / 100.0); val y = v.y(pts[2 * i + 1] / 100.0)
                if (i == 0) caminho.moveTo(x, y) else caminho.lineTo(x, y)
            }
            val (cor, largura) = estilos[l.camada.coerceIn(0, 2)]
            p.color = cor; p.strokeWidth = largura * d
            c.drawPath(caminho, p)
        }
        p.style = Paint.Style.FILL

        // Nuvens: do satélite quando há; senão desenhadas pelo céu de agora.
        val sat = if (comSatelite) try { NuvensSatelite.buscar(ctx, e.lon, cx) } catch (ex: Exception) { null } else null
        if (sat != null) {
            val pn = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 200 }
            c.drawBitmap(sat, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), pn)
            sat.recycle()
        } else nuvensDesenhadas(c, v, e, p)

        // A cidade: ponto âmbar com halo.
        p.shader = RadialGradient(v.cx, v.cy, 22 * d, intArrayOf(0x55F6C453, 0x00F6C453), null, Shader.TileMode.CLAMP)
        c.drawCircle(v.cx, v.cy, 22 * d, p); p.shader = null
        p.color = PONTO; c.drawCircle(v.cx, v.cy, 5.5f * d, p)

        // Véu embaixo (widgets) e no canto da hora: letras sempre legíveis.
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), intArrayOf(0x00000000, 0x99050A12.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        return bmp
    }

    /**
     * Fora das Américas (sem satélite): manchas suaves de nuvem em volta da cidade, na quantidade do céu de agora
     * (0 limpo ... 3 nublado; chuva = mais e mais densas). Sorteio fixo por hora: a imagem não pula a cada atualização.
     */
    private fun nuvensDesenhadas(c: Canvas, v: VistaMapa, e: EstadoCeu, p: Paint) {
        val quantas = when {
            e.chovendo || e.codigo >= 51 -> 9
            e.codigo >= 3 -> 7
            e.codigo == 2 -> 4
            e.codigo == 1 -> 2
            else -> 0
        }
        val r = java.util.Random(e.agoraMs / 3_600_000L)
        repeat(quantas) {
            val x = v.cx + (r.nextFloat() - 0.5f) * v.w * 0.7f
            val y = v.cy + (r.nextFloat() - 0.5f) * v.h * 0.6f
            val raio = v.h * (0.08f + r.nextFloat() * 0.12f)
            val alfa = if (e.chovendo) 0x8A else 0x66
            p.shader = RadialGradient(x, y, raio, intArrayOf((alfa shl 24) or 0xE6ECF5, 0x00E6ECF5), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, raio, p)
        }
        p.shader = null
    }
}

/**
 * O fundo da HOME no tema Céu de verdade: a imagem do [DesenhoCeu] (meia resolução), refeita a cada clima novo e no
 * máximo a cada 15 min, fora da thread da tela.
 */
class FundoCeu(ctx: Context, private val emSegundoPlano: (() -> Unit) -> Unit) : View(ctx), FundoVivo {
    @Volatile private var imagem: Bitmap? = null
    private var feitaEm = 0L
    private var feitaPara: String? = null
    private var trabalhando = false
    private val pintar = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destino = RectF()

    override fun atualizar(e: EstadoCeu) {
        val chave = "${e.lat},${e.lon},${e.codigo},${e.chovendo}"
        if (trabalhando || (chave == feitaPara && System.currentTimeMillis() - feitaEm < 15 * 60_000L && imagem != null)) return
        trabalhando = true
        emSegundoPlano {
            val nova = try { DesenhoCeu.imagem(context, 960, 540, e) } catch (ex: Throwable) { Log.w("TvLauncher.Ceu", "desenho: $ex"); null }
            post {
                trabalhando = false
                // Sem recycle() da antiga: a lista de desenho da GPU ainda pode apontar para ela; o coletor solta.
                if (nova != null) { imagem = nova; feitaEm = System.currentTimeMillis(); feitaPara = chave; invalidate() }
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val b = imagem ?: run { FundoEscuroPronto.desenhar(c, width.toFloat(), height.toFloat()); return }
        destino.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(b, null, destino, pintar)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); imagem = null }
}

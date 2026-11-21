package br.paulo.tvlauncher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import br.paulo.tvlauncher.input.SomNavegacaoTv
import br.paulo.tvlauncher.settings.Extras
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons da tela inicial em cruz (Paulo, 18/11/2026: "uns sons conforme for trocando"). São NOSSOS: gerados aqui em código
 * (senoides com envelope), nenhum arquivo copiado de outro sistema. Curtos (40-140 ms) e baixos.
 *
 * Como funciona: na 1ª vez cada som vira um WAV pequeno em cacheDir; o SoundPool carrega uma vez e toca com latência
 * baixa. Ligado de fábrica (Extras "cruz_sons"); fica mudo enquanto outro áudio toca (vídeo, rádio, podcast), para não
 * pingar por cima, e respeita o volume de "sistema" da TV.
 */
object SonsCruz {
    enum class Som { ANDAR, GRUPO, ABRIR, BORDA }

    const val CHAVE = "cruz_sons"

    /**
     * A cruz está na frente (MainActivity entre onResume e onPause). Com os sons dela ligados, o clique antigo do Android
     * ([SomNavegacaoTv], Extras "Som de navegação") se cala ali, senão cada tecla tocava DOIS sons (visto na Sala, 18/11/2026).
     */
    @Volatile var naFrente = false
    fun cuidaDaTela(ctx: Context) = naFrente && ligado(ctx)
    fun ligado(ctx: Context) = Extras.numero(ctx, CHAVE, 1) != 0
    fun definir(ctx: Context, sim: Boolean) { Extras.definirNumero(ctx, CHAVE, if (sim) 1 else 0); if (!sim) soltar() }

    private const val TAXA = 44100
    private const val VOLUME = 0.32f
    private var pool: SoundPool? = null
    private val ids = HashMap<Som, Int>()
    private val prontos = HashSet<Int>()

    /**
     * Toca [som]. Com [categoria], GRUPO e ABRIR saem na nota dela ([tom]): cada categoria tem a sua, e todas são da mesma
     * escala pentatônica, que soa bem em qualquer ordem (estudo da HOME, 18/11/2026). De noite ([volume]) sai mais baixo.
     */
    fun tocar(ctx: Context, som: Som, categoria: CruzLogica.Categoria? = null) {
        if (!ligado(ctx)) return
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am?.isMusicActive == true) return
        val p = pool ?: preparar(app) ?: return
        val id = ids[som] ?: return
        val v = volume(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        if (id in prontos) p.play(id, v, v, 1, 0, tom(som, categoria))
    }

    /** Escala pentatônica maior (dó, ré, mi, sol, lá): razões da frequência, uma por categoria, na ordem da linha. */
    private val PENTATONICA = floatArrayOf(1f, 9f / 8f, 5f / 4f, 3f / 2f, 5f / 3f)

    /** A velocidade de reprodução do SoundPool muda o tom: 1,5 = uma quinta acima. ANDAR e BORDA ficam no tom de sempre. */
    fun tom(som: Som, categoria: CruzLogica.Categoria?): Float =
        if (categoria == null || (som != Som.GRUPO && som != Som.ABRIR)) 1f
        else PENTATONICA[categoria.ordinal % PENTATONICA.size]

    /** 40% mais baixo das 22 h às 7 h (casa dormindo). */
    fun volume(hora: Int): Float = if (hora >= 22 || hora < 7) VOLUME * 0.6f else VOLUME

    /** Fora da HOME (ou sons desligados): solta o SoundPool. */
    fun soltar() {
        pool?.release(); pool = null; ids.clear(); prontos.clear()
    }

    private fun preparar(app: Context): SoundPool? = runCatching {
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) prontos += id }
        val pasta = File(app.cacheDir, "sons-cruz").apply { mkdirs() }
        for (s in Som.entries) {
            val f = File(pasta, "${s.name.lowercase()}-v1.wav")
            if (!f.exists()) f.writeBytes(wav(amostras(s)))
            ids[s] = p.load(f.path, 1)
        }
        pool = p
        p
    }.getOrNull()

    /**
     * O desenho de cada som (puro, testável): ANDAR = toque curto e macio (1,5 kHz, 45 ms); GRUPO = dois tons subindo
     * (660 -> 990 Hz, 120 ms); ABRIR = acorde claro (880 + 1320 Hz, 140 ms); BORDA = toque grave e abafado (230 Hz, 70 ms).
     */
    fun amostras(s: Som): FloatArray {
        fun tom(ms: Int, decai: Double, vararg parciais: Pair<Double, Double>, f: (Double, Double) -> Double = { fr, _ -> fr }): FloatArray {
            val n = TAXA * ms / 1000
            val out = FloatArray(n)
            val ataque = TAXA * 0.004   // 4 ms de subida: sem estalo
            var fase = DoubleArray(parciais.size)
            for (i in 0 until n) {
                val t = i.toDouble() / TAXA
                val env = minOf(1.0, i / ataque) * exp(-t * decai)
                var v = 0.0
                for ((k, par) in parciais.withIndex()) {
                    fase[k] += 2 * PI * f(par.first, t) / TAXA
                    v += par.second * sin(fase[k])
                }
                out[i] = (v * env).toFloat()
            }
            // últimos 3 ms descem a zero (sem clique no fim)
            val fim = (TAXA * 0.003).toInt()
            for (i in 0 until minOf(fim, n)) out[n - 1 - i] *= i.toFloat() / fim
            return out
        }
        return when (s) {
            Som.ANDAR -> tom(45, 70.0, 1500.0 to 0.55, 3000.0 to 0.08)
            Som.GRUPO -> tom(120, 30.0, 660.0 to 0.5, 1320.0 to 0.06) { fr, t -> fr * (1 + 0.5 * minOf(1.0, t / 0.06)) }
            Som.ABRIR -> tom(140, 22.0, 880.0 to 0.4, 1320.0 to 0.28, 2640.0 to 0.04)
            Som.BORDA -> tom(70, 45.0, 230.0 to 0.6, 460.0 to 0.1)
        }
    }

    /** PCM 16 bits mono num WAV mínimo. */
    fun wav(a: FloatArray): ByteArray {
        val dados = a.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + dados).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dados).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(TAXA).putInt(TAXA * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dados)
        for (x in a) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}

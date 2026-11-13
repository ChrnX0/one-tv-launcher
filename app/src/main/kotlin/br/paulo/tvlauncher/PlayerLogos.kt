package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Player universal, lista completa (06/11/2026): os logotipos dos canais (tvg-logo / stream_icon).
 *
 * Feito à mão em vez de uma biblioteca (Glide, Coil): são ~150 linhas contra ~1 MB de APK, e aqui só precisa de três
 * coisas: memória (LruCache), disco (cacheDir/logos, que o Android pode limpar quando faltar espaço) e 3 downloads ao
 * mesmo tempo. A imagem é reduzida ao tamanho do card ANTES de ir para a memória (inSampleSize): um PNG de 1000 px
 * viraria 4 MB por logo.
 *
 * Só na thread da tela: carregar(). Download e decodificação rodam no grupo de 3 threads.
 */
object PlayerLogos {
    private const val MAX_BYTES = 2L * 1024 * 1024   // logo/pôster maior que 2 MB não vale baixar
    private const val LADO_PX = 160                   // maior lado guardado (o maior card usa ~120 px na TV 1080p)

    private val tela = Handler(Looper.getMainLooper())
    // 3 downloads ao mesmo tempo, e a fila é uma PILHA (13/11/2026): ao rolar a grade, o pôster que acabou de aparecer
    // vem antes dos que já passaram. Antes era FIFO, e rolando 20 linhas a tela esperava os ~100 pôsteres do caminho.
    private val trabalho = java.util.concurrent.ThreadPoolExecutor(3, 3, 30, java.util.concurrent.TimeUnit.SECONDS,
        object : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean = offerFirst(e)
        })

    // Quem espera cada imagem que está a caminho (só na thread da tela). Dois cards com o mesmo pôster (o filme dublado e
    // o legendado) baixavam duas vezes; agora o segundo entra na fila de espera do primeiro.
    private val esperando = HashMap<String, MutableList<ImageView>>()
    // Quantas Views ainda querem cada imagem a caminho. Uma View reaproveitada para outro filme (rolagem) deixa de querer a
    // anterior; se ninguém mais quer quando chega a vez dela na fila, nem baixa. Lido nas threads de download.
    private val querem = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pedidoDaView = java.util.WeakHashMap<ImageView, String>()

    private fun largar(alvo: ImageView) {
        val antigo = pedidoDaView.remove(alvo) ?: return
        esperando[antigo]?.remove(alvo)
        querem.computeIfPresent(antigo) { _, n -> if (n <= 1) null else n - 1 }
    }
    // Endereço que deu erro -> quando (relógio desde o boot). Antes era "nunca mais nesta sessão": um único tropeço da
    // rede (Wi-Fi caiu, servidor lento) deixava o logo em branco até o processo morrer. Agora tenta de novo em 5 min.
    private val falhou = HashMap<String, Long>()

    private fun pular(url: String) = TentativaLogo.pular(falhou[url], SystemClock.elapsedRealtime())

    // Teto de 20 MB de bitmaps (o processo :player é só do vídeo; a HOME não carrega isto). ~150 pôsteres ou ~1000 logos.
    private val memoria = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Memória apertada (LauncherApp.onTrimMemory): solta os bitmaps; o disco continua com eles. */
    fun soltarMemoria() = memoria.evictAll()

    /**
     * Mostra o logo em `alvo`. A View de uma linha de lista é reaproveitada para outro canal enquanto o download corre:
     * por isso a URL vai na tag, e o resultado só é posto se a tag ainda for a mesma.
     */
    fun carregar(ctx: Context, url: String, alvo: ImageView, lado: Int = LADO_PX) {
        largar(alvo)   // esta View não espera mais a imagem de antes (foi reaproveitada)
        alvo.tag = url
        alvo.setImageDrawable(null)
        if (url.isEmpty() || !M3u.urlAceita(url) || pular(url)) return
        val k = "$lado|$url"   // o mesmo endereço pode ser logo pequeno e pôster grande
        memoria.get(k)?.let { alvo.setImageBitmap(it); return }
        pedidoDaView[alvo] = k
        querem.merge(k, 1, Int::plus)
        esperando[k]?.let { it += alvo; return }   // já está a caminho: só espera junto
        esperando[k] = mutableListOf(alvo)
        val dir = File(ctx.cacheDir, "logos")
        trabalho.execute {
            // Ninguém mais quer (rolou para longe antes da vez dela): não baixa. Quem voltar a precisar pede de novo.
            val bmp = if ((querem[k] ?: 0) <= 0) null else runCatching { obter(dir, url, lado) }.getOrNull()
            val desistiu = (querem[k] ?: 0) <= 0 && bmp == null
            tela.post {
                val alvos = esperando.remove(k).orEmpty()
                querem.remove(k)
                alvos.forEach { if (pedidoDaView[it] == k) pedidoDaView.remove(it) }
                if (desistiu) {
                    // Alguém voltou a querer depois que a fila desistiu: pede de novo para essas Views.
                    alvos.forEach { if (it.tag == url) carregar(ctx, url, it, lado) }
                    return@post
                }
                if (bmp == null) { falhou[url] = SystemClock.elapsedRealtime(); return@post }
                falhou -= url
                memoria.put(k, bmp)
                alvos.forEach { if (it.tag == url) it.setImageBitmap(bmp) }
            }
        }
    }

    private val pedidos = HashSet<String>()   // downloads em andamento para quem desenha direto no Canvas

    /**
     * Para quem desenha no Canvas (o guia em grade): devolve o logo se já estiver na memória; senão pede e chama
     * `aoChegar` quando chegar (a View então se redesenha). Nunca bloqueia.
     */
    fun emMemoria(ctx: Context, url: String, aoChegar: () -> Unit): Bitmap? {
        if (url.isEmpty() || !M3u.urlAceita(url) || pular(url)) return null
        memoria.get("$LADO_PX|$url")?.let { return it }
        if (!pedidos.add(url)) return null
        val dir = File(ctx.cacheDir, "logos")
        trabalho.execute {
            val bmp = runCatching { obter(dir, url, LADO_PX) }.getOrNull()
            tela.post {
                pedidos -= url
                if (bmp == null) falhou[url] = SystemClock.elapsedRealtime() else { falhou -= url; memoria.put("$LADO_PX|$url", bmp); aoChegar() }
            }
        }
        return null
    }

    /**
     * Para a capa dos cards da HOME (CapaAtalho): baixa (ou lê do disco) e devolve na hora. NUNCA na thread da tela (rede).
     * [opaco] = pôster (RGB_565, metade da memória); logo pode ter fundo transparente, então vai em ARGB.
     */
    fun bitmapAgora(ctx: Context, url: String, lado: Int, opaco: Boolean): Bitmap? {
        if (url.isEmpty() || !M3u.urlAceita(url)) return null
        return runCatching { obter(File(ctx.cacheDir, "logos"), url, lado, opaco) }.getOrNull()
    }

    private fun obter(dir: File, url: String, lado: Int, opaco: Boolean = lado > LADO_PX): Bitmap? {
        val arquivo = File(dir, hash(url))
        val bytes = if (arquivo.exists()) arquivo.readBytes() else baixar(url).also {
            runCatching { dir.mkdirs(); File(dir, "${arquivo.name}.tmp").apply { writeBytes(it) }.renameTo(arquivo) }
        }
        return decodificar(bytes, lado, opaco)
    }

    private fun decodificar(bytes: ByteArray, lado: Int, opaco: Boolean): Bitmap? {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
        var amostra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (amostra * 2) >= lado) amostra *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = amostra
            // Pôster não tem transparência: RGB_565 gasta metade da memória. Logo pode ter (fundo transparente): ARGB.
            if (opaco) inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Baixa seguindo até 4 redirecionamentos (de http para https o HttpURLConnection não segue sozinho). TLS padrão. */
    private fun baixar(endereco: String): ByteArray {
        var url = endereco
        repeat(5) {
            if (!M3u.urlAceita(url)) throw IOException("endereço recusado")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TvLauncher-Player/1.0")
            try {
                val codigo = c.responseCode
                if (codigo in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("sem destino")).toString()
                    return@repeat
                }
                if (codigo != 200) throw IOException("HTTP $codigo")
                if (c.contentLengthLong > MAX_BYTES) throw IOException("grande demais")
                c.inputStream.use { entrada ->
                    val saida = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        if (saida.size() > MAX_BYTES) throw IOException("grande demais")
                    }
                    return saida.toByteArray()
                }
            } finally { c.disconnect() }
        }
        throw IOException("redirecionamentos demais")
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Regra de nova tentativa dos logos, à parte (sem Android) para o teste JVM: falhou há menos de 5 min = não tenta. */
internal object TentativaLogo {
    const val ESPERA_MS = 5 * 60_000L
    fun pular(falhaEm: Long?, agora: Long): Boolean = falhaEm != null && agora - falhaEm < ESPERA_MS
}

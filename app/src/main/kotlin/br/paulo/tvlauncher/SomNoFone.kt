package br.paulo.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Som no fone (degrau 4, item 4): servidor HTTP pequeno no processo do player (":player"), porta 8097, só rede de casa.
 * Regras em [SomNoFoneRegras]. Liga pelo menu do player ("Som no fone"), desliga ao fechar o player ou no "Parar".
 * Página do celular: assets/fone/index.html (toca os pedaços em hora marcada com Web Audio; funciona em http).
 */
object SomNoFone {
    private const val TAG = "TvLauncher.Fone"

    private class Ouvinte(val fila: LinkedBlockingDeque<ByteArray> = LinkedBlockingDeque(SomNoFoneRegras.FILA_PEDACOS))

    @Volatile private var servidor: ServerSocket? = null
    @Volatile var token: String? = null; private set
    @Volatile var somTambemNaTv = false
    private val ouvintes = CopyOnWriteArrayList<Ouvinte>()
    @Volatile private var conexoes = 0
    private var pagina: ByteArray? = null

    val ouvindo get() = ouvintes.size
    val ligado get() = servidor != null
    /** Lido pelo [ProcessadorFone] a cada pedaço de áudio. */
    val tvMuda get() = SomNoFoneRegras.tvMuda(ouvintes.size, somTambemNaTv)
    /** Mudou o número de ouvintes (a janela da TV atualiza). */
    @Volatile var aoMudar: (() -> Unit)? = null

    fun hora() = SystemClock.elapsedRealtime()

    fun ligar(ctx: Context): String? {
        servidor?.let { return endereco() }
        val app = ctx.applicationContext
        if (pagina == null) pagina = try { app.assets.open("fone/index.html").use { it.readBytes() } } catch (_: Exception) { return null }
        return try {
            val s = ServerSocket(SomNoFoneRegras.PORTA, 8, InetAddress.getByName("0.0.0.0")).apply { reuseAddress = true }
            servidor = s; token = Cenas.gerarToken(); somTambemNaTv = false
            Thread({ aceitar(s) }, "fone-servidor").apply { isDaemon = true }.start()
            endereco()
        } catch (e: Exception) { Log.w(TAG, "porta: ${e.javaClass.simpleName}"); null }
    }

    // Players abertos (VodActivity/PlayerActivity, via JuntoNoPlayer). Fechou o último: para depois de [FOLGA_MS],
    // para o próximo episódio (que abre um player novo) não derrubar quem está ouvindo (4c/4d, 17/11/2026).
    private const val FOLGA_MS = 20_000L
    private var players = 0
    private val principal by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val pararSemPlayer = Runnable { if (players == 0) parar() }
    fun playerAbriu() { players++; principal.removeCallbacks(pararSemPlayer) }
    fun playerFechou() { players = (players - 1).coerceAtLeast(0); if (players == 0) principal.postDelayed(pararSemPlayer, FOLGA_MS) }

    fun parar() {
        val s = servidor ?: return
        servidor = null; token = null
        try { s.close() } catch (_: Exception) {}
        ouvintes.forEach { it.fila.clear(); it.fila.offer(ByteArray(0)) }   // pedaço vazio = fim (a thread do celular sai)
        ouvintes.clear()
        aoMudar?.invoke()
    }

    /** "http://192.168.1.153:8097/fone/<token>" ou null sem rede de casa. */
    fun endereco(): String? {
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) { null } ?: return null
        val t = token ?: return null
        return "http://$ip:${SomNoFoneRegras.PORTA}/fone/$t"
    }

    /** Do processador de áudio (thread do áudio): não bloqueia; celular lento perde o pedaço mais velho. */
    fun publicar(horaMs: Long, taxa: Int, pcm: ByteArray) {
        if (ouvintes.isEmpty()) return
        val p = SomNoFoneRegras.pedaco(horaMs, taxa, pcm)
        for (o in ouvintes) { while (!o.fila.offerLast(p)) o.fila.pollFirst() }
    }

    // ---------------------------------------------------------------- HTTP mínimo

    private fun aceitar(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Exception) { break }
            if (conexoes >= SomNoFoneRegras.MAX_CELULARES * 3) { try { c.close() } catch (_: Exception) {}; continue }
            Thread({ atender(c) }, "fone-conexao").apply { isDaemon = true }.start()
        }
    }

    private fun atender(c: Socket) {
        synchronized(this) { conexoes++ }
        try {
            c.soTimeout = 8_000; c.tcpNoDelay = true
            val ip = c.inetAddress?.hostAddress
            val saida = BufferedOutputStream(c.getOutputStream(), 16 * 1024)
            val linha = lerCabecalho(c.getInputStream()) ?: return
            val partes = linha.split(' ')
            if (partes.size < 2 || partes[0] != "GET") { responder(saida, 405, "text/plain", "método".toByteArray()); return }
            if (!SomNoFoneRegras.daCasa(ip)) { responder(saida, 403, "text/plain", "só na rede de casa".toByteArray()); return }
            val caminho = partes[1]
            val (t, acao) = SomNoFoneRegras.rota(caminho) ?: run { responder(saida, 404, "text/plain", ByteArray(0)); return }
            val meu = token
            if (meu == null || !java.security.MessageDigest.isEqual(meu.toByteArray(), t.toByteArray())) {
                responder(saida, 404, "text/plain", ByteArray(0)); return
            }
            when (acao) {
                "pagina" -> responder(saida, 200, "text/html; charset=utf-8", pagina ?: ByteArray(0))
                "hora" -> responder(saida, 200, "application/json",
                    """{"t":${hora()},"ouvindo":$ouvindo,"max":${SomNoFoneRegras.MAX_CELULARES},"tv":$somTambemNaTv,"atraso":${SomNoFoneRegras.ATRASO_PADRAO_MS}}""".toByteArray())
                "tv" -> {
                    somTambemNaTv = caminho.substringAfter('?', "").split('&').any { it == "tambem=1" }
                    responder(saida, 200, "application/json", """{"tv":$somTambemNaTv}""".toByteArray())
                }
                "som" -> transmitir(c, saida, ip)
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) { conexoes-- }
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun transmitir(c: Socket, saida: BufferedOutputStream, ip: String?) {
        val o = Ouvinte()
        synchronized(ouvintes) {
            if (!SomNoFoneRegras.podeEntrar(ip, ouvintes.size)) {
                responder(saida, 429, "application/json", """{"erro":"limite"}""".toByteArray()); return
            }
            ouvintes += o
        }
        aoMudar?.invoke()
        Log.i(TAG, "celular entrou (${ouvintes.size})")
        try {
            c.soTimeout = 0
            saida.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
            saida.flush()
            while (servidor != null) {
                // Sem som por 2 s (pausado): manda um pedaço vazio para descobrir se o celular ainda está lá.
                val p = o.fila.pollFirst(2, TimeUnit.SECONDS) ?: SomNoFoneRegras.pedaco(hora(), 48_000, ByteArray(0))
                if (p.isEmpty()) break
                saida.write(p)
                if (o.fila.isEmpty()) saida.flush()
            }
        } catch (_: Exception) {
        } finally {
            ouvintes -= o   // fechou a página: a TV volta a ter som (tvMuda)
            aoMudar?.invoke()
            Log.i(TAG, "celular saiu (${ouvintes.size})")
        }
    }

    private fun lerCabecalho(i: InputStream): String? {
        val b = StringBuilder()
        var linha: String? = null
        var anterior = -1
        while (b.length < 8_192) {
            val x = i.read(); if (x < 0) return null
            if (x == '\n'.code) {
                val l = b.toString().trimEnd('\r')
                if (linha == null) linha = l
                if (l.isEmpty() && anterior == '\n'.code) break
                if (l.isEmpty()) break
                b.setLength(0); anterior = x; continue
            }
            b.append(x.toChar()); anterior = x
        }
        return linha
    }

    private fun responder(s: BufferedOutputStream, codigo: Int, tipo: String, corpo: ByteArray) {
        val texto = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 429 to "Too Many Requests")[codigo] ?: "Erro"
        s.write(("HTTP/1.1 $codigo $texto\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        s.write(corpo); s.flush()
    }

    // ---------------------------------------------------------------- janela na TV

    /** "Som no fone" no menu do player: liga e mostra o QR; "Parar" desliga. */
    fun janela(a: Activity) {
        if (!PlanosTv.pedir(a, Planos.Recurso.SOM_NO_FONE)) return   // plano: Pro
        val url = ligar(a) ?: run {
            android.widget.Toast.makeText(a, a.getString(R.string.fone_sem_rede), android.widget.Toast.LENGTH_LONG).show(); return
        }
        val d = Dialog(a)
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val ouvindoTxt = TextView(a).apply { textSize = 20f; setTextColor(Estilo.TEXTO_2); gravity = Gravity.CENTER }
        fun atualizar() { ouvindoTxt.text = a.getString(R.string.fone_ouvindo, ouvindo, SomNoFoneRegras.MAX_CELULARES) }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
            background = Estilo.vidro(a, 24)
            addView(TextView(a).apply { text = a.getString(R.string.fone_titulo); textSize = 30f; setTextColor(Estilo.TEXTO); typeface = Fontes.nunito(700); gravity = Gravity.CENTER })
            addView(TextView(a).apply { text = a.getString(R.string.fone_texto, SomNoFoneRegras.MAX_CELULARES); textSize = 20f; setTextColor(Estilo.TEXTO_2); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(12) })
            addView(ImageView(a).apply { setImageBitmap(QrCodigo.bitmap(url, 480)) }, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(20) })
            addView(TextView(a).apply { text = url.substringBefore("/fone/") + "/fone/…"; textSize = 18f; setTextColor(Estilo.TEXTO_2); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(ouvindoTxt, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            val fechar = Estilo.botao(a, a.getString(R.string.fone_fechar), primario = false).apply { setOnClickListener { d.dismiss() } }
            val parar = Estilo.botao(a, a.getString(R.string.fone_parar)).apply { setOnClickListener { parar(); d.dismiss() } }
            addView(LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; addView(fechar); addView(parar, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) }) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
            fechar.post { fechar.requestFocus() }
        }
        atualizar()
        aoMudar = { ouvindoTxt.post { atualizar() } }
        d.setOnDismissListener { aoMudar = null }
        d.setContentView(col)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        d.show()
    }
}

/**
 * O processador que fica sempre na corrente de áudio do nosso player (EfeitosSom.fabrica). Sem celular ouvindo, só
 * copia o áudio (custo de uma cópia). Com celular: manda uma cópia em estéreo 16 bits para o [SomNoFone] e, se a TV
 * deve ficar muda, entrega silêncio ao alto-falante.
 */
class ProcessadorFone : BaseAudioProcessor() {
    private var canais = 0
    private var taxa = 0
    private var flutuante = false

    override fun onConfigure(entrada: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (entrada.encoding != C.ENCODING_PCM_16BIT && entrada.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        canais = entrada.channelCount; taxa = entrada.sampleRate; flutuante = entrada.encoding == C.ENCODING_PCM_FLOAT
        return entrada
    }

    override fun queueInput(dados: ByteBuffer) {
        val n = dados.remaining()
        if (n == 0) return
        val saida = replaceOutputBuffer(n)
        if (SomNoFone.ouvindo > 0 && canais > 0) {
            val bytes = if (flutuante) 4 else 2
            val quadros = n / (bytes * canais)
            val f = FloatArray(quadros * canais)
            val d = dados.duplicate().order(ByteOrder.nativeOrder())
            for (k in f.indices) f[k] = if (flutuante) d.float else d.short / 32768f
            SomNoFone.publicar(SomNoFone.hora(), taxa, SomNoFoneRegras.estereo16(f, canais, quadros))
        }
        if (SomNoFone.tvMuda) { repeat(n) { saida.put(0) }; dados.position(dados.limit()) } else saida.put(dados)
        saida.flip()
    }
}

/**
 * A corrente de áudio do player com o [ProcessadorFone] no FIM, depois do Sonic (velocidade): assim o som do fone sai
 * na mesma velocidade da TV (1,25x, 1,5x...). Antes (17/11 madrugada) ele ficava antes do Sonic e saía em 1x.
 * O fone não muda o número de quadros, então duração e quadros pulados vêm da corrente padrão.
 */
class CorrenteComFone(private val base: androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain,
                      private val fone: ProcessadorFone) : androidx.media3.common.audio.AudioProcessorChain {
    private val todos: Array<AudioProcessor> = base.audioProcessors + fone
    override fun getAudioProcessors(): Array<AudioProcessor> = todos
    override fun applyPlaybackParameters(p: androidx.media3.common.PlaybackParameters) = base.applyPlaybackParameters(p)
    override fun applySkipSilenceEnabled(ligado: Boolean) = base.applySkipSilenceEnabled(ligado)
    override fun getMediaDuration(duracaoTocada: Long) = base.getMediaDuration(duracaoTocada)
    override fun getSkippedOutputFrameCount() = base.skippedOutputFrameCount
}

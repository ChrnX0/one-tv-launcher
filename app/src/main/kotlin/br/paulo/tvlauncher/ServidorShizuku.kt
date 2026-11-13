package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Comandos no shell próprio (cliente ADB embutido, uid 2000): abre um fluxo `shell:` no adbd local e lê a saída com
 * teto e prazo. O nome é histórico: até 12/11/2026 este arquivo também subia o servidor do Shizuku embutido
 * (`app_process ... rikka.shizuku.server.ShizukuService`), que saiu do launcher. O registro continua no mesmo
 * shell_proprio.log.
 */
object ServidorShizuku {
    const val TAG = "TvLauncher.Shizuku"

    private const val LOG_MAX = 256 * 1024   // 256 KB: passou disso, o atual vira .1 e começa outro

    @Synchronized
    fun anotar(ctx: Context, linha: String) {
        Log.i(TAG, linha)
        try {
            val f = File(ctx.getExternalFilesDir(null), "shell_proprio.log")
            // Rotação (revisão de 04/11/2026): o log crescia sem limite — o guardião escreve a cada boot e a cada 10 min.
            if (f.length() > LOG_MAX) f.renameTo(File(f.path + ".1"))
            f.appendText("[shizuku] $linha\n")
        } catch (_: Exception) {}
    }

    /** Teto da saída guardada na memória (auditoria 10/11/2026): um `cat` de vídeo ou `cat /dev/zero` pelo MCP lotava a
     *  memória do launcher, porque tudo era juntado ANTES do corte de 60 mil caracteres do McpFerramentas. */
    const val MAX_SAIDA = 1024 * 1024   // 1 MB

    /** O que [lerComTeto] leu, e se parou no teto (então sobrou saída sem ler). */
    class Lido(val texto: String, val cortado: Boolean)

    /**
     * O laço de leitura, separado do AdbStream para o teste JVM (ServidorShizukuTest). [ler] é o read() do fluxo
     * (-1 = acabou); [acabou] é a checagem do pendingClose, feita antes de cada leitura. Guarda no máximo [max] bytes.
     */
    fun lerComTeto(max: Int, acabou: () -> Boolean = { false }, ler: (ByteArray) -> Int): Lido {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(4096)
        while (true) {
            if (acabou()) break
            val n = ler(b)
            if (n < 0) break
            val cabe = minOf(n, max - buf.size())
            buf.write(b, 0, cabe)
            if (cabe < n) return Lido(buf.toString(Charsets.UTF_8.name()), cortado = true)   // passou do teto: para aqui
        }
        return Lido(buf.toString(Charsets.UTF_8.name()), cortado = false)
    }

    /**
     * Lê tudo o que o comando escreveu (até [MAX_SAIDA]). A libadb-android (3.1.1) lança IOException("Stream closed.")
     * quando o adbd fecha o fluxo SEM ter mandado nada (ex.: `pidof` de um processo que não existe) — isso é fim de
     * saída, não erro. Medido em 04/11/2026: `pidof shizuku_server` vazio dava "Stream closed." com readText().
     */
    fun lerAteOFim(ctx: Context, fluxo: AdbStream, max: Int = MAX_SAIDA): Lido {
        val ent = fluxo.openInputStream()
        val lido = lerComTeto(max, acabou = {
            fimPendente(ctx, fluxo).also { if (it) Log.i(TAG, "fim do fluxo pelo pendingClose (o read() da lib ficaria esperando)") }
        }) { b ->
            try { ent.read(b) } catch (e: java.io.IOException) { if (fluxo.isClosed) -1 else throw e }
        }
        if (lido.cortado) try { fluxo.close() } catch (_: Exception) {}   // o resto da saída não interessa: encerra o comando
        return lido
    }

    /**
     * O que [executar] devolve, a partir do que foi lido (pura, para o teste JVM). Se o vigia fechou o fluxo por tempo
     * ([estourou]), o texto é só um PEDAÇO: antes ele voltava como se o comando tivesse terminado bem.
     *  - estourou sem nada lido = null ("não respondeu", como os chamadores já tratam);
     *  - saída cortada (tempo ou teto) = aviso NO COMEÇO (o McpFerramentas corta o fim em 60 mil caracteres).
     */
    fun resultado(lido: Lido, estourou: Boolean, limiteMs: Long): String? {
        val saida = lido.texto.trim()
        return when {
            estourou && saida.isEmpty() -> null
            estourou -> "[saída cortada: o comando passou de ${limiteMs / 1000} s]\n$saida"
            lido.cortado -> "[saída cortada: passou de ${MAX_SAIDA / (1024 * 1024)} MB]\n$saida"
            else -> saida
        }
    }

    /*
     * Corrida da libadb-android 3.1.1 (medida em 04/11/2026; era o atraso de 20 s no 1º comando): se o CLSE do adbd chega
     * com um WRTE ainda na fila, AdbStream.notifyClose(true) só marca mPendingClose, sem fechar. Depois que a fila esvazia,
     * o read() da lib entra em wait() enquanto !mIsClosed e nunca olha o mPendingClose: dorme até o vigia fechar o fluxo.
     * Por isso olhamos o mPendingClose antes de cada read(). Se a reflexão falhar (campo renomeado numa versão nova da lib),
     * usamos o read() normal e o vigia de 20 s continua sendo a rede de segurança.
     */
    private class CamposFluxo(val pendente: Field, val fila: Field, val buffer: Field)

    private val campos: CamposFluxo? by lazy {
        try {
            fun campo(nome: String) = AdbStream::class.java.getDeclaredField(nome).apply { isAccessible = true }
            CamposFluxo(campo("mPendingClose"), campo("mReadQueue"), campo("mReadBuffer"))
        } catch (e: Throwable) {
            Log.w(TAG, "reflexao do AdbStream indisponivel: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
    private val avisouSemCampos = AtomicBoolean(false)

    /** true se o adbd já mandou CLSE e não resta nada para ler (o read() da lib ficaria esperando para sempre). */
    private fun fimPendente(ctx: Context, fluxo: AdbStream): Boolean {
        val c = campos
        if (c == null) {
            if (avisouSemCampos.compareAndSet(false, true)) anotar(ctx, "aviso: sem reflexao do AdbStream; fim de fluxo so pelo read()/vigia")
            return false
        }
        return try {
            if (!c.pendente.getBoolean(fluxo)) return false
            val fila = c.fila.get(fluxo) as java.util.Queue<*>
            val vazia = synchronized(fila) { fila.isEmpty() }   // o read() da lib usa o mesmo monitor
            vazia && !(c.buffer.get(fluxo) as java.nio.ByteBuffer).hasRemaining()
        } catch (_: Throwable) { false }
    }

    /**
     * Roda um comando pelo shell próprio e devolve a saída (sem espaços nas pontas), ou null se falhar. Um vigia fecha
     * o fluxo se passar de [limiteMs], para um read() pendurado não travar a thread para sempre.
     * [gerenciaConexao] = false na pista RÁPIDA (PistaAdb): não conecta e não derruba a conexão; só a pista lenta mexe
     * nela, para nunca haver duas threads conectando ao mesmo tempo.
     */
    fun executar(ctx: Context, m: AdbConnectionManager, cmd: String, limiteMs: Long = 20_000, gerenciaConexao: Boolean = true): String? {
        val fluxoRef = AtomicReference<AdbStream?>(null)
        val acabou = AtomicBoolean(false)
        val estourou = AtomicBoolean(false)   // o vigia fechou por tempo: a saída lida é só um pedaço
        val abrindo = AtomicBoolean(false)    // true só durante o openStream (ver o vigia)
        val vigia = thread(name = "Shizuku-vigia") {
            try { Thread.sleep(limiteMs) } catch (_: InterruptedException) { return@thread }
            if (acabou.compareAndSet(false, true)) {   // CAS: se o comando terminou junto, não marca saída inteira como cortada
                anotar(ctx, "sem resposta em ${limiteMs / 1000} s para: $cmd")
                estourou.set(true)
                val f = fluxoRef.get()
                // Sem fluxo ainda = preso no openStream: o open() da libadb espera o OKAY do adbd num wait() SEM prazo e
                // fechar um fluxo que não existe não o acorda (a fila do guardião travava para sempre). Derrubar a
                // conexão acorda o open() com erro, que cai no catch abaixo; a próxima chamada reconecta.
                // Só derruba quando está mesmo no openStream: durante o autoConnect (que tem prazo próprio) derrubar
                // atrapalharia a reconexão e a tela ao vivo do painel, que usa a mesma conexão.
                // Vale também na pista RÁPIDA (exceção consciente ao "só a lenta mexe na conexão", revisão de 10/11/2026):
                // o open() não tem outro jeito de acordar, e um openStream sem OKAY por [limiteMs] quer dizer que a
                // conexão está quebrada para todos (inclusive para um comando longo da lenta). A próxima chamada reconecta.
                if (f != null) try { f.close() } catch (_: Exception) {}
                else if (abrindo.get()) try { m.disconnect() } catch (_: Exception) {}
            }
        }
        return try {
            if (!m.isConnected && (!gerenciaConexao || !m.autoConnect(ctx, 15_000))) return null
            // O prazo venceu enquanto conectava: quem pediu já desistiu, então o comando não roda mais.
            if (estourou.get()) return null
            val inicio = android.os.SystemClock.elapsedRealtime()
            abrindo.set(true)
            val fluxo = try { m.openStream("shell:${ComandoAscii.preparar(cmd)}") /* acento quebra a libadb: ver ComandoAscii */ } finally { abrindo.set(false) }
            fluxoRef.set(fluxo)
            // Na libadb 3.1.1 isto só faz flush (não manda CLSE nem fecha o stdin do comando); fica por inofensivo.
            try { fluxo.openOutputStream().close() } catch (_: Exception) {}
            val lido = lerAteOFim(ctx, fluxo)
            acabou.compareAndSet(false, true)   // terminou antes do vigia: a saída é inteira
            try { fluxo.close() } catch (_: Exception) {}
            Log.i(TAG, "comando '$cmd' em ${android.os.SystemClock.elapsedRealtime() - inicio} ms (sem o autoConnect)")
            resultado(lido, estourou.get(), limiteMs)
        } catch (e: Throwable) {
            anotar(ctx, "falhou '$cmd': ${e.javaClass.simpleName}: ${e.message}")
            // Com duas pistas (10/11/2026) a conexão é compartilhada: derrubar por causa de um comando que falhou mataria
            // o comando que a outra pista estiver rodando. Então só derruba (limpeza) se a conexão já caiu de fato; uma
            // conexão travada é tratada pelo vigia acima (openStream sem resposta). A próxima chamada reconecta.
            // Na pista rápida não mexe nunca: quem confere é a lenta (GuardiaoShizuku.conferirConexao).
            if (gerenciaConexao && !m.isConnected) try { m.disconnect() } catch (_: Exception) {}
            null
        } finally {
            acabou.set(true)
            vigia.interrupt()
        }
    }
}

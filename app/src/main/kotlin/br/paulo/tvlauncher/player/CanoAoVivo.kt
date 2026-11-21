package br.paulo.tvlauncher.player

import java.io.IOException
import java.io.InputStream

/**
 * O "cano" entre o gravador e o player (14/11/2026): a lista só deixa 1 conexão, então quem baixa o canal é o
 * gravador, e o player assiste lendo daqui o mesmo fluxo que vai para o arquivo.
 *
 * É uma memória circular de [capacidade] bytes (16 MB = os últimos ~16 s a 8 Mbps; o heap do :player é curto, ver
 * PausaAoVivo). Quem lê começa [recuo] bytes antes do ponto mais novo (uns segundos: o player acha um quadro-chave logo
 * e a imagem aparece rápido) e espera quando alcança o gravador. Leitor que ficou para trás além da capacidade (pausou muito)
 * pula para o dado mais antigo que ainda existe. Puro, com teste JVM (GravacaoTest).
 */
class CanoAoVivo(val capacidade: Int = 16 * 1024 * 1024, private val recuo: Int = 4 * 1024 * 1024) {
    private val buf = ByteArray(capacidade)
    private val trava = Object()
    /** Total de bytes já escritos desde o começo (a posição "absoluta" do fluxo). */
    private var escritos = 0L
    private var fechado = false

    fun escrever(b: ByteArray, n: Int) = synchronized(trava) {
        var off = 0
        while (off < n) {
            val p = (escritos % capacidade).toInt()
            val k = minOf(n - off, capacidade - p)
            System.arraycopy(b, off, buf, p, k)
            off += k; escritos += k
        }
        trava.notifyAll()
    }

    /** A gravação acabou: quem lê recebe o fim do fluxo depois de ler o que sobrou. */
    fun fechar() = synchronized(trava) { fechado = true; trava.notifyAll() }

    val aberto get() = synchronized(trava) { !fechado }

    /** Um leitor a partir de agora (ao vivo). [esperaMs] = quanto espera por dado novo antes de dar erro. */
    fun leitor(esperaMs: Long = 20_000): InputStream = object : InputStream() {
        var pos = synchronized(trava) { escritos - minOf(escritos, recuo.toLong(), capacidade.toLong()) }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(trava) {
            if (len == 0) return 0
            val limite = System.currentTimeMillis() + esperaMs
            while (pos >= escritos) {
                if (fechado) return -1
                val falta = limite - System.currentTimeMillis()
                if (falta <= 0) throw IOException("gravação sem dados")
                trava.wait(falta)
            }
            if (escritos - pos > capacidade) pos = escritos - capacidade   // ficou para trás demais: o mais antigo que existe
            val p = (pos % capacidade).toInt()
            val k = minOf(len.toLong(), escritos - pos, (capacidade - p).toLong()).toInt()
            System.arraycopy(buf, p, b, off, k)
            pos += k
            return k
        }
    }
}

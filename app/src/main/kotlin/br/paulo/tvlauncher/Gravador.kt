package br.paulo.tvlauncher

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O motor da gravação (14/11/2026): copia o fluxo do canal (MPEG-TS, sem converter nada) para o destino, em pedaços de
 * até 1 hora. Puro (sem Android): quem usa entrega como abrir o canal, como abrir cada pedaço no destino, quanto
 * espaço há e o relógio. Teste JVM em GravadorTest com fluxos falsos.
 *
 *  - Para no horário [fimMs], quando [parar] vira true, quando não cabem mais 10 min no destino, ou quando a conexão
 *    cai [GravacaoRegras.TENTATIVAS] vezes seguidas. O que já foi escrito fica (TS tolera corte no meio).
 *  - Conexão que cai: espera 2, 4, 8 s e pede o canal de novo, no mesmo pedaço (o buraco é só o tempo sem sinal).
 *  - Erro ao escrever (pendrive arrancado, disco cheio, NAS fora): para na hora, não tenta de novo.
 *  - [aoGravar] recebe cada trecho gravado: é por ali que o player assiste o mesmo canal sem 2ª conexão.
 */
class Gravador(
    private val abrirCanal: () -> InputStream,
    private val abrirPedaco: (parte: Int) -> OutputStream,
    private val livre: () -> Long,
    private val relogio: () -> Long,
    private val fimMs: Long,
    private val parar: AtomicBoolean = AtomicBoolean(false),
    private val esperar: (Long) -> Unit = { Thread.sleep(it) },
    private val aoGravar: (ByteArray, Int) -> Unit = { _, _ -> },
) {
    enum class Motivo { FIM, PARADO, SEM_ESPACO, CONEXAO, DESTINO }

    class Resultado(val motivo: Motivo, val bytes: Long, val partes: Int, val ms: Long, val erro: Throwable? = null)

    @Volatile var bytes = 0L; private set
    @Volatile var partes = 0; private set

    fun rodar(): Resultado {
        val comeco = relogio()
        var saida: OutputStream? = null
        var inicioPedaco = comeco
        var bytesPedaco = 0L
        var falhas = 0
        var desdeChecagem = 0L
        val buf = ByteArray(64 * 1024)
        fun fim(m: Motivo, e: Throwable? = null): Resultado {
            runCatching { saida?.flush() }; runCatching { saida?.close() }
            return Resultado(m, bytes, partes, relogio() - comeco, e)
        }
        while (true) {
            if (parar.get()) return fim(Motivo.PARADO)
            if (relogio() >= fimMs) return fim(Motivo.FIM)
            val canal = try { abrirCanal() } catch (e: Exception) {
                if (++falhas >= GravacaoRegras.TENTATIVAS) return fim(Motivo.CONEXAO, e)
                esperar(2000L shl (falhas - 1)); continue
            }
            canal.use { ent ->
                while (true) {
                    if (parar.get()) return fim(Motivo.PARADO)
                    val agora = relogio()
                    if (agora >= fimMs) return fim(Motivo.FIM)
                    val n = try { ent.read(buf) } catch (e: IOException) { -2 }
                    if (n < 0) {   // fim do fluxo (-1) ou erro de rede (-2): reconectar
                        if (++falhas >= GravacaoRegras.TENTATIVAS) return fim(Motivo.CONEXAO)
                        esperar(2000L shl (falhas - 1))
                        break
                    }
                    if (n == 0) continue
                    falhas = 0
                    try {
                        if (saida == null || GravacaoRegras.novoPedaco(agora - inicioPedaco, bytesPedaco)) {
                            runCatching { saida?.close() }
                            if (!GravacaoRegras.cabe(livre(), GravacaoRegras.bitrate(bytes, agora - comeco))) return fim(Motivo.SEM_ESPACO)
                            desdeChecagem = 0
                            partes++
                            saida = abrirPedaco(partes)
                            inicioPedaco = agora; bytesPedaco = 0
                        }
                        saida!!.write(buf, 0, n)
                    } catch (e: Exception) { saida = null; return fim(Motivo.DESTINO, e) }
                    bytes += n; bytesPedaco += n; desdeChecagem += n
                    runCatching { aoGravar(buf, n) }
                    // Espaço a cada ~16 MB (consultar o disco a cada 64 KB seria caro à toa).
                    if (desdeChecagem >= 16L * 1024 * 1024) {
                        desdeChecagem = 0
                        if (!GravacaoRegras.cabe(livre(), GravacaoRegras.bitrate(bytes, agora - comeco))) return fim(Motivo.SEM_ESPACO)
                    }
                }
            }
        }
    }
}

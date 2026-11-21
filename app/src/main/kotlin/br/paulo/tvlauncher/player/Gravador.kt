package br.paulo.tvlauncher.player

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
 *  - Conexão que cai: espera 2, 4, 8, 16, 30 s... ([GravacaoRegras.espera]) e pede o canal de novo NUM PEDAÇO NOVO (a
 *    próxima "parte", que o player emenda). Antes ia no mesmo arquivo: o canal reconectado recomeça o relógio do vídeo
 *    (PCR) e o pacote cortado no meio desalinhava o resto, e uma gravação de 1 h aparecia com 5:36:28 no player
 *    (visto na Sala, 17/11/2026: PCR 78531 s no começo do arquivo e 3275 s no fim).
 *  - Só pacotes TS inteiros (188 bytes) vão para o arquivo; o pedaço de pacote que a queda cortou é descartado.
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
        val resto = ByteArray(PACOTE)   // começo de um pacote TS que ainda não chegou inteiro
        var nResto = 0
        var reconectou = false          // a conexão caiu: o próximo dado vai para um pedaço novo
        fun fim(m: Motivo, e: Throwable? = null): Resultado {
            // No fim normal o último pacote incompleto também vai (é o fim do arquivo, não desalinha nada).
            if (nResto > 0 && saida != null) runCatching { saida!!.write(resto, 0, nResto); bytes += nResto }
            runCatching { saida?.flush() }; runCatching { saida?.close() }
            return Resultado(m, bytes, partes, relogio() - comeco, e)
        }
        while (true) {
            if (parar.get()) return fim(Motivo.PARADO)
            if (relogio() >= fimMs) return fim(Motivo.FIM)
            val canal = try { abrirCanal() } catch (e: Exception) {
                if (++falhas >= GravacaoRegras.TENTATIVAS) return fim(Motivo.CONEXAO, e)
                esperar(GravacaoRegras.espera(falhas)); continue
            }
            nResto = 0   // conexão nova começa num pacote inteiro; o pedaço cortado da anterior fica de fora
            canal.use { ent ->
                while (true) {
                    if (parar.get()) return fim(Motivo.PARADO)
                    val agora = relogio()
                    if (agora >= fimMs) return fim(Motivo.FIM)
                    val n = try { ent.read(buf) } catch (e: IOException) { -2 }
                    if (n < 0) {   // fim do fluxo (-1) ou erro de rede (-2): reconectar
                        nResto = 0
                        if (bytesPedaco > 0) reconectou = true
                        if (++falhas >= GravacaoRegras.TENTATIVAS) return fim(Motivo.CONEXAO)
                        esperar(GravacaoRegras.espera(falhas))
                        break
                    }
                    if (n == 0) continue
                    falhas = 0
                    runCatching { aoGravar(buf, n) }   // o player ao vivo recebe tudo, como chegou
                    val total = nResto + n
                    val inteiros = total - total % PACOTE   // quantos bytes fecham pacotes inteiros
                    if (inteiros == 0) { System.arraycopy(buf, 0, resto, nResto, n); nResto = total; continue }
                    try {
                        if (saida == null || reconectou || GravacaoRegras.novoPedaco(agora - inicioPedaco, bytesPedaco)) {
                            runCatching { saida?.close() }
                            if (!GravacaoRegras.cabe(livre(), GravacaoRegras.bitrate(bytes, agora - comeco))) return fim(Motivo.SEM_ESPACO)
                            desdeChecagem = 0
                            partes++
                            saida = abrirPedaco(partes)
                            inicioPedaco = agora; bytesPedaco = 0; reconectou = false
                        }
                        if (nResto > 0) saida!!.write(resto, 0, nResto)
                        saida!!.write(buf, 0, inteiros - nResto)
                    } catch (e: Exception) {
                        // Fecha o pedaço: no pendrive o descritor ficava preso (o volume não desmontava) e o buffer se perdia.
                        runCatching { saida?.close() }
                        saida = null; return fim(Motivo.DESTINO, e)
                    }
                    val sobra = total - inteiros
                    System.arraycopy(buf, n - sobra, resto, 0, sobra)
                    nResto = sobra
                    bytes += inteiros; bytesPedaco += inteiros; desdeChecagem += inteiros
                    // Espaço a cada ~16 MB (consultar o disco a cada 64 KB seria caro à toa).
                    if (desdeChecagem >= 16L * 1024 * 1024) {
                        desdeChecagem = 0
                        if (!GravacaoRegras.cabe(livre(), GravacaoRegras.bitrate(bytes, agora - comeco))) return fim(Motivo.SEM_ESPACO)
                    }
                }
            }
        }
    }

    private companion object { const val PACOTE = 188 }   // tamanho fixo do pacote MPEG-TS
}

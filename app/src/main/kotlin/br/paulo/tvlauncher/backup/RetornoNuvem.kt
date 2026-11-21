package br.paulo.tvlauncher.backup

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Volta do login do Box e do Google Drive para a TV (14/11/2026). Os dois só devolvem o código para um endereço https
 * registrado (e o do Box vale 30 segundos: digitar na TV não dá tempo). Então:
 *  1. a TV abre este porto ([PORTA]) só enquanto a tela do login está aberta (no máximo 10 minutos);
 *  2. o QR leva o celular ao Box com state = "<número>_<IP da TV>_<porto>" (Nuvem.estadoRetorno);
 *  3. o serviço manda o celular para a página estática volta.html do site do projeto, que o reenvia para
 *     http://<IP da TV>:<porto>/volta?code=...&state=<número>;
 *  4. aqui a TV confere o número, responde "pronto, volte para a TV" e troca o código pelo token na hora.
 * O código sozinho não vale nada sem o segredo do app (que só a TV tem). A parte pura ([lerPedido]) tem teste.
 */
object RetornoNuvem {
    const val PORTA = 8095

    fun novoNumero(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Resultado de uma chegada: o código, ou o erro que o serviço mandou (ex. access_denied quando a pessoa recusa). */
    sealed class Chegada {
        class Codigo(val codigo: String) : Chegada()
        class Erro(val erro: String) : Chegada()
    }

    /**
     * Linha de pedido HTTP ("GET /volta?code=X&state=N HTTP/1.1") -> o que chegou, ou null se não é para nós (outro
     * caminho, outro número, sem código). Só GET e só /volta.
     */
    fun lerPedido(linha: String, numero: String): Chegada? {
        val partes = linha.trim().split(' ')
        if (partes.size < 2 || partes[0] != "GET") return null
        val alvo = partes[1]
        if (alvo.substringBefore('?') != "/volta") return null
        val q = alvo.substringAfter('?', "").split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', ""); if (k.isEmpty()) return@mapNotNull null
            k to runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
        if (q["state"] != numero || numero.isEmpty()) return null
        q["code"]?.takeIf { it.isNotBlank() && it.length <= 512 }?.let { return Chegada.Codigo(it) }
        q["error"]?.takeIf { it.isNotBlank() }?.let { return Chegada.Erro(it.take(64)) }
        return null
    }

    private val PAGINA_OK = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OK</title></head><body style="font:20px system-ui;background:#111;color:#eee;text-align:center;padding:48px 16px">
<p style="font-size:56px;margin:0">✓</p><p>Pronto! Volte para a TV.</p><p style="color:#aaa">Done! Go back to the TV.</p></body></html>"""

    /**
     * Espera a volta (bloqueia: chamar numa thread). Devolve null se [cancelar] for ligado, se passar [ateMs], ou se o
     * porto estiver ocupado ([ocupado] é chamado nesse caso).
     */
    fun esperar(numero: String, ateMs: Long, cancelar: AtomicBoolean, ocupado: () -> Unit): Chegada? {
        val srv = runCatching { ServerSocket().apply { reuseAddress = true; soTimeout = 1000; bind(InetSocketAddress(PORTA), 4) } }
            .getOrElse { ocupado(); return null }
        srv.use {
            while (!cancelar.get() && System.currentTimeMillis() < ateMs) {
                val s = try { srv.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { return null }
                s.use { sock ->
                    sock.soTimeout = 5000
                    val linha = runCatching {
                        val ent = sock.getInputStream()
                        val sb = StringBuilder()
                        while (sb.length < 2048) { val b = ent.read(); if (b < 0 || b == '\n'.code) break; sb.append(b.toChar()) }
                        sb.toString()
                    }.getOrDefault("")
                    val chegou = lerPedido(linha, numero)
                    val (cod, corpo) = if (chegou != null) "200 OK" to PAGINA_OK else "404 Not Found" to "não"
                    val bytes = corpo.toByteArray(Charsets.UTF_8)
                    runCatching {
                        sock.getOutputStream().apply {
                            write(("HTTP/1.1 $cod\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes); flush()
                        }
                    }
                    if (chegou != null) return chegou
                }
            }
        }
        return null
    }
}

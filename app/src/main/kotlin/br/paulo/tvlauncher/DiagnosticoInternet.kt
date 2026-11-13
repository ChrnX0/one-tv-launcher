package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A parte que MEDE do "a internet caiu? em uma frase" (10/11/2026). A frase em si sai de [InternetEmUmaFrase]
 * (lógica pura, com teste). Só lê e pergunta: nada de DNS, VPN ou rede é alterado.
 *
 * As medições rodam ao mesmo tempo (cada uma espera a rede, não a CPU) e dividem UM prazo só ([LIMITE_MS]): a resposta
 * sai em até ~7 s mesmo com tudo fora do ar (o que não chegou a tempo conta como "falhou"). Chamar FORA da thread da tela.
 */
object DiagnosticoInternet {
    private const val LIMITE_MS = 7000L

    fun medir(ctx: Context): InternetEmUmaFrase.Medicoes {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val redes = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        val casa = redes.firstOrNull { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        val vpnDePe = redes.any { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        @Suppress("DEPRECATION")
        val dbm = (app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.rssi ?: -127
        val dnsFixo = Dns.atual(app).let { if (it.modo == Dns.Modo.HOST) it.host?.let { h -> Dns.nomeDe(app, h) ?: h } else null }

        val exec = Executors.newFixedThreadPool(4)
        try {
            val tcp = exec.submit(Callable { tcpMs() })
            // DNS e página de teste pela rede ATIVA: é o caminho dos apps (com a VPN ligada, passa por ela).
            val dns = exec.submit(Callable { cm.activeNetwork?.let { Dns.resolverSemCache(it) } })
            val http = exec.submit(Callable { http() })
            val roteador = exec.submit(Callable { roteador(app) })
            val hist = try { HistoricoRede.amostras(app, 24) } catch (_: Exception) { emptyList() }
            // Prazo único: cada get() espera só o que SOBRA até o fim (antes cada um esperava 7 s de novo, em fila).
            val fim = SystemClock.elapsedRealtime() + LIMITE_MS
            fun <T> pegar(f: java.util.concurrent.Future<T>, padrao: T): T = try {
                f.get(maxOf(0L, fim - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
            } catch (_: Exception) { padrao }
            val (cod, ms) = pegar(http, null to null)
            val (ro, mudo) = pegar(roteador, null to false)
            return InternetEmUmaFrase.Medicoes(
                agora = System.currentTimeMillis(),
                conectada = casa != null,
                dbm = if (dbm in -126..-1) dbm else 0,
                tcpMs = pegar(tcp, null),
                dnsMs = pegar(dns, null),
                dnsNome = dnsFixo,
                httpCodigo = cod, httpMs = ms,
                vpnFixada = try { PrivilegioDO.vpnFixada(app) != null } catch (_: Exception) { false },
                vpnDePe = vpnDePe,
                roteador = ro, roteadorMudo = mudo,
                historico = hist,
            )
        } finally { exec.shutdownNow() }
    }

    /** Mede e explica. */
    fun explicar(ctx: Context): InternetEmUmaFrase.Resultado = InternetEmUmaFrase.explicar(medir(ctx))

    /** Para a ferramenta MCP diagnostico_rede: {frase, causa, nivel, sinais}. */
    fun json(ctx: Context): JSONObject = explicar(ctx).json()

    /** Conexão até 1.1.1.1:443: prova que a internet chega SEM depender de DNS. ms ou null. */
    private fun tcpMs(): Int? {
        val ini = SystemClock.elapsedRealtime()
        return try { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 3000) }; (SystemClock.elapsedRealtime() - ini).toInt() }
        catch (_: Exception) { null }
    }

    /** A página de teste do Google (responde 204, vazia). (código, ms) ou (null, null) se não conectou. */
    private fun http(): Pair<Int?, Long?> {
        val ini = SystemClock.elapsedRealtime()
        return try {
            val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000; c.useCaches = false; c.instanceFollowRedirects = false
            try { c.responseCode to (SystemClock.elapsedRealtime() - ini) } finally { c.disconnect() }
        } catch (_: Exception) { null to null }
    }

    /**
     * O que o roteador UniFi acha (só se a integração estiver pronta). (opinião, mudo): mudo = não respondeu na rede
     * de casa (sinal de roteador travado). Recusa de chave, API ausente etc. = "não consultado", não "mudo".
     */
    private fun roteador(app: Context): Pair<InternetEmUmaFrase.Roteador?, Boolean> {
        if (!UnifiArmazem.pronto(app)) return null to false
        return try { InternetEmUmaFrase.lerSaudeRoteador(UnifiCliente.saude(app)) to false }
        catch (e: UnifiCliente.Falha) { null to InternetEmUmaFrase.roteadorMudo(e.message) }
        catch (_: Exception) { null to false }
    }
}

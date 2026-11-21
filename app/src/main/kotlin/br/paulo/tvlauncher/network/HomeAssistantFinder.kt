package br.paulo.tvlauncher.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A Casa reencontra o Home Assistant sozinha (Paulo, 18/11/2026 14:17 local: "se não for plug & play, não funciona").
 * Quando o endereço salvo para de responder (o PC ganhou outro IP do roteador, trocou o roteador), a TV procura na rede:
 * 1. pelo anúncio do próprio Home Assistant na rede (mDNS `_home-assistant._tcp`, o que o HA OS faz sozinho);
 * 2. pela porta 8123 em cada endereço da rede da TV (/24), para o HA em Docker, que não se anuncia para fora.
 * Só aceita quem parece Home Assistant SEM a chave (`/auth/providers`) e depois aceita a chave salva (`/api/` = "API
 * running"): a chave só vai para um Home Assistant de verdade, e só o NOSSO responde 200 a ela. Achou: grava e segue.
 */
object HomeAssistantFinder {
    private const val TAG = "TvLauncher.Casa"
    const val PORTA = 8123
    /** Uma procura no máximo a cada 5 min (uma lista que falha várias vezes seguidas não vira varredura sem fim). */
    const val INTERVALO_MS = 5 * 60_000L
    private const val ANUNCIO_MS = 3_000L
    private const val CONECTAR_MS = 400
    private const val FIOS = 48

    // ---------------------------------------------------------------- lógica pura (testada na JVM)

    /** Os endereços da rede /24 da TV, sem ela mesma, começando pelos vizinhos do último endereço que funcionava. */
    fun candidatos(ipTv: String, ultimoHost: String?): List<String> {
        val p = ipTv.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return emptyList()
        val base = "${p[0]}.${p[1]}.${p[2]}."
        val perto = ultimoHost?.takeIf { it.startsWith(base) }?.substringAfterLast('.')?.toIntOrNull() ?: p[3]
        return (1..254).filter { it != p[3] }.sortedBy { kotlin.math.abs(it - perto) }.map { base + it }
    }

    /** `/auth/providers` sem chave: o HA responde a lista dos jeitos de entrar, com o tipo "homeassistant". */
    fun pareceHomeAssistant(codigo: Int, corpo: String): Boolean =
        codigo == 200 && corpo.trimStart().let { it.startsWith("[") || it.startsWith("{") } && corpo.contains("\"homeassistant\"")

    /** `/api/` com a chave: 200 e "API running." só no Home Assistant que conhece a chave. */
    fun aceitouChave(codigo: Int, corpo: String): Boolean = codigo == 200 && corpo.contains("API running")

    fun endereco(host: String, porta: Int): String = "http://$host:$porta"

    /**
     * A chave só vai a um anúncio do MESMO Home Assistant (20/11/2026, debug: antes ia a qualquer aparelho da rede que
     * dissesse "homeassistant", e um aparelho falso na rede recebia o controle da casa). [uuidSalvo] = o `uuid` do
     * anúncio mDNS do HA que já aceitou a chave uma vez; sem ele (1ª procura) vale qualquer anúncio.
     */
    fun anuncioAceito(uuidSalvo: String?, uuidAnuncio: String?): Boolean = uuidSalvo == null || uuidSalvo == uuidAnuncio

    /** A varredura da porta 8123 (HA em Docker, sem anúncio) só quando o HA nunca se anunciou: nada prova quem responde. */
    fun podeVarrer(uuidSalvo: String?): Boolean = uuidSalvo == null

    /** Nunca a própria TV (127/8): um app qualquer nesta TV poderia ouvir a 8123 e receber a chave. */
    fun hostAceito(host: String): Boolean = !host.startsWith("127.") && host != "localhost"

    // ---------------------------------------------------------------- na TV (fora da thread da tela)

    @Volatile private var ultimaProcura = 0L

    /**
     * Procura o Home Assistant que aceita [token]. Devolve o endereço achado (já gravado) ou null. [forcar] ignora o
     * intervalo (adb: EXTRAS casa_procurar).
     */
    @Synchronized fun reencontrar(ctx: Context, token: String, atual: String?, forcar: Boolean = false): String? {
        val agora = System.currentTimeMillis()
        if (!forcar && agora - ultimaProcura < INTERVALO_MS) return null
        ultimaProcura = agora
        val app = ctx.applicationContext
        val ipTv = UnifiCliente.ipDaTv(app) ?: return null.also { Log.i(TAG, "procura: TV fora do Wi-Fi") }
        val ultimoHost = atual?.let { runCatching { URL(it).host }.getOrNull() }
        val vistos = HashSet<String>()
        fun testar(host: String, porta: Int): String? {
            val e = endereco(host, porta)
            if (!hostAceito(host) || !vistos.add(e) || CasaHA.enderecoValido(e) == null) return null
            val (c1, b1) = get("$e/auth/providers", null) ?: return null
            if (!pareceHomeAssistant(c1, b1)) return null
            val (c2, b2) = get("$e/api/", token) ?: return null
            return e.takeIf { aceitouChave(c2, b2) }
        }
        val uuidSalvo = CasaHA.uuidHa(app)
        // 1. anúncio na rede (HA OS, HA Core com host network), só do MESMO HA quando já sabemos qual é
        for ((h, porta, uuid) in anunciados(app)) {
            if (!anuncioAceito(uuidSalvo, uuid)) { Log.w(TAG, "procura: anúncio de outro Home Assistant ignorado"); continue }
            testar(h, porta)?.let { e -> if (uuid != null) CasaHA.guardarUuidHa(app, uuid); return gravar(app, e) }
        }
        // 2. porta 8123 aberta em algum endereço da rede (HA em Docker no PC), só se este HA nunca se anunciou
        if (podeVarrer(uuidSalvo)) for (h in abertos(candidatos(ipTv, ultimoHost))) testar(h, PORTA)?.let { return gravar(app, it) }
        Log.i(TAG, "procura: nenhum Home Assistant aceitou a chave")
        return null
    }

    private fun gravar(ctx: Context, e: String): String? {
        val erro = CasaHA.trocarEndereco(ctx, e)
        if (erro != null) { Log.w(TAG, "procura: achei mas não gravei ($erro)"); return null }
        Log.i(TAG, "procura: Home Assistant reencontrado na rede")
        return e
    }

    private fun get(url: String, token: String?): Pair<Int, String>? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_500; readTimeout = 2_000; instanceFollowRedirects = false
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val codigo = c.responseCode
            val corpo = (if (codigo in 200..299) c.inputStream else c.errorStream)?.use { ent ->
                val b = ByteArray(8 * 1024); val n = ent.read(b); if (n > 0) String(b, 0, n) else ""
            }.orEmpty()
            codigo to corpo
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** Quem responde na porta 8123, em paralelo (254 endereços em ~2 s). */
    private fun abertos(hosts: List<String>): List<String> {
        val pool = Executors.newFixedThreadPool(FIOS)
        try {
            val futuros = hosts.map { h -> h to pool.submit<Boolean> {
                try { Socket().use { s -> s.connect(InetSocketAddress(h, PORTA), CONECTAR_MS) }; true } catch (e: Exception) { false }
            } }
            return futuros.filter { (_, f) -> runCatching { f.get(5, TimeUnit.SECONDS) }.getOrDefault(false) }.map { it.first }
        } finally { pool.shutdownNow() }
    }

    /** Os Home Assistant que se anunciam na rede (mDNS), por [ANUNCIO_MS]. */
    @Suppress("DEPRECATION")
    private fun anunciados(ctx: Context): List<Triple<String, Int, String?>> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val achados = java.util.Collections.synchronizedList(ArrayList<NsdServiceInfo>())
        val ouvinte = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) { achados += s }
        }
        try { nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, ouvinte) } catch (e: Exception) { return emptyList() }
        try { Thread.sleep(ANUNCIO_MS) } catch (e: InterruptedException) {}
        runCatching { nsd.stopServiceDiscovery(ouvinte) }
        val saida = java.util.Collections.synchronizedList(ArrayList<Triple<String, Int, String?>>())
        for (s in achados.toList().take(4)) {   // um resolve por vez (Android antigo não aceita dois juntos)
            val pronto = CountDownLatch(1)
            runCatching {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { pronto.countDown() }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val uuid = runCatching { i.attributes["uuid"]?.toString(Charsets.UTF_8)?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                        i.host?.hostAddress?.takeIf { '.' in it }?.let { saida += Triple(it, i.port, uuid) }
                        pronto.countDown()
                    }
                })
            }.onFailure { pronto.countDown() }
            pronto.await(2, TimeUnit.SECONDS)
        }
        return saida.toList()
    }
}

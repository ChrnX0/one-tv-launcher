package br.paulo.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Painel no navegador SERVIDO PELO PRÓPRIO LAUNCHER (04/11/2026), sem PC: em qualquer celular ou computador da casa,
 * abrir http://<IP da TV>:8080/ (ou http://tv:8080/ com o nome cadastrado no roteador), digitar o PIN
 * definido nas Configurações do launcher e administrar a TV. Plano completo: painel-no-launcher.md.
 * Com cadeado (13/11/2026): o mesmo painel em https://tv:8444/, com o certificado que a TV gera (PainelTls.kt).
 *
 * Fase 1: entrar com PIN, lista de apps com ícone, ocultar/reexibir, desinstalar e backup (baixar/restaurar).
 * Fase 2: permissões de cada app, instalar APK e a aba Arquivos (memória interna: listar, enviar, baixar, apagar).
 *
 * Custo: com o painel DESLIGADO (o padrão), nenhum socket nem thread; [ajustar] só lê uma preferência. Ligado e
 * parado, uma thread presa no accept() e nada mais (sem timer, sem alarme). Atendendo, até 3 threads de prioridade
 * baixa, que morrem depois de 30 s sem trabalho.
 *
 * Segurança (seção 3 do plano), na ordem em que cada requisição passa:
 *  1. A conexão tem de chegar pela rede local (Wi-Fi/cabo), não pela VPN, de um endereço privado.
 *  2. O Host tem de ser um nome nosso (barra DNS rebinding). Em POST, Origin = o próprio painel e o cabeçalho
 *     X-Painel (barra CSRF).
 *  3. Fora a página e o login, tudo exige sessão (cookie HttpOnly, SameSite=Strict).
 *  4. Desinstalar e restaurar backup exigem o PIN redigitado nos últimos 5 min.
 *  5. PIN errado: bloqueio crescente e, com 20 erros, o painel se desliga (só volta pelas Configurações da TV).
 *  Não existe rota de shell genérico: cada ação é uma função fixa, e o pacote é validado antes de tudo.
 */
object PainelWeb {
    private const val TAG = "TvLauncher.Painel"
    const val COOKIE = "painel_sessao"
    private const val LIMITE_CORPO = 4 * 1024   // login e ações: corpo pequeno
    private const val TEMPO_LEITURA_MS = 15_000

    @Volatile private var servidor: ServerSocket? = null
    /** A porta https da rede de casa (PainelTls, 13/11/2026): o mesmo painel, com o certificado que a TV gera. */
    @Volatile private var servidorTls: ServerSocket? = null
    private lateinit var app: Context

    val sessoes = Sessoes({ System.currentTimeMillis() })

    /** Entrada pelo QR da TV (EntrarPeloQrActivity): um código de uso único, 2 min. */
    val codigoEntrada = CodigoEntrada({ System.currentTimeMillis() })

    /**
     * Gera um código novo e devolve o endereço que vai no QR, ou null se o painel não está no ar. O código vai depois
     * do # (fragmento): o navegador não o manda em pedido nenhum nem o põe no Referer; o app.js lê e manda por POST.
     */
    fun linkEntradaQr(destino: DestinoPainel? = null): String? {
        if (!ligado()) return null
        val base = endereco() ?: return null
        return base + DestinoPainel.fragmento(codigoEntrada.novo(), destino)   // ideia 43: abre já na página certa
    }
    @Volatile private var tentativas: ControleTentativas? = null
    private val travaPin = Any()   // um PBKDF2 por vez: muitas tentativas juntas não derrubam a CPU da TV

    /**
     * Até 6 threads atendendo e até 24 conexões esperando; além disso a conexão é recusada. 6 (10/11/2026; antes 4):
     * os eventos ao vivo ([MAX_EVENTOS] janelas) e o vídeo da tela seguram uma thread cada enquanto estão abertos, e
     * um envio e um download grandes juntos ainda têm de deixar a página responder. Thread parada custa ~1 MB.
     */
    private val pool by lazy {
        ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(24)) { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "Painel-atende")
        }.apply { allowCoreThreadTimeOut(true) }
    }

    @Volatile private var iniciando = false
    private var geracao = 0   // cada iniciar() ganha um número; uma thread de bind antiga não mexe no estado da nova

    fun ligado(): Boolean = servidor?.isClosed == false

    /** Liga ou desliga conforme as preferências (ver PainelArmazem.podeLigar). Barato e idempotente: chamar à vontade. */
    @Synchronized fun ajustar(ctx: Context) {
        app = ctx.applicationContext
        val deve = PainelArmazem.podeLigar(app)
        if (deve && !ligado() && !iniciando) iniciar() else if (!deve && (ligado() || iniciando)) parar()
    }

    /** Só chamar dentro de synchronized(travaPin): assim todas as threads usam o MESMO contador. */
    private fun controle(): ControleTentativas = tentativas ?: ControleTentativas(
        { System.currentTimeMillis() }, PainelArmazem.falhas(app),
    ) { PainelArmazem.definirFalhas(app, it) }.also { tentativas = it }

    /** Chamado pelas Configurações quando o PIN muda ou o painel é religado: derruba as sessões e zera os erros. */
    @Synchronized fun recomecar(ctx: Context) {
        app = ctx.applicationContext
        sessoes.encerrarTodas()
        tentativas?.zerar()
        ajustar(ctx)
    }

    /** Abre a porta numa thread própria (abrir socket na thread da tela é proibido no Android) e fica no accept(). */
    private fun iniciar() {
        iniciando = true
        val minha = ++geracao
        Thread({
            val s = ServerSocket()
            try {
                s.reuseAddress = true   // religar logo depois de desligar não dá "endereço em uso"
                // 0.0.0.0 (só IPv4): a checagem da conexão (redeLocal) decide quem pode falar.
                s.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), Config.PAINEL_PORTA), 16)
            } catch (e: IOException) {
                Log.w(TAG, "não consegui abrir a porta ${Config.PAINEL_PORTA}: $e")
                try { s.close() } catch (_: IOException) {}
                synchronized(this) { if (geracao == minha) iniciando = false }
                return@Thread
            }
            synchronized(this) {
                // Desligaram (ou religaram) enquanto abria? Então esta porta não vale mais: fecha já.
                if (geracao != minha || !iniciando) { try { s.close() } catch (_: IOException) {}; return@Thread }
                servidor = s
                iniciando = false
            }
            Log.i(TAG, "painel ligado na porta ${Config.PAINEL_PORTA}")
            PainelVizinhos.ligar(app)   // anuncia esta TV e procura as outras na rede (mDNS)
            iniciarTls(minha)
            aceitar(s, https = false)
            // Saiu do laço: se não foi um parar() (a porta ainda é a atual), limpa para o próximo ajustar() religar.
            synchronized(this) { if (servidor === s) servidor = null }
            try { s.close() } catch (_: IOException) {}
        }, "Painel-aceita").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    @Synchronized private fun parar() {
        iniciando = false
        geracao++
        try { servidor?.close() } catch (_: IOException) {}
        servidor = null
        try { servidorTls?.close() } catch (_: IOException) {}
        servidorTls = null
        sessoes.encerrarTodas()
        PainelVizinhos.desligar()
        Log.i(TAG, "painel desligado")
    }

    // ---------------------------------------------------------------- https na rede de casa (13/11/2026)

    /**
     * Abre a porta https ([PainelTls.PORTA]) junto com a http. O certificado é carregado (ou feito, na primeira vez)
     * numa thread própria: gerar chave leva alguns milissegundos e ler o Keystore também não é coisa da thread da tela.
     * Sem certificado (Keystore com defeito), o painel segue só em http.
     */
    private fun iniciarTls(minha: Int) {
        Thread({
            // O IP entra no certificado: espera a rede aparecer (até ~5 min) se o painel ligou antes do Wi-Fi.
            var rede = redes().entries.minByOrNull { it.key }
            var tentativas = 0
            while (rede == null && tentativas++ < 10) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@Thread }
                if (geracao != minha) return@Thread
                redeCache = 0L to emptyMap()
                rede = redes().entries.minByOrNull { it.key }
            }
            val e = PainelTls.garantir(app, rede?.key, rede?.value ?: 24) ?: return@Thread
            abrirTls(minha, e)
        }, "Painel-https").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Abre a porta https com este certificado e fica no accept() até parar() ou uma renovação fechar a porta. */
    private fun abrirTls(minha: Int, e: CertificadoX509.Emissao) {
        var s: javax.net.ssl.SSLServerSocket? = null
        for (vez in 1..3) {   // logo depois de renovar, a porta velha pode demorar um instante para soltar
            val novo = PainelTls.contexto(e).serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
            try {
                novo.reuseAddress = true
                novo.enabledProtocols = PainelTls.protocolos(novo.supportedProtocols)
                novo.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), PainelTls.PORTA), 16)
                s = novo
                break
            } catch (ex: IOException) {
                try { novo.close() } catch (_: IOException) {}
                if (vez == 3) { Log.w(TAG, "não consegui abrir a porta https ${PainelTls.PORTA}: $ex"); return }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            }
        }
        val porta = s ?: return
        synchronized(this) {
            if (geracao != minha || servidor == null) { try { porta.close() } catch (_: IOException) {}; return }
            servidorTls = porta
        }
        Log.i(TAG, "painel https ligado na porta ${PainelTls.PORTA}")
        aceitar(porta, https = true)
        synchronized(this) { if (servidorTls === porta) servidorTls = null }
        try { porta.close() } catch (_: IOException) {}
    }

    private fun ligadoTls(): Boolean = servidorTls?.isClosed == false

    /**
     * Botão "Gerar certificado novo" do painel (pede o PIN de novo): CA nova, e reabre a porta https com ela.
     * Com {"nome": "tv-sala"} no corpo, troca antes o nome da TV no certificado (o que está no DNS do roteador).
     */
    private fun renovarCertificado(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val novoNome = corpoJson(req)?.optString("nome", "")?.trim()?.lowercase().orEmpty()
        if (novoNome.isNotEmpty()) {
            if (!ChecagensPainel.nomeCertificadoValido(novoNome))
                return erro(400, "nome inválido: uma palavra só, com letras, números e hífen (ex.: tv-sala)")
            PainelTls.definirNome(app, novoNome)
        }
        val rede = redes().entries.minByOrNull { it.key }
        val e = PainelTls.emitir(app, rede?.key, rede?.value ?: 24)
        PainelArmazem.registrar(app, ip, "gerar certificado https novo", if (e == null) "falhou" else "ok")
        if (e == null) return erro(500, "a TV não conseguiu gerar o certificado")
        val minha = synchronized(this) { geracao }
        // Fecha a porta velha (as conexões já abertas, inclusive esta, continuam até responder) e abre com o novo.
        try { servidorTls?.close() } catch (_: IOException) {}
        Thread({ abrirTls(minha, e) }, "Painel-https").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        return json(200, PainelTls.estado(app, ipsDaRede(), true))
    }

    /** Nomes aceitos no Host da porta https: os do certificado e os IPs da TV (sempre com :8444). */
    private fun nomesTls(): Set<String> = PainelTls.nomesNoCertificado() + ipsDaRede()

    /** O Host vale para a porta por onde o pedido chegou? */
    private fun hostOk(host: String?, https: Boolean): Boolean =
        if (https) ChecagensPainel.viaHttps(host, nomesTls(), PainelTls.PORTA)
        else ChecagensPainel.hostPermitido(host, nomesPermitidos(), Config.PAINEL_PORTA) || ChecagensPainel.viaHttps(host, nomesHttps())

    /** Página do passo a passo (assets/painel/certificado.html), com os dados do certificado já preenchidos. */
    private fun paginaCertificado(): Resposta {
        val modelo = app.assets.open("painel/certificado.html").use { it.readBytes() }.toString(Charsets.UTF_8)
        val html = PainelTls.estado(app, ipsDaRede(), ligadoTls()).let { PaginaCertificado.preencher(modelo, it, it.optString("nome")) }
        return Resposta(200, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun arquivoCertificado(): Resposta {
        val pem = PainelTls.pemDaCa() ?: return erro(503, "o certificado ainda não foi gerado; tente em 1 minuto")
        return Resposta(200, "application/x-x509-ca-cert", pem.toByteArray(),
            mapOf("Content-Disposition" to "attachment; filename=\"one-tv-painel.crt\""))
    }

    private fun aceitar(s: ServerSocket, https: Boolean) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) {
                if (s.isClosed) break   // parar() fechou a porta: fim
                // Erro passageiro (ex.: faltou descritor de arquivo): não deixa o painel morrer em silêncio.
                Log.w(TAG, "accept falhou: $e")
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                continue
            }
            if (!redeLocal(c)) { fechar(c); continue }
            try {
                pool.execute { atender(c, https) }
            } catch (e: RejectedExecutionException) {
                fechar(c)   // fila cheia: alguém está martelando o painel
            }
        }
    }

    private fun fechar(c: Socket) = try { c.close() } catch (_: IOException) {}

    // ---------------------------------------------------------------- de onde vem a conexão

    /** IPv4 da TV na rede de casa -> tamanho do prefixo da sub-rede (ex.: "192.168.1.50" -> 24). */
    @Volatile private var redeCache: Pair<Long, Map<String, Int>> = 0L to emptyMap()

    /**
     * Os IPv4 da TV na rede de casa: interfaces ligadas de Wi-Fi ou cabo (wlan*, eth*), menos túneis de VPN e o
     * Wi-Fi Direct (p2p). Recalculado no máximo a cada 30 s (o IP só muda se o roteador mudar).
     */
    private fun redes(): Map<String, Int> {
        val (quando, atual) = redeCache
        if (System.currentTimeMillis() - quando < 30_000 && atual.isNotEmpty()) return atual
        val novas = HashMap<String, Int>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return emptyMap()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val nome = ni.name.lowercase()
                if (!nome.startsWith("wlan") && !nome.startsWith("eth")) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) novas[a.hostAddress ?: continue] = ia.networkPrefixLength.toInt()
                }
            }
        } catch (e: Exception) { Log.w(TAG, "redes: $e") }
        redeCache = System.currentTimeMillis() to novas
        return novas
    }

    fun ipsDaRede(): Set<String> = redes().keys

    /** O endereço para digitar no navegador (o primeiro IP da rede), ou null sem rede. */
    fun endereco(): String? = ipsDaRede().minOrNull()?.let { "http://$it:${Config.PAINEL_PORTA}/" }

    /**
     * A conexão chegou num IP nosso de Wi-Fi/cabo (não no da VPN) e veio da MESMA sub-rede (ex.: 192.168.1.x)?
     * Recusa tudo o que vier pela VPN, de outra VLAN do roteador ou de fora da casa.
     */
    private fun redeLocal(c: Socket): Boolean {
        val local = c.localAddress as? Inet4Address ?: return false
        val remoto = c.inetAddress as? Inet4Address ?: return false
        val prefixo = redes()[local.hostAddress] ?: return false
        return !remoto.isLoopbackAddress && ChecagensPainel.mesmaRede(local.address, remoto.address, prefixo)
    }

    private fun nomesPermitidos(): Set<String> = ipsDaRede() + Config.PAINEL_NOMES + nomesTailscale()

    /** Com a "rede Tailscale" ligada, o nome completo (…ts.net) também atende em https na porta 8443 (o túnel decifra). */
    private fun nomesHttps(): Set<String> {
        if (!TunelTailscale.redeLigada(app)) return emptySet()
        return setOfNotNull(TunelTailscale.nome(app)?.lowercase())
    }

    /** Opção "rede Tailscale" ligada: o nome da TV na conta (curto e completo), que o túnel repassa para cá. */
    private fun nomesTailscale(): Set<String> {
        if (!TunelTailscale.redeLigada(app)) return emptySet()
        val completo = TunelTailscale.nome(app) ?: return emptySet()
        return setOf(completo, completo.substringBefore('.'))
    }

    /**
     * Saída de resposta longa (vídeo ao vivo, eventos) com prazo de escrita (auditoria 10/11/2026). No Java o tempo do
     * socket só vale para LER: se o aparelho do outro lado some sem fechar a conexão (notebook dormiu, Wi-Fi caiu), o
     * write fica preso até o TCP desistir (~15 min), segurando a thread e a vaga do vídeo ("já tem alguém vendo").
     * Um vigia confere a cada 2 s; um write parado há mais de [PRAZO_MS] fecha o socket, o write lança IOException e
     * a rota libera a vaga no finally dela.
     */
    private class EscritaVigiada(private val dentro: java.io.OutputStream, private val c: Socket) : java.io.OutputStream() {
        @Volatile private var escrevendoDesde = 0L   // 0 = nenhum write em andamento
        @Volatile private var fim = false
        private val vigia = Thread({
            while (!fim) {
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { break }
                val desde = escrevendoDesde
                if (desde != 0L && System.currentTimeMillis() - desde > PRAZO_MS) { fechar(c); break }
            }
        }, "painel-vigia-escrita").apply { isDaemon = true; start() }

        private inline fun vigiado(bloco: () -> Unit) {
            escrevendoDesde = System.currentTimeMillis()
            try { bloco() } finally { escrevendoDesde = 0L }
        }
        override fun write(b: Int) = vigiado { dentro.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = vigiado { dentro.write(b, off, len) }
        override fun flush() = vigiado { dentro.flush() }
        override fun close() { fim = true; vigia.interrupt() }   // não fecha o socket: quem fecha é o atender()

        companion object { const val PRAZO_MS = 10_000L }
    }

    // ---------------------------------------------------------------- atender uma requisição

    /** [arquivo] != null: o corpo é esse arquivo, mandado aos pedaços (download da aba Arquivos). */
    private class Resposta(val codigo: Int, val tipo: String?, val corpo: ByteArray, val extras: Map<String, String> = emptyMap(),
                           val arquivo: File? = null, val fluxo: ((java.io.OutputStream) -> Unit)? = null,
                           val range: String? = null)   // cabeçalho Range do pedido, para arquivo (vídeo pula para o meio)

    /** Rotas que recebem o corpo como fluxo, direto para o disco (arquivo de vários GB não cabe na memória). */
    private val ROTAS_FLUXO = setOf("/api/arquivos/enviar", "/api/apps/instalar", "/api/fotos/enviar", "/api/perfil/foto")

    private fun json(codigo: Int, o: JSONObject, extras: Map<String, String> = emptyMap()) =
        Resposta(codigo, "application/json; charset=utf-8", o.toString().toByteArray(), extras)

    private fun erro(codigo: Int, mensagem: String, extra: JSONObject.() -> Unit = {}) =
        json(codigo, JSONObject().put("erro", mensagem).apply(extra))

    /** [https]: chegou pela porta https (a conversa TLS acontece aqui, no primeiro read, não na thread do accept). */
    private fun atender(c: Socket, https: Boolean) {
        val ip = c.inetAddress.hostAddress ?: "?"
        try {
            c.soTimeout = TEMPO_LEITURA_MS
            val entrada = c.getInputStream().buffered(8192)
            val saida = c.getOutputStream().buffered(16 * 1024)
            val resposta = try {
                val req = PainelHttp.lerCabecalhos(entrada)
                // O corpo: nas rotas de fluxo fica na conexão (a rota lê depois de conferir sessão e PIN); nas outras
                // é lido já, com limite (só a restauração de backup aceita um corpo maior). Antes de ler um byte do
                // corpo, as checagens baratas (nome, origem e, nos corpos grandes, sessão): quem não passa nem ocupa
                // a thread mandando megabytes (auditoria 10/11/2026).
                val barrado = if (req.caminho !in ROTAS_FLUXO) antesDoCorpo(req, https) else null
                if (barrado == null && req.caminho !in ROTAS_FLUXO) req.lerCorpo(limiteCorpo(req.caminho))
                // Recusado: drena o corpo (sem guardar) para o navegador conseguir ler a resposta; ver descartarCorpo.
                else if (barrado != null) req.descartarCorpo(limiteCorpo(req.caminho))
                when {
                    barrado != null -> barrado
                    // JSON aninhado demais estoura a pilha do leitor do Android e derruba o launcher: recusa antes.
                    // A cena por link não lê o corpo (Atalhos do iPhone e Home Assistant às vezes mandam formulário).
                    req.caminho !in ROTAS_FLUXO && !req.caminho.startsWith("/cena/") && req.corpo.isNotEmpty() &&
                        !ChecagensPainel.jsonRaso(req.corpoTexto()) -> erro(400, "JSON inválido")
                    else -> try {
                        rotear(req, ip, https)
                    } catch (e: StackOverflowError) {
                        // Defesa em profundidade: um leitor recursivo (JSON) que escapou do jsonRaso. É Error, não
                        // Exception: sem este catch subiria até o tratador do Android e fecharia o launcher inteiro.
                        Log.w(TAG, "pilha estourou em ${req.metodo} ${req.caminho}")
                        erro(400, "pedido inválido")
                    } catch (e: PainelArquivos.Erro) {
                        erro(e.codigo, e.message ?: "erro")
                    } catch (e: IOException) {
                        throw e   // a conexão caiu no meio (ex.: envio cancelado): nada a responder
                    } catch (e: Exception) {
                        // Erro nosso no meio de uma rota: responde 500 em vez de fechar a conexão calado.
                        Log.w(TAG, "erro em ${req.metodo} ${req.caminho}: $e")
                        erro(500, "erro na TV: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: ErroHttp) {
                erro(e.codigo, e.message ?: "pedido inválido")
            }
            val arq = resposta.arquivo
            val fluxoResp = resposta.fluxo
            if (fluxoResp != null) {
                c.soTimeout = 0   // vídeo ao vivo: a rota escreve o cabeçalho e o corpo até acabar (PainelExtras.video)
                EscritaVigiada(saida, c).use { fluxoResp(it) }
            } else if (arq == null) {
                PainelHttp.escrever(saida, resposta.codigo, resposta.tipo, resposta.corpo, resposta.extras)
            } else {
                // Arquivo: abre ANTES de mandar o cabeçalho (se sumiu, ainda dá para responder 404) e manda em pedaços.
                // O tamanho é o do arquivo aberto; se ele crescer no meio, mandamos só os bytes anunciados.
                val fluxo = try { java.io.FileInputStream(arq) } catch (e: IOException) { null }
                if (fluxo == null) {
                    val r = erro(404, "não existe (talvez apagado na TV)")
                    PainelHttp.escrever(saida, r.codigo, r.tipo, r.corpo)
                } else fluxo.use {
                    val total = arq.length()
                    val f = PainelHttp.faixa(resposta.range, total)
                    if (f != null && f.first < 0) {
                        val r = erro(416, "intervalo fora do arquivo")
                        PainelHttp.escrever(saida, r.codigo, r.tipo, r.corpo, mapOf("Content-Range" to "bytes */$total"))
                        return@use
                    }
                    val tamanho = if (f == null) total else f.second - f.first + 1
                    val extras = HashMap(resposta.extras).apply { put("Accept-Ranges", "bytes") }
                    if (f != null) {
                        extras["Content-Range"] = "bytes ${f.first}-${f.second}/$total"
                        var pular = f.first
                        while (pular > 0) { val n = it.skip(pular); if (n <= 0) break; pular -= n }
                    }
                    PainelHttp.escreverCabecalho(saida, if (f == null) 200 else 206, resposta.tipo, tamanho, extras)
                    val buf = ByteArray(64 * 1024)
                    var faltam = tamanho
                    while (faltam > 0) {
                        val n = it.read(buf, 0, minOf(buf.size.toLong(), faltam).toInt())
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        faltam -= n
                    }
                    saida.flush()
                }
            }
        } catch (e: IOException) {
            // conexão caiu ou demorou demais: nada a responder
        } catch (e: Exception) {
            Log.w(TAG, "erro atendendo $ip: $e")
        } finally {
            fechar(c)
        }
    }

    private fun limiteCorpo(caminho: String): Int = when (caminho) {
        "/api/backup/restaurar" -> BackupConfig.LIMITE_BYTES
        PainelExtras.ROTA_FOTO -> PainelExtras.LIMITE_FOTO
        "/api/backup/completo/restaurar" -> BackupCompleto.LIMITE_BYTES * 2 + 1024   // o cofre em Base64 + a senha
        else -> LIMITE_CORPO
    }

    /**
     * As checagens de [rotear] que não dependem do corpo, feitas ANTES de lê-lo: nome (Host), e nos POST a origem e o
     * X-Painel; nos corpos maiores que o comum, também a sessão. null = pode ler o corpo. [rotear] confere tudo de
     * novo (fica um caminho só para as regras); aqui é só para não gastar thread e memória com quem vai ser recusado.
     */
    private fun antesDoCorpo(req: Requisicao, https: Boolean): Resposta? {
        val host = req.cabecalho("host")
        if (!hostOk(host, https)) return erro(421, "nome desconhecido")
        // Cena e recado por link vêm de fora do painel, sem Origin (o token vai no caminho): corpo pequeno, comum.
        if (req.caminho.startsWith("/cena/") || req.caminho.startsWith("/recado/")) return null
        if (req.metodo == "POST" &&
            (!ChecagensPainel.origemPermitida(req.cabecalho("origin"), host) || req.cabecalho("x-painel") != "1"))
            return erro(403, "pedido de fora do painel")
        if (req.tamanho > LIMITE_CORPO &&
            !sessoes.existe(PainelHttp.cookie(req.cabecalho("cookie"), COOKIE))) return erro(401, "entre com o PIN")
        return null
    }

    private fun rotear(req: Requisicao, ip: String, https: Boolean): Resposta {
        val host = req.cabecalho("host")
        if (!hostOk(host, https)) return erro(421, "nome desconhecido")
        // Cena por link (Atalhos do iPhone, Home Assistant): vem de fora do painel, sem Origin; a senha é o token.
        if (req.caminho.startsWith("/cena/")) return PainelExtras.cena(app, req.metodo, req.caminho.removePrefix("/cena/"), ip).let { json(it.first, it.second) }
        // Recado por link (Home Assistant, formato TvOverlay/PiPup): também de fora, com o token no caminho.
        if (req.caminho.startsWith("/recado/")) return PainelExtras.recadoPorLink(app, req.metodo, req.caminho.removePrefix("/recado/"),
            if (req.metodo == "POST") req.corpoTexto() else null, ip).let { json(it.first, it.second) }
        if (req.metodo == "POST") {
            if (!ChecagensPainel.origemPermitida(req.cabecalho("origin"), host) || req.cabecalho("x-painel") != "1")
                return erro(403, "pedido de fora do painel")
        } else if (req.metodo != "GET") {
            return erro(405, "método não suportado")
        }

        // Página e arquivos dela: livres (a página de login é ela mesma).
        if (req.metodo == "GET") estatico(req.caminho)?.let { return it }
        // https na rede de casa (PainelTls): passo a passo, o arquivo para instalar e o estado. Livres como a página:
        // o certificado é público por natureza e é preciso baixá-lo ANTES de conseguir entrar pelo https.
        if (req.metodo == "GET") when (req.caminho) {
            "/certificado" -> return paginaCertificado()
            "/certificado.crt" -> return arquivoCertificado()
            "/certificado.css" -> return Resposta(200, "text/css; charset=utf-8",
                app.assets.open("painel/certificado.css").use { it.readBytes() })
            "/api/certificado" -> return json(200, PainelTls.estado(app, ipsDaRede(), ligadoTls()))
        }

        val token = PainelHttp.cookie(req.cabecalho("cookie"), COOKIE)
        when (req.caminho) {
            "/api/estado" -> return estado(token)
            "/api/eventos" -> if (req.metodo == "GET") return eventos(token)
            "/api/entrar" -> if (req.metodo == "POST") return entrar(req, ip)
            "/api/entrar-qr" -> if (req.metodo == "POST") return entrarPeloQr(req, ip)
            "/api/sair" -> if (req.metodo == "POST") { sessoes.encerrar(token); return json(200, JSONObject().put("ok", true), cookieApagado()) }
        }
        if (!req.caminho.startsWith("/api/")) return erro(404, "não existe")
        // As outras TVs da casa: a página pede sozinha junto com o estado (a cada 30 s). existe() e não valida(),
        // pelo mesmo motivo do /api/estado: consulta automática não pode manter a sessão viva para sempre.
        if (req.caminho == "/api/tvs" && req.metodo == "GET")
            return if (sessoes.existe(token)) json(200, PainelVizinhos.json(app)) else erro(401, "entre com o PIN")
        if (!sessoes.valida(token)) return erro(401, "entre com o PIN")
        if (req.caminho.startsWith("/api/extras/")) {   // ideias de 10/11/2026: PainelExtras.kt
            if (req.caminho == "/api/extras/video" && req.metodo == "GET")
                return Resposta(200, null, ByteArray(0), fluxo = { PainelExtras.video(app, it) })
            PainelExtras.rotear(app, req.metodo, req.caminho.removePrefix("/api/extras/"),
                if (req.metodo == "POST") corpoJson(req) else null, req.consulta, sessoes.elevada(token), ip)
                ?.let { return json(it.first, it.second) }
        }

        when (req.caminho) {
            "/api/confirmar" -> if (req.metodo == "POST") return confirmar(req, token, ip)
            "/api/apps" -> if (req.metodo == "GET") return json(200, JSONObject().put("apps", PainelApps.listar(app)))
            "/api/backup" -> if (req.metodo == "GET") {
                PainelArmazem.registrar(app, ip, "baixar backup", "ok")
                return Resposta(200, "application/json; charset=utf-8", BackupConfig.exportar(app).toByteArray(),
                    mapOf("Content-Disposition" to "attachment; filename=\"launcher-backup.json\""))
            }
            "/api/backup/anterior" -> if (req.metodo == "GET") {
                val f = BackupConfig.arquivoAnterior(app)
                if (!f.exists()) return erro(404, "nenhuma restauração feita ainda")
                return Resposta(200, "application/json; charset=utf-8", f.readBytes(),
                    mapOf("Content-Disposition" to "attachment; filename=\"launcher-antes-de-restaurar.json\""))
            }
            "/api/backup/restaurar" -> if (req.metodo == "POST") return restaurar(req, token, ip)
            // Backup completo e cifrado (BackupCompleto.kt): leva segredos, então baixar também pede o PIN de novo.
            "/api/backup/completo" -> if (req.metodo == "POST") return backupCompleto(req, token, ip)
            "/api/backup/completo/restaurar" -> if (req.metodo == "POST") return restaurarCompleto(req, token, ip)
            // Backup automático (BackupAutomatico.kt): as cópias diárias guardadas na TV.
            "/api/backup/automaticos" -> if (req.metodo == "GET") return json(200, JSONObject().put("copias",
                org.json.JSONArray().apply {
                    for (f in BackupAutomatico.listar(app)) put(JSONObject().put("nome", f.name).put("tamanho", f.length()).put("quando", f.lastModified()))
                }))
            "/api/backup/automatico" -> if (req.metodo == "GET") {
                val nome = req.consulta["nome"] ?: ""
                val f = BackupAutomatico.arquivo(app, nome) ?: return erro(404, "essa cópia não existe")
                PainelArmazem.registrar(app, ip, "baixar cópia automática $nome", "ok")
                return Resposta(200, "application/json; charset=utf-8", f.readBytes(),
                    mapOf("Content-Disposition" to "attachment; filename=\"launcher-$nome\""))
            }
            // Backup noturno cifrado (BackupNoturno.kt): o mesmo que a TV configura em Configurações > Backup noturno.
            "/api/backup/noturno" -> if (req.metodo == "GET") return json(200, backupNoturnoEstado())
            "/api/backup/noturno/config" -> if (req.metodo == "POST") return backupNoturnoConfig(req, token, ip)
            "/api/backup/noturno/agora" -> if (req.metodo == "POST") return backupNoturnoAgora(ip)
            "/api/backup/automatico/restaurar" -> if (req.metodo == "POST") {
                val nome = req.consulta["nome"] ?: ""
                val f = BackupAutomatico.arquivo(app, nome) ?: return erro(404, "essa cópia não existe")
                return restaurarTexto(f.readText(), token, ip, "restaurar cópia automática $nome")
            }
            // Aba Saúde (PainelSaude.kt). ?rapido=1 pula o shell (só os números que o app lê sozinho, na hora).
            // "acesso" (lote 10/11/2026): shell próprio, túnel do Claude e a última manutenção da noite (SaudeAcesso).
            // Barato (lê memória e prefs), então vai também no ?rapido=1.
            "/api/saude" -> if (req.metodo == "GET") return json(200, PainelSaude.estado(app, req.consulta["rapido"] != "1")
                .put("acesso", SaudeAcesso.json(app)))
            "/api/saude/liberar" -> if (req.metodo == "POST") {
                val ganho = PainelSaude.liberar(app)
                PainelArmazem.registrar(app, ip, "liberar memória", if (ganho == null) "sem shell" else "ok, ${ganho / 1048576} MB")
                return if (ganho == null) erro(503, "o shell do launcher não respondeu (a depuração sem fio está ligada?)")
                else json(200, JSONObject().put("ok", true).put("ganho", ganho))
            }
            // Fotos do protetor de tela (FotosProtetor.kt): pasta privada do launcher, só imagens.
            "/api/fotos" -> if (req.metodo == "GET") return json(200, JSONObject().put("fotos", org.json.JSONArray().apply {
                for (f in FotosProtetor.listar(app)) put(JSONObject().put("nome", f.name).put("tamanho", f.length()))
            }).put("limite", FotosProtetor.MAXIMO_FOTOS))
            "/api/fotos/ver" -> if (req.metodo == "GET") {
                val f = FotosProtetor.arquivo(app, req.consulta["nome"] ?: "") ?: return erro(404, "essa foto não existe")
                val tipo = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
                return Resposta(200, tipo, f.readBytes())
            }
            "/api/fotos/enviar" -> if (req.metodo == "POST") {
                val nome = req.consulta["nome"] ?: ""
                val motivo = FotosProtetor.receber(app, nome, req.tamanho, req.fluxoCorpo())
                PainelArmazem.registrar(app, ip, "enviar foto do protetor ${nome.take(80)}", motivo ?: "ok")
                return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(400, motivo)
            }
            "/api/fotos/apagar" -> if (req.metodo == "POST") {
                val nome = req.consulta["nome"] ?: ""
                val ok = FotosProtetor.apagar(app, nome)
                PainelArmazem.registrar(app, ip, "apagar foto do protetor ${nome.take(80)}", if (ok) "ok" else "não existe")
                return if (ok) json(200, JSONObject().put("ok", true)) else erro(404, "essa foto não existe")
            }
            "/api/registro" -> if (req.metodo == "GET") return json(200, JSONObject().put("itens", PainelArmazem.registro(app)))
            "/api/apps/instalar" -> if (req.metodo == "POST") return instalar(req, token, ip)
            "/api/arquivos" -> if (req.metodo == "GET") { semAcesso()?.let { return it }; return json(200, PainelArquivos.listar(req.consulta["caminho"])) }
            "/api/arquivos/baixar" -> if (req.metodo == "GET") return baixarArquivo(req, ip)
            "/api/arquivos/enviar" -> if (req.metodo == "POST") return enviarArquivo(req, token, ip)
            "/api/arquivos/pasta" -> if (req.metodo == "POST") return criarPasta(req, ip)
            "/api/arquivos/apagar" -> if (req.metodo == "POST") return apagarArquivo(req, token, ip)
            "/api/arquivos/mover" -> if (req.metodo == "POST") return moverArquivo(req, ip)
            "/api/tela/foto" -> if (req.metodo == "GET") return fotoTela()
            "/api/tela/tecla" -> if (req.metodo == "POST") return tecla(req)
            "/api/tela/texto" -> if (req.metodo == "POST") return texto(req, ip)
            "/api/tv/reiniciar" -> if (req.metodo == "POST") return reiniciarTv(token, ip)
            "/api/certificado/renovar" -> if (req.metodo == "POST") return renovarCertificado(req, token, ip)
            "/api/recado" -> if (req.metodo == "POST") return recado(req, ip)
            "/api/compartilhar" -> if (req.metodo == "POST") return compartilhar(req, ip)   // ideia 55
            "/api/rede/historico" -> if (req.metodo == "GET")
                return json(200, HistoricoRede.json(app, req.consulta["horas"]?.toIntOrNull() ?: 24))
            "/api/tv/autodesligar" -> when (req.metodo) {
                "GET" -> return autoDesligar()
                "POST" -> {
                    val min = corpoJson(req)?.optInt("minutos", -1) ?: return erro(400, "JSON inválido")
                    if (!AutoDesligar.definir(app, min)) return erro(400, "opção inválida")
                    PainelArmazem.registrar(app, ip, "desligar sozinha: " + (if (min == 0) "nunca" else "$min min"), "ok")
                    return autoDesligar()
                }
            }
            "/api/clima" -> when (req.metodo) {
                "GET" -> return json(200, estadoClima())
                "POST" -> return mudarClima(req, ip)
            }
            "/api/uso" -> if (req.metodo == "GET") return json(200, JSONObject().put("minutos", PainelControle.usoHoje(app)))
            "/api/mcp" -> if (req.metodo == "GET") return json(200, mcpEstado())
            "/api/mcp/config" -> if (req.metodo == "POST") return mcpConfig(req, token, ip)
            "/api/mcp/qr" -> if (req.metodo == "GET") {
                val texto = ConectarClaudeActivity.Passo.agora(app).qr ?: return erro(404, "sem código agora")
                return Resposta(200, "image/png", QrCodigo.png(texto))
            }
            // QR do endereço público do MCP (aba "Conectar uma IA"): abrir no celular e colar no app da IA.
            "/api/mcp/qr-endereco" -> if (req.metodo == "GET") {
                val nome = TunelTailscale.nome(app) ?: return erro(404, "o endereço ainda não existe (ligue o acesso na aba Claude)")
                return Resposta(200, "image/png", QrCodigo.png("https://$nome/mcp"))
            }
            // Foto da bolinha do perfil (FotoPerfil.kt, 13/11/2026). A conta tem de ser uma das contas Google desta TV:
            // o nome do arquivo sai dela, então nada que venha do navegador vira caminho de arquivo.
            "/api/perfil" -> if (req.metodo == "GET") return json(200, JSONObject().put("google", LoginGoogle.configurado(app))
                .put("contas", org.json.JSONArray().apply {
                    for (c in Perfil.contas(app)) put(JSONObject().put("conta", c)
                        .put("foto", FotoPerfil.arquivo(app, c)?.lastModified() ?: 0L).put("manual", FotoPerfil.manual(app, c)))
                }))
            "/api/perfil/foto" -> {
                val conta = Perfil.contas(app).firstOrNull { it.equals(req.consulta["conta"], ignoreCase = true) }
                    ?: return erro(404, "essa conta não está nesta TV")
                when (req.metodo) {
                    "GET" -> {
                        val f = FotoPerfil.arquivo(app, conta) ?: return erro(404, "essa conta está sem foto")
                        return Resposta(200, "image/jpeg", f.readBytes(), mapOf("Cache-Control" to "no-store"))
                    }
                    "POST" -> {
                        val motivo = FotoPerfil.receber(app, conta, req.tamanho, req.fluxoCorpo(), manual = true)
                        PainelArmazem.registrar(app, ip, "enviar foto do perfil", motivo ?: "ok")   // sem o e-mail no registro
                        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(400, motivo)
                    }
                }
            }
            "/api/perfil/foto/apagar" -> if (req.metodo == "POST") {
                val conta = Perfil.contas(app).firstOrNull { it.equals(req.consulta["conta"], ignoreCase = true) }
                    ?: return erro(404, "essa conta não está nesta TV")
                val ok = FotoPerfil.remover(app, conta)
                PainelArmazem.registrar(app, ip, "apagar foto do perfil", if (ok) "ok" else "não existe")
                return if (ok) json(200, JSONObject().put("ok", true)) else erro(404, "essa conta está sem foto")
            }
            // Player universal, passo 4 (06/11/2026): contas de TV ao vivo. Listar mostra só nome, tipo e servidor (nunca
            // usuário, senha nem o link inteiro, que no Xtream e em muitas listas leva a senha). Cadastrar e apagar pedem o PIN.
            "/api/tv/contas" -> when (req.metodo) {
                "GET" -> return json(200, tvContas())
                "POST" -> return tvAdicionar(req, token, ip)
            }
            "/api/tv/contas/apagar" -> if (req.metodo == "POST") return tvApagar(req, token, ip)
            "/api/tv/carregar" -> if (req.metodo == "POST") return tvCarregar(ip)
            "/api/roteador" -> if (req.metodo == "GET") return json(200, roteadorEstado())
            "/api/roteador/config" -> if (req.metodo == "POST") return roteadorConfig(req, ip)
            "/api/roteador/certificado" -> if (req.metodo == "POST") return roteadorCertificado(ip)
            "/api/roteador/confiar" -> if (req.metodo == "POST") return roteadorConfiar(req, ip)
            "/api/roteador/aparelhos" -> if (req.metodo == "GET") return roteadorAparelhos(req)
            "/api/roteador/esquecer" -> if (req.metodo == "POST") return roteadorEsquecer(ip)
            "/api/roteador/wifi-tv" -> if (req.metodo == "GET") return roteadorWifiTv()
            "/api/roteador/reconectar" -> return if (req.metodo == "POST") roteadorReconectar(ip) else json(200, estadoReconectar())
        }

        // /api/apps/<pacote>/<ação>
        val partes = req.caminho.removePrefix("/api/apps/").split('/')
        if (req.caminho.startsWith("/api/apps/") && partes.size == 2) {
            val pacote = partes[0]
            if (!ChecagensPainel.pacoteValido(pacote)) return erro(400, "pacote inválido")
            when (partes[1]) {
                "icone" -> if (req.metodo == "GET") return icone(req, pacote)
                "ocultar" -> if (req.metodo == "POST") return ocultar(req, pacote, ip, token)
                "desinstalar" -> if (req.metodo == "POST") return desinstalar(pacote, token, ip)
                "ficha" -> if (req.metodo == "GET") return PainelFicha.ficha(app, pacote)
                    ?.apply { put("espaco", PainelControle.espaco(app, pacote) ?: JSONObject.NULL) }
                    ?.let { json(200, it) } ?: erro(404, "app não encontrado")
                "abrir" -> if (req.metodo == "POST") return acaoApp(ip, "abrir", pacote) { PainelControle.abrir(app, pacote) }
                "parar" -> if (req.metodo == "POST") {
                    confirmarNaTv(Confirmacoes.Acao.REMOVER, txt(R.string.cf_parar_app, pacote), ip)?.let { return it }
                    return acaoApp(ip, "parar", pacote) { PainelControle.parar(app, pacote) }
                }
                "limpar" -> if (req.metodo == "POST") {
                    if (!sessoes.elevada(token)) return precisaPin()
                    confirmarNaTv(Confirmacoes.Acao.REMOVER, txt(R.string.cf_limpar_app, pacote), ip)?.let { return it }
                    return acaoApp(ip, "limpar dados de", pacote) { PainelControle.limparDados(app, pacote) }
                }
                "permissoes" -> if (req.metodo == "GET") return PainelPermissoes.listar(app, pacote)?.let { json(200, it) } ?: erro(404, "app não encontrado")
                "permissao" -> if (req.metodo == "POST") return mudarPermissao(req, pacote, token, ip)
                "especial" -> if (req.metodo == "POST") return mudarEspecial(req, pacote, token, ip)
            }
        }
        return erro(404, "não existe")
    }

    // ---------------------------------------------------------------- página

    private val TIPOS = mapOf(
        "/" to ("index.html" to "text/html; charset=utf-8"),
        "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
        "/style.css" to ("style.css" to "text/css; charset=utf-8"),
        "/extras.js" to ("extras.js" to "text/javascript; charset=utf-8"),
        "/ui.js" to ("ui.js" to "text/javascript; charset=utf-8"),   // casca do painel: navegação, Início, avisos
        "/busca.js" to ("busca.js" to "text/javascript; charset=utf-8"),   // busca rápida (Ctrl+K)
        "/arquivos.js" to ("arquivos.js" to "text/javascript; charset=utf-8"),   // prévia e organizar arquivos
        "/controle.js" to ("controle.js" to "text/javascript; charset=utf-8"),   // digitar ao vivo e touchpad
        "/certificado.js" to ("certificado.js" to "text/javascript; charset=utf-8"),   // cartão "Instalar como app (https)"
        // "Adicionar à tela inicial" no celular: nome, ícone e atalhos (10/11/2026).
        "/manifest.webmanifest" to ("manifest.webmanifest" to "application/manifest+json; charset=utf-8"),
        "/icone-192.png" to ("icone-192.png" to "image/png"),
        "/icone-512.png" to ("icone-512.png" to "image/png"),
        "/icone-mascaravel.png" to ("icone-mascaravel.png" to "image/png"),
        "/icone-apple.png" to ("icone-apple.png" to "image/png"),
        "/favicon.ico" to ("icone-192.png" to "image/png"),   // o navegador pede sozinho; sem isso, 404 no registro
        "/jmuxer.min.js" to ("jmuxer.min.js" to "text/javascript; charset=utf-8"),
        "/ia.js" to ("ia.js" to "text/javascript; charset=utf-8"),   // aba "Conectar uma IA" (10/11/2026)
        "/ideias.js" to ("ideias.js" to "text/javascript; charset=utf-8"),   // ideias de 12/11/2026 (aba Extras)
        "/sw.js" to ("sw.js" to "text/javascript; charset=utf-8"),   // avisos no celular (Web Push): o service worker
        // Fonte Manrope (a mesma da TV), só as letras latinas: 12 KB cada (visual de 13/11/2026).
        "/manrope-400.woff2" to ("manrope-400.woff2" to "font/woff2"),
        "/manrope-600.woff2" to ("manrope-600.woff2" to "font/woff2"),
        "/offline.html" to ("offline.html" to "text/html; charset=utf-8"),   // tela "A TV não respondeu" do app instalado (sw.js)
    )

    private fun estatico(caminho: String): Resposta? {
        val (arquivo, tipo) = TIPOS[caminho] ?: return null
        val bytes = app.assets.open("painel/$arquivo").use { it.readBytes() }
        return Resposta(200, tipo, bytes)
    }

    // ---------------------------------------------------------------- login

    // Sem "Secure" mesmo no HTTPS do Tailscale: o nome .ts.net também abre em http :8080, e um cookie Secure do mesmo
    // nome faria o navegador descartar o login por http (laço de PIN). Os dois caminhos já vão cifrados pelo Tailscale.
    private fun cookieSessao(token: String) = mapOf("Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
    private fun cookieApagado() = mapOf("Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")

    private fun estado(token: String?): Resposta {
        // existe() e não valida(): a página consulta isto sozinha a cada 30 s, e isso não pode manter a sessão
        // viva para sempre (a sessão tem de cair com 30 min sem ninguém mexer).
        val logado = sessoes.existe(token)
        val o = JSONObject().put("logado", logado)
        if (logado) {
            o.put("aparelho", Settings.Global.getString(app.contentResolver, "device_name") ?: "TV")
            o.put("naTela", PainelApps.naTela(app))
            o.put("elevada", sessoes.elevada(token))
        }
        return json(200, o)
    }

    // ---------------------------------------------------------------- eventos ao vivo (10/11/2026)

    private const val MAX_EVENTOS = 2                   // janelas do painel recebendo ao mesmo tempo; a 3ª cai no polling
    private const val EVENTOS_DURACAO_MS = 5 * 60_000L  // depois disso fecha; o navegador (EventSource) reconecta sozinho
    private const val EVENTOS_PASSO_MS = 1_500L
    private val eventosAbertos = java.util.concurrent.atomic.AtomicInteger(0)
    // Fluxo mais novo de cada sessão: abrir de novo (a página voltou a ficar visível) encerra o anterior dessa sessão,
    // em vez de esperar o ping descobrir que a conexão velha morreu e ocupar uma das [MAX_EVENTOS] vagas até lá.
    private val eventosDaSessao = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Server-Sent Events: a página recebe "o que está na TV" na hora em que muda (abriu um app, a TV dormiu), em vez
     * de perguntar a cada 30 s. Barato de propósito: a cada 1,5 s só olha se a tela está acesa e os eventos de uso dos
     * últimos segundos (não as 6 h do [PainelApps.naTela]); só escreve quando algo mudou, mais um "ping" a cada 5 s.
     * Como o /api/estado, usa existe() e não valida(): ficar com a página aberta não segura a sessão viva para sempre;
     * quando ela vence, o fluxo fecha e a página vai para o PIN no próximo pedido.
     */
    private fun eventos(token: String?): Resposta {
        if (token == null || !sessoes.existe(token)) return erro(401, "entre com o PIN")
        val meu = Any()
        eventosDaSessao[token] = meu   // o fluxo antigo desta sessão vê que não é mais o dela e sai no próximo passo
        if (eventosAbertos.get() >= MAX_EVENTOS) Thread.sleep(EVENTOS_PASSO_MS + 200)   // dá tempo do antigo sair
        if (eventosAbertos.incrementAndGet() > MAX_EVENTOS) {
            eventosAbertos.decrementAndGet()
            return erro(503, "muitas janelas do painel abertas; esta atualiza a cada 30 s")
        }
        return Resposta(200, null, ByteArray(0), fluxo = { saida ->
            try { transmitirEventos(token, saida) { eventosDaSessao[token] === meu } }
            finally { eventosAbertos.decrementAndGet(); eventosDaSessao.remove(token, meu) }
        })
    }

    private fun transmitirEventos(token: String?, saida: java.io.OutputStream, atual: () -> Boolean) {
        val cab = StringBuilder("HTTP/1.1 200 OK\r\n")
        for ((k, v) in PainelHttp.CABECALHOS_SEGURANCA) cab.append(k).append(": ").append(v).append("\r\n")
        cab.append("Content-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n")
        saida.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
        // Reconectar depois de 3 s se cair (padrão do navegador, explícito).
        saida.write("retry: 3000\n\n".toByteArray()); saida.flush()

        val pm = app.getSystemService(android.os.PowerManager::class.java)
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val inicio = System.currentTimeMillis()
        var desde = inicio
        var naTela = PainelApps.naTela(app)
        var enviado: String? = null
        var ultimaEscrita = inicio
        while (true) {
            val agora = System.currentTimeMillis()
            if (agora - inicio > EVENTOS_DURACAO_MS || !ligado() || !sessoes.existe(token) || !atual()) break
            if (pm != null && !pm.isInteractive) naTela = "em espera"
            else {
                var ultimo: String? = null
                try {
                    // 3 s de sobra para trás: o Android às vezes grava o evento com um pouco de atraso.
                    val ev = usm.queryEvents(desde - 3_000, agora)
                    val e = android.app.usage.UsageEvents.Event()
                    while (ev.hasNextEvent()) {
                        ev.getNextEvent(e)
                        if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) ultimo = e.packageName
                    }
                } catch (_: Exception) {}
                naTela = when {
                    ultimo != null -> PainelApps.rotuloNaTela(app, ultimo)
                    naTela == "em espera" -> PainelApps.naTela(app)   // acordou sem abrir nada novo: recalcula inteiro
                    else -> naTela
                }
            }
            desde = agora
            val json = JSONObject().put("naTela", naTela).toString()
            if (json != enviado) {
                saida.write("event: estado\ndata: $json\n\n".toByteArray()); saida.flush()
                enviado = json; ultimaEscrita = agora
            } else if (agora - ultimaEscrita > 5_000) {
                saida.write(": ping\n\n".toByteArray()); saida.flush()   // a cada 5 s: descobre logo a conexão morta (a vaga volta)
                ultimaEscrita = agora
            }
            try { Thread.sleep(EVENTOS_PASSO_MS) } catch (_: InterruptedException) { break }
        }
    }

    /**
     * Confere o PIN com o contador global. Devolve null se acertou, ou a resposta de erro pronta (PIN errado,
     * bloqueado, painel desligado por excesso de erros).
     */
    private fun conferirPin(req: Requisicao, ip: String, acao: String): Resposta? {
        val pin = try { JSONObject(req.corpoTexto()).optString("pin") } catch (e: Exception) { "" }
        synchronized(travaPin) {
            val ctl = controle()
            // Painel desligado (por erros ou nas Configurações) ou contador esgotado: não confere mais nada, nem os
            // pedidos que já estavam na fila quando a porta fechou.
            if (ctl.esgotado() || !PainelArmazem.podeLigar(app)) return erro(403, "painel desligado. Religue nas Configurações do launcher, na TV.")
            val espera = ctl.esperaMs()
            if (espera > 0) return erro(429, "muitas tentativas erradas; espere ${(espera + 999) / 1000} s") { put("esperarSeg", (espera + 999) / 1000) }
            if (PainelArmazem.conferirPin(app, pin)) { ctl.registrarSucesso(); return null }
            PainelArmazem.registrar(app, ip, acao, "PIN errado")
            return when (val r = ctl.registrarFalha()) {
                is ControleTentativas.Resultado.Errou -> erro(401, "PIN errado") { put("restantes", r.restantes) }
                is ControleTentativas.Resultado.Bloqueou -> erro(429, "PIN errado; bloqueado por ${r.ms / 60_000} min") { put("esperarSeg", r.ms / 1000) }
                ControleTentativas.Resultado.Desligar -> { desligarPorErros(ip); erro(403, "PIN errado demais: o painel foi desligado. Religue nas Configurações do launcher, na TV.") }
            }
        }
    }

    private fun desligarPorErros(ip: String) {
        PainelArmazem.definirDesligadoPorErros(app, true)
        PainelArmazem.registrar(app, ip, "painel", "desligado por PIN errado demais")
        Log.w(TAG, "PIN errado ${ControleTentativas.LIMITE_DESLIGAR} vezes: painel desligado")
        // Fecha a porta logo depois de responder (a conexão atual continua aberta até a resposta sair).
        Handler(Looper.getMainLooper()).post {
            ajustar(app)
            try { Toast.makeText(app, "Painel no navegador desligado: PIN errado muitas vezes", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun entrar(req: Requisicao, ip: String): Resposta {
        conferirPin(req, ip, "entrar")?.let { return it }
        if (!ligado()) return erro(503, "painel desligado")
        val token = sessoes.criar()
        PainelArmazem.registrar(app, ip, "entrar", "ok")
        return json(200, JSONObject().put("ok", true), cookieSessao(token))
    }

    /**
     * Entrar com o código do QR da TV, sem PIN. A sessão nasce SEM elevação: o que pede o PIN de novo (desinstalar,
     * instalar, backup...) continua pedindo. Código errado não conta como tentativa de PIN (192 bits: não se adivinha).
     */
    private fun entrarPeloQr(req: Requisicao, ip: String): Resposta {
        if (!ligado() || !PainelArmazem.podeLigar(app)) return erro(503, "painel desligado")
        val codigo = try { JSONObject(req.corpoTexto()).optString("codigo") } catch (e: Exception) { "" }
        if (!codigoEntrada.usar(codigo)) return erro(403, "código vencido ou já usado: gere outro na TV")
        val token = sessoes.criar(elevada = false)
        PainelArmazem.registrar(app, ip, "entrar pelo QR da TV", "ok")
        return json(200, JSONObject().put("ok", true), cookieSessao(token))
    }

    /** PIN redigitado para uma ação sem volta: a sessão fica "elevada" por 5 min. */
    private fun confirmar(req: Requisicao, token: String?, ip: String): Resposta {
        conferirPin(req, ip, "confirmar PIN")?.let { return it }
        sessoes.elevar(token)
        return json(200, JSONObject().put("ok", true))
    }

    private fun precisaPin() = erro(403, "confirme o PIN") { put("precisaPin", true) }

    /**
     * Confirmações (ConfirmacoesTv): com o modo "Perguntar na TV", mostra a pergunta e espera até 60 s (esta requisição
     * fica parada, numa thread do servidor, não na da tela). Null = pode seguir; senão, a resposta de recusa pronta.
     */
    private fun confirmarNaTv(a: Confirmacoes.Acao, oQue: String, ip: String): Resposta? =
        if (ConfirmacoesTv.permitir(app, a, oQue, txt(R.string.cf_quem_painel, ip))) null
        else erro(403, if (ConfirmacoesTv.modo(app, a) == Confirmacoes.Modo.BLOQUEAR)
            "bloqueado na TV (Configurações › Sistema › Confirmações)" else "negado na TV, ou ninguém respondeu em 60 s") { put("negadoNaTv", true) }

    // ---------------------------------------------------------------- apps

    private fun icone(req: Requisicao, pacote: String): Resposta {
        val (png, etiqueta) = PainelApps.icone(app, pacote) ?: return erro(404, "app não encontrado")
        // O ícone pode ficar no cache do navegador (privado, 1 dia); a etiqueta muda quando o app atualiza.
        val extras = mapOf("Cache-Control" to "private, max-age=86400", "ETag" to etiqueta)
        if (req.cabecalho("if-none-match") == etiqueta) return Resposta(304, null, ByteArray(0), extras)
        return Resposta(200, "image/png", png, extras)
    }

    private fun ocultar(req: Requisicao, pacote: String, ip: String, token: String?): Resposta {
        val oculto = try { JSONObject(req.corpoTexto()).getBoolean("oculto") } catch (e: Exception) { return erro(400, "falta \"oculto\"") }
        // Reexibir pede o PIN de novo (10/11/2026): uma sessão comum (ex.: entrou pelo QR da TV, talvez uma criança) não
        // desfaz o que o dono escondeu. Ocultar continua livre: só deixa a TV mais fechada.
        if (!oculto && !sessoes.elevada(token)) return precisaPin()
        val motivo = PainelApps.ocultar(app, pacote, oculto)
        PainelArmazem.registrar(app, ip, (if (oculto) "ocultar " else "reexibir ") + pacote, motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun desinstalar(pacote: String, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        confirmarNaTv(Confirmacoes.Acao.REMOVER, txt(R.string.cf_desinstalar_app, pacote), ip)?.let { return it }
        val motivo = PainelApps.desinstalar(app, pacote)
        PainelArmazem.registrar(app, ip, "desinstalar $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    // ---------------------------------------------------------------- backup

    private fun restaurar(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        return restaurarTexto(req.corpoTexto(), token, ip, "restaurar backup")
    }

    private fun backupCompleto(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val senha = corpoJson(req)?.optString("senha") ?: return erro(400, "JSON inválido")
        if (!CofreBackup.senhaValida(senha)) return erro(400, "a senha do backup precisa ter de 8 a 128 caracteres")
        val texto = try { BackupCompleto.exportar(app, senha) } catch (e: Exception) {
            PainelArmazem.registrar(app, ip, "baixar backup completo", "falhou: ${e.javaClass.simpleName}")
            return erro(500, "não consegui montar o backup (${e.javaClass.simpleName})")
        }
        PainelArmazem.registrar(app, ip, "baixar backup completo", "ok")
        return Resposta(200, "application/json; charset=utf-8", texto.toByteArray(),
            mapOf("Content-Disposition" to "attachment; filename=\"launcher-backup-completo.json\""))
    }

    private fun restaurarCompleto(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val senha = o.optString("senha"); val arquivo = o.optString("arquivo")
        return try {
            restaurarTexto(arquivo, token, ip, "restaurar backup completo") { BackupCompleto.importar(app, it, senha) }
        } catch (e: CofreBackup.SenhaErrada) {
            PainelArmazem.registrar(app, ip, "restaurar backup completo", "senha errada")
            erro(400, "senha do backup errada (ou o arquivo foi alterado)")
        }
    }

    // ---------------------------------------------------------------- backup noturno (BackupNoturno.kt)

    /**
     * Estado para a aba Backup. A senha NUNCA volta (só "temSenha"). As pastas de rede vêm com id e nome; ler as contas
     * decifra pelo Keystore, por isso fica aqui (thread do servidor) e não na tela da TV.
     */
    private fun backupNoturnoEstado(): JSONObject {
        val destino = BackupNoturno.destino(app)
        val nas = JSONArray().apply {
            for (c in runCatching { BackupNoturno.contasSmb(app) }.getOrDefault(emptyList()))
                put(JSONObject().put("valor", BackupNoturnoRegras.destinoSmb(c.id)).put("nome", c.nome))
        }
        val copias = JSONArray().apply {
            for (f in BackupNoturno.listar(app)) put(JSONObject().put("nome", f.name).put("tamanho", f.length()).put("quando", f.lastModified()))
        }
        return JSONObject()
            .put("ligado", BackupNoturno.ligado(app))
            .put("temSenha", BackupNoturno.temSenha(app))
            .put("minutoDoDia", BackupNoturno.minutoDoDia(app))
            .put("destino", destino)
            .put("destinoRotulo", BackupNoturno.rotuloDestino(app, destino))
            .put("nas", nas)
            .put("drive", DriveBackup.email(app) ?: "")   // conta do Google Drive conectada na TV (só o e-mail)
            .put("ultimo", BackupNoturno.ultimo(app))
            .put("copias", copias)
    }

    /**
     * Muda a configuração do backup noturno. Cada campo é opcional: {"ligado":true}, {"minutoDoDia":210},
     * {"destino":"pendrive"}, {"senha":"..."}. Senha e destino pedem o PIN de novo (sessão elevada), como o backup
     * completo: quem troca a senha decide quem abre as cópias; quem troca o destino decide para onde elas vão.
     */
    private fun backupNoturnoConfig(req: Requisicao, token: String?, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        if ((o.has("senha") || o.has("destino")) && !sessoes.elevada(token)) return precisaPin()
        // Confere TUDO antes de gravar qualquer coisa: um campo ruim não deixa a configuração pela metade.
        val minuto = if (o.has("minutoDoDia")) o.optInt("minutoDoDia", -1).takeIf { it in 0 until 24 * 60 }
            ?: return erro(400, "horário inválido") else null
        var destinoNome = ""
        val destino = if (o.has("destino")) {
            val d = o.optString("destino")
            when {
                d == BackupNoturnoRegras.SO_TV || d == BackupNoturnoRegras.PENDRIVE -> d
                // Google Drive: só com a conta já conectada NA TV (o login é pelo código no celular, na tela da TV).
                d == BackupNoturnoRegras.DRIVE -> if (DriveBackup.conectado(app)) d
                    else return erro(400, "conecte a conta do Google Drive na TV primeiro (Configurações > Backup automático)")
                // NAS: só uma pasta SMB que já está cadastrada em "Mídias na rede" (nada de endereço digitado aqui).
                else -> {
                    val conta = BackupNoturnoRegras.idSmb(d)?.let { id ->
                        runCatching { BackupNoturno.contasSmb(app) }.getOrDefault(emptyList()).firstOrNull { it.id == id }
                    } ?: return erro(400, "destino desconhecido")
                    destinoNome = conta.nome
                    d
                }
            }
        } else null
        val senha = if (o.has("senha")) o.optString("senha").takeIf { CofreBackup.senhaValida(it) }
            ?: return erro(400, "a senha do backup precisa ter de 8 a 128 caracteres") else null
        if (senha != null) {
            if (!BackupNoturno.definirSenha(app, senha)) return erro(500, "não consegui guardar a senha na TV")
            PainelArmazem.registrar(app, ip, "backup noturno: senha nova", "ok")   // nunca a senha no registro
        }
        if (destino != null) {
            BackupNoturno.definirDestino(app, destino, destinoNome)
            PainelArmazem.registrar(app, ip, "backup noturno: cópia extra", BackupNoturno.rotuloDestino(app, destino))
        }
        if (minuto != null) BackupNoturno.definirMinutoDoDia(app, minuto)
        if (o.has("ligado")) {
            val sim = o.optBoolean("ligado")
            if (!BackupNoturno.definirLigado(app, sim)) return erro(400, "defina a senha antes de ligar")
            PainelArmazem.registrar(app, ip, "backup noturno", if (sim) "ligado" else "desligado")
        }
        return json(200, backupNoturnoEstado())
    }

    /**
     * "Fazer agora": roda já e espera até 60 s pelo resultado (o backup é pequeno; a cópia para o NAS é o que demora).
     * Passou disso, solta a thread do servidor, responde "rodando" e o resultado aparece depois em "último".
     */
    private fun backupNoturnoAgora(ip: String): Resposta {
        if (!BackupNoturno.temSenha(app)) return erro(400, "defina a senha antes")
        val fila = ArrayBlockingQueue<String>(1)
        BackupNoturno.fazerAgora(app) { r -> fila.offer(r) }
        val r = fila.poll(60, java.util.concurrent.TimeUnit.SECONDS)
        PainelArmazem.registrar(app, ip, "backup noturno: fazer agora", r ?: "rodando")
        return json(200, backupNoturnoEstado().put("resultado", r ?: JSONObject.NULL))
    }

    /** Restaura um backup já lido (do arquivo enviado ou de uma cópia automática). [acao] vai para o registro. */
    private fun restaurarTexto(texto: String, token: String?, ip: String, acao: String,
                               importar: (String) -> Int = { BackupConfig.importar(app, it) }): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val n = try {
            importar(texto)
        } catch (e: CofreBackup.SenhaErrada) {
            throw e
        } catch (e: IllegalArgumentException) {
            PainelArmazem.registrar(app, ip, acao, "recusado: ${e.message}")
            return erro(400, "backup recusado: ${e.message}")
        } catch (e: Exception) {
            PainelArmazem.registrar(app, ip, acao, "falhou: $e")
            return erro(500, "a restauração falhou na TV (${e.javaClass.simpleName}); as configurações de antes foram mantidas")
        }
        PainelArmazem.registrar(app, ip, acao, "ok, $n chaves")
        // Reinicia o launcher depois de a resposta sair (várias telas leem as preferências só ao abrir). É o mesmo
        // jeito do REINICIAR do adb. O sistema sobe o processo de novo para religar o serviço de acessibilidade, e é
        // ele (AutorizaShizukuService.onServiceConnected) que reabre o painel mesmo sem a HOME na frente. As sessões caem.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1500)
        return json(200, JSONObject().put("ok", true).put("chaves", n).put("reiniciando", true))
    }

    // ---------------------------------------------------------------- fase 2: permissões

    private fun corpoJson(req: Requisicao): JSONObject? = try { JSONObject(req.corpoTexto()) } catch (e: Exception) { null }

    private fun mudarPermissao(req: Requisicao, pacote: String, token: String?, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val permissao = o.optString("permissao")
        val acao = o.optString("acao")
        // CONCEDER pede o PIN de novo (revisão 09/11/2026), como o Shizuku: um cookie roubado não pode dar câmera,
        // microfone ou localização a um app. Negar e voltar ao padrão continuam livres (só tiram poder).
        if (acao == "conceder" && !sessoes.elevada(token)) return precisaPin()
        if (permissao.length > 200 || !ChecagensPainel.pacoteValido(permissao)) return erro(400, "permissão inválida")
        // Tirar permissão nunca pergunta (fica mais seguro); só dar.
        if (acao == "conceder") confirmarNaTv(Confirmacoes.Acao.PERMISSOES,
            txt(R.string.cf_dar_permissao, permissao.substringAfterLast('.'), pacote), ip)?.let { return it }
        val motivo = PainelPermissoes.mudar(app, pacote, permissao, acao)
        PainelArmazem.registrar(app, ip, "permissão $acao ${permissao.substringAfterLast('.')} em $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun mudarEspecial(req: Requisicao, pacote: String, token: String?, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val op = o.optString("op")
        val acao = o.optString("acao")
        // PERMITIR pede o PIN de novo (revisão 09/11/2026): "instalar outros apps", "todos os arquivos" e "por cima de
        // outros apps" são poderes grandes. Negar e padrão continuam livres.
        if (acao == "permitir" && !sessoes.elevada(token)) return precisaPin()
        if (acao == "permitir") confirmarNaTv(Confirmacoes.Acao.PERMISSOES, txt(R.string.cf_dar_acesso, op, pacote), ip)?.let { return it }
        val motivo = PainelPermissoes.mudarEspecial(app, pacote, op, acao)
        PainelArmazem.registrar(app, ip, "acesso $acao $op em $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    // ---------------------------------------------------------------- fase 2: instalar APK

    private fun instalar(req: Requisicao, token: String?, ip: String): Resposta {
        // Instalar é tão sem volta quanto desinstalar (o app novo pode pedir tudo): PIN de novo, ANTES de ler o arquivo.
        if (!sessoes.elevada(token)) return precisaPin()
        if (req.tamanho <= 0 || req.tamanho > PainelApps.LIMITE_APK) return erro(413, "APK vazio ou grande demais")
        // Pergunta antes de ler o arquivo (o nome é o que o navegador mandou; o pacote de verdade vai para o registro).
        confirmarNaTv(Confirmacoes.Acao.INSTALAR, txt(R.string.cf_instalar, req.consulta["nome"]?.take(80) ?: "APK"), ip)?.let { return it }
        val r = PainelApps.instalar(app, req.fluxoCorpo(), req.tamanho)
        val quem = r.pacote?.let { "${r.nome} ($it ${r.versao})" } ?: (req.consulta["nome"]?.take(80) ?: "APK")
        PainelArmazem.registrar(app, ip, "instalar $quem", r.motivo ?: "ok")
        if (r.motivo != null) return erro(409, r.motivo)
        return json(200, JSONObject().put("ok", true).put("pacote", r.pacote).put("nome", r.nome).put("versao", r.versao))
    }

    // ---------------------------------------------------------------- fase 2: arquivos

    /** Sem o "acesso a todos os arquivos", tenta se conceder pelo shell; se não der, a resposta de erro pronta. */
    private fun semAcesso(): Resposta? =
        if (PainelArquivos.garantirAcesso(app)) null
        else erro(503, "o launcher ainda não tem acesso à memória interna, e o shell dele não respondeu para conceder. " +
            "Confira se a depuração sem fio está ligada na TV e tente de novo.")

    private fun baixarArquivo(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val f = PainelArquivos.paraBaixar(req.consulta["caminho"])
        if (req.consulta["ver"] == "1") {
            // Prévia dentro do painel: só tipos da lista fechada, e com CSP "sandbox" por cima (mesmo que um arquivo
            // mentisse a extensão, o navegador o trataria como de outra origem, sem cookie nem script).
            val tipo = PainelHttp.tipoPrevia(f.name) ?: return erro(415, "esse tipo de arquivo não tem prévia: baixe para abrir")
            // Registra a prévia como um download (é o arquivo saindo da TV), uma vez: os pedaços seguintes de um vídeo
            // (Range que não começa no 0) não enchem o registro.
            val range = req.cabecalho("range")
            if (range == null || range.replace(" ", "").startsWith("bytes=0-")) PainelArmazem.registrar(app, ip, "ver ${f.name.take(80)}", "ok")
            return Resposta(200, tipo, ByteArray(0), mapOf(
                "Content-Disposition" to PainelHttp.nomeParaBaixar(f.name).replaceFirst("attachment", "inline"),
                "Content-Security-Policy" to "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'"),
                arquivo = f, range = range)
        }
        PainelArmazem.registrar(app, ip, "baixar ${f.name.take(80)}", "ok")
        return Resposta(200, "application/octet-stream", ByteArray(0),
            mapOf("Content-Disposition" to PainelHttp.nomeParaBaixar(f.name)), arquivo = f)
    }

    private fun enviarArquivo(req: Requisicao, token: String?, ip: String): Resposta {
        // Substituir apaga o arquivo antigo: pede o PIN de novo, como apagar (revisão 09/11/2026). Antes de ler o corpo.
        if (req.consulta["substituir"] == "1" && !sessoes.elevada(token)) return precisaPin()
        semAcesso()?.let { return it }
        val nome = req.consulta["nome"]
        val f = PainelArquivos.receber(app, req.consulta["pasta"], nome, req.tamanho,
            req.consulta["substituir"] == "1", req.fluxoCorpo())
        PainelArmazem.registrar(app, ip, "enviar ${f.name.take(80)} (${req.tamanho / 1024} KB)", "ok")
        return json(200, JSONObject().put("ok", true).put("livre", PainelArquivos.livre()))
    }

    private fun criarPasta(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        PainelArquivos.criarPasta(o.optString("pasta"), o.optString("nome"))
        PainelArmazem.registrar(app, ip, "nova pasta ${o.optString("nome").take(80)}", "ok")
        return json(200, JSONObject().put("ok", true))
    }

    /** Renomear e mover são a mesma coisa (um rename): não apagam nada, então não pedem o PIN de novo. */
    private fun moverArquivo(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val de = o.optString("caminho")
        val novo = PainelArquivos.mover(app, de, o.optString("pasta"), o.optString("nome"))
        PainelArmazem.registrar(app, ip, "mover ${de.take(100)} -> ${novo.take(100)}", "ok")
        return json(200, JSONObject().put("ok", true).put("caminho", novo))
    }

    private fun apagarArquivo(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val caminho = o.optString("caminho")
        val n = PainelArquivos.apagar(app, caminho)
        PainelArmazem.registrar(app, ip, "apagar ${caminho.take(120)}", "ok, $n item(ns)")
        return json(200, JSONObject().put("ok", true).put("apagados", n))
    }

    // ---------------------------------------------------------------- TV ao vivo (player universal)

    private fun tvContas(): JSONObject {
        val lista = JSONArray()
        for (f in PlayerArmazem.fontes(app)) lista.put(JSONObject().put("id", f.id).put("nome", f.nome).put("tipo", f.tipo)
            .put("servidor", f.servidorVisivel).put("temGuia", f.guia.isNotEmpty()).put("temGuiaExtra", f.guiaExtra.isNotEmpty()).put("reservas", f.reservas.size))
        return JSONObject().put("contas", lista).put("maximo", 8)
    }

    private fun tvAdicionar(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val r = PlayerArmazem.adicionar(app, o.optString("nome"), o.optString("tipo"), o.optString("endereco"),
            o.optString("usuario"), o.optString("senha"), o.optString("guia"), o.optString("reservas").take(600), o.optString("guiaExtra"))
        val f = r.getOrElse {
            PainelArmazem.registrar(app, ip, "TV: cadastrar conta", "recusada")
            return erro(400, it.message ?: "dados inválidos")
        }
        // No registro vai só o servidor visível (sem usuário, senha nem caminho do link).
        PainelArmazem.registrar(app, ip, "TV: cadastrar conta ${f.nome} (${f.servidorVisivel})", "ok")
        return json(200, JSONObject().put("ok", true).put("id", f.id))
    }

    private fun tvApagar(req: Requisicao, token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val id = o.optString("id")
        val nome = PlayerArmazem.fontes(app).firstOrNull { it.id == id }?.nome ?: return erro(404, "conta não encontrada")
        if (!PlayerArmazem.apagar(app, id)) return erro(500, "não consegui apagar")
        PainelArmazem.registrar(app, ip, "TV: apagar conta $nome", "ok")
        return json(200, JSONObject().put("ok", true))
    }

    /** Baixa de novo a lista (e o guia) de todas as contas e conta o que veio: é o teste de que a conta funciona. */
    private fun tvCarregar(ip: String): Resposta {
        val inicio = System.currentTimeMillis()
        val porConta = JSONArray()
        for (f in PlayerArmazem.fontes(app)) {
            porConta.put(JSONObject().put("id", f.id).put("canais", PlayerCarga.canaisDaFonte(app, f, forcar = true).size))
        }
        val canais = PlayerCarga.canais(app)
        val guia = PlayerCarga.guia(app, canais, forcar = true)
        // Uma consulta só ao guia (quais canais têm programa agora) e solta o banco logo: antes era uma consulta por canal.
        val comGuia = guia.comProgramaAgora(System.currentTimeMillis()).let { no -> canais.count { it.guiaId in no } }.also { guia.fechar() }
        PainelArmazem.registrar(app, ip, "TV: recarregar contas", "ok")
        return json(200, JSONObject().put("contas", porConta).put("canais", canais.size).put("comGuia", comGuia)
            .put("ms", System.currentTimeMillis() - inicio))
    }

    // ---------------------------------------------------------------- bloco C: tela, controle, ações de app

    private fun acaoApp(ip: String, acao: String, pacote: String, fazer: () -> String?): Resposta {
        val motivo = fazer()
        PainelArmazem.registrar(app, ip, "$acao $pacote", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun fotoTela(): Resposta {
        // TV em espera: a tela está apagada e o Android recusa a foto. Responde já, com o motivo claro, sem acordar nada.
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return erro(409, "A TV está em espera. Toque em Acordar para ver a tela.")
        val (jpg, motivo) = PainelControle.foto()
        return if (jpg != null) Resposta(200, "image/jpeg", jpg) else erro(503, motivo ?: "sem foto")
    }

    private fun tecla(req: Requisicao): Resposta {
        val nome = corpoJson(req)?.optString("tecla") ?: return erro(400, "JSON inválido")
        val motivo = PainelControle.tecla(app, nome)
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    private fun texto(req: Requisicao, ip: String): Resposta {
        val t = corpoJson(req)?.optString("texto") ?: return erro(400, "JSON inválido")
        val motivo = PainelControle.digitar(app, t)
        // O texto em si não vai para o registro (pode ser uma senha digitada pelo painel).
        PainelArmazem.registrar(app, ip, "digitar ${t.length} caractere(s)", motivo ?: "ok")
        return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
    }

    /** Ideia 55: o que veio do "compartilhar" do celular ({titulo, texto, url}); decide em Compartilhar.kt. */
    private fun compartilhar(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val d = Compartilhar.decidir(o.optString("titulo"), o.optString("texto"), o.optString("url"))
            ?: return erro(400, "nada para mandar à TV")
        PainelArmazem.registrar(app, ip, "compartilhado: ${d.tipo.name.lowercase()}", "")
        return when (d.tipo) {
            Compartilhar.Tipo.RECADO -> {
                val r = AvisoTv.mostrar(app, "Recado", d.valor, 60, som = true, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
                if (r == AvisoTv.Resultado.MOSTRADO || r == AvisoTv.Resultado.GUARDADO) json(200, JSONObject().put("ok", true).put("feito", "recado"))
                else erro(409, "a acessibilidade do launcher está desligada na TV: sem ela o recado não aparece")
            }
            else -> PainelControle.abrirLink(app, d.valor)?.let { erro(409, it) }
                ?: json(200, JSONObject().put("ok", true).put("feito", if (d.tipo == Compartilhar.Tipo.YOUTUBE) "youtube" else "link"))
        }
    }

    /** Recado na TV (05/11/2026): {texto, segundos, som}. Ver Recado.kt e AvisoTv.kt. */
    private fun recado(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val texto = Recado.limpar(o.optString("texto"))
            ?: return erro(400, "escreva o recado (até ${Recado.MAX_CARACTERES} caracteres)")
        val segundos = Recado.duracao(if (o.has("segundos")) o.optInt("segundos") else null)
        val r = AvisoTv.mostrar(app, "Recado", texto, segundos, som = o.optBoolean("som", true), guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
        // O texto não vai para o registro, só o tamanho (mesma regra do "digitar"): o registro é para ver o que mexeu na TV.
        PainelArmazem.registrar(app, ip, "recado de ${texto.length} caractere(s)", r.name.lowercase())
        return when (r) {
            AvisoTv.Resultado.MOSTRADO -> json(200, JSONObject().put("ok", true).put("situacao", "na tela"))
            AvisoTv.Resultado.GUARDADO -> json(200, JSONObject().put("ok", true).put("situacao", "guardado"))
            else -> erro(409, "a acessibilidade do launcher está desligada na TV: sem ela o recado não aparece")
        }
    }
    private fun autoDesligar() = json(200, JSONObject()
        .put("minutos", AutoDesligar.minutos(app))
        .put("opcoes", org.json.JSONArray(AutoDesligar.OPCOES))
        .put("ultimoDesligamento", AutoDesligar.ultimoDesligamento))

    /** O endereço do clima (ou null) e o ponto que a última busca usou. */
    private fun estadoClima(): JSONObject {
        val fixo = LocalClima.ler(app)
        val c = app.getSharedPreferences("clima", Context.MODE_PRIVATE)
        return JSONObject()
            .put("fixo", fixo?.let { JSONObject().put("nome", it.nome).put("cep", it.cep).put("numero", it.numero).put("descricao", LocalClima.descrever(it)) } ?: JSONObject.NULL)
            .put("usado", if (c.contains("lat")) JSONObject().put("lat", c.getFloat("lat", 0f).toDouble()).put("lon", c.getFloat("lon", 0f).toDouble()) else JSONObject.NULL)
    }

    /** {"cep","numero"} cadastra o endereço; {"automatico":true} apaga. */
    private fun mudarClima(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        when {
            o.optBoolean("automatico", false) -> {
                LocalClima.limpar(app)
                PainelArmazem.registrar(app, ip, "clima: localização automática", "ok")
            }
            else -> {
                // CEP + número -> ViaCEP + Nominatim (alguns segundos; esta thread é do pedido, não da tela).
                val lugar = LocalClima.definirPorCep(app, o.optString("cep"), o.optString("numero"))
                    .getOrElse { return erro(400, it.message ?: "não achei esse endereço") }
                // O endereço não vai para o registro (é a casa), só o bairro.
                PainelArmazem.registrar(app, ip, "clima: endereço em \"${lugar.nome}\"", "ok")
            }
        }
        return json(200, estadoClima().put("ok", true))
    }

    private fun reiniciarTv(token: String?, ip: String): Resposta {
        if (!sessoes.elevada(token)) return precisaPin()
        PainelArmazem.registrar(app, ip, "reiniciar a TV", "pedido")
        // Depois da resposta sair: senão o navegador fica sem saber se deu certo.
        Handler(Looper.getMainLooper()).postDelayed({ PainelControle.reiniciarTv(app) }, 1500)
        return json(200, JSONObject().put("ok", true))
    }

    // ---------------------------------------------------------------- roteador UniFi (só leitura; ver UnifiCliente)

    /** Certificado lido no passo 1 e ainda não confirmado: (endereço, impressão). Só ele pode ser confirmado. */
    @Volatile private var certificadoPendente: Pair<String, String>? = null

    /** O que o painel mostra. A chave NUNCA vem inteira: só os 4 últimos caracteres. */
    private fun roteadorEstado(): JSONObject = JSONObject()
        .put("ligado", UnifiArmazem.ligado(app))
        .put("endereco", UnifiArmazem.endereco(app) ?: JSONObject.NULL)
        .put("chave", UnifiArmazem.finalDaChave(app) ?: JSONObject.NULL)
        .put("certificado", UnifiArmazem.certificado(app) ?: JSONObject.NULL)
        .put("avisoNovos", UnifiArmazem.avisoNovos(app))
        .put("conhecidos", UnifiArmazem.conhecidos(app).size)
        .put("pronto", UnifiArmazem.pronto(app))

    /** Campos opcionais: endereco, chave, ligado, avisoNovos. Trocar o endereço apaga chave e certificado (ver UnifiArmazem). */
    private fun roteadorConfig(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val mudou = ArrayList<String>()
        if (o.has("endereco")) {
            val e = Unifi.enderecoValido(o.optString("endereco"))
                ?: return erro(400, "endereço inválido: use o IP do roteador na rede de casa (ex.: 192.168.0.1)")
            if (e != UnifiArmazem.endereco(app)) { UnifiArmazem.definirEndereco(app, e); certificadoPendente = null; mudou.add("endereço") }
        }
        if (o.has("chave")) {
            val bruta = o.optString("chave")
            if (bruta.isEmpty()) { UnifiArmazem.definirChave(app, null); mudou.add("chave apagada") } else {
                val c = Unifi.chaveValida(bruta) ?: return erro(400, "chave inválida (cole a chave inteira criada em Integrações do UniFi)")
                if (UnifiArmazem.endereco(app) == null) return erro(400, "salve o endereço do roteador antes da chave")
                if (!UnifiArmazem.definirChave(app, c)) return erro(500, "não consegui guardar a chave cifrada")
                mudou.add("chave")
            }
        }
        if (o.has("ligado")) { UnifiArmazem.definirLigado(app, o.optBoolean("ligado")); mudou.add("ligado=${o.optBoolean("ligado")}") }
        if (o.has("avisoNovos")) { UnifiArmazem.definirAvisoNovos(app, o.optBoolean("avisoNovos")); mudou.add("aviso=${o.optBoolean("avisoNovos")}") }
        UnifiCliente.limparCache()
        if (mudou.isNotEmpty()) PainelArmazem.registrar(app, ip, "roteador: ${mudou.joinToString(", ")}", "ok")
        return json(200, roteadorEstado())
    }

    /** Passo 1 do certificado: lê do roteador SEM mandar a chave e devolve a impressão para o dono conferir. */
    private fun roteadorCertificado(ip: String): Resposta {
        val endereco = UnifiArmazem.endereco(app) ?: return erro(400, "salve o endereço do roteador primeiro")
        val c = try { UnifiCliente.buscarCertificado(app, endereco) } catch (e: UnifiCliente.Falha) { return erro(502, e.message ?: "falhou") }
        certificadoPendente = endereco to c.impressao
        PainelArmazem.registrar(app, ip, "roteador: ler certificado", "ok")
        return json(200, JSONObject().put("impressao", c.impressao).put("emitidoPara", c.emitidoPara).put("validoAte", c.validoAte)
            .put("igualAoConfirmado", c.impressao == UnifiArmazem.certificado(app)))
    }

    /** Passo 2: o dono confirma a impressão que viu. Só vale a lida agora há pouco, do endereço atual. */
    private fun roteadorConfiar(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val pendente = certificadoPendente
        if (pendente == null || pendente.first != UnifiArmazem.endereco(app) || pendente.second != o.optString("impressao"))
            return erro(409, "leia o certificado de novo antes de confirmar")
        UnifiArmazem.definirCertificado(app, pendente.second)
        certificadoPendente = null
        UnifiCliente.limparCache()
        PainelArmazem.registrar(app, ip, "roteador: confiar no certificado", "ok")
        return json(200, roteadorEstado())
    }

    private fun roteadorAparelhos(req: Requisicao): Resposta {
        val l = try { UnifiCliente.ler(app, forcar = req.consulta["atualizar"] == "1") } catch (e: UnifiCliente.Falha) {
            return erro(502, e.message ?: "falhou")
        }
        val lista = org.json.JSONArray()
        for (a in l.aparelhos) lista.put(JSONObject().put("nome", a.nome).put("ip", a.ip ?: JSONObject.NULL)
            .put("mac", a.mac ?: JSONObject.NULL).put("tipo", a.tipo).put("desde", a.desde ?: JSONObject.NULL))
        return json(200, JSONObject().put("versao", l.versao ?: JSONObject.NULL).put("respostaMs", l.respostaMs)
            .put("em", l.em).put("aparelhos", lista))
    }

    private fun roteadorWifiTv(): Resposta {
        val w = try { UnifiCliente.wifiDaTv(app) } catch (e: UnifiCliente.Falha) { return erro(502, e.message ?: "falhou") }
        val o = JSONObject().put("noWifi", w.conectadaNoWifi).put("dbm", w.dbm).put("banda", Unifi.banda(w.mhz) ?: JSONObject.NULL)
            .put("mbps", w.mbps).put("achadaNoRoteador", w.achadaNoRoteador).put("dicas", org.json.JSONArray(w.dicas))
        w.ap?.let { ap ->
            val radios = org.json.JSONArray()
            for (r in ap.radios) radios.put(JSONObject().put("ghz", r.ghz).put("canal", r.canal ?: JSONObject.NULL)
                .put("larguraMhz", r.larguraMhz ?: JSONObject.NULL).put("retransmissoesPct", r.retransmissoesPct ?: JSONObject.NULL))
            o.put("pontoDeAcesso", JSONObject().put("nome", ap.nome).put("modelo", ap.modelo ?: JSONObject.NULL)
                .put("cpuPct", ap.cpuPct ?: JSONObject.NULL).put("radios", radios))
        }
        w.peloRoteador?.let { r ->
            o.put("peloRoteador", JSONObject().put("sinalDbm", r.sinalDbm ?: JSONObject.NULL)
                .put("banda", Unifi.bandaDoRadio(r.radio) ?: JSONObject.NULL).put("canal", r.canal ?: JSONObject.NULL)
                .put("txMbps", r.txMbps ?: JSONObject.NULL).put("rxMbps", r.rxMbps ?: JSONObject.NULL)
                .put("satisfacao", r.satisfacao ?: JSONObject.NULL).put("rede", r.rede ?: JSONObject.NULL))
        }
        w.falhaApiAntiga?.let { o.put("falhaApiAntiga", it) }
        return json(200, o)
    }

    private fun estadoReconectar(): JSONObject = JSONObject()
        .put("estado", UnifiCliente.estadoReconectar.name.lowercase())
        .put("detalhe", UnifiCliente.detalheReconectar ?: JSONObject.NULL)

    /** Só ABRE a confirmação na TV: quem decide é quem está com o controle (ConfirmarRoteadorActivity). */
    private fun roteadorReconectar(ip: String): Resposta {
        UnifiCliente.pedirReconectar(app)?.let { return erro(409, it) }
        PainelArmazem.registrar(app, ip, "roteador: reconectar a TV", "esperando OK na TV")
        return json(200, estadoReconectar())
    }

    private fun roteadorEsquecer(ip: String): Resposta {
        UnifiArmazem.esquecer(app)
        certificadoPendente = null
        UnifiCliente.limparCache()
        PainelArmazem.registrar(app, ip, "roteador: esquecer", "ok")
        return json(200, roteadorEstado())
    }

    // ---------------------------------------------------------------- Claude pela internet (MCP + túnel Tailscale)

    private fun mcpEstado(): JSONObject = JSONObject()
        .put("ligado", McpServidor.ligado(app))
        .put("tunelConfigurado", TunelTailscale.configurado(app))
        .put("tunelRodando", TunelTailscale.rodando())
        .put("temChave", TunelTailscale.temChave(app))
        .put("endereco", TunelTailscale.nome(app)?.let { "https://$it/mcp" } ?: JSONObject.NULL)
        .put("erro", TunelTailscale.ultimoErro(app) ?: JSONObject.NULL)
        .put("logins", McpServidor.logins(app))
        .put("google", LoginGoogle.configurado(app))
        .put("binario", TunelTailscale.binario(app).exists())
        .put("conta", TunelTailscale.conta(app) ?: JSONObject.NULL)
        .put("rede", TunelTailscale.redeLigada(app))
        .put("atalhos", TunelTailscale.atalhos(app))
        .put("passo", ConectarClaudeActivity.Passo.agora(app).let {
            JSONObject().put("id", it.id).put("titulo", it.titulo).put("texto", it.texto).put("qr", it.qr ?: JSONObject.NULL)
        })

    /** Ligar/desligar, colar a chave do Tailscale (PIN de novo), revogar logins, esquecer o Tailscale (PIN de novo). */
    private fun mcpConfig(req: Requisicao, token: String?, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        // Abrir a TV para fora (MCP na internet, painel na rede Tailscale, atalhos para aparelhos da casa) pede o PIN de
        // novo, como instalar e desinstalar: um cookie roubado na Wi-Fi (o painel é HTTP) não pode publicar a TV
        // (revisão 09/11/2026). Desligar e revogar continuam sem PIN: fechar nunca deve ser difícil.
        val abre = o.optBoolean("ligado") || o.optBoolean("rede") || o.optString("atalhos").isNotBlank()
        if (abre && !sessoes.elevada(token)) return precisaPin()
        // Confirmações primeiro, antes de mudar qualquer coisa: abrir a TV para fora de casa.
        if (o.optBoolean("ligado") && !McpServidor.ligado(app))
            confirmarNaTv(Confirmacoes.Acao.ABRIR_FORA, txt(R.string.cf_ligar_mcp), ip)?.let { return it }
        if (o.optBoolean("rede") && !TunelTailscale.redeLigada(app))
            confirmarNaTv(Confirmacoes.Acao.ABRIR_FORA, txt(R.string.cf_ligar_tailscale), ip)?.let { return it }
        val mudou = ArrayList<String>()
        if (o.has("chave")) {
            if (!sessoes.elevada(token)) return precisaPin()
            val c = o.optString("chave").trim()
            if (!TunelTailscale.chaveValida(c)) return erro(400, "chave inválida: cole a chave inteira, que começa com tskey-")
            if (!TunelTailscale.definirChave(app, c)) return erro(500, "não consegui guardar a chave cifrada")
            mudou.add("chave do Tailscale")
        }
        if (o.optBoolean("esquecer")) {
            if (!sessoes.elevada(token)) return precisaPin()
            TunelTailscale.esquecer(app); mudou.add("esquecer Tailscale")
        }
        if (o.has("google_id")) {
            if (!sessoes.elevada(token)) return precisaPin()
            val id = o.optString("google_id").trim(); val seg = o.optString("google_segredo").trim()
            if (id.isEmpty()) { LoginGoogle.definir(app, null, null); mudou.add("login Google desligado") }
            else {
                if (!LoginGoogle.idValido(id)) return erro(400, "id inválido: termina em .apps.googleusercontent.com")
                if (!LoginGoogle.segredoValido(seg)) return erro(400, "segredo do cliente inválido")
                LoginGoogle.definir(app, id, seg); McpServidor.revogar(app); mudou.add("login Google ligado")
            }
        }
        if (o.optBoolean("revogar")) { McpServidor.revogar(app); mudou.add("revogar logins") }
        if (o.has("atalhos")) {
            if (!TunelTailscale.definirAtalhos(app, o.optString("atalhos")))
                return erro(400, "atalhos inválidos: use aparelho:porta separados por vírgula (máximo ${AtalhosTunel.MAXIMO}), ex.: nas:8096")
            mudou.add("atalhos")
        }
        if (o.has("rede")) { TunelTailscale.definirRede(app, o.optBoolean("rede")); mudou.add("rede=${o.optBoolean("rede")}") }
        if (o.has("ligado")) {
            if (o.optBoolean("ligado") && !PainelArmazem.temPin(app)) return erro(400, "defina o PIN antes")
            McpServidor.definirLigado(app, o.optBoolean("ligado")); mudou.add("ligado=${o.optBoolean("ligado")}")
        } else if (mudou.isNotEmpty()) TunelTailscale.ajustar(app)
        if (mudou.isNotEmpty()) PainelArmazem.registrar(app, ip, "mcp: ${mudou.joinToString(", ")}", "ok")
        return json(200, mcpEstado())
    }
}

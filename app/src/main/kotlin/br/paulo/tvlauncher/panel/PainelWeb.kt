package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.Fundo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Compartilhar
import br.paulo.tvlauncher.ConectarClaudeActivity
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.Gatilhos
import br.paulo.tvlauncher.LoginGoogle
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.PainelDono
import br.paulo.tvlauncher.PainelShizuku
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.VodActivity
import br.paulo.tvlauncher.alarm.AutoDesligar
import br.paulo.tvlauncher.backup.BackupAutomatico
import br.paulo.tvlauncher.backup.BackupCompleto
import br.paulo.tvlauncher.backup.BackupConfig
import br.paulo.tvlauncher.backup.DriveBackup
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.backup.MudarDeTvRegras
import br.paulo.tvlauncher.family.FotoPerfil
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.Recado
import br.paulo.tvlauncher.home.MenuApp
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.TecladoCelular
import br.paulo.tvlauncher.mcp.ChaveAcesso
import br.paulo.tvlauncher.mcp.DesafiosChave
import br.paulo.tvlauncher.mcp.TunelTailscale
import br.paulo.tvlauncher.network.HistoricoRede
import br.paulo.tvlauncher.observer.ObserverTv
import br.paulo.tvlauncher.observer.SofyMotion
import br.paulo.tvlauncher.observer.SofyTv
import br.paulo.tvlauncher.observer.SofyVoice
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.Congelar
import br.paulo.tvlauncher.privilege.PrimeiraPoderes
import br.paulo.tvlauncher.privilege.SaudeAcesso
import br.paulo.tvlauncher.screensaver.FotosProtetor
import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.txt
import br.paulo.tvlauncher.weather.AvisoRegras
import br.paulo.tvlauncher.weather.LocalClima
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
import org.json.JSONArray
import org.json.JSONObject

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
    internal const val TAG = "TvLauncher.Painel"
    const val COOKIE = "painel_sessao"
    private const val LIMITE_CORPO = 4 * 1024   // login e ações: corpo pequeno
    private const val TEMPO_LEITURA_MS = 15_000
    private const val PRAZO_TLS_MS = 10_000L

    @Volatile private var servidor: ServerSocket? = null
    /** A porta https da rede de casa (PainelTls, 13/11/2026): o mesmo painel, com o certificado que a TV gera. */
    @Volatile private var servidorTls: ServerSocket? = null
    internal lateinit var app: Context

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
        return base + DestinoPainel.fragmento(codigoEntrada.novo(), destino) + sufixoApp()   // ideia 43: abre já na página certa
    }

    /** Ideia 101 ([QrDoApp]): certificado https e nome do Tailscale para o app de celular. */
    private fun sufixoApp(): String {
        val der = try { PainelTls.emissao()?.servidor?.encoded } catch (_: Exception) { null }
        val ts = if (::app.isInitialized) TunelTailscale.nome(app) else null
        return QrDoApp.sufixo(der, ts)
    }

    /**
     * Primeira configuração pelo celular (ideia 97, [PrimeiraConfig]): código de uso único de 15 min e o "modo" que
     * deixa a porta abrir sem PIN. O modo só vale enquanto a tela do QR está na TV (EntrarPeloQrActivity sem PIN).
     */
    val codigoPrimeira = CodigoEntrada({ System.currentTimeMillis() }, PrimeiraConfig.VALIDADE_MS)
    @Volatile internal var modoPrimeira = false

    /**
     * Liga o modo da primeira configuração, abre a porta (sem PIN) e devolve o endereço do QR: `#primeira=<código>`.
     * Null se a TV já tem PIN (aí vale o "Entrar pelo celular" de sempre) ou se não está em rede nenhuma.
     */
    fun linkPrimeiraConfig(ctx: Context): String? {
        app = ctx.applicationContext
        if (PainelArmazem.temPin(app)) return null
        val base = endereco() ?: return null
        modoPrimeira = true
        ajustar(app)   // a porta abre numa thread à parte: até a pessoa apontar o celular, já está de pé
        return base + "#primeira=" + codigoPrimeira.novo() + sufixoApp()
    }

    /** A tela do QR saiu: o código deixa de valer e, se o PIN não foi criado, a porta fecha de novo. */
    fun sairPrimeiraConfig(ctx: Context) {
        app = ctx.applicationContext
        modoPrimeira = false
        codigoPrimeira.cancelar()
        ajustar(app)
    }

    @Volatile internal var tentativas: ControleTentativas? = null

    /** Entrar com a digital (ideia 99, [ChaveAcesso]): desafios de uso único, para cadastrar e para entrar. */
    internal val desafiosChave = DesafiosChave({ System.currentTimeMillis() })
    internal val travaPin = Any()   // um PBKDF2 por vez: muitas tentativas juntas não derrubam a CPU da TV

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

    /**
     * Prazo TOTAL do aperto de mão TLS (15/11/2026). O soTimeout só limita cada leitura: um aparelho mandando 1 byte a
     * cada 14 s prendia uma thread para sempre, e 6 dessas derrubavam o painel inteiro (8080 junto). Uma thread só,
     * criada no primeiro https e encerrada quando fica 30 s sem nada para vigiar.
     */
    private val vigiaTls by lazy {
        java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "Painel-vigiaTls").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    /** Faz o aperto de mão TLS em até [PRAZO_TLS_MS]; estourou, fecha a conexão (o startHandshake lança IOException). */
    private fun apertoDeMao(c: javax.net.ssl.SSLSocket) {
        val prazo = vigiaTls.schedule({ fechar(c) }, PRAZO_TLS_MS, TimeUnit.MILLISECONDS)
        try { c.startHandshake() } finally { prazo.cancel(false) }
    }

    @Volatile private var iniciando = false
    private var geracao = 0   // cada iniciar() ganha um número; uma thread de bind antiga não mexe no estado da nova

    fun ligado(): Boolean = servidor?.isClosed == false

    /** Liga ou desliga conforme as preferências (ver PainelArmazem.podeLigar). Barato e idempotente: chamar à vontade. */
    @Synchronized fun ajustar(ctx: Context) {
        app = ctx.applicationContext
        // Sem PIN, só abre no modo da primeira configuração (tela do QR na TV): o assistente cria o PIN pelo celular.
        val deve = PainelArmazem.podeLigar(app) || (modoPrimeira && !PainelArmazem.temPin(app)) ||
            MudarDeTv.recebendo()   // elevação 19.7: só enquanto a tela "Trazer de outra TV" está aberta
        if (deve && !ligado() && !iniciando) iniciar() else if (!deve && (ligado() || iniciando)) parar()
    }

    /** Só chamar dentro de synchronized(travaPin): assim todas as threads usam o MESMO contador. */
    internal fun controle(): ControleTentativas = tentativas ?: ControleTentativas(
        { System.currentTimeMillis() }, PainelArmazem.falhas(app),
    ) { PainelArmazem.definirFalhas(app, it) }.also { tentativas = it }

    /** Chamado pelas Configurações quando o PIN muda ou o painel é religado: derruba as sessões e zera os erros. */
    @Synchronized fun recomecar(ctx: Context) {
        app = ctx.applicationContext
        sessoes.encerrarTodas()
        senhasDav.limpar()
        tentativas?.zerar()
        ajustar(ctx)
    }

    /**
     * PIN trocado pelo próprio painel: o mesmo que [recomecar] faz para a troca na TV (senhas do WebDAV guardadas e o
     * contador de erros recomeçam), mas mantém a sessão de quem trocou, para não cair da página no meio do uso.
     */
    private fun pinTrocadoPeloPainel(token: String?) {
        sessoes.encerrarOutras(token)
        senhasDav.limpar()
        synchronized(travaPin) { tentativas?.zerar() }
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
            AnuncioUpnp.ligar(app)      // e o atalho "<TV> · painel" na tela "Rede" do Windows (SSDP)
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
        AnuncioDav.desligar()
        AnuncioUpnp.desligar()
        Log.i(TAG, "painel desligado")
    }

    // ---------------------------------------------------------------- https na rede de casa (13/11/2026)

    /**
     * Abre a porta https ([PainelTls.PORTA]) junto com a http. O certificado é carregado (ou feito, na primeira vez)
     * numa thread própria: gerar chave leva alguns milissegundos e ler o Keystore também não é coisa da thread da tela.
     * Sem certificado (Keystore com defeito), o painel segue só em http.
     */
    private fun iniciarTls(minha: Int) {
        Fundo.rodar("Painel-https") {
            // O IP entra no certificado: espera a rede aparecer (até ~5 min) se o painel ligou antes do Wi-Fi.
            var rede = redes().entries.minByOrNull { it.key }
            var tentativas = 0
            while (rede == null && tentativas++ < 10) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@rodar }
                if (geracao != minha) return@rodar
                redeCache = 0L to emptyMap()
                rede = redes().entries.minByOrNull { it.key }
            }
            val e = PainelTls.garantir(app, rede?.key, rede?.value ?: 24) ?: return@rodar
            abrirTls(minha, e)
        }
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
        if (PainelDav.ligado(app)) AnuncioDav.ligar(app)
        aceitar(porta, https = true)
        synchronized(this) { if (servidorTls === porta) servidorTls = null }
        try { porta.close() } catch (_: IOException) {}
    }

    private fun ligadoTls(): Boolean = servidorTls?.isClosed == false

    /**
     * A chave "Unidade de rede" mudou (TV ou painel): liga ou desliga o anúncio na rede. O servidor em si não precisa
     * de nada: cada pedido confere a chave (PainelDav.ligado).
     */
    fun unidadeRedeMudou(ctx: Context) {
        app = ctx.applicationContext
        if (ligadoTls() && PainelDav.ligado(app)) AnuncioDav.ligar(app) else AnuncioDav.desligar()
    }

    /** O endereço da unidade de rede para mostrar na TV e no painel (o nome do certificado), ou null com https fechado. */
    fun enderecoUnidade(ctx: Context): String? =
        if (!ligadoTls()) null else "https://${PainelTls.nome(ctx)}:${PainelTls.PORTA}${DavLogica.PREFIXO}/"

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
    private fun paginaCertificado(agente: String?): Resposta {
        val modelo = app.assets.open("painel/certificado.html").use { it.readBytes() }.toString(Charsets.UTF_8)
        val html = PainelTls.estado(app, ipsDaRede(), ligadoTls()).let { PaginaCertificado.preencher(modelo, it, it.optString("nome"), agente) }
        return Resposta(200, "text/html; charset=utf-8", html.toByteArray())
    }

    /**
     * O XML do aparelho UPnP. O duplo clique abre o painel, no mesmo host que o Windows usou para buscar o XML (o IP da
     * TV). Pelo http de propósito: o https da TV só abre sem aviso de segurança em quem já instalou o certificado dela.
     */
    private fun descricaoUpnp(host: String?): Resposta {
        val pagina = "http://${host ?: (ipsDaRede().minOrNull() + ":" + Config.PAINEL_PORTA)}/"
        val xml = Ssdp.descricao(Ssdp.nomeVisivel(PainelVizinhos.nomeDaTv(app)), Ssdp.uuid(PainelVizinhos.idDaTv(app)), pagina)
        return Resposta(200, "text/xml; charset=utf-8", xml.toByteArray())
    }

    /** A descrição do aparelho MTP (a TV em "Este Computador"); a página dele é o próprio painel. */
    private fun descricaoMtp(host: String?): Resposta {
        val pagina = "http://${host ?: (ipsDaRede().minOrNull() + ":" + Config.PAINEL_PORTA)}/"
        val id = PainelVizinhos.idDaTv(app)
        val xml = Ssdp.descricaoMtp(Ssdp.nomeNaUnidade(PainelVizinhos.nomeDaTv(app)), Ssdp.uuidMtp(id), pagina, id)
        return Resposta(200, "text/xml; charset=utf-8", xml.toByteArray())
    }

    /** Página com o endereço da unidade de rede para mapear (o nome do certificado e, se estiver nele, o IP). */
    private fun paginaUnidade(): Resposta {
        val modelo = app.assets.open("painel/unidade.html").use { it.readBytes() }.toString(Charsets.UTF_8)
        val est = PainelTls.estado(app, ipsDaRede(), ligadoTls())
        val ip = if (est.isNull("enderecoIp")) "" else est.optString("enderecoIp", "")
        var html = modelo.replace(Regex("<!--se:comip-->(.*?)<!--fim:comip-->", RegexOption.DOT_MATCHES_ALL)) { if (ip.isNotEmpty()) it.groupValues[1] else "" }
        html = html.replace("{{ENDERECO}}", DavLogica.escaparXml("https://${PainelTls.nome(app)}:${PainelTls.PORTA}${DavLogica.PREFIXO}/"))
            .replace("{{ENDERECO_IP}}", DavLogica.escaparXml(if (ip.isEmpty()) "" else ip.trimEnd('/') + DavLogica.PREFIXO + "/"))
        return Resposta(200, "text/html; charset=utf-8", html.toByteArray())
    }

    /**
     * O .cmd que deixa o Windows pronto e mapeia a unidade (ConectorWindows). Livre como o certificado: só leva o
     * endereço e o certificado público. Usa o IP quando ele está no certificado (nome sem ponto nem todo PC resolve).
     */
    private fun conectorWindows(): Resposta {
        val pem = PainelTls.pemDaCa() ?: return erro(503, "o certificado ainda não foi gerado; tente em 1 minuto")
        val host = hostConector()
        val texto = ConectorWindows.script(host, PainelTls.PORTA, pem, Ssdp.nomeNaUnidade(PainelVizinhos.nomeDaTv(app))) ?: return erro(503, "não consegui montar o conector")
        return Resposta(200, "application/octet-stream", texto.toByteArray(Charsets.US_ASCII),
            mapOf("Content-Disposition" to "attachment; filename=\"${ConectorWindows.ARQUIVO}\"", "Cache-Control" to "no-store"))
    }

    /** O endereço da TV no conector: o IP quando ele está no certificado (nome sem ponto nem todo PC resolve). */
    private fun hostConector(): String {
        val noCert = PainelTls.nomesNoCertificado()
        return ipsDaRede().firstOrNull { it in noCert } ?: PainelTls.nome(app)
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

    /**
     * O atalho "Abrir o painel da TV.url" (PanelShortcut) com o IP de agora, para o MTP e o WebDAV mostrarem na raiz.
     * Fica no cache do app (não na memória que o computador vê) e só é regravado quando o IP ou o idioma mudam.
     * null sem rede.
     */
    fun atalhoPainel(ctx: Context): File? {
        val ip = ipsDaRede().minOrNull() ?: return null
        val nome = PanelShortcut.fileName(ctx.getString(R.string.ur_atalho_nome)) ?: return null
        val bytes = PanelShortcut.content(ip, Config.PAINEL_PORTA)
        synchronized(travaAtalho) {
            val pasta = File(ctx.applicationContext.cacheDir, "panel-shortcut")
            if (!pasta.isDirectory && !pasta.mkdirs()) return null
            pasta.listFiles()?.forEach { if (it.name != nome) it.delete() }   // o de outro idioma
            val f = File(pasta, nome)
            if (!f.isFile || f.length() != bytes.size.toLong() || !f.readBytes().contentEquals(bytes)) {
                try { f.writeBytes(bytes) } catch (e: IOException) { Log.w(TAG, "atalho do painel: $e"); return null }
            }
            return f
        }
    }
    private val travaAtalho = Any()

    /** O endereço para digitar no navegador (o primeiro IP da rede), ou null sem rede. */
    fun endereco(): String? = ipsDaRede().minOrNull()?.let { "http://$it:${Config.PAINEL_PORTA}/" }

    /**
     * A conexão chegou num IP nosso de Wi-Fi/cabo (não no da VPN) e veio da MESMA sub-rede (ex.: 192.168.1.x)?
     * Recusa tudo o que vier pela VPN, de outra VLAN do roteador ou de fora da casa.
     */
    /** Para os outros servidores da casa (MtpNaRede): a mesma conferência de rede do painel. */
    internal fun daRedeDeCasa(c: Socket): Boolean = redeLocal(c)

    private fun redeLocal(c: Socket): Boolean {
        val local = c.localAddress as? Inet4Address ?: return false
        val remoto = c.inetAddress as? Inet4Address ?: return false
        val prefixo = redes()[local.hostAddress] ?: return false
        return !remoto.isLoopbackAddress && ChecagensPainel.mesmaRede(local.address, remoto.address, prefixo)
    }

    private fun nomesPermitidos(): Set<String> = ipsDaRede() + Config.PAINEL_NOMES + nomesTailscale()

    /** Com a "rede Tailscale" ligada, o nome completo (…ts.net) também atende em https na porta 8443 (o túnel decifra). */
    internal fun nomesHttps(): Set<String> {
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
    internal class Resposta(val codigo: Int, val tipo: String?, val corpo: ByteArray, val extras: Map<String, String> = emptyMap(),
                           val arquivo: File? = null, val fluxo: ((java.io.OutputStream) -> Unit)? = null,
                           val range: String? = null)   // cabeçalho Range do pedido, para arquivo (vídeo pula para o meio)

    /** Rotas que recebem o corpo como fluxo, direto para o disco (arquivo de vários GB não cabe na memória). */
    private val ROTAS_FLUXO = setOf("/api/arquivos/enviar", "/api/apps/instalar", "/api/fotos/enviar", "/api/perfil/foto")

    internal fun json(codigo: Int, o: JSONObject, extras: Map<String, String> = emptyMap()) =
        Resposta(codigo, "application/json; charset=utf-8", o.toString().toByteArray(), extras)

    internal fun erro(codigo: Int, mensagem: String, extra: JSONObject.() -> Unit = {}) =
        json(codigo, JSONObject().put("erro", mensagem).apply(extra))

    /** [https]: chegou pela porta https (a conversa TLS acontece aqui, no primeiro read, não na thread do accept). */
    private fun atender(c: Socket, https: Boolean) {
        val ip = c.inetAddress.hostAddress ?: "?"
        try {
            c.soTimeout = TEMPO_LEITURA_MS
            if (c is javax.net.ssl.SSLSocket) apertoDeMao(c)
            val entrada = c.getInputStream().buffered(8192)
            val saida = c.getOutputStream().buffered(16 * 1024)
            val resposta = try {
                val req = PainelHttp.lerCabecalhos(entrada, chunkedEm = { https && DavLogica.ehDav(it) && PainelDav.ligado(app) })
                // A TV como unidade de rede (WebDAV, PainelDav.kt): só na porta https e com a chave ligada. Responde
                // ela mesma (métodos e corpos próprios do WebDAV) e a conexão fecha no finally.
                if (https && PainelDav.meu(req) && PainelDav.ligado(app)) {
                    PainelDav.atender(PainelDav.ambiente(app), req, entrada, saida, ip, hostOk(req.cabecalho("host"), true)) { conferirSenhaDav(it, ip) }
                    return
                }
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
        PainelIdeias.ROTA_OPML -> PainelIdeias.LIMITE_OPML
        "/api/backup/completo/restaurar" -> BackupCompleto.LIMITE_BYTES * 2 + 1024   // o cofre em Base64 + a senha
        "/api/mudar/receber" -> BackupCompleto.LIMITE_BYTES * 4   // elevação 19.7: o cofre + a lista de apps, cifrados e em Base64
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
        // Elevação 19.7: a TV antiga manda o envelope sem sessão (ela não tem o PIN desta). O segredo do QR é conferido
        // AQUI, antes de ler o corpo: chute errado não gasta memória, e depois de 5 erros a porta fecha.
        if (req.caminho == "/api/mudar/receber" && req.metodo == "POST") {
            if (!MudarDeTv.recebendo()) return erro(410, "esta TV não está esperando (abra 'Trazer de outra TV' nela)")
            return when (MudarDeTv.conferirSegredo(req.cabecalho("x-mudar"))) {
                MudarDeTvRegras.Conferencia.OK -> null
                MudarDeTvRegras.Conferencia.ERRADO -> erro(403, "código errado")
                MudarDeTvRegras.Conferencia.VENCIDO -> erro(410, "o código venceu; gere outro na TV nova")
            }
        }
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
            "/certificado" -> return paginaCertificado(req.cabecalho("user-agent"))
            // A TV na tela "Rede" do Windows (Ssdp/AnuncioUpnp): a descrição do aparelho e a página que o duplo clique
            // abre. Livres como a do certificado (não dizem nada além do endereço). Desde 18/11/2026 sempre que o
            // painel está ligado (o atalho "· painel" não depende mais da unidade de rede).
            Ssdp.CAMINHO_DESCRICAO -> return descricaoUpnp(host)
            "/unidade" -> if (PainelDav.ligado(app)) return paginaUnidade()
            Ssdp.CAMINHO_MTP -> if (PainelDav.ligado(app)) return descricaoMtp(host)
            // o ícone da marca que as descrições acima apontam (só os nomes da lista: nada de caminho vindo de fora)
            in Ssdp.ARQUIVOS_ICONE.keys.map { Ssdp.CAMINHO_ICONES + it } -> return Resposta(200, "image/png",
                app.assets.open("upnp/" + req.caminho.removePrefix(Ssdp.CAMINHO_ICONES)).use { it.readBytes() }, mapOf("Cache-Control" to "max-age=86400"))
            // o ícone do atalho "Abrir o painel da TV.url" (PanelShortcut)
            PanelShortcut.ICON_PATH -> return Resposta(200, "image/x-icon",
                app.assets.open("upnp/sofaly.ico").use { it.readBytes() }, mapOf("Cache-Control" to "max-age=86400"))
            Ssdp.CAMINHO_MTP_SERVICO -> if (PainelDav.ligado(app)) return Resposta(200, "text/xml; charset=utf-8", Ssdp.SERVICO_MTP.toByteArray())
            // O .cmd roda como administrador e instala um certificado no Windows: só sai pela porta https (15/11/2026).
            // Pelo http, alguém no meio do caminho trocaria o certificado de dentro dele; lá, manda para o https.
            ConectorWindows.CAMINHO -> if (PainelDav.ligado(app)) return if (https) conectorWindows() else
                Resposta(302, "text/plain; charset=utf-8", ByteArray(0),
                    mapOf("Location" to "https://${hostConector()}:${PainelTls.PORTA}${ConectorWindows.CAMINHO}", "Cache-Control" to "no-store"))
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
            "/api/convite/entrar" -> if (req.metodo == "POST") return entrarPorConvite(req, ip)   // elevação 18.1
            // Elevação 19.7 (TV nova): a chave pública e o envelope, livres de sessão mas só com a tela aberta na TV.
            // O segredo do envelope já foi conferido em antesDoCorpo.
            "/api/mudar/chave" -> if (req.metodo == "GET") return MudarDeTv.chavePublica()?.takeIf { MudarDeTv.recebendo() }
                ?.let { json(200, JSONObject().put("publica", it)) } ?: erro(410, "esta TV não está esperando")
            "/api/mudar/receber" -> if (req.metodo == "POST") return receberDeOutraTv(req, ip)
            "/api/primeira" -> if (req.metodo == "POST") return primeiraConfig(req, ip)   // ideia 97: cria o PIN
            // Ideia 99: digital do celular (passkey), só pelo https do Tailscale. Estado e desafio são livres como a
            // página de entrar; a entrada confere a assinatura.
            "/api/chave/estado" -> if (req.metodo == "GET") return json(200, JSONObject()
                .put("disponivel", chaveDisponivel(host)).put("temChaves", PainelArmazem.chaves(app).isNotEmpty()))
            "/api/chave/desafio" -> if (req.metodo == "POST") {
                if (!chaveDisponivel(host)) return erro(403, "a digital só funciona pelo endereço do Tailscale (https)")
                return json(200, JSONObject().put("desafio", desafiosChave.novo()).put("rpId", ChaveAcesso.rpId(host!!)))
            }
            "/api/chave/entrar" -> if (req.metodo == "POST") return entrarComChave(req, host, ip)
            "/api/sair" -> if (req.metodo == "POST") { sessoes.encerrar(token); return json(200, JSONObject().put("ok", true), cookieApagado()) }
        }
        if (!req.caminho.startsWith("/api/")) return erro(404, "não existe")
        // As outras TVs da casa: a página pede sozinha junto com o estado (a cada 30 s). existe() e não valida(),
        // pelo mesmo motivo do /api/estado: consulta automática não pode manter a sessão viva para sempre.
        if (req.caminho == "/api/tvs" && req.metodo == "GET")
            return if (sessoes.existe(token)) json(200, PainelVizinhos.json(app)) else erro(401, "entre com o PIN")
        if (!sessoes.valida(token)) return erro(401, "entre com o PIN")
        // Elevação 18.1: a trava do papel (família, convidado) vale para TODA rota daqui para baixo, antes de qualquer
        // leitura ou ação. O que não está na lista do papel é negado (PapeisPainel.permite).
        val papel = sessoes.papel(token) ?: return erro(401, "entre com o PIN")
        if (!PapeisPainel.permite(papel, req.metodo, req.caminho))
            return erro(403, "seu acesso a esta TV não inclui isto") { put("papel", papel.chave) }
        when (req.caminho) {
            "/api/papeis" -> if (req.metodo == "GET") return json(200, JSONObject().put("convites",
                JSONArray().apply { convites().forEach { put(PapeisPainel.publico(it)) } }))
            "/api/papeis/convite" -> if (req.metodo == "POST") return criarConvite(req, token, ip, https, host)
            "/api/papeis/revogar" -> if (req.metodo == "POST") return revogarConvite(req, token, ip)
        }
        // Cuidar de longe (ideias 77-79, PainelCuidar.kt). A foto do pedido de ajuda é imagem: sai daqui mesmo.
        if (req.caminho == "/api/cuidar/foto" && req.metodo == "GET") {
            val f = CuidarDeLongeTv.fotoAjuda(app) ?: return erro(404, "sem foto de pedido de ajuda nas últimas 24 h")
            return Resposta(200, "image/jpeg", f.readBytes(), mapOf("Cache-Control" to "no-store"))
        }
        if (req.caminho == "/api/cuidar" || req.caminho.startsWith("/api/cuidar/")) {
            PainelCuidar.rotear(app, req.metodo, req.caminho.removePrefix("/api/cuidar"),
                if (req.metodo == "POST") corpoJson(req) else null, sessoes.elevada(token), ip)
                ?.let { return json(it.first, it.second) }
        }
        if (req.caminho == "/api/addons" || req.caminho.startsWith("/api/addons/")) {   // 16/11/2026: PainelAddons.kt
            PainelAddons.rotear(app, req.metodo, req.caminho.removePrefix("/api/addons"),
                if (req.metodo == "POST") corpoJson(req) else null, sessoes.elevada(token), ip)
                ?.let { return json(it.first, it.second) }
        }
        if (req.caminho == "/api/storage" || req.caminho.startsWith("/api/storage/")) {   // 20/11/2026: PainelStorage.kt
            PainelStorage.rotear(app, req.metodo, req.caminho.removePrefix("/api/storage"),
                if (req.metodo == "POST") corpoJson(req) else null, sessoes.elevada(token), ip)
                ?.let { return json(it.first, it.second) }
        }
        if (req.caminho.startsWith("/api/extras/")) {   // ideias de 10/11/2026: PainelExtras.kt
            if (req.caminho == "/api/extras/video" && req.metodo == "GET")
                return Resposta(200, null, ByteArray(0), fluxo = { PainelExtras.video(app, it) })
            // Ícones da cruz (21/11/2026, PanelIcons.kt): conjunto, troca item a item e a prévia de cada desenho.
            when {
                req.caminho == "/api/extras/icons/image" && req.metodo == "GET" ->
                    return PanelIcons.image(app, req.consulta["style"], req.consulta["type"])
                        ?.let { Resposta(200, "image/webp", it) } ?: erro(404, "não existe")
                req.caminho == "/api/extras/icons" && req.metodo == "GET" -> return json(200, PanelIcons.state(app).put("ok", true))
                req.caminho == "/api/extras/icons" && req.metodo == "POST" -> {
                    val r = corpoJson(req)?.let { PanelIcons.change(app, it) } ?: return erro(400, "conjunto ou ícone desconhecido")
                    PainelArmazem.registrar(app, ip, "ícones da cruz: ${corpoJson(req)}", "ok")
                    return json(200, r.put("ok", true))
                }
            }
            PainelExtras.rotear(app, req.metodo, req.caminho.removePrefix("/api/extras/"),
                if (req.metodo == "POST") corpoJson(req) else null, req.consulta, sessoes.elevada(token), ip)
                ?.let { return json(it.first, it.second) }
        }

        when (req.caminho) {
            "/api/confirmar" -> if (req.metodo == "POST") return confirmar(req, token, ip)
            // Estudo do painel (PR 5): "Escondidos" dos Apps só abre com o PIN conferido há pouco, igual à TV.
            "/api/elevada" -> if (req.metodo == "GET") return if (sessoes.elevada(token)) json(200, JSONObject().put("ok", true)) else precisaPin()
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
            "/api/backup/recuperacao" -> return recuperacao(req, token, ip)
            "/api/mudar/enviar" -> if (req.metodo == "POST") return enviarParaTvNova(req, token, ip)   // elevação 19.7
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
            // Conta do Google Drive pelo painel (14/11/2026, pedido do dono: o painel e as Configurações da TV têm de ter
            // as mesmas opções). Mesmo login por código da tela da TV (FotoPerfilActivity com EXTRA_DRIVE).
            "/api/backup/drive/conectar" -> if (req.metodo == "POST") return driveConectar(token, ip)
            "/api/backup/drive/login" -> if (req.metodo == "GET") return json(200, driveLoginEstado())
            "/api/backup/drive/qr" -> if (req.metodo == "GET") {
                val s = loginDrive?.takeIf { it.estado == LoginGoogle.Estado.ESPERANDO } ?: return erro(404, "sem código agora")
                return Resposta(200, "image/png", QrCodigo.png(s.urlVerificacao), mapOf("Cache-Control" to "no-store"))
            }
            "/api/backup/drive/cancelar" -> if (req.metodo == "POST") {
                loginDrive?.let { if (it.estado == LoginGoogle.Estado.ESPERANDO) it.estado = LoginGoogle.Estado.ERRO }
                loginDrive = null
                return json(200, backupNoturnoEstado())
            }
            "/api/backup/drive/desconectar" -> if (req.metodo == "POST") {
                if (!sessoes.elevada(token)) return precisaPin()
                DriveBackup.desconectar(app)
                PainelArmazem.registrar(app, ip, "Google Drive: desconectar", "ok")   // sem o e-mail no registro
                return json(200, backupNoturnoEstado())
            }
            "/api/backup/drive/copias" -> if (req.metodo == "GET") {
                if (!DriveBackup.conectado(app)) return erro(400, "conecte a conta do Google Drive primeiro")
                val copias = try { DriveBackup.listar(app) } catch (e: Exception) {
                    return erro(502, "o Drive não respondeu: ${e.message ?: e.javaClass.simpleName}")
                }
                return json(200, JSONObject().put("copias", JSONArray().apply {
                    for (c in copias) put(JSONObject().put("id", c.id).put("nome", c.nome).put("tamanho", c.tamanho))
                }))
            }
            "/api/backup/drive/restaurar" -> if (req.metodo == "POST") return driveRestaurar(req, token, ip)
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
            // Observador (ObserverTv, 20/11/2026): leituras, o que o juiz vê agora, recados e registro. Só leitura.
            // ?lang=xx (20/11/2026): os recados da Sofy já com a frase na língua do navegador, para a faixa da Início.
            "/api/observer" -> if (req.metodo == "GET") return json(200, ObserverTv.reportJson(app, 20).also { SofyTv.resolveNotes(app, it, req.consulta["lang"]) })
            // Sofy (20/11/2026): os movimentos (mesma fonte da TV, SofyMotion) e a chave "Sofy fala". O POST só liga/desliga a voz.
            "/api/sofy" -> when (req.metodo) {
                "GET" -> return json(200, SofyMotion.toJson().put("voice_on", SofyVoice.voiceOn(app)).put("screen_reader", SofyVoice.screenReaderOn(app)))
                "POST" -> {
                    val ligar = try { JSONObject(req.corpoTexto()).getBoolean("voice") } catch (e: Exception) { return erro(400, "falta voice") }
                    SofyVoice.setVoiceOn(app, ligar)
                    PainelArmazem.registrar(app, ip, "Sofy fala", if (ligar) "ligada" else "desligada")
                    return json(200, JSONObject().put("ok", true).put("voice_on", ligar))
                }
            }
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
            // Shell emprestado (PainelShizuku.kt, cartão na aba Extras). Permitir dá shell ao app: PIN de novo; revogar não.
            "/api/shizuku" -> if (req.metodo == "GET") return json(200, PainelShizuku.estado(app))
            "/api/shizuku/app" -> if (req.metodo == "POST") {
                val o = corpoJson(req) ?: return erro(400, "JSON inválido")
                val pacote = o.optString("pacote")
                val permitir = o.optString("acao") == "permitir"
                if (permitir && !sessoes.elevada(token)) return precisaPin()
                val motivo = PainelShizuku.mudar(app, pacote, permitir)
                PainelArmazem.registrar(app, ip, "Shizuku ${if (permitir) "permitir" else "revogar"} ${pacote.take(80)}", motivo ?: "ok")
                return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
            }
            "/api/dono" -> if (req.metodo == "GET") return json(200, PainelDono.estado(app))
            "/api/dono/app" -> if (req.metodo == "POST") {
                val o = corpoJson(req) ?: return erro(400, "JSON inválido")
                val pacote = o.optString("pacote")
                val acao = o.optString("acao")
                if (acao == "total" && !sessoes.elevada(token)) return precisaPin()
                val motivo = PainelDono.mudar(app, pacote, acao)
                PainelArmazem.registrar(app, ip, "Dono emprestado ${acao.take(10)} ${pacote.take(80)}", motivo ?: "ok")
                return if (motivo == null) json(200, JSONObject().put("ok", true)) else erro(409, motivo)
            }
            "/api/registro" -> if (req.metodo == "GET") return json(200, JSONObject().put("itens", PainelArmazem.registro(app)))
            "/api/apps/instalar" -> if (req.metodo == "POST") return instalar(req, token, ip)
            "/api/arquivos" -> if (req.metodo == "GET") { semAcesso()?.let { return it }; return json(200, PainelArquivos.listar(req.consulta["caminho"])) }
            "/api/arquivos/baixar" -> if (req.metodo == "GET") return baixarArquivo(req, ip)
            "/api/arquivos/enviar" -> if (req.metodo == "POST") return enviarArquivo(req, token, ip)
            "/api/arquivos/pasta" -> if (req.metodo == "POST") return criarPasta(req, ip)
            "/api/arquivos/apagar" -> if (req.metodo == "POST") return apagarArquivo(req, token, ip)
            "/api/arquivos/mover" -> if (req.metodo == "POST") return moverArquivo(req, ip)
            "/api/arquivos/tocar" -> if (req.metodo == "POST") return tocarArquivo(req, ip)
            "/api/tela/foto" -> if (req.metodo == "GET") return fotoTela()
            "/api/tela/tecla" -> if (req.metodo == "POST") return tecla(req)
            "/api/tela/texto" -> if (req.metodo == "POST") return texto(req, ip)
            "/api/tv/reiniciar" -> if (req.metodo == "POST") return reiniciarTv(token, ip)
            "/api/certificado/renovar" -> if (req.metodo == "POST") return renovarCertificado(req, token, ip)
            "/api/recado" -> if (req.metodo == "POST") return recado(req, ip)
            "/api/compartilhar" -> if (req.metodo == "POST") return compartilhar(req, ip)   // ideia 55
            "/api/rede/historico" -> if (req.metodo == "GET")
                return json(200, HistoricoRede.json(app, req.consulta["horas"]?.toIntOrNull() ?: 24))
            // Aba Configurações (PainelConfig.kt): as opções das Configurações da TV que só existiam lá.
            // Serviços de filmes e séries (PainelServicos.kt): os mesmos logins da tela da TV, para quem está no celular.
            "/api/servicos" -> when (req.metodo) {
                "GET" -> return json(200, PainelServicos.estado(app))
                "POST" -> {
                    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
                    return when (val r = PainelServicos.agir(app, o, sessoes.elevada(token)) { a, res -> PainelArmazem.registrar(app, ip, a, res) }) {
                        is PainelServicos.Resultado.Ok -> json(200, r.estado)
                        is PainelServicos.Resultado.Erro -> erro(r.codigo, r.mensagem)
                        PainelServicos.Resultado.PrecisaPin -> precisaPin()
                    }
                }
            }
            "/api/servicos/qr" -> if (req.metodo == "GET") {
                val sv = ServicosFilmes.Servico.de(req.consulta["servico"]) ?: return erro(404, "serviço desconhecido")
                val s = ServicosContas.sessao(sv)?.takeIf { it.estado == ServicosContas.EstadoLogin.ESPERANDO } ?: return erro(404, "sem código agora")
                return Resposta(200, "image/png", QrCodigo.png(s.codigo.urlQr), mapOf("Cache-Control" to "no-store"))
            }
            // Assistente da primeira configuração (ideia 97), depois do PIN criado: o que a TV já tem, e abrir na TV
            // a tela de idioma do Android (o launcher segue o idioma do sistema, nunca um fixo).
            "/api/chave/lista" -> if (req.metodo == "GET") return json(200, chavesEstado(host))
            "/api/chave/cadastrar" -> if (req.metodo == "POST") return cadastrarChave(req, token, host, ip)
            "/api/chave/apagar" -> if (req.metodo == "POST") return apagarChave(req, token, ip)
            "/api/primeira/tv" -> if (req.metodo == "GET") return json(200, primeiraEstado())
            "/api/primeira/idioma-tv" -> if (req.metodo == "POST") return abrirIdiomaNaTv(ip)
            // Degrau 2, item 6: o celular guia o "modo completo" (teclas, opções do desenvolvedor, parear, dono).
            "/api/primeira/poderes" -> when (req.metodo) {
                "GET" -> return json(200, PrimeiraPoderes.json(estadoPoderes()))
                "POST" -> return fazerPoderNaTv(req, ip)
            }
            "/api/config" -> when (req.metodo) {
                "GET" -> return json(200, PainelConfig.estado(app))
                "POST" -> {
                    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
                    return when (val r = PainelConfig.mudar(app, o, sessoes.elevada(token)) { PainelArmazem.registrar(app, ip, it, "ok") }) {
                        is PainelConfig.Resultado.Ok -> {
                            if (o.optString("campo") == "pinPainel") pinTrocadoPeloPainel(token)
                            json(200, r.estado)
                        }
                        is PainelConfig.Resultado.Erro -> erro(400, r.mensagem)
                        PainelConfig.Resultado.PrecisaPin -> precisaPin()
                    }
                }
            }
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
                        .put("foto", FotoPerfil.arquivo(app, c)?.lastModified() ?: 0L).put("manual", FotoPerfil.manual(app, c))
                        .put("nome", Perfil.nomeGuardado(app, c) ?: ""))
                }))
            // Nome do perfil (17/11/2026): o que a TV mostra na bolinha e nos avisos, no lugar do usuário do e-mail.
            "/api/perfil/nome" -> if (req.metodo == "POST") {
                val o = corpoJson(req) ?: return erro(400, "corpo inválido")
                val conta = Perfil.contas(app).firstOrNull { it.equals(o.optString("conta"), ignoreCase = true) }
                    ?: return erro(404, "essa conta não está nesta TV")
                Perfil.definirNome(app, conta, o.optString("nome"))
                PainelArmazem.registrar(app, ip, "nome do perfil", "ok")   // sem o e-mail nem o nome no registro
                return json(200, JSONObject().put("ok", true).put("nome", Perfil.nome(app, conta)))
            }
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
                // Estudo do painel (PR 5): a prateleira Congelados dos Apps e a aba Sofy desfazem um congelamento.
                // Descongelar só devolve o app como estava (Congelar.kt: pm enable, dados guardados), então não pede PIN.
                "descongelar" -> if (req.metodo == "POST") return acaoApp(ip, "descongelar", pacote) {
                    if (Congelar.definir(app, pacote, false)) null else "a TV não conseguiu descongelar (o shell próprio está ligado?)"
                }
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
        "/i18n.js" to ("i18n.js" to "text/javascript; charset=utf-8"),   // ideia 76: escolhe a língua do navegador
        "/i18n-en.js" to ("i18n-en.js" to "text/javascript; charset=utf-8"),   // as 14 línguas do app (17/11/2026)
        "/i18n-es.js" to ("i18n-es.js" to "text/javascript; charset=utf-8"),
        "/i18n-fr.js" to ("i18n-fr.js" to "text/javascript; charset=utf-8"),
        "/i18n-de.js" to ("i18n-de.js" to "text/javascript; charset=utf-8"),
        "/i18n-it.js" to ("i18n-it.js" to "text/javascript; charset=utf-8"),
        "/i18n-pl.js" to ("i18n-pl.js" to "text/javascript; charset=utf-8"),
        "/i18n-ru.js" to ("i18n-ru.js" to "text/javascript; charset=utf-8"),
        "/i18n-tr.js" to ("i18n-tr.js" to "text/javascript; charset=utf-8"),
        "/i18n-ar.js" to ("i18n-ar.js" to "text/javascript; charset=utf-8"),
        "/i18n-hi.js" to ("i18n-hi.js" to "text/javascript; charset=utf-8"),
        "/i18n-id.js" to ("i18n-id.js" to "text/javascript; charset=utf-8"),
        "/i18n-ja.js" to ("i18n-ja.js" to "text/javascript; charset=utf-8"),
        "/i18n-ko.js" to ("i18n-ko.js" to "text/javascript; charset=utf-8"),
        "/i18n-zh.js" to ("i18n-zh.js" to "text/javascript; charset=utf-8"),
        "/style.css" to ("style.css" to "text/css; charset=utf-8"),
        "/extras.js" to ("extras.js" to "text/javascript; charset=utf-8"),
        "/ui.js" to ("ui.js" to "text/javascript; charset=utf-8"),   // casca do painel: navegação, Início, avisos
        "/busca.js" to ("busca.js" to "text/javascript; charset=utf-8"),   // busca rápida (Ctrl+K)
        "/navigation.js" to ("navigation.js" to "text/javascript; charset=utf-8"),   // índice da página e aba Tudo
        "/shortcuts.js" to ("shortcuts.js" to "text/javascript; charset=utf-8"),   // Seus atalhos, "Conversa com" e Desfazer
        "/sofy-page.js" to ("sofy-page.js" to "text/javascript; charset=utf-8"),   // aba Sofy: recados, porquê e Desfazer
        "/arquivos.js" to ("arquivos.js" to "text/javascript; charset=utf-8"),   // prévia e organizar arquivos
        "/controle.js" to ("controle.js" to "text/javascript; charset=utf-8"),   // digitar ao vivo e touchpad
        "/chave.js" to ("chave.js" to "text/javascript; charset=utf-8"),   // ideia 99: entrar com a digital
        "/emprestimo.js" to ("emprestimo.js" to "text/javascript; charset=utf-8"),   // shell emprestado (Shizuku)
        "/addons.js" to ("addons.js" to "text/javascript; charset=utf-8"),   // addons no formato do Stremio (16/11/2026)
        "/storage.js" to ("storage.js" to "text/javascript; charset=utf-8"),   // pen drive e armazenamento (20/11/2026)
        "/jsqr.js" to ("jsqr.js" to "text/javascript; charset=utf-8"),   // leitor de QR (jsQR 1.4.0, Apache 2.0) para o celular sem BarcodeDetector
        "/primeira.js" to ("primeira.js" to "text/javascript; charset=utf-8"),   // ideia 97: assistente da 1ª configuração
        "/certificado.js" to ("certificado.js" to "text/javascript; charset=utf-8"),   // cartão "Instalar como app (https)"
        "/cadeado.js" to ("cadeado.js" to "text/javascript; charset=utf-8"),   // leva ao https quem já confia (16/11/2026)
        // "Adicionar à tela inicial" no celular: nome, ícone e atalhos (10/11/2026).
        "/manifest.webmanifest" to ("manifest.webmanifest" to "application/manifest+json; charset=utf-8"),
        "/icone-192.png" to ("icone-192.png" to "image/png"),
        "/icone-512.png" to ("icone-512.png" to "image/png"),
        "/icone-mascaravel.png" to ("icone-mascaravel.png" to "image/png"),
        "/icone-apple.png" to ("icone-apple.png" to "image/png"),
        // Favicon da marca (versão simplificada para tamanho pequeno, 20/11/2026). Nomes novos de propósito: o navegador
        // guarda o ícone da aba pelo endereço e não pedia de novo o icone-192.png (o Brave seguia com um ícone antigo).
        "/sofaly-favicon.svg" to ("sofaly-favicon.svg" to "image/svg+xml"),
        "/lock.svg" to ("lock.svg" to "image/svg+xml"),   // cadeado dos apps protegidos (style.css; a CSP não aceita data:)
        "/sofaly-favicon-32.png" to ("sofaly-favicon-32.png" to "image/png"),
        "/sofaly-favicon-180.png" to ("sofaly-favicon-180.png" to "image/png"),
        "/favicon.ico" to ("sofaly-favicon-32.png" to "image/png"),   // o navegador pede sozinho; sem isso, 404 no registro
        "/jmuxer.min.js" to ("jmuxer.min.js" to "text/javascript; charset=utf-8"),
        "/ia.js" to ("ia.js" to "text/javascript; charset=utf-8"),   // aba "Conectar uma IA" (10/11/2026)
        "/sofy.js" to ("sofy.js" to "text/javascript; charset=utf-8"),   // a Sofy desenhada em SVG (20/11/2026)
        "/ideias.js" to ("ideias.js" to "text/javascript; charset=utf-8"),   // ideias de 12/11/2026 (aba Extras)
        "/cuidar.js" to ("cuidar.js" to "text/javascript; charset=utf-8"),   // Cuidar de longe (ideias 77-79)
        "/sw.js" to ("sw.js" to "text/javascript; charset=utf-8"),   // avisos no celular (Web Push): o service worker
        // Fonte Manrope (a mesma da TV), só as letras latinas: 12 KB cada (visual de 13/11/2026).
        "/manrope-400.woff2" to ("manrope-400.woff2" to "font/woff2"),
        "/manrope-600.woff2" to ("manrope-600.woff2" to "font/woff2"),
        // Nunito (a letra da marca Sofaly), só títulos e o nome; variável, letras latinas (16/11/2026).
        "/nunito.woff2" to ("nunito.woff2" to "font/woff2"),
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
    internal fun cookieSessao(token: String) = mapOf("Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
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
            // Alto contraste da TV vale também no painel (e no app do celular, que mostra o painel): estudo global, ideia 3.
            o.put("altoContraste", AltoContraste.ligado(app))
            // Ideia 15 (estudo global 20/11): o teclado da TV está aberto? O controle do celular (app) pergunta isto a cada
            // poucos segundos e, quando é true, abre o próprio teclado: digitar senha e busca no celular, não com a cruz.
            o.put("pedeTexto", TecladoCelular.tecladoNaTela())
            val papel = sessoes.papel(token) ?: PapeisPainel.Papel.DONO
            o.put("papel", papel.chave).put("paginas", JSONArray(PapeisPainel.paginas(papel)))
        }
        return json(200, o)
    }

    // ---------------------------------------------------------------- eventos ao vivo (10/11/2026)

    internal const val MAX_EVENTOS = 2                   // janelas do painel recebendo ao mesmo tempo; a 3ª cai no polling
    internal const val EVENTOS_DURACAO_MS = 5 * 60_000L  // depois disso fecha; o navegador (EventSource) reconecta sozinho
    internal const val EVENTOS_PASSO_MS = 1_500L
    internal val eventosAbertos = java.util.concurrent.atomic.AtomicInteger(0)
    // Fluxo mais novo de cada sessão: abrir de novo (a página voltou a ficar visível) encerra o anterior dessa sessão,
    // em vez de esperar o ping descobrir que a conexão velha morreu e ocupar uma das [MAX_EVENTOS] vagas até lá.
    internal val eventosDaSessao = java.util.concurrent.ConcurrentHashMap<String, Any>()




    /** Senhas da unidade de rede já conferidas (ver DavLogica.CacheSenha): um PBKDF2 a cada 15 min, não a cada pedido. */
    internal val senhasDav = DavLogica.CacheSenha({ System.currentTimeMillis() })






    // ---------------------------------------------------------------- digital do celular (ideia 99)












    // ---------------------------------------------------------------- papéis e convites (elevação 18.1, PapeisPainel)

    @Synchronized internal fun convites() = PapeisPainel.validos(PapeisPainel.deJson(prefsPapeis().getString("convites", null)), System.currentTimeMillis())
    @Synchronized internal fun salvarConvites(l: List<PapeisPainel.Convite>) { prefsPapeis().edit().putString("convites", PapeisPainel.paraJson(l)).commit() }





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
        // Ocultar o que a ficha não diz "seguro" (app de sistema, essencial, sem informação) também pede o PIN (20/11/2026).
        if (oculto && !sessoes.elevada(token) && MenuApp.ocultarPedePin(PainelApps.risco(app, pacote) ?: "sem informação")) return precisaPin()
        // Fase 3 do Observador: as conferências de ANTES; 10 min depois ele confere de novo e desfaz se algo parou.
        val antes = if (oculto) runCatching { ObserverTv.beforeFreeze(app) }.getOrNull() else null
        val motivo = PainelApps.ocultar(app, pacote, oculto)
        if (motivo == null && antes != null) runCatching { ObserverTv.startTrial(app, pacote, antes) }
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





    // ---------------------------------------------------------------- backup noturno (BackupNoturno.kt)




    // ---------------------------------------------------------------- conta do Google Drive (DriveBackup.kt)

    /** O login do Drive começado pelo painel (um por vez: começar outro encerra este). Fica fora da lista do conector. */
    @Volatile internal var loginDrive: LoginGoogle.Sessao? = null







    // ---------------------------------------------------------------- fase 2: permissões

    internal fun corpoJson(req: Requisicao): JSONObject? = try { JSONObject(req.corpoTexto()) } catch (e: Exception) { null }

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

    /**
     * Ideia 17 (estudo global 20/11): toca na TV um vídeo ou música que está nos arquivos dela (o painel manda logo
     * depois de enviar o que a pessoa arrastou). Por quê: mostrar o vídeo da viagem era copiar para a TV e procurar o
     * arquivo com a cruz. Como: só arquivo de vídeo/áudio da lista de prévia, aberto no nosso player (VodActivity),
     * pelo mesmo caminho file:///storage/ que a TV já aceita para pendrive (Pastas.arquivoLocalPermitido).
     */
    private fun tocarArquivo(req: Requisicao, ip: String): Resposta {
        semAcesso()?.let { return it }
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        val f = PainelArquivos.paraBaixar(o.optString("caminho"))
        val tipo = PainelHttp.tipoPrevia(f.name) ?: return erro(415, "esse tipo de arquivo não toca na TV")
        if (!tipo.startsWith("video/") && !tipo.startsWith("audio/")) return erro(415, "só vídeo ou música tocam na TV; foto vai como recado")
        val url = android.net.Uri.fromFile(f).toString()
        if (!Pastas.arquivoLocalPermitido(url)) return erro(403, "esse lugar não pode ser tocado")
        AutoDesligar.atividade()
        VodActivity.abrirUrlEm(app, url, f.nameWithoutExtension, 0L)
        PainelArmazem.registrar(app, ip, "tocar na TV ${f.name.take(80)}", "ok")
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
        // "existe": false na versão da Play enxuta (PlayPolicy.Power.LIVE_TV_LISTS): o painel esconde a seção.
        return JSONObject().put("contas", lista).put("maximo", 8)
            .put("existe", PlayPolicy.allowed(PlayPolicy.Power.LIVE_TV_LISTS, BuildConfig.PLAY))
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
                Gatilhos.disparar(app, Gatilhos.Evento.RECADO_CHEGOU)   // elevação 10.6
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
        Gatilhos.disparar(app, Gatilhos.Evento.RECADO_CHEGOU)   // elevação 10.6
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
        val c = app.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        return JSONObject()
            .put("fixo", fixo?.let { JSONObject().put("nome", it.nome).put("cep", it.cep).put("numero", it.numero).put("descricao", LocalClima.descrever(it)) } ?: JSONObject.NULL)
            .put("usado", if (c.contains("lat")) JSONObject().put("lat", c.getFloat("lat", 0f).toDouble()).put("lon", c.getFloat("lon", 0f).toDouble()) else JSONObject.NULL)
    }

    /** {"cep","numero"} cadastra o endereço; {"cidade"} cadastra uma cidade de qualquer país (ideia 40); {"automatico":true} apaga. */
    private fun mudarClima(req: Requisicao, ip: String): Resposta {
        val o = corpoJson(req) ?: return erro(400, "JSON inválido")
        when {
            o.optBoolean("automatico", false) -> {
                LocalClima.limpar(app)
                PainelArmazem.registrar(app, ip, "clima: localização automática", "ok")
            }
            o.optString("cidade").isNotBlank() && o.optString("cep").isBlank() -> {
                val lugar = LocalClima.definirPorCidade(app, o.optString("cidade"))
                    .getOrElse { return erro(400, it.message ?: "não achei essa cidade") }
                PainelArmazem.registrar(app, ip, "clima: cidade \"${lugar.nome}\"", "ok")
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
    @Volatile internal var certificadoPendente: Pair<String, String>? = null










    // ---------------------------------------------------------------- Claude pela internet (MCP + túnel Tailscale)


}

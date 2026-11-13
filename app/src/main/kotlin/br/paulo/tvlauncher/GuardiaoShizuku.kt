package br.paulo.tvlauncher

import android.app.AlarmManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guardião do shell próprio (cliente ADB Wi-Fi embutido, uid 2000). O nome é histórico: até 12/11/2026 ele também subia
 * e mantinha de pé o servidor do Shizuku embutido, que saiu (o APK não instalava onde o app Shizuku já existia, por
 * declarar as mesmas permissões, e nenhum app da Sala usava o servidor). Os comandos do launcher nunca passaram por ele:
 * sempre foram direto pelo adb ([comando] -> [ServidorShizuku.executar]).
 *
 *  - [garantir]: se falta alguma permissão que só o shell concede, espera o Wi-Fi e a Depuração sem fio, conecta no
 *    adbd local, confere com `id` e roda a autocura. Tenta de novo com esperas crescentes (logo após o boot a TV fica
 *    sobrecarregada e o adbd troca de porta: medido em 04/11/2026). Só age se o shell já foi pareado alguma vez.
 *  - [vigiar]: uma vez por processo, fica de olho em dois gatilhos: o Wi-Fi voltou, e um relógio a cada 10 min.
 *
 * Tudo que fala com o adb passa pelas filas daqui: o gerente ADB é único (singleton) e duas threads mexendo na
 * mesma conexão derrubavam uma à outra. Desde 10/11/2026 são DUAS pistas (ver [PistaAdb]): a lenta ([fila]) é a única
 * que conecta/derruba a conexão; a rápida ([filaRapida]) só abre comandos curtos na conexão que já existe, para uma
 * tecla não esperar atrás de um comando de 2 min.
 *
 * Caminho de conexão: primeiro o autoConnect da libadb (mDNS _adb-tls-connect, porta que muda a cada boot); se falhar,
 * a porta fixa 5555 do modo TCP do adb (127.0.0.1:5555), que esta TV anuncia (_adb._tcp). Ver [conectar].
 */
object GuardiaoShizuku {
    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "GuardiaoShizuku") }
    private val filaRapida = Executors.newSingleThreadExecutor { r -> Thread(r, "GuardiaoShizuku-rapida") }
    private val pistas = FilaDuasPistas(fila, filaRapida)   // FilaComPrazo nas duas: pedido vencido não roda mais
    // Palpite (sem trava) de se a conexão está de pé: m.isConnected usa a trava da libadb, que fica presa até 15 s
    // durante uma reconexão; o palpite deixa escolher a pista sem travar quem chamou. A pista rápida confere de verdade.
    @Volatile private var conectadoPalpite = true   // otimista: a rápida confere m.isConnected e desvia se não houver
    private val conferindo = AtomicBoolean(false)   // uma conferência da conexão na pista lenta por vez
    // Acorda o guardião no meio das esperas entre tentativas (ex.: acabou de parear, não precisa esperar 2 min).
    private val despertador = Semaphore(0)
    private val trabalhando = AtomicBoolean(false)
    private var vigiando = false
    private val principal = Handler(Looper.getMainLooper())
    private const val PERIODO_MS = 10 * 60_000L
    private val ESPERAS_S = listOf(0, 10, 30, 60, 120)   // antes de cada tentativa

    private fun anotar(ctx: Context, t: String) = ServidorShizuku.anotar(ctx, "[guardiao] $t")

    /**
     * Garante o shell e a autocura. Pode ser chamado de qualquer thread; o trabalho roda na fila do guardião.
     * [mesmoSemFalta] = conecta e roda a autocura mesmo sem nada faltando e mesmo sem pareamento anterior (INICIAR).
     */
    fun garantir(ctx: Context, motivo: String, mesmoSemFalta: Boolean = false) {
        if (!Config.SHELL_AUTOMATICO) return
        val app = ctx.applicationContext
        if (!mesmoSemFalta && !SaudeAcesso.shellEsperado(app)) return   // nunca pareado: não liga a depuração de ninguém
        if (!mesmoSemFalta && !precisaCurar(app)) return                  // nada a curar: não toca no adb
        if (!trabalhando.compareAndSet(false, true)) return               // já tem uma tentativa em curso
        fila.execute {
            try { executar(app, motivo, forcar = mesmoSemFalta) } catch (e: Throwable) {
                anotar(app, "erro inesperado: ${e.javaClass.simpleName}: ${e.message}")
            } finally { trabalhando.set(false) }
        }
    }

    /**
     * Roda UM comando no nosso shell (uid 2000), na mesma fila do guardião (uma conversa com o adb por vez), e espera
     * até 40 s. Usado pelo painel no navegador para o que o Device Owner não alcança (appops). Só para comandos
     * MONTADOS PELO CÓDIGO com partes já validadas; a exceção é a ferramenta executar_shell do MCP (decisão do dono, 09/11/2026). Devolve a saída, ou null sem shell.
     */
    fun comando(ctx: Context, cmd: String, limiteMs: Long = 20_000): String? {
        val app = ctx.applicationContext
        // FilaComPrazo (auditoria 10/11/2026): se o prazo acabar com o comando ainda na fila, ele é DESCARTADO. Antes ele
        // rodava minutos depois (tecla do painel, executar_shell do MCP), quando quem pediu já tinha recebido "falhou".
        // minimoMs = limiteMs: só começa se ainda der tempo de o comando terminar antes de o chamador desistir.
        val pista = PistaAdb.escolher(PistaAdb.Pedido.COMANDO, cmd, limiteMs, conectadoPalpite)
        val saida = pistas.rodar(
            pista, limiteMs + 20_000, minimoMs = limiteMs,
            aoFalhar = { e -> anotar(app, "comando do painel falhou: ${e.javaClass.simpleName}") },
            aoDesviar = { conectadoPalpite = false },
            naRapida = {
                val m = AdbConnectionManager.obter(app)
                // Sem conexão a rápida não conecta (isso é da lenta): devolve o pedido, que vai para a lenta.
                if (!m.isConnected) FilaDuasPistas.Rapida.Desviar
                else {
                    Privilegio.religarDepuracaoSemFio(app, respeitarEspera = true)
                    val r = ServidorShizuku.executar(app, m, cmd, limiteMs, gerenciaConexao = false)
                    if (r == null) conferirConexao(app)   // falhou: a lenta confere (e reconecta se caiu)
                    FilaDuasPistas.Rapida.Feito(r)
                }
            },
            naLenta = {
                Privilegio.religarDepuracaoSemFio(app, respeitarEspera = true)
                ServidorShizuku.executar(app, AdbConnectionManager.obter(app), cmd, limiteMs)
            },
        )
        if (saida != null) { conectadoPalpite = true; SaudeAcesso.registrarShell(app, true) }   // respondeu = o shell está vivo (SaudeAcesso.kt)
        return saida
    }

    /**
     * A pista rápida não derruba a conexão quando um comando falha (só a lenta mexe nela). Então ela pede aqui uma
     * conferência na lenta: um `echo` curto; se a conexão estiver quebrada, o executar normal derruba e a próxima
     * chamada reconecta. Uma conferência por vez.
     */
    private fun conferirConexao(app: Context) {
        if (!conferindo.compareAndSet(false, true)) return
        pistas.soltar(PistaAdb.Pista.LENTA) {
            try { conectadoPalpite = ServidorShizuku.executar(app, AdbConnectionManager.obter(app), "echo ok", 5_000) != null }
            catch (_: Throwable) { conectadoPalpite = false }
            finally { conferindo.set(false) }
        }
    }

    /**
     * Manutenção noturna (ManutencaoNoturna.kt): conecta, confere o shell com `id` e roda a autocura, na pista LENTA
     * (a única que conecta). Devolve o que foi consertado (lista vazia = nada faltava), ou null se o shell não respondeu.
     */
    fun verificarECurar(ctx: Context, limiteMs: Long = 90_000): List<String>? {
        val app = ctx.applicationContext
        val r = pistas.rodar(PistaAdb.Pista.LENTA, limiteMs, aoFalhar = { e -> anotar(app, "verificar falhou: ${e.javaClass.simpleName}") },
            naRapida = { FilaDuasPistas.Rapida.Desviar }) {
            Privilegio.religarDepuracaoSemFio(app, respeitarEspera = true)
            val m = AdbConnectionManager.obter(app)
            if (!conectar(app, m, 0)) return@rodar null
            if (ServidorShizuku.executar(app, m, "id").isNullOrBlank()) return@rodar null
            conectadoPalpite = true
            autocurar(app, m)
        }
        SaudeAcesso.registrarShell(app, r != null)
        return r
    }

    /**
     * Pareia (código de 6 dígitos) pela pista RÁPIDA e espera até [prazoMs]. O pareamento usa a chave, não a conexão,
     * e o código vence em segundos: não pode esperar atrás das esperas da reconexão. Antes o PareamentoNaTv pareava
     * por fora da fila. [aoFalhar] recebe o motivo (para mostrar na janela). Dando certo, acorda o guardião.
     */
    fun parear(ctx: Context, porta: Int, codigo: String, prazoMs: Long = 30_000, aoFalhar: (String) -> Unit = {}): Boolean {
        val app = ctx.applicationContext
        val pista = PistaAdb.escolher(PistaAdb.Pedido.PAREAR)
        val ok = pistas.rodar(
            pista, prazoMs,
            aoFalhar = { e ->
                aoFalhar(if (e is java.util.concurrent.TimeoutException) "o adb está ocupado; tente de novo"
                         else "${e.javaClass.simpleName}: ${(e.cause ?: e).message}")
            },
            naRapida = { FilaDuasPistas.Rapida.Feito(parearAgora(app, porta, codigo, aoFalhar)) },
            naLenta = { parearAgora(app, porta, codigo, aoFalhar) },
        ) == true
        if (ok) despertador.release()
        return ok
    }

    private fun parearAgora(app: Context, porta: Int, codigo: String, aoFalhar: (String) -> Unit): Boolean = try {
        AdbConnectionManager.obter(app).pair("127.0.0.1", porta, codigo).also { anotar(app, "pair(127.0.0.1:$porta) => $it") }
    } catch (e: Throwable) {
        anotar(app, "pair FALHOU: ${e.javaClass.simpleName}: ${e.message}")
        aoFalhar("${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** Usado pelo PareamentoReceiver: pareia (se veio código) e então garante, tudo na mesma fila. */
    fun parearEGarantir(ctx: Context, codigo: String?, porta: Int) {
        val app = ctx.applicationContext
        if (codigo == null || porta <= 0) { garantirMesmoComBinder(app, "PAREAR"); return }
        // Pareia na pista rápida (o receiver não pode travar) e SÓ DEPOIS garante: a ordem de antes continua.
        pistas.soltar(PistaAdb.escolher(PistaAdb.Pedido.PAREAR)) {
            if (parearAgora(app, porta, codigo) {}) despertador.release()
            garantirMesmoComBinder(app, "PAREAR")
        }
    }

    /** Como [garantir], mas roda o `id` mesmo sem nada faltando (o PAREAR é um teste e quer ver o id). */
    private fun garantirMesmoComBinder(app: Context, motivo: String) {
        if (!trabalhando.compareAndSet(false, true)) return
        fila.execute {
            try { executar(app, motivo, forcar = true) } finally { trabalhando.set(false) }
        }
    }

    private fun executar(app: Context, motivo: String, forcar: Boolean) {
        anotar(app, "inicio ($motivo)")
        despertador.drainPermits()   // um "acorda" antigo não vale para esta rodada
        var idFalhouConectado = false   // o id já falhou uma vez nesta rodada com a conexão de pé
        for ((n, espera) in ESPERAS_S.withIndex()) {
            // Espera, mas acorda antes se alguém parear no meio (ver [parear]).
            if (espera > 0 && despertador.tryAcquire(espera * 1000L, TimeUnit.MILLISECONDS)) anotar(app, "acordado antes da espera acabar")
            if (!forcar && !precisaCurar(app)) { anotar(app, "nada a curar"); return }
            if (!esperarWifi(app)) { anotar(app, "tentativa ${n + 1}: sem Wi-Fi"); continue }
            Privilegio.religarDepuracaoSemFio(app, respeitarEspera = true)
            val m = try { AdbConnectionManager.obter(app) } catch (e: Throwable) {
                anotar(app, "gerente ADB falhou: ${e.javaClass.simpleName}: ${e.message}"); return
            }
            if (!conectar(app, m, n + 1)) continue
            val id = ServidorShizuku.executar(app, m, "id")
            if (id.isNullOrBlank()) {
                anotar(app, "tentativa ${n + 1}: id sem resposta")
                // Derrubar mata também o comando da pista rápida (conexão compartilhada). Só derruba se a conexão já caiu
                // ou se o id falhou de novo com ela "de pé" (aí ela está quebrada de verdade e precisa reconectar).
                if (!m.isConnected || idFalhouConectado) try { m.disconnect() } catch (_: Exception) {}
                idFalhouConectado = !idFalhouConectado && m.isConnected
                continue
            }
            idFalhouConectado = false
            anotar(app, "shell PROPRIO diz: $id")
            conectadoPalpite = true
            SaudeAcesso.registrarShell(app, true)
            val curou = autocurar(app, m)
            anotar(app, "OK ($motivo)" + if (curou.isEmpty()) "" else " - concedido: ${curou.joinToString()}")
            return
        }
        anotar(app, "desisti depois de ${ESPERAS_S.size} tentativas ($motivo)")
    }

    /** true se falta algo que só o shell concede (hoje: a janela flutuante). Faz o guardião passar no adb uma vez. */
    private fun precisaCurar(app: Context) = !android.provider.Settings.canDrawOverlays(app) ||
        Diagnostico.PERMISSOES_SHELL.any { app.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED } ||
        TravaSemDono.falta(app)

    /**
     * Com o shell (uid 2000) na mão, o launcher se concede o que o Device Owner não alcança e antes exigia o PC:
     *  - SYSTEM_ALERT_WINDOW (janela flutuante do pareamento sem PC, ver PareamentoNaTv);
     *  - WRITE_SECURE_SETTINGS (religar acessibilidade e depuração sem fio, DNS) — caso tenha sido revogada;
     *  - GET_USAGE_STATS (ordenar apps por uso);
     *  - READ_LOGS e DUMP (tela de Diagnóstico: ler os crashes de outros apps e por que o Android os fechou);
     *  - administrador do aparelho (trava de "Forçar parada" sem Device Owner, ver TravaSemDono).
     *  Só escreve se ainda não estiver concedido.
     */
    private fun autocurar(app: Context, m: AdbConnectionManager): List<String> {
        val p = app.packageName
        // O que precisou conceder E pegou (a manutenção noturna mostra no resumo). Revisão 10/11/2026: confere de novo
        // depois do comando; o que o Android recusou não entra como "consertado".
        val feito = ArrayList<String>()
        fun tem(perm: String) = app.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!android.provider.Settings.canDrawOverlays(app)) {
            ServidorShizuku.executar(app, m, "appops set $p SYSTEM_ALERT_WINDOW allow")
            if (android.provider.Settings.canDrawOverlays(app)) feito += "SYSTEM_ALERT_WINDOW"
        }
        if (!tem("android.permission.WRITE_SECURE_SETTINGS")) {
            ServidorShizuku.executar(app, m, "pm grant $p android.permission.WRITE_SECURE_SETTINGS")
            if (tem("android.permission.WRITE_SECURE_SETTINGS")) feito += "WRITE_SECURE_SETTINGS"
        }
        val uso = ServidorShizuku.executar(app, m, "appops get $p GET_USAGE_STATS") ?: ""
        if (!uso.contains("allow")) {
            ServidorShizuku.executar(app, m, "appops set $p GET_USAGE_STATS allow")
            if (ServidorShizuku.executar(app, m, "appops get $p GET_USAGE_STATS")?.contains("allow") == true) feito += "GET_USAGE_STATS"
        }
        for (perm in Diagnostico.PERMISSOES_SHELL)
            if (!tem(perm)) {
                ServidorShizuku.executar(app, m, "pm grant $p $perm")
                if (tem(perm)) feito += perm.substringAfterLast('.')
            }
        VirarDono.recuperar(app)   // uma tentativa de virar dono morreu com o Google desativado? reativa
        // Trava de "Forçar parada"/"Desinstalar" sem Device Owner: o launcher vira administrador comum (TravaSemDono.kt).
        if (TravaSemDono.falta(app)) {
            ServidorShizuku.executar(app, m, TravaSemDono.comando(app))
            if (!TravaSemDono.falta(app)) feito += "administrador do aparelho"
        }
        anotar(app, "autocura: sobreposicao=${android.provider.Settings.canDrawOverlays(app)}")
        return feito
    }

    /** Espera até 60 s por um Wi-Fi conectado. */
    private fun esperarWifi(app: Context): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        repeat(60) {
            if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Conecta no adbd do próprio aparelho. 1º: autoConnect (mDNS, TLS) — é o caminho do pareamento. 2º: 127.0.0.1:5555
     * (modo TCP do adb, porta fixa), que não depende de descobrir a porta; só funciona se o adb estiver escutando nela.
     */
    private fun conectar(app: Context, m: AdbConnectionManager, n: Int): Boolean {
        if (m.isConnected) return true
        val tls = try { m.autoConnect(app, 15_000) } catch (e: Throwable) {
            anotar(app, "tentativa $n: autoConnect falhou: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        anotar(app, "tentativa $n: autoConnect (mDNS/TLS) => $tls")
        if (tls) return true
        val tcp = try { m.connect("127.0.0.1", 5555) } catch (e: Throwable) {
            anotar(app, "tentativa $n: 127.0.0.1:5555 falhou: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        anotar(app, "tentativa $n: 127.0.0.1:5555 (modo TCP) => $tcp")
        return tcp
    }

    /** Liga os dois gatilhos do vigia. Só registra uma vez por processo. */
    fun vigiar(ctx: Context) {
        if (!Config.SHELL_AUTOMATICO || vigiando) return
        vigiando = true
        val app = ctx.applicationContext
        // 1. O Wi-Fi voltou: a depuração sem fio volta junto, dá para (re)subir.
        app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { principal.postDelayed({ garantir(app, "Wi-Fi voltou") }, 5000) }
            })
        // 2. Relógio: a cada 10 min confere (barato: se nada falta, não toca no adb). Ver [agendarRelogio].
        agendarRelogio(app)
        anotar(app, "vigiando (Wi-Fi, a cada ${PERIODO_MS / 60_000} min)")
    }

    /**
     * Um "tique" do relógio do vigia: confere a autocura, a VPN, o DNS e se há versão nova.
     */
    private fun tique(app: Context) {
        garantir(app, "periodico")
        Thread { PrivilegioDO.reforcarVpn(app) }.start()   // e a VPN fixa (AdGuard), se o túnel caiu
        Thread { Dns.voltarAoAutomaticoSeQuebrado(app) }.start()   // e o DNS fixo, se parou de responder
        Atualizador.talvezVerificar(app)   // e uma versão nova no GitHub (a cada 6 h, só com a tela apagada)
    }

    /**
     * Agenda o próximo tique (privilegios-7, 10/11/2026). Antes era Handler.postDelayed, que conta o tempo só com o
     * aparelho ACORDADO: com a TV em standby o relógio parava e, ao acordar, ainda faltavam os mesmos minutos.
     * Agora é um alarme do Android do tipo ELAPSED_REALTIME (conta o tempo dormindo) SEM "_WAKEUP": não acorda a TV de
     * madrugada; se venceu enquanto ela dormia, toca assim que ela acordar. Inexato (janela, ver EsperaCrescente) para o
     * Android juntar com outros alarmes. Desde o Android 12 a janela mínima é de 10 min (lido na documentação do
     * setWindow, não medido): no Android 14 da TV o tique vem entre 10 e 20 min. O ouvinte vive no processo, como o Handler antigo (o vigia é por processo).
     * Sem AlarmManager (não deve acontecer), cai no Handler de antes.
     */
    private fun agendarRelogio(app: Context) {
        val proximo = {
            tique(app)
            agendarRelogio(app)
        }
        val am = app.getSystemService(AlarmManager::class.java)
        if (am == null) { principal.postDelayed(proximo, PERIODO_MS); return }
        val (inicio, janela) = EsperaCrescente.janelaRelogio(SystemClock.elapsedRealtime(), PERIODO_MS)
        try {
            am.setWindow(AlarmManager.ELAPSED_REALTIME, inicio, janela, "tvlauncher:guardiao", { proximo() }, principal)
        } catch (e: Exception) {
            anotar(app, "alarme do relógio recusado (${e.javaClass.simpleName}); usando o relógio simples")
            principal.postDelayed(proximo, PERIODO_MS)
        }
    }

    /** true se a Depuração sem fio está ligada (só para diagnóstico na tela de Configurações). */
    fun depuracaoLigada(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
}

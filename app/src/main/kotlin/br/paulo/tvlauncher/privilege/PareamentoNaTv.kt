package br.paulo.tvlauncher.privilege

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.AutorizaShizukuService
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.input.SegurarOk
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Pareamento da depuração sem fio PELA PRÓPRIA TV, sem PC (item 5, 04/11/2026).
 *
 * O problema: o código de 6 dígitos do Android só vale enquanto a tela "Parear dispositivo com código" está aberta
 * (medido hoje: o código da foto expirou e a porta deu ECONNREFUSED assim que a tela fechou). Então não dá para ler o
 * código, voltar ao launcher e digitar. A solução (a mesma ideia do fork thedjchi para TV/VR) é uma JANELA FLUTUANTE do
 * launcher por cima da tela de pareamento do Android:
 *
 *  1. [comecar] abre as Opções do desenvolvedor e mostra a janela, ainda SEM pegar o foco (o controle continua mexendo
 *     nas Configurações, para você chegar em Depuração sem fio › Parear dispositivo com código).
 *  2. Enquanto isso a janela procura por mDNS o serviço _adb-tls-pairing._tcp DESTE aparelho (só existe com aquela tela
 *     aberta). Achou: mostra a porta e passa a pegar o foco (teclado de 0 a 9 operado pelo D-pad).
 *  3. "Parear": pair(127.0.0.1, porta, código) pelo gerente ADB e, dando certo, o guardião confere o shell e roda a autocura.
 *  4. (13/11/2026) Achada a porta, o launcher LÊ o código na tela das Configurações pelo serviço de acessibilidade
 *     ([lerCodigoDaTela]). Se não achar em ~6 s, fica o teclado para digitar, como antes.
 *  5. (18/11/2026, depuração) Parear dá o shell (uid 2000): só com OK numa tecla FÍSICA do controle em "Parear"
 *     ([dispatchKeyEvent] do painel, mesma regra do consentimento do Cuidar de longe). Tecla injetada (painel, MCP,
 *     adb `input`) ou clique de acessibilidade não pareiam. A janela fecha com BACK e sozinha em [PRAZO_MS] (o mDNS para).
 *
 * Precisa da permissão de sobreposição (SYSTEM_ALERT_WINDOW). O guardião a concede sozinho pelo shell (appops) quando o
 * Shizuku sobe; ver [GuardiaoShizuku]. Sem ela, [podeMostrar] é false e a tela de Configurações avisa.
 */
object PareamentoNaTv {
    private const val SERVICO = "_adb-tls-pairing._tcp"
    private const val PRAZO_MS = 3 * 60_000L
    private var botaoParear: View? = null
    // Aparelhos de entrada que existiam quando a janela abriu: tecla de aparelho criado depois não pareia
    // (CuidarDeLonge.aparelhoDeAntes; aqui o shell não fica bloqueado, então esta é a barreira).
    private var aparelhosNaAbertura: Set<Int>? = null
    private var appCtx: Context? = null
    private val prazo = Runnable { appCtx?.let { fechar(it) } }
    private val principal = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    private var janela: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var ouvinte: NsdManager.DiscoveryListener? = null
    private var porta = -1
    private val codigo = StringBuilder()
    private lateinit var linhaPorta: TextView
    private lateinit var linhaCodigo: TextView
    private lateinit var linhaEstado: TextView

    /**
     * A janela é do SERVIÇO DE ACESSIBILIDADE do launcher (TYPE_ACCESSIBILITY_OVERLAY), não uma janela flutuante comum:
     * medido em 04/11/2026, as Opções do desenvolvedor da TV escondem as janelas flutuantes de apps
     * (dumpsys window: mForceHideNonSystemOverlayWindow=true). A janela de acessibilidade não entra nesse bloqueio.
     */
    fun podeMostrar(@Suppress("UNUSED_PARAMETER") ctx: Context) = AutorizaShizukuService.instancia != null

    fun comecar(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val svc = AutorizaShizukuService.instancia ?: return false
        // Janela antiga ainda na tela: só reabre as Configurações. Janela antiga que SUMIU (o serviço de acessibilidade
        // foi religado, e as janelas dele vão junto — medido com o uiautomator): esquece e monta de novo.
        if (janela?.isAttachedToWindow == true) return true
        fechar(app)
        porta = -1; codigo.setLength(0)
        appCtx = app
        aparelhosNaAbertura = runCatching { InputDevice.getDeviceIds().toSet() }.getOrNull()
        mostrarJanela(svc)
        procurarPorta(app)
        principal.postDelayed(prazo, PRAZO_MS)   // esquecida na tela: fecha sozinha e para o mDNS
        try {
            app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { estado(app.getString(R.string.pn_sem_opcoes, e.javaClass.simpleName)) }
        return true
    }

    fun fechar(ctx: Context) {
        val app = ctx.applicationContext
        principal.removeCallbacks(prazo)
        ouvinte?.let { try { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } catch (_: Exception) {} }
        ouvinte = null
        janela?.let { j -> try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {} }
        janela = null; botaoParear = null
    }

    // ---------------------------------------------------------------- janela

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun mostrarJanela(app: Context) {
        fun texto(t: String, tam: Float, cor: Int = Color.WHITE) = TextView(app).apply { text = t; textSize = tam; setTextColor(cor) }
        fun tecla(rotulo: String, aoApertar: () -> Unit) = TextView(app).apply {
            text = rotulo; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPaddingRelative(dp(app, 10), dp(app, 8), dp(app, 10), dp(app, 8))
            setTextColor(Estilo.textoFoco())   // foco branco: o texto escurece (sistema de design)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(Estilo.FOCO) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoApertar() }
        }

        linhaPorta = texto(app.getString(R.string.pn_abra), 15f, 0xCCFFFFFF.toInt())
        linhaCodigo = texto(app.getString(R.string.pn_codigo, "______"), 26f)
        linhaEstado = texto(app.getString(R.string.pn_procurando), 14f, Estilo.ACENTO)

        val teclado = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val linhas = listOf(listOf("1", "2", "3", "4", "5"), listOf("6", "7", "8", "9", "0"))
        var primeira: View? = null
        for (l in linhas) {
            teclado.addView(LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                for (d in l) {
                    val t = tecla(d) { if (codigo.length < 6) { codigo.append(d); atualizarCodigo() } }
                    if (primeira == null) primeira = t
                    addView(t, LinearLayout.LayoutParams(dp(app, 52), dp(app, 48)).apply { setMargins(dp(app, 3), dp(app, 3), dp(app, 3), dp(app, 3)) })
                }
            })
        }
        teclado.addView(LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tecla(app.getString(R.string.pn_apagar)) { if (codigo.isNotEmpty()) { codigo.setLength(codigo.length - 1); atualizarCodigo() } })
            // Sem clique: só o OK físico em [dispatchKeyEvent] pareia (clique de acessibilidade não faz nada).
            addView(tecla(app.getString(R.string.pn_parear)) {}.also { botaoParear = it }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
            addView(tecla(app.getString(R.string.qr_fechar)) { fechar(app) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
        })

        val painel = object : LinearLayout(app) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) fechar(app); return true }
                val b = botaoParear
                if (b != null && b.isFocused && SegurarOk.ehOk(e.keyCode)) {
                    if (e.action == KeyEvent.ACTION_UP) {
                        if (CuidarDeLonge.teclaDeGente(e.deviceId, e.source, KeyCharacterMap.VIRTUAL_KEYBOARD, InputDevice.SOURCE_HDMI) &&
                            CuidarDeLonge.aparelhoDeAntes(e.deviceId, aparelhosNaAbertura)) parear(app)
                        else estado(app.getString(R.string.pn_so_controle))
                    }
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(app, 20), dp(app, 16), dp(app, 20), dp(app, 16))
            background = GradientDrawable().apply { cornerRadius = dp(app, 18).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(texto(app.getString(R.string.pn_titulo), 20f))
            addView(linhaPorta)
            addView(linhaCodigo)
            addView(teclado)
            addView(linhaEstado)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Começa SEM foco: o controle continua nas Configurações até a tela de pareamento abrir.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
            // Enquanto procura, fica embaixo à direita (o menu das Configurações, por onde você navega, fica à esquerda).
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(app, 24); y = dp(app, 24) }
        app.getSystemService(WindowManager::class.java).addView(painel, p)
        janela = painel; params = p
        painel.tag = primeira
    }

    private fun atualizarCodigo() {
        linhaCodigo.text = linhaCodigo.context.getString(R.string.pn_codigo, codigo.toString().padEnd(6, '_'))
    }

    private fun estado(t: String) {
        ServidorShizuku.anotar(janela?.context ?: return, "[pareamento na TV] $t")
        principal.post { if (janela != null) linhaEstado.text = t }
    }

    /** Achou a tela de pareamento aberta: a janela passa a pegar o foco do controle. */
    private fun pegarFoco(@Suppress("UNUSED_PARAMETER") app: Context) {
        val j = janela ?: return
        val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        // Vai para baixo à esquerda: na tela "Parear com o dispositivo" da TV o CÓDIGO fica na metade direita, e a
        // esquerda só tem o título. Medido em 04/11/2026: no canto superior direito a janela cobria o código.
        p.gravity = Gravity.BOTTOM or Gravity.START
        try { j.context.getSystemService(WindowManager::class.java).updateViewLayout(j, p) } catch (_: Exception) {}
        // Código já lido da tela: o foco vai direto para "Parear" (falta só o OK no controle); senão, para o teclado.
        (if (codigo.length == 6) botaoParear else j.tag as? View)?.requestFocus()
    }

    // ---------------------------------------------------------------- porta (mDNS) e pareamento

    private fun enderecosLocais(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }

    @Suppress("DEPRECATION")
    private fun procurarPorta(app: Context) {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val locais = enderecosLocais()
        val o = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, erro: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        // Só a porta DESTE aparelho: na rede há outros com depuração sem fio (outras TVs, celulares).
                        if (host !in locais && host != "127.0.0.1") return
                        principal.post {
                            if (janela == null) return@post
                            // Porta nova = tela de pareamento aberta de novo, com código novo: o velho não vale mais.
                            if (i.port != porta) { codigo.setLength(0); atualizarCodigo() }
                            porta = i.port
                            linhaPorta.text = linhaPorta.context.getString(R.string.pn_achada, porta)
                            estado(app.getString(R.string.pn_porta_achada, porta))
                            lerCodigoDaTela(app, 0)   // antes do foco: a janela ativa ainda é a das Configurações
                            pegarFoco(app)
                        }
                    }
                })
            }
            // A tela de pareamento fechou: a porta morreu com ela. Esquece porta e código e volta a procurar.
            override fun onServiceLost(info: NsdServiceInfo) {
                principal.post {
                    if (janela == null || porta <= 0) return@post
                    porta = -1; codigo.setLength(0); atualizarCodigo()
                    linhaPorta.text = linhaPorta.context.getString(R.string.pn_abra)
                    estado(app.getString(R.string.pn_procurando))
                }
            }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, erro: Int) { estado(app.getString(R.string.pn_mdns_falhou, erro.toString())) }
            override fun onStopDiscoveryFailed(t: String, erro: Int) {}
        }
        try { nsd.discoverServices(SERVICO, NsdManager.PROTOCOL_DNS_SD, o); ouvinte = o } catch (e: Exception) { estado(app.getString(R.string.pn_mdns_falhou, e.javaClass.simpleName)) }
    }

    /** Os textos das janelas que NÃO são do launcher (a tela de pareamento das Configurações). */
    private fun textosDaTela(): List<String> {
        val svc = AutorizaShizukuService.instancia ?: return emptyList()
        val saida = ArrayList<String>()
        fun andar(n: android.view.accessibility.AccessibilityNodeInfo?, prof: Int) {
            if (n == null || prof > 30 || saida.size > 400) return
            n.text?.toString()?.let { saida += it }
            for (i in 0 until n.childCount) andar(n.getChild(i), prof + 1)
        }
        // As janelas vêm do flagRetrieveInteractiveWindows (res/xml do serviço); sem ele, só a janela ativa.
        val raizes = try { svc.windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
            .ifEmpty { listOfNotNull(try { svc.rootInActiveWindow } catch (_: Exception) { null }) }
        for (raiz in raizes) {
            if (raiz.packageName?.toString() == svc.packageName) continue   // a nossa janela tem os dígitos do teclado
            try { andar(raiz, 0) } catch (_: Exception) {}
        }
        return saida
    }

    /** Tenta ler o código até 12 vezes (a cada 0,5 s). Achou e a pessoa não digitou nada: preenche e pareia. */
    private fun lerCodigoDaTela(app: Context, tentativa: Int) {
        if (janela == null || codigo.isNotEmpty() || tentativa >= 12) return
        // Versão da Play enxuta (PlayPolicy): não lê a tela das Configurações; a pessoa digita o código.
        if (!PlayPolicy.allowed(PlayPolicy.Power.READ_PAIRING_CODE, BuildConfig.PLAY)) return
        val achado = CodigoPareamento.achar(textosDaTela())
        if (achado == null) { principal.postDelayed({ lerCodigoDaTela(app, tentativa + 1) }, 500); return }
        codigo.append(achado); atualizarCodigo()
        // Não pareia sozinho: o OK tem que vir de uma pessoa no controle (o foco já vai para "Parear").
        estado(app.getString(R.string.pn_confirme, app.getString(R.string.pn_parear)))
        botaoParear?.requestFocus()
    }

    private fun parear(app: Context) {
        val c = codigo.toString()
        if (porta <= 0) { estado(app.getString(R.string.pn_nao_achei)); return }
        if (c.length != 6) { estado(app.getString(R.string.pn_6)); return }
        estado(app.getString(R.string.pn_pareando))
        val p = porta
        trabalho.execute {
            // Pela fila do adb (pista rápida do GuardiaoShizuku), não mais por fora dela (privilegios-10).
            var erro: String? = null
            val ok = GuardiaoShizuku.parear(app, p, c) { erro = it }
            if (!ok) {
                estado(erro?.let { app.getString(R.string.pn_falhou, it) } ?: app.getString(R.string.pn_recusou))
                // O código que falhou não fica guardado (senão a leitura da tela nunca mais preenche o novo).
                principal.post { codigo.setLength(0); if (janela != null) { atualizarCodigo(); lerCodigoDaTela(app, 0) } }
                return@execute
            }
            estado(app.getString(R.string.pn_pareado))
            GuardiaoShizuku.parearEGarantir(app, null, -1)
            // Confirmação que se vê (Paulo, 17/11/2026): ao parear, o Android fecha sozinho a caixinha do código e
            // parecia erro. Agora um aviso grande diz que deu certo e, depois, se o shell respondeu de verdade.
            AvisoTv.rapido(app, app.getString(R.string.pn_ok_titulo), app.getString(R.string.pn_ok_texto), 8)
            principal.postDelayed({ fechar(app) }, 2500)
            // Confere o shell com um comando real (até ~1 min: o guardião conecta na mesma fila logo depois).
            var shell = false
            for (i in 1..6) {
                if (GuardiaoShizuku.comando(app, "echo ok", 10_000)?.trim() == "ok") { shell = true; break }
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
            }
            if (shell) AvisoTv.rapido(app, app.getString(R.string.pn_ok_titulo), app.getString(R.string.pn_ok_shell), 8)
            else AvisoTv.rapido(app, app.getString(R.string.pn_ok_titulo), app.getString(R.string.pn_ok_lento), 10)
        }
    }
}

/**
 * Um código de pareamento entre os textos da tela: exatamente 6 dígitos (o Android pode separá-los com espaço). Só devolve
 * se houver UM candidato: com dois diferentes, melhor deixar a pessoa digitar do que parear com o errado. Fora do
 * PareamentoNaTv (que cria um Handler ao nascer) para o teste rodar no PC: CodigoPareamentoTest.
 */
object CodigoPareamento {
    fun achar(textos: List<String>): String? =
        textos.map { it.replace(Regex("\\s"), "") }.filter { it.length == 6 && it.all(Char::isDigit) }.distinct().singleOrNull()
}

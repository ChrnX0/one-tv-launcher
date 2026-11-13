package br.paulo.tvlauncher

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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
 *     ([lerCodigoDaTela]) e pareia sozinho. Se não achar em ~6 s, fica o teclado para digitar, como antes.
 *
 * Precisa da permissão de sobreposição (SYSTEM_ALERT_WINDOW). O guardião a concede sozinho pelo shell (appops) quando o
 * Shizuku sobe; ver [GuardiaoShizuku]. Sem ela, [podeMostrar] é false e a tela de Configurações avisa.
 */
object PareamentoNaTv {
    private const val SERVICO = "_adb-tls-pairing._tcp"
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
        mostrarJanela(svc)
        procurarPorta(app)
        try {
            app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { estado("não abri as Opções do desenvolvedor: $e") }
        return true
    }

    fun fechar(ctx: Context) {
        val app = ctx.applicationContext
        ouvinte?.let { try { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } catch (_: Exception) {} }
        ouvinte = null
        janela?.let { j -> try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {} }
        janela = null
    }

    // ---------------------------------------------------------------- janela

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun mostrarJanela(app: Context) {
        fun texto(t: String, tam: Float, cor: Int = Color.WHITE) = TextView(app).apply { text = t; textSize = tam; setTextColor(cor) }
        fun tecla(rotulo: String, aoApertar: () -> Unit) = TextView(app).apply {
            text = rotulo; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(app, 10), dp(app, 8), dp(app, 10), dp(app, 8))
            setTextColor(Estilo.textoFoco())   // foco branco: o texto escurece (sistema de design)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(Estilo.FOCO) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(app, 10).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoApertar() }
        }

        linhaPorta = texto("Abra: Depuração sem fio › Parear dispositivo com código", 15f, 0xCCFFFFFF.toInt())
        linhaCodigo = texto("Código: ______", 26f)
        linhaEstado = texto("procurando a tela de pareamento…", 14f, Estilo.ACENTO)

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
            addView(tecla("Apagar") { if (codigo.isNotEmpty()) { codigo.setLength(codigo.length - 1); atualizarCodigo() } })
            addView(tecla("Parear") { parear(app) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
            addView(tecla("Fechar") { fechar(app) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(app, 8) })
        })

        val painel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 20), dp(app, 16), dp(app, 20), dp(app, 16))
            background = GradientDrawable().apply { cornerRadius = dp(app, 18).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(texto("Parear o launcher (sem PC)", 20f))
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
        linhaCodigo.text = "Código: " + codigo.toString().padEnd(6, '_')
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
        (j.tag as? View)?.requestFocus()
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
                        porta = i.port
                        principal.post {
                            if (janela == null) return@post
                            linhaPorta.text = "Tela de pareamento achada (porta $porta). Digite o código:"
                            estado("porta $porta achada")
                            lerCodigoDaTela(app, 0)   // antes do foco: a janela ativa ainda é a das Configurações
                            pegarFoco(app)
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, erro: Int) { estado("busca mDNS falhou ($erro)") }
            override fun onStopDiscoveryFailed(t: String, erro: Int) {}
        }
        try { nsd.discoverServices(SERVICO, NsdManager.PROTOCOL_DNS_SD, o); ouvinte = o } catch (e: Exception) { estado("busca mDNS: $e") }
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
        val achado = CodigoPareamento.achar(textosDaTela())
        if (achado == null) { principal.postDelayed({ lerCodigoDaTela(app, tentativa + 1) }, 500); return }
        codigo.append(achado); atualizarCodigo()
        estado("código lido da tela")
        parear(app)
    }

    private fun parear(app: Context) {
        val c = codigo.toString()
        if (porta <= 0) { estado("ainda não achei a tela de pareamento"); return }
        if (c.length != 6) { estado("o código tem 6 dígitos"); return }
        estado("pareando…")
        val p = porta
        trabalho.execute {
            // Pela fila do adb (pista rápida do GuardiaoShizuku), não mais por fora dela (privilegios-10).
            var erro: String? = null
            val ok = GuardiaoShizuku.parear(app, p, c) { erro = it }
            if (!ok) { estado(erro?.let { "falhou: $it" } ?: "o Android recusou o código"); return@execute }
            estado("pareado! conferindo o shell…")
            GuardiaoShizuku.parearEGarantir(app, null, -1)
            principal.postDelayed({ fechar(app) }, 4000)
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

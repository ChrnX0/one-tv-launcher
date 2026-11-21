package br.paulo.tvlauncher.input

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.CasaActivity
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.EscolherAppActivity
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.PainelRapidoActivity
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.VigiaTrailer
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.home.Home
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.privilege.JanelaNaFrente

/**
 * Os dois botões de app do controle (Netflix e YouTube) passam a abrir o app que você escolher.
 *  - toque: abre o app salvo para aquele botão;
 *  - segurar 3 s: abre a lista de apps (EscolherAppActivity) para trocar; a escolha fica salva.
 *
 * Como chega aqui: o serviço de acessibilidade (AutorizaShizukuService.onKeyEvent) vê a tecla ANTES do sistema.
 * Devolvendo true, o sistema nunca a recebe: o Netflix não abre sozinho e o YouTube (que o Home do Google tratava) passa a funcionar.
 * Códigos medidos num controle do Chromecast com Google TV em 01/11/2026 (log TvLauncher.Tecla): Netflix = BUTTON_4, YouTube = BUTTON_3.
 */
object BotoesControle {
    private const val TAG = "TvLauncher.Botao"
    private const val SEGURAR_MS = 3000L

    /** Cada botão: código da tecla, nome mostrado na lista e o app padrão (antes de você escolher). */
    class Botao(val tecla: Int, val nome: String, val padrao: String)

    val BOTOES = listOf(
        // Padrão do botão Netflix: o Netflix (ou o que estiver em botao_netflix no pessoal.properties, fora do git).
        Botao(KeyEvent.KEYCODE_BUTTON_4, "Netflix", BuildConfig.BOTAO_NETFLIX),
        Botao(KeyEvent.KEYCODE_BUTTON_3, "YouTube", "com.google.android.youtube.tv"),
    )

    private val principal = Handler(Looper.getMainLooper())
    private var abrirLista: Runnable? = null   // o "segurou 3 s" agendado; null = nenhum botão apertado
    private var listaAberta = false             // true = a lista já abriu neste aperto: soltar não abre app
    private var apertado: Int? = null           // tecla cujo DOWN vimos neste aperto

    fun botao(tecla: Int) = BOTOES.firstOrNull { it.tecla == tecla }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE)

    fun appDo(ctx: Context, b: Botao): String = prefs(ctx).getString("botao_${b.tecla}", b.padrao) ?: b.padrao

    fun salvar(ctx: Context, b: Botao, pacote: String) {
        prefs(ctx).edit().putString("botao_${b.tecla}", pacote).apply()
        Log.i(TAG, "${b.nome} agora abre $pacote")
    }

    /** Chamado para cada tecla. true = a tecla é nossa (o sistema não a vê). */
    fun tratar(ctx: Context, e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_HOME && Home.launcherxAtivo(ctx)) return tratarHome(ctx, e)
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return tratarVoltar(ctx, e)
        // Remap (ideia 6): só teclas livres; coloridas e números só com a nossa HOME na frente (RemapTeclas).
        if (RemapTeclas.livre(e.keyCode)) {
            if (RemapTeclas.capturando) return false   // a tela "aperte a tecla" está esperando por ela
            val cfg = RemapTeclas.da(ctx, e.keyCode)
            if (cfg.isNotEmpty() && (!RemapTeclas.soNaHome(e.keyCode) || JanelaNaFrente.homeNaFrente(ctx)))
                return tratarRemap(ctx, e, cfg)
        }
        val b = botao(e.keyCode) ?: return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                // Aperto novo: agenda a lista para daqui a 3 s. Se soltar antes, o agendamento é cancelado.
                listaAberta = false
                abrirLista?.let { principal.removeCallbacks(it) }
                apertado = b.tecla
                abrirLista = Runnable {
                    listaAberta = true
                    abrirLista = null
                    // try: este código roda no processo da HOME; uma falha aqui não pode derrubar o launcher.
                    try {
                        ctx.startActivity(Intent(ctx, EscolherAppActivity::class.java)
                            .putExtra(EscolherAppActivity.EXTRA_TECLA, b.tecla)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { Log.w(TAG, "não abriu a lista de apps: $e") }
                }.also { principal.postDelayed(it, SEGURAR_MS) }
            }
            KeyEvent.ACTION_UP -> {
                abrirLista?.let { principal.removeCallbacks(it) }
                abrirLista = null
                // Só abre o app se o DOWN deste aperto passou por aqui (ex.: serviço religado no meio do aperto
                // recebe só o UP — antes isso abria o app sozinho).
                if (!listaAberta && apertado == b.tecla) abrirApp(ctx, appDo(ctx, b))
                apertado = null
            }
        }
        return true
    }

    // ---------------------------------------------------------------- remap (ideia 6)

    private var gestos: Gestos? = null          // a máquina da tecla em andamento
    private var teclaGestos = 0
    private var cfgGestos: Map<RemapTeclas.Gesto, String> = emptyMap()
    private var relogio: Runnable? = null

    private fun tratarRemap(ctx: Context, e: KeyEvent, cfg: Map<RemapTeclas.Gesto, String>): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) return true   // repetição de tecla segurada
        val agora = SystemClock.uptimeMillis()
        var g = gestos
        if (g == null || teclaGestos != e.keyCode || (g.livre && e.action == KeyEvent.ACTION_DOWN)) {
            if (e.action != KeyEvent.ACTION_DOWN) return true   // UP sem o DOWN (serviço religado no meio do aperto)
            g?.interromper()?.let { disparar(ctx, teclaGestos, cfgGestos, it) }   // toque pendente da tecla anterior
            val ehApp = e.keyCode in RemapTeclas.APP
            // Botão de app sem "segurar" próprio: segurar 3 s abre a lista de apps, como sempre foi.
            val segurarMs = if (RemapTeclas.Gesto.SEGURAR in cfg || !ehApp) RemapTeclas.SEGURAR_MS else SEGURAR_MS
            g = Gestos(segurarMs, RemapTeclas.DUPLO_MS, ehApp || RemapTeclas.Gesto.SEGURAR in cfg, RemapTeclas.Gesto.DUPLO in cfg)
            gestos = g; teclaGestos = e.keyCode; cfgGestos = cfg
        }
        val reconhecido = if (e.action == KeyEvent.ACTION_DOWN) g.desce(agora) else g.sobe(agora)
        reconhecido?.let { disparar(ctx, teclaGestos, cfgGestos, it) }
        agendar(ctx)
        return true
    }

    private fun agendar(ctx: Context) {
        relogio?.let { principal.removeCallbacks(it) }
        relogio = null
        val p = gestos?.prazo ?: return
        relogio = Runnable {
            relogio = null
            gestos?.venceu(SystemClock.uptimeMillis())?.let { disparar(ctx, teclaGestos, cfgGestos, it) }
            agendar(ctx)
        }.also { principal.postAtTime(it, p) }
    }

    /** Faz o gesto: o atalho configurado; sem atalho num botão de app, o comportamento antigo (app salvo / lista). */
    private fun disparar(ctx: Context, tecla: Int, cfg: Map<RemapTeclas.Gesto, String>, gesto: RemapTeclas.Gesto) {
        val atalho = cfg[gesto]
        Log.i(TAG, "tecla $tecla ${gesto.chave} → ${atalho ?: "padrão"}")
        try {
            when {
                atalho == null -> {
                    val b = botao(tecla) ?: return
                    if (gesto == RemapTeclas.Gesto.SEGURAR) ctx.startActivity(Intent(ctx, EscolherAppActivity::class.java)
                        .putExtra(EscolherAppActivity.EXTRA_TECLA, b.tecla).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else abrirApp(ctx, appDo(ctx, b))
                }
                atalho.startsWith("app:") -> abrirApp(ctx, atalho.removePrefix("app:"))
                atalho.startsWith("cena:") -> {
                    val nome = atalho.removePrefix("cena:")
                    Fundo.rodar { Log.i(TAG, Cenas.rodar(ctx.applicationContext, nome, "tecla")) }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "gesto falhou: $e") }   // processo da HOME: nada aqui pode derrubá-lo
    }

    private var homeLongo: Runnable? = null
    private var homeLongoDisparou = false
    private var homeApertado = false

    /**
     * Botão HOME com o launcherx do Google ATIVO (04/11/2026). Ele declara a HOME com priority=2 (app de sistema
     * privilegiado), e no resolvedor do Android a prioridade vence QUALQUER preferência, até a persistente do Device
     * Owner e o papel de HOME (medido: role HOME = nosso, preferred = nosso, e o HOME abria launcherx/.home.HomeActivity).
     * Então a tecla é tratada aqui, antes do sistema: aperto curto = a nossa HOME; segurado = a tela Casa (luzes e cenas
     * do Home Assistant, CasaActivity). Até 18/11/2026 segurar HOME abria o painel rápido; o dono pediu o painel no
     * VOLTAR segurado ("a seta da direita para a esquerda é o mesmo movimento do painel abrindo") e a Casa no HOME.
     */
    private fun tratarHome(ctx: Context, e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                homeApertado = true
                homeLongoDisparou = false
                homeLongo?.let { principal.removeCallbacks(it) }
                homeLongo = Runnable {
                    homeLongoDisparou = true
                    homeLongo = null
                    abrirCasa(ctx)
                }.also { principal.postDelayed(it, Home.SEGURAR_HOME_MS) }
            }
            KeyEvent.ACTION_UP -> {
                homeLongo?.let { principal.removeCallbacks(it) }
                homeLongo = null
                if (homeApertado && !homeLongoDisparou) Home.irParaHome(ctx)
                homeApertado = false
            }
        }
        return true
    }

    private fun abrirCasa(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, CasaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { Log.w(TAG, "não abriu a Casa: $e") }   // processo da HOME: nada aqui pode derrubá-lo
    }

    /** VOLTAR segurado: a mesma conta do "segurar" da ajuda (toque curto passa intacto para o app; ver [CuidarDeLonge.Segurar]). */
    private val voltar = CuidarDeLonge.Segurar(Home.SEGURAR_HOME_MS).apply { tecla = KeyEvent.KEYCODE_BACK }
    private var voltarRelogio: Runnable? = null

    /**
     * Botão VOLTAR (18/11/2026, pedido do dono): segurado abre o painel rápido (PainelRapidoActivity), em qualquer tela.
     * O toque curto NÃO é engolido: o DOWN segue para o app da frente e o voltar continua igual, sem atraso e sem
     * reinjetar tecla. Só quando completa [Home.SEGURAR_HOME_MS] o painel abre e daí em diante as repetições e o UP
     * ficam conosco (o app não recebe o "soltar", e por isso não volta). Com a ajuda de longe (77) ligada no VOLTAR, a
     * tecla é dela (3 s): os dois no mesmo botão disparariam um por cima do outro.
     */
    private fun tratarVoltar(ctx: Context, e: KeyEvent): Boolean {
        if (CuidarDeLongeTv.usaTecla(KeyEvent.KEYCODE_BACK)) return false
        val desce = e.action == KeyEvent.ACTION_DOWN
        // Trailer no YouTube (18/11/2026, VigiaTrailer): o toque curto é nosso e devolve a ficha (o YouTube levava para a
        // tela inicial dele e ficava aberto). Segurar continua abrindo o painel rápido.
        if (VigiaTrailer.emCurso() || (!desce && trailerSegurando)) {
            if (desce && e.repeatCount == 0) trailerSegurando = true
            val longo = tratarVoltarNormal(ctx, e, desce)
            if (!desce) {
                trailerSegurando = false
                if (!longo) VigiaTrailer.voltar(ctx)
            }
            return true
        }
        return tratarVoltarNormal(ctx, e, desce)
    }

    /** VOLTAR do trailer: o DOWN já foi engolido; o UP decide se foi toque curto. */
    private var trailerSegurando = false

    private fun tratarVoltarNormal(ctx: Context, e: KeyEvent, desce: Boolean): Boolean {
        if (desce && e.repeatCount == 0) {
            voltarRelogio?.let { principal.removeCallbacks(it) }
            voltarRelogio = Runnable { voltarRelogio = null; if (voltar.venceu(SystemClock.uptimeMillis())) PainelRapidoActivity.abrir(ctx) }
                .also { principal.postDelayed(it, Home.SEGURAR_HOME_MS) }
        } else if (!desce) {
            voltarRelogio?.let { principal.removeCallbacks(it) }; voltarRelogio = null
        }
        return voltar.evento(e.keyCode, desce, e.repeatCount, e.eventTime) {
            voltarRelogio?.let { principal.removeCallbacks(it) }; voltarRelogio = null
            PainelRapidoActivity.abrir(ctx)
        }
    }

    /** Abre um app pelo pacote. Na TV o certo é o atalho "leanback"; se o app não tiver, tenta o comum. */
    fun abrirApp(ctx: Context, pacote: String) {
        val pm = ctx.packageManager
        val abrir = pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
        if (abrir == null) {
            Log.w(TAG, "app $pacote não encontrado")
            return
        }
        try { ctx.startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Log.w(TAG, "não abriu $pacote: $e") }
    }
}

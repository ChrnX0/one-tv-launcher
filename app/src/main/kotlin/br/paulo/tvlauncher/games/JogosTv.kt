package br.paulo.tvlauncher.games

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.InputDevice
import br.paulo.tvlauncher.JogosActivity
import br.paulo.tvlauncher.network.AcordarPc
import br.paulo.tvlauncher.network.DiagnosticoInternet
import br.paulo.tvlauncher.network.Rede
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.privilege.JanelaNaFrente
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.FiltroNoturno
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A parte Android do card Jogos ([Jogos] é a lógica pura): quais apps de jogo existem, controles ligados, a rede para
 * jogar na nuvem, o "modo jogo" e o "Jogar no PC". Guardado nas prefs "jogos" (escolhas da pessoa, IP e app do PC).
 * O MAC do PC continua onde já estava ([AcordarPc]).
 */
object JogosTv {
    private const val PREFS = "jogos"
    private const val CHAVE_ESCOLHAS = "escolhas"
    const val CHAVE_IP_PC = "pc_ip"
    const val CHAVE_APP_PC = "pc_app"

    class App(val pacote: String, val nome: String, val grupo: Jogos.Grupo)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun escolhas(ctx: Context): Map<String, String> = Jogos.escolhasDeJson(prefs(ctx).getString(CHAVE_ESCOLHAS, null))

    /** [valor] = nome de um Jogos.Grupo, Jogos.FORA ou null (volta ao automático). */
    fun escolher(ctx: Context, pacote: String, valor: String?) {
        prefs(ctx).edit().putString(CHAVE_ESCOLHAS, Jogos.escolhasParaJson(Jogos.escolher(escolhas(ctx), pacote, valor))).apply()
        cacheJogo.remove(pacote)
    }

    fun ipPc(ctx: Context): String = prefs(ctx).getString(CHAVE_IP_PC, "") ?: ""
    /** Vazio apaga. false = IP inválido. */
    fun guardarIpPc(ctx: Context, ip: String): Boolean {
        val t = ip.trim()
        if (t.isNotEmpty() && !Jogos.ipValido(t)) return false
        prefs(ctx).edit().putString(CHAVE_IP_PC, t).apply(); return true
    }
    fun appPcEscolhido(ctx: Context): String? = prefs(ctx).getString(CHAVE_APP_PC, null)
    fun escolherAppPc(ctx: Context, pacote: String?) { prefs(ctx).edit().putString(CHAVE_APP_PC, pacote).apply() }

    /** O card aparece na HOME? Dono, 14/11/2026: sempre (a chave existe para quem não joga desligar). */
    fun cardLigado(ctx: Context) = Extras.ligado(ctx, Extras.Chave.CARD_JOGOS)

    private fun categoriaJogo(ai: ApplicationInfo) = ai.category == ApplicationInfo.CATEGORY_GAME

    /** pacote -> é jogo (a categoria só muda se o app for reinstalado; a escolha limpa a entrada). */
    private val cacheJogo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** O app é jogo? Uma consulta ao PackageManager por pacote (depois, cache). Qualquer thread. */
    fun ehJogo(ctx: Context, pacote: String): Boolean = cacheJogo.getOrPut(pacote) {
        // MATCH_UNINSTALLED_PACKAGES: um jogo congelado sob demanda (OnDemandTv) está oculto e continua sendo jogo.
        @Suppress("DEPRECATION")
        val cat = try { categoriaJogo(ctx.packageManager.getApplicationInfo(pacote, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)) } catch (_: Exception) { false }
        Jogos.ehJogo(pacote, cat, escolhas(ctx))
    }

    /** A HOME já leu a categoria do app (carregarApps): guarda se é jogo sem consultar o PackageManager de novo. */
    fun primar(ctx: Context, pacote: String, categoria: Int) {
        if (!cacheJogo.containsKey(pacote)) cacheJogo[pacote] = Jogos.ehJogo(pacote, categoria == ApplicationInfo.CATEGORY_GAME, escolhas(ctx))
    }

    /** Os jogos instalados (com ícone na TV), por nome. Inclui os de sistema (Play Games). Chamar fora da tela. */
    fun listar(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val esc = escolhas(ctx)
        val vistos = HashSet<String>()
        val apps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        } catch (_: Exception) { emptyList() }
        return apps.mapNotNull { ri ->
            val p = ri.activityInfo.packageName
            if (p == ctx.packageName || !vistos.add(p)) return@mapNotNull null
            val cat = categoriaJogo(ri.activityInfo.applicationInfo)
            cacheJogo[p] = Jogos.ehJogo(p, cat, esc)
            if (!Jogos.ehJogo(p, cat, esc)) null else App(p, ri.loadLabel(pm).toString(), Jogos.grupo(p, esc))
        }.plus(
            // Congelados sob demanda (OnDemandTv) somem da consulta: entram com o nome guardado; o OK descongela e abre.
            OnDemandTv.entries(ctx).filter { it.pkg !in vistos && OnDemandTv.installed(ctx, it.pkg) && ehJogo(ctx, it.pkg) }
                .map { App(it.pkg, it.label, Jogos.grupo(it.pkg, esc)) }
        ).sortedBy { it.nome.lowercase() }
    }

    fun instalado(ctx: Context, pacote: String) = try { ctx.packageManager.getPackageInfo(pacote, 0); true } catch (_: Exception) { false }

    // ---------------------------------------------------------------- controles

    /** Nomes dos controles de videogame ligados agora (o controle remoto da TV não conta: não é gamepad). */
    fun controles(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
        .filter(::ehGamepad).map { it.name }.distinct()

    /**
     * Controle de videogame de verdade. Na Sala (15/11/2026) apareciam "virtual-search" e "virtual-remote": aparelhos
     * internos do sistema que se dizem gamepad. Por isso: só os ligados de fora (USB/Bluetooth, Android 10+) e nenhum
     * com nome "virtual".
     */
    private fun ehGamepad(d: InputDevice): Boolean {
        if (d.isVirtual || d.name.orEmpty().startsWith("virtual", ignoreCase = true)) return false
        if (android.os.Build.VERSION.SDK_INT >= 29 && !d.isExternal) return false
        val gamepad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        // Teclado com touchpad (Logitech K400 Plus na Sala, 15/11/2026) se diz "joystick" por causa da roda de rolar:
        // teclado alfabético só conta se também for gamepad.
        if (!gamepad && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        return gamepad || d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    class Controle(val nome: String, val bateria: Int?)

    /** Os controles ligados com a bateria em % (Android 12+, quando o controle informa; null = não sabe). */
    fun controlesComBateria(): List<Controle> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
        .filter(::ehGamepad)
        .distinctBy { it.name }
        .map { d ->
            val b = if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
                d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt().coerceIn(0, 100) }
            }.getOrNull() else null
            Controle(d.name, b)
        }

    fun ehControle(id: Int): Boolean {
        val d = InputDevice.getDevice(id) ?: return false
        return ehGamepad(d) && d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
    }

    /** Abre a tela do Android para parear Bluetooth (onde o controle aparece no modo de pareamento). */
    fun abrirPareamento(ctx: Context): Boolean = listOf(
        Intent("android.settings.BLUETOOTH_PAIRING_SETTINGS"),   // Google TV: "Parear controle ou acessório"
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
    ).any { i -> try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false } }

    // ---------------------------------------------------------------- modo jogo

    /**
     * Um jogo está na frente e o modo jogo está ligado: protetor, filtro noturno e avisos não interrompem.
     * Usa o mesmo sinal do resto do launcher (a última janela que o serviço de acessibilidade viu).
     */
    fun modoJogoAgora(ctx: Context): Boolean {
        if (!Extras.ligado(ctx, Extras.Chave.MODO_JOGO)) return false
        val p = JanelaNaFrente.pacote ?: return false
        return p != ctx.packageName && ehJogo(ctx, p)
    }

    @Volatile private var ultimoModo = false

    /** O serviço de acessibilidade chama a cada tela que abre: entrou/saiu de um jogo -> o filtro noturno se reavalia. */
    fun janela(ctx: Context) {
        val agora = modoJogoAgora(ctx)
        if (agora != ultimoModo) { ultimoModo = agora; FiltroNoturno.avaliar() }
    }

    // ---------------------------------------------------------------- controle ligado abre a tela Jogos

    private var ouvinte: android.hardware.input.InputManager.InputDeviceListener? = null
    private var gerente: android.hardware.input.InputManager? = null

    fun iniciarControles(ctx: Context) {
        if (ouvinte != null) return
        val app = ctx.applicationContext
        val l = object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) {
                // Só com a chave ligada e a NOSSA HOME na tela: nunca tira a pessoa de um filme ou de um app.
                if (!Extras.ligado(app, Extras.Chave.CONTROLE_ABRE_JOGOS) || !cardLigado(app)) return
                if (!ehControle(id) || !JanelaNaFrente.homeNaFrente(app)) return
                try { app.startActivity(Intent(app, JogosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            override fun onInputDeviceRemoved(id: Int) {}
            override fun onInputDeviceChanged(id: Int) {}
        }
        try {
            val im = app.getSystemService(android.hardware.input.InputManager::class.java)
            im.registerInputDeviceListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
            ouvinte = l; gerente = im
        } catch (_: Exception) {}
    }

    fun pararControles() {
        val l = ouvinte ?: return
        ouvinte = null
        try { gerente?.unregisterInputDeviceListener(l) } catch (_: Exception) {}
        gerente = null
    }

    // ---------------------------------------------------------------- rede

    class Medida(val rede: Jogos.Rede, val ms: Int?)

    /** Mede (até ~10 s): tipo de conexão, sinal do Wi-Fi e o menor de 3 tempos de conexão. Chamar fora da tela. */
    fun medirRede(ctx: Context): Medida {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val cabo = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        @Suppress("DEPRECATION")
        val dbm = if (cabo) 0 else (app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.rssi ?: -127
        val tempos = (1..3).mapNotNull { DiagnosticoInternet.tcpMs() }
        val ms = tempos.minOrNull()
        return Medida(Jogos.avaliarRede(caps != null, cabo, if (dbm in -126..-1) dbm else 0, ms), ms)
    }

    // ---------------------------------------------------------------- jogar no PC

    /** O PC responde? (porta 47989 do Sunshine/GeForce Experience, 27036 do Steam, ou 3389/445 de qualquer Windows) */
    fun pcResponde(ip: String): Boolean = listOf(47989, 27036, 445, 3389).any { porta ->
        try { Socket().use { it.connect(InetSocketAddress(ip, porta), 800) }; true } catch (_: Exception) { false }
    }

    /**
     * Acorda o PC e espera ele responder (ou o tempo fixo, sem IP). [progresso] recebe os segundos que passaram.
     * true = o PC respondeu (ou não havia como perguntar). Chamar fora da tela; [cancelado] interrompe.
     */
    fun acordarEEsperar(ctx: Context, progresso: (Int) -> Unit, cancelado: () -> Boolean): Boolean {
        val ip = ipPc(ctx)
        if (ip.isNotEmpty() && pcResponde(ip)) return true   // já estava ligado
        AcordarPc.acordar(ctx)
        val inicio = SystemClock.elapsedRealtime()
        val limite = if (ip.isEmpty()) Jogos.ESPERA_SEM_IP_MS else Jogos.ESPERA_PC_MS
        var ultimoReenvio = inicio
        while (!cancelado()) {
            val passou = SystemClock.elapsedRealtime() - inicio
            progresso((passou / 1000).toInt())
            if (passou >= limite) return ip.isEmpty()
            if (ip.isNotEmpty() && pcResponde(ip)) return true
            // Reenvia o pacote a cada 15 s: um pacote UDP perdido não deixa a pessoa esperando à toa.
            if (SystemClock.elapsedRealtime() - ultimoReenvio > 15_000) { AcordarPc.acordar(ctx); ultimoReenvio = SystemClock.elapsedRealtime() }
            Thread.sleep(1000)
        }
        return false
    }
}

package br.paulo.tvlauncher.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.privilege.RegrasEmprestimo.Modo
import br.paulo.tvlauncher.privilege.RegrasEmprestimo.Parado
import br.paulo.tvlauncher.settings.Planos
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/**
 * O launcher EMPRESTA o shell (uid 2000) a outros apps: servidor do Shizuku embutido (15/11/2026, "poder máximo").
 *
 * Histórico: existiu de 04/11 a 12/11/2026 e saiu porque o APK DEFINIA as permissões moe.shizuku.* (o Android não
 * instala dois apps que definem a mesma permissão, então o launcher não entrava em TV com o app Shizuku). Agora o
 * launcher não define nenhuma: o servidor acha os clientes pela permissão que ELES pedem (texto no manifesto deles) e
 * guarda quem foi autorizado na config dele, por PIN na TV (PedidoShizukuActivity). Ver shizuku/NOTICE.md.
 *
 * Como sobe: pelo nosso próprio adb (GuardiaoShizuku.comando), com app_process apontando para o nosso APK, igual ao
 * starter do Shizuku. Modos (Configurações): DESLIGADO (fábrica), SOB_DEMANDA (sobe quando um app cliente aparece na
 * frente e desce depois de 5 min sem cliente), SEMPRE (fica de pé e volta se morrer).
 */
object ShizukuEmbutido {
    private const val PREFS = "emprestimo_shell"
    const val OCIOSO_MS = 5 * 60_000L

    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "ShizukuEmbutido") }
    private val principal = Handler(Looper.getMainLooper())
    private val subindo = AtomicBoolean(false)
    private val vigiando = AtomicBoolean(false)

    @Volatile private var clientes: Set<String> = emptySet()
    @Volatile private var sequencia = -1          // PackageManager.getChangedPackages: só recalcula se algo mudou
    @Volatile private var ultimoCliente = 0L      // elapsedRealtime da última vez que um cliente esteve na frente
    private var desligarAgendado: Runnable? = null

    private fun anotar(ctx: Context, t: String) = ServidorShizuku.anotar(ctx, "[emprestimo] $t")

    fun modo(ctx: Context): Modo =
        RegrasEmprestimo.modoDe(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("modo", null))

    /** Muda o modo. Desligar derruba o servidor na hora; ligar sobe (SEMPRE) ou espera um cliente (SOB_DEMANDA). */
    fun definirModo(ctx: Context, m: Modo) {
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("modo", m.name).apply()
        anotar(app, "modo: $m")
        when (m) {
            Modo.DESLIGADO -> parar(app, "desligado")
            Modo.SOB_DEMANDA -> { if (!clienteAtivo()) parar(app, "sob demanda sem cliente") }
            Modo.SEMPRE -> garantir(app, "modo sempre")
        }
    }

    fun oficialInstalado(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(RegrasEmprestimo.PACOTE_SHIZUKU_OFICIAL, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun parado(ctx: Context): Parado = RegrasEmprestimo.parado(
        modo(ctx), Planos.permite(ctx, Planos.Recurso.PODER_EMPRESTADO) && emprestimoExiste(), oficialInstalado(ctx))

    /** Versão da Play enxuta (PlayPolicy): emprestar o shell não existe; fica parado como "sem plano". */
    fun emprestimoExiste() = PlayPolicy.allowed(PlayPolicy.Power.LEND_POWERS, BuildConfig.PLAY)

    /** O servidor responde agora (o launcher, como gerente, tem o binder dele)? */
    fun rodando(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    /** Texto curto para o adb (ESTADO) e o painel. */
    fun estado(ctx: Context): String = when {
        rodando() -> "rodando uid=${Shizuku.getUid()} versao=${Shizuku.getVersion()} modo=${modo(ctx)}"
        else -> "parado (${parado(ctx).name.lowercase()}) modo=${modo(ctx)}"
    }

    /**
     * Sobe o servidor se ele deve existir e não está de pé. Qualquer thread; o trabalho roda na fila daqui (a conversa
     * com o adb em si passa pela fila única do GuardiaoShizuku).
     */
    fun garantir(ctx: Context, motivo: String) {
        val app = ctx.applicationContext
        val p = parado(app)
        // Não deve existir (desligado, plano, app Shizuku oficial instalado): se o nosso estiver de pé, desce.
        // O binder que o launcher recebe é sempre o do NOSSO servidor: o oficial só entrega o dele ao pacote dele.
        if (p != Parado.NAO) { if (rodando()) parar(app, "parado: $p"); return }
        vigiar(app)
        if (rodando() || !subindo.compareAndSet(false, true)) return
        fila.execute {
            try { subir(app, motivo) } catch (e: Throwable) { anotar(app, "falhou ao subir: ${e.javaClass.simpleName}: ${e.message}") }
            finally { subindo.set(false) }
        }
    }

    private fun subir(app: Context, motivo: String) {
        val ai = app.applicationInfo
        val cmd = RegrasEmprestimo.comandoPartida(ai.sourceDir, ai.nativeLibraryDir)
        val pid = GuardiaoShizuku.comando(app, "pidof ${RegrasEmprestimo.NOME_PROCESSO}", 15_000)
        if (pid.isNullOrBlank()) {
            anotar(app, "subindo ($motivo)")
            val r = GuardiaoShizuku.comando(app, cmd, 20_000)
            if (r == null) { anotar(app, "sem shell próprio: não deu para subir"); return }
        } else anotar(app, "processo já existe (pid $pid); esperando o binder ($motivo)")
        // O binder chega pelo ProvedorShizuku, em outra thread; aqui só esperamos ele aparecer (até 10 s).
        repeat(20) {
            if (rodando()) { anotar(app, "de pé: uid=${Shizuku.getUid()} versao=${Shizuku.getVersion()}"); return }
            Thread.sleep(500)
        }
        anotar(app, "o binder não chegou em 10 s")
    }

    /** Derruba o servidor (o próprio servidor encerra, a pedido do gerente). */
    fun parar(ctx: Context, motivo: String) {
        val app = ctx.applicationContext
        fila.execute {
            try { if (rodando()) { Shizuku.exit(); anotar(app, "encerrado ($motivo)") } } catch (_: Throwable) {}
        }
    }

    /** Uma vez por processo: se o servidor morrer no modo SEMPRE, sobe de novo. */
    private fun vigiar(app: Context) {
        if (!vigiando.compareAndSet(false, true)) return
        Shizuku.addBinderDeadListener {
            if (modo(app) == Modo.SEMPRE) principal.postDelayed({ garantir(app, "binder morreu") }, 3000)
        }
    }

    // --- Sob demanda: o gatilho é nosso. Um app cliente sem servidor não avisa ninguém (o ShizukuProvider é dele);
    // o servidor, ao subir, entrega o binder a todo app cliente. Então: cliente apareceu na frente -> sobe.

    private fun clienteAtivo(): Boolean =
        JanelaNaFrente.pacote in clientes || (ultimoCliente > 0 && SystemClock.elapsedRealtime() - ultimoCliente < OCIOSO_MS)

    /** Chamado pelo serviço de acessibilidade a cada tela que abre (depois do JanelaNaFrente). */
    fun janelaApareceu(ctx: Context, pacote: CharSequence?) {
        val p = pacote?.toString() ?: return
        val app = ctx.applicationContext
        if (p == app.packageName || modo(app) != Modo.SOB_DEMANDA) return
        fila.execute { if (ehCliente(app, p)) principal.post { clienteNaFrente(app, p) } }
    }

    private fun clienteNaFrente(app: Context, pacote: String) {
        ultimoCliente = SystemClock.elapsedRealtime()
        if (!rodando()) anotar(app, "sob demanda: $pacote na frente")
        garantir(app, "sob demanda: $pacote")
        agendarDesligar(app)
    }

    private fun agendarDesligar(app: Context) {
        desligarAgendado?.let { principal.removeCallbacks(it) }
        desligarAgendado = Runnable { talvezDesligar(app) }.also { principal.postDelayed(it, OCIOSO_MS) }
    }

    private fun talvezDesligar(app: Context) {
        desligarAgendado = null
        if (modo(app) != Modo.SOB_DEMANDA) return
        if (JanelaNaFrente.pacote in clientes) {   // o cliente continua na frente: espera mais
            ultimoCliente = SystemClock.elapsedRealtime(); agendarDesligar(app); return
        }
        val passou = SystemClock.elapsedRealtime() - ultimoCliente
        if (passou < OCIOSO_MS) { principal.postDelayed({ talvezDesligar(app) }, OCIOSO_MS - passou); return }
        ultimoCliente = 0L
        parar(app, "${OCIOSO_MS / 60_000} min sem cliente na frente")
    }

    /** Pacotes que pedem o Shizuku. Lista em cache; só recalcula quando algum app foi instalado/atualizado/removido. */
    fun clientesInstalados(ctx: Context): Set<String> {
        val app = ctx.applicationContext
        val pm = app.packageManager
        val mudou = sequencia < 0 || pm.getChangedPackages(sequencia) != null
        if (mudou) {
            sequencia = pm.getChangedPackages(0)?.sequenceNumber ?: 0
            clientes = try {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                    .filter { it.packageName != app.packageName && RegrasEmprestimo.ehCliente(it.requestedPermissions) }
                    .map { it.packageName }.toSet()
            } catch (_: Exception) { emptySet() }
        }
        return clientes
    }

    private fun ehCliente(app: Context, pacote: String) = pacote in clientesInstalados(app)
}

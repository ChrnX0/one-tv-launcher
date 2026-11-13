package br.paulo.tvlauncher

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.WeakHashMap

/**
 * Atualização vigiada (10/11/2026): a parte Android das [VigiaRegras] (leia lá o porquê de não dar para voltar versão).
 *
 * Ligações:
 *  - LauncherApp.onCreate -> [aoIniciar] (anota a versão que abriu; só no processo principal) e [vigiarTelas] (modo
 *    seguro, em todos os processos);
 *  - cada tela do launcher (menos HOME, Configurações, Diagnóstico e alarme) -> [fechada] logo depois do super.onCreate;
 *  - RegistroErros (tratador de quedas) -> [aoCair], ANTES de o Android fechar o processo;
 *  - Atualizador -> [podeInstalar], [aoVerRelease], [textoPausa], e [avisarSePendente] no relógio de 10 min;
 *  - Configurações -> [pausada] / [liberar] (linha "Modo seguro").
 *
 * Guarda tudo num arquivo próprio (filesDir/vigia-atualizacao.txt), com trava de arquivo: o player roda em outro
 * processo (:player) e também pode cair, e os dois processos leem e gravam o mesmo estado.
 *
 * O que conta como queda: só exceção Java/Kotlin não tratada (passa pelo tratador do RegistroErros). Travamento (ANR),
 * queda de código nativo e processo morto pelo sistema (memória) NÃO contam: o Android encerra o processo sem chamar
 * o tratador.
 */
object VigiaAtualizacao {
    private const val TAG = "TvLauncher.Vigia"
    private const val ARQUIVO = "vigia-atualizacao.txt"

    private fun versao(): Long = BuildConfig.VERSION_CODE.toLong()

    /** Lê, muda e grava sob a trava de arquivo (vale entre processos). Erro de disco = não muda nada.
     *  O synchronized é para as threads do MESMO processo: a trava de arquivo do Java não pode ser pega duas vezes
     *  pelo mesmo processo (daria OverlappingFileLockException). */
    @Synchronized
    private fun <T> mexer(ctx: Context, muda: (VigiaRegras.Estado) -> Pair<VigiaRegras.Estado, T>): T? = try {
        val dir = ctx.applicationContext.filesDir
        RandomAccessFile(File(dir, "$ARQUIVO.trava"), "rw").use { raf ->
            raf.channel.lock().use {
                val arq = File(dir, ARQUIVO)
                val antes = VigiaRegras.deTexto(if (arq.exists()) arq.readText() else null)
                val (depois, r) = muda(antes)
                if (depois != antes) {
                    // Grava num temporário e troca: uma queda no meio não deixa o arquivo pela metade.
                    val tmp = File(dir, "$ARQUIVO.tmp")
                    tmp.writeText(VigiaRegras.paraTexto(depois))
                    if (!tmp.renameTo(arq)) { arq.writeText(VigiaRegras.paraTexto(depois)); tmp.delete() }
                }
                r
            }
        }
    } catch (e: Exception) { Log.w(TAG, "estado: ${e.message}"); null }

    fun ler(ctx: Context): VigiaRegras.Estado = mexer(ctx) { it to it } ?: VigiaRegras.Estado()

    // Cópia em memória para o caminho quente (toda tela que abre): só relê o arquivo (trava + leitura) se a data dele
    // mudou. Sem arquivo = estado vazio, sem nem pegar a trava.
    @Volatile private var cache: Pair<Long, VigiaRegras.Estado>? = null

    private fun lerRapido(ctx: Context): VigiaRegras.Estado {
        val arq = File(ctx.applicationContext.filesDir, ARQUIVO)
        val data = arq.lastModified()   // 0 = não existe
        if (data == 0L) return VigiaRegras.Estado()
        cache?.let { (d, e) -> if (d == data) return e }
        val e = ler(ctx)
        cache = data to e
        return e
    }

    /** Telas fechadas pelo modo seguro (fraca: não segura a tela na memória). Só a thread da tela mexe aqui. */
    private val fechadas: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Para a tela chamar logo depois do super.onCreate: `if (VigiaAtualizacao.fechada(this)) return`.
     * Por quê: o [vigiarTelas] roda DENTRO do super.onCreate e o finish() dele não interrompe o resto do onCreate da
     * tela; se a queda está no onCreate, só esse return evita que ela aconteça de novo. (finish() no onCreate: o
     * Android pula onStart/onResume/onPause/onStop e chama direto o onDestroy.)
     */
    fun fechada(a: Activity): Boolean = a in fechadas

    /** O processo principal (não o :player). O aviso e o registro do painel (SharedPreferences) só rodam nele. */
    private fun processoPrincipal(ctx: Context): Boolean = try {
        val nome = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        nome == null || nome == ctx.packageName
    } catch (_: Exception) { true }

    /** O processo abriu. Rápido (um arquivo pequeno): roda antes da HOME, para uma queda logo em seguida já contar. */
    fun aoIniciar(ctx: Context) {
        // Só no processo principal: o :player gravaria o registro do painel (SharedPreferences) de outro processo, e o
        // cache das prefs do principal apagaria a linha; e lá o AvisoTv nunca tem a acessibilidade.
        if (!processoPrincipal(ctx)) return
        val e = mexer(ctx) { val n = VigiaRegras.aoIniciar(it, versao(), System.currentTimeMillis()); n to n } ?: return
        if (e.vigiando == versao()) Log.i(TAG, "vigiando a versão ${versao()}")
        if (e.avisoPendente || e.registroPendente) {
            // O aviso precisa do serviço de acessibilidade, que liga uns segundos depois do launcher: tenta algumas vezes.
            Fundo.rodar("vigia-aviso") {
                for (i in 0 until 12) {
                    if (avisarSePendente(ctx)) break
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /** O launcher caiu (chamado pelo tratador do RegistroErros, com o processo morrendo: nada de thread nova). */
    fun aoCair(ctx: Context, erro: Throwable) {
        val tela = VigiaRegras.telaDoErro(classesDaPilha(erro))
        val q = mexer(ctx) { val q = VigiaRegras.aoCair(it, versao(), System.currentTimeMillis(), tela); q.estado to q } ?: return
        if (q.marcouRuim) Log.e(TAG, "versão ${versao()} caiu ${VigiaRegras.QUEDAS_PARA_RUIM} vezes: marcada ruim (tela: ${tela ?: "?"})")
    }

    /** As classes da pilha do erro e das causas, na ordem (a mais funda de cada uma primeiro). */
    private fun classesDaPilha(erro: Throwable): List<String> {
        val r = ArrayList<String>()
        var t: Throwable? = erro
        var voltas = 0
        while (t != null && voltas++ < 10) {
            t.stackTrace.forEach { r += it.className }
            t = t.cause?.takeIf { it !== t }
        }
        return r
    }

    /** Pode instalar o Release [codigo]? (Nunca uma versão ruim; com a pausa, só uma maior que a que caiu.) */
    fun podeInstalar(ctx: Context, codigo: Long) = VigiaRegras.podeInstalar(ler(ctx), codigo)

    /** O GitHub mostrou o Release [codigo]: se for maior que a versão que caiu, a pausa acaba. */
    fun aoVerRelease(ctx: Context, codigo: Long) {
        val saiu = mexer(ctx) { val n = VigiaRegras.aoVerRelease(it, codigo); n to (it.pausada && !n.pausada) } ?: false
        if (saiu) Log.i(TAG, "Release $codigo é maior que a versão ruim: atualização automática liberada")
    }

    fun pausada(ctx: Context) = ler(ctx).let { it.pausada || it.seguroVersao == versao() }

    /** Configurações > Modo seguro > OK: religa a tela bloqueada e a atualização (a versão ruim continua proibida). */
    fun liberar(ctx: Context) {
        mexer(ctx) { VigiaRegras.liberar(it) to Unit }
        try { PainelArmazem.registrar(ctx, "tv", "atualização: modo seguro desligado pelo dono", "ok") } catch (_: Exception) {}
    }

    /** Texto para as Configurações / estado do Atualizador, ou null se não há pausa nem modo seguro. */
    fun textoPausa(ctx: Context): String? {
        val e = ler(ctx)
        if (!e.pausada && e.seguroVersao != versao()) return null
        val v = if (e.pausada) e.pausaPor else e.seguroVersao
        val tela = if (e.seguroVersao == versao() && e.telaSuspeita.isNotEmpty()) nomeDaTela(ctx, e.telaSuspeita) else null
        return if (tela != null) txt(R.string.vig_pausada_tela, v, tela) else txt(R.string.vig_pausada, v)
    }

    /**
     * Mostra o aviso na TV e escreve no registro do painel, se a última queda marcou a versão como ruim.
     * true = nada mais pendente. Chamado na abertura e pelo relógio de 10 min (Atualizador.talvezVerificar).
     */
    fun avisarSePendente(ctx: Context): Boolean {
        if (!processoPrincipal(ctx)) return true
        val e = ler(ctx)
        if (!e.avisoPendente && !e.registroPendente) return true
        val v = e.pausaPor.takeIf { it != 0L } ?: versao()
        val texto = textoPausa(ctx) ?: txt(R.string.vig_pausada, v)
        if (e.registroPendente) {
            try { PainelArmazem.registrar(ctx, "tv", "atualização: versão $v caiu ${VigiaRegras.QUEDAS_PARA_RUIM} vezes", texto) } catch (_: Exception) {}
            mexer(ctx) { it.copy(registroPendente = false) to Unit }
        }
        if (!e.avisoPendente) return true
        val r = try {
            AvisoTv.mostrar(ctx, txt(R.string.vig_aviso_titulo), texto, segundos = 20, som = false, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
        } catch (ex: Exception) { AvisoTv.Resultado.SEM_ACESSIBILIDADE }
        if (r == AvisoTv.Resultado.MOSTRADO || r == AvisoTv.Resultado.GUARDADO) {
            mexer(ctx) { it.copy(avisoPendente = false) to Unit }
            return true
        }
        return false
    }

    /** "PlayerActivity" -> o nome da tela que aparece para o usuário (rótulo do manifesto), ou o próprio nome. */
    private fun nomeDaTela(ctx: Context, classe: String): String = try {
        val pm = ctx.packageManager
        pm.getActivityInfo(ComponentName(ctx.packageName, "${ctx.packageName}.$classe"), 0).loadLabel(pm).toString()
            .takeIf { it.isNotBlank() && it != pm.getApplicationLabel(ctx.applicationInfo).toString() } ?: classe
    } catch (_: Exception) { classe }

    /**
     * Modo seguro: a tela que derrubou a versão ruim não abre (fecha logo ao criar e avisa). Vale em todos os processos
     * do launcher (o player roda à parte). A HOME, as Configurações, o Diagnóstico e o alarme nunca são bloqueados.
     * Limite: este callback roda dentro do super.onCreate; para a queda no próprio onCreate não se repetir, a tela
     * precisa do [fechada] logo depois do super.onCreate (todas as do launcher têm, menos as que nunca bloqueiam).
     */
    fun vigiarTelas(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, salvo: Bundle?) {
                val classe = a.javaClass.name.removePrefix("${app.packageName}.")
                if (classe.contains('.')) return   // tela de biblioteca, não nossa
                if (!VigiaRegras.telaBloqueada(lerRapido(app), versao(), classe)) return
                Log.w(TAG, "modo seguro: $classe não abre nesta versão")
                fechadas += a
                try { Toast.makeText(app, txt(R.string.vig_tela_bloqueada), Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                a.finish()
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

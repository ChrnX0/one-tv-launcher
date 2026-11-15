package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gravação da TV ao vivo, a parte Android (14/11/2026). Regras puras em GravacaoRegras, motor em Gravador, estudo em
 * docs/gravacao.md.
 *
 * Roda no processo ":player" (o mesmo do PlayerActivity): assim o player lê o mesmo [CanoAoVivo] e assiste o canal que
 * está gravando sem abrir uma 2ª conexão (a lista do dono só deixa 1). Serviço em primeiro plano (dataSync) com
 * WakeLock e WifiLock: a TV pode estar com a tela apagada. Uma gravação por vez.
 */
object Gravacoes {
    private const val TAG = "TvLauncher.Gravacao"
    private const val ARQUIVO = "gravacoes_agendadas.json"
    private const val ESTADO = "gravando.json"   // lido pela HOME (outro processo) para o "● Gravando" no card TV

    /** A gravação em andamento (neste processo). */
    class Sessao(
        val canalId: String, val canalNome: String, val programa: String, val inicio: Long, @Volatile var fim: Long,
        val agendadaId: String?, val destino: String,
    ) {
        val cano = CanoAoVivo()
        val parar = AtomicBoolean(false)
        @Volatile var canal: InputStream? = null
        @Volatile var bytes = 0L
    }

    @Volatile var atual: Sessao? = null; internal set
    /**
     * Entre o pedido e o [atual] existir (ler o cache de canais e abrir o destino leva segundos, numa thread de fundo):
     * sem esta marca, um segundo ▶ Gravar ou o alarme de uma agendada nesse intervalo abria OUTRA gravação (duas
     * conexões, o mesmo arquivo, e o fim da primeira soltava as travas da segunda).
     */
    internal val iniciando = java.util.concurrent.atomic.AtomicBoolean(false)
    fun ocupado() = atual != null || iniciando.get()
    private val ouvintes = ArrayList<() -> Unit>()
    private val principal = Handler(Looper.getMainLooper())

    fun ouvir(f: () -> Unit) = synchronized(ouvintes) { ouvintes += f }
    fun esquecer(f: () -> Unit) = synchronized(ouvintes) { ouvintes -= f }
    internal fun avisarOuvintes() {
        val l = synchronized(ouvintes) { ouvintes.toList() }
        principal.post { l.forEach { runCatching { it() } } }
    }

    fun gravando(canalId: String) = atual?.let { it.canalId == canalId && !it.parar.get() } == true

    // ---------------------------------------------------------------- comandos (qualquer tela)

    /** "Gravar agora": até [fim] (o fim do programa + folga, ou 1 h / 2 h). */
    fun gravarAgora(ctx: Context, c: Canal, programa: String, fim: Long) {
        val i = Intent(ctx, GravacaoServico::class.java).setAction(GravacaoServico.ACAO_AGORA)
            .putExtra("canal", c.id).putExtra("nome", c.nome).putExtra("programa", programa).putExtra("fim", fim)
        iniciar(ctx, i)
    }

    fun parar(ctx: Context) {
        atual?.let { it.parar.set(true); runCatching { it.canal?.close() } }
    }

    private fun iniciar(ctx: Context, i: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) { Log.w(TAG, "não iniciei o serviço: $e") }
    }

    // ---------------------------------------------------------------- agendamentos

    private fun arquivo(ctx: Context) = File(ctx.applicationContext.filesDir, ARQUIVO)

    fun agendadas(ctx: Context): List<GravacaoRegras.Agendada> = synchronized(this) {
        GravacaoRegras.deJson(arquivo(ctx).takeIf { it.exists() }?.readText())
    }

    private fun salvar(ctx: Context, lista: List<GravacaoRegras.Agendada>) = synchronized(this) {
        PlayerCarga.gravarAtomico(arquivo(ctx), GravacaoRegras.paraJson(lista))
    }

    /** Agenda; devolve as que conflitam (aí não agenda). Lista cheia: devolve null. */
    fun agendar(ctx: Context, c: Canal, p: Programa, semanal: Boolean): List<GravacaoRegras.Agendada>? = synchronized(this) {
        val lista = agendadas(ctx)
        if (lista.size >= GravacaoRegras.MAX_AGENDADAS) return null
        val nova = GravacaoRegras.Agendada(UUID.randomUUID().toString(), c.id, c.nome, p.titulo, p.inicio, p.fim, semanal)
        val conflito = GravacaoRegras.conflitos(nova, lista)
        if (conflito.isNotEmpty()) return conflito
        salvar(ctx, lista + nova)
        armar(ctx)
        return emptyList()
    }

    fun agendada(ctx: Context, canalId: String, inicio: Long) = agendadas(ctx).firstOrNull { it.canal == canalId && it.inicio == inicio }

    fun cancelar(ctx: Context, id: String) = synchronized(this) {
        salvar(ctx, agendadas(ctx).filter { it.id != id })
        armar(ctx)
    }

    /** Depois de gravar: "toda semana" vai para a próxima; as outras saem da lista. */
    internal fun concluir(ctx: Context, id: String) = synchronized(this) {
        salvar(ctx, agendadas(ctx).mapNotNull { a ->
            when {
                a.id != id -> a
                a.semanal -> a.copy(inicio = a.inicio + GravacaoRegras.SEMANA_MS, fim = a.fim + GravacaoRegras.SEMANA_MS)
                else -> null
            }
        })
        armar(ctx)
    }

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 47,
        Intent(ctx, GravacaoReceiver::class.java).setAction(GravacaoReceiver.ACAO_HORA),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * (Re)arma o alarme da próxima. Chamado ao agendar, ao terminar, no boot (BootReceiver). Se uma devia estar gravando
     * agora (a TV religou no meio), começa já. Limpa as que passaram (e avança as semanais).
     */
    fun armar(ctx: Context) {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val antes = agendadas(app)
        val lista = antes.mapNotNull { GravacaoRegras.proxima(it, agora) }
        if (lista != antes) salvar(app, lista)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        GravacaoRegras.gravandoAgora(lista, agora)?.let { a ->
            if (!ocupado()) iniciar(app, Intent(app, GravacaoServico::class.java).setAction(GravacaoServico.ACAO_AGENDADA).putExtra("id", a.id))
        }
        val prox = GravacaoRegras.proximaAComecar(lista, agora)
        if (prox == null) { am.cancel(pendente(app)); return }
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prox.comecaEm, pendente(app)) }
        catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prox.comecaEm, pendente(app)) }
    }

    // ---------------------------------------------------------------- estado para a HOME (outro processo)

    internal fun escreverEstado(ctx: Context, s: Sessao?) {
        val f = File(ctx.applicationContext.filesDir, ESTADO)
        if (s == null) { f.delete(); return }
        runCatching { PlayerCarga.gravarAtomico(f, JSONObject().put("canal", s.canalNome).put("fim", s.fim).toString()) }
    }

    /** Para a HOME: "Globo até 22:05", ou null se nada grava. Lê disco: fora da thread da tela. */
    fun resumoParaHome(ctx: Context): String? {
        val f = File(ctx.applicationContext.filesDir, ESTADO)
        if (!f.exists()) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val fim = o.optLong("fim")
        if (fim < System.currentTimeMillis()) return null   // o processo morreu sem apagar
        return txt(R.string.grv_ate, o.optString("canal"), hora(fim))
    }

    fun hora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}

/** O alarme de uma gravação agendada. Roda no ":player" (manifest). */
class GravacaoReceiver : BroadcastReceiver() {
    companion object { const val ACAO_HORA = "br.paulo.tvlauncher.gravacao.HORA" }
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACAO_HORA) Gravacoes.armar(ctx)
    }
}

class GravacaoServico : Service() {
    companion object {
        const val ACAO_AGORA = "br.paulo.tvlauncher.gravacao.AGORA"
        const val ACAO_AGENDADA = "br.paulo.tvlauncher.gravacao.AGENDADA"
        const val ACAO_PARAR = "br.paulo.tvlauncher.gravacao.PARAR"
        private const val TAG = "TvLauncher.Gravacao"
        private const val CANAL_NOTIF = "gravacao"
        private const val ID_NOTIF = 4701
    }

    private var trava: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        // O Android exige a notificação logo no começo (5 s), antes de qualquer outra coisa.
        mostrarNotificacao(getString(R.string.grv_preparando))
        when (i?.action) {
            ACAO_PARAR -> { Gravacoes.parar(this); if (Gravacoes.atual == null) encerrar() }
            ACAO_AGORA -> comecar(i.getStringExtra("canal").orEmpty(), i.getStringExtra("nome").orEmpty(),
                i.getStringExtra("programa").orEmpty(), i.getLongExtra("fim", 0L), null)
            ACAO_AGENDADA -> {
                val a = Gravacoes.agendadas(this).firstOrNull { it.id == i.getStringExtra("id") }
                if (a == null) { if (!Gravacoes.ocupado()) encerrar() } else comecar(a.canal, a.canalNome, a.programa, a.terminaEm, a.id)
            }
            else -> if (!Gravacoes.ocupado()) encerrar()
        }
        return START_NOT_STICKY
    }

    private fun comecar(canalId: String, nome: String, programa: String, fim: Long, agendadaId: String?) {
        val ja = Gravacoes.atual
        if (ja != null) {
            // Já grava: o mesmo canal só estica o fim; outro canal fica de fora (1 conexão).
            if (ja.canalId == canalId) { ja.fim = maxOf(ja.fim, fim); Gravacoes.escreverEstado(this, ja); atualizarNotificacao(ja); Gravacoes.avisarOuvintes() }
            else Log.w(TAG, "já gravando ${ja.canalNome}: ignorei $nome")
            return
        }
        if (fim <= System.currentTimeMillis()) { encerrar(); return }
        if (!Gravacoes.iniciando.compareAndSet(false, true)) { Log.w(TAG, "outra gravação está começando: ignorei $nome"); return }
        val app = applicationContext
        Fundo.rodar("gravacao") { try {
            val canal = PlayerCarga.canaisDoCache(app).firstOrNull { it.id == canalId }
            val destino = runCatching { GravacaoDestino.abrir(app) }.getOrNull()
            if (canal == null || destino == null) {
                val motivo = getString(if (canal == null) R.string.grv_sem_canal else R.string.grv_sem_destino)
                Log.w(TAG, "não gravei $nome: $motivo")
                Avisos.enviar(app, Avisos.Tipo.GRAVACAO, getString(R.string.grv_nao_gravou, nome), motivo)
                agendadaId?.let { Gravacoes.concluir(app, it) }
                runCatching { destino?.close() }
                Gravacoes.iniciando.set(false)   // antes do encerrar(), que não fecha nada enquanto há gravação começando
                Handler(Looper.getMainLooper()).post { encerrar() }
                return@rodar
            }
            gravar(canal, nome, programa, fim, agendadaId, destino)
        } catch (e: Exception) {
            Log.w(TAG, "gravação de $nome falhou ao começar: ${e.javaClass.simpleName}")
            Gravacoes.iniciando.set(false)
            Handler(Looper.getMainLooper()).post { encerrar() }   // sem isso a notificação "preparando" ficava para sempre
        } finally { Gravacoes.iniciando.set(false) } }
    }

    private fun gravar(c: Canal, nome: String, programa: String, fim: Long, agendadaId: String?, destino: GravacaoDestino.Destino) {
        val app = applicationContext
        val inicio = System.currentTimeMillis()
        val s = Gravacoes.Sessao(c.id, nome.ifEmpty { c.nome }, programa, inicio, fim, agendadaId, destino.nome)
        Gravacoes.atual = s
        Gravacoes.iniciando.set(false)   // daqui em diante quem barra uma segunda gravação é o [atual]
        Gravacoes.escreverEstado(app, s)
        Gravacoes.avisarOuvintes()
        travar()
        atualizarNotificacao(s)
        Log.i(TAG, "começou: ${s.canalNome} até ${Gravacoes.hora(fim)} em ${destino.nome}")
        val g = Gravador(
            abrirCanal = { GravacaoCanal.abrir(c.url, c.agenteUsuario).also { s.canal = it } },
            abrirPedaco = { parte -> destino.abrir(GravacaoRegras.nomeArquivo(inicio, s.canalNome, programa.ifEmpty { null }, parte)) },
            livre = { destino.livre() },
            relogio = { System.currentTimeMillis() },
            fimMs = Long.MAX_VALUE,   // o fim pode esticar: o laço abaixo confere s.fim
            parar = s.parar,
            aoGravar = { b, n -> s.cano.escrever(b, n); s.bytes += n },
        )
        // O Gravador lê fimMs uma vez; o fim desta sessão pode esticar ("gravar 1 h a mais"): um vigia para no horário.
        val vigia = Thread {
            while (!s.parar.get()) {
                if (System.currentTimeMillis() >= s.fim) { s.parar.set(true); runCatching { s.canal?.close() }; break }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val r = try { g.rodar() } finally {
            vigia.interrupt()
            s.cano.fechar()
            runCatching { destino.close() }
        }
        val porHorario = System.currentTimeMillis() >= s.fim - 2000
        Log.i(TAG, "terminou: ${r.motivo} ${r.bytes} bytes ${r.partes} partes ${r.ms / 1000}s ${r.erro ?: ""}")
        Gravacoes.atual = null
        Gravacoes.escreverEstado(app, null)
        Gravacoes.avisarOuvintes()
        agendadaId?.let { Gravacoes.concluir(app, it) }
        val texto = when {
            r.motivo == Gravador.Motivo.SEM_ESPACO -> getString(R.string.grv_parou_espaco, destino.nome)
            r.motivo == Gravador.Motivo.CONEXAO -> getString(R.string.grv_parou_conexao)
            r.motivo == Gravador.Motivo.DESTINO -> getString(R.string.grv_parou_destino, destino.nome)
            else -> null
        }
        if (texto != null) Avisos.enviar(app, Avisos.Tipo.GRAVACAO, getString(R.string.grv_parou, s.canalNome), texto)
        else if (porHorario) Avisos.enviar(app, Avisos.Tipo.GRAVACAO, getString(R.string.grv_terminou, s.canalNome), programa.ifEmpty { s.canalNome })
        Handler(Looper.getMainLooper()).post { encerrar() }
    }

    private fun travar() {
        runCatching {
            trava = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onetv:gravacao")
                ?.apply { setReferenceCounted(false); acquire(13 * 60 * 60_000L) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "onetv:gravacao")?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun encerrar() {
        if (Gravacoes.ocupado()) return
        runCatching { trava?.release() }; runCatching { wifi?.release() }
        trava = null; wifi = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        // O sistema matou o serviço: a gravação para (o que foi escrito fica) e o alarme segue para as próximas.
        Gravacoes.parar(this)
        runCatching { trava?.release() }; runCatching { wifi?.release() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificação

    private fun atualizarNotificacao(s: Gravacoes.Sessao) =
        mostrarNotificacao(getString(R.string.grv_notif, s.canalNome, Gravacoes.hora(s.fim)))

    private fun mostrarNotificacao(texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CANAL_NOTIF) == null)
            nm?.createNotificationChannel(NotificationChannel(CANAL_NOTIF, getString(R.string.grv_canal_notif), NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(this, 1, Intent(this, GravacaoServico::class.java).setAction(ACAO_PARAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL_NOTIF) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_gravando).setContentTitle(getString(R.string.grv_titulo)).setContentText(texto)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.grv_parar), parar).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_NOTIF, n)
    }
}

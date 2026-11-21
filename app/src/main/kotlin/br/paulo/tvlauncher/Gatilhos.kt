package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.alarm.Agenda
import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.alarm.FiltroRede
import br.paulo.tvlauncher.alarm.RelogioGatilhos
import br.paulo.tvlauncher.home.HomeCardsTv
import br.paulo.tvlauncher.home.MidiaRecente
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.settings.Extras
import org.json.JSONObject

/**
 * Eventos como gatilho (14/11/2026, ideia 7): "quando X acontecer, rode a cena Y". Os eventos:
 *  - filme começou / pausou: o player de Filmes e Séries (processo :player, avisa por [GatilhoReceiver]) e qualquer app
 *    com sessão de mídia (Netflix, YouTube...; precisa do acesso às notificações, o mesmo do "Tocando agora");
 *  - TV ligou (a tela acendeu);
 *  - todos saíram (Presenca, a mesma regra que apaga a TV);
 *  - chuva chegando (AvisoChuva, quando o aviso sai).
 * Quem roda é [Cenas.rodar]: o catálogo fechado e as Confirmações de sempre. Cada evento dispara no máximo uma vez a
 * cada [INTERVALO_MS] (um app que pisca tocar/pausar não vira pisca-pisca de luz). "Pausou" só vale depois de
 * [ESPERA_PAUSA_MS] pausado: avançar ou trocar de episódio não acende a luz.
 *
 * Gravado em "launcher" (chave "gatilhos"), que já vai no backup completo.
 */
object Gatilhos {
    private const val TAG = "TvLauncher.Gatilho"
    private const val CHAVE = "gatilhos"
    const val INTERVALO_MS = 20_000L
    const val ESPERA_PAUSA_MS = 3_000L
    const val ACAO_MIDIA = "br.paulo.tvlauncher.GATILHO_MIDIA"

    enum class Evento(val chave: String) { MIDIA_TOCOU("midia_tocou"), MIDIA_PAUSOU("midia_pausou"), TV_LIGOU("tv_ligou"),
        TODOS_SAIRAM("todos_sairam"), CHUVA("chuva"),
        // Elevação 10.6 (17/11/2026)
        FILME_ACABOU("filme_acabou"), PROGRAMA_COMECOU("programa_comecou"), NASCER_DO_SOL("nascer_do_sol"), POR_DO_SOL("por_do_sol"),
        CAMPAINHA("campainha"), ALGUEM_CHEGOU("alguem_chegou"), INTERNET_CAIU("internet_caiu"), INTERNET_VOLTOU("internet_voltou"),
        RECADO_CHEGOU("recado_chegou"), ANIVERSARIO("aniversario"), COMPROMISSO("compromisso") }

    /** Os eventos que são hora (o relógio de minuto só roda se algum deles tem cena). */
    val DE_HORA = setOf(Evento.NASCER_DO_SOL, Evento.POR_DO_SOL, Evento.ANIVERSARIO, Evento.COMPROMISSO)

    /** {"midia_tocou": "Cinema"}: evento → nome da cena. Ignora evento desconhecido e nome inválido. Pura. */
    fun lerJson(texto: String?): Map<Evento, String> = try {
        val o = JSONObject(texto ?: "{}")
        Evento.values().mapNotNull { e -> o.optString(e.chave).trim().takeIf { Cenas.nomeValido(it) }?.let { e to it } }.toMap()
    } catch (e: Exception) { emptyMap() }

    fun paraJson(m: Map<Evento, String>): String = JSONObject().apply { for ((e, c) in m) put(e.chave, c) }.toString()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE)
    fun todos(ctx: Context): Map<Evento, String> = lerJson(prefs(ctx).getString(CHAVE, null))
    fun salvar(ctx: Context, m: Map<Evento, String>) = prefs(ctx).edit().putString(CHAVE, paraJson(m)).apply()

    // Elevação 10.5: "só se" de cada evento (SoSe.kt).
    fun soSe(ctx: Context): Map<Evento, List<SoSe.Condicao>> = SoSe.lerJson(prefs(ctx).getString("gatilhos_so_se", null))
    fun salvarSoSe(ctx: Context, m: Map<Evento, List<SoSe.Condicao>>) = prefs(ctx).edit().putString("gatilhos_so_se", SoSe.paraJson(m)).apply()

    private val ultimo = HashMap<Evento, Long>()

    /** Pura: o evento pode disparar agora? (no máximo um a cada [INTERVALO_MS]) */
    fun podeDisparar(anterior: Long?, agora: Long): Boolean = anterior == null || agora - anterior >= INTERVALO_MS

    /** Aconteceu [e]: roda a cena ligada a ele, se houver. Qualquer thread. */
    fun disparar(ctx: Context, e: Evento) {
        val cena = todos(ctx)[e] ?: return
        val agora = SystemClock.elapsedRealtime()
        synchronized(ultimo) { if (!podeDisparar(ultimo[e], agora)) return; ultimo[e] = agora }
        val app = ctx.applicationContext
        Fundo.rodar {
            val cond = soSe(app)[e].orEmpty()
            if (cond.isNotEmpty() && !SoSe.todas(cond, SoSeTv.estado(app))) { Log.i(TAG, "${e.chave}: só se ${cond.map { it.chave.substringBefore(':') }} não vale agora"); return@rodar }
            Log.i(TAG, "${e.chave}: ${Cenas.rodar(app, cena, "evento")}")
        }
    }

    // ---------------------------------------------------------------- mídia

    private val principal by lazy { Handler(Looper.getMainLooper()) }   // lazy: os testes JVM não têm Looper
    private val filtro = FiltroMidia(ESPERA_PAUSA_MS)
    private var relogioPausa: Runnable? = null

    /** Tocando/pausado mudou (player próprio ou sessão de outro app). Thread principal. */
    fun midia(ctx: Context, tocando: Boolean) {
        val app = ctx.applicationContext
        val agora = SystemClock.uptimeMillis()
        relogioPausa?.let { principal.removeCallbacks(it) }; relogioPausa = null
        if (tocando) { filtro.tocou()?.let { disparar(app, it) }; return }
        val prazo = filtro.pausou(agora) ?: return
        relogioPausa = Runnable { relogioPausa = null; filtro.venceu(SystemClock.uptimeMillis())?.let { disparar(app, it) } }
            .also { principal.postAtTime(it, prazo) }
    }

    /** Do processo :player (VodActivity): avisa o processo principal, onde as cenas rodam. [acabou] = o filme chegou ao fim (10.6). */
    fun avisarDoPlayer(ctx: Context, tocando: Boolean, acabou: Boolean = false) {
        try {
            ctx.sendBroadcast(Intent(ACAO_MIDIA).setComponent(ComponentName(ctx, GatilhoReceiver::class.java)).putExtra("tocando", tocando)
                .putExtra("acabou", acabou))
        } catch (e: Exception) { Log.w(TAG, "aviso do player: $e") }
    }

    private var iniciado = false
    private val sessoes = HashMap<MediaController, MediaController.Callback>()

    /** Liga os ouvintes (tela acesa e sessões de mídia). Chamado pelo serviço de acessibilidade; repetir não faz nada. */
    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        try {
            // SCREEN_ON é transmissão protegida (só o sistema envia); NOT_EXPORTED deixa isso explícito onde existe.
            val tela = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != Intent.ACTION_SCREEN_ON) return
                    disparar(app, Evento.TV_LIGOU)
                    CuidarDeLongeTv.tvLigou(app)   // Cuidar de longe: "ligou hoje" (79) e lembrete que esperava a tela (78)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(tela, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(tela, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (e: Exception) { Log.w(TAG, "tela: $e") }
        iniciarRede(app)
        principal.postDelayed(relogio(app), 60_000)
        // Sessões de mídia de outros apps: só com o acesso às notificações (sem ele, SecurityException: fica só o player).
        try {
            val msm = app.getSystemService(MediaSessionManager::class.java) ?: return
            val ouvinte = ComponentName(app, OuvinteNotificacoes::class.java)
            val aoMudar = MediaSessionManager.OnActiveSessionsChangedListener { cs -> acompanhar(app, cs.orEmpty()) }
            msm.addOnActiveSessionsChangedListener(aoMudar, ouvinte, principal)
            acompanhar(app, msm.getActiveSessions(ouvinte))
        } catch (e: SecurityException) { Log.i(TAG, "sem acesso às notificações: só o player próprio dispara mídia") }
        catch (e: Exception) { Log.w(TAG, "sessões: $e") }
    }

    // ---------------------------------------------------------------- 10.6: internet e hora

    private val filtroRede = FiltroRede()

    private fun iniciarRede(app: Context) {
        try {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: android.net.Network) { principal.post { synchronized(filtroRede) { filtroRede.voltou() }?.let { disparar(app, it) } } }
                override fun onLost(n: android.net.Network) { principal.post { synchronized(filtroRede) { filtroRede.perdeu(SystemClock.elapsedRealtime()) } } }
            }, principal)
        } catch (e: Exception) { Log.w(TAG, "rede: $e") }
    }

    @Volatile private var conferidoEm = 0L

    /** A cada minuto: internet que caiu há 1 min, e os eventos de hora (só se algum tem cena; o trabalho vai ao Fundo). */
    private fun relogio(app: Context): Runnable = object : Runnable {
        override fun run() {
            principal.postDelayed(this, 60_000)
            synchronized(filtroRede) { filtroRede.conferir(SystemClock.elapsedRealtime()) }?.let { disparar(app, it) }
            val agora = System.currentTimeMillis()
            val antes = conferidoEm.takeIf { it > 0 } ?: (agora - 60_000)
            conferidoEm = agora
            // Lembrete da casa com hora (HomeCards, 18/11/2026): o aviso sai na hora mesmo sem ninguém voltar à HOME.
            if (Extras.ligado(app, Extras.Chave.CARTAO_LEMBRETE_CASA)) Fundo.rodar("lembretes-casa") { runCatching { HomeCardsTv.avisarLembretes(app) } }
            val ligados = todos(app).keys.intersect(DE_HORA)
            if (ligados.isEmpty()) return
            Fundo.rodar("gatilhos-hora") {
                if (Evento.NASCER_DO_SOL in ligados || Evento.POR_DO_SOL in ligados) SoSeTv.sol(app, agora)?.let { (nascer, por) ->
                    if (RelogioGatilhos.caiu(antes, agora, nascer)) disparar(app, Evento.NASCER_DO_SOL)
                    if (RelogioGatilhos.caiu(antes, agora, por)) disparar(app, Evento.POR_DO_SOL)
                }
                if (Evento.ANIVERSARIO in ligados &&
                    RelogioGatilhos.caiu(antes, agora, runCatching { RelogioGatilhos.aniversarioHoje(Aniversarios.ler(app), agora) }.getOrNull()))
                    disparar(app, Evento.ANIVERSARIO)
                if (Evento.COMPROMISSO in ligados) {
                    val ev = runCatching { Agenda.eventosDaTv(app, antes - 60_000, agora + 60_000) }.getOrDefault(emptyList())
                    if (RelogioGatilhos.compromissoComecou(antes, agora, ev.map { it.inicio to it.diaInteiro })) disparar(app, Evento.COMPROMISSO)
                }
            }
        }
    }

    private fun acompanhar(app: Context, cs: List<MediaController>) {
        for ((c, cb) in sessoes) c.unregisterCallback(cb)
        sessoes.clear()
        for (c in cs) {
            if (c.packageName == app.packageName) continue   // o nosso player já avisa por conta própria
            // "Continuar" da cruz (18/11/2026): o último título de cada app de mídia. Junta rajadas: no máximo uma
            // anotação a cada 1,5 s (apps que mandam a posição a cada segundo não seguram a anotação para sempre).
            var pendente = false
            val anotar = Runnable { pendente = false; runCatching { MidiaRecente.anotar(app, c) } }
            fun pedirAnotar() { if (!pendente) { pendente = true; principal.postDelayed(anotar, 1500) } }
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(s: PlaybackState?) {
                    when (s?.state) {
                        PlaybackState.STATE_PLAYING -> midia(app, true)
                        PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> midia(app, false)
                        else -> {}
                    }
                    pedirAnotar()
                }
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = pedirAnotar()
            }
            c.registerCallback(cb, principal)
            sessoes[c] = cb
            pedirAnotar()
        }
    }
}

/**
 * O lado puro de "tocou / pausou" (GatilhosTest). Tocou só dispara se o último evento não foi tocou (play depois de
 * avançar não repete); pausou espera [esperaMs]: se voltar a tocar antes, nada acontece.
 */
class FiltroMidia(private val esperaMs: Long) {
    private var ultimo: Gatilhos.Evento? = null
    private var prazo: Long? = null

    fun tocou(): Gatilhos.Evento? {
        prazo = null
        if (ultimo == Gatilhos.Evento.MIDIA_TOCOU) return null
        ultimo = Gatilhos.Evento.MIDIA_TOCOU
        return ultimo
    }

    /** Devolve quando conferir de novo (ou null: nada a esperar, ex.: nem estava tocando). */
    fun pausou(agora: Long): Long? {
        if (ultimo != Gatilhos.Evento.MIDIA_TOCOU) return null
        return (agora + esperaMs).also { prazo = it }
    }

    fun venceu(agora: Long): Gatilhos.Evento? {
        val p = prazo ?: return null
        if (agora < p) return null
        prazo = null
        ultimo = Gatilhos.Evento.MIDIA_PAUSOU
        return ultimo
    }
}

/** Recebe o "tocando/pausado" do player (processo :player) no processo principal. Não exportado. */
class GatilhoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Gatilhos.ACAO_MIDIA) return
        if (i.getBooleanExtra("acabou", false)) Gatilhos.disparar(ctx, Gatilhos.Evento.FILME_ACABOU)
        else Gatilhos.midia(ctx, i.getBooleanExtra("tocando", false))
    }
}

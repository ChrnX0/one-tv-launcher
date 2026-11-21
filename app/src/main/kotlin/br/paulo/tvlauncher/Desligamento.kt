package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.alarm.SonoSuave
import br.paulo.tvlauncher.network.AcordarComLg
import br.paulo.tvlauncher.network.LgNetcast
import br.paulo.tvlauncher.network.VolumeLg
import br.paulo.tvlauncher.settings.irPara

/**
 * "Desligar em X min" (05/11/2026, bloco 5 do brainstorm): o timer de sono da TV. Diferente do "desligar sozinha
 * sem uso" (PR #17), este desliga na hora marcada, com ou sem gente mexendo no controle.
 *
 * Quem liga: os atalhos de voz ("Ok Google, abrir Desligar em 30 minutos", ver AtalhoVozActivity).
 * Como: um alarme do Android (AlarmManager) acorda o [DesligamentoReceiver] 1 min antes (aviso na tela) e na hora
 * (apaga a tela; o Chromecast manda a TV para standby pelo HDMI-CEC, como o botão de ligar do controle).
 * Um alarme do sistema sobrevive ao launcher ser fechado pelo Android; só o reboot o apaga (e aí a TV já desligou).
 * A hora marcada fica nas preferências "launcher" ([CHAVE], milissegundos; 0 = nenhum).
 */
object Desligamento {
    private const val TAG = "TvLauncher.Desligar"
    const val CHAVE = "desligar_em"
    /** Quantos minutos foram escolhidos na última marcação: o painel rápido avança a partir dele (15 → 30 → ...). */
    const val CHAVE_MIN = "desligar_min"
    const val ACAO_AVISO = "br.paulo.tvlauncher.DESLIGAR_AVISO"
    const val ACAO_DESLIGAR = "br.paulo.tvlauncher.DESLIGAR_AGORA"
    /** Desligar suave (12/11/2026): 5 min antes, a tela escurece e o volume desce aos poucos (SonoSuave). */
    const val ACAO_SUAVE = "br.paulo.tvlauncher.DESLIGAR_SUAVE"
    private const val AVISO_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE)

    /** Quando a TV vai desligar (ms desde 1970), ou null se não há timer de sono. */
    fun marcado(ctx: Context): Long? = prefs(ctx).getLong(CHAVE, 0).takeIf { it > System.currentTimeMillis() }

    /** Os minutos escolhidos no timer que está valendo (null sem timer ou se foi marcado antes desta chave existir). */
    fun escolhido(ctx: Context): Int? =
        if (marcado(ctx) == null) null else prefs(ctx).getInt(CHAVE_MIN, 0).takeIf { it > 0 }

    /** Marca para daqui a [minutos]. Marcar de novo substitui o anterior (não soma). */
    fun agendar(ctx: Context, minutos: Int) {
        val em = System.currentTimeMillis() + minutos * 60_000L
        prefs(ctx).edit().putLong(CHAVE, em).putInt(CHAVE_MIN, minutos).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        // setExactAndAllowWhileIdle: na hora exata mesmo com o aparelho em repouso (o app tem USE_EXACT_ALARM).
        if (minutos >= 2) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, em - AVISO_MS, pendente(ctx, ACAO_AVISO))
        // Desligar suave: a descida começa 5 min antes (ou já, se o timer é mais curto que isso).
        SonoSuave.parar(ctx)
        if (minutos * 60_000L > SonoSuave.DURACAO_MS)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, em - SonoSuave.DURACAO_MS, pendente(ctx, ACAO_SUAVE))
        else SonoSuave.comecar(ctx, em)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, em, pendente(ctx, ACAO_DESLIGAR))
        Log.i(TAG, "a TV desliga em $minutos min")
    }

    /** Desmarca. Devolve true se havia algo marcado. */
    fun cancelar(ctx: Context): Boolean {
        val havia = marcado(ctx) != null
        prefs(ctx).edit().remove(CHAVE).remove(CHAVE_MIN).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendente(ctx, ACAO_AVISO))
        am.cancel(pendente(ctx, ACAO_DESLIGAR))
        am.cancel(pendente(ctx, ACAO_SUAVE))
        SonoSuave.parar(ctx)   // cancelou: a tela clareia e o volume volta na hora
        Log.i(TAG, "timer de sono cancelado (havia: $havia)")
        return havia
    }

    /** O PendingIntent de cada etapa: o mesmo (action igual) a cada chamada, por isso cancelar() acha o agendado. */
    private fun pendente(ctx: Context, acao: String) = PendingIntent.getBroadcast(
        ctx, acao.hashCode(), Intent(ctx, DesligamentoReceiver::class.java).setAction(acao),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "30 minutos", "1 hora", "1 hora e 30 minutos": para os avisos na tela. */
    fun duracaoTexto(minutos: Int): String = duracaoFalada(minutos * 60)   // no idioma da TV (Textos.kt)

    /**
     * Hora de desligar. A LG ignora o standby do CEC (verificado 14/11/2026), então, se ela está pareada, ela desliga
     * PRIMEIRO pela rede (com o volume de antes devolvido) e o Chromecast apaga [DEPOIS_DA_LG_MS] depois. Na ordem
     * inversa (16/11) a LG ficava 15 s sem sinal e mostrava a tela de entradas antes de desligar. Se a LG não
     * responder, o Chromecast apaga mesmo assim, no máximo [PRAZO_LG_MS] depois.
     * Chamar na thread principal (o volume de antes do SonoSuave é lido aqui).
     */
    fun desligarTudo(ctx: Context) {
        val app = ctx.applicationContext
        val feito = java.util.concurrent.atomic.AtomicBoolean(false)
        val apagar = Runnable {
            if (!feito.compareAndSet(false, true)) return@Runnable
            apagarTela(app)
            SonoSuave.parar(app, esperarMs = 8_000L)   // a película sai e o volume do Android volta depois que dormiu
        }
        lgPrimeiro(app, SonoSuave.tomarLgAntes(), apagar)
    }

    /**
     * Desliga a LG pela rede (se pareada e com "desligar junto") e roda [depois] (apagar o Chromecast) em seguida.
     * Decisão do Paulo (17/11/2026): só desliga a LG se ela está NA ENTRADA DO CHROMECAST. Na antena ou em outro HDMI
     * alguém está vendo outra coisa na LG: o timer e o "desligar sem uso" apagam só o Chromecast. Entrada que não deu
     * para ler = na dúvida, não desliga (a mesma regra do [AcordarComLg]). [lgAntes] > 0 = volume a devolver antes.
     */
    fun lgPrimeiro(app: Context, lgAntes: Int, depois: Runnable) {
        val ui = Handler(Looper.getMainLooper())
        if (!VolumeLg.configurado(app) || !VolumeLg.desligarJunto(app)) { depois.run(); return }
        ui.postDelayed(depois, PRAZO_LG_MS)   // garantia: a LG sumiu da rede ou demorou
        Fundo.rodar("Desligar-lg") {
            val e = VolumeLg.entrada(app)
            val nosso = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            if (!LgNetcast.desligarLgJunto(e, nosso, VolumeLg.entradaAprendida(app))) {
                Log.i(TAG, "LG fica ligada: entrada ${e?.nome ?: "não lida"} não é a do Chromecast")
                if (e != null && lgAntes > 0) VolumeLg.irPara(app, lgAntes)   // o volume que o sono suave baixou volta
                ui.post(depois); return@rodar
            }
            if (lgAntes > 0) VolumeLg.irPara(app, lgAntes)
            val ok = VolumeLg.desligar(app)
            if (ok) lgDesligadaEm = SystemClock.elapsedRealtime()
            Log.i(TAG, "LG desligada pela rede: $ok")
            ui.postDelayed(depois, if (ok) DEPOIS_DA_LG_MS else 0L)
        }
    }
    @Volatile private var lgDesligadaEm = 0L

    /**
     * O Chromecast dormiu por conta própria (tempo parado + protetor de tela, botão do controle dele...). O Android manda
     * <Standby> pelo CEC, mas a LG da Sala RECUSA (Feature Abort 36:04, visto em 19/11/2026 às 18:31 e 18:32) e fica
     * ligada sem sinal. Então, como no timer de sono: se ela está na rede e na entrada do Chromecast, desliga pela rede.
     * Espera [ESPERA_APAGAR_MS] antes: se foi o botão do controle (que também manda IR para a LG), ela já saiu da rede.
     * Chamado no ACTION_SCREEN_OFF ([AcordarComLg]).
     */
    fun lgAoApagar(ctx: Context) {
        val app = ctx.applicationContext
        if (!VolumeLg.configurado(app) || !VolumeLg.desligarJunto(app)) return
        Fundo.rodar("Apagou-lg") {
            Thread.sleep(ESPERA_APAGAR_MS)
            if (telaAcesa(app)) return@rodar   // acordou de novo
            if (!LgNetcast.desligarAoApagar(SystemClock.elapsedRealtime(), lgDesligadaEm)) return@rodar
            if (!VolumeLg.naRede(app)) { Log.i(TAG, "Chromecast dormiu; LG já fora da rede"); return@rodar }
            val e = VolumeLg.entrada(app)
            val nosso = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            if (!LgNetcast.desligarLgJunto(e, nosso, VolumeLg.entradaAprendida(app))) {
                Log.i(TAG, "Chromecast dormiu; LG fica ligada: entrada ${e?.nome ?: "não lida"} não é a do Chromecast")
                return@rodar
            }
            val ok = VolumeLg.desligar(app)
            if (ok) lgDesligadaEm = SystemClock.elapsedRealtime()
            Log.i(TAG, "Chromecast dormiu; LG desligada pela rede: $ok")
        }
    }
    private const val ESPERA_APAGAR_MS = 4_000L
    private const val DEPOIS_DA_LG_MS = 2_000L
    private const val PRAZO_LG_MS = 25_000L

    internal fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    /**
     * Apaga a tela pelo 1º caminho que funcionar: a ação de acessibilidade "bloquear tela" (Android 9+) e, se a tela
     * continuar acesa 3 s depois, o lockNow do Device Owner. Os dois só apagam a tela: nada se perde.
     */
    fun apagarTela(ctx: Context) {
        val app = ctx.applicationContext
        val ui = Handler(Looper.getMainLooper())
        val caminhos = listOf<Pair<String, () -> Boolean>>(
            "acessibilidade" to {
                AutorizaShizukuService.instancia?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
            },
            "Device Owner" to { app.getSystemService(DevicePolicyManager::class.java).lockNow(); true },
        )
        fun tentar(i: Int) {
            if (i >= caminhos.size || !telaAcesa(app)) return
            val (nome, fazer) = caminhos[i]
            val aceitou = try { fazer() } catch (e: Exception) { Log.w(TAG, "$nome falhou: $e"); false }
            Log.i(TAG, "apagando a tela pela $nome: ${if (aceitou) "aceito" else "recusado"}")
            ui.postDelayed({ if (telaAcesa(app)) tentar(i + 1) else Log.i(TAG, "tela apagada pela $nome") }, if (aceitou) 3000 else 0)
        }
        tentar(0)
    }
}

/** Recebe os dois alarmes do [Desligamento]. Não exportado: só o próprio app (via AlarmManager) o chama. */
class DesligamentoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Desmarcado entre o agendamento e agora (ou trocado por outro horário): ignora o alarme velho.
        val marcado = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getLong(Desligamento.CHAVE, 0)
        if (marcado == 0L) return
        if (intent.action == Desligamento.ACAO_DESLIGAR && marcado - System.currentTimeMillis() > 30_000) return
        when (intent.action) {
            Desligamento.ACAO_AVISO -> if (Desligamento.telaAcesa(ctx))
                AvisoTv.rapido(ctx, ctx.getString(R.string.dl_um_minuto), ctx.getString(R.string.dl_para_nao, ctx.getString(R.string.lbl_nao_desligar)), 20)
            Desligamento.ACAO_SUAVE -> if (Desligamento.telaAcesa(ctx)) SonoSuave.comecar(ctx, marcado)
            Desligamento.ACAO_DESLIGAR -> {
                ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().remove(Desligamento.CHAVE).apply()
                Desligamento.desligarTudo(ctx)   // LG primeiro (pela rede), depois o Chromecast
            }
        }
    }
}

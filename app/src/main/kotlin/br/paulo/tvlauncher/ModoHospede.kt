package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.paulo.tvlauncher.family.HospedeRegras
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.PinNaTela
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelControle
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.privilege.PrivilegioDO
import java.util.TimeZone

/**
 * Modo hóspede (degrau 2, 16/11/2026, casa de aluguel tipo Airbnb): a parte do Android. Regras em HospedeRegras.kt.
 *
 * Ligar = liga também o modo visita (perfil, agenda e apps pessoais somem). Desligar pede o PIN do painel, em
 * qualquer lugar da TV: o modo visita não desliga sozinho enquanto o hóspede estiver ligado (ModoVisita.definir).
 * Tudo nas prefs "modo_visita" (vão só no backup completo cifrado, como a senha do Wi-Fi das visitas).
 *
 * A limpeza ([encerrarEstadia]) apaga os dados dos apps de streaming: com Device Owner pelo DevicePolicyManager
 * (PainelControle.limparDados), sem ele pelo shell próprio (`pm clear`). Roda na hora da saída marcada (alarme) ou
 * pelo botão "Encerrar estadia agora". Depois, o próximo hóspede vê as boas-vindas na primeira HOME.
 */
object ModoHospede {
    private const val TAG = "TvLauncher.Hospede"
    private const val PREFS = "modo_visita"
    private const val ATIVO = "hospede_ativo"
    private const val SAIDA = "hospede_saida"
    private const val HORA = "hospede_hora"
    private const val REGRAS = "hospede_regras"
    private const val APPS = "hospede_apps"
    private const val BOAS_VINDAS = "hospede_boas_vindas"
    private const val ULTIMA = "hospede_ultima_limpeza"
    private const val TOTAL = "hospede_total"   // degrau 3: limpeza total (todos os apps da TV)
    const val ACAO_SAIDA = "br.paulo.tvlauncher.HOSPEDE_SAIDA"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zona get() = TimeZone.getDefault()

    fun ativo(ctx: Context) = prefs(ctx).getBoolean(ATIVO, false)

    /**
     * Liga o modo. Desligar é [desligarComPin] (na TV) ou [desligar] (painel/adb, que já são autenticados).
     * Sem PIN do painel definido NÃO liga (17/11/2026): com o hóspede ligado as Configurações e a saída do modo pedem o
     * PIN, e sem PIN nenhum dos dois abriria mais pela TV. Devolve se ligou.
     */
    fun ligar(ctx: Context): Boolean {
        if (!PainelArmazem.temPin(ctx)) return false
        prefs(ctx).edit().putBoolean(ATIVO, true).putBoolean(BOAS_VINDAS, true).apply()
        ModoVisita.definir(ctx, true)
        agendar(ctx)
        return true
    }

    fun desligar(ctx: Context) {
        prefs(ctx).edit().putBoolean(ATIVO, false).commit()   // commit: o ModoVisita.definir logo abaixo lê o valor novo
        ModoVisita.definir(ctx, false)
        agendar(ctx)
    }

    /** Pede o PIN na tela e desliga. Sem PIN definido, não desliga (avisa onde criar). */
    fun desligarComPin(a: Activity, fim: () -> Unit = {}) =
        PinNaTela.pedir(a, a.getString(R.string.hos_pin_sair)) { desligar(a); fim() }

    // ------------------------------------------------------------------ saída (check-out)

    fun saida(ctx: Context) = prefs(ctx).getLong(SAIDA, 0L)
    fun hora(ctx: Context) = prefs(ctx).getInt(HORA, HospedeRegras.HORA_PADRAO)

    /** OK em "Saída do hóspede": o próximo dia (ou desligada). */
    fun proximaSaida(ctx: Context): Long {
        val n = HospedeRegras.proximaSaida(saida(ctx), System.currentTimeMillis(), hora(ctx), zona)
        prefs(ctx).edit().putLong(SAIDA, n).apply()
        agendar(ctx)
        return n
    }

    /** OK em "Hora da saída": a hora seguinte; a saída marcada passa para a hora nova no mesmo dia. */
    fun proximaHora(ctx: Context): Int {
        val h = HospedeRegras.proximaHora(hora(ctx))
        val agora = System.currentTimeMillis()
        val s = saida(ctx)
        // Hora nova que já passou hoje: vai para o dia seguinte (antes virava 0 e a saída sumia sem aviso).
        val nova = if (s > agora) HospedeRegras.diasAte(agora, s, zona).let { d ->
            HospedeRegras.saidaEm(agora, d, h, zona).takeIf { it > agora } ?: HospedeRegras.saidaEm(agora, d + 1, h, zona)
        } else 0L
        prefs(ctx).edit().putInt(HORA, h).putLong(SAIDA, nova).apply()
        agendar(ctx)
        return h
    }

    /** "Hoje às 11:00", "Amanhã às 11:00", "qua., 18 de nov. às 11:00" ou "Não marcada". */
    fun textoSaida(ctx: Context): String {
        val s = saida(ctx)
        if (s <= 0L) return ctx.getString(R.string.hos_saida_nenhuma)
        val hora = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(s))
        return when (val d = HospedeRegras.diasAte(System.currentTimeMillis(), s, zona)) {
            0 -> ctx.getString(R.string.hos_saida_hoje, hora)
            1 -> ctx.getString(R.string.hos_saida_amanha, hora)
            else -> {
                val padrao = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM")
                ctx.getString(R.string.hos_saida_dia, java.text.SimpleDateFormat(padrao, java.util.Locale.getDefault()).format(java.util.Date(s)), hora)
            }
        }
    }

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 7301,
        Intent(ctx, HospedeReceiver::class.java).setAction(ACAO_SAIDA),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Agenda (ou cancela) o alarme da saída. Chamado ao mudar algo e a cada processo novo (atualizar apaga alarmes). */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val s = saida(ctx)
        am.cancel(pendente(ctx))
        if (!ativo(ctx) || s <= 0L) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s, pendente(ctx))
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s, pendente(ctx))
        } catch (e: Exception) { Log.w(TAG, "alarme da saída: $e") }
    }

    /** Rede de segurança (HOME voltou, processo novo): a saída passou e o alarme não veio (TV fora da tomada). */
    fun conferirSaida(ctx: Context) {
        if (ativo(ctx) && HospedeRegras.venceu(saida(ctx), System.currentTimeMillis())) Fundo.rodar("hospede-saida") { encerrarEstadia(ctx) }
    }

    // ------------------------------------------------------------------ limpeza

    fun regras(ctx: Context) = prefs(ctx).getString(REGRAS, "") ?: ""
    fun definirRegras(ctx: Context, t: String) = prefs(ctx).edit().putString(REGRAS, t.take(600)).apply()

    /** A lista escolhida pelo dono, ou null (= os apps de streaming conhecidos). */
    fun escolhidos(ctx: Context): Set<String>? = prefs(ctx).getStringSet(APPS, null)?.let { HashSet(it) }

    fun alternarApp(ctx: Context, pacote: String): Boolean {
        val lista: MutableSet<String> = escolhidos(ctx)?.toMutableSet() ?: paraLimpar(ctx).toMutableSet()
        val marcado = if (pacote in lista) { lista.remove(pacote); false } else { lista.add(pacote); true }
        prefs(ctx).edit().putStringSet(APPS, lista).apply()
        return marcado
    }

    fun marcado(ctx: Context, pacote: String) = pacote in (escolhidos(ctx) ?: paraLimpar(ctx).toSet())

    /** Os apps que a limpeza vai apagar agora (os que têm ícone na TV, menos os protegidos; HospedeRegras.paraLimpar). */
    fun paraLimpar(ctx: Context): List<String> =
        HospedeRegras.paraLimpar(escolhidos(ctx), lancaveis(ctx), setOf(ctx.packageName, Config.PACOTE_CONFIG), total(ctx))

    /** Os apps com ícone na TV (leanback, e os de celular que a TV abre). */
    fun lancaveis(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        fun de(cat: String) = try {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat), 0).map { it.activityInfo.packageName }
        } catch (_: Exception) { emptyList() }
        return (de(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER) + de(android.content.Intent.CATEGORY_LAUNCHER)).toSet()
    }

    /** Degrau 3, "limpeza total": sem lista escolhida, apaga os dados de TODOS os apps da TV (menos os protegidos). */
    fun total(ctx: Context) = prefs(ctx).getBoolean(TOTAL, false)
    fun definirTotal(ctx: Context, sim: Boolean) { prefs(ctx).edit().putBoolean(TOTAL, sim).remove(APPS).apply() }

    /**
     * Fim da estadia: apaga os dados dos apps, desmarca a saída e deixa as boas-vindas para o próximo. Lento (até 30 s
     * por app): chame fora da thread da tela. Devolve o resumo ("3 apps limpos" ou a lista do que falhou).
     */
    @Synchronized fun encerrarEstadia(ctx: Context): String {
        val app = ctx.applicationContext
        val dono = PrivilegioDO.somosDonos(app)
        val falhas = ArrayList<String>()
        var ok = 0
        for (p in paraLimpar(app)) {
            // Com dono, os apps de fábrica (YouTube, Netflix) também: antes o limparDados os recusava e o próximo
            // hóspede achava a conta do anterior. Se o Android recusar, tenta o pm clear pelo shell.
            val erro = (if (dono) PainelControle.limparDados(app, p, deSistemaTambem = true) else "sem dono")
                ?.let { e -> if (GuardiaoShizuku.comando(app, "pm clear $p", 30_000)?.contains("Success") == true) null else if (dono) e else "shell" }
            if (erro == null) ok++ else falhas += "$p ($erro)"
        }
        val resumo = if (falhas.isEmpty()) app.getString(R.string.hos_limpou, ok) else app.getString(R.string.hos_limpou_falhas, ok, falhas.joinToString(", "))
        prefs(app).edit().putLong(SAIDA, 0L).putBoolean(BOAS_VINDAS, true)
            .putString(ULTIMA, "${android.text.format.DateFormat.getDateFormat(app).format(java.util.Date())} · $resumo").apply()
        agendar(app)
        Log.i(TAG, "estadia encerrada: $resumo")
        return resumo
    }

    fun ultimaLimpeza(ctx: Context) = prefs(ctx).getString(ULTIMA, null)

    // ------------------------------------------------------------------ boas-vindas

    /** Na HOME (onResume): primeira vez desta estadia = mostra as boas-vindas. */
    fun talvezBoasVindas(a: Activity) {
        if (!ativo(a) || !prefs(a).getBoolean(BOAS_VINDAS, false)) return
        prefs(a).edit().putBoolean(BOAS_VINDAS, false).apply()
        try { a.startActivity(Intent(a, HospedeBoasVindasActivity::class.java)) } catch (_: Exception) {}
    }
}

/** O alarme da hora da saída (ModoHospede.agendar). */
class HospedeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ModoHospede.ACAO_SAIDA || !ModoHospede.ativo(ctx)) return
        // Sem goAsync: a limpeza total pode levar minutos (até 30 s por app) e passava do prazo de 60 s do broadcast
        // (ANR). O processo do launcher fica vivo pelo serviço de acessibilidade, então a thread termina o trabalho.
        val app = ctx.applicationContext
        Fundo.rodar("hospede-saida") { ModoHospede.encerrarEstadia(app) }
    }
}

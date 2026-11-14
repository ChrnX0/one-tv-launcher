package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log

/**
 * Regras puras dos timers (ideia 31, 14/11/2026; TimerRegrasTest): até [MAX] timers com nome ao mesmo tempo e um
 * cronômetro. Antes era um timer só ("na TV não faz sentido uma pilha de timers", 05/11/2026): o estudo de 13/11 reabriu
 * a escolha (arroz e feijão ao mesmo tempo) e o dono aprovou. Continua curto: 3 no máximo, e a HOME mostra o próximo.
 */
object TimerRegras {
    const val MAX = 3

    /** Um timer: [id] estável, [inicio] e [fim] em ms, [nome] ("" = sem nome). */
    data class Item(val id: Long, val inicio: Long, val fim: Long, val nome: String)

    /** Os que ainda correm, do que acaba primeiro ao último. */
    fun correndo(l: List<Item>, agora: Long): List<Item> = l.filter { it.fim > agora }.sortedBy { it.fim }

    /**
     * Junta [novo] à lista: tira os vencidos; um timer com o MESMO nome é trocado (pedir "arroz 10 min" de novo
     * recomeça o arroz); cheio, sai o mais antigo (o que começou primeiro).
     */
    fun adicionar(l: List<Item>, novo: Item, agora: Long): List<Item> {
        var r = l.filter { it.fim > agora }
        if (novo.nome.isNotBlank()) r = r.filterNot { it.nome.trim().equals(novo.nome.trim(), ignoreCase = true) }
        while (r.size >= MAX) r = r - r.minBy { it.inicio }
        return (r + novo).sortedBy { it.fim }
    }

    /** Os que acabaram até [agora] (com 1 s de folga: o alarme do Android pode chegar um pouquinho antes) e os que ficam. */
    fun separarVencidos(l: List<Item>, agora: Long): Pair<List<Item>, List<Item>> = l.partition { it.fim <= agora + 1000 }

    /** O nome que a tela do fim mostra: "Arroz", "Arroz e Feijão"; vazio = sem nome. */
    fun nomes(l: List<Item>, e: String): String {
        val n = l.map { it.nome.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (n.size <= 1) n.firstOrNull().orEmpty() else n.dropLast(1).joinToString(", ") + e + n.last()
    }

    /** "4:05" (até 1 h) ou "1:04:05". */
    fun relogio(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /** Cronômetro: "12:04" desde [inicio] (conta para cima; arredonda para baixo). */
    fun cronometro(inicio: Long, agora: Long): String {
        val s = ((agora - inicio) / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /**
     * O texto da pílula da HOME: o timer que acaba primeiro ("4:32 · Arroz"), "+1" se houver outros, e o cronômetro
     * ("↑ 12:04") se estiver correndo. null = nada correndo (a pílula some).
     */
    fun pilula(l: List<Item>, cronoInicio: Long, agora: Long): String? {
        val c = correndo(l, agora)
        val partes = mutableListOf<String>()
        c.firstOrNull()?.let { t ->
            partes += relogio(t.fim - agora) + (if (t.nome.isNotBlank()) "  ·  ${t.nome}" else "") + (if (c.size > 1) "  +${c.size - 1}" else "")
        }
        if (cronoInicio > 0) partes += "↑ " + cronometro(cronoInicio, agora)
        return if (partes.isEmpty()) null else partes.joinToString("   ")
    }

    fun ler(json: String?): List<Item> = try {
        val a = org.json.JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val fim = o.optLong("fim", 0)
            if (fim <= 0) null else Item(o.optLong("id", fim), o.optLong("inicio", fim), fim, o.optString("nome", "").take(40))
        }
    } catch (e: Exception) { emptyList() }

    fun escrever(l: List<Item>): String = org.json.JSONArray().apply {
        l.forEach { put(org.json.JSONObject().put("id", it.id).put("inicio", it.inicio).put("fim", it.fim).put("nome", it.nome)) }
    }.toString()
}

/**
 * Timers da cozinha (05/11/2026, bloco 5 do brainstorm): "Ok Google, timer de 10 minutos". Desde 14/11/2026 (ideia 31)
 * até 3 ao mesmo tempo, com nome, e um cronômetro (regras em [TimerRegras]).
 *
 * Por que é nosso: nesta TV o único app que atende o pedido padrão do Android de timer (AlarmClock.ACTION_SET_TIMER)
 * é um "stub" de fábrica (com.android.tv.frameworkpackagestubs, prioridade -1) que não faz nada. Medido na TV em
 * 05/11/2026 com `cmd package query-activities -a android.intent.action.SET_TIMER`. Declarando o mesmo pedido com
 * prioridade normal (0), o nosso vence sem perguntar, como já acontece com o despertador (SET_ALARM).
 *
 * A lista fica nas preferências "timer" (JSON em "lista") e UM alarme do Android tipo despertador (setAlarmClock) fica
 * armado para o que acaba primeiro; ao tocar, a tela do alarme (AlarmeTocandoActivity, modo timer) tira os vencidos
 * ([tocou]) e arma o próximo. A HOME mostra o próximo embaixo da data (MainActivity.tarefaTimer); OK ali cancela esse.
 */
object Timer {
    private const val TAG = "TvLauncher.Timer"
    private const val PREFS = "timer"

    data class Ativo(val fimMs: Long, val nome: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A lista guardada. Lê também o formato antigo (um timer só, "fim" e "nome") de quem atualizou com um correndo. */
    private fun lista(ctx: Context): List<TimerRegras.Item> {
        val p = prefs(ctx)
        val l = TimerRegras.ler(p.getString("lista", null))
        val fimAntigo = p.getLong("fim", 0)
        return if (fimAntigo > 0 && l.isEmpty()) listOf(TimerRegras.Item(fimAntigo, fimAntigo, fimAntigo, p.getString("nome", "") ?: "")) else l
    }

    private fun gravar(ctx: Context, l: List<TimerRegras.Item>) {
        prefs(ctx).edit().remove("fim").remove("nome").putString("lista", TimerRegras.escrever(l)).apply()
    }

    /** Os timers correndo, do que acaba primeiro ao último. */
    fun ativos(ctx: Context): List<Ativo> = TimerRegras.correndo(lista(ctx), System.currentTimeMillis()).map { Ativo(it.fim, it.nome) }

    /** O que acaba primeiro (ou null). */
    fun ativo(ctx: Context): Ativo? = ativos(ctx).firstOrNull()

    fun iniciar(ctx: Context, segundos: Int, nome: String) {
        val agora = System.currentTimeMillis()
        val novo = TimerRegras.Item(agora, agora, agora + segundos * 1000L, nome.trim().take(40))
        val l = TimerRegras.adicionar(lista(ctx), novo, agora)
        gravar(ctx, l)
        armar(ctx, l.first().fim)
        Log.i(TAG, "timer de $segundos s ('$nome'); ${l.size} correndo")
    }

    private fun armar(ctx: Context, fim: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fim, null), pendente(ctx))
        } catch (e: SecurityException) { Log.w(TAG, "sem permissão de alarme exato: $e") }
    }

    /**
     * Depois de reiniciar a TV (BootReceiver, 10/11/2026), restaurar backup ou mudar a hora: o Android esquece os
     * alarmes agendados. Arma de novo o próximo; os que venceram com a TV desligada somem (tocar atrasado, ao ligar,
     * assustaria mais do que ajudaria).
     */
    fun reagendar(ctx: Context) {
        val l = TimerRegras.correndo(lista(ctx), System.currentTimeMillis())
        gravar(ctx, l)
        if (l.isEmpty()) { cancelarAlarme(ctx); return }
        armar(ctx, l.first().fim)
        Log.i(TAG, "timer re-armado: ${l.size} correndo, o próximo em ${(l.first().fim - System.currentTimeMillis()) / 1000} s")
    }

    /** Cancela TODOS os timers ("Não avisar"). Devolve true se havia algum correndo. */
    fun cancelar(ctx: Context): Boolean {
        val havia = ativo(ctx) != null
        gravar(ctx, emptyList())
        cancelarAlarme(ctx)
        return havia
    }

    /** Cancela só o que acaba primeiro (OK na pílula da HOME). Devolve true se havia algum. */
    fun cancelarProximo(ctx: Context): Boolean {
        val l = TimerRegras.correndo(lista(ctx), System.currentTimeMillis())
        if (l.isEmpty()) return false
        gravar(ctx, l.drop(1))
        reagendar(ctx)
        return true
    }

    private fun cancelarAlarme(ctx: Context) { ctx.getSystemService(AlarmManager::class.java)?.cancel(pendente(ctx)) }

    /** O alarme tocou (a tela já abriu): tira os vencidos, arma o próximo e devolve o nome para mostrar. */
    fun tocou(ctx: Context): String {
        val (vencidos, ficam) = TimerRegras.separarVencidos(lista(ctx), System.currentTimeMillis())
        gravar(ctx, ficam)
        reagendar(ctx)
        return TimerRegras.nomes(vencidos, ctx.getString(R.string.dur_e))
    }

    // ---- Cronômetro (ideia 31): conta para cima; a HOME mostra junto da pílula do timer.
    fun cronometroInicio(ctx: Context): Long = prefs(ctx).getLong("crono", 0)
    fun iniciarCronometro(ctx: Context) { prefs(ctx).edit().putLong("crono", System.currentTimeMillis()).apply() }
    /** Para e devolve quanto marcou ("12:04"), ou null se não estava correndo. */
    fun pararCronometro(ctx: Context): String? {
        val i = cronometroInicio(ctx).takeIf { it > 0 } ?: return null
        prefs(ctx).edit().remove("crono").apply()
        return TimerRegras.cronometro(i, System.currentTimeMillis())
    }

    /** Texto da pílula da HOME, ou null (ver [TimerRegras.pilula]). */
    fun pilula(ctx: Context): String? = TimerRegras.pilula(lista(ctx), cronometroInicio(ctx), System.currentTimeMillis())

    // requestCode 3: o 1 e o 2 são do Despertador (o toque e o "mostrar"). Códigos iguais com o mesmo destino se
    // confundiriam: o Android acharia que é o mesmo PendingIntent e o timer apagaria o alarme do despertador.
    private fun pendente(ctx: Context) = PendingIntent.getActivity(
        ctx, 3,
        Intent(ctx, AlarmeTocandoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmeTocandoActivity.EXTRA_TIMER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "4:05" (até 1 h) ou "1:04:05". */
    fun restanteTexto(ms: Long): String = TimerRegras.relogio(ms)

    /** "10 minutos", "1 hora e 30 minutos", "45 segundos": para a confirmação na tela. */
    fun duracaoTexto(segundos: Int): String = duracaoFalada(segundos)   // no idioma da TV (Textos.kt)
}

/**
 * Quem atende o "criar timer" do Android (AlarmClock.ACTION_SET_TIMER, com EXTRA_LENGTH em segundos e, às vezes,
 * EXTRA_MESSAGE com o nome) e os atalhos de voz da contagem.
 *
 * Por que os atalhos (05/11/2026, teste do dono na TV): "Ok Google, timer de 2 minutos" NÃO chega aqui. O Gemini
 * desta TV responde que "ainda não consegue alterar o timer neste dispositivo" e nem manda o SET_TIMER. Então, como
 * no "Desligar em 30 minutos", cada duração vira um "app" com nome falável (activity-alias no manifesto):
 * "Ok Google, abrir Avisar em 10 minutos". O nome evita "timer" e "contagem": com "abrir Contagem de 1 minuto" o
 * Gemini entendeu pedido de vídeo e mostrou contagens do YouTube (teste do dono, 05/11/2026).
 * "Ampulheta" (ou o SET_TIMER sem duração) abre a escolha da duração no controle; "Não avisar" cancela.
 */
class TimerActivity : Activity() {
    companion object {
        /** Alias do manifesto -> minutos. */
        val ATALHOS = mapOf("Avisar5" to 5, "Avisar10" to 10, "Avisar15" to 15, "Avisar20" to 20, "Avisar30" to 30)
        /** Opções da tela de escolha, em minutos. */
        val OPCOES = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val alias = intent.component?.className?.substringAfterLast('.') ?: ""
        val seg = when {
            alias in ATALHOS -> ATALHOS.getValue(alias) * 60
            alias == "NaoAvisar" -> { cancelar(); finish(); return }
            else -> intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        }
        val nome = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)?.trim() ?: ""
        Log.i("TvLauncher.Timer", "pedido: alias=$alias length=$seg message='$nome'")
        if (seg in 1..24 * 3600) { marcar(seg, nome); finish(); return }
        setContentView(escolha())   // sem duração: escolher no controle
    }

    private fun marcar(seg: Int, nome: String) {
        Timer.iniciar(this, seg, nome)
        val titulo = if (nome.isNotEmpty()) " ($nome)" else ""
        AvisoTv.rapido(this, "Timer", getString(R.string.tm_aviso_em, Timer.duracaoTexto(seg)) + titulo)
    }

    private fun cancelar() = AvisoTv.rapido(this, "Timer",
        getString(if (Timer.cancelar(this)) R.string.tm_cancelada else R.string.tm_nenhuma))

    /**
     * Uma caixa no meio da tela com um botão por duração (OK marca e fecha; Voltar fecha sem marcar). Mesmo cartão e
     * mesmos botões do diálogo único do sistema de design (Estilo.dialogo / Estilo.botao).
     */
    private fun escolha(): android.view.View {
        fun dp(v: Int) = Estilo.dp(this, v)
        fun botao(rotulo: String, acao: () -> Unit) = Estilo.botao(this, rotulo).apply { setOnClickListener { acao(); finish() } }
        val grade = android.widget.GridLayout(this).apply { columnCount = 3; clipChildren = false }
        OPCOES.forEach { min ->
            grade.addView(botao(if (min < 60) getString(R.string.cfg_min, min) else txtN(R.plurals.dur_horas, 1)) { marcar(min * 60, "") },
                android.widget.GridLayout.LayoutParams().apply { width = dp(150); setMargins(dp(Estilo.E1), dp(Estilo.E1), dp(Estilo.E1), dp(Estilo.E1)) })
        }
        val caixa = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(Estilo.E6 - 8), dp(Estilo.E3 + 8), dp(Estilo.E6 - 8), dp(Estilo.E3 + 8))
            background = Estilo.fundo(this@TimerActivity, Estilo.CARTAO, Estilo.RAIO, Estilo.VIDRO_BORDA)
            addView(Estilo.texto(this@TimerActivity, Estilo.Papel.SECAO, "Timer"),
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(Estilo.E1) })
            addView(Estilo.texto(this@TimerActivity, Estilo.Papel.TITULO, getString(R.string.tm_titulo)).apply { textSize = 24f },
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(Estilo.E2) })
            addView(grade)
            // Cronômetro (ideia 31): começa ou para; parado, mostra quanto marcou.
            val crono = Timer.cronometroInicio(this@TimerActivity) > 0
            addView(botao(getString(if (crono) R.string.tm_crono_parar else R.string.tm_crono_iniciar)) {
                if (crono) Timer.pararCronometro(this@TimerActivity)?.let { AvisoTv.rapido(this@TimerActivity, getString(R.string.tm_crono), getString(R.string.tm_crono_marcou, it)) }
                else { Timer.iniciarCronometro(this@TimerActivity); AvisoTv.rapido(this@TimerActivity, getString(R.string.tm_crono), getString(R.string.tm_crono_comecou)) }
            }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E2) })
            if (Timer.ativo(this@TimerActivity) != null) addView(botao(getString(R.string.tm_cancelar_atual)) { cancelar() },
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        }
        grade.getChildAt(3)?.post { grade.getChildAt(3).requestFocus() }   // foco em "10 min", a escolha mais comum
        return android.widget.FrameLayout(this).apply {
            setBackgroundColor(Estilo.VEU)
            // Largura fixa: com wrap_content o GridLayout era medido antes dos botões e a 3ª coluna saía cortada (visto na Sala).
            addView(caixa, android.widget.FrameLayout.LayoutParams(dp(3 * (150 + 2 * Estilo.E1) + 2 * (Estilo.E6 - 8)), -2, android.view.Gravity.CENTER))
        }
    }
}

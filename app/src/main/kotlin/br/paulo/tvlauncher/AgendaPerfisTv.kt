package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import br.paulo.tvlauncher.alarm.AgendaPerfis
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.PerfilCriancaTv
import br.paulo.tvlauncher.family.PerfilDados
import java.util.TimeZone

/**
 * Agenda da família (degrau 2): a parte do Android. Regras em AgendaPerfis.kt. Prefs "agenda_perfis" (entram no backup).
 *  - [agendar]: um alarme para a próxima troca (refeito a cada mudança, a cada processo novo e depois de cada troca);
 *  - o alarme ([AgendaPerfisReceiver]) e a HOME ao voltar ([conferir]) aplicam a troca que vale agora.
 * A troca automática não pede PIN (quem marcou foi o adulto, atrás do PIN), mas liga/desliga as regras de criança
 * como a troca à mão (PerfilCriancaTv.trocarSemPin).
 */
object AgendaPerfisTv {
    private const val TAG = "TvLauncher.AgendaPerfis"
    private const val PREFS = "agenda_perfis"
    private const val LISTA = "lista"
    private const val APLICADA = "aplicada_em"
    private const val ACAO = "br.paulo.tvlauncher.AGENDA_PERFIL"
    private val zona get() = TimeZone.getDefault()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lista(ctx: Context): List<AgendaPerfis.Entrada> = AgendaPerfis.ordenar(AgendaPerfis.ler(prefs(ctx).getString(LISTA, null)))

    fun gravar(ctx: Context, l: List<AgendaPerfis.Entrada>) {
        // Marca "aplicada agora": uma entrada nova cujo horário já passou hoje não troca o perfil na mesma hora.
        prefs(ctx).edit().putString(LISTA, AgendaPerfis.escrever(l)).putLong(APLICADA, System.currentTimeMillis()).apply()
        agendar(ctx)
    }

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 7302,
        Intent(ctx, AgendaPerfisReceiver::class.java).setAction(ACAO), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendente(ctx))
        val (_, quando) = AgendaPerfis.proxima(lista(ctx), System.currentTimeMillis(), zona) ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
        } catch (e: Exception) { Log.w(TAG, "alarme: $e") }
    }

    /** Aplica a troca que vale agora, se ainda não aplicada. true = trocou o perfil. */
    fun conferir(ctx: Context): Boolean {
        val l = lista(ctx)
        if (l.isEmpty() || ModoVisita.ativo(ctx)) return false   // visita/hóspede: o perfil fica genérico
        val agora = System.currentTimeMillis()
        val e = AgendaPerfis.deveAplicar(l, agora, zona, prefs(ctx).getLong(APLICADA, 0L)) ?: return false
        prefs(ctx).edit().putLong(APLICADA, agora).apply()
        val contas = Perfil.contas(ctx)
        val para = contas.firstOrNull { PerfilDados.sanitizar(it) == PerfilDados.sanitizar(e.conta) } ?: return false
        val de = Perfil.ativa(ctx, contas)
        if (de == para) return false
        PerfilCriancaTv.trocarSemPin(ctx, de, para)
        Log.i(TAG, "perfil trocado pela agenda")
        return true
    }
}

/** O alarme da próxima troca de perfil (AgendaPerfisTv.agendar). */
class AgendaPerfisReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pronto = goAsync()
        Fundo.rodar("agenda-perfis") {
            try { AgendaPerfisTv.conferir(ctx); AgendaPerfisTv.agendar(ctx) } finally { pronto.finish() }
        }
    }
}

/**
 * A tela da agenda (Configurações > Família > Agenda da família). Cada linha é "14:00 · Segunda a sexta · Pedro"; OK
 * pergunta se apaga. "Adicionar" pergunta em 3 passos curtos: de quem, que dias, que hora. Abre atrás do PIN dos pais.
 */
class AgendaFamiliaActivity : Activity() {
    private lateinit var coluna: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.agf_titulo), getString(R.string.agf_sub))
        coluna = col
        setContentView(raiz)
        montar()
    }

    private fun nomeDias(d: Int) = getString(when (d) {
        AgendaPerfis.TODOS -> R.string.agf_todos
        AgendaPerfis.UTEIS -> R.string.agf_uteis
        AgendaPerfis.FIM_DE_SEMANA -> R.string.agf_fds
        else -> R.string.agf_alguns
    })

    private fun hora(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun montar() {
        coluna.removeAllViews()
        val l = AgendaPerfisTv.lista(this)
        val add = Estilo.linhaOpcao(this, getString(R.string.agf_adicionar), getString(R.string.agf_adicionar_det)).apply {
            setOnClickListener { if (l.size >= AgendaPerfis.MAX) aviso(getString(R.string.agf_cheia)) else escolherConta() }
        }
        coluna.addView(add, lp())
        for (e in l) {
            coluna.addView(Estilo.linhaOpcao(this, "${hora(e.minuto)} · ${Perfil.nome(this, e.conta)}", nomeDias(e.dias)).apply {
                setOnClickListener {
                    CaixaTv(this@AgendaFamiliaActivity).setTitle(getString(R.string.agf_apagar))
                        .setMessage("${hora(e.minuto)} · ${Perfil.nome(this@AgendaFamiliaActivity, e.conta)} · ${nomeDias(e.dias)}")
                        .setNegativeButton(getString(R.string.cl_cancelar), null)
                        .setPositiveButton(getString(R.string.agf_apagar_sim)) { _, _ ->
                            AgendaPerfisTv.gravar(this@AgendaFamiliaActivity, AgendaPerfisTv.lista(this@AgendaFamiliaActivity) - e); montar()
                        }.show()
                }
            }, lp())
        }
        add.requestFocus()
    }

    /** Uma caixa com uma lista de opções (passo do "Adicionar"). */
    private fun escolher(titulo: String, opcoes: List<String>, inicial: Int = 0, aoEscolher: (Int) -> Unit) {
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(0, Estilo.dp(context, 8), 0, 0) }
        var caixa: android.app.Dialog? = null
        opcoes.forEachIndexed { i, t ->
            lista.addView(Estilo.linhaOpcao(this, t).apply { setOnClickListener { caixa?.dismiss(); aoEscolher(i) } }, lp())
        }
        val rolagem = ScrollView(this).apply { addView(lista); isVerticalScrollBarEnabled = false }
        caixa = CaixaTv(this).setTitle(titulo).setView(rolagem).setNegativeButton(getString(R.string.cl_cancelar), null).create()
        caixa.setOnShowListener { lista.getChildAt(inicial)?.requestFocus() }
        rolagem.layoutParams = ViewGroup.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        caixa.show()
    }

    private fun escolherConta() {
        val contas = Perfil.contas(this)
        if (contas.isEmpty()) { aviso(getString(R.string.agf_sem_contas)); return }
        escolher(getString(R.string.agf_quem), contas.map { Perfil.nome(this, it) }) { i -> escolherDias(contas[i]) }
    }

    private fun escolherDias(conta: String) {
        val dias = listOf(AgendaPerfis.TODOS, AgendaPerfis.UTEIS, AgendaPerfis.FIM_DE_SEMANA)
        escolher(getString(R.string.agf_dias), dias.map(::nomeDias)) { i -> escolherHora(conta, dias[i]) }
    }

    private fun escolherHora(conta: String, dias: Int) {
        val h = AgendaPerfis.HORARIOS
        escolher(getString(R.string.agf_hora), h.map(::hora), inicial = h.indexOf(14 * 60)) { i ->
            AgendaPerfisTv.gravar(this, AgendaPerfisTv.lista(this) + AgendaPerfis.Entrada(conta, dias, h[i]))
            montar()
        }
    }

    private fun aviso(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Estilo.dp(this@AgendaFamiliaActivity, 8) }
}

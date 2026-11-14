package br.paulo.tvlauncher

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.TimeZone

/**
 * Ideia 44: lê o uso da última semana (UsageStatsManager, a mesma permissão que o modo simples usa) e, se uma regra
 * de [Sugestoes] disparar, pergunta na HOME. Só confere uma vez por dia e só com a HOME na frente (nunca por cima
 * de um filme). Sem a permissão de uso, não há sinais e nada aparece.
 */
object SugestoesTv {
    private const val CONFERIDO = "sugestao_conferida_em"
    private const val ULTIMA = "sugestao_ultima_em"
    private const val DISPENSADAS = "sugestao_dispensadas"
    private const val DIA_MS = 24 * 60 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("extras", Context.MODE_PRIVATE)

    fun aoRetomarHome(a: Activity) {
        val p = prefs(a)
        val agora = System.currentTimeMillis()
        if (agora - p.getLong(CONFERIDO, 0) < DIA_MS) return   // leitura barata antes de qualquer consulta
        if (ModoVisita.ativo(a) || Extras.ligado(a, Extras.Chave.MODO_SIMPLES)) return
        p.edit().putLong(CONFERIDO, agora).apply()
        val app = a.applicationContext
        Fundo.rodar("sugestoes") {
            val momentos = usoDaSemana(app, agora)
            val fuso = TimeZone.getDefault()
            val s = Sugestoes.Sinais(
                noitesMadrugada = Sugestoes.noites(momentos, fuso, 0),
                noitesTarde = Sugestoes.noites(momentos, fuso, 22),
                desligarSozinhaMin = AutoDesligar.minutos(app),
                filtroLigado = Extras.ligado(app, Extras.Chave.FILTRO_NOTURNO),
                dispensadas = p.getStringSet(DISPENSADAS, emptySet()) ?: emptySet(),
                ultimaEm = p.getLong(ULTIMA, 0),
                agora = agora,
            )
            val tipo = Sugestoes.escolher(s) ?: return@rodar
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed || !a.hasWindowFocus()) return@runOnUiThread
                p.edit().putLong(ULTIMA, System.currentTimeMillis()).apply()
                perguntar(a, tipo, s)
            }
        }
    }

    /** Momentos em que a TV estava em uso (um app veio para a frente ou a tela acendeu) nos últimos 7 dias. */
    private fun usoDaSemana(ctx: Context, agora: Long): List<Long> = try {
        val ev = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(agora - 7 * DIA_MS, agora)
        val e = UsageEvents.Event()
        val saida = ArrayList<Long>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) saida += e.timeStamp
        }
        saida
    } catch (_: Exception) { emptyList() }

    private fun perguntar(a: Activity, tipo: Sugestoes.Tipo, s: Sugestoes.Sinais) {
        val (titulo, texto) = when (tipo) {
            Sugestoes.Tipo.DESLIGAR_SOZINHA -> a.getString(R.string.sug_desligar_titulo) to a.getString(R.string.sug_desligar, s.noitesMadrugada)
            Sugestoes.Tipo.FILTRO_NOTURNO -> a.getString(R.string.sug_filtro_titulo) to a.getString(R.string.sug_filtro, s.noitesTarde)
        }
        CaixaTv(a).setTitle(titulo).setMessage(texto)
            .setPositiveButton(a.getString(R.string.sug_ligar)) { _, _ -> aplicar(a, tipo) }
            .setNegativeButton(a.getString(R.string.sug_agora_nao), null)
            .setNeutralButton(a.getString(R.string.sug_nunca)) { _, _ ->
                val p = prefs(a)
                p.edit().putStringSet(DISPENSADAS, HashSet(p.getStringSet(DISPENSADAS, emptySet()) ?: emptySet()) + tipo.name).apply()
            }
            .show()
    }

    private fun aplicar(a: Activity, tipo: Sugestoes.Tipo) {
        when (tipo) {
            Sugestoes.Tipo.DESLIGAR_SOZINHA -> AutoDesligar.definir(a, 120)   // 2 h sem ninguém apertar o controle
            Sugestoes.Tipo.FILTRO_NOTURNO -> if (!Extras.ligado(a, Extras.Chave.FILTRO_NOTURNO)) ExtrasTv.alternar(a, Extras.Chave.FILTRO_NOTURNO)
        }
        AvisoTv.rapido(a, a.getString(R.string.sug_feito_titulo), a.getString(R.string.sug_feito))
    }
}

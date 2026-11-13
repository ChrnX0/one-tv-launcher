package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Presença pelo roteador UniFi (12/11/2026, ideia 7): "a TV ficou ligada e todo mundo saiu". Os celulares escolhidos no
 * painel (pelo MAC que o roteador vê) somem da rede por [AUSENCIA_MIN] minutos E ninguém aperta o controle no mesmo
 * tempo, com a tela acesa: a TV apaga a tela (o CEC manda a TV dormir) e avisa no celular.
 *
 * Medido na Sala em 12/11/2026 (aparelhos_rede): os celulares da casa continuam conectados de madrugada, com a tela
 * apagada (conectados desde 21h58 e 02h00). O celular dormindo não some da lista; por isso 15 min bastam.
 * O Android usa MAC aleatório POR REDE, mas fixo naquela rede: escolher pelo MAC funciona até alguém "esquecer a rede".
 *
 * Só lê o roteador (a chave de API que já está no launcher). Confere a cada [INTERVALO_MS], só com a chave ligada e a
 * tela acesa: TV dormindo não gasta nada.
 */
object Presenca {
    private const val TAG = "TvLauncher.Presenca"
    const val CHAVE_MACS = "presenca_macs"
    const val AUSENCIA_MIN = 15L
    const val INTERVALO_MS = 5 * 60_000L

    data class Decisao(val ausenteDesde: Long?, val desligar: Boolean)

    /**
     * Regra pura (PresencaTest). [presentes] = MACs na rede agora (minúsculas); [escolhidos] = os que contam.
     * Sem escolhidos, ou roteador sem resposta ([presentes] = null), nunca desliga e zera a contagem.
     */
    fun decidir(presentes: Set<String>?, escolhidos: Set<String>, ausenteDesde: Long?, agora: Long,
                minutosSemTecla: Long, telaAcesa: Boolean): Decisao {
        if (escolhidos.isEmpty() || presentes == null) return Decisao(null, false)
        if (escolhidos.any { it in presentes }) return Decisao(null, false)
        val desde = ausenteDesde ?: agora
        val minutosFora = (agora - desde) / 60_000
        return Decisao(desde, telaAcesa && minutosFora >= AUSENCIA_MIN && minutosSemTecla >= AUSENCIA_MIN)
    }

    /** "aa:bb:..." -> minúsculas, só os válidos, sem repetir. */
    fun lerMacs(texto: String): Set<String> = texto.split(',', ';', ' ', '\n').map { it.trim().lowercase() }
        .filter { Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(it) }.toSet()

    fun escolhidos(ctx: Context): Set<String> = lerMacs(Extras.texto(ctx, CHAVE_MACS))
    fun definirEscolhidos(ctx: Context, macs: Collection<String>) =
        Extras.definirTexto(ctx, CHAVE_MACS, lerMacs(macs.joinToString(",")).joinToString(","))

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 43, Intent(ctx, PresencaReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Liga (ou desliga) a conferência periódica conforme a chave. Idempotente. */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!Extras.ligado(ctx, Extras.Chave.PRESENCA) || escolhidos(ctx).isEmpty()) { am.cancel(pendente(ctx)); return }
        // Inexato de propósito: 5 ou 6 min tanto faz, e o Android junta com outros despertares.
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + INTERVALO_MS,
            INTERVALO_MS, pendente(ctx))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("presenca", Context.MODE_PRIVATE)

    /** Uma conferência. Chama o roteador: FORA da thread da tela. */
    fun conferir(ctx: Context): String {
        val app = ctx.applicationContext
        if (!Extras.ligado(app, Extras.Chave.PRESENCA)) return "desligado"
        val acesa = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        val p = prefs(app)
        if (!acesa) { p.edit().remove("ausente_desde").apply(); return "tela apagada" }
        val presentes = try { UnifiCliente.ler(app, forcar = true).aparelhos.mapNotNull { it.mac?.lowercase() }.toSet() }
            catch (e: Exception) { Log.w(TAG, "roteador: ${e.message}"); null }
        val antes = p.getLong("ausente_desde", 0L).takeIf { it > 0 }
        val d = decidir(presentes, escolhidos(app), antes, System.currentTimeMillis(), Rotinas.minutosSemTecla(), acesa)
        p.edit().putLong("ausente_desde", d.ausenteDesde ?: 0L).apply()
        if (d.desligar) {
            p.edit().remove("ausente_desde").putLong("ultimo_desligou", System.currentTimeMillis()).apply()
            Log.i(TAG, "todos saíram há ${AUSENCIA_MIN} min: apagando a tela")
            Desligamento.apagarTela(app)
            Avisos.enviar(app, Avisos.Tipo.PRESENCA, txt(R.string.pr_tv_desligada), txt(R.string.pr_todos_sairam))
            PainelArmazem.registrar(app, "presença", "TV desligada: todos saíram", "ok")
            return "desligou"
        }
        return if (d.ausenteDesde != null) "ausentes há ${(System.currentTimeMillis() - d.ausenteDesde) / 60_000} min" else "alguém em casa"
    }

    /** Para o painel: os aparelhos do roteador com a marca de quem conta. */
    fun json(ctx: Context): JSONObject {
        val esc = escolhidos(ctx)
        val o = JSONObject().put("escolhidos", JSONArray(esc.toList()))
            .put("ultimoDesligou", prefs(ctx).getLong("ultimo_desligou", 0L))
        try {
            o.put("aparelhos", JSONArray().apply {
                UnifiCliente.ler(ctx).aparelhos.filter { it.mac != null }.forEach {
                    put(JSONObject().put("nome", it.nome).put("mac", it.mac!!.lowercase()).put("conta", it.mac.lowercase() in esc))
                }
            })
        } catch (e: Exception) { o.put("erro", e.message ?: "roteador sem resposta") }
        return o
    }
}

/** Alarme periódico da [Presenca]. Não exportado. */
class PresencaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        val app = ctx.applicationContext
        Fundo.rodar("presenca") { try { Presenca.conferir(app) } catch (_: Exception) {} finally { r.finish() } }
    }
}

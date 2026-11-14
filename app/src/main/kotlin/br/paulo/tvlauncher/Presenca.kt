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
 * Presença pelo roteador UniFi (12/11/2026, ideia 7; Home Assistant e IP na rede desde 14/11, ver [CHAVE_HA]): "a TV ficou ligada e todo mundo saiu". Os celulares escolhidos no
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
    /**
     * Ideia 40 (14/11/2026): presença sem roteador UniFi, para o produto público. Duas fontes a mais, que somam com o
     * UniFi (basta UMA dizer "alguém em casa"):
     *  - Home Assistant: entidades `person.*` ou `device_tracker.*` (o app do HA no celular informa) em "home";
     *  - aparelho que responde na rede: IPs fixos (reserva no roteador), conferidos pelo nosso shell com um ping e a
     *    tabela de vizinhos do Android (`ip neigh`). Celular dormindo costuma não responder ao ping, mas responde ao
     *    ARP: por isso vale o estado do vizinho (REACHABLE/STALE/DELAY/PROBE), não o ping.
     */
    const val CHAVE_HA = "presenca_ha"
    const val CHAVE_IPS = "presenca_ips"
    const val MAX_FONTE = 8
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

    /**
     * Junta as fontes (ideia 40). Cada uma: true = alguém em casa, false = ninguém, null = não respondeu / não usada.
     * Uma true basta; sem nenhuma resposta = não sei (null), e aí a TV nunca desliga.
     */
    fun juntar(fontes: List<Boolean?>): Boolean? = when {
        fontes.any { it == true } -> true
        fontes.any { it == false } -> false
        else -> null
    }

    /** Mesma regra do [decidir], já com as fontes juntas. [alguem] null = não sei: zera e nunca desliga. */
    fun decidirJunto(alguem: Boolean?, ausenteDesde: Long?, agora: Long, minutosSemTecla: Long, telaAcesa: Boolean): Decisao {
        if (alguem != false) return Decisao(null, false)
        val desde = ausenteDesde ?: agora
        return Decisao(desde, telaAcesa && (agora - desde) / 60_000 >= AUSENCIA_MIN && minutosSemTecla >= AUSENCIA_MIN)
    }

    /** Entidades do HA que contam: só `person.` e `device_tracker.`, ids válidos, até [MAX_FONTE]. */
    fun lerEntidades(texto: String): List<String> = texto.split(',', ';', ' ', '\n').map { it.trim().lowercase() }
        .filter { (it.startsWith("person.") || it.startsWith("device_tracker.")) && CasaHA.idValido(it) }.distinct().take(MAX_FONTE)

    /** IPv4 privados (casa), até [MAX_FONTE]. Nada de nome nem de IP público: o shell só faz ping dentro de casa. */
    fun lerIps(texto: String): List<String> = texto.split(',', ';', ' ', '\n').map { it.trim() }
        .filter { ip ->
            val n = ip.split('.').map { it.toIntOrNull() }
            n.size == 4 && n.all { it != null && it in 0..255 } && ip.split('.').none { it.length > 1 && it.startsWith("0") } &&
                (n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1]!! in 16..31))
        }.distinct().take(MAX_FONTE)

    /** Comando fixo do shell para os [ips] (já validados por [lerIps]): um ping curto e o vizinho de cada um. */
    fun comandoIps(ips: List<String>): String = ips.joinToString("; ") { "ping -c 1 -W 1 $it >/dev/null 2>&1; ip neigh show $it" }

    /** Saída do `ip neigh show` -> algum IP está na rede? "192.168.1.5 dev wlan0 lladdr aa:.. REACHABLE". */
    fun algumVizinho(saida: String): Boolean = saida.lineSequence().any { l ->
        val p = l.trim().split(Regex("\\s+"))
        "lladdr" in p && p.last() in setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")
    }

    /** "aa:bb:..." -> minúsculas, só os válidos, sem repetir. */
    fun lerMacs(texto: String): Set<String> = texto.split(',', ';', ' ', '\n').map { it.trim().lowercase() }
        .filter { Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(it) }.toSet()

    fun escolhidos(ctx: Context): Set<String> = lerMacs(Extras.texto(ctx, CHAVE_MACS))
    fun entidades(ctx: Context): List<String> = lerEntidades(Extras.texto(ctx, CHAVE_HA))
    fun ips(ctx: Context): List<String> = lerIps(Extras.texto(ctx, CHAVE_IPS))
    fun definirOutras(ctx: Context, entidades: String, ips: String) {
        Extras.definirTexto(ctx, CHAVE_HA, lerEntidades(entidades).joinToString(","))
        Extras.definirTexto(ctx, CHAVE_IPS, lerIps(ips).joinToString(","))
    }
    private fun temFonte(ctx: Context) = escolhidos(ctx).isNotEmpty() || entidades(ctx).isNotEmpty() || ips(ctx).isNotEmpty()
    fun definirEscolhidos(ctx: Context, macs: Collection<String>) =
        Extras.definirTexto(ctx, CHAVE_MACS, lerMacs(macs.joinToString(",")).joinToString(","))

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 43, Intent(ctx, PresencaReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Liga (ou desliga) a conferência periódica conforme a chave. Idempotente. */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!Extras.ligado(ctx, Extras.Chave.PRESENCA) || !temFonte(ctx)) { am.cancel(pendente(ctx)); return }
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
        val macs = escolhidos(app)
        val unifi: Boolean? = if (macs.isEmpty()) null else try {
            val presentes = UnifiCliente.ler(app, forcar = true).aparelhos.mapNotNull { it.mac?.lowercase() }.toSet()
            macs.any { it in presentes }
        } catch (e: Exception) { Log.w(TAG, "roteador: ${e.message}"); null }
        val ents = entidades(app)
        val ha: Boolean? = if (ents.isEmpty() || !CasaHA.configurado(app)) null else try {
            ents.mapNotNull { CasaHA.estadoDe(app, it) }.takeIf { it.isNotEmpty() }?.any { it == "home" }
        } catch (e: Exception) { Log.w(TAG, "home assistant: ${e.message}"); null }
        val listaIps = ips(app)
        val rede: Boolean? = if (listaIps.isEmpty()) null
            else GuardiaoShizuku.comando(app, comandoIps(listaIps), 15_000)?.let { algumVizinho(it) }
        val antes = p.getLong("ausente_desde", 0L).takeIf { it > 0 }
        val d = decidirJunto(juntar(listOf(unifi, ha, rede)), antes, System.currentTimeMillis(), Rotinas.minutosSemTecla(), acesa)
        p.edit().putLong("ausente_desde", d.ausenteDesde ?: 0L).apply()
        if (d.desligar) {
            Gatilhos.disparar(ctx, Gatilhos.Evento.TODOS_SAIRAM)   // ideia 7: ex.: apagar as luzes junto
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
            .put("entidades", JSONArray(entidades(ctx))).put("ips", JSONArray(ips(ctx)))
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

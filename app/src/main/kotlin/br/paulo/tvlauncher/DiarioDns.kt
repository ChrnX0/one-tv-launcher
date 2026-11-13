package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.app.admin.DnsEvent
import android.content.ComponentName
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diário de DNS por app (10/11/2026, ideia C da pesquisa): quais nomes cada app da TV consultou, por dia, sem VPN e sem
 * root. Usa o "registro de rede" do Device Owner. Li em https://developer.android.com/work/dpc/logging: o DO liga com
 * setNetworkLoggingEnabled; o sistema junta até 1.200 eventos por lote e avisa o AdminReceiver
 * (onNetworkLogsAvailable); cada DnsEvent traz o nome consultado, os IPs, o pacote do app e a hora. É preciso buscar o
 * lote logo, porque o sistema apaga os antigos.
 *
 * NÃO VERIFICADO na TV: a página manda ler "Affiliated users". Esta TV tem o usuário 11; se o Android exigir que todo
 * usuário seja "afiliado", setNetworkLoggingEnabled dá SecurityException. Nesse caso [ajustar] guarda o erro, a tela de
 * extras mostra, e nada mais acontece (a chave vem DESLIGADA por padrão).
 *
 * Privacidade (dados da família): fica só na TV, em files/diario_dns/AAAA-MM-DD.json, um arquivo por dia, e os com mais
 * de [DIAS] dias são apagados. Só nome + contagem + último horário; nunca o conteúdo do que foi acessado. Não entra no
 * backup (o BackupConfig só copia preferências).
 */
object DiarioDns {
    private const val TAG = "TvLauncher.DiarioDns"
    const val DIAS = 7
    private const val PASTA = "diario_dns"
    private const val CHAVE_ERRO = "diario_dns_erro"

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    /** Liga ou desliga o registro de rede conforme a chave em [Extras]. Idempotente. */
    fun ajustar(ctx: Context) {
        val querer = Extras.ligado(ctx, Extras.Chave.DIARIO_DNS)
        try {
            if (dpm(ctx).isNetworkLoggingEnabled(admin(ctx)) != querer) {
                dpm(ctx).setNetworkLoggingEnabled(admin(ctx), querer)
                Log.i(TAG, "registro de rede => $querer")
            }
            Extras.definirTexto(ctx, CHAVE_ERRO, "")
        } catch (e: Exception) {
            Log.w(TAG, "registro de rede recusado: $e")
            Extras.definirTexto(ctx, CHAVE_ERRO, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (!querer) apagarTudo(ctx)
    }

    /** Erro da última tentativa de ligar ("" = nenhum). */
    fun erro(ctx: Context): String = Extras.texto(ctx, CHAVE_ERRO)

    /** Chamado pelo AdminReceiver (fora da thread da tela). */
    fun receberLote(ctx: Context, token: Long) {
        if (!Extras.ligado(ctx, Extras.Chave.DIARIO_DNS)) return
        val eventos = try { dpm(ctx).retrieveNetworkLogs(admin(ctx), token) } catch (e: Exception) {
            Log.w(TAG, "retrieveNetworkLogs falhou: $e"); null
        } ?: return
        val porDia = HashMap<String, AgregadorDns>()
        for (ev in eventos) {
            if (ev !is DnsEvent) continue   // conexões TCP também chegam; aqui só interessam os nomes
            val dia = diaDe(ev.timestamp)
            val ag = porDia.getOrPut(dia) { carregar(ctx, dia) }
            ag.somar(ev.packageName ?: "?", ev.hostname ?: continue, ev.timestamp)
        }
        for ((dia, ag) in porDia) gravar(ctx, dia, ag)
        limparAntigos(ctx, System.currentTimeMillis())
    }

    fun diaDe(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun pasta(ctx: Context) = File(ctx.filesDir, PASTA).apply { mkdirs() }

    fun carregar(ctx: Context, dia: String): AgregadorDns {
        val f = File(pasta(ctx), "$dia.json")
        return if (f.exists()) try { AgregadorDns.deJson(JSONObject(f.readText())) } catch (e: Exception) { AgregadorDns() } else AgregadorDns()
    }

    private fun gravar(ctx: Context, dia: String, ag: AgregadorDns) {
        val f = File(pasta(ctx), "$dia.json")
        val tmp = File(pasta(ctx), "$dia.json.tmp")
        tmp.writeText(ag.json().toString())
        tmp.renameTo(f)
    }

    fun diasGuardados(ctx: Context): List<String> =
        pasta(ctx).list()?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }?.sortedDescending() ?: emptyList()

    private fun limparAntigos(ctx: Context, agora: Long) {
        val manter = AgregadorDns.diasParaManter(agora, DIAS).toSet()
        pasta(ctx).listFiles()?.forEach { f -> if (f.name.removeSuffix(".json") !in manter) f.delete() }
    }

    fun apagarTudo(ctx: Context) { File(ctx.filesDir, PASTA).deleteRecursively() }

    /** Resumo em texto (adb e tela de extras): os [n] pares app → nome mais consultados no dia. */
    fun resumo(ctx: Context, dia: String = diaDe(System.currentTimeMillis()), n: Int = 30): String {
        val ag = carregar(ctx, dia)
        if (ag.vazio()) return "sem registros em $dia" + (erro(ctx).takeIf { it.isNotEmpty() }?.let { " (erro: $it)" } ?: "")
        return ag.maisConsultados(n).joinToString("\n") { "${it.contagem}× ${it.pacote} → ${it.host}" }
    }
}

/** Contagem por (app, nome) de um dia. Pura (só org.json), para testar no PC: DiarioDnsTest. */
class AgregadorDns {
    class Linha(val pacote: String, val host: String, var contagem: Int, var ultimo: Long)

    private val mapa = LinkedHashMap<String, Linha>()

    fun somar(pacote: String, host: String, ms: Long) {
        val h = host.lowercase().trimEnd('.')
        val l = mapa.getOrPut("$pacote\u0000$h") { Linha(pacote, h, 0, 0) }
        l.contagem++
        if (ms > l.ultimo) l.ultimo = ms
    }

    fun vazio() = mapa.isEmpty()
    fun tamanho() = mapa.size

    fun maisConsultados(n: Int): List<Linha> = mapa.values.sortedWith(compareByDescending<Linha> { it.contagem }.thenBy { it.host }).take(n)

    /** Por app: quantos nomes distintos e quantas consultas. */
    fun porApp(): Map<String, Pair<Int, Int>> =
        mapa.values.groupBy { it.pacote }.mapValues { (_, ls) -> ls.size to ls.sumOf { it.contagem } }

    fun json(): JSONObject = JSONObject().put("linhas", JSONArray().apply {
        mapa.values.forEach { put(JSONArray().put(it.pacote).put(it.host).put(it.contagem).put(it.ultimo)) }
    })

    companion object {
        fun deJson(o: JSONObject): AgregadorDns {
            val ag = AgregadorDns()
            val a = o.optJSONArray("linhas") ?: return ag
            for (i in 0 until a.length()) {
                val l = a.optJSONArray(i) ?: continue
                val linha = Linha(l.optString(0), l.optString(1), l.optInt(2), l.optLong(3))
                ag.mapa["${linha.pacote}\u0000${linha.host}"] = linha
            }
            return ag
        }

        /** Os nomes de arquivo (AAAA-MM-DD) dos últimos [dias] dias, hoje incluído, no fuso da TV. */
        fun diasParaManter(agora: Long, dias: Int): List<String> =
            (0 until dias).map { DiarioDns.diaDe(agora - it * 86_400_000L) }
    }
}

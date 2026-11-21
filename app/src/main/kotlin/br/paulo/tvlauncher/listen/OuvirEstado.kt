package br.paulo.tvlauncher.listen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import br.paulo.tvlauncher.OuvirComandoReceiver
import br.paulo.tvlauncher.OuvirServico
import br.paulo.tvlauncher.alarm.TocandoAgora
import br.paulo.tvlauncher.player.PlayerArmazem
import java.io.File
import org.json.JSONObject

/**
 * Áudio que continua (17/11/2026, "Ouvir" parte 1): o que o NOSSO som (rádio; depois podcast) está tocando, contado do
 * processo ":player" (onde mora o [OuvirServico]) ao processo principal (HOME, protetor, painel, MCP).
 *
 * Por que um arquivo e não a sessão de mídia: ler as sessões de outros apps exige o "ouvinte de notificações" ligado
 * (Tocando agora). O nosso próprio som não pode depender disso: a pílula da HOME e o protetor têm que mostrar a rádio
 * em qualquer TV. O serviço grava files/player/tocando.json a cada mudança e avisa com [ACAO_MUDOU] (só para o próprio
 * app); quem lê confere se o processo que gravou ainda está vivo (o arquivo de um ":player" que caiu não vale).
 *
 * Os comandos (pausar/continuar/parar) vão no sentido contrário por [comandar]: um broadcast explícito ao
 * [OuvirComandoReceiver], que roda no ":player". Explícito = chega mesmo com o app em segundo plano (protetor de tela).
 * Lógica pura (JSON, validade): OuvirEstadoTest.
 */
object OuvirEstado {
    const val ACAO_MUDOU = "br.paulo.tvlauncher.OUVIR_MUDOU"
    const val ACAO_COMANDO = "br.paulo.tvlauncher.OUVIR_COMANDO"
    const val EXTRA_O = "o"
    /** Comandos aceitos por [comandar]. */
    const val ALTERNAR = "alternar"
    const val PAUSAR = "pausar"
    const val CONTINUAR = "continuar"
    const val PARAR = "parar"
    const val AVANCAR = "avancar"   // podcast: +30 s
    const val VOLTAR = "voltar"     // podcast: -15 s
    val COMANDOS = setOf(ALTERNAR, PAUSAR, CONTINUAR, PARAR, AVANCAR, VOLTAR)

    /**
     * [tipo] "radio" (depois "podcast"); [id] da fonte; [titulo] = o nome da rádio; [detalhe] = a música que ela manda
     * (vazio se não manda); [tocando] = tocar pedido (true também enquanto conecta; false = pausado); [pid] do ":player" que gravou;
     * [desde] = SystemClock.elapsedRealtime() da última mudança (o relógio é do sistema, vale entre processos).
     */
    data class Estado(val tipo: String, val id: String, val titulo: String, val detalhe: String, val tocando: Boolean,
                      val pid: Int, val desde: Long)

    // ---------------------------------------------------------------- puro

    fun paraJson(e: Estado): String = JSONObject().put("tipo", e.tipo).put("id", e.id).put("titulo", e.titulo)
        .put("detalhe", e.detalhe).put("tocando", e.tocando).put("pid", e.pid).put("desde", e.desde).toString()

    /** Null para texto inválido, sem título ou sem pid (arquivo truncado, versão antiga). */
    fun deJson(t: String?): Estado? = runCatching {
        val o = JSONObject(t ?: return null)
        val titulo = o.optString("titulo").trim()
        val pid = o.optInt("pid", 0)
        if (titulo.isEmpty() || pid <= 0) null
        else Estado(o.optString("tipo", "radio"), o.optString("id"), titulo.take(120), o.optString("detalhe").trim().take(200),
            o.optBoolean("tocando"), pid, o.optLong("desde", 0L))
    }.getOrNull()

    /**
     * O estado ainda vale? Só se o processo que gravou está vivo ([pidsVivos] = os processos do app agora). Pausado há
     * mais de [TocandoAgora.PAUSADA_MAX_MS] não vale (o serviço já terá parado; não mostra pílula de rádio esquecida).
     */
    fun valido(e: Estado?, pidsVivos: Set<Int>, agora: Long): Estado? = when {
        e == null || e.pid !in pidsVivos -> null
        e.desde > agora -> null   // gravado antes de reiniciar a TV (o relógio do boot recomeçou)
        !e.tocando && e.desde > 0 && agora - e.desde > TocandoAgora.PAUSADA_MAX_MS -> null
        else -> e
    }

    // ---------------------------------------------------------------- Android

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), "tocando.json")

    /** Grava (ou apaga, com null) e avisa os outros processos. Escrita atômica: quem lê nunca vê meio arquivo. */
    fun gravar(ctx: Context, e: Estado?) {
        val f = arquivo(ctx)
        runCatching {
            if (e == null) f.delete()
            else { val tmp = File(f.parentFile, "tocando.json.tmp"); tmp.writeText(paraJson(e)); tmp.renameTo(f) }
        }
        runCatching { ctx.sendBroadcast(Intent(ACAO_MUDOU).setPackage(ctx.packageName)) }
    }

    /** O que o nosso som está tocando agora, ou null. Qualquer processo; fora da thread da tela não é preciso (é leve). */
    fun ler(ctx: Context): Estado? {
        val e = deJson(runCatching { arquivo(ctx).takeIf { it.exists() }?.readText() }.getOrNull()) ?: return null
        // Só o ":player" conta: depois de reiniciar a TV, outro processo nosso pode ter ganhado o mesmo número.
        val vivos = runCatching {
            ctx.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
                .filter { it.processName == ctx.packageName + ":player" }.map { it.pid }.toSet()
        }.getOrDefault(emptySet())
        return valido(e, vivos, SystemClock.elapsedRealtime())
    }

    /** Manda [o] (um de [COMANDOS]) ao som que está tocando. Sem nada tocando, o receptor ignora. */
    fun comandar(ctx: Context, o: String): Boolean {
        if (o !in COMANDOS) return false
        return runCatching {
            ctx.sendBroadcast(Intent(ctx, OuvirComandoReceiver::class.java).setAction(ACAO_COMANDO).putExtra(EXTRA_O, o)); true
        }.getOrDefault(false)
    }
}

package br.paulo.tvlauncher

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Remap de verdade do controle (14/11/2026, ideia 6). Cada tecla livre ganha até três gestos (toque, segurar e toque
 * duplo), e cada gesto abre um app ou roda uma cena. Antes só os botões Netflix e YouTube mudavam (e só o toque).
 *
 * Quais teclas: só as que não fazem falta para navegar ([livre]): os dois botões de app, as quatro coloridas e os
 * números. Setas, OK, VOLTAR, HOME, volume, energia e mídia ficam fora de propósito (um remap errado travaria a TV).
 * As coloridas e os números só valem com a NOSSA HOME na frente ([soNaHome]): no player e nos apps eles trocam canal e
 * têm função própria. Os botões de app valem em qualquer tela, como antes.
 *
 * Gravado em "launcher" (chave "remap"), que já vai no backup (BackupConfig.ARQUIVOS). O que toca de fato é o
 * [BotoesControle]; a decisão do gesto é a máquina pura [Gestos] (GestosTest).
 */
object RemapTeclas {
    private const val CHAVE = "remap"
    const val SEGURAR_MS = 700L   // o mesmo do HOME segurado (Home.SEGURAR_HOME_MS)
    const val DUPLO_MS = 350L     // espera pelo segundo toque; só existe nas teclas com toque duplo configurado

    enum class Gesto(val chave: String) { TOQUE("toque"), SEGURAR("segurar"), DUPLO("duplo") }

    val APP = listOf(KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_3)
    val CORES = listOf(KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE)
    val NUMEROS = (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).toList()

    fun livre(tecla: Int): Boolean = tecla in APP || tecla in CORES || tecla in NUMEROS
    fun soNaHome(tecla: Int): Boolean = tecla in CORES || tecla in NUMEROS

    /** Quando true, as teclas livres não são tratadas: a tela de configurar as recebe ("aperte a tecla"). */
    @Volatile var capturando = false

    private val APP_OK = Regex("^app:[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** "app:<pacote>" ou "cena:<nome>". Qualquer outra coisa é descartada (o remap não executa texto livre). */
    fun atalhoValido(a: String): Boolean =
        APP_OK.matches(a) || (a.startsWith("cena:") && Cenas.nomeValido(a.removePrefix("cena:")))

    /** {"<tecla>": {"toque": "app:...", "segurar": "cena:..."}}. Ignora tecla não livre e atalho inválido. Pura. */
    fun lerJson(texto: String?): Map<Int, Map<Gesto, String>> = try {
        val o = JSONObject(texto ?: "{}")
        o.keys().asSequence().mapNotNull { k ->
            val tecla = k.toIntOrNull()?.takeIf { livre(it) } ?: return@mapNotNull null
            val g = o.optJSONObject(k) ?: return@mapNotNull null
            val m = Gesto.values().mapNotNull { gesto -> g.optString(gesto.chave).takeIf { atalhoValido(it) }?.let { gesto to it } }.toMap()
            if (m.isEmpty()) null else tecla to m
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    fun paraJson(m: Map<Int, Map<Gesto, String>>): String = JSONObject().apply {
        for ((tecla, gestos) in m) if (gestos.isNotEmpty())
            put(tecla.toString(), JSONObject().apply { for ((g, a) in gestos) put(g.chave, a) })
    }.toString()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun todas(ctx: Context): Map<Int, Map<Gesto, String>> = lerJson(prefs(ctx).getString(CHAVE, null))

    fun da(ctx: Context, tecla: Int): Map<Gesto, String> = todas(ctx)[tecla] ?: emptyMap()

    /** Grava os gestos de uma tecla (vazio = apaga a tecla). */
    fun salvar(ctx: Context, tecla: Int, gestos: Map<Gesto, String>) {
        val m = todas(ctx).toMutableMap()
        val bons = gestos.filterValues { atalhoValido(it) }
        if (bons.isEmpty()) m.remove(tecla) else m[tecla] = bons
        prefs(ctx).edit().putString(CHAVE, paraJson(m)).apply()
    }

    /** Nome da tecla para a tela: "Netflix", "YouTube", "Vermelha", "5". */
    fun nome(ctx: Context, tecla: Int): String = when (tecla) {
        KeyEvent.KEYCODE_BUTTON_4 -> "Netflix"
        KeyEvent.KEYCODE_BUTTON_3 -> "YouTube"
        KeyEvent.KEYCODE_PROG_RED -> ctx.getString(R.string.tec_vermelha)
        KeyEvent.KEYCODE_PROG_GREEN -> ctx.getString(R.string.tec_verde)
        KeyEvent.KEYCODE_PROG_YELLOW -> ctx.getString(R.string.tec_amarela)
        KeyEvent.KEYCODE_PROG_BLUE -> ctx.getString(R.string.tec_azul)
        in NUMEROS -> (tecla - KeyEvent.KEYCODE_0).toString()
        else -> KeyEvent.keyCodeToString(tecla)
    }
}

/**
 * Máquina dos gestos de UMA tecla (pura, GestosTest). O chamador entrega DESCE (só o primeiro, sem as repetições),
 * SOBE e PRAZO (quando o relógio agendado por [prazo] vence) e recebe o gesto reconhecido, se houver.
 *  - sem segurar e sem duplo: o toque sai no SOBE, sem atraso nenhum;
 *  - com segurar: segurou [segurarMs] = SEGURAR (o SOBE seguinte é ignorado);
 *  - com duplo: o toque espera [duploMs] por um segundo DESCE; veio = DUPLO, não veio = TOQUE.
 */
class Gestos(private val segurarMs: Long, private val duploMs: Long, private val temSegurar: Boolean, private val temDuplo: Boolean) {
    private enum class Estado { LIVRE, APERTADA, ESPERA_DUPLO, CONSUMIDA }
    private var estado = Estado.LIVRE
    /** Quando o PRAZO deve chegar (relógio do chamador); null = nada agendado. */
    var prazo: Long? = null
        private set

    fun desce(agora: Long): RemapTeclas.Gesto? = when (estado) {
        Estado.ESPERA_DUPLO -> { estado = Estado.CONSUMIDA; prazo = null; RemapTeclas.Gesto.DUPLO }
        else -> { estado = Estado.APERTADA; prazo = if (temSegurar) agora + segurarMs else null; null }
    }

    fun sobe(agora: Long): RemapTeclas.Gesto? = when (estado) {
        Estado.APERTADA -> if (temDuplo) { estado = Estado.ESPERA_DUPLO; prazo = agora + duploMs; null }
            else { estado = Estado.LIVRE; prazo = null; RemapTeclas.Gesto.TOQUE }
        else -> { if (estado == Estado.CONSUMIDA) estado = Estado.LIVRE; null }
    }

    /** O relógio venceu. Prazo velho (já trocado por outro) não faz nada. */
    fun venceu(agora: Long): RemapTeclas.Gesto? {
        val p = prazo ?: return null
        if (agora < p) return null
        prazo = null
        return when (estado) {
            Estado.APERTADA -> { estado = Estado.CONSUMIDA; RemapTeclas.Gesto.SEGURAR }
            Estado.ESPERA_DUPLO -> { estado = Estado.LIVRE; RemapTeclas.Gesto.TOQUE }
            else -> null
        }
    }

    /** Nada em andamento (pode trocar a configuração da tecla). */
    val livre: Boolean get() = estado == Estado.LIVRE

    /** Outra tecla interrompeu: um toque pendente (esperando o duplo) sai agora; o resto é esquecido. */
    fun interromper(): RemapTeclas.Gesto? {
        val r = if (estado == Estado.ESPERA_DUPLO) RemapTeclas.Gesto.TOQUE else null
        estado = Estado.LIVRE; prazo = null
        return r
    }
}

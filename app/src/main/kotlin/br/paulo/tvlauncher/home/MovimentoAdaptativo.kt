package br.paulo.tvlauncher.home

import android.content.Context

/**
 * Quanto os ícones da cruz se mexem, de acordo com o aparelho (Paulo, 18/11/2026: "dependendo do hardware o launcher
 * liga sozinho ou não ou diminui... faz algo inteligente").
 *
 * Dois passos:
 * 1. Ao abrir, um palpite pelo aparelho: memória e núcleos ([peloAparelho]).
 * 2. Com a HOME na frente, a MainActivity mede o tempo de cada quadro. Se quadros lentos demais se repetem ([pesado]),
 *    o nível desce um degrau ([descer]) e fica assim por [REBAIXADO_VALE_MS]; depois volta a tentar o do aparelho.
 * "Remover animações" do sistema sempre vence: [Nivel.PARADO].
 *
 * Medido na Sala (Chromecast, 2 GB, 4 núcleos ARM 32 bits, v463042): parada, a HOME desenhava ~21 quadros/s só com as
 * animações, ~19 ms cada. No [Nivel.EQUILIBRADO] as categorias tocam uma vez ao chegar e descansam.
 */
object MovimentoAdaptativo {

    /** Do mais parado ao mais vivo (a ordem importa: [efetivo] usa o menor). */
    enum class Nivel { PARADO, ECONOMICO, EQUILIBRADO, COMPLETO }

    /** Memória (MB) abaixo da qual o aparelho começa no econômico, e abaixo da qual começa no equilibrado. */
    const val RAM_ECONOMICO_MB = 1200L
    const val RAM_COMPLETO_MB = 3000L
    /** Quadros medidos por rodada, e a fração de lentos (em %) que faz descer um degrau. */
    const val AMOSTRA = 240
    const val LIMITE_PCT = 8
    /** Depois de abrir a HOME, os primeiros quadros (montar a tela) não contam. */
    const val IGNORAR_INICIO_MS = 2000L
    /** Quanto tempo vale um rebaixamento antes de tentar de novo o nível do aparelho. */
    const val REBAIXADO_VALE_MS = 7L * 24 * 3_600_000L
    /** "Descanso" que, na prática, faz a animação tocar uma vez só (um dia). */
    const val UMA_VEZ_MS = 24L * 3_600_000L

    /** Palpite inicial: pouca memória ou "aparelho fraco" declarado pelo sistema = econômico; até 3 GB = equilibrado. */
    fun peloAparelho(ramMb: Long, nucleos: Int, poucaMemoria: Boolean): Nivel = when {
        poucaMemoria || (ramMb in 1 until RAM_ECONOMICO_MB) || nucleos in 1..2 -> Nivel.ECONOMICO
        ramMb in 1 until RAM_COMPLETO_MB || nucleos in 3..4 -> Nivel.EQUILIBRADO
        else -> Nivel.COMPLETO
    }

    /** Um quadro é lento quando perde duas batidas da tela (em 60 Hz, > 34,3 ms): aí o olho vê o tranco. */
    fun limiteQuadroMs(hz: Float): Float = 2000f / (if (hz in 20f..240f) hz else 60f) + 1f

    /** Rodada completa e lenta demais? */
    fun pesado(lentos: Int, total: Int): Boolean = total >= AMOSTRA && lentos * 100 >= total * LIMITE_PCT

    /** Um degrau abaixo; sozinho nunca chega a [Nivel.PARADO] (isso é escolha da pessoa, em "Remover animações"). */
    fun descer(n: Nivel): Nivel = when (n) {
        Nivel.COMPLETO -> Nivel.EQUILIBRADO
        else -> if (n == Nivel.PARADO) Nivel.PARADO else Nivel.ECONOMICO
    }

    /** O nível que vale agora: o do aparelho, ou o rebaixado enquanto vale, ou parado com "Remover animações". */
    fun efetivo(aparelho: Nivel, rebaixado: Nivel?, rebaixadoEm: Long, agora: Long, semAnimacao: Boolean): Nivel = when {
        semAnimacao -> Nivel.PARADO
        rebaixado != null && agora - rebaixadoEm in 0 until REBAIXADO_VALE_MS -> minOf(aparelho, rebaixado)
        else -> aparelho
    }

    /** Descanso entre passadas do ícone escolhido (null = não anima). */
    fun descansoEscolhido(n: Nivel): Long? = when (n) {
        Nivel.COMPLETO -> IconeCruz.DESCANSO_ESCOLHIDO_MS
        Nivel.EQUILIBRADO -> 4000L
        Nivel.ECONOMICO -> UMA_VEZ_MS
        Nivel.PARADO -> null
    }

    /** Descanso das categorias dos lados (null = não animam); no equilibrado tocam uma vez ao chegar. */
    fun descansoCategoria(n: Nivel): Long? = when (n) {
        Nivel.COMPLETO -> IconeCruz.DESCANSO_CATEGORIA_MS
        Nivel.EQUILIBRADO -> UMA_VEZ_MS
        else -> null
    }

    /** Quando cada categoria começa (posição j na linha): em ciclo, espaçadas; uma vez só, logo em seguida, em onda. */
    fun atrasoCategoria(n: Nivel, j: Int): Long = if (n == Nivel.COMPLETO) 2000L + j * 1900L else 500L + j * 280L

    // ------------------------------------------------------------------ no aparelho

    private const val PREFS = "movimento"

    fun doAparelho(ctx: Context): Nivel {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        return peloAparelho(mi.totalMem / (1024 * 1024), Runtime.getRuntime().availableProcessors(), am?.isLowRamDevice == true)
    }

    fun atual(ctx: Context, semAnimacao: Boolean): Nivel {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val forcado = p.getString("forcado", null)?.let { s -> Nivel.entries.firstOrNull { it.name == s } }
        if (forcado != null) return if (semAnimacao) Nivel.PARADO else forcado
        val reb = p.getString("rebaixado", null)?.let { s -> Nivel.entries.firstOrNull { it.name == s } }
        return efetivo(doAparelho(ctx), reb, p.getLong("rebaixado_em", 0L), System.currentTimeMillis(), semAnimacao)
    }

    fun rebaixar(ctx: Context, para: Nivel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rebaixado", para.name).putLong("rebaixado_em", System.currentTimeMillis()).apply()
    }

    /** adb (EXTRAS movimento): sem nível mostra o estado; "auto" apaga o forçado e o rebaixamento. */
    fun comando(ctx: Context, nivel: String?): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when {
            nivel.isNullOrBlank() -> {}
            nivel == "auto" -> p.edit().remove("forcado").remove("rebaixado").remove("rebaixado_em").apply()
            else -> Nivel.entries.firstOrNull { it.name.equals(nivel, true) }?.let { p.edit().putString("forcado", it.name).apply() }
                ?: return "nível inválido (parado, economico, equilibrado, completo, auto)"
        }
        return "aparelho=${doAparelho(ctx)} forcado=${p.getString("forcado", "-")} rebaixado=${p.getString("rebaixado", "-")}" +
            " agora=${atual(ctx, false)} (vale na próxima HOME)"
    }
}

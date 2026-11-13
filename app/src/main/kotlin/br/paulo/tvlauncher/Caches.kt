package br.paulo.tvlauncher

import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * Limpar caches (10/11/2026, 2ª leva das ideias; li no TV Remote, "Clean caches"): pede ao Android para apagar o cache
 * de TODOS os apps (`pm trim-caches`, pelo shell próprio, uid 2000). Logins e dados dos apps ficam: só o cache sai.
 * O Chromecast tem 8 GB e enche com o cache de YouTube, Netflix e Play. Chamar fora da thread da tela.
 */
object Caches {
    private fun livre(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    /** Devolve um texto curto: quanto liberou, ou o erro. */
    fun limpar(ctx: Context): String {
        val antes = livre()
        // "Quero 1 TB livre": o Android apaga todo cache que puder para chegar perto disso.
        val saida = GuardiaoShizuku.comando(ctx, "pm trim-caches 1000G") ?: return "sem shell (o adb próprio está desligado?)"
        if (saida.contains("Error", ignoreCase = true) || saida.contains("Exception")) return "falhou: ${saida.trim().take(120)}"
        val ganho = (livre() - antes).coerceAtLeast(0)
        return "liberado ${ConsumoRede.texto(ganho)}; livre agora ${ConsumoRede.texto(livre())}"
    }

    fun livreTexto(): String = ConsumoRede.texto(livre())
}

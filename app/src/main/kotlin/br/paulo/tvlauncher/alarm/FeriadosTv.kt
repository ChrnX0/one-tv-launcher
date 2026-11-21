package br.paulo.tvlauncher.alarm

import android.content.Context
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A parte Android de [Feriados]: lê a tabela do APK (só a linha do país, guardada) e o país pelo fuso. Fora da tela. */
object FeriadosTv {
    private const val TAG = "TvLauncher.Feriados"
    @Volatile private var guardada: Pair<String, String?>? = null   // país -> linha

    fun pais(): String? = Feriados.pais(
        runCatching { android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id) }.getOrNull(),
        Locale.getDefault().country)

    /** Hoje é feriado nacional aqui? false quando não sei (melhor rodar a automação de dia útil que calar sem motivo). */
    fun hoje(ctx: Context, agora: Long = System.currentTimeMillis()): Boolean {
        val pais = pais() ?: return false
        val linha = guardada?.takeIf { it.first == pais }?.second ?: run {
            val json = runCatching { ctx.assets.open("feriados/feriados.json").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            val l = json?.let { Feriados.linhaDoPais(it, pais) }
            guardada = pais to l
            l
        }
        val c = Calendar.getInstance().apply { timeInMillis = agora }
        val r = Feriados.eFeriado(linha, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        if (r == null && linha != null) Log.w(TAG, "tabela de feriados não cobre ${c.get(Calendar.YEAR)}: rodar scripts/gerar-feriados.py")
        return r == true
    }
}

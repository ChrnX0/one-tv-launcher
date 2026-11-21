package br.paulo.tvlauncher.player

import android.content.Context
import br.paulo.tvlauncher.player.VariantesCanal.Nivel

/**
 * A memória da escolha de qualidade da TV ao vivo (18/11/2026), no processo do player. A lógica fica em [QualidadeAuto];
 * aqui só o que precisa sobreviver a fechar o player: o teto deixado pelas travadas, a maior banda medida, a escolha
 * manual de cada canal e a resolução REAL de cada endereço (o "4K" do nome nem sempre é 4K).
 */
object QualidadeTv {
    private const val PREFS = "qualidade_tv"
    private const val BANDA_VALE_MS = 24 * 60 * 60_000L
    private const val MAX_REAIS = 1_500

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** O teto em vigor agora (já subindo sozinho com o tempo sem travar), ou null. */
    fun teto(ctx: Context, agora: Long = System.currentTimeMillis()): Nivel? {
        val p = prefs(ctx)
        val n = Nivel.entries.getOrNull(p.getInt("teto", -1)) ?: return null
        return QualidadeAuto.tetoAgora(n, p.getLong("teto_desde", 0L), agora)
    }

    fun definirTeto(ctx: Context, n: Nivel, agora: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putInt("teto", n.ordinal).putLong("teto_desde", agora).apply()
    }

    /** A maior banda (bits/s) medida nas últimas 24 h, ou null. */
    fun banda(ctx: Context, agora: Long = System.currentTimeMillis()): Long? {
        val p = prefs(ctx)
        if (agora - p.getLong("banda_em", 0L) > BANDA_VALE_MS) return null
        return p.getLong("banda", 0L).takeIf { it > 0 }
    }

    /** Uma medida nova: guarda se for a maior das últimas 24 h (medida de stream ao vivo só subestima a rede). */
    fun medir(ctx: Context, bitsPorSegundo: Long, agora: Long = System.currentTimeMillis()) {
        if (bitsPorSegundo <= 0) return
        val atual = banda(ctx, agora)
        if (atual != null && atual >= bitsPorSegundo) return
        prefs(ctx).edit().putLong("banda", bitsPorSegundo).putLong("banda_em", agora).apply()
    }

    fun manual(ctx: Context, canalId: String): Nivel? =
        Nivel.entries.getOrNull(prefs(ctx).getInt("m_$canalId", -1))

    fun definirManual(ctx: Context, canalId: String, n: Nivel?) {
        val e = prefs(ctx).edit()
        if (n == null) e.remove("m_$canalId") else e.putInt("m_$canalId", n.ordinal)
        e.apply()
    }

    /** A resolução real já vista neste endereço (chave = resumo do endereço, sem a senha em texto). */
    fun real(ctx: Context, url: String): Nivel? = Nivel.entries.getOrNull(prefs(ctx).getInt(chaveReal(url), -1))

    fun definirReal(ctx: Context, url: String, n: Nivel) {
        val p = prefs(ctx)
        val k = chaveReal(url)
        if (p.getInt(k, -1) == n.ordinal) return
        val e = p.edit()
        // Teto de entradas: passou, recomeça (é só um atalho; a próxima vez que tocar, mede de novo).
        if (p.all.keys.count { it.startsWith("r_") } >= MAX_REAIS) p.all.keys.filter { it.startsWith("r_") }.forEach { e.remove(it) }
        e.putInt(k, n.ordinal).apply()
    }

    private fun chaveReal(url: String) = "r_" + Integer.toHexString(url.hashCode()) + Integer.toHexString(url.length)
}

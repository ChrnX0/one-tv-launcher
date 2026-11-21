package br.paulo.tvlauncher.panel

import android.content.Context
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.settings.Extras

/** Onde fica a escolha dos blocos do painel rápido (ideia 42). As regras estão em [PainelBlocos] (puro). */
object PainelBlocosTv {
    private const val CHAVE = "painel_blocos"

    /** No arquivo das [Extras] (vai no backup completo); sem a chave gravada = os blocos de sempre. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.EXTRAS, Context.MODE_PRIVATE)

    fun escolhidos(ctx: Context): List<String> = PainelBlocos.ler(prefs(ctx).getString(CHAVE, null))
    fun definir(ctx: Context, lista: List<String>) { prefs(ctx).edit().putString(CHAVE, PainelBlocos.gravar(lista)).apply() }
    fun voltarAoPadrao(ctx: Context) { prefs(ctx).edit().remove(CHAVE).apply() }
}

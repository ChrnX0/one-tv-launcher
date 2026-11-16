package br.paulo.onetv.celular

import android.content.Context

/** As TVs salvas neste celular (prefs privadas do app; só IP, nome e a impressão do certificado: nada secreto). */
object Tvs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    fun lista(ctx: Context): List<Pareamento.Tv> = Pareamento.deJson(prefs(ctx).getString("lista", null))
    fun guardar(ctx: Context, tv: Pareamento.Tv) = prefs(ctx).edit().putString("lista", Pareamento.paraJson(Pareamento.guardar(lista(ctx), tv))).apply()
    fun esquecer(ctx: Context, tv: Pareamento.Tv) = prefs(ctx).edit().putString("lista", Pareamento.paraJson(lista(ctx).filter { !Pareamento.mesma(it, tv) })).apply()
    fun porChave(ctx: Context, chave: String?): Pareamento.Tv? = lista(ctx).firstOrNull { Pareamento.chave(it) == chave }
    fun porIp(ctx: Context, ip: String?): Pareamento.Tv? = lista(ctx).firstOrNull { it.ip == ip }
}

package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import br.paulo.tvlauncher.settings.Extras

/**
 * Entradas HDMI e AV como cards da linha de apps (degrau 1 contra o Projectivy, 15/11/2026).
 *
 * Só existe em TV de verdade (TCL, Sony, Philips): stick e caixinha (Chromecast, Shield) não têm entrada, então a lista
 * vem vazia e a HOME fica igual. Cada entrada vira um "app" de chave "entrada:<id>"; a HOME abre pelo canal de
 * passagem do Android (TvContract.buildChannelUriForPassthroughInput), o mesmo que o launcher do fabricante usa.
 * A chave serve para ordem e ocultos (Organizacao) como a de qualquer app.
 *
 * Ligado de fábrica (só aparece onde existe); a chave [Extras.Chave.ENTRADAS_TV] esconde.
 */
object EntradasTv {
    const val PREFIXO = "entrada:"

    class Entrada(val chave: String, val nome: String, val icone: Drawable?)

    fun eh(pacote: String) = pacote.startsWith(PREFIXO)

    /** Só as entradas físicas (HDMI, AV, componente...). Os sintonizadores de apps (IPTV, canais) ficam de fora. */
    fun listar(ctx: Context): List<Entrada> {
        if (!Extras.ligado(ctx, Extras.Chave.ENTRADAS_TV)) return emptyList()
        return fisicas(ctx)
            .sortedWith(compareBy({ it.type }, { it.id }))
            .map { i ->
                val nome = runCatching { i.loadCustomLabel(ctx)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { i.loadLabel(ctx).toString() }.getOrDefault(i.id)
                Entrada(PREFIXO + i.id, nome, runCatching { i.loadIcon(ctx) }.getOrNull())
            }
    }

    private fun fisicas(ctx: Context): List<TvInputInfo> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching { tim.tvInputList }.getOrDefault(emptyList())
            .filter { it.isPassthroughInput && it.type != TvInputInfo.TYPE_TUNER }
            .filter { runCatching { !it.isHidden(ctx) }.getOrDefault(true) }
    }

    /** O aparelho tem entrada física? Sem ela (Chromecast, caixinha) a chave nem aparece nas Configurações. */
    fun existe(ctx: Context) = fisicas(ctx).isNotEmpty()

    /** Abre a entrada pela chave "entrada:<id>". false = o aparelho não sabe abrir (a HOME não faz nada). */
    fun abrir(ctx: Context, chave: String): Boolean {
        val id = chave.removePrefix(PREFIXO)
        val i = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }
}

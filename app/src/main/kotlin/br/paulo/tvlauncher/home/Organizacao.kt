package br.paulo.tvlauncher.home

import android.content.Context
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.PerfilDados

/**
 * Apps ocultos e a ordem manual de antes, nas SharedPreferences "launcher". Só guarda e lê (não desenha nada).
 * 21/11/2026: as pilhas (manuais e automáticas) e o "mover" saíram com a HOME antiga; a cruz arruma os apps por
 * categoria (CategoriaApps, CruzLogica). As chaves antigas ("pilhas", "pilhas_auto"...) ficam nas preferências e no
 * backup sem efeito, para um backup velho continuar restaurando sem erro.
 */
object Organizacao {
    private const val PREFS = Prefs.LAUNCHER

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- ordem

    // Perfis de verdade (ideia 11): a ordem é do perfil ativo ("ordem@<usuario>", ver PerfilDados). Sem ordem própria,
    // vale a "ordem" de antes (compartilhada), que nunca é apagada nem sobrescrita por um perfil.
    fun ordem(ctx: Context): List<String> {
        val p = prefs(ctx)
        val chave = PerfilDados.chaveLeitura("ordem", Perfil.chaveDados(ctx)) { p.contains(it) }
        return p.getString(chave, "").orEmpty().split(",").filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- ocultos

    fun ocultos(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet("ocultos", emptySet()) ?: emptySet())   // cópia: o Android proíbe mexer no original

    private fun salvarOcultos(ctx: Context, pacotes: Set<String>) {
        prefs(ctx).edit().putStringSet("ocultos", HashSet(pacotes)).apply()
    }

    fun ocultar(ctx: Context, pacote: String) {
        salvarOcultos(ctx, ocultos(ctx) + pacote)
    }

    fun mostrar(ctx: Context, pacote: String) {
        salvarOcultos(ctx, ocultos(ctx) - pacote)
    }
}

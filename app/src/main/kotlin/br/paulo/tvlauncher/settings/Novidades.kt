package br.paulo.tvlauncher.settings

import android.app.Dialog
import android.content.Context
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R

/**
 * "O que mudou nesta versão" (14/11/2026, ideia 45). Só em Configurações > Sistema > Esta versão: decisão de não
 * aparecer sozinho na HOME, para não confundir quem só assiste. O texto fica em strings_versoes.xml (array
 * "novidades"), reescrito a cada Release, nos dois idiomas.
 */
object Novidades {
    fun itens(ctx: Context): List<String> = ctx.resources.getStringArray(R.array.novidades).toList()

    fun mostrar(ctx: Context) {
        val d = Dialog(ctx)
        val ok = Estilo.botao(ctx, ctx.getString(R.string.ver_ok)).apply { setOnClickListener { d.dismiss() } }
        val tela = Estilo.dialogo(ctx, ctx.getString(R.string.ver_novidades_sobre, BuildConfig.VERSION_CODE.toString()),
            ctx.getString(R.string.ver_novidades), itens(ctx).joinToString("\n") { "• $it" }, listOf(ok), larguraDp = 760)
        Estilo.vestir(d, tela)
        d.setOnShowListener { ok.requestFocus() }
        d.show()
    }
}

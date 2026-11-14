package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout

/**
 * Ideia 67: guarda o uso (UsoLocalRegras) nas prefs "uso_local", fora de todo backup e do painel web.
 * Telas: contadas num ponto só, no onActivityCreated do LauncherApp. Opções das Configurações: no linha() delas.
 */
object UsoLocal {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("uso_local", Context.MODE_PRIVATE)

    @Synchronized fun contar(ctx: Context, nome: String) {
        val p = prefs(ctx)
        p.edit().putString("mapa", UsoLocalRegras.somar(p.getString("mapa", null), nome, System.currentTimeMillis())).apply()
    }

    fun contarTela(a: Activity) = runCatching { contar(a, "tela:" + UsoLocalRegras.nomeDaTela(a.javaClass.name)) }

    fun zerar(ctx: Context) = prefs(ctx).edit().remove("mapa").apply()

    /** A caixa com a lista (mais usados no alto; o fim da lista é o candidato a cortar). */
    fun mostrar(a: Activity) {
        val agora = System.currentTimeMillis()
        val itens = UsoLocalRegras.itens(prefs(a).getString("mapa", null))
        val texto = if (itens.isEmpty()) a.getString(R.string.l51_uso_vazio) else itens.joinToString("\n") {
            val nome = it.nome.removePrefix("tela:").removePrefix("opcao:")
            val tipo = a.getString(if (it.nome.startsWith("tela:")) R.string.l51_uso_tela else R.string.l51_uso_opcao)
            a.getString(R.string.l51_uso_linha, nome, tipo, it.vezes, UsoLocalRegras.diasDesde(it.ultimo, agora))
        }
        val corpo = android.widget.ScrollView(a).apply {
            addView(Estilo.texto(a, Estilo.Papel.LEGENDA, texto).apply { setPadding(48, 8, 48, 8); isFocusable = true })
        }
        CaixaTv(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(a.getString(R.string.l51_uso_titulo))
            .setView(LinearLayout(a).apply { addView(corpo, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.55f).toInt())) })
            .setPositiveButton("OK", null)
            .setNegativeButton(a.getString(R.string.l51_uso_zerar)) { _, _ -> zerar(a) }
            .show()
    }
}

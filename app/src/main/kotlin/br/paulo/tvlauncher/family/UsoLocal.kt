package br.paulo.tvlauncher.family

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R

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

    /** Telas contadas por nome de classe -> nome na tela (teste na TV 15/11/2026: aparecia "Main (tela)", "Dream"). */
    private val NOMES_TELAS = mapOf(
        "Main" to R.string.l51_tela_Main,
        "Dream" to R.string.l51_tela_Dream,
        "Filmes" to R.string.l51_tela_Filmes,
        "Series" to R.string.l51_tela_Series,
        "Jogos" to R.string.l51_tela_Jogos,
        "Player" to R.string.l51_tela_Player,
        "PainelRapido" to R.string.l51_tela_PainelRapido,
        "Vod" to R.string.l51_tela_Vod,
        "Previsao" to R.string.l51_tela_Previsao,
        "Elenco" to R.string.l51_tela_Elenco,
        "ConfigLauncher" to R.string.l51_tela_ConfigLauncher,
        "MidiaRede" to R.string.l51_tela_MidiaRede,
        "Calendario" to R.string.l51_tela_Calendario,
        "Gravacoes" to R.string.l51_tela_Gravacoes,
        "Busca" to R.string.l51_tela_Busca,
        "Casa" to R.string.l51_tela_Casa,
        "AlarmeTocando" to R.string.l51_tela_AlarmeTocando,
        "Radio" to R.string.l51_tela_Radio,
        "Diagnostico" to R.string.l51_tela_Diagnostico)

    fun zerar(ctx: Context) = prefs(ctx).edit().remove("mapa").apply()

    /** A caixa com a lista (mais usados no alto; o fim da lista é o candidato a cortar). */
    fun mostrar(a: Activity) {
        val agora = System.currentTimeMillis()
        val itens = UsoLocalRegras.itens(prefs(a).getString("mapa", null))
        val texto = if (itens.isEmpty()) a.getString(R.string.l51_uso_vazio) else itens.joinToString("\n") {
            val tela = it.nome.startsWith("tela:")
            val cru = it.nome.removePrefix("tela:").removePrefix("opcao:")
            // Nome da classe ("Main", "Dream") vira o nome que a pessoa conhece; tela nova sem nome fica com o da classe.
            val nome = if (tela) NOMES_TELAS[cru]?.let { id -> a.getString(id) } ?: cru else cru
            val tipo = a.getString(if (tela) R.string.l51_uso_tela else R.string.l51_uso_opcao)
            val vezes = a.resources.getQuantityString(R.plurals.l51_uso_vezes, it.vezes.toInt(), it.vezes.toInt())
            val quando = when (val d = UsoLocalRegras.diasDesde(it.ultimo, agora)) {
                0L -> a.getString(R.string.l51_uso_hoje); 1L -> a.getString(R.string.l51_uso_ontem)
                else -> a.getString(R.string.l51_uso_dias, d.toInt())
            }
            a.getString(R.string.l51_uso_linha, nome, tipo, vezes, quando)
        }
        val corpo = android.widget.ScrollView(a).apply {
            addView(Estilo.texto(a, Estilo.Papel.LEGENDA, texto).apply { setPaddingRelative(48, 8, 48, 8); isFocusable = true })
        }
        CaixaTv(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(a.getString(R.string.l51_uso_titulo))
            .setView(LinearLayout(a).apply { addView(corpo, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.55f).toInt())) })
            .setPositiveButton("OK", null)
            .setNegativeButton(a.getString(R.string.l51_uso_zerar)) { _, _ -> zerar(a) }
            .show()
    }
}

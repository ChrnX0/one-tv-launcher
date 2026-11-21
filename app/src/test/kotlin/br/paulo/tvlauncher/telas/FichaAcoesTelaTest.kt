package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.FichaAcoes
import br.paulo.tvlauncher.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Coluna fina de ações da ficha (16/11/2026, 240 dp): em cada idioma, nenhum texto pode sair cortado com "…" (os longos
 * quebram em 2 linhas). Roda com -Ptelas; a foto vai para app/build/telas/ficha-acoes-<idioma>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FichaAcoesTelaTest {
    @Config(sdk = [34], qualifiers = "pt-w960dp-h540dp-land-television-xhdpi") @Test fun portugues() = conferir("pt")
    @Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi") @Test fun ingles() = conferir("en")
    @Config(sdk = [34], qualifiers = "de-w960dp-h540dp-land-television-xhdpi") @Test fun alemao() = conferir("de")
    @Config(sdk = [34], qualifiers = "fr-w960dp-h540dp-land-television-xhdpi") @Test fun frances() = conferir("fr")
    @Config(sdk = [34], qualifiers = "ru-w960dp-h540dp-land-television-xhdpi") @Test fun russo() = conferir("ru")

    private fun conferir(idioma: String) {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (col, acoes) = FichaAcoes.coluna(a)
        val itens = listOf(
            Triple(a.getString(R.string.series_continuar_curto, "T1E1"), "16:48", "continuar"),
            Triple(a.getString(R.string.filmes_do_comeco), null, "comeco"),
            Triple(a.getString(R.string.series_episodios), a.getString(R.string.series_temporada, 2), "episodios"),
            Triple(a.getString(R.string.series_trocar_temporada), "3", "temporadas"),
            Triple(a.getString(R.string.trailer), null, "trailer"),
            Triple(a.getString(R.string.idioma_versao_legendada), null, "versao"),
            Triple(a.getString(R.string.elenco_botao), null, "elenco"),
            Triple(a.getString(R.string.filmes_colecao), "4", "colecao"),
            Triple(a.getString(R.string.series_sinopse_mais), null, "sinopse"),
            Triple(a.getString(R.string.ext_nao_e_este), null, "naoeeste"),
        )
        itens.forEach { (t, d, m) -> FichaAcoes.adicionar(acoes, FichaAcoes.acao(a, t, d, m) {}) }
        val raiz = FrameLayout(a).apply { background = Estilo.fundoTela(a); setPadding(Estilo.dp(a, 48), Estilo.dp(a, 36), 0, 0) }
        // A coluna na largura de verdade (a ficha usa LARGURA_DP): antes somava os 48 dp da margem e ficava 288 dp, larga
        // demais para pegar o "Episódio" / "s" que a Sala mostrou com 240 dp (16/11/2026).
        raiz.addView(col, FrameLayout.LayoutParams(Estilo.dp(a, FichaAcoes.LARGURA_DP), -1))
        a.setContentView(raiz)
        acoes.getChildAt(0).apply { isFocusableInTouchMode = true; requestFocus() }
        val larg = Estilo.dp(a, 360); val alt = Estilo.dp(a, 540)
        raiz.measure(View.MeasureSpec.makeMeasureSpec(larg, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(alt, View.MeasureSpec.EXACTLY))
        raiz.layout(0, 0, larg, alt)
        val foto = Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888).also { raiz.draw(Canvas(it)) }
        File("build/telas").apply { mkdirs() }.let { File(it, "ficha-acoes-$idioma.png") }.outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun textos(v: View): List<TextView> = when (v) {
            is TextView -> listOf(v)
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
            else -> emptyList()
        }
        for (i in 0 until acoes.childCount) textos(acoes.getChildAt(i)).forEach { t ->
            val l = t.layout
            val cortado = l != null && l.lineCount > 0 && l.getEllipsisCount(l.lineCount - 1) > 0
            assertTrue("[$idioma] cortado: \"${t.text}\"", !cortado)
            // Quebra só entre palavras (na Sala, 16/11/2026, "Episódios" quebrava em "Episódio" / "s").
            if (l != null) for (n in 0 until l.lineCount - 1) {
                val fim = l.getLineEnd(n)
                assertTrue("[$idioma] palavra partida: \"${t.text}\"", fim <= 0 || fim >= t.text.length || t.text[fim - 1].isWhitespace() || t.text[fim].isWhitespace() || t.text[fim - 1] == '-')
            }
        }
    }
}

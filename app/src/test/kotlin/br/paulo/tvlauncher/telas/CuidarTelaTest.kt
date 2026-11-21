package br.paulo.tvlauncher.telas

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.CuidarDeLongeActivity
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.CuidarTelaActivity
import br.paulo.tvlauncher.family.CuidarDeLonge
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Cuidar de longe (15/11/2026): foto da pergunta grande (consentimento, pedido de ajuda, lembrete) e da tela de
 * configuração, em inglês e português, com regras fixas (sem referência a comparar): nada cortado, nada por cima de
 * outro texto, alguma coisa com o foco e, nas perguntas, letra de pelo menos 18 sp (Estilo.Leitura.PERGUNTA*).
 * Fotos em app/build/telas/cuidar-*.png. Roda com -Ptelas, como o TelasTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class CuidarTelaTest {
    private val saida = File("build/telas")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        CuidarDeLongeTv.mudar(app) {
            CuidarDeLonge.consentir(CuidarDeLonge.Config(nome = "Ana Beatriz", aparelhos = listOf("abc"),
                avisos = CuidarDeLonge.Aviso.values().toSet(),
                lembretes = listOf(CuidarDeLonge.Lembrete(1, 20, 0, CuidarDeLonge.TODO_DIA, "Remédio da pressão (1 comprimido)"))), 1L)
        }
        val problemas = mutableListOf<String>()
        fun tela(nome: String, modo: String?, minimoSp: Float) {
            val intent = Intent(app, if (modo == null) CuidarDeLongeActivity::class.java else CuidarTelaActivity::class.java)
            if (modo != null) intent.putExtra(CuidarTelaActivity.EXTRA_MODO, modo).putExtra(CuidarTelaActivity.EXTRA_CHAVE, "1@20261115")
                .putExtra(CuidarTelaActivity.EXTRA_TEXTO, "Remédio da pressão (1 comprimido)").putExtra(CuidarTelaActivity.EXTRA_MOMENTO, 1L)
            val ctl = if (modo == null) Robolectric.buildActivity(CuidarDeLongeActivity::class.java, intent)
                else Robolectric.buildActivity(CuidarTelaActivity::class.java, intent)
            val a: Activity = ctl.setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            problemas += medir(a, "$idioma/$nome", "cuidar-$idioma-$nome", minimoSp)
            a.finish()
        }
        tela("consentimento", CuidarTelaActivity.MODO_CONSENTIMENTO, 18f)
        tela("ajuda", CuidarTelaActivity.MODO_AJUDA, 18f)
        tela("lembrete", CuidarTelaActivity.MODO_LEMBRETE, 18f)
        tela("configuracao", null, 0f)
        assertTrue("Cuidar de longe ($idioma):\n" + problemas.joinToString("\n"), problemas.isEmpty())
    }

    private fun medir(a: Activity, chave: String, arquivo: String, minimoSp: Float): List<String> {
        val raiz: View = a.window.decorView
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
        val textos = mutableListOf<Pair<TextView, Rect>>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v is TextView && v.text.isNotBlank() && v.width > 0 && v.height > 0) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r)) textos += v to r
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        val p = mutableListOf<String>()
        fun rotulo(t: TextView) = t.text.toString().replace('\n', ' ').trim().take(40)
        for ((t, _) in textos) {
            val sp = t.textSize / escala
            if (sp < minimoSp - 0.05f) p += "$chave: letra pequena (${sp}sp) em \"${rotulo(t)}\""
            if (t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } == true) p += "$chave: texto cortado \"${rotulo(t)}\""
        }
        // Só nas perguntas (cartão único): as caixas não podem se cruzar. A tela de configuração rola e tem linhas fora da vista.
        if (minimoSp > 0f) for (i in textos.indices) for (j in i + 1 until textos.size) {
            val (t1, r1) = textos[i]; val (t2, r2) = textos[j]
            if (t1.parent === t2 || t2.parent === t1) continue
            val x = Rect()
            if (x.setIntersect(r1, r2) && x.width() > 3 && x.height() > 3) p += "$chave: \"${rotulo(t1)}\" por cima de \"${rotulo(t2)}\""
        }
        if (raiz.findFocus() == null) p += "$chave: nada tem o foco"
        return p
    }
}

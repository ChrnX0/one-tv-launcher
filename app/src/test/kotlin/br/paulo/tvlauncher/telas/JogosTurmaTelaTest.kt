package br.paulo.tvlauncher.telas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.JogosActivity
import br.paulo.tvlauncher.games.TurmaDiscord
import br.paulo.tvlauncher.games.TurmaDiscordTv
import br.paulo.tvlauncher.listen.CruzOuvir
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Tela Jogos com a turma do Discord (17/11/2026; na cruz desde 18/11/2026): foto na 1ª prateleira e na turma (foco na sala de
 * voz, com o QR), em inglês e português, com nomes de verdade compridos. Regras fixas: nada por
 * cima de outro texto, letra de pelo menos 11 sp (a da barra de dicas) e alguma coisa com o foco. Fotos em app/build/telas/jogos-turma-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class JogosTurmaTelaTest {
    private val saida = File("build/telas")

    // Formato real do widget, nomes inventados.
    private val widget = """
        {"id":"81384788765712384","name":"Os Parças","channels":[{"id":"111111111111111111","name":"Squad","position":0}],
         "members":[
           {"username":"Lucas","status":"online","activity":{"name":"Fortnite"},"channel_id":"111111111111111111"},
           {"username":"Mariana Albuquerque","status":"online","activity":{"name":"Fortnite"},"channel_id":"111111111111111111"},
           {"username":"Tio Beto","status":"dnd","game":{"name":"EA SPORTS FC 26"}},
           {"username":"Ju","status":"idle"},
           {"username":"Rafa","status":"online"}],
         "presence_count":9}
    """.trimIndent()

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        TurmaDiscordTv.guardarServidor(app, "81384788765712384")
        TurmaDiscordTv.guardarWebhook(app, "https://discord.com/api/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123-xyz")
        TurmaDiscordTv.lembrar(TurmaDiscordTv.Estado.Pronta(TurmaDiscord.ler(widget)))
        val a = Robolectric.buildActivity(JogosActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        val problemas = mutableListOf<String>()
        problemas += medir(a, "$idioma/fechado", "jogos-turma-$idioma-fechado")
        // ▶ até a turma (redesenho na cruz, 18/11/2026): a sala de voz é o 1º item da prateleira.
        val raiz = a.window.decorView as ViewGroup
        val cruz = achar(raiz) { it is CruzOuvir } as CruzOuvir?
        cruz?.ir("turma", animar = false)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        problemas += medir(a, "$idioma/aberto", "jogos-turma-$idioma-aberto")
        if (cruz?.itemAtual()?.nome?.contains("Squad") != true) problemas += "$idioma: a sala de voz não apareceu na turma"
        a.finish()
        assertTrue("Jogos com a turma ($idioma):\n" + problemas.joinToString("\n"), problemas.isEmpty())
    }

    private fun achar(v: View, ok: (View) -> Boolean): View? {
        if (ok(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) achar(v.getChildAt(i), ok)?.let { return it }
        return null
    }

    private fun textos(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun medir(a: android.app.Activity, chave: String, arquivo: String): List<String> {
        val raiz: View = a.window.decorView
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        @Suppress("DEPRECATION") val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
        val lista = mutableListOf<Pair<TextView, Rect>>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v is TextView && v.text.isNotBlank() && v.width > 0 && v.height > 0) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r)) lista += v to r
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        val p = mutableListOf<String>()
        fun rotulo(t: TextView) = t.text.toString().replace('\n', ' ').trim().take(40)
        for ((t, _) in lista) {
            val sp = t.textSize / escala
            if (sp < 11f - 0.05f) p += "$chave: letra pequena (${sp}sp) em \"${rotulo(t)}\""
        }
        for (i in lista.indices) for (j in i + 1 until lista.size) {
            val (t1, r1) = lista[i]; val (t2, r2) = lista[j]
            if (t1.parent === t2 || t2.parent === t1) continue
            val x = Rect()
            if (x.setIntersect(r1, r2) && x.width() > 3 && x.height() > 3) p += "$chave: \"${rotulo(t1)}\" por cima de \"${rotulo(t2)}\""
        }
        if (raiz.findFocus() == null) p += "$chave: nada tem o foco"
        return p
    }
}

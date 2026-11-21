package br.paulo.tvlauncher.telas

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.ReceitasActivity
import br.paulo.tvlauncher.panel.confirmar
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
 * Receitas prontas (degrau 4): a lista "Para quem é esta TV?" e a confirmação de duas receitas (com avisos), em
 * inglês e português. Fotos em app/build/telas/receitas-*.png. Roda com -Ptelas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ReceitasTelaTest {
    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = RuntimeEnvironment.getApplication()
        val problemas = mutableListOf<String>()
        fun tela(nome: String, confirmar: String?) {
            val i = Intent(app, ReceitasActivity::class.java)
            if (confirmar != null) i.putExtra(ReceitasActivity.EXTRA_CONFIRMAR, confirmar)
            val a = Robolectric.buildActivity(ReceitasActivity::class.java, i).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            problemas += MedirTela.medir(a, "$idioma/$nome", "receitas-$idioma-$nome", if (confirmar == null) 0f else 16f, cartao = confirmar != null)
            a.finish()
        }
        tela("lista", null)
        tela("vo", "VO")
        tela("criancas", "CRIANCAS")
        assertTrue("Receitas ($idioma):\n" + problemas.joinToString("\n"), problemas.isEmpty())
    }
}

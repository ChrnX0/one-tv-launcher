package br.paulo.tvlauncher.telas

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.RecadoVideo
import br.paulo.tvlauncher.RecadoVideoActivity
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Recado em vídeo (degrau 4): o cartão "Vídeo de Ana · OK para assistir" e a lista, em inglês e português. -Ptelas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class RecadoVideoTelaTest {
    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = RuntimeEnvironment.getApplication()
        val nome = "v-20261117-101500-0a1b2c3d.mp4"
        File(RecadoVideo.pasta(app)!!, nome).writeBytes(ByteArray(10))
        app.getSharedPreferences("familia_video", Context.MODE_PRIVATE).edit().putString("lista", JSONArray().put(JSONObject()
            .put("a", nome).put("q", "Ana Beatriz").put("t", "Olha o Theo andando de bicicleta!").put("w", 1_763_370_000_000L).put("b", 10)).toString()).commit()
        val problemas = mutableListOf<String>()
        fun tela(nomeTela: String, arquivo: String?) {
            val i = Intent(app, RecadoVideoActivity::class.java)
            if (arquivo != null) i.putExtra(RecadoVideoActivity.EXTRA_ARQUIVO, arquivo)
            val a = Robolectric.buildActivity(RecadoVideoActivity::class.java, i).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            problemas += MedirTela.medir(a, "$idioma/$nomeTela", "video-$idioma-$nomeTela", if (arquivo != null) 16f else 0f, cartao = arquivo != null)
            a.finish()
        }
        tela("cartao", nome)
        tela("lista", null)
        assertTrue("Vídeo ($idioma):\n" + problemas.joinToString("\n"), problemas.isEmpty())
    }
}

package br.paulo.tvlauncher.telas

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.ContinuarActivity
import br.paulo.tvlauncher.ContinuarOutraTv
import br.paulo.tvlauncher.player.JuntoRegras
import br.paulo.tvlauncher.SessaoJunto
import br.paulo.tvlauncher.SessaoJuntoActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Degrau 4: cartões "Quarto chama para assistir junto" (filme e canal) e "Continuar de onde parou?", en + pt. -Ptelas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class JuntoTelaTest {
    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val problemas = mutableListOf<String>()
        fun <T : Activity> tela(classe: Class<T>, nome: String) {
            val a = Robolectric.buildActivity(classe).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            problemas += MedirTela.medir(a, "$idioma/$nome", "junto-$idioma-$nome", 16f, cartao = true)
            a.finish()
        }
        val agora = System.currentTimeMillis()
        SessaoJunto.conviteParaTeste(JuntoRegras.Convite("0123456789abcdef0123456789abcdef", "onetv-quarto-dos-pais",
            "O Senhor dos Anéis: A Sociedade do Anel", "tmdb:filme:120", agora))
        tela(SessaoJuntoActivity::class.java, "convite")
        SessaoJunto.conviteParaTeste(JuntoRegras.Convite("0123456789abcdef0123456789abcdef", "onetv-quarto-dos-pais",
            "Globo HD", "canal:globo", agora))
        tela(SessaoJuntoActivity::class.java, "convite-canal")
        SessaoJunto.conviteParaTeste(null)
        ContinuarOutraTv.paraTeste(JuntoRegras.Continuar("Férias em família 2025.mkv", "arquivo:Ferias.mkv", 3_723_000,
            "smb://nas/videos/Ferias.mkv", "onetv-sala"))
        tela(ContinuarActivity::class.java, "continuar")
        ContinuarOutraTv.paraTeste(JuntoRegras.Continuar("Duna: Parte Dois", "tmdb:filme:693134", 2_000_000, null, "onetv-sala"))
        tela(ContinuarActivity::class.java, "continuar-servico")
        ContinuarOutraTv.paraTeste(null)
        assertTrue("Junto ($idioma):\n" + problemas.joinToString("\n"), problemas.isEmpty())
    }
}

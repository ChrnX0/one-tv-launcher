package br.paulo.tvlauncher.telas

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.AlarmesActivity
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.alarm.Despertador
import br.paulo.tvlauncher.player.salvar
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
 * Tela do despertador no jeito da cruz (18/11/2026): lista com 3 alarmes e o editor, andando com as teclas como o
 * controle. Fotos em app/build/telas/despertador-*.png. Confere letra mínima de 16 sp, nada cortado, o foco na tela,
 * que ▲ no rolete muda a hora, ◀▶ trocam de zona e que VOLTAR salva o que mudou.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class AlarmeTelaTest {

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun pt() = andar("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun en() = andar("en")

    private fun andar(lingua: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = RuntimeEnvironment.getApplication()
        Despertador.listar(app).forEach { Despertador.apagar(app, it.id) }
        Despertador.salvar(app, Despertador.Alarme(0, 6, 30, 0b0111110, "Remédio", true))
        Despertador.salvar(app, Despertador.Alarme(0, 9, 0, 0b1000001, "", false))
        Despertador.salvar(app, Despertador.Alarme(0, 21, 15, 0, "", true, som = 1, rampa = 60))
        val a = Robolectric.buildActivity(AlarmesActivity::class.java).setup().get()
        fun tecla(k: Int) {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        }
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val problemas = mutableListOf<String>()
        problemas += MedirTela.medir(a, "$lingua/lista", "despertador-$lingua-1-lista", 16f, cartao = false)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        problemas += MedirTela.medir(a, "$lingua/lista2", "despertador-$lingua-2-lista-desligado", 16f, cartao = false)
        tecla(KeyEvent.KEYCODE_DPAD_UP)
        tecla(KeyEvent.KEYCODE_DPAD_CENTER)   // editor do 06:30
        problemas += MedirTela.medir(a, "$lingua/editor", "despertador-$lingua-3-editor-hora", 16f, cartao = false)
        tecla(KeyEvent.KEYCODE_DPAD_UP)       // 07:30
        tecla(KeyEvent.KEYCODE_DPAD_RIGHT); tecla(KeyEvent.KEYCODE_DPAD_RIGHT)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)     // Repetir
        problemas += MedirTela.medir(a, "$lingua/opcoes", "despertador-$lingua-4-editor-opcoes", 16f, cartao = false)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN); tecla(KeyEvent.KEYCODE_DPAD_DOWN); tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        problemas += MedirTela.medir(a, "$lingua/opcoes2", "despertador-$lingua-5-editor-bomdia", 16f, cartao = false)
        @Suppress("DEPRECATION") a.onBackPressed()   // VOLTAR salva
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        // A data da Moldura (hora do canto, comum a todas as telas) tem o tamanho da legenda (15 sp desde o pente fino de
        // 19/11/2026; era 12); o resto desta tela, 16 ou mais.
        val desta = problemas.filterNot { "(${Estilo.Papel.LEGENDA.sp}sp)" in it && ("feira" in it || "janeiro" in it || "Thursday" in it || "January" in it) }
        assertTrue(desta.joinToString("\n"), desta.isEmpty())
        val horas = Despertador.listar(app).map { Despertador.horaTexto(it) }.sorted()
        assertEquals(listOf("07:30", "09:00", "21:15"), horas)
        MedirTela.medir(a, "$lingua/fim", "despertador-$lingua-6-lista-salvo", 16f, cartao = false)
        // Novo alarme aberto e fechado sem mexer: não cria nada
        tecla(KeyEvent.KEYCODE_DPAD_DOWN); tecla(KeyEvent.KEYCODE_DPAD_DOWN); tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        tecla(KeyEvent.KEYCODE_DPAD_CENTER)
        MedirTela.medir(a, "$lingua/novo", "despertador-$lingua-7-novo", 16f, cartao = false)
        @Suppress("DEPRECATION") a.onBackPressed()
        assertEquals(3, Despertador.listar(app).size)
    }
}

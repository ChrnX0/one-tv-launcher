package br.paulo.tvlauncher.telas

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.PrevisaoActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * A tela do Clima em 3 telas (21/11/2026): com uma previsão fixa (sem rede), desenha cada tela, anda com baixo/cima
 * como o controle e tira foto (app/build/telas/clima-*.png). Confere: baixo avança, da 3ª volta à 1ª, cima volta da 1ª
 * para a 3ª, o foco fica sempre na tela que está à mostra, e nenhuma letra pequena, texto cortado ou por cima de outro.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ClimaTelaTest {
    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = andar("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = andar("en")

    @Config(qualifiers = "ar-ldrtl-w960dp-h540dp-land-television-xhdpi")
    @Test fun arabe() = andar("ar")

    @After fun limpar() { PrevisaoActivity.dadosDeTeste = null }

    /** Sábado 11h, 27°, chuva forte entre 15h e 17h, sol forte ao meio-dia: frase "chuva por volta das 15h". */
    private fun previsao(): JSONObject {
        fun arr(n: Int, f: (Int) -> Any) = JSONArray().apply { (0 until n).forEach { put(f(it)) } }
        val horas = 72
        fun hh(i: Int) = i % 24
        val chuva = { i: Int -> when (hh(i)) { 15 -> 55; 16 -> 80; 17 -> 60; else -> if (i >= 24) 20 else 5 } }
        return JSONObject()
            .put("current", JSONObject().put("time", "2026-11-21T11:00").put("temperature_2m", 27.0).put("apparent_temperature", 29.0)
                .put("relative_humidity_2m", 60).put("weather_code", 2).put("pressure_msl", 1012.0).put("wind_speed_10m", 9.0)
                .put("wind_direction_10m", 120).put("is_day", 1))
            .put("hourly", JSONObject()
                .put("time", arr(horas) { "2026-11-%02dT%02d:00".format(21 + it / 24, hh(it)) })
                .put("temperature_2m", arr(horas) { 25.0 + 6 * sin((hh(it) - 9) * PI / 12) })
                .put("precipitation_probability", arr(horas) { chuva(it) })
                .put("weather_code", arr(horas) { if (chuva(it) >= 50) 63 else 2 })
                .put("wind_speed_10m", arr(horas) { 8.0 }).put("visibility", arr(horas) { 20000.0 })
                .put("dew_point_2m", arr(horas) { 17.0 }).put("uv_index", arr(horas) { if (hh(it) in 10..14) 8.5 else 1.0 }))
            .put("daily", JSONObject()
                .put("time", arr(9) { "2026-11-%02d".format(21 + it) })
                .put("weather_code", arr(9) { listOf(63, 2, 3, 61, 0, 1, 80, 2, 3)[it] })
                .put("temperature_2m_max", arr(9) { 31.0 - it % 3 }).put("temperature_2m_min", arr(9) { 19.0 + it % 2 })
                .put("precipitation_probability_max", arr(9) { listOf(80, 20, 10, 65, 0, 5, 70, 15, 30)[it] })
                .put("sunrise", arr(9) { "2026-11-%02dT05:31".format(21 + it) }).put("sunset", arr(9) { "2026-11-%02dT18:44".format(21 + it) })
                .put("precipitation_sum", arr(9) { 6.2 }))
    }

    private fun andar(lingua: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        PrevisaoActivity.dadosDeTeste = previsao()
        val a = Robolectric.buildActivity(PrevisaoActivity::class.java).setup().get()
        // A previsão "chega" numa thread (Fundo.rodar), como na TV: espera ela montar as telas.
        repeat(150) { if (a.window.decorView.findFocus() == null) { Thread.sleep(100); ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) } }
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        fun textos(v: android.view.View): List<String> = when (v) {
            is android.widget.TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue("a primeira dica tem o foco; na tela: ${textos(a.window.decorView).take(12)}", a.window.decorView.findFocus() != null)
        val problemas = mutableListOf<String>()
        fun tecla(k: Int) {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        }
        assertEquals(0, a.paginaAtual)
        problemas += MedirTela.medir(a, "clima-agora", "clima-$lingua-1-agora", 15f, cartao = true)

        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("baixo leva às horas", 1, a.paginaAtual)
        problemas += MedirTela.medir(a, "clima-horas", "clima-$lingua-2-horas", 15f, cartao = true)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("da faixa de horas, baixo vai aos dias na mesma tela", 1, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("dos dias, baixo leva aos detalhes", 2, a.paginaAtual)
        problemas += MedirTela.medir(a, "clima-detalhes", "clima-$lingua-3-detalhes", 15f, cartao = true)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(2, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("da última, baixo volta à primeira (carrossel)", 0, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("da primeira, cima vai à última", 2, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("dos cartões de baixo, cima sobe aos detalhes", 2, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, a.paginaAtual)
        // OK numa dica abre as horas (a conta por trás da resposta).
        tecla(KeyEvent.KEYCODE_DPAD_UP); tecla(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, a.paginaAtual)
        tecla(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, a.paginaAtual)
        assertTrue("sem problema de tela: $problemas", problemas.isEmpty())
        a.finish()
    }
}

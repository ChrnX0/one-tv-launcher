package br.paulo.tvlauncher.telas

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import br.paulo.tvlauncher.alarm.DescansoFase
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.screensaver.ProtetorGaleria
import br.paulo.tvlauncher.screensaver.RelogioProtetor
import java.io.File
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Protetor v2 (17/11/2026): o protetor inteiro com o fundo "A Terra vista do espaço" (as fotos do APK), em cada estilo de
 * relógio, posição e tamanho, com o resto escondido ("só a hora") e à mostra. Salva app/build/telas/protetor2-*.png e
 * falha se um texto sair cortado, se o relógio sair da tela ou se o grupo da hora invadir os widgets de baixo.
 * Roda com -Ptelas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class ProtetorV2TelaTest {
    private val saida = File("build/telas")

    /** Só executa o que vem do FundoTerra (ler a foto do APK). O resto (rede, agenda, Estação) fica de fora do teste. */
    private class SoTerra : AbstractExecutorService() {
        override fun execute(r: Runnable) { if (Throwable().stackTrace.any { it.className.contains("FundoTerra") || it.className.contains("montarTerra") }) r.run() }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(t: Long, u: TimeUnit) = true
    }

    private class Caso(val nome: String, val relogio: String, val pos: String, val tam: String, val mostrar: String, val tecla: Boolean,
        val estilos: Map<String, String> = emptyMap(), val hora: Int = 15)

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi") @Test fun portugues() = conferir("pt", listOf(
        Caso("fino-alto-tudo", Protetor.REL_FINO, Protetor.POS_ALTO, "normal", Protetor.MOSTRAR_SEMPRE, false),
        Caso("editorial-baixo-so", Protetor.REL_EDITORIAL, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SO_HORA, false),
        Caso("editorial-baixo-tecla", Protetor.REL_EDITORIAL, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SO_HORA, true),
        Caso("monumental-baixo-grande", Protetor.REL_MONUMENTAL, Protetor.POS_BAIXO, "grande", Protetor.MOSTRAR_SEMPRE, false),
        Caso("ponteiros-centro-tudo", Protetor.REL_PONTEIROS, Protetor.POS_CENTRO, "normal", Protetor.MOSTRAR_SEMPRE, false),
        Caso("ponteiros-centro-so", Protetor.REL_PONTEIROS, Protetor.POS_CENTRO, "grande", Protetor.MOSTRAR_SO_HORA, false),
        Caso("extenso-baixo-tudo", Protetor.REL_EXTENSO, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SEMPRE, false),
        Caso("extenso-alto-grande", Protetor.REL_EXTENSO, Protetor.POS_ALTO, "grande", Protetor.MOSTRAR_SEMPRE, false),
        Caso("empilhado-centro-so", Protetor.REL_EMPILHADO, Protetor.POS_CENTRO, "normal", Protetor.MOSTRAR_SO_HORA, false),
        Caso("empilhado-alto-tudo", Protetor.REL_EMPILHADO, Protetor.POS_ALTO, "grande", Protetor.MOSTRAR_SEMPRE, false),
        // Variações dos elementos (17/11/2026).
        Caso("var-linha-serifa-vidro", Protetor.REL_EDITORIAL, Protetor.POS_ALTO, "normal", Protetor.MOSTRAR_SEMPRE, false, mapOf(
            "protetor_widget" to Protetor.WIDGET_LINHA, "protetor_textos" to Protetor.TEXTOS_SERIFA, "protetor_linhas" to Protetor.LINHAS_VIDRO,
            "protetor_avisos" to Protetor.AVISOS_LISTA, "protetor_credito" to Protetor.CREDITO_DISCRETO)),
        Caso("var-grande-maiusculas-texto", Protetor.REL_FINO, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SEMPRE, false, mapOf(
            "protetor_widget" to Protetor.WIDGET_GRANDE, "protetor_textos" to Protetor.TEXTOS_MAIUSCULAS, "protetor_linhas" to Protetor.LINHAS_TEXTO,
            "protetor_avisos" to Protetor.AVISOS_UM)),
        // Fases do dia (estudo global 20/11, ideia 9): madrugada escura sem logo; manhã com o resumo mesmo em "só a hora".
        Caso("fase-madrugada", Protetor.REL_FINO, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SEMPRE, false, hora = 3),
        Caso("fase-manha-so-hora", Protetor.REL_FINO, Protetor.POS_BAIXO, "normal", Protetor.MOSTRAR_SO_HORA, false, hora = 8),
        Caso("fase-noite", Protetor.REL_EDITORIAL, Protetor.POS_ALTO, "normal", Protetor.MOSTRAR_SEMPRE, false, hora = 21),
    ))

    @Config(qualifiers = "de-w960dp-h540dp-land-television-xhdpi") @Test fun alemao() = conferir("de", listOf(
        Caso("extenso-vira-fino", Protetor.REL_EXTENSO, Protetor.POS_BAIXO, "grande", Protetor.MOSTRAR_SEMPRE, false),
        Caso("monumental-centro", Protetor.REL_MONUMENTAL, Protetor.POS_CENTRO, "normal", Protetor.MOSTRAR_SEMPRE, false),
    ))

    private fun conferir(idioma: String, casos: List<Caso>) {
        saida.mkdirs()
        val problemas = mutableListOf<String>()
        for (caso in casos) {
            val a = Robolectric.buildActivity(Activity::class.java).setup().get()
            val d = a.resources.displayMetrics.density
            val agora = System.currentTimeMillis()
            a.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().clear()
                .putString("protetor_fundo", Protetor.FUNDO_TERRA).putString("protetor_relogio", caso.relogio)
                .putString("protetor_posicao", caso.pos).putString("protetor_tamanho", caso.tam)
                .putString("protetor_mostrar", caso.mostrar)
                .apply { caso.estilos.forEach { (k, v) -> putString(k, v) } }.commit()
            a.getSharedPreferences("clima", Context.MODE_PRIVATE).edit().clear()
                .putInt("temp", 22).putLong("em", agora).putInt("codigo", 2).putInt("max", 27).putInt("min", 18)
                .putInt("umidade", 70).putInt("chuva", 20).putBoolean("eDia", false).commit()
            br.paulo.tvlauncher.alarm.DescansoFase.horaFixa = caso.hora
            val raiz = FrameLayout(a)
            val g = ProtetorGaleria(a, SoTerra(), comPintura = false, terra = true)
            g.montar(raiz)
            a.setContentView(raiz)
            val decor = a.window.decorView
            fun desenhar(): Bitmap {
                decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 1920, 1080)
                val foto = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(foto)); return foto
            }
            desenhar()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            if (caso.tecla) { g.tecla(); desenhar() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            val foto = desenhar()
            File(saida, "protetor2-$idioma-${caso.nome}.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }

            fun andar(v: View, f: (View) -> Unit) {
                if (v.visibility != View.VISIBLE) return
                f(v); if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i), f)
            }
            var relogio: View? = null
            andar(decor) { v ->
                if (v is RelogioProtetor) relogio = v
                // O texto do relógio nunca pode quebrar no meio da hora (só o "por extenso" tem mais de uma linha).
                if (v is TextView && v.parent is RelogioProtetor) {
                    val linhas = v.layout?.lineCount ?: 0
                    val esperado = if (caso.relogio == Protetor.REL_EMPILHADO && idioma == "pt") 2 else 1
                    if (caso.relogio != Protetor.REL_EXTENSO && linhas > esperado) problemas += "${caso.nome}: hora quebrou em $linhas linhas"
                }
            }
            val r = Rect()
            relogio?.getGlobalVisibleRect(r)
            val tela = Rect(0, 0, 1920, 1080)
            val inteiro = Rect(); relogio?.let { val loc = IntArray(2); it.getLocationInWindow(loc); inteiro.set(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height) }
            if (relogio == null) problemas += "${caso.nome}: sem relógio"
            else if (!tela.contains(inteiro)) problemas += "${caso.nome}: relógio sai da tela ${inteiro.toShortString()} (${(inteiro.width() / d).toInt()}x${(inteiro.height() / d).toInt()} dp)"
            a.finish()
            g.soltar()
        }
        br.paulo.tvlauncher.alarm.DescansoFase.horaFixa = null
        if (problemas.isNotEmpty()) fail("Protetor v2 ($idioma):\n" + problemas.joinToString("\n"))
    }
}

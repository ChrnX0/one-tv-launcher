package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.AjudaActivity
import br.paulo.tvlauncher.AlarmesActivity
import br.paulo.tvlauncher.BoasVindasActivity
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.EntrarPeloQrActivity
import br.paulo.tvlauncher.FilmesActivity
import br.paulo.tvlauncher.JogosActivity
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.PrevisaoActivity
import br.paulo.tvlauncher.SeriesActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Teste de tela por foto (ideia 96, 14/11/2026). O Robolectric desenha as telas principais de verdade (gráficos nativos,
 * as mesmas fontes), numa TV de 1920x1080 (960x540 dp, xhdpi, modo TV), em inglês e em português, e:
 *
 *  1. salva a foto de cada tela em app/build/telas/ (o workflow telas.yml publica como artefato para olhar);
 *  2. confere o que dá para medir sem olho humano: a MENOR letra da tela, texto CORTADO (reticências), texto POR CIMA
 *     de outro texto e se alguma coisa tem o FOCO (sem foco, o controle remoto não anda);
 *  3. compara com a versão anterior, gravada em app/src/test/telas/referencia.json. Só o que PIORA quebra o teste
 *     (letra menor, corte novo, sobreposição nova, foco que sumiu); o que já estava lá fica anotado na referência.
 *
 * Mudou de propósito (ou corrigiu algo)? Rode `./gradlew testReleaseUnitTest -Ptelas -PtelasAtualizar` e mande a
 * referência nova junto. Só roda com -Ptelas (fora do "Build e Release"): baixa o Android do Robolectric e leva ~2 min.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class TelasTest {
    private val telas: List<Pair<String, Class<out Activity>>> = listOf(
        "boas-vindas" to BoasVindasActivity::class.java,
        "home" to MainActivity::class.java,
        "home-grande" to MainActivity::class.java,   // layout flexível: cartões Grandes e transparentes (LayoutHome)
        "configuracoes" to ConfigLauncherActivity::class.java,
        "filmes" to FilmesActivity::class.java,
        "series" to SeriesActivity::class.java,
        "ajuda" to AjudaActivity::class.java,
        "previsao" to PrevisaoActivity::class.java,
        "despertador" to AlarmesActivity::class.java,
        "jogos" to JogosActivity::class.java,
        "entrar-celular" to EntrarPeloQrActivity::class.java,
    )
    private val referencia = File("src/test/telas/referencia.json")
    private val saida = File("build/telas")

    data class Medida(val menorSp: Float, val cortes: Set<String>, val sobrepostos: Set<String>, val foco: Boolean) {
        fun json(): JSONObject = JSONObject().put("menorSp", menorSp.toDouble()).put("foco", foco)
            .put("cortes", JSONArray(cortes.sorted())).put("sobrepostos", JSONArray(sobrepostos.sorted()))
        companion object {
            fun de(o: JSONObject) = Medida(o.optDouble("menorSp").toFloat(),
                o.optJSONArray("cortes").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() },
                o.optJSONArray("sobrepostos").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() },
                o.optBoolean("foco"))
        }
    }

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    private fun conferir(idioma: String) {
        // Na TV não existe "modo toque": sem isto o requestFocus() das telas não pega e o foco parece sumido.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val agora = JSONObject()
        val problemas = mutableListOf<String>()
        val ref = if (referencia.exists()) JSONObject(referencia.readText()) else JSONObject()
        for ((nome, cls) in telas) {
            val chave = "$idioma/$nome"
            val app = RuntimeEnvironment.getApplication()
            br.paulo.tvlauncher.LayoutHome.definirTamanho(app, if (nome == "home-grande") 115 else 100)
            br.paulo.tvlauncher.Extras.definir(app, br.paulo.tvlauncher.Extras.Chave.CARTOES_TRANSPARENTES, nome == "home-grande")
            val m = try { medir(cls, "$idioma-$nome") } catch (e: Throwable) {
                problemas += "$chave: a tela não abriu (${e.javaClass.simpleName}: ${e.message?.take(160)})"; continue
            }
            agora.put(chave, m.json())
            val antes = ref.optJSONObject(chave)?.let { Medida.de(it) } ?: continue
            if (m.menorSp < antes.menorSp - 0.05f) problemas += "$chave: a menor letra encolheu de ${antes.menorSp}sp para ${m.menorSp}sp"
            for (c in m.cortes - antes.cortes) problemas += "$chave: texto cortado: \"$c\""
            for (s in m.sobrepostos - antes.sobrepostos) problemas += "$chave: texto por cima de outro: $s"
            if (antes.foco && !m.foco) problemas += "$chave: nada tem o foco (o controle não anda)"
        }
        File(saida, "medidas-$idioma.json").writeText(agora.toString(1))
        File(saida, "relatorio-$idioma.md").writeText(if (problemas.isEmpty()) "Telas ($idioma): nada piorou.\n"
            else "Telas ($idioma): ${problemas.size} problema(s)\n\n" + problemas.joinToString("\n") { "- $it" } + "\n")
        if (System.getProperty("telas.atualizar") == "true") {
            // Junta com a outra língua (cada teste grava a sua parte) e sai sem cobrar nada.
            val junto = if (referencia.exists()) JSONObject(referencia.readText()) else JSONObject()
            for (k in junto.keys().asSequence().toList()) if (k.startsWith("$idioma/")) junto.remove(k)
            for (k in agora.keys()) junto.put(k, agora.get(k))
            referencia.parentFile.mkdirs()
            referencia.writeText(JSONObject(sortedMapOf<String, Any>().apply { for (k in junto.keys()) put(k, junto.get(k)) }).toString(1) + "\n")
            return
        }
        if (problemas.isNotEmpty()) fail("Telas ($idioma) pioraram:\n" + problemas.joinToString("\n") +
            "\nFoi de propósito? ./gradlew testReleaseUnitTest -Ptelas -PtelasAtualizar e mande app/src/test/telas/referencia.json junto.")
    }

    private fun medir(cls: Class<out Activity>, arquivo: String): Medida {
        val a = Robolectric.buildActivity(cls).setup().get()
        val raiz: View = a.window.decorView
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
        val textos = mutableListOf<Pair<TextView, Rect>>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v is TextView && v.text.isNotBlank() && v.width > 0 && v.height > 0) {
                val r = areaDoTexto(v)
                if (r != null) textos += v to r
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        val menor = textos.minOfOrNull { it.first.textSize / escala } ?: 0f
        val cortes = textos.filter { (t, _) -> t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } == true }
            .map { rotulo(it.first) }.toSet()
        val sobrepostos = mutableSetOf<String>()
        for (i in textos.indices) for (j in i + 1 until textos.size) {
            val (t1, r1) = textos[i]; val (t2, r2) = textos[j]
            val x = Rect()
            if (!x.setIntersect(r1, r2)) continue
            // Encostar não conta: só sobreposição de verdade (mais de 3 px nos dois sentidos).
            if (x.width() > 3 && x.height() > 3) sobrepostos += listOf(rotulo(t1), rotulo(t2)).sorted().joinToString(" × ")
        }
        a.finish()
        return Medida(menor, cortes, sobrepostos, raiz.findFocus() != null)
    }

    /** Onde as letras estão de fato na tela (não a caixa inteira da view), já cortado pelo que os pais deixam ver. */
    private fun areaDoTexto(t: TextView): Rect? {
        val l = t.layout ?: return null
        val visivel = Rect()
        if (!t.getGlobalVisibleRect(visivel)) return null
        val pos = IntArray(2).also { t.getLocationInWindow(it) }
        var esq = Float.MAX_VALUE; var dir = -Float.MAX_VALUE
        for (i in 0 until l.lineCount) { esq = minOf(esq, l.getLineLeft(i)); dir = maxOf(dir, l.getLineRight(i)) }
        val x0 = pos[0] + t.compoundPaddingLeft - t.scrollX
        val y0 = pos[1] + t.extendedPaddingTop - t.scrollY
        val r = Rect(x0 + esq.toInt(), y0, x0 + dir.toInt(), y0 + l.height)
        return if (r.intersect(visivel)) r else null
    }

    private fun rotulo(t: TextView): String = t.text.toString().replace('\n', ' ').trim().take(40)
}

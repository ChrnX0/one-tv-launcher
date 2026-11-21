package br.paulo.tvlauncher.telas

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.home.SplashOverlay
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Auditoria de medidas (pente fino de 19/11/2026). Abre TODAS as telas do manifesto que abrem sem dados de fora,
 * desenha cada uma numa TV 1920x1080 (960x540 dp) e mede cada elemento contra regras fixas:
 *
 *  - CONTRASTE (WCAG 2.1 AA, 1.4.3): cor da letra contra o fundo DE VERDADE atrás dela. O fundo sai de uma segunda
 *    foto com as letras transparentes, então vale para cartão, foto e degradê. Letra grande (24 sp, ou 18,7 sp em
 *    negrito) pede 3:1; o resto 4,5:1.
 *  - LETRA: menor que 14 sp é pequena para ler a 3 m (vale para a TV, não para o celular).
 *  - ESCALA DE LETRAS: tamanhos fora da escala de papéis do app ([ESCALA]) e quantos tamanhos diferentes a tela usa.
 *  - MARGEM SEGURA (overscan, Android TV): texto ou elemento com foco a menos de 48 dp das laterais ou 27 dp de cima e
 *    de baixo (5% da tela) pode sumir em TVs que cortam a borda.
 *  - GRADE DE 4 dp: margens e paddings que não são múltiplos de 4 dp.
 *  - QUASE ALINHADO: dois textos cujas bordas esquerdas ficam a 1-3 dp uma da outra (parece erro, não intenção).
 *  - FOCO: elemento com foco menor que 32 dp de altura (alvo difícil de ver), e se alguém tem o foco ao abrir.
 *
 * Não quebra o build: grava app/build/telas/auditoria-<idioma>.json e .md. Roda só com -Ptelas -Pauditoria.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class AuditoriaTelasTest {
    private val saida = File("build/telas/auditoria")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = auditar("pt")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = auditar("en")

    private fun auditar(idioma: String) {
        assumeTrue(System.getProperty("telas.auditoria") == "true")
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        br.paulo.tvlauncher.home.SplashOverlay.setEnabled(app, false)
        val pm = app.packageManager
        val atividades = pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
            .map { it.name }.sorted()
        // Sem rede: a medida não pode depender do clima do dia nem de servidor de fora.
        System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "9")
        System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "9")
        val tudo = JSONObject()
        val md = StringBuilder("# Auditoria de medidas ($idioma), ${atividades.size} telas no manifesto\n\n")
        val naoAbriu = mutableListOf<String>()
        for (nome in atividades) {
            val curto = nome.substringAfterLast('.')
            @Suppress("UNCHECKED_CAST")
            val cls = try { Class.forName(nome) as Class<out Activity> } catch (e: Throwable) { naoAbriu += "$curto (classe)"; continue }
            val r = try { medir(cls, "$idioma-$curto") } catch (e: Throwable) {
                naoAbriu += "$curto (${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(100)})"; continue
            }
            tudo.put(curto, r)
            val achados = r.getJSONArray("achados")
            md.append("## $curto\n")
            md.append("textos ${r.getInt("textos")}, focáveis ${r.getInt("focaveis")}, tamanhos de letra ${r.getJSONArray("tamanhos")}, foco ao abrir: ${r.getBoolean("foco")}\n\n")
            if (achados.length() == 0) md.append("- nada fora das regras\n")
            for (i in 0 until achados.length()) md.append("- ").append(achados.getString(i)).append('\n')
            md.append('\n')
        }
        md.append("## Telas que não abriram sozinhas (precisam de dados de fora; conferir na TV)\n")
        for (n in naoAbriu) md.append("- ").append(n).append('\n')
        File(saida, "auditoria-$idioma.json").writeText(tudo.toString(1))
        File(saida, "auditoria-$idioma.md").writeText(md.toString())
    }

    private fun medir(cls: Class<out Activity>, arquivo: String): JSONObject {
        val a = Robolectric.buildActivity(cls).setup().get()
        if (cls == MainActivity::class.java) repeat(10) { Thread.sleep(100); org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        else org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        val raiz: View = a.window.decorView
        val dm = a.resources.displayMetrics
        val dp = dm.density
        val sp = dm.scaledDensity
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val textos = mutableListOf<Pair<TextView, Rect>>()
        val focaveis = mutableListOf<Pair<View, Rect>>()
        val todas = mutableListOf<View>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            todas += v
            val vis = Rect()
            val aparece = v.width > 0 && v.height > 0 && v.getGlobalVisibleRect(vis)
            if (aparece && v is TextView && v.text.isNotBlank()) areaDoTexto(v)?.let { textos += v to it }
            if (aparece && v.isFocusable && v !is ViewGroup || aparece && v.isFocusable && v is ViewGroup && v.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) focaveis += v to vis
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)

        // Segunda foto: as mesmas telas com as letras transparentes = o fundo de verdade atrás de cada texto.
        val cores = textos.map { it.first.currentTextColor }
        textos.forEach { it.first.setTextColor(Color.TRANSPARENT) }
        val fundo = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(fundo))
        textos.forEachIndexed { i, (t, _) -> t.setTextColor(cores[i]) }

        val achados = mutableListOf<String>()
        fun rot(t: TextView) = "\"" + t.text.toString().replace('\n', ' ').trim().take(36) + "\""
        val w = raiz.width; val h = raiz.height
        val tamanhos = sortedSetOf<Float>()
        for ((i, par) in textos.withIndex()) {
            val (t, r) = par
            val tam = ((t.textSize / sp) * 10).roundToInt() / 10f
            tamanhos += tam
            val negrito = t.typeface?.isBold == true || (t.typeface?.weight ?: 400) >= 600
            // Contraste: o fundo é a mediana da luminância dos pontos atrás das letras.
            val bg = mediana(fundo, r)
            if (bg != null) {
                val fg = compor(cores[i], bg)
                val c = contraste(fg, bg)
                val grande = tam >= 24f || (negrito && tam >= 18.6f)
                val minimo = if (grande) 3.0 else 4.5
                if (c < minimo) achados += "CONTRASTE ${"%.2f".format(c)}:1 (mínimo $minimo) em ${rot(t)}: letra ${hex(fg)} sobre ${hex(bg)}, ${tam}sp"
            }
            if (tam < 14f) achados += "LETRA PEQUENA ${tam}sp em ${rot(t)} (mínimo 14 sp para ler a 3 m)"
            if (tam !in ESCALA) achados += "FORA DA ESCALA ${tam}sp em ${rot(t)} (escala: ${ESCALA.joinToString()})"
            if (r.left < 48 * dp || r.right > w - 48 * dp || r.top < 27 * dp || r.bottom > h - 27 * dp)
                achados += "FORA DA MARGEM SEGURA ${rot(t)} em ${caixa(r, dp)} (5%: 48 dp dos lados, 27 dp em cima e embaixo)"
        }
        for ((v, r) in focaveis) {
            val nome = (v.contentDescription ?: (v as? TextView)?.text ?: v.javaClass.simpleName).toString().take(30)
            if (r.height() < 32 * dp) achados += "FOCO PEQUENO \"$nome\" com ${(r.height() / dp).roundToInt()} dp de altura (mínimo 32 dp)"
            if (r.left < 48 * dp || r.right > w - 48 * dp || r.top < 27 * dp || r.bottom > h - 27 * dp)
                achados += "FOCÁVEL FORA DA MARGEM SEGURA \"$nome\" em ${caixa(r, dp)}"
        }
        // Grade de 4 dp: margens e paddings (o que alguém escolheu à mão).
        val foraGrade = sortedMapOf<Int, Int>()
        for (v in todas) {
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            val vals = listOfNotNull(lp?.leftMargin, lp?.topMargin, lp?.rightMargin, lp?.bottomMargin,
                v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            for (px in vals) if (px > 0) {
                val d = (px / dp).roundToInt()
                if (d % 4 != 0 && abs(px / dp - d) < 0.3f) foraGrade[d] = (foraGrade[d] ?: 0) + 1
            }
        }
        if (foraGrade.isNotEmpty()) achados += "FORA DA GRADE DE 4 dp: " + foraGrade.entries.joinToString { "${it.key} dp ×${it.value}" }
        // Quase alinhado: bordas esquerdas de textos a 1-3 dp (mesma coluna "quase").
        val bordas = textos.map { it.second.left / dp to it.first }
        val vistos = mutableSetOf<String>()
        for (i in bordas.indices) for (j in i + 1 until bordas.size) {
            val d = abs(bordas[i].first - bordas[j].first)
            if (d >= 1f && d <= 3f) {
                val k = "${rot(bordas[i].second)} × ${rot(bordas[j].second)}"
                if (vistos.add(k) && vistos.size <= 8) achados += "QUASE ALINHADO (${"%.1f".format(d)} dp) $k"
            }
        }
        if (raiz.findFocus() == null && focaveis.isNotEmpty()) achados += "SEM FOCO AO ABRIR: o controle não sabe onde começa"
        val foco = raiz.findFocus() != null
        a.finish()
        return JSONObject().put("textos", textos.size).put("focaveis", focaveis.size).put("foco", foco)
            .put("tamanhos", JSONArray(tamanhos.toList())).put("achados", JSONArray(achados))
    }

    private fun caixa(r: Rect, dp: Float) = "(${(r.left / dp).roundToInt()},${(r.top / dp).roundToInt()})-(${(r.right / dp).roundToInt()},${(r.bottom / dp).roundToInt()}) dp"

    private fun mediana(b: Bitmap, r: Rect): Int? {
        val x0 = r.left.coerceIn(0, b.width - 1); val x1 = r.right.coerceIn(0, b.width)
        val y0 = r.top.coerceIn(0, b.height - 1); val y1 = r.bottom.coerceIn(0, b.height)
        if (x1 <= x0 || y1 <= y0) return null
        val px = ArrayList<Int>()
        val passo = maxOf(1, ((x1 - x0) * (y1 - y0) / 4000))
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { if (n++ % passo == 0) px += b.getPixel(x, y) }
        if (px.isEmpty()) return null
        px.sortBy { lum(it) }
        return px[px.size / 2] or 0xFF000000.toInt()
    }

    private fun compor(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun c(f: Int, b: Int) = (f * a + b * (1 - a)).roundToInt()
        return Color.rgb(c(Color.red(fg), Color.red(bg)), c(Color.green(fg), Color.green(bg)), c(Color.blue(fg), Color.blue(bg)))
    }

    private fun lum(c: Int): Double {
        fun l(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * l(Color.red(c)) + 0.7152 * l(Color.green(c)) + 0.0722 * l(Color.blue(c))
    }

    private fun contraste(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

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

    companion object {
        /** A escala declarada no app: os 5 papéis de [br.paulo.tvlauncher.Estilo.Papel] + os tamanhos de leitura de [br.paulo.tvlauncher.Estilo.Leitura]. */
        val ESCALA: Set<Float> by lazy {
            br.paulo.tvlauncher.Estilo.Papel.entries.map { it.sp }.toSet() +
                br.paulo.tvlauncher.Estilo.Leitura::class.java.declaredFields.filter { it.type == java.lang.Float.TYPE }
                    .map { it.isAccessible = true; it.getFloat(null) }
        }
    }
}

package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.AboutActivity
import br.paulo.tvlauncher.AjudaActivity
import br.paulo.tvlauncher.AlarmesActivity
import br.paulo.tvlauncher.AutomacoesActivity
import br.paulo.tvlauncher.BoasVindasActivity
import br.paulo.tvlauncher.BuscaActivity
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConhecerProActivity
import br.paulo.tvlauncher.DiagnosticoActivity
import br.paulo.tvlauncher.EntrarPeloQrActivity
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.ExtrasActivity
import br.paulo.tvlauncher.FilaAppsActivity
import br.paulo.tvlauncher.FilmesActivity
import br.paulo.tvlauncher.JogosActivity
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.PlacarActivity
import br.paulo.tvlauncher.PrevisaoActivity
import br.paulo.tvlauncher.RadioActivity
import br.paulo.tvlauncher.ReceberTvActivity
import br.paulo.tvlauncher.SeriesActivity
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.LayoutHome
import br.paulo.tvlauncher.home.SplashOverlay
import br.paulo.tvlauncher.player.salvar
import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.settings.CorSegura
import br.paulo.tvlauncher.weather.ClimaMet
import br.paulo.tvlauncher.weather.WeatherApiSource
import java.io.File
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
        "home-leve" to MainActivity::class.java,     // cruz com a barra Leve (sem cápsulas) e ícones Duotom
        "home-relogio" to MainActivity::class.java,  // cruz com a barra Relógio grande e ícones Símbolo
        "home-vidro" to MainActivity::class.java,    // cruz com a barra de Vidro (padrão por algumas horas na onda 3)
        "home-recua" to MainActivity::class.java,    // cruz com a barra Recua ("home" usa a Ilha, padrão desde 18/11/2026)
        "configuracoes" to ConfigLauncherActivity::class.java,
        // Configurações iguais ao painel (20/11/2026): abas com o índice "Nesta página" e as pílulas "Conversa com".
        "configuracoes-remoto" to ConfigLauncherActivity::class.java,
        "configuracoes-familia" to ConfigLauncherActivity::class.java,
        "sobre" to br.paulo.tvlauncher.AboutActivity::class.java,   // logo Sofaly, slogan e versão (18/11/2026)
        "filmes" to FilmesActivity::class.java,
        "series" to SeriesActivity::class.java,
        "ajuda" to AjudaActivity::class.java,
        "previsao" to PrevisaoActivity::class.java,
        "despertador" to AlarmesActivity::class.java,
        "jogos" to JogosActivity::class.java,
        "entrar-celular" to EntrarPeloQrActivity::class.java,
        "planos" to br.paulo.tvlauncher.ConhecerProActivity::class.java,
        "placar" to br.paulo.tvlauncher.PlacarActivity::class.java,   // elevação 2.1: os 7 pilares
        // Elevação 16.1: mais telas para o leitor de tela conferir (todo elemento com foco tem nome).
        "extras" to br.paulo.tvlauncher.ExtrasActivity::class.java,
        "automacoes" to br.paulo.tvlauncher.AutomacoesActivity::class.java,
        "busca" to br.paulo.tvlauncher.BuscaActivity::class.java,
        "diagnostico" to br.paulo.tvlauncher.DiagnosticoActivity::class.java,
        "radio" to br.paulo.tvlauncher.RadioActivity::class.java,
        // Elevação 19.7: TV nova em 2 min (o QR para receber e a fila dos apps).
        "mudar-tv" to br.paulo.tvlauncher.ReceberTvActivity::class.java,
        "fila-apps" to br.paulo.tvlauncher.FilaAppsActivity::class.java,
        // Elevação 16.3: as mesmas telas com o alto contraste ligado; todo texto tem que passar de 7:1 ("contraste").
        "home-ac" to MainActivity::class.java,
        "configuracoes-ac" to ConfigLauncherActivity::class.java,
        "placar-ac" to br.paulo.tvlauncher.PlacarActivity::class.java,
        "ajuda-ac" to AjudaActivity::class.java,
    )
    private val referencia = File("src/test/telas/referencia.json")
    private val saida = File("build/telas")

    data class Medida(val menorSp: Float, val cortes: Set<String>, val sobrepostos: Set<String>, val foco: Boolean,
                      val soCor: Set<String> = emptySet(), val contraste: Set<String> = emptySet(),
                      val semNome: Set<String> = emptySet()) {
        fun json(): JSONObject = JSONObject().put("menorSp", menorSp.toDouble()).put("foco", foco)
            .put("cortes", JSONArray(cortes.sorted())).put("sobrepostos", JSONArray(sobrepostos.sorted()))
            .put("soCor", JSONArray(soCor.sorted())).put("contraste", JSONArray(contraste.sorted()))
            .put("semNome", JSONArray(semNome.sorted()))
        companion object {
            private fun conjunto(o: JSONObject, k: String) = o.optJSONArray(k).let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
            fun de(o: JSONObject) = Medida(o.optDouble("menorSp").toFloat(), conjunto(o, "cortes"), conjunto(o, "sobrepostos"),
                o.optBoolean("foco"), conjunto(o, "soCor"), conjunto(o, "contraste"), conjunto(o, "semNome"))
        }
    }

    /**
     * Telas que buscam dados na rede depois de abrir: medidas sem rede (ver conferir). Todas as HOMEs também (19/11/2026):
     * no CI a HOME em grade pegou o tempo de verdade ("nublado, chuva 75% agora" em árabe) e a medida dependeu do clima do dia.
     */
    private val TELAS_SEM_REDE = setOf("previsao")

    @Config(qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
    @Test fun ingles() = conferir("en")

    @Config(qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
    @Test fun portugues() = conferir("pt")

    /** Elevação 16.8: árabe espelhado de verdade (as telas de View e as desenhadas à mão). */
    @Config(qualifiers = "ar-ldrtl-w960dp-h540dp-land-television-xhdpi")
    @Test fun arabe() = conferir("ar")

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
            // A abertura com a logo (SplashOverlay) cobriria a HOME na foto: o relógio do Robolectric começa "logo após o boot".
            br.paulo.tvlauncher.home.SplashOverlay.setEnabled(app, false)
            br.paulo.tvlauncher.settings.AltoContraste.definir(app, nome.endsWith("-ac"))
            // "home" é a cruz (a única tela inicial; a antiga, em fileira/grade, saiu em 21/11/2026).
            br.paulo.tvlauncher.home.LayoutHome.definirBarraCruz(app, when (nome) {
                "home-leve" -> br.paulo.tvlauncher.home.LayoutHome.BarraCruz.LEVE
                "home-relogio" -> br.paulo.tvlauncher.home.LayoutHome.BarraCruz.RELOGIO
                "home-vidro" -> br.paulo.tvlauncher.home.LayoutHome.BarraCruz.VIDRO
                "home-recua" -> br.paulo.tvlauncher.home.LayoutHome.BarraCruz.RECUA
                else -> br.paulo.tvlauncher.home.LayoutHome.BarraCruz.ILHA
            })
            br.paulo.tvlauncher.home.LayoutHome.definirIconesCruz(app, when (nome) {
                "home-leve" -> br.paulo.tvlauncher.home.IconeCruz.Estilo.DUOTOM
                "home-relogio" -> br.paulo.tvlauncher.home.IconeCruz.Estilo.SIMBOLO
                "home-vidro" -> br.paulo.tvlauncher.home.IconeCruz.Estilo.OBJETO
                else -> br.paulo.tvlauncher.home.IconeCruz.Estilo.LINHA   // o padrão desde 18/11/2026
            })
            // A Previsão busca o tempo numa thread: com rede, às vezes chega antes da foto (outro desenho, outra letra
            // menor) e às vezes não. Sem rede (proxy que recusa) e sem a resposta guardada na memória, a tela é sempre a mesma: a medida não depende da sorte.
            val semRede = nome in TELAS_SEM_REDE || cls == MainActivity::class.java
            val proxyAntes = listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").associateWith { System.getProperty(it) }
            if (semRede) { br.paulo.tvlauncher.weather.ClimaMet.esquecerCache(); br.paulo.tvlauncher.weather.WeatherApiSource.esquecerCache()
                System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "9")
                System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "9") }
            val m = try { medir(cls, "$idioma-$nome", ABA_DA_TELA[nome]) } catch (e: Throwable) {
                problemas += "$chave: a tela não abriu (${e.javaClass.simpleName}: ${e.message?.take(160)})"; continue
            } finally {
                if (semRede) for ((k, v) in proxyAntes) if (v == null) System.clearProperty(k) else System.setProperty(k, v)
            }
            agora.put(chave, m.json())
            val antes = ref.optJSONObject(chave)?.let { Medida.de(it) } ?: continue
            if (m.menorSp < antes.menorSp - 0.05f) problemas += "$chave: a menor letra encolheu de ${antes.menorSp}sp para ${m.menorSp}sp"
            for (c in m.cortes - antes.cortes) problemas += "$chave: texto cortado: \"$c\""
            for (s in m.sobrepostos - antes.sobrepostos) problemas += "$chave: texto por cima de outro: $s"
            if (antes.foco && !m.foco) problemas += "$chave: nada tem o foco (o controle não anda)"
            for (c in m.soCor - antes.soCor) problemas += "$chave: estado só pela cor (16.4): $c"
            for (c in m.semNome - antes.semNome) problemas += "$chave: elemento com foco sem nome para o leitor de tela (16.1): $c"
            for (c in m.contraste - antes.contraste) problemas += "$chave: texto abaixo de 7:1 no alto contraste (16.3): $c"
        }
        br.paulo.tvlauncher.settings.AltoContraste.definir(RuntimeEnvironment.getApplication(), false)
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

    /** Telas das Configurações abertas numa aba que não é a primeira (EXTRA_ABA). */
    private val ABA_DA_TELA = mapOf("configuracoes-remoto" to ConfigLauncherActivity.ABA_REMOTO, "configuracoes-familia" to 2)

    private fun medir(cls: Class<out Activity>, arquivo: String, aba: Int? = null): Medida {
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), cls).apply { if (aba != null) putExtra(ConfigLauncherActivity.EXTRA_ABA, aba) }
        val a = Robolectric.buildActivity(cls, intent).setup().get()
        // A HOME lê apps e player fora da tela e desenha depois: espera essas voltas (a cruz só aparece com elas).
        if (cls == MainActivity::class.java) repeat(10) { Thread.sleep(100); org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val raiz: View = a.window.decorView
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Daltonismo.salvar(foto, File(saida, "daltonismo"), arquivo)   // 16.4: as fotos como quem não vê cor

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
        val soCor = Daltonismo.estadosSoPelaCor(raiz)
        val contraste = if (!arquivo.endsWith("-ac")) emptySet() else textos.mapNotNull { (t, _) ->
            // Fundo de comparação: o grafite da tela, ou o foco branco (texto escuro de linha focada). Vale o melhor.
            val cor = t.currentTextColor
            val c = maxOf(br.paulo.tvlauncher.settings.CorSegura.contraste(br.paulo.tvlauncher.settings.CorSegura.compor(cor, br.paulo.tvlauncher.Estilo.FUNDO), br.paulo.tvlauncher.Estilo.FUNDO),
                br.paulo.tvlauncher.settings.CorSegura.contraste(br.paulo.tvlauncher.settings.CorSegura.compor(cor, br.paulo.tvlauncher.Estilo.FOCO), br.paulo.tvlauncher.Estilo.FOCO))
            if (c < br.paulo.tvlauncher.settings.AltoContraste.MINIMO) "${rotulo(t)} (${"%.1f".format(c)}:1)" else null
        }.toSet()
        val semNome = LeitorTela.semNome(raiz)
        a.finish()
        return Medida(menor, cortes, sobrepostos, raiz.findFocus() != null, soCor, contraste, semNome)
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

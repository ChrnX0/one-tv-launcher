package br.paulo.tvlauncher.telas

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import br.paulo.tvlauncher.FichaFilme
import br.paulo.tvlauncher.Filme
import br.paulo.tvlauncher.FilmesActivity
import br.paulo.tvlauncher.FonteTv
import br.paulo.tvlauncher.InfoExterna
import br.paulo.tvlauncher.PlayerArmazem
import br.paulo.tvlauncher.Posicao
import br.paulo.tvlauncher.QrCodigo
import br.paulo.tvlauncher.ServicosContas
import br.paulo.tvlauncher.ServicosFilmes
import br.paulo.tvlauncher.ServicosFilmes.Servico
import br.paulo.tvlauncher.ServicosFilmesActivity
import br.paulo.tvlauncher.ServicosSync
import br.paulo.tvlauncher.SincronizarActivity
import br.paulo.tvlauncher.SincronizarFilmes
import br.paulo.tvlauncher.Vod
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.time.Duration
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Fotos da Simkl dentro do launcher para mostrar à própria Simkl (17/11/2026). NÃO é teste de regressão: só desenha,
 * com dados de exemplo inventados (conta "sofaly-demo", filmes fictícios, pôsteres gerados aqui), as telas reais:
 * lista de serviços, código de login, conta Simkl conectada, Continuar na grade de Filmes e a ficha com a nota.
 * Só roda com -Ptelas -PsimklDemo; as fotos saem em app/build/telas-simkl/. Rede bloqueada durante o desenho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class SimklDemoTelaTest {
    private val saida = File("build/telas-simkl")

    // ---------------------------------------------------------------- Keystore falso (o Robolectric não tem AndroidKeyStore)
    class KsFalso : KeyStoreSpi() {
        companion object { val chaves = HashMap<String, Key>() }
        override fun engineGetKey(alias: String, password: CharArray?): Key? = chaves[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { chaves[alias] = key }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
        override fun engineDeleteEntry(alias: String) { chaves.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(chaves.keys)
        override fun engineContainsAlias(alias: String) = alias in chaves
        override fun engineSize() = chaves.size
        override fun engineIsKeyEntry(alias: String) = alias in chaves
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) {}
    }
    class KgFalso : KeyGeneratorSpi() {
        private var alias = "x"
        override fun engineInit(random: SecureRandom?) {}
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) { (params as? KeyGenParameterSpec)?.let { alias = it.keystoreAlias } }
        override fun engineInit(keysize: Int, random: SecureRandom?) {}
        override fun engineGenerateKey(): SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES").also { KsFalso.chaves[alias] = it }
    }
    class ProvFalso : Provider("AndroidKeyStore", 1.0, "falso para foto") {
        init { put("KeyStore.AndroidKeyStore", KsFalso::class.java.name); put("KeyGenerator.AES", KgFalso::class.java.name) }
    }

    // ---------------------------------------------------------------- dados de exemplo
    private data class Exemplo(val nome: String, val ano: Int, val genero: String, val c1: Int, val c2: Int, val min: Int, val parou: Int,
                               val trakt: Float, val simkl: Float)
    private val exemplos = listOf(
        Exemplo("Glass Harbor", 2024, "Drama", 0xFF1F3B5C.toInt(), 0xFF0B1424.toInt(), 128, 47, 7.6f, 8.1f),
        Exemplo("The Quiet Orbit", 2023, "Science Fiction", 0xFF3A1E5C.toInt(), 0xFF0E0A1F.toInt(), 114, 82, 7.2f, 7.8f),
        Exemplo("Midnight Bakery", 2025, "Comedy", 0xFF6A3A12.toInt(), 0xFF1E0F05.toInt(), 97, 31, 6.9f, 7.4f),
        Exemplo("Northbound", 2022, "Adventure", 0xFF1E5C47.toInt(), 0xFF07170F.toInt(), 121, 64, 7.4f, 7.9f),
        Exemplo("Paper Lanterns", 2024, "Romance", 0xFF5C1E2A.toInt(), 0xFF1A070B.toInt(), 105, 12, 7.0f, 7.6f),
        Exemplo("Salt & Cedar", 2021, "Mystery", 0xFF2E4A1E.toInt(), 0xFF0C1407.toInt(), 133, 95, 7.3f, 8.0f),
        Exemplo("Echo Valley", 2023, "Thriller", 0xFF1E2E5C.toInt(), 0xFF070B1A.toInt(), 110, 58, 6.8f, 7.3f),
        Exemplo("The Lantern Keeper", 2025, "Fantasy", 0xFF5C4A1E.toInt(), 0xFF1A1407.toInt(), 126, 20, 7.5f, 8.2f),
        Exemplo("Blue Hour", 2022, "Drama", 0xFF1E4F5C.toInt(), 0xFF07151A.toInt(), 99, 70, 7.1f, 7.7f),
        Exemplo("Static Garden", 2024, "Animation", 0xFF4F1E5C.toInt(), 0xFF14071A.toInt(), 92, 40, 7.7f, 8.3f),
        // Já assistidos (vieram do histórico): fora do Continuar, contam em Watched.
        Exemplo("Copper Sky", 2020, "Western", 0xFF5C331E.toInt(), 0xFF1A0E07.toInt(), 118, 118, 7.0f, 7.5f),
        Exemplo("Winter Signal", 2021, "Thriller", 0xFF1E3F5C.toInt(), 0xFF07121A.toInt(), 104, 104, 7.2f, 7.9f),
    )
    private val fonte = FonteTv("demo", "Demo", FonteTv.TIPO_XTREAM, "https://demo.invalid", "demo", "demo")
    private fun capa(i: Int) = "https://demo.invalid/poster/$i.png"

    private fun poster(e: Exemplo): ByteArray {
        val w = 400; val h = 600
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), e.c1, e.c2, Shader.TileMode.CLAMP) })
        // um "sol" e um horizonte, só para não parecer cartão vazio
        c.drawCircle(w * 0.68f, h * 0.36f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 230, 200) })
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(60, 0, 0, 0) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        var y = h * 0.66f
        for (linha in e.nome.uppercase().split(' ')) { c.drawText(linha, 28f, y, p); y += 40f }
        val out = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); return out.toByteArray()
    }

    private fun sha1(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun preparar() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(ProvFalso())
        // Nada sai para a rede: o proxy aponta para uma porta fechada (as telas tratam a falha como "sem rede").
        for (p in listOf("http", "https")) { System.setProperty("$p.proxyHost", "127.0.0.1"); System.setProperty("$p.proxyPort", "9") }
        System.setProperty("http.nonProxyHosts", "")
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        saida.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        // Conta Simkl conectada (nome de exemplo) com as quatro sincronizações ligadas; última há 12 min.
        ServicosContas.definir(app, Servico.SIMKL, ServicosFilmes.Conta("token-de-exemplo", usuario = "sofaly-demo"))
        ServicosSync.definirOpcoes(app, Servico.SIMKL, SincronizarFilmes.Opcoes(interesses = true, assistidos = true, continuar = true, contar = true))
        File(PlayerArmazem.pasta(app), "sincronizar.json").writeText(JSONObject().put("ultima_simkl", System.currentTimeMillis() - 12 * 60_000L).toString())
        // Catálogo de exemplo, pôsteres no cache de disco, posições (Continuar) e notas guardadas.
        PlayerArmazem.substituirContas(app, listOf(fonte))
        val filmes = exemplos.mapIndexed { i, e -> Filme("${i + 1}", fonte.id, e.nome, "Movies", capa(i + 1), null, e.ano, tmdb = 900_000 + i, adicionado = 1_700_000_000L + i) }
        File(PlayerArmazem.pasta(app), "${fonte.id}.filmes.json").writeText(Vod.filmesParaJson(filmes))
        val logos = File(app.cacheDir, "logos").apply { mkdirs() }
        exemplos.forEachIndexed { i, e -> File(logos, sha1(capa(i + 1))).writeBytes(poster(e)) }
        val agora = System.currentTimeMillis()
        PlayerArmazem.definirPosicoes(app, exemplos.mapIndexed { i, e ->
            filmes[i].chave to Posicao(e.parou * 60_000L, e.min * 60_000L, agora - i * 3_600_000L)
        }.toMap())
        // Interests ⇄ Plan to watch: 4 títulos que vieram da lista da Simkl.
        PlayerArmazem.definirInteresses(app, series = false, setOf(filmes[1].chave, filmes[3].chave, filmes[7].chave, filmes[9].chave))
        val ext = JSONObject()
        exemplos.forEachIndexed { i, e ->
            ext.put(filmes[i].chave, InfoExterna.guardadoParaJson(InfoExterna.Guardado(tmdb = 900_000 + i, traktNota = e.trakt, traktEm = agora,
                simkl = 2_000_000 + i, simklNota = e.simkl, simklEm = agora)))
        }
        File(PlayerArmazem.pasta(app), "externo.json").writeText(ext.toString())
    }

    private fun esperar(ms: Long = 2500) {
        val fim = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fim) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun foto(a: Activity, nome: String) {
        val raiz = a.window.decorView
        val b = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(b))
        File(saida, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun chamar(o: Any, nome: String, vararg args: Any?): Any? {
        val m = o.javaClass.declaredMethods.first { it.name == nome && it.parameterCount == args.size }
        m.isAccessible = true; return m.invoke(o, *args)
    }
    private fun campo(o: Any, nome: String): Any? = o.javaClass.getDeclaredField(nome).apply { isAccessible = true }.get(o)
    private fun definirCampo(o: Any, nome: String, v: Any?) = o.javaClass.getDeclaredField(nome).apply { isAccessible = true }.set(o, v)

    @Test fun fotos() {
        assumeTrue(System.getProperty("simkl.demo") == "true")
        preparar()

        // 1. Settings › Movie and series services: Simkl conectada.
        val sv = Robolectric.buildActivity(ServicosFilmesActivity::class.java).setup().get()
        esperar()
        foto(sv, "2-services-connected")

        // 2. Login por código (device code): a tela real, com um código de exemplo no lugar do que a Simkl manda.
        ServicosContas.definir(sv, Servico.SIMKL, null)
        chamar(sv, "mostrarCodigo", Servico.SIMKL)
        esperar(3000)   // o pedido do código falha (rede bloqueada); a sessão de exemplo entra em seguida
        val codigo = ServicosFilmes.Codigo("K7X4P", "https://simkl.com/pin", "exemplo", 900, 5)
        @Suppress("UNCHECKED_CAST")
        val sessoes = ServicosContas::class.java.getDeclaredField("sessoes").apply { isAccessible = true }.get(ServicosContas)
            as MutableMap<Servico, ServicosContas.Sessao>
        sessoes[Servico.SIMKL] = ServicosContas.Sessao(Servico.SIMKL, codigo, System.currentTimeMillis() + 900_000)
        chamar(sv, "atualizarCodigo")
        val qr = campo(sv, "cQr") as ImageView
        qr.setImageBitmap(QrCodigo.bitmap(codigo.urlQr, 600)); qr.visibility = android.view.View.VISIBLE
        esperar(500)
        foto(sv, "1-sign-in-code")
        sessoes.remove(Servico.SIMKL)
        ServicosContas.definir(sv, Servico.SIMKL, ServicosFilmes.Conta("token-de-exemplo", usuario = "sofaly-demo"))

        // 3. A tela da conta Simkl: o que sincroniza + "Data from Simkl (simkl.com)".
        val sync = Robolectric.buildActivity(SincronizarActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), SincronizarActivity::class.java).putExtra("servico", "simkl")).setup().get()
        esperar()
        foto(sync, "3-simkl-account")
        // O fim da mesma tela: "Sign out of Simkl" e a atribuição "Data from Simkl (simkl.com)".
        fun rolar(v: android.view.View) { if (v is android.widget.ScrollView) v.fullScroll(android.view.View.FOCUS_DOWN) else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) rolar(v.getChildAt(i)) }
        rolar(sync.window.decorView)
        esperar(500)
        foto(sync, "3b-simkl-account-end")

        // 4. Filmes › Continue: barras de onde parou (vem do "Continue ⇄ Where you stopped") e a nota no foco.
        val fm = Robolectric.buildActivity(FilmesActivity::class.java).setup().get()
        esperar(4000)
        chamar(fm, "escolherCat", "*continuar", true)
        esperar(3000)
        foto(fm, "4-movies-continue")

        // 5. Ficha do filme: "Continue 47:00", barra, "Trakt 76% · Simkl 8.1".
        @Suppress("UNCHECKED_CAST")
        val todos = campo(fm, "todos") as List<Filme>
        val f = todos.first()
        val e = exemplos.first()
        val info = FichaFilme(sinopse = "A ferry captain returns to the harbor town she left twenty years ago and finds the lighthouse " +
            "keeper's diary that changes everything she believed about her father. (Sample title and synopsis for this demo.)",
            elenco = "Sample Cast One, Sample Cast Two, Sample Cast Three", diretor = "Sample Director", genero = e.genero, duracaoMin = e.min)
        val g = InfoExterna.Guardado(tmdb = 900_000, traktNota = e.trakt, traktEm = System.currentTimeMillis(), simkl = 2_000_000,
            simklNota = e.simkl, simklEm = System.currentTimeMillis())
        definirCampo(fm, "ficha", f); definirCampo(fm, "fichaInfo", info); definirCampo(fm, "externo", f to g)
        chamar(fm, "desenharFicha", f, info, false)
        esperar(2500)
        foto(fm, "5-movie-details-rating")
    }

    /**
     * PROPOSTA (branch do PR #191): quem NÃO conectou a Simkl vê só a nota do Trakt e, na ficha, o convite
     * "Simkl rating · Connect your account"; OK abre o código de login. Roda com -PsimklProposta.
     */
    @Test fun proposta() {
        assumeTrue(System.getProperty("simkl.proposta") == "true")
        preparar()
        val app = RuntimeEnvironment.getApplication()
        ServicosContas.definir(app, Servico.SIMKL, null)
        val pasta = File("build/telas-simkl-proposta").apply { mkdirs() }

        val fm = Robolectric.buildActivity(FilmesActivity::class.java).setup().get()
        esperar(4000)
        @Suppress("UNCHECKED_CAST")
        val todos = campo(fm, "todos") as List<Filme>
        val f = todos.first(); val e = exemplos.first()
        val info = FichaFilme(sinopse = "A ferry captain returns to the harbor town she left twenty years ago and finds the lighthouse " +
            "keeper's diary that changes everything she believed about her father. (Sample title and synopsis for this demo.)",
            elenco = "Sample Cast One, Sample Cast Two, Sample Cast Three", diretor = "Sample Director", genero = e.genero, duracaoMin = e.min)
        // Sem conta Simkl: nenhuma nota da Simkl guardada, só a do Trakt.
        val g = InfoExterna.Guardado(tmdb = 900_000, traktNota = e.trakt, traktEm = System.currentTimeMillis())
        definirCampo(fm, "ficha", f); definirCampo(fm, "fichaInfo", info); definirCampo(fm, "externo", f to g)
        chamar(fm, "desenharFicha", f, info, false)
        esperar(1500)
        fm.window.decorView.findViewWithTag<android.view.View>("simkl")?.requestFocus()
        esperar(500)
        val raiz = fm.window.decorView
        val b = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888); raiz.draw(Canvas(b))
        File(pasta, "P1-movie-details-connect-simkl.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package br.paulo.tvlauncher.settings

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import br.paulo.tvlauncher.Fundo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fonte do launcher (21/11/2026, item 1 do plano para passar o Projectivy: "Ajuste fino").
 *
 * Porquê: a letra é metade da cara de uma tela; o Projectivy deixa trocar, a Sofaly só tinha a Nunito.
 * Para quê: cada casa escolhe a letra que lê melhor. A Atkinson Hyperlegible foi desenhada para baixa visão (o nosso
 * público inclui idosos) e a Lexend para leitura rápida; as outras são gosto.
 * Como: uma lista CURADA de fontes do Google Fonts (todas OFL, legíveis a 3 m). A Nunito vem no APK; as outras baixam
 * do repositório oficial google/fonts na primeira escolha (100 KB a 900 KB) e ficam em filesDir/fonts. Funciona sem os
 * serviços do Google (Fire TV). Quem usa a fonte: [Fontes.nunito] (125 chamadas no código) devolve a escolhida no peso
 * pedido, e [aplicarNaTela] troca a letra dos textos que só herdam a fonte do tema. Com a Nunito nada disso roda.
 * Liga com: Tamanho do texto (TamanhoTexto), Visuais prontos e o QR do visual (VisualCodigo leva a chave junto).
 */
object FontChoice {
    private const val TAG = "TvLauncher.Fonte"
    const val KEY = "font_family"
    const val DEFAULT = "nunito"

    /** id, nome para mostrar, caminho no google/fonts (null = embutida), true = serifa. */
    class Font(val id: String, val name: String, val path: String?)

    val FONTS = listOf(
        Font(DEFAULT, "Nunito", null),
        Font("atkinson", "Atkinson Hyperlegible", "ofl/atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext%5Bwght%5D.ttf"),
        Font("lexend", "Lexend", "ofl/lexend/Lexend%5Bwght%5D.ttf"),
        Font("inter", "Inter", "ofl/inter/Inter%5Bopsz,wght%5D.ttf"),
        Font("roboto", "Roboto", "ofl/roboto/Roboto%5Bwdth,wght%5D.ttf"),
        Font("figtree", "Figtree", "ofl/figtree/Figtree%5Bwght%5D.ttf"),
        Font("outfit", "Outfit", "ofl/outfit/Outfit%5Bwght%5D.ttf"),
        Font("dmsans", "DM Sans", "ofl/dmsans/DMSans%5Bopsz,wght%5D.ttf"),
        Font("jakarta", "Plus Jakarta Sans", "ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf"),
        Font("manrope", "Manrope", "ofl/manrope/Manrope%5Bwght%5D.ttf"),
        Font("rubik", "Rubik", "ofl/rubik/Rubik%5Bwght%5D.ttf"),
        Font("quicksand", "Quicksand", "ofl/quicksand/Quicksand%5Bwght%5D.ttf"),
        Font("comfortaa", "Comfortaa", "ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"),
        Font("fredoka", "Fredoka", "ofl/fredoka/Fredoka%5Bwdth,wght%5D.ttf"),
        Font("spacegrotesk", "Space Grotesk", "ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"),
        Font("montserrat", "Montserrat", "ofl/montserrat/Montserrat%5Bwght%5D.ttf"),
        Font("lora", "Lora", "ofl/lora/Lora%5Bwght%5D.ttf"),
    )
    private const val BASE = "https://raw.githubusercontent.com/google/fonts/main/"
    private const val MAX_BYTES = 2_000_000

    fun byId(id: String) = FONTS.firstOrNull { it.id == id } ?: FONTS[0]
    fun currentId(ctx: Context) = byId(Extras.texto(ctx, KEY, DEFAULT)).id
    fun current(ctx: Context) = byId(currentId(ctx))

    private fun file(ctx: Context, f: Font) = File(File(ctx.filesDir, "fonts").apply { mkdirs() }, f.id + ".ttf")

    /** A fonte escolhida está pronta para usar (embutida, ou baixada). */
    fun ready(ctx: Context, f: Font = current(ctx)) = f.path == null || file(ctx, f).length() > 0

    /** Cabeçalho de TTF/OTF: 00 01 00 00, "true", "OTTO". Puro (FontChoiceTest): nunca grava uma página de erro como fonte. */
    fun looksLikeFont(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val h = (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
        return h == 0x00010000 || h == 0x74727565 || h == 0x4F54544F
    }

    /**
     * Escolhe [id]. Se precisar baixar, baixa fora da tela e chama [pronto] (na thread da tela) com true/false.
     * A escolha só grava depois que o arquivo existe: sem internet, a TV continua com a fonte de antes.
     */
    fun choose(ctx: Context, id: String, pronto: (Boolean) -> Unit) {
        val f = byId(id)
        val app = ctx.applicationContext
        if (ready(app, f)) { gravar(app, f); pronto(true); return }
        Fundo.rodar("fonte-${f.id}") {
            val ok = runCatching { baixar(app, f) }.onFailure { Log.w(TAG, "baixar ${f.id}: $it") }.getOrDefault(false)
            if (ok) gravar(app, f)
            android.os.Handler(android.os.Looper.getMainLooper()).post { pronto(ok) }
        }
    }

    private fun gravar(ctx: Context, f: Font) {
        Extras.definirTexto(ctx, KEY, f.id)
        Fontes.limpar()
        cache.clear()
    }

    private fun baixar(ctx: Context, f: Font): Boolean {
        val c = URL(BASE + f.path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) return false
            val bytes = c.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES || !looksLikeFont(bytes)) return false
            val destino = file(ctx, f)
            val tmp = File(destino.path + ".tmp")
            tmp.writeBytes(bytes)
            return tmp.renameTo(destino)
        } finally { c.disconnect() }
    }

    private val cache = HashMap<String, Typeface>()

    /**
     * A fonte escolhida no peso pedido (escala da Nunito: até 450 = texto, até 550 = médio, acima = destaque), ou null
     * se for a Nunito ou se o arquivo sumiu (aí quem chamou usa a Nunito).
     */
    fun typeface(ctx: Context, peso: Int): Typeface? {
        val f = current(ctx)
        if (f.path == null) return null
        val arq = file(ctx, f)
        if (!arq.exists()) return null
        val w = weight(peso)
        return synchronized(cache) {
            cache.getOrPut("${f.id}|$w") {
                runCatching { Typeface.Builder(arq).setFontVariationSettings("'wght' $w").setWeight(w).build() }
                    .getOrDefault(Typeface.DEFAULT)
            }
        }
    }

    /** Mesmo degrau da Nunito (Fontes.nunito): as fontes do Google são mais finas que a Nunito, então sobem igual. Puro. */
    fun weight(peso: Int) = when { peso <= 450 -> 500; peso <= 550 -> 600; else -> 800 }

    /**
     * Troca a letra dos textos que só herdaram a fonte do tema (não chamaram setTypeface). Roda a cada desenho novo da
     * tela, só quando há fonte escolhida; cada TextView é marcada para não ser trocada duas vezes.
     */
    fun aplicarNaTela(a: Activity) {
        if (current(a).path == null) return
        val raiz = a.window?.decorView ?: return
        raiz.viewTreeObserver.addOnGlobalLayoutListener { trocar(a, raiz) }
    }

    private val MARCA = "font_choice".hashCode()

    private fun trocar(ctx: Context, v: View) {
        if (v is TextView && v.getTag(MARCA) == null) {
            v.setTag(MARCA, true)
            val atual = v.typeface
            // Só troca quem está na Nunito (padrão do tema) ou sem fonte: Fraunces do protetor, ícones etc. ficam.
            if (atual == null || atual == Typeface.DEFAULT || Fontes.ehNunito(atual)) {
                val peso = if (atual?.isBold == true) 800 else (if (android.os.Build.VERSION.SDK_INT >= 28) atual?.weight ?: 400 else 400)
                typeface(ctx, peso)?.let { v.typeface = it }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) trocar(ctx, v.getChildAt(i))
    }
}

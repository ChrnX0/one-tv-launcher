package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Typeface

/**
 * Os visuais da tela inicial. Escolhe-se no menu da bolinha do perfil ("Aparência"); fica salvo.
 *  - ESCURO: grafite azulado com brilhos bem suaves (código) + o céu de agora por cima — sol, lua e nuvens
 *    caminhando na tela, a partir da posição real do sol e do clima (era o antigo tema "Céu ao vivo"; hoje é
 *    parte fixa do Escuro, não existe mais como opção separada).
 *  - GALERIA: uma pintura de domínio público por dia (acervo aberto do The Met), com etiqueta de museu.
 * Fonte: a Manrope em todos (res/font, licença SIL Open Font); o tema muda só o fundo.
 */
enum class Tema(private val rotulo: Int) {
    ESCURO(R.string.tema_escuro), GALERIA(R.string.tema_galeria);

    /** Nome do tema no idioma da TV. */
    val nome: String get() = txt(rotulo)

    companion object {
        private const val CHAVE = "tema"

        fun atual(ctx: Context): Tema = try {
            valueOf(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(CHAVE, ESCURO.name) ?: ESCURO.name)
        } catch (e: IllegalArgumentException) { ESCURO }

        /** Passa para o próximo tema da lista (dá a volta). */
        fun proximo(ctx: Context): Tema {
            val novo = values()[(atual(ctx).ordinal + 1) % values().size]
            ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(CHAVE, novo.name).apply()
            return novo
        }
    }
}

/** As fontes de cada tema, carregadas uma vez. "display" = hora e temperatura; "ui" = o resto. */
object Fontes {
    private val cache = HashMap<String, Typeface>()

    private fun carregar(ctx: Context, arquivo: String, variacao: String? = null): Typeface =
        cache.getOrPut("$arquivo|$variacao") {
            try {
                Typeface.Builder(ctx.assets, "fonts/$arquivo").apply { if (variacao != null) setFontVariationSettings(variacao) }.build()
            } catch (e: Exception) {
                Typeface.DEFAULT   // arquivo faltando: a tela continua funcionando com a fonte do sistema
            }
        }

    // Sistema de design (10/11/2026, dono: "conciso e coerente"): os dois temas usam a MESMA fonte, a Manrope. O tema muda
    // o fundo, não a letra. Antes: Roboto Light no Escuro, Cormorant/Figtree na Galeria. O parâmetro [tema] ficou para
    // não mexer em quem já chama; não muda mais nada.
    @Suppress("UNUSED_PARAMETER") fun display(ctx: Context, tema: Tema): Typeface = manrope(ctx, 200)
    @Suppress("UNUSED_PARAMETER") fun ui(ctx: Context, tema: Tema): Typeface = manrope(ctx, 400)
    @Suppress("UNUSED_PARAMETER") fun uiMedia(ctx: Context, tema: Tema): Typeface = manrope(ctx, 500)

    /**
     * Manrope (sem serifa geométrica, Google Fonts, licença OFL) em pesos fixos: 200, 400, 500 ou 600. Usada no protetor
     * de tela (ProtetorGaleria). Arquivos estáticos (um por peso) porque o Google só entrega a versão variável em woff2,
     * que o Android não lê.
     */
    fun manrope(ctx: Context, peso: Int): Typeface = cache.getOrPut("manrope|$peso") {
        // Os arquivos moram em res/font (não em assets) para o tema do app usar a mesma família como fonte padrão.
        val id = when {
            peso <= 300 -> R.font.manrope_200
            peso <= 450 -> R.font.manrope_400
            peso <= 550 -> R.font.manrope_500
            else -> R.font.manrope_600
        }
        try { ctx.resources.getFont(id) } catch (e: Exception) { Typeface.DEFAULT }
    }

    /** O mesmo, sem precisar de um Context à mão (usa o do app). Serve nos lugares que só tinham Typeface.create(...). */
    fun manrope(peso: Int): Typeface = manrope(LauncherApp.instancia, peso)

    /** Fraunces itálica (serifa "de museu", OFL): o nome da obra no protetor. */
    fun fraunces(ctx: Context): Typeface = carregar(ctx, "fraunces_italic.ttf")
}

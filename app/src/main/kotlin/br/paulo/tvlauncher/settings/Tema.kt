package br.paulo.tvlauncher.settings

import android.content.Context
import android.graphics.Typeface
import br.paulo.tvlauncher.LauncherApp
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.FundoMeuDados
import br.paulo.tvlauncher.screensaver.WallpaperPlugins
import br.paulo.tvlauncher.txt

/**
 * Os visuais da tela inicial. Escolhe-se no menu da bolinha do perfil ("Aparência"); fica salvo.
 *  - ESCURO: grafite azulado com brilhos bem suaves (código) + o céu de agora por cima — sol, lua e nuvens
 *    caminhando na tela, a partir da posição real do sol e do clima (era o antigo tema "Céu ao vivo"; hoje é
 *    parte fixa do Escuro, não existe mais como opção separada).
 *  - GALERIA: uma pintura de domínio público por dia (acervo aberto do The Met), com etiqueta de museu.
 *  - CEU ("Céu de verdade", 17/11/2026): o mapa da região com a cidade e as nuvens de agora (CeuDeVerdade.kt).
 * Fonte: a Nunito em todos (visual Cinema+) (res/font, licença SIL Open Font); o tema muda só o fundo.
 */
enum class Tema(private val rotulo: Int) {
    ESCURO(R.string.tema_escuro), GALERIA(R.string.tema_galeria), CEU(R.string.tema_ceu),
    /** "Meu fundo" (degrau 1, 15/11/2026): foto, GIF ou vídeo da pessoa (FundoMeu.kt). Só entra na volta com arquivo escolhido. */
    MEU(R.string.tema_meu),
    /** "Plugin de fundo" (18/11/2026): imagens/vídeos de um plugin de fundo (WallpaperPluginView). Só entra com um escolhido. */
    PLUGIN(R.string.wp_tema);

    /** Nome do tema no idioma da TV. */
    val nome: String get() = txt(rotulo)

    companion object {
        private const val CHAVE = "tema"

        fun atual(ctx: Context): Tema = try {
            valueOf(ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getString(CHAVE, ESCURO.name) ?: ESCURO.name)
                // "Meu fundo" sem arquivo (apagado do pendrive, por exemplo) volta ao Escuro em vez de tela preta.
                .let { if (it == MEU && FundoMeuDados.arquivo(ctx) == null) ESCURO else it }
                // Plugin de fundo sem plugin escolhido (desinstalado, desligado): Escuro. O OK (certificado) a view confere.
                .let { if (it == PLUGIN && WallpaperPlugins.escolhidoId(ctx) == null) ESCURO else it }
        } catch (e: IllegalArgumentException) { ESCURO }

        /** Grava o tema escolhido (painel web, aba Configurações). */
        fun definir(ctx: Context, t: Tema) {
            ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putString(CHAVE, t.name).apply()
        }

        /** Passa para o próximo tema da lista (dá a volta). */
        fun proximo(ctx: Context): Tema {
            val opcoes = values().filter { (it != MEU || FundoMeuDados.arquivo(ctx) != null) && (it != PLUGIN || WallpaperPlugins.escolhidoId(ctx) != null) }
            val novo = opcoes[(opcoes.indexOf(atual(ctx)) + 1).coerceAtLeast(0) % opcoes.size]
            ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putString(CHAVE, novo.name).apply()
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

    // Sistema de design: os temas usam a MESMA fonte; o tema muda o fundo, não a letra. Visual Cinema+ (17/11/2026, dono
    // aprovou): Nunito, a mesma do painel web no celular e no PC, então TV, celular e PC têm a mesma cara. Títulos pesados
    // (800) no lugar da Manrope fina. O parâmetro [tema] ficou para não mexer em quem já chama; não muda mais nada.
    @Suppress("UNUSED_PARAMETER") fun display(ctx: Context, tema: Tema): Typeface = nunito(ctx, 800)
    @Suppress("UNUSED_PARAMETER") fun ui(ctx: Context, tema: Tema): Typeface = nunito(ctx, 400)
    @Suppress("UNUSED_PARAMETER") fun uiMedia(ctx: Context, tema: Tema): Typeface = nunito(ctx, 500)

    /**
     * Nunito (sem serifa arredondada, licença OFL) em 3 pesos fixos. O pedido segue a escala antiga (200 a 600) e cai um
     * degrau acima, porque a Nunito do Cinema+ é mais encorpada: texto comum (até 450) = 600, médio (até 550) = 700,
     * destaque = 800. Arquivos estáticos (um por peso) tirados da variável oficial: o Android 9+ só lê variável por XML.
     */
    fun nunito(ctx: Context, peso: Int): Typeface = FontChoice.typeface(ctx, peso) ?: nunitoEmbutida(ctx, peso)

    /** Esquece as fontes carregadas (a fonte escolhida mudou: FontChoice). As telas abertas trocam ao recriar. */
    fun limpar() = synchronized(cache) { cache.keys.removeAll { it.startsWith("nunito|") } }

    /**
     * Esta letra é a "do texto" (Nunito ou a do tema), e não uma das especiais (Fraunces do protetor, ícones)? Usado por
     * FontChoice.aplicarNaTela para saber o que pode trocar.
     */
    fun ehNunito(t: Typeface): Boolean = synchronized(cache) { cache.entries.none { !it.key.startsWith("nunito|") && it.value == t } }

    private fun nunitoEmbutida(ctx: Context, peso: Int): Typeface = cache.getOrPut("nunito|$peso") {
        // Os arquivos moram em res/font (não em assets) para o tema do app usar a mesma família como fonte padrão.
        val id = when {
            peso <= 450 -> R.font.nunito_600
            peso <= 550 -> R.font.nunito_700
            else -> R.font.nunito_800
        }
        try { ctx.resources.getFont(id) } catch (e: Exception) { Typeface.DEFAULT }
    }

    /** O mesmo, sem precisar de um Context à mão (usa o do app). Serve nos lugares que só tinham Typeface.create(...). */
    fun nunito(peso: Int): Typeface = nunito(LauncherApp.instancia, peso)

    /** Fraunces itálica (serifa "de museu", OFL): o nome da obra no protetor. */
    fun fraunces(ctx: Context): Typeface = carregar(ctx, "fraunces_italic.ttf")

    /** Fraunces reta, peso 300 (OFL): o relógio "Editorial" do protetor (17/11/2026). */
    fun fraunces300(ctx: Context): Typeface = carregar(ctx, "fraunces_300.ttf")
}

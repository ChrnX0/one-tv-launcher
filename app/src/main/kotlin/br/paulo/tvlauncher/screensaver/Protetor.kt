package br.paulo.tvlauncher.screensaver

import android.content.Context
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.alarm.HoraPorExtenso
import br.paulo.tvlauncher.home.FundoTerra

/**
 * Estilo do nosso protetor de tela (10/11/2026). "galeria" (padrão): a pintura do dia do tema Galeria com hora, data,
 * clima, céu e avisos por cima ([ProtetorGaleria]). "classico": o protetor de 03/11 (relógio com degradê e 4 quadrados).
 * Gravado nas prefs "launcher", que entram no backup.
 */
object Protetor {
    const val CHAVE = "protetor_estilo"
    const val GALERIA = "galeria"
    const val CLASSICO = "classico"

    /**
     * Sempre Galeria (dono, 11/11/2026: o protetor do Escuro tem que ser IGUAL ao da Galeria, só com o wallpaper escuro).
     * A pref antiga "classico" (vinda de um backup ou da opção que existia nas Configurações) é ignorada.
     */
    @Suppress("UNUSED_PARAMETER")
    fun estilo(ctx: Context): String = GALERIA

    fun definir(ctx: Context, estilo: String) {
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putString(CHAVE, normalizar(estilo)).apply()
    }

    /**
     * O estilo dos widgets de baixo do protetor (15/11/2026, dono: "quero poder escolher eu o estilo do widget nas
     * configs"). Os três mostram a mesma coisa (agora, próximas horas, amanhã), só muda o desenho:
     * FAIXA (padrão): uma faixa de vidro com as próximas horas numa curva; CARTOES: quatro cartões de vidro (inclui sol,
     * lua, umidade e chuva de hoje); SOLTO: sem caixa, a temperatura grande e o tempo dito numa frase.
     * Substitui a escolha antiga "próximas horas / resumo do dia" (12/11), que dividia a informação em dois widgets.
     * Prefs "launcher" (entram no backup). Valor antigo ("horas", "resumo") ou desconhecido vira o padrão.
     */
    const val CHAVE_WIDGET = "protetor_widget"
    const val WIDGET_FAIXA = "faixa"
    const val WIDGET_CARTOES = "cartoes"
    const val WIDGET_SOLTO = "solto"
    /** 17/11/2026 (Paulo: "variações tb para os elementos do clima e todos os outros"): uma linha só, sem caixa; e a
     *  temperatura grande e fina, como um segundo relógio. */
    const val WIDGET_LINHA = "linha"
    const val WIDGET_GRANDE = "grande"
    val WIDGETS = listOf(WIDGET_FAIXA, WIDGET_CARTOES, WIDGET_SOLTO, WIDGET_LINHA, WIDGET_GRANDE)

    fun widget(ctx: Context): String =
        normalizarWidget(ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getString(CHAVE_WIDGET, null))

    fun definirWidget(ctx: Context, v: String) {
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putString(CHAVE_WIDGET, normalizarWidget(v)).apply()
    }

    /** O próximo da lista (a linha das Configurações troca com OK). */
    fun proximoWidget(atual: String): String = WIDGETS[(WIDGETS.indexOf(normalizarWidget(atual)) + 1) % WIDGETS.size]

    fun normalizarWidget(v: String?): String = if (v in WIDGETS) v!! else WIDGET_FAIXA

    /** Valor desconhecido ou vazio (backup antigo, digitação) vira o padrão. */
    fun normalizar(v: String?): String = if (v == CLASSICO) CLASSICO else GALERIA

    // ------------------------------------------------------------------ protetor v2 (17/11/2026, protótipo "Protetor Vivo 2")
    // Paulo: "A+B+C ... e tb ... alguns estilos selecionaveis para o relogio e todos os elementos q já temos". Cada escolha
    // é uma lista fechada: OK nas Configurações passa para o próximo valor ([proximo]); valor desconhecido vira o padrão.
    // Prefs "launcher" (entram no backup).

    /** Uma escolha das Configurações: a chave nas prefs, os valores possíveis e o padrão (o primeiro). */
    class Escolha(val chave: String, val valores: List<String>) {
        val padrao get() = valores.first()
        fun normalizar(v: String?): String = if (v in valores) v!! else padrao
        fun proximo(atual: String?): String = valores[(valores.indexOf(normalizar(atual)) + 1) % valores.size]
        fun ler(ctx: Context): String = normalizar(prefs(ctx).getString(chave, null))
        fun gravar(ctx: Context, v: String) { prefs(ctx).edit().putString(chave, normalizar(v)).apply() }
    }

    /** O fundo: o do TEMA (pintura do The Met, wallpaper Escuro ou Céu de verdade) ou as fotos da Terra da NASA ([FundoTerra]). */
    const val FUNDO_TEMA = "tema"
    const val FUNDO_TERRA = "terra"
    val FUNDO = Escolha("protetor_fundo", listOf(FUNDO_TEMA, FUNDO_TERRA))

    /** O relógio ([RelogioProtetor]). "fino" é o de antes (hora cheia forte, minuto fino). "extenso" só em português. */
    const val REL_FINO = "fino"
    const val REL_EDITORIAL = "editorial"
    const val REL_MONUMENTAL = "monumental"
    const val REL_PONTEIROS = "ponteiros"
    const val REL_EXTENSO = "extenso"
    const val REL_EMPILHADO = "empilhado"
    val RELOGIO = Escolha("protetor_relogio", listOf(REL_FINO, REL_EDITORIAL, REL_MONUMENTAL, REL_PONTEIROS, REL_EXTENSO, REL_EMPILHADO))

    /** Os relógios que a língua da TV permite: "por extenso" só existe em português ([HoraPorExtenso]). */
    fun relogiosDaLingua(lingua: String): List<String> =
        if (HoraPorExtenso.disponivel(lingua)) RELOGIO.valores else RELOGIO.valores - REL_EXTENSO

    /** O relógio a usar: o gravado, ou o fino se o gravado não existe nesta língua (TV trocou de língua). */
    fun relogio(ctx: Context, lingua: String): String = RELOGIO.ler(ctx).let { if (it in relogiosDaLingua(lingua)) it else REL_FINO }

    /** O próximo relógio na lista da língua (a linha das Configurações). */
    fun proximoRelogio(atual: String, lingua: String): String {
        val l = relogiosDaLingua(lingua); val i = l.indexOf(atual)
        return if (i < 0) l.first() else l[(i + 1) % l.size]
    }

    /** Onde fica o grupo da hora. "alto" é o lugar de antes (canto de cima, à esquerda). */
    const val POS_ALTO = "alto"
    const val POS_BAIXO = "baixo"
    const val POS_CENTRO = "centro"
    val POSICAO = Escolha("protetor_posicao", listOf(POS_ALTO, POS_BAIXO, POS_CENTRO))

    val TAMANHO = Escolha("protetor_tamanho", listOf("normal", "grande"))
    /** O relógio "grande" é 22 % maior (o protótipo; letra grande para quem enxerga pouco). */
    fun fatorTamanho(v: String): Float = if (v == "grande") 1.22f else 1f

    /**
     * Quando o resto aparece. SO_HORA (padrão do protótipo aprovado): só o relógio; a 1ª tecla do controle mostra o resto
     * por [TECLA_MS] (Voltar sempre fecha). DE_VEZ: o resto aparece [VEZ_MOSTRA_MS] a cada [VEZ_CICLO_MS]. SEMPRE: o de antes.
     */
    const val MOSTRAR_SO_HORA = "so_hora"
    const val MOSTRAR_DE_VEZ = "de_vez"
    const val MOSTRAR_SEMPRE = "sempre"
    val MOSTRAR = Escolha("protetor_mostrar", listOf(MOSTRAR_SO_HORA, MOSTRAR_DE_VEZ, MOSTRAR_SEMPRE))
    const val TECLA_MS = 15_000L
    const val VEZ_CICLO_MS = 30_000L
    const val VEZ_MOSTRA_MS = 15_000L

    /** O resto está à mostra agora? [teclaAte] = até quando vale a última tecla; [desde] = quando o protetor abriu. */
    fun infoVisivel(modo: String, agora: Long, teclaAte: Long, desde: Long): Boolean = when (modo) {
        MOSTRAR_SEMPRE -> true
        MOSTRAR_DE_VEZ -> agora < teclaAte || ((agora - desde).coerceAtLeast(0) % VEZ_CICLO_MS) < VEZ_MOSTRA_MS
        else -> agora < teclaAte
    }

    // Variações dos outros elementos (17/11/2026, mesmo pedido). O primeiro valor é o desenho de antes.
    /** Avisos e notificações (coluna da direita): cartões de vidro, lista sem caixa, ou um cartão por vez (troca a cada 8 s). */
    const val AVISOS_CARTOES = "cartoes"
    const val AVISOS_LISTA = "lista"
    const val AVISOS_UM = "um"
    val AVISOS = Escolha("protetor_avisos", listOf(AVISOS_CARTOES, AVISOS_LISTA, AVISOS_UM))
    /** Saudação, data e agenda: Manrope (moderno), Fraunces itálica (serifa) ou maiúsculas espaçadas. */
    const val TEXTOS_MODERNO = "moderno"
    const val TEXTOS_SERIFA = "serifa"
    const val TEXTOS_MAIUSCULAS = "maiusculas"
    val TEXTOS = Escolha("protetor_textos", listOf(TEXTOS_MODERNO, TEXTOS_SERIFA, TEXTOS_MAIUSCULAS))
    /** Frase do tempo e curiosidades: com ícones, dentro de um vidro escuro, ou só o texto. */
    const val LINHAS_ICONES = "icones"
    const val LINHAS_VIDRO = "vidro"
    const val LINHAS_TEXTO = "texto"
    val LINHAS = Escolha("protetor_linhas", listOf(LINHAS_ICONES, LINHAS_VIDRO, LINHAS_TEXTO))
    /** Crédito da imagem: etiqueta de museu (título grande + autor) ou uma linha discreta. */
    const val CREDITO_ETIQUETA = "etiqueta"
    const val CREDITO_DISCRETO = "discreto"
    val CREDITO = Escolha("protetor_credito", listOf(CREDITO_ETIQUETA, CREDITO_DISCRETO))

    /** Os elementos que se ligam e desligam um por um. A hora não está aqui: ela sempre aparece. */
    enum class Elemento(val chave: String) {
        SAUDACAO("saud"), DATA("data"), LINHA_DIA("linha_dia"), AGENDA("agenda"), FRASE("frase"), CURIOSIDADES("cur"),
        AVISOS("avisos"), WIDGET("widget"), CREDITO("credito")
    }
    private const val CHAVE_OCULTOS = "protetor_ocultos"

    /** Os desligados, gravados como "saud,cur". Chave desconhecida (versão futura, backup) é ignorada. */
    fun lerOcultos(texto: String?): Set<Elemento> =
        texto.orEmpty().split(',').mapNotNull { k -> Elemento.entries.firstOrNull { it.chave == k.trim() } }.toSet()
    fun escreverOcultos(s: Set<Elemento>): String = Elemento.entries.filter { it in s }.joinToString(",") { it.chave }

    fun ligado(ctx: Context, e: Elemento): Boolean = e !in lerOcultos(prefs(ctx).getString(CHAVE_OCULTOS, null))
    fun alternar(ctx: Context, e: Elemento) {
        val s = lerOcultos(prefs(ctx).getString(CHAVE_OCULTOS, null)).toMutableSet()
        if (!s.remove(e)) s.add(e)
        prefs(ctx).edit().putString(CHAVE_OCULTOS, escreverOcultos(s)).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE)
}

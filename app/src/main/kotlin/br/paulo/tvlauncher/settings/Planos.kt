package br.paulo.tvlauncher.settings

import android.content.Context
import br.paulo.tvlauncher.BuildConfig

/**
 * Planos Básico / Pro / Ultra (degrau 1, "venda sem atrito", 15/11/2026): o PONTO ÚNICO de checagem.
 *
 * Toda tela que um dia ficar restrita pergunta aqui ([permite]); nenhuma decide sozinha. A divisão segue os docs
 * aprovados pelo Paulo ("Grátis e Premium" e "Estratégia: Básico, Pro e Ultra", 15/11/2026) e mora só na tabela
 * [MINIMO]: mudar um recurso de plano é mudar uma linha.
 *
 * Venda (16/11/2026): só no build da Play ([VENDA_ATIVA] = BuildConfig.PLAY), pela cobrança da Play (CobrancaPlay.kt).
 * O APK do site e a Sala continuam Ultra para todo mundo. Puro (PlanosTest), menos [atual].
 */
object Planos {
    /** Liga a cobrança: só no build da Play. Fora dele, todo aparelho é tratado como Ultra (nada muda para a Sala). */
    val VENDA_ATIVA: Boolean get() = BuildConfig.PLAY
    const val PAGINA = "https://chrnx0.github.io/sofaly/plans.html"

    enum class Plano { BASICO, PRO, ULTRA }

    enum class Recurso {
        IPTV_VARIAS_LISTAS, IPTV_TV_DE_VOLTA, IPTV_BLOQUEIO, IPTV_GRAVACAO,
        LISTAS_TRAKT_SIMKL, PAINEL_COMPLETO, FORA_DE_CASA, CLAUDE_MCP, ROTINAS, HOME_ASSISTANT, UNIDADE_DE_REDE,
        TEMAS_TODOS, BACKUP_NUVEM,
        CLIMA_3_MODELOS, FICHAS_TMDB, LEGENDA_AUTOMATICA, SUPORTE_COM_PRAZO,
        /** Degrau 3 (15/11/2026): pedido de ajuda, lembrete com aviso à família, TV que não ligou (CuidarDeLonge). */
        CUIDAR_DE_LONGE,
        // Extras (15/11/2026): emprestar o shell e o Device Owner a outros apps (ShizukuEmbutido, depois o Dhizuku).
        PODER_EMPRESTADO,
        // Degraus 3 e 4: divisão escolhida pelo Paulo em 17/11/2026 (revisão dos degraus, T2). Ficam no Básico (por isso
        // não estão aqui): receitas prontas, cenas prontas, relatar problema, hóspede, recado e foto da família DENTRO de casa.
        /** Pro: o link da família (recado e foto de fora de casa, FamiliaConectada). */
        FAMILIA_LINK,
        /** Pro: recado em vídeo, som no fone, continuar em outra TV, plugins na tela inicial. */
        RECADO_VIDEO, SOM_NO_FONE, CONTINUAR_OUTRA_TV, PLUGINS,
        /** Ultra: várias casas no app do celular, sessão junto e o que usar o servidor da Sofaly (custo recorrente). */
        VARIAS_CASAS, SESSAO_JUNTO, SERVIDOR_SOFALY,
        /** Ouvir (Paulo, 17/11/2026): podcasts no Básico; buscar e "em alta" (Podcast Index, pelo servidor nosso) no Pro. */
        PODCAST_BUSCA,
        /** Cartões próprios da tela inicial (HomeCards, Paulo 18/11/2026: "os cartões de dados são do Pro"). */
        CARTOES_HOME,
        /**
         * Fontes de dados pagas (Paulo, 19/11/2026: "o que existir de alternativa grátis hoje usa para o plano básico,
         * o que for pago começa no pro e para o ultra ficam os mais especiais"). Quem escolhe a fonte é o DataSources.kt.
         * Pro: previsão da WeatherAPI (chance de chuva de verdade e qualidade do ar) e a nota do Simkl sem conta (licença).
         */
        CLIMA_PRO, SIMKL_NOTA_TODOS,
    }

    /** O plano mínimo de cada recurso. O que não está aqui é do Básico (tela inicial, protetor, despertador, player...). */
    val MINIMO: Map<Recurso, Plano> = mapOf(
        Recurso.IPTV_VARIAS_LISTAS to Plano.PRO, Recurso.IPTV_TV_DE_VOLTA to Plano.PRO,
        Recurso.IPTV_BLOQUEIO to Plano.PRO, Recurso.IPTV_GRAVACAO to Plano.PRO,
        Recurso.LISTAS_TRAKT_SIMKL to Plano.PRO, Recurso.PAINEL_COMPLETO to Plano.PRO,
        Recurso.FORA_DE_CASA to Plano.PRO, Recurso.CLAUDE_MCP to Plano.PRO, Recurso.ROTINAS to Plano.PRO,
        Recurso.HOME_ASSISTANT to Plano.PRO, Recurso.UNIDADE_DE_REDE to Plano.PRO,
        Recurso.TEMAS_TODOS to Plano.PRO, Recurso.BACKUP_NUVEM to Plano.PRO, Recurso.PODER_EMPRESTADO to Plano.PRO,
        Recurso.PODCAST_BUSCA to Plano.PRO, Recurso.CARTOES_HOME to Plano.PRO,
        Recurso.CLIMA_3_MODELOS to Plano.ULTRA, Recurso.FICHAS_TMDB to Plano.PRO,   // TMDB: pago, começa no Pro (19/11/2026)
        Recurso.CLIMA_PRO to Plano.PRO, Recurso.SIMKL_NOTA_TODOS to Plano.PRO,
        Recurso.LEGENDA_AUTOMATICA to Plano.ULTRA, Recurso.SUPORTE_COM_PRAZO to Plano.ULTRA,
        Recurso.CUIDAR_DE_LONGE to Plano.ULTRA,
        Recurso.FAMILIA_LINK to Plano.PRO, Recurso.RECADO_VIDEO to Plano.PRO, Recurso.SOM_NO_FONE to Plano.PRO,
        Recurso.CONTINUAR_OUTRA_TV to Plano.PRO, Recurso.PLUGINS to Plano.PRO,
        Recurso.VARIAS_CASAS to Plano.ULTRA, Recurso.SESSAO_JUNTO to Plano.ULTRA, Recurso.SERVIDOR_SOFALY to Plano.ULTRA,
    )

    fun permite(plano: Plano, r: Recurso) = plano.ordinal >= (MINIMO[r] ?: Plano.BASICO).ordinal

    /** Quantas listas IPTV cabem (Básico 1; Pro e Ultra sem limite). */
    fun listasIptv(plano: Plano) = if (plano == Plano.BASICO) 1 else Int.MAX_VALUE
    /** Quantas TVs da mesma casa a compra cobre. */
    fun tvsPorCompra(plano: Plano) = if (plano == Plano.ULTRA) 3 else 1
    /** Temas liberados no Básico (os outros pedem o Pro). */
    val TEMAS_BASICO = setOf(Tema.ESCURO.name, Tema.GALERIA.name, Tema.CEU.name)   // Céu: satélite e mapa sem custo

    /** O plano deste aparelho. Sem venda ativa: Ultra. Com venda: virá da compra (ainda não existe). */
    fun atual(ctx: Context): Plano = if (!VENDA_ATIVA) Plano.ULTRA else CobrancaPlay.planoGuardado(ctx)

    fun permite(ctx: Context, r: Recurso) = permite(atual(ctx), r)

    /** Para o adb e o painel: o plano e o que cada recurso exige. */
    fun texto(plano: Plano): String = buildString {
        append("plano: $plano (venda ${if (VENDA_ATIVA) "ativa" else "desligada: tudo liberado"})\n")
        Recurso.values().forEach { append("${it.name}: ${MINIMO[it]} ${if (permite(plano, it)) "ok" else "bloqueado"}\n") }
    }.trimEnd()
}

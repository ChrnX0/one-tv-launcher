package br.paulo.tvlauncher.home

import android.content.Context
import br.paulo.tvlauncher.settings.Extras

/**
 * Onda 3 do estudo da tela inicial (aprovada pelo Paulo em 18/11/2026, "Sim, tudo"): modo ambiente, arte que desliza
 * (paralaxe), barra de cima em vidro, "Quem está vendo?" ao ligar e "Dormir em 30 min" à noite.
 *
 * Aqui ficam as regras puras (teste JVM: CruzOnda3Test) e as chaves de cada parte, todas ligadas de fábrica e com
 * linha própria em Configurações › Tela inicial. Quem desenha é a MainActivity/HomeCruz.
 */
object CruzOnda3 {
    // ---- chaves (Extras.numero: 1 ligado, 0 desligado)
    const val CHAVE_AMBIENTE = "cruz_ambiente"
    const val CHAVE_PARALAXE = "cruz_paralaxe"
    const val CHAVE_QUEM = "cruz_quem"
    const val CHAVE_DORMIR = "cruz_dormir"

    fun ligado(ctx: Context, chave: String) = Extras.numero(ctx, chave, 1) != 0
    fun alternar(ctx: Context, chave: String) = Extras.definirNumero(ctx, chave, if (ligado(ctx, chave)) 0 else 1)

    // ---- modo ambiente

    /** Parada este tempo na HOME (sem tecla), a cruz recua e ficam a arte e a hora grande. */
    const val AMBIENTE_MS = 2 * 60_000L
    /** A cruz some (e volta) neste tempo: devagar, para não assustar quem está olhando. */
    const val AMBIENTE_FADE_MS = 1000L
    const val VOLTA_MS = 250L

    /**
     * Entra no modo ambiente? Só com a cruz em foco (não na barra de cima nem em "Todos os apps"), sem menu aberto e sem
     * nada tocando (com a rádio tocando, a pílula "Tocando agora" precisa ficar à vista).
     */
    fun entraAmbiente(ligado: Boolean, cruzEmFoco: Boolean, todosAbertos: Boolean, menuAberto: Boolean, tocando: Boolean) =
        ligado && cruzEmFoco && !todosAbertos && !menuAberto && !tocando

    // ---- paralaxe

    /** Quanto a arte anda para o lado oposto da tecla (dp). Só translação: barata no Chromecast. */
    const val PARALAXE_DP = 8f
    const val PARALAXE_MS = 450L

    /** Direção (x, y) da arte para cada tecla: ◀ empurra a arte para a direita, ▼ para cima etc. */
    fun paralaxe(t: CruzLogica.Tecla): Pair<Int, Int> = when (t) {
        CruzLogica.Tecla.ESQUERDA -> 1 to 0
        CruzLogica.Tecla.DIREITA -> -1 to 0
        CruzLogica.Tecla.CIMA -> 0 to 1
        CruzLogica.Tecla.BAIXO -> 0 to -1
        else -> 0 to 0
    }

    // ---- "Quem está vendo?"

    /** A TV precisa ter dormido pelo menos isto para perguntar de novo (desligar e ligar logo não pergunta). */
    const val QUEM_DORMIU_MS = 60 * 60_000L
    /** Ligou a TV e a HOME só apareceu bem depois (estava num app): não pergunta mais. */
    const val QUEM_JANELA_MS = 5 * 60_000L

    fun perguntaQuemVe(ligado: Boolean, contas: Int, dormiuMs: Long, desdeQueLigouMs: Long, visita: Boolean) =
        ligado && !visita && contas >= 2 && dormiuMs >= QUEM_DORMIU_MS && desdeQueLigouMs in 0..QUEM_JANELA_MS

    // ---- "Dormir em 30 min"

    const val DORMIR_MIN = 30

    /** O atalho aparece em Continuar das 22 h às 4 h (ou a qualquer hora enquanto há um timer de sono marcado). */
    fun mostraDormir(ligado: Boolean, hora: Int, temTimer: Boolean) = temTimer || (ligado && (hora >= 22 || hora < 4))
}

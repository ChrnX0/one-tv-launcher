package br.paulo.tvlauncher

import android.content.Context

/**
 * Chaves liga/desliga das ideias da pesquisa de 10/11/2026 (launchers parecidos). Ficam num arquivo de preferências
 * só delas ("extras") e numa tela só delas (ExtrasActivity), para não mexer na tela de Configurações, que está em
 * vários PRs abertos ao mesmo tempo.
 *
 * Cada chave tem o padrão escolhido pelo risco: o que é invisível e reversível vem ligado; o que muda o que a família
 * vê, ou grava dados, vem desligado.
 */
object Extras {
    private const val ARQUIVO = "extras"

    enum class Chave(val padrao: Boolean) {
        /** Atualização do sistema só entre 3h e 5h (DevicePolicyManager.setSystemUpdatePolicy). */
        JANELA_OTA(true),
        /** "Forçar parada" e "Limpar dados" travados no launcher e na VPN fixa (setUserControlDisabledPackages). */
        TRAVAR_CRITICOS(true),
        /** Mexer na organização da HOME (menu do OK segurado) pede o PIN do painel. */
        PIN_HOME(false),
        /** Escurecer e esquentar a tela no horário da noite. */
        FILTRO_NOTURNO(false),
        /** Cor de destaque tirada do fundo (tema Galeria). */
        COR_DO_FUNDO(false),
        /** Diário de DNS por app (registro de rede do Device Owner). Grava na TV o que cada app acessou. */
        DIARIO_DNS(false),
        /** Reaplicar a limpeza de apps depois de uma atualização do sistema. */
        DEBLOAT_POS_OTA(true),
        // 2ª leva (10/11/2026, "faz tudo"): ver NoiteRegras.kt
        /** Hora de dormir: no horário, só os apps da lista abrem e o volume tem teto. Desligar pede o PIN. */
        HORA_DORMIR(false),
        /** Tocando agora: ver o que toca e pausar pelo painel (precisa do acesso às sessões de mídia, pelo shell). */
        TOCANDO_AGORA(false),
        /** Modo leve: animações do Android em 0,5x (desligar volta os valores de antes). */
        MODO_LEVE(false),
        /** Clique baixo a cada seta/OK nas telas do launcher. */
        SOM_NAVEGACAO(false),
        /** Recado por link (formato TvOverlay/PiPup, para o Home Assistant). Sem link criado, não faz nada. */
        RECADO_LINK(false),
        /** Ideia grande 3: o Claude pode ver a tela pelo MCP (ferramenta ver_tela). A TV avisa cada vez. */
        VER_TELA(false),
        // Ideias de 12/11/2026 (brainstorm 3)
        /** Desligar suave: nos 5 min finais do timer de sono, tela escurece e volume desce aos poucos. */
        SONO_SUAVE(true),
        /** Mural da família no protetor de tela (lista de compras, recados fixos, aniversários da agenda). */
        MURAL(false),
        /** Presença pelo roteador UniFi: todos os celulares saíram há 15 min e ninguém mexe no controle = TV dorme. */
        PRESENCA(false),
        /** "Hoje na TV" na tela Buscar (caixa vazia): o que passa agora e nas próximas 3 h nos canais que interessam. */
        HOJE_NA_TV(false),
        /** Retrospectiva da semana: domingo à noite, um cartão no protetor e no painel. */
        RETROSPECTIVA(false),
        /** Modo simples (para quem tem dificuldade com o controle): HOME com 6 cards grandes e nome falado. Desligar pede o PIN. */
        MODO_SIMPLES(false),
    }

    /**
     * Chaves que só mudam com o PIN: segurança (OTA, travas, PIN da HOME), privacidade (diário de DNS, ver a tela) e a
     * hora de dormir. Um conjunto só, usado pelo painel (PainelExtras) E pela tela da TV (ExtrasActivity): antes ele
     * ficava só no painel, e pela TV qualquer um com o controle desligava o PIN da HOME sem PIN nenhum.
     */
    val CHAVES_COM_PIN: Set<Chave> = setOf(Chave.JANELA_OTA, Chave.TRAVAR_CRITICOS, Chave.PIN_HOME, Chave.DIARIO_DNS,
        Chave.HORA_DORMIR, Chave.RECADO_LINK, Chave.VER_TELA, Chave.MODO_SIMPLES)

    /**
     * A tela da TV deve pedir o PIN para mudar [c]? Só se houver PIN no painel ([temPin]; sem PIN não há o que pedir).
     * Exceção: a hora de dormir só pede ao DESLIGAR ([ligadoAgora] = true); ligar é a direção segura.
     */
    fun precisaPinNaTv(c: Chave, ligadoAgora: Boolean, temPin: Boolean): Boolean =
        temPin && c in CHAVES_COM_PIN && ((c != Chave.HORA_DORMIR && c != Chave.MODO_SIMPLES) || ligadoAgora)   // essas duas: só desligar pede

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun ligado(ctx: Context, c: Chave): Boolean = prefs(ctx).getBoolean(c.name, c.padrao)
    fun definir(ctx: Context, c: Chave, sim: Boolean) { prefs(ctx).edit().putBoolean(c.name, sim).apply() }

    fun texto(ctx: Context, chave: String, padrao: String = ""): String = prefs(ctx).getString(chave, padrao) ?: padrao
    fun definirTexto(ctx: Context, chave: String, valor: String) { prefs(ctx).edit().putString(chave, valor).apply() }
    fun numero(ctx: Context, chave: String, padrao: Int): Int = prefs(ctx).getInt(chave, padrao)
    fun definirNumero(ctx: Context, chave: String, valor: Int) { prefs(ctx).edit().putInt(chave, valor).apply() }
}

package br.paulo.tvlauncher

/**
 * O que a versão da Play (`-Pplay`, [BuildConfig.PLAY]) não pode fazer, num ponto só (18/11/2026, Paulo escolheu a
 * "Play enxuta": o que pode derrubar o app na loja fica no Extras, fora da Play; plano em docs/play/politicas.md).
 * Lógica pura, sem Android (PlayPolicyTest). Na Sala e no APK do site nada muda: todas as funções recebem `play` e
 * devolvem o comportamento de sempre quando `play == false`.
 *
 * PR 1 — IA sem acessibilidade. A política da Play proíbe usar a API de acessibilidade para um app "iniciar, planejar
 * e executar ações ou decisões" sozinho (support.google.com/googleplay/android-developer/answer/16585319). O Claude
 * pelo MCP é exatamente isso. Na versão da Play:
 *  - somem as ferramentas que leem ou clicam a tela de outros apps pela acessibilidade ([AI_ACCESSIBILITY_TOOLS]);
 *  - as teclas pedidas pela IA vão pelo shell próprio (`input keyevent`), e INÍCIO abre a nossa tela inicial por
 *    Intent, sem `performGlobalAction`;
 *  - o texto pedido pela IA vai pelo `input text` (sem acento), sem ACTION_SET_TEXT no campo de outro app.
 * O que a PESSOA faz (controle, painel web, rotinas que ela marcou) continua usando a acessibilidade, declarada ao
 * revisor (docs/play/notas-ao-revisor.md).
 */
object PlayPolicy {
    /** Ferramentas do MCP que leem ou tocam a tela de outros apps pela acessibilidade (PR 1). */
    val AI_ACCESSIBILITY_TOOLS = setOf("elementos_na_tela", "tocar_elemento")

    /** Tudo o que o MCP não oferece na versão da Play: as de acessibilidade e as que mexem em outros apps (PR 2). */
    // executar_shell (20/11/2026, debug): comando livre como `adb shell` deixa a IA fazer `pm install`/`pm disable-user`,
    // justamente o que INSTALL_APK e FREEZE_APPS tiram da Play. As ferramentas prontas continuam.
    val PLAY_REMOVED_MCP_TOOLS = AI_ACCESSIBILITY_TOOLS + setOf("instalar_apk", "executar_shell")

    /** A ferramenta do MCP existe nesta versão? */
    fun mcpToolAllowed(name: String, play: Boolean): Boolean = !play || name !in PLAY_REMOVED_MCP_TOOLS

    /**
     * PR 2 — poderes sobre outros apps e sobre o aparelho que a política "Abuso de dispositivo e rede" pode ler como
     * interferência, ou que contornam uma permissão que a loja nega a launchers:
     *  - INSTALL_APK: instalar APK pelo shell (`pm install`) contorna REQUEST_INSTALL_PACKAGES, que a Play só dá a
     *    loja de apps; na versão da Play a pessoa instala pela Play Store.
     *  - FREEZE_APPS: congelar (`pm disable-user`) apps de outros; descongelar continua valendo.
     *  - LEND_POWERS: emprestar o shell (servidor Shizuku) e o Device Owner (servidor Dhizuku) a outros apps.
     *  - READ_PAIRING_CODE: ler na tela das Configurações o código de pareamento pela acessibilidade; na Play a pessoa
     *    digita o código no teclado da janela (sempre existiu).
     */
    enum class Power {
        INSTALL_APK, FREEZE_APPS, LEND_POWERS, READ_PAIRING_CODE,
        /**
         * PR 3 — vídeo de fonte que a pessoa cola. Players de IPTV são removidos da Play por denúncia de detentores de
         * direitos mesmo vazios (36 em 2026, Smarters entre eles); o próprio Stremio ficou 19 dias fora. Na Play:
         *  - LIVE_TV_LISTS: contas de TV ao vivo (Xtream e listas M3U). Sem conta, somem junto a TV ao vivo, a TV de
         *    volta, a gravação e os Filmes/Séries que vinham dessas contas (PlayerArmazem é o ponto único);
         *  - ADDON_STREAMS: o recurso `stream` dos add-ons (de onde tocar). Catálogo, ficha e legenda continuam.
         * Mídia da casa (pasta compartilhada, Jellyfin, Plex, DLNA, pendrive), rádio e podcasts ficam.
         */
        LIVE_TV_LISTS, ADDON_STREAMS,
    }

    /** O poder existe nesta versão? Fora da Play, todos. */
    fun allowed(power: Power, play: Boolean): Boolean = !play

    /** A IA pode agir pela acessibilidade (tecla global, texto no campo de outro app)? Na Play, nunca. */
    fun aiMayUseAccessibility(play: Boolean): Boolean = !play
}

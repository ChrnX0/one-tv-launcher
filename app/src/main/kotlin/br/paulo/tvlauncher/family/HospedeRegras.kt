package br.paulo.tvlauncher.family

import java.util.Calendar
import java.util.TimeZone

/**
 * Modo hóspede (degrau 2, 16/11/2026; pedido do dono para casa de aluguel tipo Airbnb): as REGRAS, sem Android, para o
 * teste JVM (HospedeRegrasTest). Quem age é o ModoHospede.kt.
 *
 * O que o modo faz, por cima do modo visita (que já esconde perfil, agenda e apps pessoais):
 *  - sair do modo e abrir as Configurações pede o PIN (o hóspede não desliga sem querer, nem de propósito);
 *  - na hora da saída (check-out) marcada, ou no "Encerrar estadia agora", apaga os dados dos apps de streaming
 *    (logins da Netflix etc. e o histórico deles) e prepara as boas-vindas do próximo hóspede;
 *  - a tela de boas-vindas mostra as regras da casa e o QR do Wi-Fi das visitas, uma vez por estadia.
 */
object HospedeRegras {
    /**
     * Apps de streaming e música em que um hóspede costuma entrar com a conta dele. Só os INSTALADOS e que não são do
     * sistema entram na limpeza ([paraLimpar]); o dono pode trocar a lista (Configurações > Família > Apps do hóspede).
     */
    val APPS_CONHECIDOS = listOf(
        "com.netflix.ninja",                              // Netflix
        "com.amazon.amazonvideo.livingroom",              // Prime Video
        "com.disney.disneyplus",                          // Disney+
        "com.wbd.stream", "com.hbo.hbonow",               // Max
        "com.globo.globotv",                              // Globoplay
        "com.apple.atve.androidtv.appletv",               // Apple TV
        "com.cbs.ott", "com.cbs.app",                     // Paramount+
        "com.spotify.tv.android",                         // Spotify
        "com.crunchyroll.crunchyroid",                    // Crunchyroll
        "tv.pluto.android",                               // Pluto TV
        "com.google.android.youtube.tv",                  // YouTube (onde o hóspede mais entra com a conta dele; 17/11/2026)
        "com.google.android.apps.youtube.music",          // YouTube Music
        "com.google.android.youtube.tvkids",              // YouTube Kids
        "com.peacocktv.peacockandroid",                   // Peacock
        "com.hulu.livingroomplus",                        // Hulu
        "com.mubi", "br.com.telecine.android",            // MUBI, Telecine
        "com.deezer.android.tv",                          // Deezer
        "com.twitch.android.app",                         // Twitch
    )

    const val HORA_PADRAO = 11
    const val MAX_DIAS = 14

    /**
     * Nunca se limpam: o que mantém a TV funcionando (serviços do Google, Play, a tela inicial da Google, que guarda o
     * Gemini e a escolha de conta, as Configurações, o assistente). O nosso pacote entra por fora (ModoHospede).
     */
    val PROTEGIDOS = setOf(
        "android", "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.android.tv.settings", "com.android.settings",
        "com.google.android.katniss", "com.google.android.tvrecommendations", "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.google.android.apps.tv.dreamx", "com.google.android.tungsten.setupwraith",
    )

    /**
     * Os pacotes que a limpeza apaga, em ordem. [lancaveis] = os apps que aparecem na TV (têm ícone); só eles entram, e
     * nunca um [PROTEGIDOS]. Degrau 3 (16/11/2026): apps de fábrica também entram (a Netflix vem de fábrica em muitos
     * aparelhos, e antes ficava de fora). Base: a lista que o dono escolheu; sem escolha, com [total] = todos os apps
     * da TV ("limpeza total"), senão os conhecidos de streaming.
     */
    fun paraLimpar(escolhidos: Set<String>?, lancaveis: Set<String>, protegidos: Set<String>, total: Boolean = false): List<String> =
        (escolhidos ?: if (total) lancaveis else APPS_CONHECIDOS.toSet())
            .filter { it in lancaveis && it !in protegidos && it !in PROTEGIDOS }.distinct().sorted()

    /** A hora da saída: [dias] depois de hoje (0 = hoje), às [hora]:00 no fuso [zona]. */
    fun saidaEm(agora: Long, dias: Int, hora: Int, zona: TimeZone): Long = Calendar.getInstance(zona).apply {
        timeInMillis = agora
        add(Calendar.DAY_OF_YEAR, dias)
        set(Calendar.HOUR_OF_DAY, hora.coerceIn(0, 23)); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Quantos dias depois de hoje está [saida] (para mostrar "hoje", "amanhã", "em 3 dias"). Negativo = já passou. */
    fun diasAte(agora: Long, saida: Long, zona: TimeZone): Int {
        fun dia(t: Long) = Calendar.getInstance(zona).apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((dia(saida) - dia(agora)) / 86_400_000.0).toInt()
    }

    /**
     * OK na linha "Saída do hóspede": desligada -> hoje -> amanhã -> ... -> [MAX_DIAS] -> desligada. Hoje com a hora já
     * passada pula para amanhã (uma saída no passado limparia na hora). Devolve 0 para "desligada".
     */
    fun proximaSaida(atual: Long, agora: Long, hora: Int, zona: TimeZone): Long {
        val dias = if (atual <= agora) -1 else diasAte(agora, atual, zona)
        var d = dias + 1
        while (d <= MAX_DIAS) {
            val t = saidaEm(agora, d, hora, zona)
            if (t > agora) return t
            d++
        }
        return 0L
    }

    /** OK na linha "Hora da saída": 8 h a 14 h, dando a volta. */
    fun proximaHora(h: Int) = if (h !in 8..13) 8 else h + 1

    /** A saída marcada chegou (e ainda não foi feita). */
    fun venceu(saida: Long, agora: Long) = saida in 1..agora
}

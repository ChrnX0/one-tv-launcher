package br.paulo.tvlauncher.games

import br.paulo.tvlauncher.network.Rede
import org.json.JSONObject

/**
 * Card "Jogos" da HOME (14/11/2026, ideia do dono): um hub onde ficam os apps de jogo (GeForce NOW, Steam Link,
 * PS Remote Play, RetroArch, Moonlight, Play Games...), que saem da linha de apps.
 *
 * Só lógica, sem nada do Android, para testar no PC (JogosTest). Quem lê a TV (apps, controles, rede) é [JogosTv].
 *
 * Regra do dono: entram TODOS os apps de jogo, inclusive os que rodam mal no Chromecast ("se roda ou não é problema
 * dele"). Um app é jogo quando: a pessoa disse que é (escolha), OU está na lista [CONHECIDOS], OU declara a categoria
 * de jogo do Android (ApplicationInfo.CATEGORY_GAME, API 26+). A escolha da pessoa vence tudo.
 */
object Jogos {
    /** Os 4 grupos da tela Jogos, na ordem em que aparecem. */
    enum class Grupo { NUVEM, PC, RETRO, TV }

    /** Valor de escolha que tira um app do card (volta para a linha de apps). */
    const val FORA = "FORA"

    /**
     * Pacotes conhecidos -> grupo. Os nomes de pacote foram conferidos na Play Store/F-Droid/GitHub de cada projeto
     * até 14/11/2026, menos os marcados "não verifiquei" (entram mesmo assim: um pacote que não existe só não casa).
     */
    val CONHECIDOS: Map<String, Grupo> = linkedMapOf(
        // Na nuvem: o jogo roda num servidor e a TV só recebe o vídeo.
        "com.nvidia.geforcenow" to Grupo.NUVEM,
        "com.microsoft.xboxone.smartglass" to Grupo.NUVEM,      // app Xbox (Cloud Gaming)
        "com.gamepass" to Grupo.NUVEM,                          // versões antigas do XStreaming (build.gradle dele)
        "com.boosteroid.streaming" to Grupo.NUVEM,              // Boosteroid (não verifiquei)
        "com.amazon.luna.android" to Grupo.NUVEM,               // Amazon Luna (não verifiquei)
        // Do PC e do console: o jogo roda num computador ou console da casa.
        "com.valvesoftware.steamlink" to Grupo.PC,
        "com.limelight" to Grupo.PC,                            // Moonlight
        "com.playstation.remoteplay" to Grupo.PC,               // PS Remote Play (celular)
        "com.playstation.remoteplay.tv" to Grupo.PC,            // PS Remote Play da Google TV (visto na Sala, 14/11/2026)
        "eu.hetko.xbplayer" to Grupo.PC,                        // XBPlayer: Xbox de casa e Xbox Cloud (Play Store, roda na TV)
        "com.xstreaming" to Grupo.PC,                           // XStreaming: o mesmo, código aberto (GitHub)
        "tv.parsec.client" to Grupo.PC,                         // Parsec (não verifiquei)
        "com.metallic.chiaki" to Grupo.PC,                      // Chiaki (PlayStation, código aberto)
        // Retrô: emuladores.
        "com.retroarch" to Grupo.RETRO,
        "com.retroarch.aarch64" to Grupo.RETRO,
        "com.retroarch.ra32" to Grupo.RETRO,
        "com.swordfish.lemuroid" to Grupo.RETRO,
        "org.ppsspp.ppsspp" to Grupo.RETRO,
        "org.ppsspp.ppssppgold" to Grupo.RETRO,
        "org.dolphinemu.dolphinemu" to Grupo.RETRO,
        "com.github.stenzek.duckstation" to Grupo.RETRO,
        // Jogos da TV: instalados na própria TV.
        "com.google.android.play.games" to Grupo.TV,
    )

    /** Apps para "Jogar no PC", na ordem de preferência quando a pessoa não escolheu um. */
    val APPS_PC = listOf("com.limelight", "com.valvesoftware.steamlink", "tv.parsec.client")

    /** Sugestões da loja do card (abre a página da Play Store; Moonlight também pela loja curada do GitHub). */
    class Sugestao(val pacote: String, val nome: String, val grupo: Grupo)
    val SUGESTOES = listOf(
        Sugestao("com.nvidia.geforcenow", "GeForce NOW", Grupo.NUVEM),
        Sugestao("com.valvesoftware.steamlink", "Steam Link", Grupo.PC),
        Sugestao("com.limelight", "Moonlight", Grupo.PC),
        Sugestao("com.playstation.remoteplay.tv", "PS Remote Play", Grupo.PC),
        Sugestao("eu.hetko.xbplayer", "XBPlayer (Xbox)", Grupo.PC),
        Sugestao("com.retroarch", "RetroArch", Grupo.RETRO),
    )

    /** O app é jogo? [categoriaJogo] = declara ApplicationInfo.CATEGORY_GAME. */
    fun ehJogo(pacote: String, categoriaJogo: Boolean, escolhas: Map<String, String>): Boolean {
        val e = escolhas[pacote]
        if (e == FORA) return false
        if (e != null && grupoDe(e) != null) return true
        return pacote in CONHECIDOS || categoriaJogo
    }

    /** Em que grupo o jogo fica: escolha > lista conhecida > "Jogos da TV" (o que declara ser jogo). */
    fun grupo(pacote: String, escolhas: Map<String, String>): Grupo =
        escolhas[pacote]?.let(::grupoDe) ?: CONHECIDOS[pacote] ?: Grupo.TV

    private fun grupoDe(nome: String): Grupo? = Grupo.values().firstOrNull { it.name == nome }

    /**
     * Os jogos de [apps] (pacote -> declara categoria de jogo) por grupo, na ordem dos grupos. Grupo vazio não entra.
     * Dentro do grupo, a ordem de [apps] (quem chama já ordena por nome ou uso).
     */
    fun agrupar(apps: List<Pair<String, Boolean>>, escolhas: Map<String, String>): Map<Grupo, List<String>> {
        val m = linkedMapOf<Grupo, MutableList<String>>()
        for (g in Grupo.values()) m[g] = mutableListOf()
        for ((p, cat) in apps) if (ehJogo(p, cat, escolhas)) m.getValue(grupo(p, escolhas)) += p
        return m.filterValues { it.isNotEmpty() }
    }

    /** Muda a escolha de um app ([valor] = nome de um [Grupo], [FORA], ou null = volta ao automático). */
    fun escolher(escolhas: Map<String, String>, pacote: String, valor: String?): Map<String, String> =
        if (valor == null) escolhas - pacote else escolhas + (pacote to valor)

    fun escolhasDeJson(texto: String?): Map<String, String> = try {
        val o = JSONObject(texto ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
            .filterValues { it == FORA || grupoDe(it) != null }
    } catch (_: Exception) { emptyMap() }

    fun escolhasParaJson(m: Map<String, String>): String = JSONObject(m as Map<*, *>).toString()

    /** Qual app abre o "Jogar no PC": o escolhido (se instalado) ou o 1º de [APPS_PC] instalado. Null = nenhum. */
    fun appDoPc(instalados: Set<String>, escolhido: String?): String? =
        escolhido?.takeIf { it in instalados } ?: APPS_PC.firstOrNull { it in instalados }

    /**
     * Ordem da fila "Seus jogos" (esboço A, 14/11/2026): o jogado por último primeiro. [uso] = pacote -> última vez
     * (ms, do UsageStats do Android, que vê o jogo aberto por qualquer caminho). Quem nunca foi usado fica no fim, na
     * ordem que veio (por nome).
     */
    fun ordenarPorUso(pacotes: List<String>, uso: Map<String, Long>): List<String> =
        pacotes.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { uso[it.value] ?: 0L }.thenBy { it.index })
            .map { it.value }

    /** O jogo usado por último entre [pacotes] (o fundo do card Jogos na HOME). Null = nenhum usado. */
    fun ultimoUsado(pacotes: Collection<String>, uso: Map<String, Long>): String? =
        pacotes.filter { (uso[it] ?: 0L) > 0L }.maxByOrNull { uso.getValue(it) }

    // ---------------------------------------------------------------- rede

    enum class Rede { BOA, OK, FRACA, SEM }

    /**
     * A rede aguenta jogar na nuvem? Jogo pela nuvem sofre com atraso (ms) e Wi-Fi fraco, não com o download.
     * Números das recomendações públicas do GeForce NOW (até 14/11/2026: menos de 80 ms, ideal menos de 40 ms) e da
     * faixa de sinal Wi-Fi que o Android chama de "fraco" (abaixo de -70 dBm). [dbm] = 0 quando não há Wi-Fi (cabo).
     */
    fun avaliarRede(conectada: Boolean, cabo: Boolean, dbm: Int, tcpMs: Int?): Rede = when {
        !conectada || tcpMs == null -> Rede.SEM
        tcpMs > 80 || (!cabo && dbm in -126..-71) -> Rede.FRACA
        tcpMs > 40 || (!cabo && dbm in -70..-61) -> Rede.OK
        else -> Rede.BOA
    }

    // ---------------------------------------------------------------- jogar no PC

    /** Quanto esperar o PC acordar antes de abrir o app mesmo assim (um PC com SSD costuma levar 20 a 40 s). */
    const val ESPERA_PC_MS = 60_000L
    /** Sem IP do PC guardado não dá para perguntar se ele acordou: espera este tempo fixo e abre o app. */
    const val ESPERA_SEM_IP_MS = 20_000L

    /** IPv4 "192.168.1.10" válido? (o IP do PC fica nas preferências, nunca no código). */
    fun ipValido(texto: String): Boolean {
        val p = texto.trim().split('.')
        return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    }
}

package br.paulo.tvlauncher

/**
 * Biblioteca retrô da tela Jogos (21/11/2026, pedido do dono: "o hub reconhece os jogos da pasta e abre no emulador,
 * dentro da legalidade"). Só lógica, sem Android, para testar no PC (RetroLibraryTest).
 *
 * Porquê: os jogos que a pessoa já tem no pen drive (na Sala, 308 de Super Nintendo) não apareciam em lugar nenhum;
 * para jogar era preciso entrar no RetroArch e navegar por pastas.
 * Para quê: escolher o jogo pelo nome, no visual da cruz, e jogar com um OK, como num console.
 * Como: quem lê o pen drive é [RetroLibraryTv] pelo shell próprio (o mesmo caminho da aba Arquivos, que funciona até
 * no build da Play, sem "acesso a todos os arquivos"). Aqui fica: reconhecer o console pela pasta ou extensão, limpar o
 * nome, tirar repetidos e montar o pedido de abertura do emulador.
 *
 * Legalidade (decidido 21/11/2026): o Sofaly não traz jogo, não baixa jogo, não dá link para jogo e não baixa capa
 * de caixa (o acervo de capas do RetroArch não tem licença). A capa é a imagem que a pessoa deixou ao lado do jogo ou
 * uma capa desenhada pelo próprio Sofaly.
 */
object RetroLibrary {

    /**
     * Consoles reconhecidos. [extensoes] só valem sozinhas quando são exclusivas do console; as genéricas (.bin, .iso,
     * .zip, .chd, .cue, .7z) precisam de uma pasta com o nome do console. [nucleo] = núcleo do RetroArch para Android
     * da Play, em ordem de preferência (vem como módulo da Play: `lib<nucleo>.so` na pasta de bibliotecas do app; conferido na Sala em 21/11/2026,
     * 50 núcleos instalados), escolhido pelo desempenho no Chromecast 32 bits.
     */
    enum class Console(val nome: String, val extensoes: Set<String>, val pastas: Set<String>, val nucleos: List<String>) {
        NES("NES", setOf("nes", "fds", "unf", "unif"), setOf("nes", "nintendo", "famicom", "fc"), listOf("fceumm", "nestopia")),
        SNES("Super Nintendo", setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "super nintendo", "supernintendo", "super famicom"), listOf("snes9x", "bsnes")),
        N64("Nintendo 64", setOf("n64", "z64", "v64"), setOf("n64", "nintendo 64", "nintendo64"), listOf("mupen64plus_next_gles3")),
        GB("Game Boy", setOf("gb"), setOf("gb", "game boy", "gameboy"), listOf("gambatte", "sameboy", "mgba")),
        GBC("Game Boy Color", setOf("gbc"), setOf("gbc", "game boy color", "gameboy color"), listOf("gambatte", "sameboy", "mgba")),
        GBA("Game Boy Advance", setOf("gba"), setOf("gba", "game boy advance", "gameboy advance"), listOf("mgba")),
        NDS("Nintendo DS", setOf("nds"), setOf("nds", "nintendo ds", "ds"), listOf("melondsds")),
        MD("Mega Drive", setOf("md", "gen", "smd"), setOf("megadrive", "mega drive", "genesis", "md"), listOf("genesis_plus_gx", "picodrive")),
        SMS("Master System", setOf("sms"), setOf("mastersystem", "master system", "sms"), listOf("genesis_plus_gx", "picodrive")),
        GG("Game Gear", setOf("gg"), setOf("gamegear", "game gear", "gg"), listOf("genesis_plus_gx", "picodrive")),
        PS1("PlayStation", setOf("pbp"), setOf("psx", "ps1", "playstation", "playstation 1"), listOf("pcsx_rearmed", "mednafen_psx_hw")),
        PSP("PSP", setOf("cso"), setOf("psp"), listOf("ppsspp")),
        ATARI2600("Atari 2600", setOf("a26"), setOf("atari2600", "atari 2600", "a2600"), listOf("stella")),
        PCE("PC Engine", setOf("pce"), setOf("pce", "pc engine", "pcengine", "turbografx"), listOf("geargrafx")),
    }

    /** Extensões que só contam com a pasta do console (sozinhas não dizem nada). */
    val GENERICAS = setOf("bin", "iso", "zip", "7z", "chd", "cue", "img")

    /** [portugues] = tradução ou versão brasileira marcada no nome ("(PT-BR)", "(BR)"): vira um selo na tela. */
    class Jogo(val caminho: String, val console: Console, val nome: String, val regiao: Int, val capa: String?,
               val portugues: Boolean = false)

    private val IMAGENS = setOf("png", "jpg", "jpeg", "webp")

    private fun extensao(arquivo: String) = arquivo.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    private fun normal(s: String) = s.lowercase().replace('_', ' ').replace('-', ' ').trim()

    /** O console de [caminho]: uma pasta com o nome dele (a mais próxima do arquivo vence) ou a extensão exclusiva. */
    fun console(caminho: String): Console? {
        val ext = extensao(caminho)
        val pastas = caminho.split('/').dropLast(1).map(::normal).reversed()
        val pelaPasta = pastas.firstNotNullOfOrNull { p -> Console.values().firstOrNull { p in it.pastas } }
        val pelaExtensao = Console.values().firstOrNull { ext in it.extensoes }
        return when {
            pelaExtensao != null -> pelaExtensao   // .sfc é SNES mesmo numa pasta "Jogos"
            ext in GENERICAS -> pelaPasta
            else -> null
        }
    }

    /**
     * Nome para a tela: sem extensão, sem as marcas de catálogo ("(USA)", "[!]", "(Rev 1)"), "_" vira espaço e
     * "Legend of Zelda, The" vira "The Legend of Zelda".
     */
    fun nomeLimpo(caminho: String): String {
        var n = caminho.substringAfterLast('/')
        while ('.' in n && extensao(n).let { e -> e in GENERICAS || Console.values().any { e in it.extensoes } })
            n = n.substringBeforeLast('.')   // "Knights of the Round .sfc.sfc" (visto no pen drive da Sala)
        n = n.replace(Regex("""\s*[\(\[][^)\]]*([\)\]]|$)"""), "")   // "(USA)", "[!]" e o "(BR" sem fechar
            .replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        // "Legend of Zelda, The - ..." e "Lion King The" (sem vírgula, comum em coleções) viram "The Legend...".
        val artigo = Regex("""^(.*?),? (The)(\s+-.*)?$""").find(n)
            ?: Regex("""^(.*), (A|An|O|Os|As)(\s+-.*)?$""").find(n)
        if (artigo != null && artigo.groupValues[1].isNotBlank())
            n = artigo.groupValues[2] + " " + artigo.groupValues[1] + artigo.groupValues[3]
        return n.ifEmpty { caminho.substringAfterLast('/') }
    }

    /** Quanto a região agrada (menor = melhor): sem marca ou Brasil, depois EUA/Mundo, Europa, Japão, o resto. */
    fun regiao(caminho: String): Int {
        val n = caminho.substringAfterLast('/').lowercase()
        return when {
            "(brazil" in n || "(b)" in n || portugues(caminho) -> 0
            "(usa" in n || "(world" in n || "(u)" in n || "(ue)" in n -> 1
            "(europe" in n || "(e)" in n -> 2
            "(japan" in n || "(j)" in n -> 3
            Regex("""[\(\[]""").containsMatchIn(n) -> 4
            else -> 0
        }
    }

    /** Tradução ou versão brasileira marcada no nome. */
    fun portugues(caminho: String): Boolean =
        Regex("""[\(\[](pt-br|ptbr|br|br-u|brazil|portugu[eê]s)[\)\]-]?""", RegexOption.IGNORE_CASE)
            .containsMatchIn(caminho.substringAfterLast('/'))

    /** Arquivo que não é jogo de ninguém: pastas internas de apps, ocultos, lixo do macOS. */
    private fun ignorar(caminho: String): Boolean {
        val partes = caminho.split('/')
        return partes.any { it.startsWith('.') } || "/Android/" in caminho || "/LOST.DIR/" in caminho || "/System Volume Information/" in caminho ||
            partes.last().startsWith("._")
    }

    /**
     * Monta a biblioteca a partir da saída de `find <raiz> -type f` (uma linha por arquivo; `\r` do adb ignorado).
     * Repetidos (mesmo console e mesmo nome limpo) ficam um só, o da região preferida. Ordem: console, depois nome.
     */
    fun montar(saidaFind: String): List<Jogo> {
        val linhas = saidaFind.split(Regex("""\r?\n""")).map { it.trim() }.filter { it.startsWith('/') && !ignorar(it) }
        val imagens = linhas.filter { extensao(it) in IMAGENS }
            .associateBy { it.substringBeforeLast('.').lowercase() }
        val jogos = linhas.mapNotNull { c ->
            val con = console(c) ?: return@mapNotNull null
            Jogo(c, con, nomeLimpo(c), regiao(c), imagens[c.substringBeforeLast('.').lowercase()], portugues(c))
        }
        return jogos.groupBy { it.console to it.nome.lowercase() }.values
            .map { grupo -> grupo.minWith(compareBy<Jogo> { it.regiao }.thenBy { it.caminho }) }
            .sortedWith(compareBy<Jogo> { it.console.ordinal }.thenBy { it.nome.lowercase() })
    }

    /** Quantos jogos por console, na ordem dos consoles (prateleira "Consoles"). */
    fun porConsole(jogos: List<Jogo>): List<Pair<Console, Int>> =
        jogos.groupingBy { it.console }.eachCount().toList().sortedBy { it.first.ordinal }

    /** "Continuar": os jogados (caminho -> última vez em ms), o mais recente primeiro, no máximo [max]. */
    fun continuar(jogos: List<Jogo>, ultimaVez: Map<String, Long>, max: Int = 10): List<Jogo> =
        jogos.filter { (ultimaVez[it.caminho] ?: 0L) > 0L }.sortedByDescending { ultimaVez[it.caminho] }.take(max)

    /** Raiz de um volume em `/storage/<id>` (null fora de /storage). O emulado é `/storage/emulated/0`. */
    fun raizDoVolume(caminho: String): String? {
        val p = caminho.split('/')
        if (p.size < 4 || p[1] != "storage") return null
        return if (p[2] == "emulated") "/storage/emulated/${p.getOrNull(3) ?: return null}" else "/storage/${p[2]}"
    }

    // ---------------------------------------------------------------- abrir no RetroArch

    const val RETROARCH = "com.retroarch"
    const val RETROARCH_ATIVIDADE = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    /**
     * Caminho 2 (escolhido pelo dono 21/11/2026): o RetroArch da Play não pede permissão de arquivos, mas lê a própria
     * pasta `Android/data/com.retroarch/files` em qualquer volume. Copiamos o jogo para lá NO MESMO pen drive (não gasta
     * a memória da TV, que tem ~800 MB livres). Null = o jogo não está num volume /storage.
     */
    fun destinoNoEmulador(caminho: String, pacote: String = RETROARCH): String? {
        val raiz = raizDoVolume(caminho) ?: return null
        return "$raiz/Android/data/$pacote/files/sofaly/${caminho.substringAfterLast('/')}"
    }

    private fun aspas(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Comando do shell que copia o jogo para [destino] (só se ainda não estiver lá com o mesmo tamanho). */
    fun comandoCopiar(origem: String, destino: String): String {
        val dir = destino.substringBeforeLast('/')
        return "mkdir -p ${aspas(dir)} && { [ -f ${aspas(destino)} ] && [ \$(stat -c %s ${aspas(destino)}) = \$(stat -c %s ${aspas(origem)}) ] || cp ${aspas(origem)} ${aspas(destino)}; } && echo ok"
    }

    /**
     * Extras do Intent que abre [rom] direto no RetroArch (ROM e núcleo), no formato que outros frontends usam
     * (Pegasus, Daijishō). [dirNucleos] = `nativeLibraryDir` do RetroArch: a versão da Play extrai ali os núcleos que
     * vêm da Play (ex.: `libsnes9x.so`, conferido na Sala em 21/11/2026). Sem CONFIGFILE: um caminho errado faria o
     * RetroArch criar outra configuração e "perder" a da pessoa. Abrir o jogo ainda não foi testado na TV.
     */
    fun extrasRetroArch(rom: String, nucleo: String, dirNucleos: String): Map<String, String> = mapOf(
        "ROM" to rom,
        "LIBRETRO" to "$dirNucleos/lib$nucleo.so",
        "QUITFOCUS" to "",
    )

    /**
     * O primeiro núcleo de [console] que o RetroArch tem instalado. [modulos] = `ApplicationInfo.splitNames` do RetroArch
     * (módulos da Play: "core_snes9x", "core_snes9x.config.armeabi_v7a"...). Null = nenhum: a pessoa baixa no RetroArch
     * (só o próprio app pode pedir os módulos dele à Play).
     */
    fun nucleoInstalado(console: Console, modulos: Collection<String>): String? {
        val tem = modulos.filter { it.startsWith("core_") && '.' !in it }.map { it.removePrefix("core_") }.toSet()
        return console.nucleos.firstOrNull { it in tem }
    }
}

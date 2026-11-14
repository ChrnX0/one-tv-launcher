package br.paulo.tvlauncher

/**
 * Ideia 71 (14/11/2026): instalar pacote dividido (.apks do SAI/bundletool, .xapk do APKPure). São ZIPs
 * com várias partes .apk: a base e as "config" (arquitetura, tela, idioma). Aqui se escolhe, sem Android
 * (PacoteDivididoTest), quais partes vão para a MESMA sessão do PackageInstaller:
 *  - todas as .apk do ZIP, menos as de arquitetura que esta TV não roda (arm64 numa TV 32 bits, x86) e menos
 *    "standalones/" do bundletool (são APKs inteiros alternativos, não partes);
 *  - densidade e idioma vão todas: o Android usa a que serve e ignora o resto.
 * OBB (dados extras de jogos no .xapk) fica de fora: o PainelApps avisa.
 */
object PacoteDividido {
    const val MAX_PARTES = 60
    private val ARQS = mapOf("armeabi_v7a" to "armeabi-v7a", "armeabi" to "armeabi", "arm64_v8a" to "arm64-v8a",
        "x86_64" to "x86_64", "x86" to "x86", "mips" to "mips", "mips64" to "mips64")

    /** A arquitetura que o nome da parte cita ("split_config.arm64_v8a.apk" -> "arm64-v8a"), ou null. */
    fun arquitetura(nome: String): String? {
        val base = nome.substringAfterLast('/').lowercase().removeSuffix(".apk").replace('-', '_')
        // Os compostos primeiro (x86_64 antes de x86, arm64_v8a antes de armeabi).
        for (k in listOf("arm64_v8a", "armeabi_v7a", "x86_64", "mips64", "armeabi", "x86", "mips"))
            if (Regex("(^|[._])${k}($|[._])").containsMatchIn(base)) return ARQS[k]
        return null
    }

    /** As partes a instalar, na ordem do ZIP. Null = o ZIP não tem parte que sirva (ou passou do teto). */
    fun partes(nomes: List<String>, abisDaTv: List<String>): List<String>? {
        val apks = nomes.filter { n ->
            n.endsWith(".apk", true) && !n.endsWith("/") && !n.startsWith("standalones/") && !n.contains("/standalones/") &&
                !n.contains("..") && !n.startsWith("/")
        }
        val escolhidas = apks.filter { n -> arquitetura(n)?.let { it in abisDaTv } ?: true }
        if (escolhidas.isEmpty() || escolhidas.size > MAX_PARTES) return null
        // Se há partes de arquitetura no pacote e nenhuma serve à TV, o app não roda aqui.
        val temArq = apks.any { arquitetura(it) != null }
        if (temArq && escolhidas.none { arquitetura(it) != null }) return null
        return escolhidas
    }

    /** A parte principal: "base.apk" (bundletool/SAI) ou "<pacote>.apk" (xapk); nunca "split_*" nem "config.*". */
    fun ehBase(nome: String): Boolean {
        val n = nome.substringAfterLast('/').lowercase().replace(Regex("^\\d+_"), "")
        return n == "base.apk" || (!n.startsWith("split") && !n.contains("config.") && arquitetura(n) == null)
    }

    /** Qual das [nomes] é a base: "base.apk" ou "*-master.apk" primeiro; senão a primeira que [ehBase] aceita. */
    fun escolherBase(nomes: List<String>): String? {
        fun limpo(n: String) = n.substringAfterLast('/').lowercase().replace(Regex("^\\d+_"), "")
        return nomes.firstOrNull { limpo(it) == "base.apk" || limpo(it).endsWith("-master.apk") }
            ?: nomes.firstOrNull { ehBase(it) }
    }

    /** Tem OBB (dados de jogo do .xapk)? Esses não são instalados por aqui. */
    fun temObb(nomes: List<String>): Boolean = nomes.any { it.endsWith(".obb", true) }

    /** Nome da parte dentro da sessão: só o arquivo, sem pasta, e único. */
    fun nomeNaSessao(nome: String, i: Int): String =
        "${i}_" + nome.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}

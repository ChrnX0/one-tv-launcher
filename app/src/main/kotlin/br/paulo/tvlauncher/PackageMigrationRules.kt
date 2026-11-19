package br.paulo.tvlauncher

/**
 * Regras puras da troca de pacote br.paulo.tvlauncher -> tv.sofaly.launcher (19/11/2026). Ficam aqui, sem Android,
 * para o teste JVM (PackageMigrationRulesTest) conferir o que é copiado, o que é reescrito e o que nunca passa.
 * O fluxo com o Android está em PackageMigration.kt.
 */
object PackageMigrationRules {
    /** Arquivos do APK novo no Release (o build de 32 e o de 64 bits). Nome em inglês (regra do Paulo, 18/11/2026). */
    const val ASSET_32 = "sofaly-launcher.apk"
    const val ASSET_64 = "sofaly-launcher-arm64.apk"

    /** Uma entrada maior que isto não passa (um app de TV não tem motivo para um arquivo de 64 MB nos dados). */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    /** O total da cópia. Passou disso, a migração para antes de transferir o dono: nada muda na TV. */
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    /** Arquivos de texto maiores que isto não são reescritos (só copiados). */
    const val MAX_REWRITE_BYTES = 4L * 1024 * 1024

    /**
     * Pastas copiadas, como aparecem no zip. As quatro primeiras são do dataDir do app; "external" é a pasta do app em
     * Android/data (fotos de perfil, CSV do clima), que o app novo não consegue ler sozinho.
     * Ficam de fora: cache, code_cache, lib e app_* (WebView e afins, refeitos sozinhos).
     */
    val COPIED_ROOTS = listOf("shared_prefs", "files", "databases", "no_backup", "external")

    /** O caminho é seguro para gravar dentro do dataDir? Sem "..", sem barra no começo, sem vazio, sem "\". */
    fun safePath(rel: String): Boolean {
        if (rel.isEmpty() || rel.length > 512 || rel.startsWith("/") || rel.contains('\\') || rel.contains('\u0000')) return false
        return rel.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Este arquivo (caminho relativo, como no zip) entra na cópia? */
    fun shouldCopy(rel: String): Boolean {
        if (!safePath(rel)) return false
        if (rel.substringBefore('/') !in COPIED_ROOTS || !rel.contains('/')) return false
        // O certificado do painel vai à parte: a chave dele está trancada pelo Keystore do app antigo (PainelTls).
        if (rel.startsWith("no_backup/painel-tls/")) return false
        // O estado da própria migração é de cada lado.
        if (rel == "shared_prefs/${PREFS}.xml") return false
        // Arquivos temporários de escrita pela metade (".tmp", ".novo", ".bak" do SharedPreferences) não servem.
        if (rel.endsWith(".tmp") || rel.endsWith(".novo") || rel.endsWith(".bak")) return false
        return true
    }

    /** Arquivos de texto que podem guardar o nome do pacote: preferências (XML) e os JSON do app. */
    fun shouldRewrite(rel: String): Boolean =
        (rel.startsWith("shared_prefs/") && rel.endsWith(".xml")) || rel.endsWith(".json")

    /**
     * Troca o PACOTE antigo pelo novo num texto, sem tocar nos nomes de CLASSE. "br.paulo.tvlauncher" sozinho ou antes
     * de "/" (componente "pacote/classe") vira o novo; "br.paulo.tvlauncher.MainActivity" (classe) e
     * "br.paulo.tvlauncher.PAREAR" (ação) ficam como estão, porque o código continua no mesmo namespace.
     */
    fun rewritePackage(texto: String, de: String = AppIdentity.LEGACY_PACKAGE, para: String = AppIdentity.NEW_PACKAGE): String =
        Regex("(?<![A-Za-z0-9_.])" + Regex.escape(de) + "(?![A-Za-z0-9_.])").replace(texto, Regex.escapeReplacement(para))

    /**
     * Lista de serviços de acessibilidade ("pacote/Classe:pacote/Classe"): tira os de [tirarPacote] e põe [componente]
     * (uma vez só). A forma curta "pacote/.Classe" também conta como do pacote.
     */
    fun swapAccessibility(lista: String?, tirarPacote: String, componente: String): String {
        val itens = lista.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { it.substringBefore('/') != tirarPacote && it != componente }
        return (itens + componente).joinToString(":")
    }

    /** Qual APK do Release instalar: o da mesma arquitetura, só se o .sha256 dele também estiver lá. */
    fun pickAsset(nomes: Set<String>, is64: Boolean): String? {
        val nome = if (is64) ASSET_64 else ASSET_32
        return nome.takeIf { it in nomes && "$it.sha256" in nomes }
    }

    /** Preferências do estado da migração (não são copiadas; ver [shouldCopy]). */
    const val PREFS = "package_migration"

    /** Etapas, gravadas em [PREFS] para o comando "migration" mostrar onde parou. */
    enum class Step { IDLE, INSTALLED, ADMIN, IMPORTED, TRANSFERRED, DONE, FAILED, ROLLED_BACK }
}

package br.paulo.tvlauncher.backup

/**
 * Regras puras da tela "Pen drive e armazenamento" (StorageActivity), testadas em StorageModeRulesTest.
 *
 * Porquê: aparelhos com 4-8 GB (Chromecast e outros) enchem; um pen drive pode virar memória da TV. Para quê: a pessoa
 * escolhe o uso sem saber o que é "adotar". Como: o Android já sabe tudo (`sm list-disks`, `sm list-volumes all`,
 * `sm partition`); aqui só se lê a saída, se decide o modo atual, se recomenda um e se monta o comando seguro.
 * Conversa com: AppStorage (mover apps para a parte adotada), GravacaoDestino dos players (gravam na parte comum ou na
 * memória da TV), MTP/WebDAV (só a parte comum aparece na rede como drive separado).
 */
object StorageModeRules {
    enum class Modo { COMUM, MISTO, MEMORIA_TV, DESCONHECIDO }
    enum class Tipo { PUBLICO, PRIVADO, EMULADO, OUTRO }

    /** Uma linha de `sm list-volumes all`: "<id> <estado> <fsUuid>". */
    data class Volume(val id: String, val estado: String, val fsUuid: String?) {
        val tipo: Tipo get() = when {
            id.startsWith("public:") -> Tipo.PUBLICO
            id.startsWith("private:") -> Tipo.PRIVADO
            id.startsWith("emulated") -> Tipo.EMULADO
            else -> Tipo.OUTRO
        }
        val montado: Boolean get() = estado == "mounted"
        /** "major,minor" do volume (ex.: "8,2" de "private:8,2"); null na memória interna. */
        val dispositivo: Pair<Int, Int>? get() = majorMinor(id.substringAfter(':', "").substringBefore(';'))
    }

    private val DISCO = Regex("^disk:(\\d+),(\\d+)$")

    private fun majorMinor(s: String): Pair<Int, Int>? {
        val p = s.split(',')
        if (p.size != 2) return null
        val a = p[0].toIntOrNull() ?: return null
        val b = p[1].toIntOrNull() ?: return null
        return a to b
    }

    private fun linhas(saida: String) = saida.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** `sm list-disks`: "disk:8,0" por linha. Só o que tem o formato certo (vai para o comando). */
    fun discos(saida: String): List<String> = linhas(saida).filter { DISCO.matches(it) }

    fun volumes(saida: String): List<Volume> = linhas(saida).mapNotNull { l ->
        val p = l.split(Regex("\\s+"))
        if (p.size < 2) null else Volume(p[0], p[1], p.getOrNull(2)?.takeIf { it != "null" })
    }

    /**
     * Volumes de um disco: mesmo major e minor logo depois do disco (sda = 8,0; partições 8,1..8,15). É assim que o
     * kernel numera partições de disco SCSI/USB.
     */
    fun doDisco(disco: String, vols: List<Volume>): List<Volume> {
        val m = DISCO.matchEntire(disco) ?: return emptyList()
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return vols.filter { v ->
            val d = v.dispositivo ?: return@filter false
            v.tipo != Tipo.EMULADO && d.first == major && d.second in (minor + 1)..(minor + 15)
        }
    }

    fun modo(volsDoDisco: List<Volume>): Modo {
        val pub = volsDoDisco.any { it.tipo == Tipo.PUBLICO }
        val pri = volsDoDisco.any { it.tipo == Tipo.PRIVADO }
        return when {
            pub && pri -> Modo.MISTO
            pri -> Modo.MEMORIA_TV
            pub -> Modo.COMUM
            else -> Modo.DESCONHECIDO
        }
    }

    /** Recomendação mostrada no topo da tela. */
    data class Recomendacao(val modo: Modo, val comumPorcento: Int, val motivo: Motivo)
    enum class Motivo { TV_FOLGADA, PEN_PEQUENO, PEN_LENTO, PEN_GRANDE, TV_APERTADA }

    private const val GB = 1024L * 1024 * 1024

    /**
     * [livreTv] e [tamanhoPen] em bytes; [velocidadeMBs] = gravação medida (null = não medida).
     * - Pen drive lento (< [ADOTAR_MIN_MBS]) ou pequeno (< 8 GB): comum.
     * - TV com folga (>= 4 GB livres): comum (não vale apagar o pen drive).
     * - Pen drive grande (>= 32 GB): misto meio a meio (apps + gravações/arquivos).
     * - Senão: memória da TV.
     */
    fun recomendar(livreTv: Long, tamanhoPen: Long, velocidadeMBs: Double?): Recomendacao = when {
        velocidadeMBs != null && velocidadeMBs < ADOTAR_MIN_MBS -> Recomendacao(Modo.COMUM, 100, Motivo.PEN_LENTO)
        tamanhoPen < 8 * GB -> Recomendacao(Modo.COMUM, 100, Motivo.PEN_PEQUENO)
        livreTv >= 4 * GB -> Recomendacao(Modo.COMUM, 100, Motivo.TV_FOLGADA)
        tamanhoPen >= 32 * GB -> Recomendacao(Modo.MISTO, 50, Motivo.PEN_GRANDE)
        else -> Recomendacao(Modo.MEMORIA_TV, 0, Motivo.TV_APERTADA)
    }

    /**
     * Gravação mínima (MB/s, medida com dd + fsync) para virar memória da TV ou misto. Porquê (Sala, 20/11/2026): um pen
     * drive que o Android mediu em r1572 w1001 s285 ms moveu um app a ~45 MB/min, o system_server caiu no meio e, com ele
     * adotado, a TV travou no boot. Abaixo disso a tela recusa adotar; só dá para usar como pen drive comum.
     */
    const val ADOTAR_MIN_MBS = 10.0

    /**
     * Pode formatar em [modo]? Comum sempre pode. Misto e memória da TV só com a velocidade medida e acima do mínimo;
     * [medivel] = false quando não há parte comum montada para medir (já adotado inteiro): aí a decisão já foi tomada antes.
     */
    fun podeFormatar(modo: Modo, velocidadeMBs: Double?, medivel: Boolean): Boolean = when (modo) {
        Modo.COMUM -> true
        Modo.MISTO, Modo.MEMORIA_TV -> !medivel || (velocidadeMBs != null && velocidadeMBs >= ADOTAR_MIN_MBS)
        Modo.DESCONHECIDO -> false
    }

    /**
     * `sm partition <disco> public|private|mixed <n>`. APAGA o pen drive: a tela só chama depois do aviso e do PIN.
     * No misto, [comumPorcento] é a parte que continua comum (vold: Disk::partitionMixed cria a pública primeiro com
     * essa porcentagem). Só 10..90 no misto, para nenhum lado ficar inútil.
     */
    fun comandoParticao(disco: String, modo: Modo, comumPorcento: Int = 50): String? {
        if (!DISCO.matches(disco)) return null
        return when (modo) {
            Modo.COMUM -> "sm partition $disco public"
            Modo.MEMORIA_TV -> "sm partition $disco private"
            Modo.MISTO -> if (comumPorcento in 10..90) "sm partition $disco mixed $comumPorcento" else null
            Modo.DESCONHECIDO -> null
        }
    }

    /** `sm partition` não imprime nada quando dá certo; qualquer texto com erro/exception é falha. */
    fun falhaParticao(saida: String?): String? {
        if (saida == null) return "shell"
        val t = linhas(saida)
        val erro = t.firstOrNull { it.contains("Exception") || it.contains("Error", ignoreCase = true) || it.startsWith("Failure") }
        return erro?.take(160)
    }

    /** `df -k`: caminho -> (total, livre) em bytes. Pula o cabeçalho e linhas quebradas. */
    fun df(saida: String): Map<String, Pair<Long, Long>> = linhas(saida).drop(1).mapNotNull { l ->
        val p = l.split(Regex("\\s+"))
        if (p.size < 6) return@mapNotNull null
        val total = p[1].toLongOrNull() ?: return@mapNotNull null
        val livre = p[3].toLongOrNull() ?: return@mapNotNull null
        p[5] to (total * 1024 to livre * 1024)
    }.toMap()

    private val UUID = Regex("^[0-9A-Fa-f-]{4,64}$")
    fun uuidSeguro(u: String) = UUID.matches(u)

    /** Só caminhos de volume montado ("/storage/8991-1F25"), sem nada que o shell interprete. */
    fun caminhoSeguro(c: String) = Regex("^/storage/[0-9A-Fa-f-]{4,64}$").matches(c)

    /** toybox dd: "33554432 bytes (32 M) copied, 2.512 s, 13 M/s". MB/s pelos bytes e segundos. */
    fun velocidadeDd(saida: String): Double? {
        val m = Regex("(\\d+) bytes .*copied, ([0-9.]+) s").find(saida) ?: return null
        val bytes = m.groupValues[1].toDoubleOrNull() ?: return null
        val seg = m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return bytes / 1048576.0 / seg
    }
}

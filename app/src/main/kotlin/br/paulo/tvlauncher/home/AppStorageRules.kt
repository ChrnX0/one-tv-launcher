package br.paulo.tvlauncher.home

/** Regras puras de AppStorage.kt (sem Android), testadas em AppStorageTest. */
object AppStorageRules {
    /** Para onde o menu oferece mover um app. */
    enum class Destino { NENHUM, EXTERNO, DISPOSITIVO }

    /** Pacote Android válido: só entra no comando do shell o que passa aqui (nada de espaço, `;` ou aspas). */
    private val PACOTE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    /** fsUuid de volume: FAT/exFAT "1234-ABCD" ou o UUID do volume adotado (hex com hífens). */
    private val UUID = Regex("^[0-9A-Fa-f-]{4,64}$")

    /**
     * Pode mover: nunca o próprio launcher (HOME, Device Owner, ADB embutido; se o pen drive sair, a TV ficaria sem
     * tela inicial), nunca app de fábrica (o Android recusa) e nunca os protegidos (teclado ativo, VPN fixa,
     * instalador, GMS: sem eles a TV quebra se o pen drive sair).
     */
    fun podeMover(pacote: String, nosso: String, sistema: Boolean, protegido: Boolean): Boolean =
        pacote != nosso && !sistema && !protegido && PACOTE.matches(pacote)

    /** App que já está fora sempre pode voltar; para ir, precisa existir um volume adotado montado. */
    fun destino(pode: Boolean, noExterno: Boolean, temAdotado: Boolean): Destino = when {
        !pode -> Destino.NENHUM
        noExterno -> Destino.DISPOSITIVO
        temAdotado -> Destino.EXTERNO
        else -> Destino.NENHUM
    }

    /**
     * Lê `sm list-volumes private`. Cada linha é "<id> <estado> <fsUuid>"; a memória interna é "private mounted
     * null" e um pen drive adotado é "private:8,3 mounted 5a1b...". Devolve os fsUuid dos adotados montados.
     */
    fun adotados(saida: String): List<String> = saida.split(Regex("\\r?\\n")).mapNotNull { linha ->
        val p = linha.trim().split(Regex("\\s+"))
        if (p.size < 3 || !p[0].startsWith("private:") || p[1] != "mounted") null
        else p[2].takeIf { UUID.matches(it) }
    }

    /** O comando, ou null se algo não é seguro de pôr no shell. [uuid] null = de volta ao dispositivo. */
    fun comando(pacote: String, uuid: String?): String? {
        if (!PACOTE.matches(pacote)) return null
        if (uuid != null && !UUID.matches(uuid)) return null
        return "pm move-package $pacote ${uuid ?: "internal"}"
    }

    /** null = deu certo; senão, o motivo curto que o Android deu (ex.: "Failure [MOVE_FAILED_INSUFFICIENT_STORAGE]"). */
    fun falha(saida: String?): String? {
        if (saida == null) return "shell"
        val linhas = saida.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (linhas.any { it == "Success" }) return null
        return linhas.lastOrNull()?.take(120) ?: "?"
    }

    /**
     * App grande para mudar de lugar: pede confirmação com aviso. Porquê (Sala, 20/11/2026): mover o RetroArch (904 MB,
     * ~100 partes) para um pen drive lento derrubou o system_server no meio da cópia. Tamanho = app + dados + cache.
     */
    const val GRANDE_BYTES = 300L * 1024 * 1024
    const val MUITAS_PARTES = 20
    fun arriscado(bytes: Long, partes: Int): Boolean = bytes >= GRANDE_BYTES || partes >= MUITAS_PARTES
}

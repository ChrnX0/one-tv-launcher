package br.paulo.tvlauncher.observer

/**
 * Toda falha em uma frase e um botão (elevação 2.3, onda 1, 17/11/2026): o catálogo, puro (FalhasTest). A parte que
 * olha a TV e mostra é o FalhasTv.
 *
 * Cada estado tem: a frase (o que aconteceu, na língua de casa), a causa provável, e UM botão que conserta ou leva
 * ao lugar do conserto. Nenhum mostra código de erro cru (HTTP 403, errno, nome de exceção): [semCodigo] confere.
 *
 * Ordem = prioridade (o que impede de ver TV primeiro).
 */
object Falhas {
    enum class Tipo { CANAL_CAIU, LISTA_VENCEU, RELOGIO_ERRADO, DISCO_CHEIO, SHELL_PARADO, TUNEL_SEM_LOGIN, BACKUP_FALHOU }

    /** O que o botão faz. */
    enum class Conserto { OUTRO_CANAL, ABRIR_CONTA, ACERTAR_RELOGIO, LIBERAR_ESPACO, RELIGAR_SHELL, ENTRAR_TUNEL, REFAZER_BACKUP }

    fun conserto(t: Tipo): Conserto = when (t) {
        Tipo.CANAL_CAIU -> Conserto.OUTRO_CANAL
        Tipo.LISTA_VENCEU -> Conserto.ABRIR_CONTA
        Tipo.RELOGIO_ERRADO -> Conserto.ACERTAR_RELOGIO
        Tipo.DISCO_CHEIO -> Conserto.LIBERAR_ESPACO
        Tipo.SHELL_PARADO -> Conserto.RELIGAR_SHELL
        Tipo.TUNEL_SEM_LOGIN -> Conserto.ENTRAR_TUNEL
        Tipo.BACKUP_FALHOU -> Conserto.REFAZER_BACKUP
    }

    /** Abaixo disso o Android começa a falhar em gravar (guia, gravações, atualização). */
    const val DISCO_MINIMO_MB = 300L
    /** Relógio mais longe que isso da hora da internet: certificados e guia erram. */
    const val RELOGIO_FOLGA_MS = 5 * 60_000L
    /** Nenhum backup certo nesse tempo, com o backup da noite ligado: falhou. */
    const val BACKUP_ATRASO_MS = 2 * 24 * 3_600_000L
    /** Antes disso o relógio com certeza está errado (TV que voltou a 1970 ou à data de fábrica). */
    const val ANO_MINIMO_MS = 1_767_225_600_000L   // 01/01/2026 00:00 UTC

    /** O que a TV sabe agora. null = não sei / não se aplica (nunca vira falha). */
    data class Sinais(
        val agora: Long,
        val livreMb: Long? = null,
        val horaDaInternet: Long? = null,
        val certificadoForaDaData: Boolean = false,
        val listasVencidas: Int = 0,
        val shellOk: Boolean? = null,
        val tunelFaltaLogin: Boolean = false,
        val backupLigado: Boolean = false,
        val ultimoBackupOk: Long = 0,
        val backupDesde: Long = 0,
    )

    /** Os estados de falha de agora (os do player, canal e lista, vêm por [doPlayer]). */
    fun detectar(s: Sinais): List<Tipo> = buildList {
        if (s.listasVencidas > 0) add(Tipo.LISTA_VENCEU)
        if (relogioErrado(s)) add(Tipo.RELOGIO_ERRADO)
        if (s.livreMb != null && s.livreMb < DISCO_MINIMO_MB) add(Tipo.DISCO_CHEIO)
        if (s.shellOk == false) add(Tipo.SHELL_PARADO)
        if (s.tunelFaltaLogin) add(Tipo.TUNEL_SEM_LOGIN)
        if (s.backupLigado && s.agora - maxOf(s.ultimoBackupOk, s.backupDesde) > BACKUP_ATRASO_MS) add(Tipo.BACKUP_FALHOU)
    }.sortedBy { it.ordinal }

    fun relogioErrado(s: Sinais): Boolean =
        s.agora < ANO_MINIMO_MS || s.certificadoForaDaData ||
            (s.horaDaInternet != null && kotlin.math.abs(s.agora - s.horaDaInternet) > RELOGIO_FOLGA_MS)

    /**
     * O canal não tocou nem na reserva. Lista do canal vencida, ou o servidor recusando (401/403 = conta), é a LISTA;
     * o resto (404, 5xx, sem resposta, arquivo ruim) é o CANAL que caiu no provedor.
     */
    fun doPlayer(codigoHttp: Int?, listaVencida: Boolean): Tipo =
        if (listaVencida || codigoHttp == 401 || codigoHttp == 403 || codigoHttp == 458 || codigoHttp == 512) Tipo.LISTA_VENCEU
        else Tipo.CANAL_CAIU

    /** Data "Date:" do HTTP (RFC 1123), em ms; null se não ler. */
    fun dataHttp(cabecalho: String?): Long? = runCatching {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT")
        f.parse(cabecalho ?: return null)?.time
    }.getOrNull()

    private val CODIGO_CRU = Regex("""\b(?:HTTP|errno|E[A-Z]{3,}|[A-Za-z]+Exception|ERR_[A-Z_]+|0x[0-9a-fA-F]+)\b|\b[45]\d\d\b""")

    /** A frase não tem código de erro cru (vale para o que a pessoa lê). */
    fun semCodigo(texto: String): Boolean = !CODIGO_CRU.containsMatchIn(texto)
}

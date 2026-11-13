package br.paulo.tvlauncher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * As contas do backup noturno cifrado (BackupNoturno.kt), sem nada de Android: assim dá para testar na JVM
 * (BackupNoturnoTest). Quando roda, quando tenta de novo, que nome o arquivo leva e quais cópias velhas saem.
 */
object BackupNoturnoRegras {
    /** Quantas cópias ficam em cada lugar (TV, NAS, pendrive). Uma por noite = uma semana para trás. */
    const val MANTER = 7
    /** Horário padrão: 3:30, quando quase nunca tem alguém vendo TV. Guardado em minutos desde a meia-noite. */
    const val PADRAO_MINUTO_DO_DIA = 3 * 60 + 30
    /** TV em uso na hora marcada: tenta de novo a cada [ESPERA_MIN] minutos, no máximo [MAX_TENTATIVAS] vezes. */
    const val ESPERA_MIN = 30
    const val MAX_TENTATIVAS = 4
    /** Tela acesa conta como "ociosa" só se ninguém apertou o controle por este tempo. */
    const val OCIOSO_MIN = 30L
    /** Sem cópia boa há mais que isto (TV desligada da tomada nas noites, por exemplo): tenta logo, sem esperar a noite. */
    const val ATRASO_MS = 48L * 60 * 60 * 1000
    const val PASTA_EXTERNA = "OneTV-backup"

    private val NOME = Regex("""onetv-backup-\d{8}\.json""")

    /** Nome da cópia do dia: a data vai em ordem (ano primeiro), então ordenar pelo nome = ordenar pelo tempo. */
    fun nomeDoDia(quando: Long, fuso: TimeZone = TimeZone.getDefault()): String =
        "onetv-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = fuso }.format(Date(quando)) + ".json"

    /** Só os arquivos com o nosso nome exato: a rotação nunca apaga outra coisa da pasta (nem no NAS, nem no pendrive). */
    fun nomeValido(nome: String) = NOME.matches(nome)

    /** Das cópias em [nomes], quais apagar para sobrarem as [manter] mais novas. Outros arquivos não entram na conta. */
    fun quaisApagar(nomes: Collection<String>, manter: Int = MANTER): List<String> =
        nomes.filter { nomeValido(it) }.sortedDescending().drop(manter)

    /** A próxima vez do horário [minutoDoDia] DEPOIS de [agora] (hoje, se ainda não passou; senão amanhã). */
    fun proximaVez(agora: Long, minutoDoDia: Int, fuso: TimeZone = TimeZone.getDefault()): Long {
        val m = minutoDoDia.coerceIn(0, 24 * 60 - 1)
        val c = Calendar.getInstance(fuso).apply {
            timeInMillis = agora
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= agora) c.add(Calendar.DAY_OF_MONTH, 1)   // add (e não +24 h): respeita horário de verão
        return c.timeInMillis
    }

    /** Depois de uma tentativa (feita, adiada ou desistida), o "atrasado" não adianta de novo por este tempo. */
    const val INTERVALO_ADIANTAR_MS = 24L * 60 * 60 * 1000

    /**
     * Quando agendar: a próxima noite, ou daqui a 15 min se a última cópia boa está atrasada ([atrasado]).
     * A tentativa adiantada também passa pela regra do ócio: nunca roda em cima de quem está vendo.
     * [ultimoAlarme] = quando o alarme rodou pela última vez (qualquer resultado). Se foi há menos de 24 h, NÃO adianta:
     * o adiantamento vale uma vez por dia; sem isso, TV em uso o dia todo virava um laço de 15 min + 4 adiamentos
     * com uma falha no registro a cada ~2 h.
     */
    fun quandoAgendar(agora: Long, minutoDoDia: Int, ultimoOk: Long, ligadoDesde: Long, fuso: TimeZone = TimeZone.getDefault(),
                      ultimoAlarme: Long = 0L): Long {
        val noite = proximaVez(agora, minutoDoDia, fuso)
        val adiantado = agora + 15 * 60_000L
        val podeAdiantar = ultimoAlarme <= 0L || agora - ultimoAlarme >= INTERVALO_ADIANTAR_MS
        return if (podeAdiantar && atrasado(ultimoOk, ligadoDesde, agora) && adiantado < noite) adiantado else noite
    }

    /** Um adiamento guardado (TV em uso) ainda vale se cai no futuro: o agendar() normal não o pode sobrescrever. */
    fun adiamentoValido(pendenteEm: Long, agora: Long): Boolean = pendenteEm > agora

    /**
     * Atrasado = ligado há mais de 48 h e sem cópia boa nesse tempo. Conta a partir de quando foi ligado, para não
     * disparar logo que o dono liga (ele tem a linha "Fazer agora" para isso).
     */
    fun atrasado(ultimoOk: Long, ligadoDesde: Long, agora: Long): Boolean {
        val referencia = maxOf(ultimoOk, ligadoDesde)
        return referencia > 0 && agora - referencia > ATRASO_MS
    }

    /**
     * A TV está livre para o backup? Tela apagada = sim. Com o protetor de tela na frente = sim. Tela acesa sem
     * protetor = só se NADA está tocando (filme/série não aperta tecla por horas: [tocandoMidia] = AudioManager
     * isMusicActive) e ninguém apertou o controle há [OCIOSO_MIN] min; e se não dá para ver as teclas (acessibilidade
     * desligada), não arrisca. O backup é leve, mas o PBKDF2 ocupa a CPU de 32 bits por 1 a 2 s e o envio usa a rede.
     */
    fun tvOciosa(telaAcesa: Boolean, protetorNaTela: Boolean, minutosSemTecla: Long, vejoTeclas: Boolean,
                 tocandoMidia: Boolean = false): Boolean =
        !telaAcesa || protetorNaTela || (!tocandoMidia && vejoTeclas && minutosSemTecla >= OCIOSO_MIN)

    /** Depois da tentativa [tentativa] (0 = a da hora marcada) adiada, quando tentar de novo; null = desiste por hoje. */
    fun proximaTentativa(agora: Long, tentativa: Int): Long? =
        if (tentativa + 1 > MAX_TENTATIVAS) null else agora + ESPERA_MIN * 60_000L

    /** "03:30" */
    fun hhmm(minutoDoDia: Int): String = "%02d:%02d".format(minutoDoDia / 60, minutoDoDia % 60)

    /** Anda o horário em [delta] minutos, dando a volta no relógio. */
    fun mudarHorario(minutoDoDia: Int, delta: Int): Int = Math.floorMod(minutoDoDia + delta, 24 * 60)

    // ---------------------------------------------------------------- destino da cópia extra

    /** Como o destino fica guardado: "" = só na TV, "pendrive", ou "smb:<id da conta de mídia>". */
    const val SO_TV = ""
    const val PENDRIVE = "pendrive"
    private const val PREFIXO_SMB = "smb:"

    fun destinoSmb(id: String) = PREFIXO_SMB + id
    /** O id da conta SMB do destino, ou null se o destino não é um NAS. */
    fun idSmb(destino: String): String? = destino.takeIf { it.startsWith(PREFIXO_SMB) }?.removePrefix(PREFIXO_SMB)?.ifEmpty { null }

    /** OK na linha "Cópia extra": só na TV -> cada pasta SMB cadastrada -> pendrive -> só na TV. */
    fun proximoDestino(atual: String, idsSmb: List<String>): String {
        val opcoes = listOf(SO_TV) + idsSmb.map { destinoSmb(it) } + PENDRIVE
        val i = opcoes.indexOf(atual)
        return opcoes[(i + 1) % opcoes.size]   // destino que sumiu (conta apagada): i = -1, volta ao começo
    }
}

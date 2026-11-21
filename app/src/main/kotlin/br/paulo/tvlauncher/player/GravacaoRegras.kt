package br.paulo.tvlauncher.player

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Gravação da TV ao vivo (14/11/2026): as regras puras, sem Android, com teste JVM (GravacaoRegrasTest).
 * Estudo e decisões em docs/gravacao.md.
 *
 *  - Nome do arquivo: data, hora, canal e programa, sem os caracteres que o FAT32 do pendrive recusa.
 *  - Pedaços de no máximo 1 hora (e nunca perto de 4 GB, o limite de arquivo do FAT32).
 *  - Folga: começa 2 min antes e termina 5 min depois do horário do guia (o guia costuma atrasar).
 *  - Agendamentos: conflito (a lista só tem 1 conexão: dois ao mesmo tempo é impossível) e "toda semana".
 *  - Espaço: para quando não cabem mais 10 minutos no destino.
 */
object GravacaoRegras {
    const val PASTA = "OneTV-gravacoes"
    const val EXTENSAO = ".ts"
    const val PEDACO_MS = 60 * 60_000L
    const val PEDACO_MAX_BYTES = 3_800_000_000L          // abaixo dos 4 GiB do FAT32, com folga
    const val FOLGA_ANTES_MS = 2 * 60_000L
    const val FOLGA_DEPOIS_MS = 5 * 60_000L
    const val RESERVA_MS = 10 * 60_000L                  // precisa caber mais 10 min para continuar
    const val BITRATE_PADRAO_BPS = 8_000_000L            // enquanto não mediu: o pior caso (Full HD)
    // A conexão caiu: tenta de novo até 8 vezes seguidas, esperando 2, 4, 8, 16 e depois 30 s (~2 min no total).
    // Medido na Sala (15/11/2026): a lista derrubou o canal aos 90 s e respondeu HTTP 502 por mais de 6 s; com 3
    // tentativas (2 + 4 s) a gravação desistia.
    const val TENTATIVAS = 8
    fun espera(falhas: Int): Long = minOf(2000L shl (falhas - 1).coerceIn(0, 10), 30_000L)
    const val SEMANA_MS = 7 * 24 * 60 * 60_000L
    const val MAX_AGENDADAS = 50

    // ---------------------------------------------------------------- nomes

    /** Tira o que o FAT32/exFAT/NTFS recusam (\ / : * ? " < > |), controles e pontos/espaços nas pontas. */
    fun limpar(s: String, max: Int = 60): String {
        val t = s.map { if (it.code < 32 || it in "\\/:*?\"<>|") ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return t.take(max).trim().trim('.', ' ').ifEmpty { "gravacao" }
    }

    /** "2026-11-14 2030 Globo - Jornal Nacional.ts"; a partir da parte 2: "... (parte 2).ts". */
    fun nomeArquivo(inicioMs: Long, canal: String, programa: String?, parte: Int, fuso: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(fuso).apply { timeInMillis = inicioMs }
        val data = "%04d-%02d-%02d %02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val titulo = if (programa.isNullOrBlank()) limpar(canal) else limpar(canal, 30) + " - " + limpar(programa, 60)
        return "$data $titulo" + (if (parte > 1) " (parte $parte)" else "") + EXTENSAO
    }

    /** Arquivos de uma mesma gravação, na ordem das partes (para o player emendar). */
    fun partes(nomes: Collection<String>, primeira: String): List<String> {
        val base = primeira.removeSuffix(EXTENSAO)
        val re = Regex(Regex.escape(base) + " \\(parte (\\d+)\\)" + Regex.escape(EXTENSAO))
        val outras = nomes.mapNotNull { n -> re.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()?.let { it to n } }.sortedBy { it.first }
        return (if (primeira in nomes) listOf(primeira) else emptyList()) + outras.map { it.second }
    }

    /** O contrário de [nomeArquivo]: (início, "Globo - Jornal Nacional"). Nome de outro jeito: (null, nome sem .ts). */
    fun lerNome(nome: String, fuso: TimeZone = TimeZone.getDefault()): Pair<Long?, String> {
        val base = nome.removeSuffix(EXTENSAO).replace(Regex(" \\(parte \\d+\\)$"), "")
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2})(\\d{2}) (.+)$").matchEntire(base) ?: return null to base
        val (a, me, d, h, mi, t) = m.destructured
        val c = Calendar.getInstance(fuso).apply { clear(); set(a.toInt(), me.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
        return c.timeInMillis to t
    }

    /** Agrupa os arquivos da pasta em gravações (a parte 1 e as seguintes), a mais nova primeiro. */
    fun agrupar(nomes: Collection<String>): List<List<String>> {
        val primeiras = nomes.filter { it.endsWith(EXTENSAO) && !Regex(" \\(parte \\d+\\)\\.ts$").containsMatchIn(it) }
        return primeiras.sortedDescending().map { partes(nomes, it) }
    }

    /** Começar um pedaço novo: passou 1 hora ou o arquivo chegou perto do limite do FAT32. */
    fun novoPedaco(msNoPedaco: Long, bytesNoPedaco: Long) = msNoPedaco >= PEDACO_MS || bytesNoPedaco >= PEDACO_MAX_BYTES

    // ---------------------------------------------------------------- espaço

    /** Bitrate medido (bits/s); antes de 30 s de amostra, o padrão (o começo do fluxo vem em rajada). */
    fun bitrate(bytes: Long, ms: Long): Long = if (ms < 30_000 || bytes <= 0) BITRATE_PADRAO_BPS else bytes * 8 * 1000 / ms

    /** Bytes que [ms] de gravação ocupam a [bps]. */
    fun bytesPara(ms: Long, bps: Long): Long = bps / 8 * ms / 1000

    fun cabe(livreBytes: Long, bps: Long) = livreBytes >= bytesPara(RESERVA_MS, bps)

    /** Horas que cabem no espaço livre (para "cabem ~18 h"), arredondado para baixo. */
    fun horasQueCabem(livreBytes: Long, bps: Long = 5_000_000L): Long = if (bps <= 0) 0 else livreBytes / bytesPara(60 * 60_000L, bps)

    // ---------------------------------------------------------------- agendamentos

    data class Agendada(
        val id: String,
        val canal: String,          // id do canal (o mesmo do player)
        val canalNome: String,
        val programa: String,
        val inicio: Long,           // horário do guia, sem folga
        val fim: Long,
        val semanal: Boolean = false,
        /** Onde gravar (GravacaoDestino.Opcao.id); "" = o automático (pendrive, senão a pasta da rede de reserva). */
        val destino: String = "",
    ) {
        val comecaEm get() = inicio - FOLGA_ANTES_MS
        val terminaEm get() = fim + FOLGA_DEPOIS_MS
    }

    fun valida(a: Agendada) = a.id.isNotEmpty() && a.canal.isNotEmpty() && a.fim > a.inicio && a.fim - a.inicio <= 12 * 60 * 60_000L

    /** As que se sobrepõem a [nova] (com as folgas): com 1 conexão, não dá para gravar as duas. Mesmo canal também conta. */
    fun conflitos(nova: Agendada, outras: List<Agendada>): List<Agendada> =
        outras.filter { it.id != nova.id && it.comecaEm < nova.terminaEm && nova.comecaEm < it.terminaEm }

    /** "Toda semana": depois de gravar (ou se o horário já passou), a mesma hora 7 dias depois. */
    fun proxima(a: Agendada, agora: Long): Agendada? {
        if (a.terminaEm > agora) return a
        if (!a.semanal) return null
        val semanas = (agora - a.terminaEm) / SEMANA_MS + 1
        return a.copy(inicio = a.inicio + semanas * SEMANA_MS, fim = a.fim + semanas * SEMANA_MS)
    }

    /** A que deve estar gravando agora (a de início mais antigo, se houver mais de uma por engano). */
    fun gravandoAgora(lista: List<Agendada>, agora: Long): Agendada? =
        lista.filter { it.comecaEm <= agora && agora < it.terminaEm }.minByOrNull { it.comecaEm }

    /** A próxima a começar (para o alarme). */
    fun proximaAComecar(lista: List<Agendada>, agora: Long): Agendada? = lista.filter { it.comecaEm > agora }.minByOrNull { it.comecaEm }

    fun paraJson(lista: List<Agendada>): String = JSONArray().apply {
        lista.forEach { put(JSONObject().put("id", it.id).put("canal", it.canal).put("canalNome", it.canalNome).put("programa", it.programa)
            .put("inicio", it.inicio).put("fim", it.fim).put("semanal", it.semanal).put("destino", it.destino)) }
    }.toString()

    fun deJson(texto: String?): List<Agendada> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Agendada(o.optString("id"), o.optString("canal"), o.optString("canalNome"), o.optString("programa"),
                o.optLong("inicio"), o.optLong("fim"), o.optBoolean("semanal"), o.optString("destino")).takeIf { valida(it) }
        }.take(MAX_AGENDADAS)
    }

    /** A pergunta "Gravar onde?" com o lugar da última vez em primeiro (os outros na ordem de sempre: pendrives, rede). */
    fun <T> ordenarOpcoes(opcoes: List<T>, ultimo: String, id: (T) -> String): List<T> {
        val i = opcoes.indexOfFirst { id(it) == ultimo }
        return if (i <= 0) opcoes else listOf(opcoes[i]) + opcoes.filterIndexed { k, _ -> k != i }
    }

    /** Só pergunta quando há escolha de verdade. */
    fun perguntarOnde(quantas: Int) = quantas >= 2
}

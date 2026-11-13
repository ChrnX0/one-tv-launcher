package br.paulo.tvlauncher

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * TV de volta (11/11/2026, ideia vista no 1 Play): assistir um programa que já passou, ou o que está passando desde o
 * começo, usando o "arquivo" que o servidor Xtream guarda de alguns canais (tv_archive). Funciona com 1 conexão: o
 * programa antigo TOMA O LUGAR do ao vivo, nunca abre um segundo vídeo.
 *
 * Só lógica pura (sem Android nem rede), testada em TvDeVoltaTest. Quem baixa e toca é a PlayerActivity.
 *
 * Protocolo (o mesmo de todo painel Xtream):
 *   lista de programas: player_api.php?...&action=get_simple_data_table&stream_id=N  (título e descrição em Base64)
 *   vídeo do passado:   /timeshift/<usuario>/<senha>/<minutos>/<AAAA-MM-DD:HH-mm>/<N>.ts
 * O horário do endereço é no FUSO DO SERVIDOR (server_info.timezone do login), não no da TV.
 */
object TvDeVolta {
    /** De onde vem o canal: a conta e o número dele no servidor. */
    data class Origem(val conta: Xtream.Conta, val streamId: String)

    data class Programa(val inicio: Long, val fim: Long, val titulo: String, val descricao: String, val temArquivo: Boolean)

    /** Teto de dias que aceitamos do servidor (alguns mandam 30, 60...). Mais que isso a lista fica enorme e inútil. */
    const val MAX_DIAS = 14

    /**
     * Acha a conta e o número do canal pelo endereço do vídeo. Vale para conta Xtream e para lista M3U de um painel
     * Xtream (o link get.php que os vendedores mandam), que usam um destes dois formatos:
     *   http://servidor:porta/live/usuario/senha/123.ts      http://servidor:porta/usuario/senha/123
     * Qualquer outro formato = null (aí o canal não tem TV de volta).
     */
    fun origem(url: String): Origem? {
        val semQuery = url.trim().substringBefore('?').substringBefore('#')
        val esquema = semQuery.substringBefore("://", "")
        if (esquema != "http" && esquema != "https") return null
        val resto = semQuery.substringAfter("://")
        val host = resto.substringBefore('/')
        if (host.isEmpty()) return null
        var partes = resto.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        if (partes.size == 4 && partes[0] == "live") partes = partes.drop(1)
        if (partes.size != 3) return null
        val id = partes[2].substringBeforeLast('.')
        if (!id.all { it.isDigit() } || id.isEmpty()) return null
        fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        return Origem(Xtream.Conta("$esquema://$host", dec(partes[0]), dec(partes[1])), id)
    }

    /** tv_archive_duration (ou catchup-days da lista M3U) vira dias aceitos: 0 = canal sem TV de volta. */
    fun dias(valor: Any?): Int {
        val n = when (valor) {
            is Number -> valor.toInt()
            is String -> valor.trim().toIntOrNull() ?: 0
            else -> 0
        }
        return n.coerceIn(0, MAX_DIAS)
    }

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")

    fun urlTabela(o: Origem): String = Xtream.urlApi(o.conta, "get_simple_data_table", mapOf("stream_id" to o.streamId))

    /** O fuso que o servidor informa no login (server_info.timezone). Desconhecido = o da TV, que costuma ser o mesmo. */
    fun fuso(jsonLogin: String?, padrao: TimeZone = TimeZone.getDefault()): TimeZone {
        val nome = runCatching { JSONObject(jsonLogin ?: return padrao).optJSONObject("server_info")?.optString("timezone") }
            .getOrNull()?.trim().orEmpty()
        if (nome.isEmpty() || nome == "null") return padrao
        val tz = TimeZone.getTimeZone(nome)
        // getTimeZone devolve "GMT" quando não conhece o nome: aí fica o padrão
        return if (tz.id == "GMT" && !nome.equals("GMT", true) && !nome.equals("UTC", true)) padrao else tz
    }

    /**
     * Endereço do programa que já passou. A duração vai em minutos inteiros, arredondada para cima (um programa de
     * 59,5 min não pode perder o fim). Programa que ainda está passando: pede até o fim previsto, o servidor entrega
     * o que já existe e o player segue crescendo junto.
     */
    fun urlArquivo(o: Origem, inicioMs: Long, fimMs: Long, fusoServidor: TimeZone): String {
        val minutos = ((fimMs - inicioMs + 59_999) / 60_000).coerceAtLeast(1)
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = fusoServidor }
        return "${o.conta.servidor}/timeshift/${cod(o.conta.usuario)}/${cod(o.conta.senha)}/$minutos/${f.format(inicioMs)}/${cod(o.streamId)}.ts"
    }

    private fun base64(s: String): String {
        if (s.isEmpty() || s == "null") return ""
        return runCatching { String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrDefault(s).trim()
    }

    /** Lê a resposta do get_simple_data_table. Programas com início/fim inválido saem. */
    fun lerTabela(json: String): List<Programa> {
        val a = runCatching { JSONObject(json).optJSONArray("epg_listings") }.getOrNull() ?: return emptyList()
        fun seg(o: JSONObject, k: String): Long? = when (val v = o.opt(k)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ini = seg(o, "start_timestamp") ?: return@mapNotNull null
            val fim = seg(o, "stop_timestamp") ?: return@mapNotNull null
            if (fim <= ini) return@mapNotNull null
            Programa(ini * 1000, fim * 1000, base64(o.optString("title")).take(120).ifEmpty { "Sem título" },
                base64(o.optString("description")).take(Xmltv.MAX_DESCRICAO), o.optString("has_archive", "0").trim() == "1")
        }.distinctBy { it.inicio }.sortedBy { it.inicio }
    }

    /**
     * O que dá para assistir: programas que já começaram, dentro dos `dias` do canal, com arquivo. O que está passando
     * entra mesmo sem a marca (é o "começar do início"). Do mais novo para o mais antigo, que é o que se procura.
     */
    fun assistiveis(lista: List<Programa>, agora: Long, dias: Int): List<Programa> {
        val desde = agora - dias.coerceIn(1, MAX_DIAS) * 86_400_000L
        return lista.filter { p ->
            p.inicio < agora && p.inicio >= desde && (p.temArquivo || p.fim > agora)
        }.sortedByDescending { it.inicio }
    }

    /** Está passando agora? (o 1º da lista, quando for, vira "Começar do início".) */
    fun passandoAgora(p: Programa, agora: Long) = p.inicio <= agora && agora < p.fim

    /**
     * Acabou o programa: o próximo que tem arquivo e já começou. null = o próximo é o ao vivo (volta para ele).
     */
    fun seguinte(lista: List<Programa>, atual: Programa, agora: Long): Programa? =
        lista.filter { it.inicio >= atual.fim && it.inicio < agora && (it.temArquivo || it.fim > agora) }.minByOrNull { it.inicio }
}

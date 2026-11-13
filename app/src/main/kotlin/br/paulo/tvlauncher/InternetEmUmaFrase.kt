package br.paulo.tvlauncher

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "A internet caiu? Em uma frase" (10/11/2026). Junta as medições de agora (Wi-Fi, conexão sem DNS, DNS sem cache,
 * página de teste do Google), o que o roteador UniFi acha da internet (se configurado) e o histórico de rede, e
 * responde em português simples, com a causa mais provável. Ex.: "O Wi-Fi está ok, mas a internet do provedor caiu
 * às 18:12; o roteador também vê a internet fora (há 6 min)."
 *
 * Só lógica, sem nada do Android, para testar no PC (InternetEmUmaFraseTest). Quem mede é [DiagnosticoInternet].
 * A ordem das perguntas segue a ordem em que um problema causa o outro: sem Wi-Fi nada funciona; sem internet o DNS
 * também falha; com internet e DNS, sobra a página de teste (bloqueio) e a qualidade (lentidão, quedas recentes).
 */
object InternetEmUmaFrase {
    enum class Causa { OK, QUEDA_RECENTE, LENTA, VPN_DESLIGADA, SEM_WIFI, ROTEADOR, PROVEDOR, ENTRE_TV_E_ROTEADOR, VPN, DNS, BLOQUEIO }

    /** O que o roteador diz (stat/health da API antiga do UniFi). Status: "ok", "error", "warning" ou null. */
    class Roteador(val wan: String?, val internet: String?, val latenciaMs: Int?) {
        /** O roteador perdeu o provedor: o cabo/modem ("wan") ou a internet em si ("www"). */
        val internetFora get() = wan == "error" || internet == "error"
        val internetOk get() = !internetFora && (internet == "ok" || wan == "ok")
    }

    /**
     * Medições de agora. [tcpMs]: conexão até 1.1.1.1:443 (internet SEM depender de DNS); [dnsMs]: resolução que pula
     * o cache; [httpCodigo]: resposta da página de teste do Google (204 = ok); null em qualquer uma = falhou.
     * [dbm] 0 = sem informação (cabo). [roteador] null = não consultado; [roteadorMudo] = configurado e não respondeu.
     */
    class Medicoes(
        val agora: Long,
        val conectada: Boolean,
        val dbm: Int = 0,
        val tcpMs: Int? = null,
        val dnsMs: Long? = null,
        val dnsNome: String? = null,
        val httpCodigo: Int? = null,
        val httpMs: Long? = null,
        val vpnFixada: Boolean = false,
        val vpnDePe: Boolean = false,
        val roteador: Roteador? = null,
        val roteadorMudo: Boolean = false,
        val historico: List<ResumoRede.Amostra> = emptyList(),
    )

    /** [nivel]: 0 = ok, 1 = atenção, 2 = falha (as mesmas cores do Diagnóstico). [sinais]: o que foi medido, curto. */
    class Resultado(val frase: String, val causa: Causa, val nivel: Int, val sinais: List<String>) {
        fun json(): JSONObject = JSONObject().put("frase", frase).put("causa", causa.name.lowercase()).put("nivel", nivel)
            .put("sinais", org.json.JSONArray(sinais))
    }

    /** Sinal abaixo disso (dBm) já trava vídeo. Resposta acima disso = "devagar". */
    const val SINAL_FRACO_DBM = -75
    const val TCP_LENTO_MS = 300
    const val HTTP_LENTO_MS = 2000L
    private const val DIA_MS = 24 * 3_600_000L
    /** Medido pelo menos isso das últimas 24 h para dizer "sem quedas nas últimas 24 h". */
    private const val COBERTURA_DIA_MS = 20 * 3_600_000L

    fun explicar(m: Medicoes, zona: ZoneId = ZoneId.systemDefault()): Resultado {
        val sinais = sinais(m)
        fun r(frase: String, causa: Causa, nivel: Int) = Resultado(frase, causa, nivel, sinais)
        val internetOk = m.tcpMs != null || m.httpCodigo == 204
        val sinalFraco = m.dbm in -126 until SINAL_FRACO_DBM
        val h = m.historico.sortedBy { it.t }

        // 1) Sem Wi-Fi (nem cabo): nada mais importa.
        if (!m.conectada) {
            val desde = inicioDaFalha(h, m.agora) { it.dbm == 0 && !it.internet }
            return r("A TV está sem Wi-Fi${desde?.let { " " + desde(it, m.agora, zona) } ?: ""}. " +
                "Veja se o roteador está ligado ou se o nome e a senha da rede mudaram.", Causa.SEM_WIFI, 2)
        }

        // 2) No Wi-Fi, mas a internet não chega (nem a conexão sem DNS nem a página de teste).
        if (!internetOk) {
            val desde = inicioDaFalha(h, m.agora) { !it.internet }
            val ro = m.roteador
            return when {
                ro != null && ro.internetFora -> {
                    val oque = if (ro.wan == "error") "o roteador perdeu a ligação com o modem do provedor" else "o roteador também vê a internet fora"
                    val ha = desde?.let { " (há ${duracao(m.agora - it)})" } ?: ""
                    r("O Wi-Fi está ok, mas a internet do provedor caiu${desde?.let { " " + quando(it, m.agora, zona) } ?: ""}; $oque$ha. " +
                        "Não é a TV: é esperar o provedor ou reiniciar o modem.", Causa.PROVEDOR, 2)
                }
                ro != null && ro.internetOk && m.vpnDePe -> r("O roteador tem internet, mas a TV não passa${desde?.let { " " + desde(it, m.agora, zona) } ?: ""}: " +
                    "a VPN deve estar travada. Desligar e ligar a VPN costuma resolver.", Causa.VPN, 2)
                ro != null && ro.internetOk -> r("O roteador tem internet, mas a TV não chega nela${desde?.let { " " + desde(it, m.agora, zona) } ?: ""}: " +
                    "o problema está entre a TV e o roteador" + (if (sinalFraco) " (o sinal do Wi-Fi está fraco, ${m.dbm} dBm)." else ".") +
                    " Reconectar a TV ao Wi-Fi costuma resolver.", Causa.ENTRE_TV_E_ROTEADOR, 2)
                m.roteadorMudo -> r("A TV está no Wi-Fi, mas nem o roteador nem a internet respondem${desde?.let { " " + desde(it, m.agora, zona) } ?: ""}: " +
                    "o roteador pode ter travado. Desligar e ligar o roteador costuma resolver.", Causa.ROTEADOR, 2)
                else -> {
                    val ha = desde?.let { " " + quando(it, m.agora, zona) + " (há ${duracao(m.agora - it)})" } ?: ""
                    r("O Wi-Fi está ok, mas a internet não chega$ha; deve ser o provedor ou o roteador" +
                        (if (m.vpnDePe) " (a VPN ligada também pode ser a culpada)." else "."), Causa.PROVEDOR, 2)
                }
            }
        }

        // 3) A internet chega, mas os nomes não resolvem: para os apps é o mesmo que "sem internet".
        if (m.dnsMs == null) {
            val desde = inicioDaFalha(h, m.agora) { it.internet && it.dnsMs < 0 }
            val qual = m.dnsNome?.let { " ($it)" } ?: ""
            return r("A internet chega, mas o DNS$qual não responde${desde?.let { " " + desde(it, m.agora, zona) } ?: ""}: " +
                "os apps não acham os servidores. Trocar o DNS para Automático resolve.", Causa.DNS, 2)
        }

        // 4) Internet e DNS ok, mas a página de teste não abre: alguém no caminho está barrando.
        if (m.httpCodigo != 204) {
            val como = if (m.httpCodigo == null) "não abriu" else "respondeu diferente (código ${m.httpCodigo})"
            return r("A internet e o DNS respondem, mas a página de teste do Google $como: " +
                "algo no caminho (VPN, bloqueio do DNS ou rede pedindo login) está barrando.", Causa.BLOQUEIO, 2)
        }

        // 5) Funciona agora. Sobram a VPN, a lentidão e as quedas recentes.
        val queda = ultimaQueda(h, m.agora, zona)
        val complemento = queda?.let { " " + it.replaceFirstChar { c -> c.uppercase() } + "." } ?: ""
        if (m.vpnFixada && !m.vpnDePe)
            return r("A internet está ok; só a VPN está desligada (os apps funcionam sem ela).$complemento", Causa.VPN_DESLIGADA, 1)
        val lentoTcp = (m.tcpMs ?: 0) > TCP_LENTO_MS
        val lentoHttp = (m.httpMs ?: 0) > HTTP_LENTO_MS
        if (sinalFraco || lentoTcp || lentoHttp) {
            val porque = when {
                sinalFraco -> "o sinal do Wi-Fi está fraco (${m.dbm} dBm); aproximar o roteador ou usar cabo ajuda"
                lentoTcp -> "a internet demora ${m.tcpMs} ms para responder" +
                    (if (m.roteador?.latenciaMs?.let { it > TCP_LENTO_MS } == true) ", e o roteador vê o mesmo: é o provedor" else "")
                else -> "a página de teste levou ${(m.httpMs ?: 0) / 100 / 10.0} s para abrir".replace('.', ',')
            }
            return r("A internet funciona, mas devagar: $porque.$complemento", Causa.LENTA, 1)
        }
        if (queda != null) {
            val recente = m.historico.isNotEmpty() && quedas(h, m.agora).lastOrNull()?.let { m.agora - it.fim < 3_600_000L } == true
            return r("A internet está ok agora; $queda.", Causa.QUEDA_RECENTE, if (recente) 1 else 0)
        }
        // "Sem quedas nas últimas 24 h" só com o dia quase todo medido; senão, só o que foi registrado.
        val cobertura = h.filter { it.t >= m.agora - DIA_MS }.let { if (it.isEmpty()) -1L else ResumoRede.resumir(it, m.agora - DIA_MS, m.agora).coberturaMs }
        val semQuedas = when {
            cobertura >= COBERTURA_DIA_MS -> " Sem quedas nas últimas 24 h."
            cobertura >= 0 -> " Nenhuma queda registrada (a TV mediu só parte das últimas 24 h)."
            else -> ""
        }
        val avisoRoteador = if (m.roteador?.internetFora == true) " (O roteador avisa problema na internet, mas a TV navega normal.)" else ""
        return r("Está tudo ok: Wi-Fi, internet e DNS respondem.$semQuedas$avisoRoteador", Causa.OK, if (avisoRoteador.isEmpty()) 0 else 1)
    }

    // ---------------------------------------------------------------- partes (públicas para teste)

    /**
     * A falha do UniFi quer dizer "o roteador não responde" (travado)? Só o tempo esgotado e a conexão recusada
     * (mensagens de UnifiCliente.traduzir). 404 da API antiga ("não respondeu nesse caminho"), chave recusada, TLS e
     * erro desconhecido ("não consegui falar…") = o roteador está de pé ou não sabemos: "não consultado", não "mudo".
     */
    fun roteadorMudo(mensagem: String?): Boolean =
        mensagem != null && (mensagem.contains("não respondeu a tempo") || mensagem.contains("nada respondeu nesse endereço"))

    /**
     * Desde quando dura a falha de AGORA, pelo histórico: a última amostra precisa ser recente e falhar, e volta enquanto
     * as anteriores também falham, sem buraco de dados no meio (buraco = não sabemos o que houve). Null = sem histórico.
     */
    fun inicioDaFalha(h: List<ResumoRede.Amostra>, agora: Long, falha: (ResumoRede.Amostra) -> Boolean): Long? {
        val ultima = h.lastOrNull() ?: return null
        if (agora - ultima.t > ResumoRede.BURACO_MS || !falha(ultima)) return null
        var i = h.size - 1
        while (i > 0 && falha(h[i - 1]) && h[i].t - h[i - 1].t <= ResumoRede.BURACO_MS) i--
        return h[i].t
    }

    /**
     * Quedas já terminadas nas últimas 24 h, da mais velha para a mais nova. O resumo fecha uma queda também quando ela
     * só MUDA de tipo (internet -> DNS): aqui os pedaços colados viram uma queda só, que termina quando tudo voltou
     * (senão a frase diria "voltou" com o DNS ainda falhando). Tipo da queda junta = "internet" se algum pedaço for.
     */
    fun quedas(h: List<ResumoRede.Amostra>, agora: Long): List<ResumoRede.Queda> {
        val juntas = mutableListOf<ResumoRede.Queda>()
        for (q in pedacos(h, agora)) {
            val ant = juntas.lastOrNull()
            if (ant != null && !ant.aberta && ant.fim == q.inicio)
                juntas[juntas.size - 1] = ResumoRede.Queda(ant.inicio, q.fim, if (ant.tipo == "internet" || q.tipo == "internet") "internet" else "dns", q.aberta)
            else juntas += q
        }
        return juntas.filter { !it.aberta }
    }

    private fun pedacos(h: List<ResumoRede.Amostra>, agora: Long) =
        ResumoRede.resumir(h.filter { it.t >= agora - DIA_MS }, agora - DIA_MS, agora).quedas

    /** "a internet caiu às 18:12 e voltou às 18:20 (8 min)" [+ "; foram 3 quedas em 24 h, 25 min fora"], ou null. */
    fun ultimaQueda(h: List<ResumoRede.Amostra>, agora: Long, zona: ZoneId): String? {
        val q = quedas(h, agora)
        val u = q.lastOrNull() ?: return null
        val oque = if (u.tipo == "dns") "o DNS falhou" else "a internet caiu"
        // Sem amostra no fim da queda = a TV parou de medir (desligada da tomada etc.): não dá para dizer quando voltou.
        if (h.none { it.t == u.fim }) return "$oque ${quando(u.inicio, agora, zona)} (depois a TV ficou sem medir; não dá para saber quando voltou)" +
            (if (q.size > 1) "; foram ${q.size} quedas em 24 h" else "")
        // Voltou no mesmo dia em que caiu: só a hora ("às 18:20"), sem repetir "ontem".
        val fim = if (mesmoDia(u.inicio, u.fim, zona)) "às " + Instant.ofEpochMilli(u.fim).atZone(zona).format(HM) else quando(u.fim, agora, zona)
        var s = "$oque ${quando(u.inicio, agora, zona)} e voltou $fim (${duracao(u.duracao)})"
        if (q.size > 1) s += "; foram ${q.size} quedas em 24 h, ${duracao(pedacos(h, agora).filter { it.tipo == "internet" && !it.aberta }.sumOf { it.duracao })} sem internet no total"
        return s
    }

    /** O que foi medido, em pedaços curtos (vai junto na resposta do MCP e na tela, embaixo da frase). */
    fun sinais(m: Medicoes): List<String> = buildList {
        add(if (!m.conectada) "Wi-Fi: desconectado" else if (m.dbm in -126..-1) "Wi-Fi: ${m.dbm} dBm" else "rede: conectada")
        add("internet sem DNS: " + (m.tcpMs?.let { "$it ms" } ?: "falhou"))
        add("DNS sem cache: " + (m.dnsMs?.let { "$it ms" } ?: "falhou") + (m.dnsNome?.let { " ($it)" } ?: ""))
        add("página de teste: " + when (m.httpCodigo) { null -> "falhou"; 204 -> "ok" + (m.httpMs?.let { " em $it ms" } ?: ""); else -> "código ${m.httpCodigo}" })
        if (m.vpnFixada || m.vpnDePe) add("VPN: " + if (m.vpnDePe) "ligada" else "desligada")
        add("roteador: " + when {
            m.roteador != null -> when {
                m.roteador.internetFora -> "vê a internet fora"
                m.roteador.internetOk -> "vê a internet ok" + (m.roteador.latenciaMs?.let { " ($it ms)" } ?: "")
                else -> "sem opinião"
            }
            m.roteadorMudo -> "não respondeu"
            else -> "não consultado"
        })
        add("histórico: ${m.historico.count { it.t >= m.agora - DIA_MS }} medições em 24 h")
    }

    /** Lê o stat/health do UniFi: {"data":[{"subsystem":"wan","status":"ok"},{"subsystem":"www","status":"error",...}]}. */
    fun lerSaudeRoteador(texto: String): Roteador? = try {
        val dados = JSONObject(texto).optJSONArray("data")
        var wan: String? = null; var www: String? = null; var lat: Int? = null
        if (dados != null) for (i in 0 until dados.length()) {
            val s = dados.optJSONObject(i) ?: continue
            val st = s.optString("status").lowercase().takeIf { it in setOf("ok", "error", "warning") }
            when (s.optString("subsystem")) {
                "wan" -> wan = st
                "www" -> { www = st; if (s.has("latency") && !s.isNull("latency")) lat = s.optInt("latency").takeIf { it in 0..60_000 } }
            }
        }
        if (wan == null && www == null) null else Roteador(wan, www, lat)
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- textos de tempo

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DM = DateTimeFormatter.ofPattern("dd/MM")

    private fun mesmoDia(a: Long, b: Long, zona: ZoneId) =
        Instant.ofEpochMilli(a).atZone(zona).toLocalDate() == Instant.ofEpochMilli(b).atZone(zona).toLocalDate()

    /** "às 18:12" (hoje), "ontem às 18:12" ou "em 08/11 às 18:12". */
    fun quando(t: Long, agora: Long, zona: ZoneId): String {
        val d = Instant.ofEpochMilli(t).atZone(zona)
        val hoje = Instant.ofEpochMilli(agora).atZone(zona).toLocalDate()
        val hm = d.format(HM)
        return when (d.toLocalDate()) {
            hoje -> "às $hm"
            hoje.minusDays(1) -> "ontem às $hm"
            else -> "em ${d.format(DM)} às $hm"
        }
    }

    /** "desde as 18:12", "desde ontem às 18:12". */
    private fun desde(t: Long, agora: Long, zona: ZoneId) = quando(t, agora, zona).let { if (it.startsWith("às ")) "desde as " + it.removePrefix("às ") else "desde $it" }

    /** "menos de 1 min", "6 min", "2 h", "1 h 20 min". */
    fun duracao(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "menos de 1 min"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }
}

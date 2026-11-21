package br.paulo.tvlauncher.network

import br.paulo.tvlauncher.family.Recado
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Roteador UniFi (UX7 e outros com UniFi OS), integração SÓ DE LEITURA (05/11/2026). Aqui fica só a lógica pura, sem
 * nada do Android, para o teste rodar no PC (UnifiTest). A rede está em [UnifiCliente]; a chave, em [UnifiArmazem].
 *
 * A API usada é a "Integration v1" do UniFi Network (9.x ou mais novo): a chave é criada no próprio UniFi, em
 * Configurações → Integrações, e vai no cabeçalho X-API-Key. Caminho no UniFi OS: /proxy/network/integration/v1/…
 * Rotas usadas: /info (versão), /sites (o "site" da casa) e /sites/{id}/clients (aparelhos conectados).
 * Fonte: OpenAPI da comunidade github.com/tmcpro/unifi-network-api (engenharia reversa; a documentação oficial da
 * Ubiquiti fica em developer.ui.com). Nenhuma rota de AÇÃO (bloquear, reiniciar) é chamada.
 */
object Unifi {
    const val BASE = "/proxy/network/integration/v1"
    const val MAX_APARELHOS = 1000          // casa nenhuma tem mais; protege a memória se o roteador responder lixo
    const val MAX_CONHECIDOS = 1000

    /** Um aparelho conectado no roteador. [tipo]: "WIRED", "WIRELESS", "VPN" ou "TELEPORT" (o que a API manda). */
    data class Aparelho(val id: String, val nome: String, val ip: String?, val mac: String?, val tipo: String, val desde: String?,
                        val pontoDeAcesso: String? = null) {
        /** O que identifica o aparelho entre uma leitura e outra: o MAC (o id da API muda se o roteador for refeito). */
        val chave: String get() = mac?.lowercase() ?: "id:$id"
    }

    /**
     * Endereço do roteador: só um IPv4 de rede PRIVADA (10.x, 172.16–31.x, 192.168.x), com porta opcional.
     * Por quê: a chave de API vai junto em cada pedido. Aceitar um nome (ex.: "unifi.lan") exigiria DNS, e um DNS
     * envenenado mandaria a chave para fora de casa; aceitar IP público mandaria a chave pela internet.
     * Devolve "ip" ou "ip:porta" normalizado, ou null se não serve.
     */
    fun enderecoValido(bruto: String?): String? {
        val t = bruto?.trim()?.removePrefix("https://")?.trimEnd('/') ?: return null
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?$""").matchEntire(t) ?: return null
        val n = (1..4).map { m.groupValues[it].toInt() }
        if (n.any { it > 255 }) return null
        val privado = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        if (!privado) return null
        val porta = m.groups[5]?.value?.toInt()
        if (porta != null && porta !in 1..65535) return null
        val ip = n.joinToString(".")
        return if (porta == null || porta == 443) ip else "$ip:$porta"
    }

    /**
     * Chave de API: o UniFi gera uma sequência de letras, números, "-" e "_" (uns 32 caracteres). Aceito de 16 a 128
     * desses caracteres; tira espaços e quebras que vêm junto ao colar. Null se não serve.
     */
    fun chaveValida(bruta: String?): String? {
        val t = bruta?.filterNot { it.isWhitespace() } ?: return null
        return t.takeIf { it.length in 16..128 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }
    }

    /** Impressão digital SHA-256 do certificado, como o navegador mostra: "AB:CD:…" (32 pares). */
    fun impressao(certificadoDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificadoDer).joinToString(":") { "%02X".format(it) }

    /** Lê uma página de /sites/{id}/clients. Devolve os aparelhos e o totalCount (para saber se há mais páginas). */
    fun lerPaginaAparelhos(texto: String): Pair<List<Aparelho>, Int> {
        val o = JSONObject(texto)
        val dados = o.getJSONArray("data")
        val lista = ArrayList<Aparelho>(dados.length())
        for (i in 0 until dados.length()) {
            val a = dados.optJSONObject(i) ?: continue
            val id = a.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val mac = a.optString("macAddress").takeIf { it.isNotEmpty() }
            val nome = a.optString("name").trim().takeIf { it.isNotEmpty() } ?: mac ?: "sem nome"
            lista.add(Aparelho(id, nome.take(80), a.optString("ipAddress").takeIf { it.isNotEmpty() }, mac,
                a.optString("type", "?"), a.optString("connectedAt").takeIf { it.isNotEmpty() },
                a.optString("uplinkDeviceId").takeIf { it.isNotEmpty() }))
        }
        return lista to o.optInt("totalCount", lista.size)
    }

    /** O site da casa: [id] para a API de integração e [ref] (internalReference, ex.: "default") para a API antiga. */
    data class Site(val id: String, val ref: String)

    /** O site da casa em /sites: o de internalReference "default" ou, se não houver, o primeiro. */
    fun lerSite(texto: String): Site? {
        val dados = JSONObject(texto).getJSONArray("data")
        var primeiro: Site? = null
        for (i in 0 until dados.length()) {
            val s = dados.optJSONObject(i) ?: continue
            val id = s.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val ref = s.optString("internalReference").takeIf { refValida(it) } ?: "default"
            if (s.optString("internalReference") == "default") return Site(id, ref)
            if (primeiro == null) primeiro = Site(id, ref)
        }
        return primeiro
    }

    /** O nome curto do site vai no caminho da API antiga: só letras minúsculas e números, para não montar caminho estranho. */
    fun refValida(ref: String): Boolean = ref.length in 1..32 && ref.all { it in 'a'..'z' || it in '0'..'9' }

    /** Os aparelhos de [atuais] que não estão em [conhecidos] (comparando por [Aparelho.chave]). */
    fun novos(conhecidos: Set<String>, atuais: List<Aparelho>): List<Aparelho> {
        val ambiguos = ambiguos(atuais)
        // O nome só reconhece quando nunca foi visto em dois aparelhos ao mesmo tempo (dois "iPhone" diferentes).
        fun peloNome(a: Aparelho) = chaveNome(a)?.takeIf { it !in ambiguos && AMBIGUO + it !in conhecidos }
        return atuais.filter { it.chave !in conhecidos && peloNome(it)?.let { n -> n in conhecidos } != true }
            .distinctBy { peloNome(it) ?: it.chave }
    }

    /** Nomes (chaveNome) que aparecem agora em mais de um MAC: são aparelhos diferentes com o mesmo nome. */
    private fun ambiguos(atuais: List<Aparelho>): Set<String> =
        atuais.mapNotNull { a -> chaveNome(a)?.let { it to a.mac } }.groupBy({ it.first }, { it.second }).filterValues { it.toSet().size > 1 }.keys

    private const val AMBIGUO = "ambiguo:"

    /**
     * Celular com MAC aleatório (Android/iOS trocam o MAC por rede e de tempos em tempos): o UniFi dá a ele o nome
     * do aparelho + o fim do MAC ("S25-de-Paulo 9c:d3"). Pente fino 19/11/2026 (Sala): o mesmo celular avisava
     * "Aparelho novo na rede" a cada MAC novo (4 avisos em 2 dias). A chave pelo nome sem esse fim reconhece o aparelho.
     * Só para MAC administrado localmente (bit 0x02 do 1º byte) E nome nesse formato; MAC de fábrica segue pelo MAC.
     */
    fun chaveNome(a: Aparelho): String? {
        val mac = a.mac ?: return null
        val primeiro = mac.take(2).toIntOrNull(16) ?: return null
        if (primeiro and 0x02 == 0) return null
        val m = SUFIXO_MAC.find(a.nome) ?: return null
        val base = a.nome.substring(0, m.range.first).trim()
        return if (base.isEmpty()) null else "nome:" + base.lowercase()
    }

    private val SUFIXO_MAC = Regex("""\s[0-9a-fA-F]{2}:[0-9a-fA-F]{2}$""")

    /**
     * A lista de conhecidos depois de uma leitura: os antigos + os novos, no máximo [MAX_CONHECIDOS] (sai o mais
     * antigo). Ordem importa: o fim da lista é o mais recente.
     */
    fun juntarConhecidos(antigos: List<String>, atuais: List<Aparelho>): List<String> {
        val r = LinkedHashSet(antigos)
        for (a in atuais) { r.add(a.chave); chaveNome(a)?.let { r.add(it) } }
        for (n in ambiguos(atuais)) r.add(AMBIGUO + n)   // daqui em diante esse nome não basta para reconhecer
        return r.toList().takeLast(MAX_CONHECIDOS)
    }

    /** Texto do recado na TV: até 3 nomes e "+N". Cabe no limite do [Recado] (200 caracteres, 3 linhas). */
    fun textoNovos(novos: List<Aparelho>): String {
        val nomes = novos.take(3).map { a -> a.nome.take(40) + (a.ip?.let { " ($it)" } ?: "") }
        val resto = novos.size - nomes.size
        return nomes.joinToString("\n") + if (resto > 0) " e mais $resto" else ""
    }

    // ---------------------------------------------------------------- Wi-Fi da TV (diagnóstico, só leitura)

    /** Um rádio do ponto de acesso: banda ("2.4", "5", "6"), canal e % de retransmissões (null se a API não mandou). */
    data class Radio(val ghz: String, val canal: Int?, val larguraMhz: Int?, val retransmissoesPct: Double?)

    /** O ponto de acesso onde a TV está: nome, modelo, rádios e CPU (%). */
    data class PontoDeAcesso(val nome: String, val modelo: String?, val radios: List<Radio>, val cpuPct: Double?)

    /**
     * Junta /devices/{id} (nome, modelo e canais em interfaces.radios) com /devices/{id}/statistics/latest
     * (retransmissões por rádio em interfaces.radios e cpuUtilizationPct). [estatisticas] pode faltar.
     */
    fun lerPontoDeAcesso(detalhes: String, estatisticas: String?): PontoDeAcesso {
        val d = JSONObject(detalhes)
        val canais = HashMap<String, Pair<Int?, Int?>>()
        d.optJSONObject("interfaces")?.optJSONArray("radios")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
                canais[r.optString("frequencyGHz")] = (if (r.has("channel")) r.optInt("channel") else null) to
                    (if (r.has("channelWidthMHz")) r.optInt("channelWidthMHz") else null)
            }
        }
        val retrans = HashMap<String, Double?>()
        var cpu: Double? = null
        if (estatisticas != null) {
            val e = JSONObject(estatisticas)
            cpu = if (e.has("cpuUtilizationPct")) e.optDouble("cpuUtilizationPct") else null
            e.optJSONObject("interfaces")?.optJSONArray("radios")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
                    retrans[r.optString("frequencyGHz")] = if (r.has("txRetriesPct")) r.optDouble("txRetriesPct") else null
                }
            }
        }
        val bandas = (canais.keys + retrans.keys).filter { it.isNotEmpty() }.distinct().sortedBy { it.toDoubleOrNull() ?: 99.0 }
        val radios = bandas.map { b -> Radio(b, canais[b]?.first, canais[b]?.second, retrans[b]) }
        return PontoDeAcesso(d.optString("name").ifEmpty { "ponto de acesso" }.take(80),
            d.optString("model").takeIf { it.isNotEmpty() }, radios, cpu)
    }

    /** Banda do Wi-Fi a partir da frequência do Android (MHz): "2.4", "5", "6" ou null. */
    fun banda(mhz: Int): String? = when (mhz) {
        in 2400..2500 -> "2.4"
        in 4900..5899 -> "5"
        in 5925..7125 -> "6"
        else -> null
    }

    const val DBM_FRACO = -75       // abaixo disto o streaming em 4K costuma travar
    const val DBM_RAZOAVEL = -67
    const val RETRANS_ALTA = 20.0   // % de retransmissões do rádio: interferência ou canal congestionado
    const val RETRANS_ATENCAO = 10.0
    const val LINK_BAIXO_MBPS = 50

    /**
     * Dicas objetivas para o Wi-Fi da TV, da mais grave para a menos grave. Lista vazia = está tudo bem.
     * [dbm] e [mbps] vêm do Android da própria TV; [ap] do roteador (pode faltar se a TV não foi achada nele).
     */
    fun dicas(dbm: Int, mhz: Int, mbps: Int, ap: PontoDeAcesso?): List<String> {
        val r = ArrayList<String>()
        val banda = banda(mhz)
        if (dbm < DBM_FRACO)
            r.add("Sinal fraco ($dbm dBm): o ponto de acesso está longe ou tem parede/móvel no caminho. Aproximar o ponto de acesso, " +
                "pôr um mais perto da TV ou usar cabo (adaptador de rede para o Chromecast) resolve.")
        else if (dbm < DBM_RAZOAVEL)
            r.add("Sinal razoável ($dbm dBm): dá para assistir, mas em 4K pode travar. Melhor com o ponto de acesso mais perto.")
        val temCincoNoAp = ap?.radios?.any { it.ghz == "5" || it.ghz == "6" } == true
        if (banda == "2.4" && dbm >= -70 && temCincoNoAp)
            r.add("A TV está no 2,4 GHz (mais lento e com mais interferência) mesmo com sinal bom e o ponto de acesso tendo 5 GHz. " +
                "No UniFi, ligue o \"Band Steering\" na rede Wi-Fi ou crie uma rede só de 5 GHz para a TV.")
        val radioDaTv = ap?.radios?.firstOrNull { it.ghz == banda }
        radioDaTv?.retransmissoesPct?.let { p ->
            val canal = radioDaTv.canal?.let { " (canal $it)" } ?: ""
            if (p >= RETRANS_ALTA)
                r.add("Muitas retransmissões no rádio de ${radioDaTv.ghz.replace('.', ',')} GHz$canal: ${"%.0f".format(p)}%. É interferência ou canal " +
                    "congestionado (vizinhos). No UniFi, rode a otimização de canais (WiFi AI / Channel AI) ou escolha um canal livre à mão.")
            else if (p >= RETRANS_ATENCAO)
                r.add("Retransmissões um pouco altas no rádio de ${radioDaTv.ghz.replace('.', ',')} GHz$canal: ${"%.0f".format(p)}%.")
        }
        if (mbps in 1 until LINK_BAIXO_MBPS && dbm >= DBM_RAZOAVEL)
            r.add("Velocidade do link baixa ($mbps Mbps) mesmo com sinal bom: costuma ser canal estreito (20 MHz) ou padrão antigo na rede Wi-Fi.")
        return r
    }

    // ---------------------------------------------------------------- API antiga (sinal visto pelo roteador)

    /**
     * A TV como o ROTEADOR a vê, de /proxy/network/api/s/{site}/stat/sta (API antiga, a do próprio app do UniFi; a chave de
     * integração costuma funcionar nela, mas isso não é documentado). [sinalDbm]: "signal"; [radio]: "ng" = 2,4 GHz,
     * "na" = 5 GHz, "6e" = 6 GHz; taxas em Mbps; [satisfacao]: nota de 0 a 100 do próprio UniFi.
     */
    data class SinalRoteador(val mac: String, val sinalDbm: Int?, val radio: String?, val canal: Int?, val txMbps: Int?,
                             val rxMbps: Int?, val satisfacao: Int?, val rede: String?)

    /** Procura o aparelho de IP [ip] na resposta de stat/sta. Null se não estiver lá. */
    fun lerEstacao(texto: String, ip: String): SinalRoteador? {
        val dados = JSONObject(texto).optJSONArray("data") ?: return null
        for (i in 0 until dados.length()) {
            val a = dados.optJSONObject(i) ?: continue
            if (a.optString("ip") != ip) continue
            val mac = a.optString("mac").takeIf { macValida(it) } ?: return null
            fun int(n: String) = if (a.has(n) && !a.isNull(n)) a.optInt(n) else null
            return SinalRoteador(mac.lowercase(), int("signal"), a.optString("radio").takeIf { it.isNotEmpty() }, int("channel"),
                int("tx_rate")?.let { it / 1000 }, int("rx_rate")?.let { it / 1000 }, int("satisfaction"),
                a.optString("essid").takeIf { it.isNotEmpty() }?.take(40))
        }
        return null
    }

    /** "aa:bb:cc:dd:ee:ff". O MAC vai no corpo do único comando que mandamos ao roteador: nada além disso passa. */
    fun macValida(mac: String?): Boolean = mac != null && Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$").matches(mac)

    /** "ng" → "2.4", "na" → "5", "6e" → "6". */
    fun bandaDoRadio(radio: String?): String? = when (radio) { "ng" -> "2.4"; "na" -> "5"; "6e" -> "6"; else -> null }

    /** Dica a mais quando o roteador dá nota baixa para a conexão da TV (a "satisfação" do UniFi). */
    fun dicaSatisfacao(s: SinalRoteador?): String? = s?.satisfacao?.takeIf { it in 0..59 }?.let {
        "O próprio UniFi dá nota $it de 100 para a conexão da TV. Experimente \"Reconectar a TV\": ela escolhe de novo o melhor ponto de acesso."
    }

    /**
     * Resumo de uma resposta para diagnóstico, SEM valores (nada de MAC, IP ou nome): o "rc" da API antiga, quantos itens
     * vieram em "data" e os nomes dos campos do 1º item. Ex.: "rc=ok, 10 itens, campos: ip, mac, signal…".
     */
    fun resumoResposta(texto: String): String = try {
        val o = JSONObject(texto)
        val rc = o.optJSONObject("meta")?.optString("rc")?.takeIf { it.isNotEmpty() }
        val dados = o.optJSONArray("data")
        val campos = dados?.optJSONObject(0)?.keys()?.asSequence()?.toList()?.sorted()?.take(25)?.joinToString(", ")
        listOfNotNull(rc?.let { "rc=$it" }, dados?.let { "${it.length()} itens" } ?: "sem \"data\"",
            campos?.let { "campos: $it" }).joinToString(", ")
    } catch (e: Exception) {
        "não é JSON (${texto.length} caracteres, começa com \"${texto.take(15).filter { it.isLetterOrDigit() || it in "<>{}[ !" }}\")"
    }

    // ------------------------------------------------------------ relato do roteador (só leitura, 15/11/2026)

    /**
     * "Por que a TV caiu do Wi-Fi?": os aparelhos UniFi (gateway, pontos de acesso) com o tempo que estão ligados,
     * a partir do stat/device da API antiga. Um tempo ligado menor que o da queda mostra que o roteador reiniciou.
     */
    fun relatoAparelhos(texto: String, agoraMs: Long, zona: java.time.ZoneId): List<String> {
        val dados = JSONObject(texto).optJSONArray("data") ?: return listOf("aparelhos: resposta sem lista")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zona)
        val linhas = ArrayList<String>()
        for (i in 0 until dados.length()) {
            val d = dados.optJSONObject(i) ?: continue
            val nome = d.optString("name").ifEmpty { d.optString("model") }.take(40)
            val modelo = d.optString("model").take(20)
            val versao = d.optString("version").take(30)
            val up = d.optLong("uptime", -1L)
            val ligado = if (up < 0) "tempo ligado desconhecido" else {
                val desde = fmt.format(java.time.Instant.ofEpochMilli(agoraMs - up * 1000))
                "ligado há ${up / 3600} h ${up % 3600 / 60} min (desde $desde)"
            }
            linhas += "aparelho $nome ($modelo, ${d.optString("type").take(10)}): $ligado" +
                (if (versao.isNotEmpty()) ", versão $versao" else "") +
                (if (d.optBoolean("upgradable")) ", tem atualização pendente" else "")
        }
        return linhas.ifEmpty { listOf("aparelhos: nenhum") }
    }

    /**
     * Eventos do stat/event da API antiga que importam para a queda: os da TV (pelo [macTv]) e os dos aparelhos UniFi
     * (reinício, atualização, perda de contato, mudança de canal). Do mais velho ao mais novo, no máximo [max].
     */
    fun relatoEventos(texto: String, macTv: String?, zona: java.time.ZoneId, max: Int = 80): List<String> {
        val dados = JSONObject(texto).optJSONArray("data") ?: return listOf("eventos: resposta sem lista")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(zona)
        val mac = macTv?.lowercase()
        val achados = ArrayList<Pair<Long, String>>()
        var total = 0
        for (i in 0 until dados.length()) {
            val e = dados.optJSONObject(i) ?: continue
            total++
            val chave = e.optString("key")
            val daTv = mac != null && (e.optString("user").lowercase() == mac || e.optString("client").lowercase() == mac ||
                e.optString("guest").lowercase() == mac || e.optString("msg").lowercase().contains(mac))
            val doAparelho = EVENTOS_DE_APARELHO.any { chave.startsWith(it) }
            if (!daTv && !doAparelho) continue
            val t = e.optLong("time", 0L)
            achados += t to "${if (t > 0) fmt.format(java.time.Instant.ofEpochMilli(t)) else "?"} ${if (daTv) "[TV] " else ""}$chave: ${e.optString("msg").take(160)}"
        }
        val linhas = achados.sortedBy { it.first }.takeLast(max).map { it.second }
        return listOf("eventos: $total no período, ${achados.size} sobre a TV ou os aparelhos UniFi") + linhas
    }

    private val EVENTOS_DE_APARELHO = listOf("EVT_GW_", "EVT_AP_", "EVT_SW_", "EVT_LU_", "EVT_DM_", "EVT_USW_", "EVT_UGW_", "EVT_UDM_")
}

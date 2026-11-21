package br.paulo.tvlauncher.network

/**
 * Volume de verdade nas LG NetCast 4.0 (2013, ex.: 42LN5700), ideia 49 de 13/11/2026. Regras puras, testadas em
 * LgNetcastTest; quem fala com a TV é o [VolumeLg].
 *
 * Por que: quando o Chromecast sai pelo HDMI com volume fixo, o volume do Android (STREAM_MUSIC) não chega na TV. A LG
 * aceita teclas pela rede (protocolo ROAP, HTTP na porta 8080), depois de parear com um código que ela mostra na tela.
 * Verificado na Sala em 14/11/2026: pareou, subiu e desceu um ponto, e `volume_info` devolveu nível 0 a 100 e o mudo.
 * O formato antigo de pareamento (UDAP, /udap/api/pairing) responde 200 nesta TV mas NÃO mostra o código.
 */
object LgNetcast {
    const val PORTA = 8080
    const val TIPO = "application/atom+xml"
    const val CAMINHO_AUTH = "/roap/api/auth"
    const val CAMINHO_COMANDO = "/roap/api/command"
    const val CAMINHO_VOLUME = "/roap/api/data?target=volume_info"
    /** Entrada e canal de agora (pareada). Na Sala, no Chromecast: inputSourceName HDMI1, labelName = nome do Chromecast. */
    const val CAMINHO_ENTRADA = "/roap/api/data?target=cur_channel"
    /** Responde sem pareamento: serve para achar a TV na rede e conferir que é ela (uuid). */
    const val CAMINHO_MODELO = "/udap/api/data?target=netrcu.model_info"

    // Códigos de tecla do ROAP. Subir e descer verificados na Sala; o mudo vem da mesma tabela e NÃO foi testado.
    const val TECLA_SUBIR = 24
    const val TECLA_DESCER = 25
    const val TECLA_MUDO = 26
    /** Energia: DESLIGA (verificado na Sala em 15/11/2026). Não liga: em standby a LG sai da rede; quem liga é o CEC. */
    const val TECLA_ENERGIA = 1

    /** Teto de teclas numa ida: a LG vai de 0 a 100; nada pede mais do que isso de uma vez. */
    const val MAX_TECLAS = 100

    private const val XML = """<?xml version="1.0" encoding="utf-8"?>"""

    fun corpoPedirCodigo() = "$XML<auth><type>AuthKeyReq</type></auth>"
    fun corpoParear(codigo: String) = "$XML<auth><type>AuthReq</type><value>$codigo</value></auth>"
    fun corpoTecla(tecla: Int) = "$XML<command><name>HandleKeyInput</name><value>$tecla</value></command>"

    /** O código que a LG mostra: só dígitos (4 a 8). Nada além disso entra no XML. */
    fun codigoValido(c: String?): Boolean = c != null && Regex("[0-9]{4,8}").matches(c)

    /** Só IPv4 de rede local: a LG está em casa, e o código nunca deve ir para fora. */
    fun ipLocal(ip: String?): Boolean {
        val p = ip?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        if (p.size != 4 || p.any { it !in 0..255 } || ip.split('.').any { it.length > 1 && it.startsWith("0") }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** O erro do ROAP (200 = ok), ou null se a resposta não for do ROAP. */
    fun erro(xml: String): Int? = tag(xml, "ROAPError")?.toIntOrNull()

    data class Volume(val nivel: Int, val mudo: Boolean, val maximo: Int = 100)

    fun volume(xml: String): Volume? {
        if (erro(xml) != 200) return null
        val nivel = tag(xml, "level")?.toIntOrNull() ?: return null
        val max = tag(xml, "maxLevel")?.toIntOrNull()?.takeIf { it > 0 } ?: 100
        return Volume(nivel.coerceIn(0, max), tag(xml, "mute") == "true", max)
    }

    data class Modelo(val uuid: String, val nome: String, val modelo: String)

    /** A resposta do model_info, se for uma LG. */
    fun modelo(xml: String): Modelo? {
        if (tag(xml, "manufacturer")?.contains("LG", ignoreCase = true) != true) return null
        val uuid = tag(xml, "uuid") ?: return null
        return Modelo(uuid, tag(xml, "friendlyName").orEmpty(), tag(xml, "modelName").orEmpty())
    }

    /**
     * Quantas teclas para ir de [atual] a [alvo]: positivo = subir, negativo = descer. Limitado a [MAX_TECLAS] e ao
     * [maximo] da TV (um alvo fora da faixa não vira cem teclas a mais).
     */
    fun teclas(atual: Int, alvo: Int, maximo: Int = 100): Int {
        val d = alvo.coerceIn(0, maximo) - atual.coerceIn(0, maximo)
        return d.coerceIn(-MAX_TECLAS, MAX_TECLAS)
    }

    /** Teclas só para BAIXAR até [teto] (0 se já está abaixo): hora de dormir e desligar suave nunca sobem o volume. */
    fun descerAte(atual: Int, teto: Int, maximo: Int = 100): Int = minOf(0, teclas(atual, teto, maximo))

    /** Porcentagem do máximo da TV para nível (o teto da hora de dormir é em %). */
    fun nivelDaPorcentagem(pct: Int, maximo: Int = 100): Int = maximo * pct.coerceIn(0, 100) / 100

    /**
     * Teto do despertador na LG (14/11/2026): no primeiro teste o alarme levou a LG de 8 a 66 e acordou a casa. O
     * despertador nunca sobe a LG acima disto, seja qual for o "volume mínimo" escolhido.
     */
    const val TETO_ALARME = 30

    /** Teclas por rodada antes de ler o volume de novo: em rajada a LG acelera (42 teclas subiram 58 pontos). */
    const val TECLAS_POR_RODADA = 3

    /**
     * Despertador com a LG (16/11/2026, pedido do dono): começa baixo e sobe aos poucos até um volume FIXO escolhido nas
     * configurações do alarme (não "só se estiver baixo"). Opções do volume final, na escala 0-100 da LG; todas ≤ [TETO_ALARME].
     */
    val OPCOES_ALARME = listOf(NAO_MEXER, 10, 15, 20, 25, 30)
    const val PADRAO_ALARME = 15
    /** Volume da LG em que o toque começa (ou o final, se for menor). */
    const val INICIO_ALARME = 5

    /**
     * "Não mexer" na LG (17/11/2026): quem tinha escolhido "não mexer" no volume mínimo passava a ter a LG subindo até
     * 15 depois de parear. 0 = o despertador não toca no volume da LG (nem no do Android, que é fixo no HDMI).
     */
    const val NAO_MEXER = 0

    /** O volume final escolhido: [NAO_MEXER] ou 1..[TETO_ALARME] (um valor velho ou estranho nas prefs não passa do teto). */
    fun alvoFixoAlarme(escolhido: Int): Int = if (escolhido <= NAO_MEXER) NAO_MEXER else escolhido.coerceAtMost(TETO_ALARME)

    /** Sem escolha gravada para a LG: herda o "não mexer" do volume mínimo; senão o [PADRAO_ALARME]. */
    fun volumeLgInicial(minimoPct: Int): Int = if (minimoPct <= 0) NAO_MEXER else PADRAO_ALARME

    /**
     * Na LG a subida é SEMPRE gradual (pedido do dono, 16/11): um alarme com rampa 0 ("tocar direto", o padrão dos
     * alarmes novos) sobe a LG em [RAMPA_MIN_LG_SEG]. A rampa escolhida, se maior, vale.
     */
    const val RAMPA_MIN_LG_SEG = 90   // Paulo, 19/11: 60 s ainda pareceu rápido
    fun rampaLg(rampaSeg: Int): Int = maxOf(rampaSeg, RAMPA_MIN_LG_SEG)

    /**
     * Quando a rampa do SOM do app começa a contar. Teste de 20/11/2026: a LG estava desligada, o CEC a acordou só 52 s
     * depois do alarme e ela voltou no volume da véspera (11); o som do app já estava em ~40% da rampa, então tocou alto até
     * a nossa rampa da LG baixar para 5. Agora o som do app fica no mínimo enquanto a LG não está pronta ([lgProntaMs] = 0)
     * e começa a subir junto com a rampa dela. [lgProntaMs] null = a LG não é controlada (rampa normal desde [inicioMs]).
     * Se a LG nunca ficar pronta, desiste depois de [ESPERA_LG_APP_MS] e sobe a partir daí.
     */
    fun inicioRampaApp(inicioMs: Long, lgProntaMs: Long?, agoraMs: Long): Long = when {
        lgProntaMs == null -> inicioMs
        lgProntaMs > 0 -> maxOf(inicioMs, lgProntaMs)
        agoraMs - inicioMs >= ESPERA_LG_APP_MS -> inicioMs + ESPERA_LG_APP_MS
        else -> agoraMs   // ainda esperando: x = 0, som no mínimo
    }
    const val ESPERA_LG_APP_MS = 130_000L   // um pouco mais que a espera da LG em VolumeLg.subirAlarme (120 s)

    /** Onde a subida começa: [INICIO_ALARME], ou já o [alvo] se ele for menor. */
    fun inicioRampaAlarme(alvo: Int): Int = minOf(INICIO_ALARME, alvo)

    /** De quanto em quanto tempo sobe 1 ponto para ir de [inicio] a [alvo] em [rampaSeg] (no mínimo 1 s entre teclas). */
    fun intervaloRampaMs(rampaSeg: Int, inicio: Int, alvo: Int): Long {
        val passos = alvo - inicio
        if (passos <= 0 || rampaSeg <= 0) return 0L
        return maxOf(1000L, rampaSeg * 1000L / passos)
    }

    /** Durante a subida: alguém mexeu no volume (passou mais de 2 pontos do que esperávamos)? Então paramos de subir. */
    fun mexeramNaRampa(lido: Int, esperado: Int): Boolean = Math.abs(lido - esperado) > 2

    /** Para quanto o despertador sobe a LG: o mínimo [pct] do máximo, limitado a [TETO_ALARME]; null = não mexe. */
    fun alvoAlarme(atual: Int, maximo: Int, pct: Int): Int? {
        if (maximo <= 0 || pct <= 0) return null
        val minimo = ((maximo * pct.coerceIn(0, 100) + 99) / 100).coerceIn(1, maximo)
        val alvo = minOf(minimo, TETO_ALARME, maximo)
        return if (atual < alvo) alvo else null
    }

    /**
     * Ao parar o despertador: para quanto voltar (null = não mexe). Volta ao [original] se fomos nós que subimos
     * ([definido] != null) e: ninguém mexeu (está a até 2 pontos do que pusemos) OU ficou acima do [TETO_ALARME]
     * (alto demais nunca fica depois de um alarme nosso).
     */
    fun devolverAlarme(agora: Int, definido: Int?, original: Int): Int? {
        if (definido == null || original < 0 || agora == original) return null
        return if (Math.abs(agora - definido) <= 2 || agora > TETO_ALARME) original else null
    }

    /** Uma rodada de ajuste: no máximo [TECLAS_POR_RODADA] teclas na direção do alvo (o sinal diz a direção). */
    fun rodada(atual: Int, alvo: Int, maximo: Int = 100): Int =
        teclas(atual, alvo, maximo).coerceIn(-TECLAS_POR_RODADA, TECLAS_POR_RODADA)

    /** A entrada que a LG mostra: [nome] = "HDMI1", "TV"...; [rotulo] = o nome que o aparelho deu pelo CEC. */
    data class Entrada(val nome: String, val rotulo: String)

    fun entrada(xml: String): Entrada? {
        if (erro(xml) != 200) return null
        val nome = tag(xml, "inputSourceName")?.takeIf { it.isNotBlank() } ?: return null
        return Entrada(nome, tag(xml, "labelName").orEmpty())
    }

    /**
     * A LG está na entrada do Chromecast? Só HDMI, e com o rótulo igual ao nome deste aparelho ([nossoNome], o
     * "device_name" que o CEC manda como OSD name) ou a mesma entrada já reconhecida antes ([aprendida], ex.: "HDMI1").
     * Na dúvida, não: acordar o Chromecast troca a LG para ele, e quem está vendo a antena perderia o programa.
     */
    fun entradaDoChromecast(e: Entrada, nossoNome: String?, aprendida: String?): Boolean {
        if (!e.nome.startsWith("HDMI", ignoreCase = true)) return false
        val nome = nossoNome?.trim().orEmpty()
        if (nome.isNotEmpty() && e.rotulo.trim().equals(nome, ignoreCase = true)) return true
        return !aprendida.isNullOrBlank() && e.nome.equals(aprendida, ignoreCase = true)
    }

    /**
     * Timer de sono / "desligar sem uso" (17/11/2026): desligar a LG junto só se ela está na entrada do Chromecast.
     * Sem leitura (null) = não: quem está na antena não perde o programa por causa de um pedido que falhou.
     */
    fun desligarLgJunto(e: Entrada?, nossoNome: String?, aprendida: String?): Boolean =
        e != null && entradaDoChromecast(e, nossoNome, aprendida)

    /**
     * Chromecast dormiu por qualquer caminho (tempo parado, botão, protetor de tela): vale tentar desligar a LG pela rede?
     * Não se nós mesmos a desligamos há menos de [JA_DESLIGOU_MS] (timer/sem uso já fizeram, e uma 2ª tecla de energia
     * numa LG ainda desligando não deve ir). [ultimaMs] = 0 nunca desligou.
     */
    fun desligarAoApagar(agoraMs: Long, ultimaMs: Long): Boolean = ultimaMs <= 0 || agoraMs - ultimaMs >= JA_DESLIGOU_MS
    const val JA_DESLIGOU_MS = 60_000L

    /**
     * Quando conferir a entrada ([AcordarComLg]): [ver] recebe cada leitura "LG na rede?" e devolve true quando ela
     * voltou depois de sair. Só conta como desligada depois de [FORA_SEGUIDAS] leituras fora (uma perdida não é a LG
     * desligando), e uma vez por volta: se a pessoa escolheu a antena, não fica perguntando de novo.
     */
    class Volta {
        private var foraSeguidas = 0
        private var viuDesligada = false

        fun ver(naRede: Boolean): Boolean {
            if (!naRede) {
                if (++foraSeguidas >= FORA_SEGUIDAS) viuDesligada = true
                return false
            }
            foraSeguidas = 0
            if (!viuDesligada) return false
            viuDesligada = false
            return true
        }

        companion object { const val FORA_SEGUIDAS = 2 }
    }

    /** Mandar "User-Agent: UDAP/2.0"? Só no caminho UDAP; no ROAP esse cabeçalho faz a LG responder 400. */
    fun agenteUdap(caminho: String): Boolean = caminho.startsWith("/udap/")

    private fun tag(xml: String, nome: String): String? =
        Regex("<$nome>([^<]*)</$nome>").find(xml)?.groupValues?.get(1)?.trim()
}

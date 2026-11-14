package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Caixa de comando do painel do celular, SEM IA (ideia 10, 14/11/2026). A pessoa escreve (ou dita pelo microfone do
 * teclado) "coloca na Globo", "abre o YouTube", "avisa em 7 minutos", "desliga em 30 minutos", e a frase vira UMA ação
 * do catálogo fechado (ComandosIa), no mesmo JSON que o Claude manda. Daí em diante é o caminho de sempre:
 * PedidoIa.propor -> ComandosIa.conferir -> Confirmações -> execução. Nada de shell, nada de IA.
 *
 * Só a regra, pura (sem Android), testada em CaixaComandoTest (português e inglês). As frases de canal em português
 * ("coloca na Globo", "o que passa no SBT") são do leitor que já existia para a voz (CanalBusca.interpretarFrase): quem
 * chama passa esse leitor em [interpretar] (o CanalBusca usa o ComandosIa, que tem textos do Android).
 */
object CaixaComando {
    const val MAX_FRASE = 200
    // Os mesmos tetos do ComandosIa (MAX_TIMER_MIN, MAX_DESLIGAR_MIN) e do CanalBusca.MAX_TERMO, copiados: aqueles não
    // são puros. O ComandosIa confere de novo de qualquer jeito.
    const val MAX_TIMER = 240
    const val MAX_DESLIGAR = 480
    private const val MAX_CANAL = 60

    /** O que a frase pede. [tipo] é um tipo do ComandosIa.TIPOS; [alvo] = app/canal, [numero] = minutos ou vezes. */
    data class Pedido(val tipo: String, val alvo: String = "", val numero: Int = 0, val acao: String = "") {
        /** O JSON do catálogo (formato do ComandosIa.esquema), com uma ação só. */
        fun json(): String {
            val a = JSONObject().put("tipo", tipo)
            when (tipo) {
                "abrir_app" -> a.put("pacote", alvo)
                "abrir_tela" -> a.put("tela", alvo)
                "tocar_canal", "o_que_passa" -> a.put("canal", alvo)
                "timer", "desligar_em" -> a.put("minutos", numero)
                "volume" -> a.put("acao", acao).put("vezes", numero.coerceAtLeast(1))
                "midia" -> a.put("acao", acao)
            }
            return JSONObject().put("acoes", JSONArray().put(a)).put("resposta", "").toString()
        }
    }

    /** Minúsculas, sem acento e sem pontuação, espaços simples: "Abre o YouTube!" -> "abre o youtube". */
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // ---------------------------------------------------------------- números ("sete minutos", "meia hora")

    private val NUMEROS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "doze" to 12, "quinze" to 15, "vinte" to 20, "trinta" to 30,
        "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "noventa" to 90,
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    private const val UNIDADES = """minutos?|mins?|horas?|hrs?|minutes?|hours?"""
    /** "7 min", "7min", "1h"; ou por extenso, separado da unidade: "sete minutos", "meia hora", "half an hour". */
    private val reTempo = Regex("""\b(\d{1,3})\s*($UNIDADES|m|h)\b|\b([a-z]+(?: e [a-z]+)?|half an?)\s+($UNIDADES)\b""")

    /** "7 minutos" -> 7; "meia hora" -> 30; "1 hora" -> 60; "vinte e cinco minutos" -> 25. Null = não achou. */
    fun minutos(f: String): Int? {
        for (m in reTempo.findAll(f)) {
            val digitos = m.groupValues[1]
            val n = digitos.ifEmpty { m.groupValues[3] }
            val hora = (if (digitos.isNotEmpty()) m.groupValues[2] else m.groupValues[4]).startsWith("h")
            val valor = when {
                n == "meia" || n.startsWith("half") -> if (hora) 30 else null
                digitos.isNotEmpty() -> digitos.toInt() * (if (hora) 60 else 1)
                else -> n.split(" e ").map { NUMEROS[it] }.let { l -> if (l.any { it == null }) null else l.sumOf { it!! } }
                    ?.let { if (hora) it * 60 else it }
            }
            if (valor != null) return valor
        }
        return null
    }

    /** "abre a previsão": as telas do launcher que o catálogo abre (ComandosIa.TELAS). */
    private val TELAS = mapOf(
        "previsao" to "previsao", "previsao do tempo" to "previsao", "tempo" to "previsao", "clima" to "previsao",
        "weather" to "previsao", "forecast" to "previsao",
        "agenda" to "agenda", "calendario" to "agenda", "calendar" to "agenda",
        "despertador" to "despertador", "despertadores" to "despertador", "alarme" to "despertador", "alarmes" to "despertador",
        "alarm" to "despertador", "alarms" to "despertador",
        "configuracoes" to "configuracoes", "ajustes" to "configuracoes", "settings" to "configuracoes",
        "inicio" to "inicio", "tela inicial" to "inicio", "home" to "inicio")

    // ---------------------------------------------------------------- frases

    private val reComeco = Regex("""^(?:ok google|hey google|ei google|google|por favor|please|tv)\s+""")
    private val reFinal = Regex("""\s+(?:por favor|please|agora|now|pra mim|para mim)$""")

    private val reDesligarCancelar = Regex("""^(?:cancela\w*|cancel)\b.*\b(?:deslig\w*|sleep|turn off|shut ?down)\b|^(?:nao deslig\w*|don ?t turn off)\b""")
    private val reDesligar = Regex("""\b(?:deslig\w*|dorm\w*|turn (?:the tv )?off|turn off|shut (?:the tv )?down|switch off|sleep)\b.*\b(?:em|daqui a|daqui|dentro de|in|after)\b""")
    private val reTimer = Regex("""\b(?:avis\w*|lembr\w*|timer|temporizador|cronometr\w*|alarme|remind\w*|alert\w*|notify|wake)\b""")
    private val reVolume = Regex("""\b(?:volume|som|sound)\b""")
    private val reSubir = Regex("""\b(?:aument\w*|sobe|subir|mais alto|up|louder|raise|increase|turn up)\b""")
    private val reDescer = Regex("""\b(?:abaix\w*|diminu\w*|baix\w*|desce|descer|mais baixo|down|lower|quieter|decrease|turn down)\b""")
    private val reMudo = Regex("""^(?:mudo|muta|mutar|silencio|silencia\w*|mute|unmute|tira o mudo|tirar o mudo)\b""")
    private val rePausar = Regex("""^(?:pausa|pausar|pause|stop)\b|^(?:para|parar)$""")
    private val reContinuar = Regex("""^(?:continua|continuar|despausa|play|resume|tocar|toca)$""")
    private val reAbrir = Regex("""^(?:(?:eu\s+)?(?:quero\s+)?(?:abre|abrir|abra|abri|inicia|iniciar|entra|entrar|vai|vai pro|vai para|open|launch|start|run|go to)\s+)(?:(?:o|a|no|na|em|pro|pra|para|the|app|aplicativo)\s+)*(.+)$""")
    private val reTocarEn = Regex("""^(?:(?:i want to\s+)?(?:watch|put on|put|turn on|switch to|change to|turn to|tune to|play|go to)\s+)(?:(?:the|on|to|channel)\s+)*(.+)$""")
    private val reOQuePassaEn = Regex("""^what s on\s+(?:(?:the|channel)\s+)*(.+)$|^what is on\s+(?:(?:the|channel)\s+)*(.+)$|^what s playing on\s+(.+)$""")
    private val reVerboPt = Regex("""^(?:(?:eu\s+)?(?:quero\s+)?(?:assistir|ver|colocar|coloca|coloque|poe|ponha|bota|botar|muda|mudar|mude|troca|trocar|liga|ligar|tocar|toca)\s+)(?:(?:o|a|no|na|em|pro|pra|para)\s+)*(.+)$""")

    /**
     * A frase -> o pedido, ou null ("não entendi"). [apps] = pacote -> nome dos apps que a HOME mostra (os únicos que
     * um pedido pode abrir). [canalPt] = o leitor de frases de canal da voz (CanalBusca), já devolvendo um Pedido.
     */
    fun interpretar(fraseCrua: String?, apps: Map<String, String>, canalPt: (String) -> Pedido? = { null }): Pedido? {
        var f = normalizar(fraseCrua?.take(MAX_FRASE) ?: return null)
        repeat(2) { f = f.replace(reComeco, "").replace(reFinal, "") }
        if (f.isEmpty()) return null

        // 1) Timer de sono: "desliga em 30 minutos", "turn off in 1 hour"; "cancela o desligamento".
        if (reDesligarCancelar.containsMatchIn(f)) return Pedido("desligar_em", numero = 0)
        if (reDesligar.containsMatchIn(f)) minutos(f)?.let { m ->
            return if (m in 1..MAX_DESLIGAR) Pedido("desligar_em", numero = m) else null
        }
        // 2) Timer: "avisa em 7 minutos", "timer de 10 min", "remind me in 5 minutes".
        // Sem tempo na frase ("abre o alarme") segue adiante.
        if (reTimer.containsMatchIn(f)) minutos(f)?.let { m ->
            return if (m in 1..MAX_TIMER) Pedido("timer", numero = m) else null
        }
        // 3) Volume e mídia.
        if (reMudo.containsMatchIn(f)) return Pedido("volume", acao = "mudo", numero = 1)
        if (reVolume.containsMatchIn(f)) {
            val vezes = Regex("""\b(\d{1,2})\b""").find(f)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 2
            return when {
                reSubir.containsMatchIn(f) -> Pedido("volume", acao = "subir", numero = vezes)
                reDescer.containsMatchIn(f) -> Pedido("volume", acao = "descer", numero = vezes)
                else -> null
            }
        }
        if (rePausar.containsMatchIn(f)) return Pedido("midia", acao = "pausar")
        if (reContinuar.matches(f)) return Pedido("midia", acao = "tocar")

        // 4) Abrir um app: "abre o YouTube", "open Netflix". Sem app com esse nome: talvez seja canal ("abre a Globo").
        reAbrir.find(f)?.let { m ->
            val resto = m.groupValues[1]
            TELAS[resto]?.let { return Pedido("abrir_tela", alvo = it) }
            app(resto, apps)?.let { return Pedido("abrir_app", alvo = it) }
            return canalPt("coloca $resto") ?: Pedido("tocar_canal", alvo = resto.take(MAX_CANAL))
        }
        // "coloca o YouTube" / "watch Netflix": o verbo de canal com o nome de um app abre o app.
        (reVerboPt.find(f)?.groupValues?.get(1) ?: reTocarEn.find(f)?.groupValues?.get(1))?.let { resto ->
            app(resto, apps)?.let { return Pedido("abrir_app", alvo = it) }
        }
        // 5) Canais. Português: o leitor da voz. Inglês: "what's on Globo", "put on channel 5".
        canalPt(fraseCrua.take(MAX_FRASE))?.let { return it }
        reOQuePassaEn.find(f)?.let { m ->
            val c = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
            if (c.isNotEmpty()) return Pedido("o_que_passa", alvo = c.take(MAX_CANAL))
        }
        reTocarEn.find(f)?.let { m ->
            val c = m.groupValues[1].trim()
            if (c.isNotEmpty()) return Pedido("tocar_canal", alvo = c.take(MAX_CANAL))
        }
        // 6) Só o nome de um app ("Netflix"): abre.
        app(f, apps)?.let { return Pedido("abrir_app", alvo = it) }
        return null
    }

    /**
     * O pacote do app chamado [texto]: nome igual (sem acento, caixa e espaços) primeiro; senão, um único app cujo nome
     * começa com o texto em palavras inteiras ("prime" -> "Prime Video"; "globo" NÃO é "Globoplay": aí é o canal), com
     * pelo menos 3 letras. Null = nenhum (ou mais de um).
     */
    fun app(texto: String, apps: Map<String, String>): String? {
        val t = compacto(texto)
        if (t.length < 2) return null
        apps.entries.firstOrNull { compacto(it.value) == t }?.let { return it.key }
        if (t.length < 3) return null
        val n = normalizar(texto) + " "
        val comeca = apps.entries.filter { (normalizar(it.value) + " ").startsWith(n) }
        return comeca.singleOrNull()?.key
    }

    private fun compacto(s: String) = normalizar(s).replace(" ", "")
}

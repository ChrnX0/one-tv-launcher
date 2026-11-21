package br.paulo.tvlauncher.observer

import br.paulo.tvlauncher.alarm.AcertoAlarme
import br.paulo.tvlauncher.player.Serie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Placar de promessas (elevação 2.1, onda 0, 17/11/2026): as REGRAS, sem Android (PlacarTest). A tela é a
 * PlacarActivity ("Como esta TV está indo"); quem grava as medidas é o MedidasTv.
 *
 * O produto promete sete coisas (os 7 pilares da auditoria de elevação). Cada uma tem UM número medido na própria TV,
 * um alvo e a data da medida. Pilar sem medida diz "ainda não medido": nunca inventa número nem mostra o alvo como se
 * fosse o resultado.
 *
 * As medidas vêm em séries curtas ([Serie]: os últimos [GUARDAR] pontos). O número do placar é a MEDIANA da série
 * (uma volta lenta por acaso, o Android compilando, não estraga o número), exceto onde a promessa é uma contagem
 * (despertador: "tocou na hora em N de N"; quedas em 30 dias).
 */
object Placar {
    /** Quantos pontos cada série guarda. 30 trocas de canal, 30 voltas à HOME: o bastante para a mediana não pular. */
    const val GUARDAR = 30

    /** Alvos (proposta da auditoria, aprovada com o "tudo" de 17/11/2026). */
    const val ALVO_HOME_MS = 1_000L
    const val ALVO_CANAL_MS = 1_500L
    const val ALVO_INSTALACAO_MS = 5 * 60_000L

    /** Chaves das séries (nome do arquivo em filesDir/medidas/). Uma chave é escrita por UM processo só. */
    object Chave {
        const val HOME = "home"                 // volta à HOME até os apps desenhados (processo principal)
        const val HOME_FRIA = "home_fria"       // processo novo até a HOME desenhada (processo principal)
        const val CANAL = "canal"               // tecla até o primeiro quadro do canal (processo :player)
        const val INSTALACAO_INICIO = "instalacao_inicio"   // processo principal
        const val INSTALACAO = "instalacao"     // primeira abertura até o primeiro canal (processo :player)
        const val LIMPEZA = "limpeza"           // memória ganha na limpeza, MB (processo principal)
        const val VOD = "vod"                   // filme/episódio: tela aberta até o primeiro quadro (processo :player)
        const val VOD_TRAVADAS = "vod_travadas" // travadas por hora de vídeo tocando, por sessão de 5 min ou mais (:player)
        const val VOD_PERDIDOS = "vod_perdidos" // quadros perdidos por minuto de vídeo tocando, idem (:player)
    }

    data class Ponto(val valor: Long, val em: Long)

    data class Serie(val pontos: List<Ponto> = emptyList()) {
        val ultimo get() = pontos.lastOrNull()
        fun somar(p: Ponto, guardar: Int = GUARDAR) = Serie((pontos + p).takeLast(guardar))
        fun mediana(): Long? = pontos.map { it.valor }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }

    fun texto(s: Serie): String = JSONArray().apply { s.pontos.forEach { put(JSONArray().put(it.valor).put(it.em)) } }.toString()

    fun ler(t: String?): Serie = try {
        val a = JSONArray(t ?: "[]")
        Serie((0 until a.length()).mapNotNull { i ->
            val p = a.optJSONArray(i) ?: return@mapNotNull null
            if (p.length() < 2) null else Ponto(p.optLong(0), p.optLong(1)).takeIf { it.em > 0 }
        }.takeLast(GUARDAR))
    } catch (_: Exception) { Serie() }

    enum class Pilar { LIGA, AO_VIVO, PLAYER, CASA, CONTROLE, TODOS, INSTALA }

    /**
     * Como a linha sai: [estado] null = ainda não medido; true = dentro do alvo; false = fora do alvo.
     * [valor] e [alvo] já vêm como número cru; a tela formata (ms → "0,8 s", contagem → "N de N").
     */
    sealed class Numero {
        data class Tempo(val ms: Long) : Numero()
        data class Contagem(val certos: Int, val total: Int) : Numero()
        data class Quedas(val quedas: Int, val dias: Int) : Numero()
    }

    data class Linha(val pilar: Pilar, val numero: Numero?, val alvo: Numero?, val dentro: Boolean?, val medidoEm: Long?, val amostras: Int = 0)

    /** Tudo o que a TV mediu, já lido do disco. null/vazio = não medido. */
    data class Entradas(
        val home: Serie = Serie(),
        val quedas30: Int? = null, val diasComUso30: Int = 0, val vitaisEm: Long? = null,
        val canal: Serie = Serie(),
        val alarme: AcertoAlarme.Resumo? = null,
        val instalacao: Serie = Serie(),
    )

    fun linhas(e: Entradas): List<Linha> = listOf(
        linhaHome(e),
        tempo(Pilar.AO_VIVO, e.canal, ALVO_CANAL_MS),
        Linha(Pilar.PLAYER, null, null, null, null),       // 6.1 (matriz de amostras na bancada 2.2): depende do aparelho de teste
        linhaAlarme(e.alarme),
        Linha(Pilar.CONTROLE, null, null, null, null),     // 2.5 (teste por terceiro): onda 3
        Linha(Pilar.TODOS, null, null, null, null),        // 16.x (acessibilidade, línguas revisadas): onda 2
        tempo(Pilar.INSTALA, e.instalacao, ALVO_INSTALACAO_MS),
    )

    private fun tempo(p: Pilar, s: Serie, alvo: Long): Linha {
        val m = s.mediana() ?: return Linha(p, null, Numero.Tempo(alvo), null, null)
        return Linha(p, Numero.Tempo(m), Numero.Tempo(alvo), m <= alvo, s.ultimo?.em, s.pontos.size)
    }

    /** Pilar 1: duas promessas (HOME em menos de 1 s E zero quedas em 30 dias); o número mostrado é o tempo. */
    private fun linhaHome(e: Entradas): Linha {
        val t = tempo(Pilar.LIGA, e.home, ALVO_HOME_MS)
        if (t.numero == null) return t
        val semQueda = e.quedas30?.let { it == 0 }
        return t.copy(dentro = t.dentro == true && semQueda != false)
    }

    private fun linhaAlarme(r: AcertoAlarme.Resumo?): Linha {
        if (r == null || r.total == 0) return Linha(Pilar.CASA, null, Numero.Contagem(1, 1), null, null)
        return Linha(Pilar.CASA, Numero.Contagem(r.naHora, r.total), Numero.Contagem(r.total, r.total), r.naHora == r.total, r.ultimoEm, r.total)
    }

    /** "0,8 s", "1,4 s", "3 min 20 s" (vírgula ou ponto conforme [decimal]). */
    fun tempoTexto(ms: Long, decimal: Char = ','): String = when {
        ms < 60_000 -> {
            val dec = (ms + 50) / 100   // décimos, arredondado
            "${dec / 10}$decimal${dec % 10} s"
        }
        else -> {
            val s = (ms + 500) / 1000
            if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
        }
    }

    /** JSON para o adb (`EXTRAS --es o placar`), o MCP e o painel. */
    fun json(l: List<Linha>): JSONObject = JSONObject().put("pilares", JSONArray().apply {
        l.forEach { x ->
            put(JSONObject().put("pilar", x.pilar.name.lowercase()).put("medido", x.numero != null)
                .put("valor", numeroJson(x.numero)).put("alvo", numeroJson(x.alvo))
                .put("dentro", x.dentro ?: JSONObject.NULL).put("em", x.medidoEm ?: JSONObject.NULL).put("amostras", x.amostras))
        }
    })

    private fun numeroJson(n: Numero?): Any = when (n) {
        null -> JSONObject.NULL
        is Numero.Tempo -> JSONObject().put("ms", n.ms)
        is Numero.Contagem -> JSONObject().put("certos", n.certos).put("total", n.total)
        is Numero.Quedas -> JSONObject().put("quedas", n.quedas).put("dias", n.dias)
    }
}

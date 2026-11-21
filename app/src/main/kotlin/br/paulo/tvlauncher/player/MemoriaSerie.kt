package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Estilo
import org.json.JSONObject

/**
 * Lembrar por série (elevação 6.4, onda 1, 17/11/2026): as REGRAS (MemoriaSerieTest). Quem grava e aplica é o
 * VodActivity, pela mesma chave que o Idioma e o AberturaSerie já usam para a série inteira.
 *
 * O áudio (original ou dublado) já era lembrado por série (Idioma.lembrar). Aqui entra o resto que a pessoa escolhe
 * no ▼ e que hoje valia para tudo ou só para um episódio:
 *  - legenda: o idioma escolhido ("pt", "en"…), ou desligada ("" = a pessoa tirou a legenda);
 *  - velocidade (1,25× numa série de conversa);
 *  - atraso da legenda baixada (a mesma versão costuma vir com o mesmo atraso em todos os episódios).
 * O atraso do ÁUDIO continua por saída de som (EfeitosSom): ele depende da caixa Bluetooth, não da série.
 * null = a pessoa nunca escolheu: vale o automático de sempre.
 */
object MemoriaSerie {
    /** Teto de séries guardadas: as mais antigas saem (cada uma ocupa umas dezenas de bytes). */
    const val MAX = 300

    data class Escolha(val legenda: String? = null, val velocidade: Float? = null, val atrasoLegendaMs: Long? = null, val em: Long = 0L)

    fun lembrar(todas: Map<String, Escolha>, serie: String, agora: Long, f: (Escolha) -> Escolha): Map<String, Escolha> {
        if (serie.isEmpty()) return todas
        val nova = f(todas[serie] ?: Escolha()).copy(em = agora)
        val m = LinkedHashMap(todas); m[serie] = nova
        return if (m.size <= MAX) m else m.entries.sortedByDescending { it.value.em }.take(MAX).associate { it.key to it.value }
    }

    /** A velocidade só volta se for uma das oferecidas no ▼ (um arquivo mexido não deixa a série a 8×). */
    fun velocidadeValida(v: Float?, oferecidas: List<Float>): Float? = v?.takeIf { x -> oferecidas.any { kotlin.math.abs(it - x) < 0.001f } }

    /**
     * Qual faixa de legenda ligar, entre as que o vídeo tem ([idiomas], na ordem das faixas): o índice, -1 = desligar
     * a legenda, null = não mexer (nunca escolheu, ou o idioma lembrado não existe neste episódio).
     */
    fun legenda(e: Escolha?, idiomas: List<String?>): Int? {
        val l = e?.legenda ?: return null
        if (l.isEmpty()) return -1
        val i = idiomas.indexOfFirst { it != null && Idioma.normalizar(it) == Idioma.normalizar(l) }
        return i.takeIf { it >= 0 }
    }

    fun paraJson(m: Map<String, Escolha>): String = JSONObject().apply {
        for ((k, e) in m) put(k, JSONObject().apply {
            e.legenda?.let { put("l", it) }; e.velocidade?.let { put("v", it.toDouble()) }
            e.atrasoLegendaMs?.let { put("a", it) }; put("em", e.em)
        })
    }.toString()

    fun deJson(t: String?): Map<String, Escolha> = try {
        val o = JSONObject(t ?: "{}")
        o.keys().asSequence().mapNotNull { k ->
            val e = o.optJSONObject(k) ?: return@mapNotNull null
            k to Escolha(if (e.has("l")) e.optString("l") else null, if (e.has("v")) e.optDouble("v").toFloat() else null,
                if (e.has("a")) e.optLong("a") else null, e.optLong("em"))
        }.toMap()
    } catch (_: Exception) { emptyMap() }
}

/**
 * Estilo da legenda (elevação 6.5, onda 1): três estilos prontos e a posição. Os estilos usam as mesmas duas chaves que
 * já existiam (fundo preto e letra amarela, PlayerAcessivel), para as Configurações e o ▼ falarem a mesma língua.
 */
object EstiloLegenda {
    enum class Estilo(val fundoPreto: Boolean, val amarela: Boolean) {
        PADRAO(false, false), ALTO_CONTRASTE(true, false), AMARELA(true, true)
    }

    /** As duas chaves como estão → o estilo (a combinação "amarela sem fundo" das chaves antigas conta como amarela). */
    fun de(fundoPreto: Boolean, amarela: Boolean): Estilo = when {
        amarela -> Estilo.AMARELA
        fundoPreto -> Estilo.ALTO_CONTRASTE
        else -> Estilo.PADRAO
    }

    fun proximo(e: Estilo): Estilo = Estilo.entries[(e.ordinal + 1) % Estilo.entries.size]
}

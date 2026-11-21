package br.paulo.tvlauncher

/**
 * A frase do dia e as três dicas da primeira tela do Clima (21/11/2026, pedido do Paulo: "uma tela principal com as
 * informações e dicas mais importantes").
 *
 * Porquê: a tela antiga mostrava números (24 horas, chance, vento) e deixava a conta para quem olha. Do sofá, ninguém
 * quer somar porcentagens para saber se leva guarda-chuva.
 * Para quê: a resposta pronta, em uma frase e três perguntas do dia a dia (guarda-chuva, roupa no varal, hora boa
 * para sair), que um idoso lê a 3 m.
 * Como: regras fixas sobre os MESMOS dados que a tela já busca (hora a hora: temperatura, chance de chuva, UV, dia/noite).
 * Ideia tirada do AccuWeather (índices do dia a dia) e do Google Weather (resumo em uma frase), sem IA: a mesma entrada
 * dá sempre a mesma frase. Pura (sem Android), testada em DicasClimaTest. Os textos ficam na tela (strings cl_*).
 */
internal object DicasClima {
    /** Chuva que conta como "vai chover" (mesmo limiar do [AvisoChuva]). */
    const val CHUVA = 50
    /** Abaixo disto, a hora é seca (para varal, sair e "deve parar"). */
    const val SECO = 30
    /** A partir disto o varal corre risco. */
    const val VARAL = 40

    /** Uma hora da previsão, a partir de agora. [hoje] = cai no mesmo dia de agora. */
    data class Hora(val hora: Int, val temp: Double, val chance: Int, val uv: Double, val dia: Boolean, val hoje: Boolean)

    /** [horas] começa na hora de agora (índice 0) e vai até 24 horas. [chanceAmanha] = máxima do dia seguinte. */
    data class Entrada(val chovendo: Boolean, val horas: List<Hora>, val maxHoje: Int, val chanceAmanha: Int?)

    enum class TipoFrase { PARA_AS, CHUVA_SEGUE, CHUVA_AS, CALOR, FRIO, SOL_FORTE, AMANHA_CHUVA, CALMO }

    /** [a] e [b]: hora, temperatura ou chance, conforme o tipo (ver cada caso em [frase]). */
    data class Frase(val tipo: TipoFrase, val a: Int = 0, val b: Int = 0)

    enum class Resposta { AGORA, SIM_AS, TALVEZ, NAO, ATE, SECA_HOJE, MELHOR_NAO, AMANHA_SIM, AMANHA_NAO, AS, AMANHA_AS, HOJE_NAO }

    /** [hora] vale para SIM_AS, ATE, AS e AMANHA_AS. */
    data class Dica(val resposta: Resposta, val hora: Int = -1)

    /** As 12 horas à frente (a "janela" das decisões de hoje). */
    private fun proximas(e: Entrada, n: Int = 12) = e.horas.take(n)

    /** O que mais importa hoje, por ordem: chuva agora, chuva chegando, calor, frio, sol forte, chuva amanhã, dia calmo. */
    fun frase(e: Entrada): Frase {
        val janela = proximas(e)
        if (e.chovendo) {
            val para = janela.drop(1).firstOrNull { it.chance < SECO }
            return if (para != null) Frase(TipoFrase.PARA_AS, para.hora) else Frase(TipoFrase.CHUVA_SEGUE)
        }
        val chuva = janela.indexOfFirst { it.chance >= CHUVA }
        if (chuva >= 0) {
            // A chance mostrada é a maior das 3 horas a partir dali (a da 1ª hora que passa do limiar pode ser só 50%).
            val pico = janela.drop(chuva).take(3).maxOf { it.chance }
            return Frase(TipoFrase.CHUVA_AS, janela[chuva].hora, pico)
        }
        val hoje = e.horas.filter { it.hoje }
        val quente = hoje.maxByOrNull { it.temp }
        if (e.maxHoje >= 33 && quente != null && quente.temp >= 32.5) return Frase(TipoFrase.CALOR, e.maxHoje, quente.hora)
        val fria = janela.minByOrNull { it.temp }
        if (fria != null && fria.temp <= 10.4) return Frase(TipoFrase.FRIO, Math.round(fria.temp).toInt())
        val sol = hoje.filter { it.dia && it.uv >= 8 }
        if (sol.isNotEmpty()) return Frase(TipoFrase.SOL_FORTE, sol.first().hora, (sol.last().hora + 1) % 24)
        if ((e.chanceAmanha ?: 0) >= 60) return Frase(TipoFrase.AMANHA_CHUVA, e.chanceAmanha!!)
        return Frase(TipoFrase.CALMO, e.maxHoje)
    }

    /** Levo guarda-chuva? Chovendo = agora; 50%+ nas próximas 12 h = a partir da hora; 30-49% = talvez. */
    fun guardaChuva(e: Entrada): Dica {
        if (e.chovendo) return Dica(Resposta.AGORA)
        val janela = proximas(e)
        janela.firstOrNull { it.chance >= CHUVA }?.let { return if (it == janela.first()) Dica(Resposta.AGORA) else Dica(Resposta.SIM_AS, it.hora) }
        return if (janela.any { it.chance >= SECO }) Dica(Resposta.TALVEZ) else Dica(Resposta.NAO)
    }

    /**
     * Roupa no varal: só seca com sol (de dia). De noite, a resposta é sobre amanhã. De dia: até a primeira hora com
     * 40%+ de chuva; se ela chega em menos de 2 h, melhor não; sem chuva até o pôr do sol, seca hoje.
     */
    fun varal(e: Entrada): Dica {
        val agora = e.horas.firstOrNull() ?: return Dica(Resposta.HOJE_NAO)
        if (e.chovendo) return Dica(Resposta.MELHOR_NAO)
        if (!agora.dia) return if ((e.chanceAmanha ?: 100) < VARAL) Dica(Resposta.AMANHA_SIM) else Dica(Resposta.AMANHA_NAO)
        val sol = e.horas.takeWhile { it.dia }
        val chuva = sol.indexOfFirst { it.chance >= VARAL }
        return when {
            chuva < 0 -> Dica(Resposta.SECA_HOJE)
            chuva < 2 -> Dica(Resposta.MELHOR_NAO)
            else -> Dica(Resposta.ATE, sol[chuva].hora)
        }
    }

    /**
     * Hora boa para sair (caminhar, ir à feira): de dia entre 6h e 20h, sem chuva (< 30%), entre 15° e 29° e sol que
     * não queima (UV < 6). A primeira das próximas 24 h; se for amanhã, diz "amanhã".
     */
    fun sair(e: Entrada): Dica {
        val boa = e.horas.indexOfFirst { it.hora in 6..20 && it.chance < SECO && it.temp in 15.0..29.4 && it.uv < 6 && it.dia }
        if (boa < 0) return Dica(Resposta.HOJE_NAO)
        val h = e.horas[boa]
        return when {
            boa == 0 && !e.chovendo -> Dica(Resposta.AGORA)
            h.hoje -> Dica(Resposta.AS, h.hora)
            else -> Dica(Resposta.AMANHA_AS, h.hora)
        }
    }
}

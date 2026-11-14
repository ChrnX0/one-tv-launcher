package br.paulo.tvlauncher

/**
 * Regras puras do controle da família (ideias 33 e 34 do estudo de 13/11/2026), sem nada do Android, para testar no
 * computador (FamiliaTest). Quem usa: PainelArmazem (PIN das crianças), TempoTelaTv (cota diária).
 */

/**
 * PIN das crianças (ideia 34): um segundo PIN, opcional, para destravar coisas NA FRENTE da criança (categoria oculta,
 * canal bloqueado, sair do modo simples, mexer na HOME, desligar a hora de dormir ou o tempo de tela). O PIN do painel
 * continua administrando a TV (painel, MCP, Configurações) e também vale nesses lugares: quem sabe o maior sabe o menor.
 * Guardado do mesmo jeito que o do painel (só o resumo PBKDF2, ver [PinSeguro]).
 */
object PinFamilia {
    enum class Novo { OK, INVALIDO, IGUAL_AO_PAINEL }

    /** O PIN novo das crianças pode ser gravado? Igual ao do painel não serve: daria o PIN do painel a quem só devia ter o outro. */
    fun conferirNovo(pin: String, igualAoPainel: Boolean): Novo = when {
        !PinSeguro.valido(pin) -> Novo.INVALIDO
        igualAoPainel -> Novo.IGUAL_AO_PAINEL
        else -> Novo.OK
    }

    /**
     * Confere um PIN digitado na TV. Primeiro o das crianças (o que mais se digita ali), depois o do painel. Cada
     * conferência é um PBKDF2 de ~1 s nesta TV: o do painel só roda se o das crianças falhou.
     */
    fun confereNaTv(pin: String, temFamilia: Boolean, confereFamilia: (String) -> Boolean, conferePainel: (String) -> Boolean): Boolean =
        (temFamilia && confereFamilia(pin)) || conferePainel(pin)
}

/**
 * Tempo de tela por dia (ideia 33): uma cota de minutos por dia para os apps. Conta só com a tela acesa e um app que
 * conta na frente (fora dos essenciais e da lista de apps liberados da hora de dormir). Acabou: o app volta para a HOME
 * com aviso, como na hora de dormir. Com o PIN, dá para dar mais tempo só para hoje. Zera à meia-noite.
 */
object TempoTela {
    const val CHAVE_COTA = "tela_cota"         // minutos por dia
    const val CHAVE_ESTADO = "tela_estado"     // "dia;usadoMs;extraMin"
    const val PADRAO_COTA = 120
    const val PASSO_COTA = 30
    const val MIN_COTA = 30
    const val MAX_COTA = 8 * 60
    const val EXTRA_MIN = 30
    const val AVISO_ANTES_MS = 5 * 60_000L
    /** Um intervalo maior que isto entre duas contagens (TV dormiu, processo parado) não conta inteiro. */
    const val MAX_PASSO_MS = 90_000L

    /** [dia] = AAAAMMDD do dia da conta. [usadoMs] = tempo contado hoje. [extraMin] = tempo a mais dado hoje com o PIN. */
    data class Estado(val dia: Int, val usadoMs: Long, val extraMin: Int)

    fun ler(texto: String?): Estado {
        val p = texto.orEmpty().split(';')
        return Estado(p.getOrNull(0)?.toIntOrNull() ?: 0, (p.getOrNull(1)?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
            (p.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, 24 * 60))
    }

    fun escrever(e: Estado): String = "${e.dia};${e.usadoMs};${e.extraMin}"

    /** O estado de [hoje]: se o guardado é de outro dia, começa do zero. */
    fun doDia(e: Estado, hoje: Int): Estado = if (e.dia == hoje) e else Estado(hoje, 0L, 0)

    /** Soma [passoMs] ao dia [hoje] (limitado a [MAX_PASSO_MS]; negativo, de relógio que voltou, não conta). */
    fun somar(e: Estado, hoje: Int, passoMs: Long): Estado {
        val d = doDia(e, hoje)
        return d.copy(usadoMs = d.usadoMs + passoMs.coerceIn(0L, MAX_PASSO_MS))
    }

    /** Dá [min] a mais só para hoje. */
    fun darMais(e: Estado, hoje: Int, min: Int = EXTRA_MIN): Estado = doDia(e, hoje).let { it.copy(extraMin = (it.extraMin + min).coerceAtMost(24 * 60)) }

    /** Quanto falta hoje, em ms (0 = acabou). */
    fun restanteMs(e: Estado, hoje: Int, cotaMin: Int): Long {
        val d = doDia(e, hoje)
        return ((cotaMin + d.extraMin) * 60_000L - d.usadoMs).coerceAtLeast(0L)
    }

    fun acabou(e: Estado, hoje: Int, cotaMin: Int): Boolean = restanteMs(e, hoje, cotaMin) == 0L

    /** Este app gasta a cota? A HOME, as janelas do sistema e os apps liberados da hora de dormir não gastam. */
    fun conta(pacote: String?, permitidos: Set<String>): Boolean =
        pacote != null && pacote !in HoraDormir.ESSENCIAIS && pacote !in permitidos

    /** A contagem acabou de cruzar os 5 min finais? (avisa uma vez só) */
    fun cruzouAviso(antesMs: Long, depoisMs: Long): Boolean = antesMs > AVISO_ANTES_MS && depoisMs <= AVISO_ANTES_MS && depoisMs > 0

    /** A próxima cota da linha das Configurações: +30 min, dando a volta de 8 h para 30 min. */
    fun proximaCota(atual: Int, passo: Int = PASSO_COTA): Int {
        val v = atual + passo
        return when { v > MAX_COTA -> MIN_COTA; v < MIN_COTA -> MAX_COTA; else -> v }
    }

    /** "1 h 30 min", "45 min", "2 h". */
    fun texto(ms: Long, h: String = "h", min: String = "min"): String {
        val total = ((ms + 59_999) / 60_000).toInt()
        val hs = total / 60; val m = total % 60
        return when { hs == 0 -> "$m $min"; m == 0 -> "$hs $h"; else -> "$hs $h $m $min" }
    }
}

/** Despertador com canal (ideia 30): qual canal tocar. Pura (FamiliaTest). */
object CanalDespertar {
    /** O canal [id] da lista, se existir e não estiver bloqueado pelo controle parental. null = toca o som suave. */
    fun escolher(canais: List<Canal>, id: String?, parental: ControleParental.Regras): Canal? {
        if (id.isNullOrBlank()) return null
        val c = canais.firstOrNull { it.id == id } ?: return null
        return if (ControleParental.bloqueado(c, parental)) null else c
    }
}

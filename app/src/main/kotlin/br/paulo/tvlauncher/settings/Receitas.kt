package br.paulo.tvlauncher.settings

import br.paulo.tvlauncher.ReceitasTv

/**
 * Receitas prontas (degrau 4, 17/11/2026): "Para quem é esta TV?" com 4 cartões (TV da vó, das crianças, do gamer, de
 * aluguel). Uma receita NÃO é modo novo: é só uma lista de ajustes que já existem (Modo simples, modo infantil, hora de
 * dormir, hóspede, tamanho do texto, ler o foco, card Jogos...). Um OK aplica; o "Desfazer" devolve exatamente o
 * estado de antes, a partir da foto dos ajustes tirada antes de aplicar.
 *
 * Só lógica pura (sem Android), testada em ReceitasTest. Quem lê e grava os ajustes é o [ReceitasTv].
 *
 * Regras (aceite da especificação):
 *  - desfazer devolve a foto (aplicar duas receitas seguidas guarda a foto da PRIMEIRA: desfazer volta ao começo);
 *  - qualquer mudança que DESLIGA algo protegido por PIN (modo simples, infantil, hora de dormir, hóspede) pede o PIN;
 *  - ligar o Modo simples mostra o aviso antes (quem não sabe o PIN fica preso nele);
 *  - o que depende de plano pago que este aparelho não tem não é ligado: aparece no resumo com o aviso.
 */
object Receitas {
    /**
     * Os ajustes que uma receita pode mexer. A ordem conta: é a ordem de gravar (o hóspede vem antes da visita porque
     * a visita não desliga enquanto o hóspede estiver ligado, ModoVisita.definir).
     * [protegido] = desligar pede o PIN. [binario] = 0/1; o tamanho do texto é 100/110/120.
     */
    enum class Ajuste(val protegido: Boolean = false, val binario: Boolean = true) {
        HOSPEDE(protegido = true),
        VISITA,
        MODO_SIMPLES(protegido = true),
        INFANTIL(protegido = true),
        HORA_DORMIR(protegido = true),
        TAMANHO_TEXTO(binario = false),
        LER_FOCO,
        SOM_NAVEGACAO,
        CARD_JOGOS,
        MODO_JOGO,
        CONTROLE_ABRE_JOGOS,
    }

    /** As 4 receitas. Cada uma só diz os ajustes que muda; o resto fica como está. */
    enum class Receita(val ajustes: Map<Ajuste, Int>) {
        /** Letra grande, cartões grandes com o nome falado e "Pedir ajuda". */
        VO(mapOf(Ajuste.MODO_SIMPLES to 1, Ajuste.TAMANHO_TEXTO to 120, Ajuste.LER_FOCO to 1, Ajuste.SOM_NAVEGACAO to 1)),
        /** Só o catálogo infantil, hora de dormir e o card Jogos. */
        CRIANCAS(mapOf(Ajuste.INFANTIL to 1, Ajuste.HORA_DORMIR to 1, Ajuste.CARD_JOGOS to 1)),
        /** Card Jogos, modo jogo (protetor e avisos esperam) e o controle de videogame abrindo a tela Jogos. */
        GAMER(mapOf(Ajuste.CARD_JOGOS to 1, Ajuste.MODO_JOGO to 1, Ajuste.CONTROLE_ABRE_JOGOS to 1)),
        /** Modo hóspede (e com ele o modo visita): boas-vindas, limpeza na saída, nada pessoal na HOME. */
        ALUGUEL(mapOf(Ajuste.HOSPEDE to 1, Ajuste.VISITA to 1)),
    }

    /** O plano mínimo de cada ajuste, quando houver: o modo infantil é o bloqueio do player (Planos.Recurso.IPTV_BLOQUEIO). */
    val RECURSO: Map<Ajuste, Planos.Recurso> = mapOf(Ajuste.INFANTIL to Planos.Recurso.IPTV_BLOQUEIO)

    /** Avisos que aparecem ANTES de aplicar. */
    enum class Aviso {
        /** Liga o Modo simples e há PIN: só quem sabe o PIN sai dele. */
        PRENDE,
        /** Liga algo que deveria ter trava (simples, infantil, dormir, hóspede), mas não há PIN: qualquer um desliga. */
        SEM_PIN,
        /** Parte da receita precisa de um plano que este aparelho não tem: essa parte não é ligada. */
        PLANO,
    }

    /** O valor válido de [a]: binário vira 0/1; o tamanho cai no mais próximo de 100/110/120 (TamanhoTexto.OPCOES). */
    fun normalizar(a: Ajuste, v: Int): Int = if (a.binario) (if (v != 0) 1 else 0)
        else listOf(100, 110, 120).minByOrNull { kotlin.math.abs(it - v) }!!

    /**
     * O que aplicar de [r] partindo de [agora]: só os ajustes que mudam de valor, sem os que o plano não deixa
     * ([permite]). Ordem do enum [Ajuste].
     */
    fun mudancas(r: Receita, agora: Map<Ajuste, Int>, permite: (Planos.Recurso) -> Boolean = { true }): List<Pair<Ajuste, Int>> =
        Ajuste.values().mapNotNull { a ->
            val alvo = r.ajustes[a] ?: return@mapNotNull null
            if (RECURSO[a]?.let(permite) == false) return@mapNotNull null
            val v = normalizar(a, alvo)
            if (agora[a]?.let { normalizar(a, it) } == v) null else a to v
        }

    /** Os ajustes de [r] que o plano não deixa ligar (para o aviso [Aviso.PLANO] e o resumo). */
    fun bloqueadosPeloPlano(r: Receita, permite: (Planos.Recurso) -> Boolean): List<Ajuste> =
        r.ajustes.keys.filter { RECURSO[it]?.let(permite) == false }.sortedBy { it.ordinal }

    /** Os avisos de aplicar [r] (vazio = aplica direto). */
    fun avisos(r: Receita, agora: Map<Ajuste, Int>, temPin: Boolean, permite: (Planos.Recurso) -> Boolean = { true }): Set<Aviso> {
        val liga = mudancas(r, agora, permite).filter { it.second == 1 }.map { it.first }
        val s = LinkedHashSet<Aviso>()
        if (Ajuste.MODO_SIMPLES in liga && temPin) s += Aviso.PRENDE
        if (!temPin && liga.any { it.protegido }) s += Aviso.SEM_PIN
        if (bloqueadosPeloPlano(r, permite).isNotEmpty()) s += Aviso.PLANO
        return s
    }

    /** Uma lista de mudanças pede o PIN? Sim se DESLIGA algo protegido e há PIN definido (sem PIN não há o que pedir). */
    fun pedePin(mudancas: List<Pair<Ajuste, Int>>, temPin: Boolean): Boolean =
        temPin && mudancas.any { (a, v) -> a.protegido && v == 0 }

    /**
     * A foto de antes, a guardar ao aplicar: a já guardada ganha (aplicar duas receitas seguidas e desfazer volta ao
     * estado de ANTES da primeira); ajustes que a foto antiga não tinha entram com o valor de agora.
     */
    fun guardar(jaGuardada: String?, agora: Map<Ajuste, Int>): String {
        val antiga = deTexto(jaGuardada)
        return paraTexto(agora + antiga)   // à direita ganha
    }

    /** O que desfazer: voltar cada ajuste da [foto] que hoje está diferente. Vazio = nada a desfazer. */
    fun desfazer(foto: String?, agora: Map<Ajuste, Int>): List<Pair<Ajuste, Int>> {
        val f = deTexto(foto)
        return Ajuste.values().mapNotNull { a ->
            val v = f[a] ?: return@mapNotNull null
            if (agora[a]?.let { normalizar(a, it) } == v) null else a to v
        }
    }

    /** "HOSPEDE=0;MODO_SIMPLES=1;TAMANHO_TEXTO=110" (ordem do enum). */
    fun paraTexto(m: Map<Ajuste, Int>): String =
        Ajuste.values().filter { it in m }.joinToString(";") { "${it.name}=${normalizar(it, m.getValue(it))}" }

    /** Lê a foto; pedaço estranho é ignorado (foto vinda de versão mais nova, ou corrompida). */
    fun deTexto(t: String?): Map<Ajuste, Int> {
        if (t.isNullOrBlank()) return emptyMap()
        val m = LinkedHashMap<Ajuste, Int>()
        for (p in t.split(';')) {
            val (k, v) = p.split('=').takeIf { it.size == 2 } ?: continue
            val a = Ajuste.values().firstOrNull { it.name == k.trim() } ?: continue
            val n = v.trim().toIntOrNull() ?: continue
            m[a] = normalizar(a, n)
        }
        return m
    }

    /** A receita do nome (painel, adb). */
    fun dePalavra(p: String?): Receita? = Receita.values().firstOrNull { it.name.equals(p?.trim(), ignoreCase = true) }
}

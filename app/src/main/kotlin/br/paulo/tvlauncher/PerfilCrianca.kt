package br.paulo.tvlauncher

/**
 * Perfil de criança (ideia 11, 14/11/2026): uma marca por conta. Trocar PARA um perfil de criança liga, de uma vez, o
 * modo infantil (ControleParental.Regras.infantil) e a hora de dormir (Extras.Chave.HORA_DORMIR), guardando como
 * estavam antes. SAIR dele exige o PIN dos pais (o do painel; o PIN das crianças não serve) e devolve o estado guardado.
 *
 * Só lógica pura (sem Android): testada em PerfilCriancaTest. Quem aplica é o [PerfilCriancaTv].
 */
object PerfilCrianca {
    /** Como o modo infantil e a hora de dormir estavam (ou devem ficar). */
    data class Estado(val infantil: Boolean, val dormir: Boolean)

    /**
     * O que fazer numa troca de perfil:
     *  - [pedePin]: sair de um perfil de criança (para qualquer outro) exige o PIN dos pais;
     *  - [ligar]: o destino é de criança e a origem não: guarda o estado de agora e liga os dois;
     *  - [restaurar]: o destino não é de criança e a origem é: volta ao estado guardado.
     * Criança -> criança: pede o PIN (é sair de um perfil de criança), mantém tudo ligado e o estado guardado de antes.
     */
    data class Troca(val pedePin: Boolean, val ligar: Boolean, val restaurar: Boolean) {
        val nada: Boolean get() = !pedePin && !ligar && !restaurar
    }

    /**
     * [temPin] = há PIN do painel definido. Sem PIN não há como pedir (não dá para travar com uma senha que não existe,
     * como no TravaHome); para isso não virar brecha, marcar um perfil como de criança exige PIN definido ([podeMarcar]).
     */
    fun decidir(de: String?, para: String, criancas: Set<String>, temPin: Boolean): Troca {
        if (de == para) return Troca(false, false, false)
        val deCrianca = de != null && de in criancas
        val paraCrianca = para in criancas
        return Troca(pedePin = deCrianca && temPin, ligar = paraCrianca && !deCrianca, restaurar = deCrianca && !paraCrianca)
    }

    /** Ligar ou desligar a marca de criança pede o PIN, e só existe com PIN definido. */
    fun podeMarcar(temPin: Boolean): Boolean = temPin

    /** O estado a guardar ao entrar: o já guardado ganha (entrar duas vezes não guarda o "tudo ligado" por cima). */
    fun guardar(jaGuardado: String?, agora: Estado): String = jaGuardado?.takeIf { deTexto(it) != null } ?: paraTexto(agora)

    /** O estado ao sair: o guardado; sem nada guardado (ex.: veio de um backup), fica como está (o lado seguro). */
    fun aoSair(guardado: String?, agora: Estado): Estado = deTexto(guardado) ?: agora

    /** O estado com o perfil de criança valendo. */
    val LIGADO = Estado(infantil = true, dormir = true)

    fun paraTexto(e: Estado): String = "i" + (if (e.infantil) 1 else 0) + "d" + (if (e.dormir) 1 else 0)

    fun deTexto(t: String?): Estado? {
        val m = Regex("i([01])d([01])").matchEntire(t?.trim() ?: return null) ?: return null
        return Estado(m.groupValues[1] == "1", m.groupValues[2] == "1")
    }

    /** Liga/desliga [chave] (PerfilDados.sanitizar da conta) no conjunto das contas de criança. */
    fun alternar(criancas: Set<String>, chave: String): Set<String> =
        if (chave.isEmpty()) criancas else if (chave in criancas) criancas - chave else criancas + chave

    /** O aviso curto da troca: o nome é a parte do e-mail antes do @ ("ana@gmail.com" -> "ana"). */
    fun nomeCurto(conta: String): String = conta.substringBefore('@').ifEmpty { conta }
}

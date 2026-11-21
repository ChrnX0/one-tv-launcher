package br.paulo.tvlauncher.network

/**
 * "Não pergunte de novo por um tempo" por endereço (18/11/2026, unidade de rede). Um aparelho da rede podia pedir a
 * conexão MTP sem parar, cada vez com um nome novo: cada pedido abria o "Permitir?" na tela de quem está vendo, e
 * enquanto ele estava aberto o computador verdadeiro era recusado. Depois de um "não" (ou pergunta sem resposta) o
 * endereço fica [duracaoMs] sem perguntar. Pura (testada na JVM); [agora] vem de fora.
 */
class CastigoIp(private val duracaoMs: Long = 5 * 60_000L, private val maximo: Int = 64) {
    private val ate = LinkedHashMap<String, Long>()

    @Synchronized fun emCastigo(ip: String, agora: Long): Boolean {
        val fim = ate[ip] ?: return false
        if (agora < fim) return true
        ate.remove(ip); return false
    }

    @Synchronized fun castigar(ip: String, agora: Long) {
        ate.remove(ip)
        ate[ip] = agora + duracaoMs
        // Teto: um aparelho trocando de endereço não enche a memória (sai o mais antigo).
        while (ate.size > maximo) ate.remove(ate.keys.first())
    }

    @Synchronized fun limpar() = ate.clear()
}

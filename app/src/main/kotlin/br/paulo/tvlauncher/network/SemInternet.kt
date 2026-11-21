package br.paulo.tvlauncher.network

/**
 * Ideia 88 (14/11/2026, dono): sem internet, a HOME diz em UMA linha o que continua funcionando, para ninguém achar que
 * "a TV quebrou". Ex.: "Sem internet · Funcionam: pendrive, Na rede, Gravações". Some sozinha quando a internet volta.
 *
 * Só lógica (testes em SemInternetTest). Quem mede é a HOME (MainActivity: rede validada pelo Android, pendrive
 * montado, contas de Na rede, destino das gravações). Estar sem internet = a rede padrão não tem NET_CAPABILITY_VALIDATED
 * (o Android testa a internet sozinho) ou não há rede nenhuma.
 */
object SemInternet {
    /** O que funciona sem internet. A ordem é a da frase. */
    enum class Item { PENDRIVE, NA_REDE, GRAVACOES }

    /**
     * [redeLocal]: há Wi-Fi/cabo (só falta a internet). [pendrive]: um pendrive/HD montado. [servidores]: endereços
     * das contas de Na rede. [destinoGravacao]: "pendrive", "rede" ou null (sem destino configurado).
     */
    class Estado(val redeLocal: Boolean, val pendrive: Boolean, val servidores: List<String>, val destinoGravacao: String?)

    /** Espera antes de mostrar: no boot e na troca de rede o Android leva alguns segundos para validar a internet. */
    const val ESPERA_MS = 20_000L

    fun itens(e: Estado): List<Item> = buildList {
        if (e.pendrive) add(Item.PENDRIVE)
        // Servidor de casa (NAS, Jellyfin, pasta do PC) só funciona com a rede local de pé; servidor da internet, não.
        if (e.redeLocal && e.servidores.any { servidorDeCasa(it) }) add(Item.NA_REDE)
        val gravacoes = when (e.destinoGravacao) { "pendrive" -> e.pendrive; "rede" -> e.redeLocal; else -> false }
        if (gravacoes) add(Item.GRAVACOES)
    }

    /** [nomes] na ordem de [Item]. Sem nada funcionando, só "Sem internet". */
    fun frase(itens: List<Item>, semInternet: String, funcionam: (String) -> String, nomes: Map<Item, String>): String =
        if (itens.isEmpty()) semInternet
        else "$semInternet · " + funcionam(itens.joinToString(", ") { nomes[it] ?: it.name })

    /**
     * O servidor de uma conta de Na rede é de dentro de casa? Aceita "192.168.1.5", "nas.local", "\\NAS\filmes",
     * "http://192.168.1.5:8096/", "smb://usuario@nas/pasta", "[fd00::1]". Regras do host em [hostDeCasa].
     */
    fun servidorDeCasa(servidor: String): Boolean {
        var s = servidor.trim().replace('\\', '/').trimStart('/')
        s = s.substringAfter("://")                // tira o esquema (http, smb, nfs…)
        s = s.substringBefore('/').substringAfterLast('@')   // tira o caminho e o usuário
        if (s.startsWith("[")) {                   // IPv6: só os de casa (fd/fc = ULA, fe80 = link-local)
            val v6 = s.substringAfter('[').substringBefore(']').lowercase()
            return v6.startsWith("fd") || v6.startsWith("fc") || v6.startsWith("fe80") || v6 == "::1"
        }
        s = s.substringBefore(':')                 // tira a porta
        return s.isNotEmpty() && hostDeCasa(s)
    }

    /** As mesmas regras de CasaHA.hostDeCasa (copiadas: CasaHA carrega Android e este arquivo testa no PC). */
    private fun hostDeCasa(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || ('.' !in h)) return true   // nome sem ponto: resolvido pelo roteador ("nas")
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val n = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || n[0] == 127 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
}

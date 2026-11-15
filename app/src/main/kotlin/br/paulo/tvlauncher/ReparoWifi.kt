package br.paulo.tvlauncher

/**
 * Wi-Fi que não volta sozinho (15/11/2026): o Chromecast da Sala ficou 3h40 ligado e SEM Wi-Fi (sem sinal nenhum,
 * de 02:57 a 06:37 locais) e só voltou quando reiniciou. Nesse tempo o MCP, o painel e o backup ficaram fora.
 *
 * O que o launcher faz agora, medindo a cada 30 s enquanto a rede está fora ([HistoricoRede]):
 *  - Wi-Fi LIGADO nas configurações mas sem conexão há [ESPERA_MS] (5 min): desliga e religa o Wi-Fi (Device Owner);
 *  - depois de [MAX_RELIGAR] religadas sem voltar: reinicia a TV, se não houver som tocando e se a última vez que
 *    o launcher reiniciou por isso foi há mais de [INTERVALO_REINICIO_MS] (6 h), para nunca entrar em ciclo.
 * Wi-Fi DESLIGADO de propósito, ou TV no cabo: não mexe em nada.
 * Esta parte é só a decisão (sem Android), com teste no PC.
 */
object ReparoWifi {
    const val ESPERA_MS = 5 * 60_000L
    const val ENTRE_ACOES_MS = 5 * 60_000L
    const val MAX_RELIGAR = 2
    const val INTERVALO_REINICIO_MS = 6 * 60 * 60_000L

    enum class Acao { NADA, RELIGAR_WIFI, REINICIAR }

    /**
     * @param semWifiDesde quando a TV ficou sem Wi-Fi conectado (null = conectada, no cabo ou Wi-Fi desligado de propósito)
     * @param religadas quantas vezes o Wi-Fi já foi religado nesta queda
     * @param ultimaAcao quando foi a última religada (0 = nenhuma nesta queda)
     * @param ultimoReinicio quando o launcher reiniciou a TV por falta de Wi-Fi pela última vez (0 = nunca)
     * @param somTocando se há áudio tocando (alguém pode estar vendo algo gravado ou do pendrive)
     */
    fun decidir(agora: Long, semWifiDesde: Long?, religadas: Int, ultimaAcao: Long, ultimoReinicio: Long,
                somTocando: Boolean): Acao {
        if (semWifiDesde == null || agora - semWifiDesde < ESPERA_MS) return Acao.NADA
        if (ultimaAcao > 0 && agora - ultimaAcao < ENTRE_ACOES_MS) return Acao.NADA
        if (religadas < MAX_RELIGAR) return Acao.RELIGAR_WIFI
        if (somTocando) return Acao.NADA
        if (ultimoReinicio > 0 && agora - ultimoReinicio in 0 until INTERVALO_REINICIO_MS) return Acao.NADA
        return Acao.REINICIAR
    }
}

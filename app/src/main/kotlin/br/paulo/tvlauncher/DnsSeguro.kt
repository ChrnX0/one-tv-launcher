package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * DNS com rede de segurança (10/11/2026, ideia B da pesquisa de launchers parecidos; inspirada no "DNS filter" do
 * projeto tv-remote, que "puts the old setting back" se os nomes param de resolver em 30 s).
 *
 * O que já existia: [Dns.voltarAoAutomaticoSeQuebrado] volta para o Automático quando o servidor fixo cai DEPOIS de
 * um tempo. O que faltava: logo depois de a pessoa TROCAR o servidor na tela de DNS, se o novo não resolver nomes,
 * voltar para o que estava ANTES (não para o Automático). É o caso do apagão de 04/11/2026: um servidor errado e a casa
 * fica sem internet até alguém perceber.
 *
 * A regra de decisão ([deveVoltar]) é pura, para testar no PC sem TV (DnsSeguroTest).
 */
object DnsSeguro {
    private const val TAG = "TvLauncher.DnsSeguro"
    /** Tentativas de [Dns.testarResolucao]; cada uma insiste por ~9 s. 3 = ~27 s, perto dos 30 s do tv-remote. */
    private const val TENTATIVAS = 3

    /**
     * Volta para o anterior só quando TUDO isto vale:
     *  - o teste de resolução falhou;
     *  - o novo é um servidor FIXO (só um servidor fixo consegue quebrar a resolução; se o novo é Automático ou
     *    Desligado e mesmo assim não resolve, o problema é a rede, e voltar não ajudaria);
     *  - o sistema ainda está no valor novo (se alguém trocou de novo nesse meio tempo, não mexe);
     *  - o anterior é diferente do novo.
     */
    fun deveVoltar(resolveu: Boolean, novo: Dns.Estado, atual: Dns.Estado, anterior: Dns.Estado): Boolean {
        if (resolveu) return false
        if (novo.modo != Dns.Modo.HOST) return false
        if (!igual(atual, novo)) return false
        return !igual(anterior, novo)
    }

    fun igual(a: Dns.Estado, b: Dns.Estado): Boolean =
        a.modo == b.modo && (a.modo != Dns.Modo.HOST || a.host.equals(b.host, ignoreCase = true))

    /**
     * Chamar FORA da thread da tela, logo depois de [Dns.aplicar]. Testa por ~27 s; se precisar, regrava [anterior].
     * Devolve true se voltou.
     */
    fun conferirDepoisDeTrocar(ctx: Context, anterior: Dns.Estado, novo: Dns.Estado): Boolean {
        var resolveu = false
        for (i in 1..TENTATIVAS) {
            if (Dns.testarResolucao(ctx) != null) { resolveu = true; break }
        }
        val atual = Dns.atual(ctx)
        if (!deveVoltar(resolveu, novo, atual, anterior)) return false
        val ok = Dns.aplicar(ctx, anterior.modo, anterior.host)
        Log.w(TAG, "servidor ${novo.host} não resolveu nomes; voltei para ${anterior.modo} ${anterior.host ?: ""} (gravou=$ok)")
        return ok
    }
}

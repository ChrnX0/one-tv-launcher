package br.paulo.tvlauncher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Área "Conta" das Configurações (07/11/2026, pedido do dono): os dados da conta de IPTV, como a área de usuário do
 * 1-Stream mostra — nome da lista, usuário, situação, criação e validade, telas simultâneas e tipo de login.
 *
 * Os dados vêm do próprio login Xtream (o "user_info" que o servidor devolve em player_api.php), com a conta já
 * cadastrada na TV: nada de pedir usuário e senha de novo. Lista M3U comum não tem esses dados (o protocolo não manda);
 * só uma lista "get.php?username=…" de painel Xtream tem, e aí ela é consultada como Xtream (Xtream.contaDaLista).
 *
 * Aqui fica só a parte pura (montar as linhas), testada em ContaIptvTest. Quem baixa é o PlayerCarga.situacao.
 */
object ContaIptv {
    /** Uma linha da área Conta: rótulo à esquerda, valor à direita. `alerta` pinta o valor (vencida, perto de vencer). */
    data class Linha(val rotulo: String, val valor: String, val alerta: Boolean = false)

    /** Dias que faltam para vencer a partir dos quais o aviso fica amarelo. */
    const val DIAS_AVISO = 7

    fun tipoLogin(f: FonteTv): String = when {
        f.tipo == FonteTv.TIPO_XTREAM -> "Xtream Codes"
        Xtream.contaDaLista(f.endereco) != null -> "M3U (painel Xtream)"
        else -> "M3U"
    }

    /** A conta Xtream por trás da fonte (Xtream de verdade ou lista get.php), ou null se não houver como consultar. */
    fun contaConsultavel(f: FonteTv): Xtream.Conta? = f.xtream ?: Xtream.contaDaLista(f.endereco)

    /** "Ativa" / "Desativada (vencida)" etc. O texto que o servidor manda é traduzido quando é um dos conhecidos. */
    fun estado(s: Xtream.Situacao): String {
        if (s.ativa) return if (s.teste) "Ativa (teste)" else "Ativa"
        val motivo = when (s.status.lowercase(Locale.ROOT)) {
            "expired" -> "vencida"
            "banned" -> "bloqueada pelo vendedor"
            "disabled" -> "desligada pelo vendedor"
            "" -> "usuário ou senha recusados"
            else -> s.status.take(40)
        }
        return "Desativada ($motivo)"
    }

    fun data(segundos: Long, fuso: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).apply { timeZone = fuso }.format(Date(segundos * 1000))

    /** Quantos dias antes do vencimento a HOME começa a avisar (11/11/2026, visto no 1 Play). */
    const val AVISAR_DIAS = DIAS_AVISO   // o mesmo limite do amarelo da área Conta: a pílula e a Conta não podem discordar

    /**
     * Texto da pílula da HOME para a lista que vence primeiro: "Playon vence em 3 dias", "vence amanhã", "vence hoje",
     * "venceu". null = nenhuma vence nos próximos [AVISAR_DIAS] dias (nem venceu há mais de 30: aí já não adianta avisar).
     * `itens` = nome da lista e vencimento em segundos (exp_date do Xtream).
     */
    fun avisoVencimento(itens: List<Pair<String, Long>>, agoraMs: Long, fuso: TimeZone = TimeZone.getDefault()): String? {
        // Lista que venceu há mais de 30 dias não conta (não adianta avisar). Ela sai ANTES de escolher a primeira a
        // vencer: senão uma lista antiga e abandonada escondia o aviso de outra que vence amanhã.
        val (nome, expira) = itens.filter { Datas.diasAte(it.second * 1000, agoraMs, fuso) >= -30 }.minByOrNull { it.second } ?: return null
        val dias = Datas.diasAte(expira * 1000, agoraMs, fuso)
        val quando = when {
            expira * 1000 <= agoraMs -> "venceu"
            dias > AVISAR_DIAS -> return null
            dias == 0L -> "vence hoje"
            dias == 1L -> "vence amanhã"
            else -> "vence em $dias dias"
        }
        return "${nome.take(24)} $quando"
    }

    /** "vence em 12 dias", "vence hoje", "venceu há 3 dias". */
    fun prazo(expiraEm: Long, agoraMs: Long): String {
        val dias = Math.floorDiv(expiraEm * 1000 - agoraMs, 86_400_000L)
        return when {
            dias > 1 -> "faltam $dias dias"
            dias == 1L -> "falta 1 dia"
            dias == 0L -> "vence hoje"
            dias == -1L -> "venceu ontem"
            else -> "venceu há ${-dias} dias"
        }
    }

    /**
     * As linhas da área Conta para uma fonte. `s` null = não deu para consultar (sem rede, ou lista sem dados de conta);
     * `consultou` diz qual dos dois, para a mensagem certa.
     */
    fun linhas(f: FonteTv, s: Xtream.Situacao?, consultou: Boolean, agoraMs: Long, fuso: TimeZone = TimeZone.getDefault()): List<Linha> {
        val l = ArrayList<Linha>()
        l += Linha("Lista de reprodução", f.nome.ifEmpty { "(sem nome)" })
        val usuario = s?.usuario?.ifEmpty { null } ?: contaConsultavel(f)?.usuario
        if (usuario != null) l += Linha("Usuário", usuario)
        l += Linha("Tipo de login", tipoLogin(f))
        l += Linha("Servidor", f.servidorVisivel)
        if (s == null) {
            l += Linha("Situação", if (consultou) "o servidor não respondeu agora · tente de novo" else "lista M3U comum: o servidor não informa os dados da conta")
            return l
        }
        l += Linha("Situação", estado(s), alerta = !s.ativa)
        l += Linha("Criada em", s.criadaEm?.let { data(it, fuso) } ?: "não informado")
        val exp = s.expiraEm
        if (exp == null) l += Linha("Vence em", "sem data de validade")
        else {
            val diasFaltando = Math.floorDiv(exp * 1000 - agoraMs, 86_400_000L)
            l += Linha("Vence em", "${data(exp, fuso)} · ${prazo(exp, agoraMs)}", alerta = diasFaltando < DIAS_AVISO)
        }
        val telas = when {
            s.telasMax == null -> "não informado"
            s.telasEmUso != null -> "${s.telasMax} (${s.telasEmUso} em uso agora)"
            else -> s.telasMax.toString()
        }
        l += Linha("Conexões máximas", telas)
        if (s.mensagem.isNotEmpty()) l += Linha("Recado do vendedor", s.mensagem)
        return l
    }
}

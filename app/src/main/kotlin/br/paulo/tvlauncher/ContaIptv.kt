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

    /**
     * Os textos da área Conta. O padrão é o português (o que os testes da JVM conferem); a tela das Configurações passa
     * os do idioma da TV, lidos dos recursos (res/values = inglês, res/values-pt = português).
     */
    data class Textos(
        val lista: String = "Lista de reprodução", val semNome: String = "(sem nome)", val usuario: String = "Usuário",
        val tipoLogin: String = "Tipo de login", val servidor: String = "Servidor", val situacao: String = "Situação",
        val naoRespondeu: String = "o servidor não respondeu agora · tente de novo",
        val m3uComum: String = "lista M3U comum: o servidor não informa os dados da conta",
        val criada: String = "Criada em", val naoInformado: String = "não informado", val vence: String = "Vence em",
        val semValidade: String = "sem data de validade", val conexoes: String = "Conexões máximas",
        val emUso: (Int, Int) -> String = { max, uso -> "$max ($uso em uso agora)" },
        val recado: String = "Recado do vendedor", val m3uXtream: String = "M3U (painel Xtream)",
        val ativa: String = "Ativa", val ativaTeste: String = "Ativa (teste)",
        val desativada: (String) -> String = { "Desativada ($it)" },
        val vencida: String = "vencida", val banida: String = "bloqueada pelo vendedor",
        val desligada: String = "desligada pelo vendedor", val recusado: String = "usuário ou senha recusados",
        val faltam: (Long) -> String = { "faltam $it dias" }, val falta1: String = "falta 1 dia",
        val venceHoje: String = "vence hoje", val venceuOntem: String = "venceu ontem",
        val venceuHa: (Long) -> String = { "venceu há $it dias" },
        val formatoData: String = "dd/MM/yyyy", val local: Locale = Locale("pt", "BR"),
    )

    private val PT = Textos()

    /** Dias que faltam para vencer a partir dos quais o aviso fica amarelo. */
    const val DIAS_AVISO = 7

    fun tipoLogin(f: FonteTv, t: Textos = PT): String = when {
        f.tipo == FonteTv.TIPO_XTREAM -> "Xtream Codes"
        Xtream.contaDaLista(f.endereco) != null -> t.m3uXtream
        else -> "M3U"
    }

    /** A conta Xtream por trás da fonte (Xtream de verdade ou lista get.php), ou null se não houver como consultar. */
    fun contaConsultavel(f: FonteTv): Xtream.Conta? = f.xtream ?: Xtream.contaDaLista(f.endereco)

    /** "Ativa" / "Desativada (vencida)" etc. O texto que o servidor manda é traduzido quando é um dos conhecidos. */
    fun estado(s: Xtream.Situacao, t: Textos = PT): String {
        if (s.ativa) return if (s.teste) t.ativaTeste else t.ativa
        val motivo = when (s.status.lowercase(Locale.ROOT)) {
            "expired" -> t.vencida
            "banned" -> t.banida
            "disabled" -> t.desligada
            "" -> t.recusado
            else -> s.status.take(40)
        }
        return t.desativada(motivo)
    }

    fun data(segundos: Long, fuso: TimeZone = TimeZone.getDefault(), t: Textos = PT): String =
        SimpleDateFormat(t.formatoData, t.local).apply { timeZone = fuso }.format(Date(segundos * 1000))

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
    fun prazo(expiraEm: Long, agoraMs: Long, t: Textos = PT): String {
        val dias = Math.floorDiv(expiraEm * 1000 - agoraMs, 86_400_000L)
        return when {
            dias > 1 -> t.faltam(dias)
            dias == 1L -> t.falta1
            dias == 0L -> t.venceHoje
            dias == -1L -> t.venceuOntem
            else -> t.venceuHa(-dias)
        }
    }

    /**
     * As linhas da área Conta para uma fonte. `s` null = não deu para consultar (sem rede, ou lista sem dados de conta);
     * `consultou` diz qual dos dois, para a mensagem certa.
     */
    fun linhas(f: FonteTv, s: Xtream.Situacao?, consultou: Boolean, agoraMs: Long, fuso: TimeZone = TimeZone.getDefault(),
               t: Textos = PT): List<Linha> {
        val l = ArrayList<Linha>()
        l += Linha(t.lista, f.nome.ifEmpty { t.semNome })
        val usuario = s?.usuario?.ifEmpty { null } ?: contaConsultavel(f)?.usuario
        if (usuario != null) l += Linha(t.usuario, usuario)
        l += Linha(t.tipoLogin, tipoLogin(f, t))
        l += Linha(t.servidor, f.servidorVisivel)
        if (s == null) {
            l += Linha(t.situacao, if (consultou) t.naoRespondeu else t.m3uComum)
            return l
        }
        l += Linha(t.situacao, estado(s, t), alerta = !s.ativa)
        l += Linha(t.criada, s.criadaEm?.let { data(it, fuso, t) } ?: t.naoInformado)
        val exp = s.expiraEm
        if (exp == null) l += Linha(t.vence, t.semValidade)
        else {
            val diasFaltando = Math.floorDiv(exp * 1000 - agoraMs, 86_400_000L)
            l += Linha(t.vence, "${data(exp, fuso, t)} · ${prazo(exp, agoraMs, t)}", alerta = diasFaltando < DIAS_AVISO)
        }
        val telas = when {
            s.telasMax == null -> t.naoInformado
            s.telasEmUso != null -> t.emUso(s.telasMax, s.telasEmUso)
            else -> s.telasMax.toString()
        }
        l += Linha(t.conexoes, telas)
        if (s.mensagem.isNotEmpty()) l += Linha(t.recado, s.mensagem)
        return l
    }
}

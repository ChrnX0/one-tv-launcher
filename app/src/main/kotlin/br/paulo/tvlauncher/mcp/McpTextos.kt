package br.paulo.tvlauncher.mcp

/**
 * Textos das páginas de login do conector (MCP) no navegador, em português ou inglês conforme o idioma do NAVEGADOR
 * (cabeçalho Accept-Language), não o da TV: quem lê é a pessoa no celular ou no computador (15/11/2026).
 * Puro, para o teste JVM (McpTextosTest).
 */
class McpTextos(val en: Boolean) {
    companion object {
        /** Inglês, a não ser que o idioma preferido do navegador seja português. Sem cabeçalho: inglês (o padrão do app). */
        fun de(acceptLanguage: String?): McpTextos {
            val primeiro = acceptLanguage?.split(',')?.map { it.substringBefore(';').trim() }?.firstOrNull { it.isNotEmpty() && it != "*" }
            return McpTextos(primeiro == null || !primeiro.lowercase().startsWith("pt"))
        }

        /** Mensagens que vêm de McpAutorizacao e LoginGoogle (em português no código). Fora da lista: como vieram. */
        private val INGLES = mapOf(
            "faltam redirect_uris" to "redirect_uris missing",
            "endereço de volta não permitido" to "return address not allowed",
            "cliente desconhecido; registre o conector de novo" to "unknown client; register the connector again",
            "endereço de volta diferente do registrado" to "return address differs from the registered one",
            "só response_type=code" to "only response_type=code",
            "falta PKCE S256" to "PKCE S256 missing",
            "login Google não configurado" to "Google sign-in is not set up",
            "muitos logins seguidos; espere um minuto" to "too many sign-ins in a row; wait a minute",
            "o login foi negado no Google" to "the sign-in was denied on Google",
            "o código venceu; comece de novo" to "the code expired; start again",
            "outro login começou; comece de novo" to "another sign-in started; start again",
            "o Google não confirmou o e-mail" to "Google did not confirm the e-mail",
            "erro ao falar com o Google" to "error talking to Google",
        )
        private val CONTA_FORA = Regex("^a conta (.+) não está em nenhum perfil desta TV$")
        private val GOOGLE_RESPONDEU = Regex("^Google respondeu: (.+)$")
        private val GOOGLE_RECUSOU = Regex("^o Google recusou \\((\\d+)\\): confira o id e o segredo do cliente$")
    }

    private fun t(pt: String, ingles: String) = if (en) ingles else pt

    /** Traduz uma mensagem de McpAutorizacao/LoginGoogle. */
    fun mensagem(m: String): String {
        if (!en) return m
        INGLES[m]?.let { return it }
        CONTA_FORA.matchEntire(m)?.let { return "the account ${it.groupValues[1]} is not in any profile of this TV" }
        GOOGLE_RESPONDEU.matchEntire(m)?.let { return "Google answered: ${it.groupValues[1]}" }
        GOOGLE_RECUSOU.matchEntire(m)?.let { return "Google refused (${it.groupValues[1]}): check the client id and secret" }
        return m
    }

    val lang get() = t("pt-BR", "en")
    val pedidoInvalido get() = t("Pedido inválido", "Invalid request")
    val useGoogle get() = t("Use a conta Google", "Use the Google account")
    val useGoogleTexto get() = t("Este login é pela conta Google. Abra o endereço de novo.", "This sign-in uses the Google account. Open the address again.")
    val naoDeu get() = t("Não deu", "It did not work")
    val desligado get() = t("Desligado", "Turned off")
    val desligadoTexto get() = t("O acesso pela internet foi desligado. Religue nas Configurações do launcher, na TV.",
        "Internet access was turned off. Turn it back on in the launcher Settings, on the TV.")
    val pinDemaisTexto get() = t("PIN errado demais. O acesso pela internet foi desligado; religue na TV.",
        "Too many wrong PINs. Internet access was turned off; turn it back on on the TV.")
    fun espere(s: Long) = t("Muitas tentativas erradas. Espere $s s.", "Too many wrong attempts. Wait $s s.")
    fun pinErrado(restantes: Int) = t("PIN errado ($restantes tentativa(s) antes de esperar).", "Wrong PIN ($restantes attempt(s) before waiting).")
    fun pinBloqueado(min: Long) = t("PIN errado. Bloqueado por $min min.", "Wrong PIN. Locked for $min min.")
    val negado get() = t("Negado na TV", "Denied on the TV")
    val negadoTexto get() = t("A TV não autorizou este login (negado no controle, ninguém respondeu em 60 s, ou bloqueado nas Confirmações).",
        "The TV did not allow this sign-in (denied on the remote, nobody answered in 60 s, or blocked in Confirmations).")
    val vencido get() = t("Login vencido", "Sign-in expired")
    val vencidoTexto get() = t("Abra o endereço de novo pelo app.", "Open the address again from the app.")
    val jaUsado get() = t("Já usado", "Already used")
    val jaUsadoTexto get() = t("Este login já foi concluído.", "This sign-in is already done.")
    val naoLiberado get() = t("Não liberado", "Not allowed")
    fun querUsar(nomeHtml: String) = t("<b>$nomeHtml</b> quer usar as ferramentas da TV da sala.", "<b>$nomeHtml</b> wants to use the living room TV tools.")
    fun querUsarDestino(nomeHtml: String, destinoHtml: String) = t(
        "<b>$nomeHtml</b> (volta para <b>$destinoHtml</b>) quer usar as ferramentas da TV da sala.",
        "<b>$nomeHtml</b> (returns to <b>$destinoHtml</b>) wants to use the living room TV tools.")
    fun abra(linkHtml: String) = t("1. Abra $linkHtml (pode ser no celular).", "1. Open $linkHtml (the phone works too).")
    val entreGoogle get() = t("2. Entre com a conta Google de um dos perfis da TV e digite o código:",
        "2. Sign in with the Google account of one of the TV profiles and type the code:")
    val continua get() = t("Esta página continua sozinha quando o Google confirmar.", "This page goes on by itself when Google confirms.")
    val entrarGoogle get() = t("Entrar com a conta Google", "Sign in with Google")
    val comOk get() = t("Ações só acontecem depois de um OK no controle da TV.", "Actions only happen after an OK on the TV remote.")
    val semOk get() = t("Atenção: as ações pedidas (abrir app, volume, despertador...) acontecem na hora, sem OK no controle.",
        "Note: requested actions (open app, volume, alarm...) happen right away, without an OK on the remote.")
    val pinPainel get() = t("PIN do painel", "Panel PIN")
    val permitir get() = t("Permitir", "Allow")
    val entrarTv get() = t("Entrar na TV", "Sign in to the TV")
}

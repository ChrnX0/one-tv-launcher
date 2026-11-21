package br.paulo.tvlauncher.screensaver

/**
 * Plugins de terceiros (degrau 3, 16/11/2026): o CONTRATO do cartão na HOME, sem Android (PluginCartaoTest).
 *
 * Um plugin é um app separado (na Play, de qualquer desenvolvedor) que declara um ContentProvider exportado com
 *     <intent-filter><action android:name="tv.sofaly.plugin.CARTAO"/></intent-filter>
 * O launcher pergunta `content://<autoridade>/cartao` e recebe UMA linha com as colunas:
 *     titulo (até 30), valor (até 12, o número grande: "2 x 1", "R$ 5,42", "18°"), detalhe (até 80), cor ("#RRGGBB"),
 *     acao (opcional: Intent em URI, que só pode abrir o PRÓPRIO plugin).
 * O plugin manda DADOS; quem desenha é a HOME, no nosso estilo e com letra grande: plugin nenhum desenha por cima da
 * tela, nem deixa a HOME ilegível. Nada sai do launcher para o plugin (ele não lê apps, contas, IPTV nem o painel).
 *
 * Permissão por plugin, como no celular: nenhum aparece sem o OK do dono (Configurações › Tela inicial › Plugins), e o
 * OK vale para o certificado de assinatura daquele momento (outro certificado = outro app = pergunta de novo).
 */
object PluginCartao {
    const val ACAO = "tv.sofaly.plugin.CARTAO"
    const val CAMINHO = "cartao"
    const val MAX_PLUGINS = 3
    val COLUNAS = arrayOf("titulo", "valor", "detalhe", "cor", "acao")

    data class Cartao(val pacote: String, val titulo: String, val valor: String, val detalhe: String, val cor: Int, val acao: String?)

    private fun texto(t: String?, max: Int) = (t ?: "").map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(max)

    /** Cor "#RRGGBB" (sem transparência: o cartão é sempre opaco e legível). Inválida = a cor padrão. */
    fun cor(t: String?): Int = Regex("^#([0-9a-fA-F]{6})$").find(t?.trim() ?: "")?.groupValues?.get(1)?.toLong(16)?.toInt()?.or(0xFF000000.toInt())
        ?: COR_PADRAO
    val COR_PADRAO = 0xFF2B3A55.toInt()

    /**
     * A linha que o plugin devolveu -> o cartão, ou null se não serve (sem título e sem valor). A ação só é aceita se
     * for uma intent "intent:" que não aponta para OUTRO pacote nem para um componente (a HOME ainda força o pacote do
     * plugin ao abrir).
     */
    fun limpar(pacote: String, linha: Map<String, String?>): Cartao? {
        val titulo = texto(linha["titulo"], 30)
        val valor = texto(linha["valor"], 12)
        if (titulo.isEmpty() && valor.isEmpty()) return null
        val acao = linha["acao"]?.trim()?.takeIf { acaoValida(pacote, it) }
        return Cartao(pacote, titulo, valor, texto(linha["detalhe"], 80), cor(linha["cor"]), acao)
    }

    fun acaoValida(pacote: String, uri: String): Boolean {
        if (!uri.startsWith("intent:") || uri.length > 500) return false
        val extras = uri.substringAfter("#Intent;", "").split(';')
        if (extras.any { it.startsWith("component=") || it.startsWith("SEL") }) return false
        val p = extras.firstOrNull { it.startsWith("package=") }?.removePrefix("package=")
        return p == null || p == pacote
    }

    /** O OK do dono vale para (pacote, certificado). */
    fun permitido(permitidos: Map<String, String>, pacote: String, certificado: String?): Boolean =
        certificado != null && permitidos[pacote] == certificado
}

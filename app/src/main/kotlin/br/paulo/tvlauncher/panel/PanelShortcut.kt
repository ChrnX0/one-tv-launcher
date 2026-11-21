package br.paulo.tvlauncher.panel

/**
 * O atalho "Abrir o painel da TV.url" (18/11/2026, pedido do dono: "o usuário só clica e abre o painel"). Aparece
 * sozinho na raiz da memória interna, tanto na TV em "Este Computador" (MTP, MtpLoja) quanto na unidade de rede
 * (WebDAV, PainelDav). Não existe no disco da TV: cada listagem monta o arquivo com o IP de AGORA, então o atalho
 * continua certo quando o roteador dá outro IP à TV. Se o dono criar um arquivo de verdade com o mesmo nome, vale o dele.
 *
 * O formato é o "Internet Shortcut" do Windows (um .ini de duas linhas); o duplo clique abre o navegador padrão.
 * Aqui só a parte pura (testes JVM em PanelShortcutTest); quem grava o arquivo é PainelWeb.atalhoPainel.
 */
object PanelShortcut {
    const val EXTENSION = ".url"
    /** O ícone da marca que o painel já serve (assets/upnp); o Windows usa no atalho quando consegue baixar. */
    const val ICON_PATH = Ssdp.CAMINHO_ICONES + "sofaly.ico"

    /**
     * O nome do arquivo a partir do texto traduzido ("Abrir o painel da TV" -> "Abrir o painel da TV.url"). Tira o que
     * o Windows não aceita em nome de arquivo; null se não sobrar nada válido.
     */
    fun fileName(label: String): String? {
        val limpo = label.filterNot { it in "\\/:*?\"<>|" || it.code < 0x20 || it.code == 0x7f }.trim().trimEnd('.').take(80)
        if (limpo.isEmpty()) return null
        val nome = limpo + EXTENSION
        return if (ChecagensPainel.nomeArquivoValido(nome) && !DavLogica.escondido(nome)) nome else null
    }

    /** O endereço do painel pelo IP (sem certificado para instalar: abre em qualquer computador da casa). */
    fun panelUrl(ip: String, port: Int): String = "http://${hostPart(ip)}:$port/"

    /** O conteúdo do .url. CRLF e só ASCII (o Windows lê o arquivo como .ini). */
    fun content(ip: String, port: Int): ByteArray {
        val base = "http://${hostPart(ip)}:$port"
        return ("[InternetShortcut]\r\nURL=$base/\r\nIconFile=$base$ICON_PATH\r\nIconIndex=0\r\n").toByteArray(Charsets.US_ASCII)
    }

    /** IPv6 vai entre colchetes na URL. Qualquer coisa que não pareça IP é recusada (nada de texto estranho no .ini). */
    private fun hostPart(ip: String): String {
        require(ip.isNotEmpty() && ip.length <= 45 && ip.all { it.isDigit() || it in "abcdefABCDEF.:" }) { "IP inválido" }
        return if (':' in ip) "[$ip]" else ip
    }

    /** [rel] (caminho relativo à raiz da memória interna) é o atalho de nome [name]? Só na raiz, nome exato. */
    fun isShortcut(rel: String, name: String?): Boolean = name != null && rel == name
}

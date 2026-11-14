package br.paulo.tvlauncher

/**
 * Ideia 55 (14/11/2026): "Enviar para a TV" pelo botão de compartilhar do celular. O painel instalado como app (https,
 * Chrome do Android) entra no menu de compartilhar (manifest.webmanifest, share_target) e manda aqui o que veio:
 * título, texto e link. Aqui se decide o que a TV faz, sem Android (CompartilharTest):
 *  - link do YouTube (watch, youtu.be, shorts, live): abre o vídeo no app do YouTube da TV, pelo endereço de sempre;
 *  - outro link http(s): abre na TV (o app que se registrou para ele, ou o navegador);
 *  - só texto: vira recado na tela.
 * O link costuma vir dentro do "texto" (o Android junta "Olha isso https://..."), então o primeiro http(s) vale.
 */
object Compartilhar {
    enum class Tipo { YOUTUBE, LINK, RECADO }
    data class Destino(val tipo: Tipo, val valor: String)

    private val LINK = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val ID_YT = Regex("^[A-Za-z0-9_-]{11}$")

    fun decidir(titulo: String?, texto: String?, url: String?): Destino? {
        val achado = listOfNotNull(url, texto, titulo).firstNotNullOfOrNull { LINK.find(it)?.value }
            ?.trimEnd('.', ',', ')', ']', '!', '?', ';')
        if (achado != null && McpPoderRegras.linkValido(achado)) {
            youtube(achado)?.let { return Destino(Tipo.YOUTUBE, it) }
            return Destino(Tipo.LINK, achado)
        }
        val recado = listOfNotNull(titulo?.trim(), texto?.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n")
        return Recado.limpar(recado.take(Recado.MAX_CARACTERES))?.let { Destino(Tipo.RECADO, it) }
    }

    /** O endereço "https://www.youtube.com/watch?v=<id>" de um link do YouTube, ou null se não é um vídeo dele. */
    fun youtube(link: String): String? {
        val semEsquema = link.substringAfter("://")
        val host = semEsquema.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        val caminho = semEsquema.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val consulta = semEsquema.substringAfter('?', "").substringBefore('#')
        val id = when (host) {
            "youtu.be" -> caminho.substringBefore('/')
            "youtube.com", "music.youtube.com" -> when {
                caminho == "watch" -> consulta.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                caminho.startsWith("shorts/") || caminho.startsWith("live/") || caminho.startsWith("embed/") ->
                    caminho.substringAfter('/').substringBefore('/')
                else -> null
            }
            else -> null
        } ?: return null
        return if (ID_YT.matches(id)) "https://www.youtube.com/watch?v=$id" else null
    }
}

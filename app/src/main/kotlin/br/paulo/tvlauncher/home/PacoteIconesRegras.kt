package br.paulo.tvlauncher.home

import org.xmlpull.v1.XmlPullParser

/**
 * Pacotes de ícones (degrau 1 contra o Projectivy, 15/11/2026). Desligado de fábrica (decisão do Paulo): sem pacote
 * escolhido, a HOME desenha os ícones dos próprios apps, como sempre.
 *
 * O formato é o "appfilter.xml" que quase todo pacote de ícones publica (ADW, Nova, Lawnchair e os de TV):
 *   <resources>
 *     <item component="ComponentInfo{com.netflix.ninja/com.netflix.ninja.MainActivity}" drawable="netflix"/>
 *     <iconback img1="fundo"/>   (máscaras e fundos: ignorados de propósito, ver abaixo)
 *   </resources>
 * O nome em `drawable` é um recurso do pacote (res/drawable*). Só lemos `item`; as máscaras (iconback/iconmask/
 * iconupon/scale), que redesenham os apps SEM ícone no pacote, ficam de fora: o app sem ícone no pacote mantém o dele,
 * que é mais legível que um ícone recortado.
 *
 * Só a leitura pura (testada em PacoteIconesRegrasTest). A parte do Android é [PacoteIcones].
 */
object PacoteIconesRegras {
    /** Ações que um pacote de ícones declara para ser achado pelos launchers. */
    val ACOES = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.anddoes.launcher.THEME", "com.gau.go.launcherex.theme", "app.lawnchair.icons.THEMED_ICON",
    )

    /** Mapa "pacote/classe" -> nome do drawable, e "pacote" -> o primeiro drawable daquele pacote (reserva). */
    class Mapa(val porComponente: Map<String, String>, val porPacote: Map<String, String>) {
        val tamanho: Int get() = porComponente.size

        /** Ícone do app: a atividade exata primeiro; se o pacote mudou o nome da atividade, qualquer um do pacote. */
        fun drawable(pacote: String, classe: String?): String? =
            classe?.let { porComponente["$pacote/${normalizarClasse(pacote, it)}"] } ?: porPacote[pacote]
    }

    /** ".MainActivity" vira "pacote.MainActivity" (o appfilter às vezes usa o nome curto). */
    fun normalizarClasse(pacote: String, classe: String): String = if (classe.startsWith(".")) pacote + classe else classe

    /** "ComponentInfo{pkg/cls}" -> ("pkg", "cls" normalizada). Null se estiver fora do formato. */
    fun componente(texto: String): Pair<String, String>? {
        val t = texto.trim()
        if (!t.startsWith("ComponentInfo{") || !t.endsWith("}")) return null
        val dentro = t.substring("ComponentInfo{".length, t.length - 1)
        val barra = dentro.indexOf('/')
        if (barra <= 0 || barra == dentro.length - 1) return null
        val pacote = dentro.substring(0, barra).trim()
        val classe = dentro.substring(barra + 1).trim()
        if (pacote.isEmpty() || classe.isEmpty()) return null
        return pacote to normalizarClasse(pacote, classe)
    }

    /** Nome de drawable válido de recurso Android (evita ler qualquer coisa que o XML traga). */
    fun drawableValido(nome: String): Boolean = nome.isNotEmpty() && nome.length <= 120 && nome.all { it.isLetterOrDigit() || it == '_' }

    /**
     * Lê o appfilter já aberto num [XmlPullParser] (o do res/xml vem pronto do Android; o de assets, pelo Xml.newPullParser).
     * Limite de [MAX_ITENS] itens: um pacote enorme ou malformado não segura a memória da TV.
     */
    fun ler(p: XmlPullParser): Mapa {
        val comp = LinkedHashMap<String, String>()
        val pac = LinkedHashMap<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && comp.size < MAX_ITENS) {
            if (ev == XmlPullParser.START_TAG && p.name == "item") {
                val c = p.getAttributeValue(null, "component")?.let { componente(it) }
                val d = p.getAttributeValue(null, "drawable")?.trim()
                if (c != null && d != null && drawableValido(d)) {
                    val chave = "${c.first}/${c.second}"
                    if (chave !in comp) comp[chave] = d
                    if (c.first !in pac) pac[c.first] = d
                }
            }
            ev = p.next()
        }
        return Mapa(comp, pac)
    }

    const val MAX_ITENS = 20_000

    /** Próximo pacote na volta da opção (""=nenhum -> 1º -> 2º -> ... -> ""). */
    fun proximo(atual: String, instalados: List<String>): String {
        if (instalados.isEmpty()) return ""
        val i = instalados.indexOf(atual)
        return if (atual.isEmpty() || i < 0) instalados.first() else instalados.getOrElse(i + 1) { "" }
    }
}

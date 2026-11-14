package br.paulo.tvlauncher

import java.text.Normalizer

/**
 * Segundo guia (ideia 15, 14/11/2026): cada conta pode ter um link de guia XMLTV EXTRA. Ele não compete com o
 * principal: os programas dele só entram nos canais que ficaram SEM programação nenhuma no guia principal.
 *
 * Como um canal da lista acha o seu canal no guia extra:
 *  - pelo mesmo id (o "channel" do XMLTV igual ao tvg-id do canal), ou
 *  - pelo nome normalizado: sem acento, sem caixa, sem os sufixos de qualidade (HD, FHD, SD, UHD, 4K...), sem espaços
 *    e pontuação. "SporTV 2 FHD" e "Sportv-2" viram o mesmo "sportv2".
 *
 * Só lógica pura (sem Android), testada em GuiaExtraTest. Quem lê o XMLTV é o Xmltv.lerExtraEmFluxo; quem grava, o
 * PlayerCarga.guia.
 */
object GuiaExtra {
    /** Palavras de qualidade/formato que não fazem parte do nome do canal (comparadas já normalizadas). */
    private val SUFIXOS = setOf("hd", "fhd", "sd", "uhd", "4k", "hdr", "hevc", "h264", "h265", "fullhd", "720p", "1080p", "2160p")
    private val MARCAS = Regex("\\p{Mn}+")
    private val SEPARADORES = Regex("[^a-z0-9]+")

    /**
     * "SporTV 2 FHD" -> "sportv2"; "Globo HD (SP)" -> "globosp"; "ESPN²" -> "espn2". Vazio = sem nome que preste
     * (aí o canal não casa por nome).
     */
    fun normalizarNome(nome: String): String {
        val semAcento = Normalizer.normalize(nome, Normalizer.Form.NFKD).replace(MARCAS, "").lowercase()
        val palavras = semAcento.split(SEPARADORES).filter { it.isNotEmpty() }
        // Tira as palavras de qualidade em qualquer posição ("Globo HD SP"), mas nunca o nome inteiro ("HD" sozinho
        // continua "hd": um canal chamado só "4K" ainda é um nome).
        val limpas = palavras.filter { it !in SUFIXOS }.ifEmpty { palavras }
        return limpas.joinToString("")
    }

    /**
     * A ponte entre o guia extra e os canais que faltam. [faltando] = guiaId do canal -> nome dele, só os canais da conta
     * que têm guiaId e ficaram sem programa no guia principal. Pequena: dois mapas de texto, do tamanho desses canais.
     */
    class Mescla(faltando: Map<String, String>) {
        private val porId: Set<String> = faltando.keys.toHashSet()
        private val porNome: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { m ->
            for ((id, nome) in faltando) {
                val n = normalizarNome(nome)
                if (n.isNotEmpty()) m.getOrPut(n) { ArrayList(1) }.let { if (id !in it) it += id }
            }
        }
        // id do canal no guia extra -> guiaIds dos nossos canais (montado conforme os <channel> aparecem)
        private val traducao = HashMap<String, List<String>>()

        /** Nada a preencher: nem vale a pena baixar o guia extra. */
        val vazia: Boolean get() = porId.isEmpty()

        /** Um <channel id="..."> do guia extra, com os seus display-name. */
        fun canal(idExtra: String, nomes: List<String>) {
            if (idExtra.isEmpty()) return
            val achados = LinkedHashSet<String>()
            if (idExtra in porId) achados += idExtra
            for (nome in nomes) porNome[normalizarNome(nome)]?.let { achados += it }
            // O próprio id às vezes é o nome ("SporTV2.br"): tenta também, sem o sufixo de país.
            porNome[normalizarNome(idExtra.substringBeforeLast('.'))]?.let { achados += it }
            if (achados.isNotEmpty()) traducao[idExtra] = (traducao[idExtra].orEmpty() + achados).distinct()
        }

        /**
         * Para quais dos nossos canais (guiaId) vão os programas do canal [idExtra]. Vazio = canal que não interessa.
         * Um <programme> de canal sem <channel> antes (guia fora do padrão) ainda casa pelo id.
         */
        fun destinos(idExtra: String): List<String> =
            traducao[idExtra] ?: if (idExtra in porId) listOf(idExtra) else emptyList()
    }

    /**
     * Os canais a preencher: dos [canais] (guiaId -> nome) da conta, os que têm guiaId e NÃO estão em [comPrograma]
     * (os que receberam algum programa do guia principal).
     */
    fun faltando(canais: List<Pair<String, String>>, comPrograma: Set<String>): Map<String, String> {
        val saida = LinkedHashMap<String, String>()
        for ((id, nome) in canais) if (id.isNotEmpty() && id !in comPrograma && id !in saida) saida[id] = nome
        return saida
    }
}

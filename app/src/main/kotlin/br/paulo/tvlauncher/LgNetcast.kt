package br.paulo.tvlauncher

/**
 * Volume de verdade nas LG NetCast 4.0 (2013, ex.: 42LN5700), ideia 49 de 13/11/2026. Regras puras, testadas em
 * LgNetcastTest; quem fala com a TV é o [VolumeLg].
 *
 * Por que: quando o Chromecast sai pelo HDMI com volume fixo, o volume do Android (STREAM_MUSIC) não chega na TV. A LG
 * aceita teclas pela rede (protocolo ROAP, HTTP na porta 8080), depois de parear com um código que ela mostra na tela.
 * Verificado na Sala em 14/11/2026: pareou, subiu e desceu um ponto, e `volume_info` devolveu nível 0 a 100 e o mudo.
 * O formato antigo de pareamento (UDAP, /udap/api/pairing) responde 200 nesta TV mas NÃO mostra o código.
 */
object LgNetcast {
    const val PORTA = 8080
    const val TIPO = "application/atom+xml"
    const val CAMINHO_AUTH = "/roap/api/auth"
    const val CAMINHO_COMANDO = "/roap/api/command"
    const val CAMINHO_VOLUME = "/roap/api/data?target=volume_info"
    /** Responde sem pareamento: serve para achar a TV na rede e conferir que é ela (uuid). */
    const val CAMINHO_MODELO = "/udap/api/data?target=netrcu.model_info"

    // Códigos de tecla do ROAP. Subir e descer verificados na Sala; o mudo vem da mesma tabela e NÃO foi testado.
    const val TECLA_SUBIR = 24
    const val TECLA_DESCER = 25
    const val TECLA_MUDO = 26

    /** Teto de teclas numa ida: a LG vai de 0 a 100; nada pede mais do que isso de uma vez. */
    const val MAX_TECLAS = 100

    private const val XML = """<?xml version="1.0" encoding="utf-8"?>"""

    fun corpoPedirCodigo() = "$XML<auth><type>AuthKeyReq</type></auth>"
    fun corpoParear(codigo: String) = "$XML<auth><type>AuthReq</type><value>$codigo</value></auth>"
    fun corpoTecla(tecla: Int) = "$XML<command><name>HandleKeyInput</name><value>$tecla</value></command>"

    /** O código que a LG mostra: só dígitos (4 a 8). Nada além disso entra no XML. */
    fun codigoValido(c: String?): Boolean = c != null && Regex("[0-9]{4,8}").matches(c)

    /** Só IPv4 de rede local: a LG está em casa, e o código nunca deve ir para fora. */
    fun ipLocal(ip: String?): Boolean {
        val p = ip?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        if (p.size != 4 || p.any { it !in 0..255 } || ip.split('.').any { it.length > 1 && it.startsWith("0") }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** O erro do ROAP (200 = ok), ou null se a resposta não for do ROAP. */
    fun erro(xml: String): Int? = tag(xml, "ROAPError")?.toIntOrNull()

    data class Volume(val nivel: Int, val mudo: Boolean, val maximo: Int = 100)

    fun volume(xml: String): Volume? {
        if (erro(xml) != 200) return null
        val nivel = tag(xml, "level")?.toIntOrNull() ?: return null
        val max = tag(xml, "maxLevel")?.toIntOrNull()?.takeIf { it > 0 } ?: 100
        return Volume(nivel.coerceIn(0, max), tag(xml, "mute") == "true", max)
    }

    data class Modelo(val uuid: String, val nome: String, val modelo: String)

    /** A resposta do model_info, se for uma LG. */
    fun modelo(xml: String): Modelo? {
        if (tag(xml, "manufacturer")?.contains("LG", ignoreCase = true) != true) return null
        val uuid = tag(xml, "uuid") ?: return null
        return Modelo(uuid, tag(xml, "friendlyName").orEmpty(), tag(xml, "modelName").orEmpty())
    }

    /**
     * Quantas teclas para ir de [atual] a [alvo]: positivo = subir, negativo = descer. Limitado a [MAX_TECLAS] e ao
     * [maximo] da TV (um alvo fora da faixa não vira cem teclas a mais).
     */
    fun teclas(atual: Int, alvo: Int, maximo: Int = 100): Int {
        val d = alvo.coerceIn(0, maximo) - atual.coerceIn(0, maximo)
        return d.coerceIn(-MAX_TECLAS, MAX_TECLAS)
    }

    /** Teclas só para BAIXAR até [teto] (0 se já está abaixo): hora de dormir e desligar suave nunca sobem o volume. */
    fun descerAte(atual: Int, teto: Int, maximo: Int = 100): Int = minOf(0, teclas(atual, teto, maximo))

    /** Porcentagem do máximo da TV para nível (o teto da hora de dormir é em %). */
    fun nivelDaPorcentagem(pct: Int, maximo: Int = 100): Int = maximo * pct.coerceIn(0, 100) / 100

    /** Mandar "User-Agent: UDAP/2.0"? Só no caminho UDAP; no ROAP esse cabeçalho faz a LG responder 400. */
    fun agenteUdap(caminho: String): Boolean = caminho.startsWith("/udap/")

    private fun tag(xml: String, nome: String): String? =
        Regex("<$nome>([^<]*)</$nome>").find(xml)?.groupValues?.get(1)?.trim()
}

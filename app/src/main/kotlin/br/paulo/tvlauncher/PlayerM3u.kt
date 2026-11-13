package br.paulo.tvlauncher

/**
 * Player universal, passo 1 (06/11/2026): o que o player sabe de um canal, e a leitura de listas M3U.
 *
 * Só lógica pura (sem Android), para testar no computador (app/src/test/.../PlayerM3uTest.kt).
 * O launcher NÃO traz lista nem fonte nenhuma: só lê a que o usuário cadastrar (repo público, ver o estudo).
 */
data class Canal(
    val id: String,            // chave estável do canal dentro da conta (tvg-id, stream_id do Xtream ou IdCanal.estavel)
    val nome: String,
    val numero: Int,           // número para digitar no controle/celular (tvg-chno, ou a ordem na lista)
    val grupo: String = "",    // "group-title" da lista (ex.: Esportes)
    val logo: String = "",     // URL do logotipo (tvg-logo), pode vir vazia
    val url: String,           // endereço do vídeo (http/https; rtmp/udp ficam de fora, ver M3u.urlAceita)
    val guiaId: String = "",   // tvg-id: liga o canal ao guia XMLTV
    val agenteUsuario: String = "",  // algumas listas exigem um "User-Agent" próprio (#EXTVLCOPT:http-user-agent)
    val arquivoDias: Int = 0,  // TV de volta: quantos dias o servidor guarda (tv_archive / catchup-days); 0 = não tem
)

object M3u {
    /** Teto de canais por lista: uma lista gigante (100 mil linhas) não pode derrubar a TV de 2 GB. */
    const val MAX_CANAIS = 20_000

    /**
     * Lê o texto de uma lista M3U/M3U8 "estendida" e devolve os canais na ordem do arquivo.
     *
     * Formato (uma entrada = duas linhas, mais linhas de opção opcionais no meio):
     *   #EXTINF:-1 tvg-id="c1" tvg-logo="http://..." group-title="Notícias",Canal 1
     *   http://servidor/canal1.m3u8
     * Linhas desconhecidas são ignoradas, em vez de dar erro: as listas de verdade vêm cheias de variações.
     */
    fun ler(texto: String): List<Canal> = ler(texto.lineSequence())

    /**
     * O mesmo, linha por linha: o PlayerCarga passa as linhas direto da rede (sem montar o texto inteiro na memória,
     * que numa lista de 30 MB ocupava o dobro disso).
     */
    fun ler(linhas: Sequence<String>): List<Canal> {
        val canais = ArrayList<Canal>()
        val numerosUsados = HashSet<Int>()
        var info: Map<String, String>? = null   // atributos do último #EXTINF, esperando a linha da URL
        var nome = ""
        var grupoExtgrp = ""                    // #EXTGRP: outra forma de dizer o grupo
        var agente = ""
        // removePrefix("﻿"): arquivo salvo no Windows às vezes começa com a "marca de ordem de bytes" (BOM)
        var primeira = true
        for (bruta in linhas) {
            // trim também tira o \r das linhas com fim CRLF; o BOM só pode vir no começo da 1ª linha
            val linha = (if (primeira) bruta.removePrefix("﻿") else bruta).trim()
            primeira = false
            when {
                linha.isEmpty() -> {}
                linha.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (attrs, n) = lerExtinf(linha)
                    info = attrs; nome = n; grupoExtgrp = ""; agente = ""
                }
                linha.startsWith("#EXTGRP:", ignoreCase = true) -> grupoExtgrp = linha.substring(8).trim()
                linha.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    agente = linha.substringAfter('=').trim()
                linha.startsWith("#") -> {}     // #EXTM3U, #KODIPROP etc.: não usamos
                else -> {
                    val a = info ?: continue    // URL solta, sem #EXTINF antes: pula
                    info = null
                    if (!urlAceita(linha)) continue
                    if (canais.size >= MAX_CANAIS) break
                    // Número: tvg-chno quando vem válido e ainda livre; senão, o próximo número livre.
                    val pedido = a["tvg-chno"]?.toIntOrNull()?.takeIf { it in 1..99_999 && it !in numerosUsados }
                    val numero = pedido ?: proximoLivre(numerosUsados, canais.size + 1)
                    numerosUsados += numero
                    val guiaId = a["tvg-id"].orEmpty()
                    val nomeLido = nome.ifEmpty { a["tvg-name"].orEmpty() }
                    val grupo = a["group-title"].orEmpty().ifEmpty { grupoExtgrp }
                    canais += Canal(
                        // Id estável (player-8): tvg-id, nome + categoria, ou resumo da URL. Nunca a URL (token muda, senha vaza).
                        id = IdCanal.estavel(guiaId, nomeLido, grupo, linha),
                        nome = nomeLido.ifEmpty { "Canal $numero" },
                        numero = numero,
                        grupo = grupo,
                        logo = a["tvg-logo"].orEmpty().takeIf { urlAceita(it) }.orEmpty(),
                        url = linha,
                        guiaId = guiaId,
                        agenteUsuario = agente,
                        arquivoDias = TvDeVolta.dias(a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"]),
                    )
                }
            }
        }
        // Dois canais com o mesmo tvg-id (comum: versão HD e SD) não podem ter a mesma chave.
        return desduplicarIds(canais)
    }

    /**
     * O link do guia que a própria lista anuncia na 1ª linha: `#EXTM3U x-tvg-url="..."` (ou url-tvg="...").
     * Pode vir mais de um separado por vírgula: fica o primeiro http/https. null = a lista não diz.
     */
    fun guiaDaLista(texto: String): String? {
        val cab = texto.removePrefix("﻿").lineSequence().firstOrNull()?.trim() ?: return null
        if (!cab.startsWith("#EXTM3U", ignoreCase = true)) return null
        val attrs = lerExtinf("#EXTM3U:" + cab.substring(7)).first
        val valor = attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: return null
        return valor.split(',').map { it.trim() }.firstOrNull { urlAceita(it) }
    }

    /** Só http e https: o Media3 toca esses, e um "file://" numa lista baixada da internet poderia ler arquivo da TV. */
    fun urlAceita(url: String): Boolean {
        val u = url.trim().lowercase()
        return (u.startsWith("http://") || u.startsWith("https://")) && u.length > 10 && ' ' !in u
    }

    /**
     * Separa os atributos (chave="valor") e o nome que vem depois da última vírgula FORA das aspas.
     * Ex.: `#EXTINF:-1 tvg-name="A, B" group-title="X",Nome do canal` -> ({tvg-name=A, B, group-title=X}, "Nome do canal")
     */
    internal fun lerExtinf(linha: String): Pair<Map<String, String>, String> {
        val corpo = linha.substringAfter(':', "")
        val attrs = HashMap<String, String>()
        var i = 0
        var dentroAspas = false
        var virgula = -1
        // 1ª volta: acha a vírgula que separa atributos do nome (a primeira fora de aspas)
        while (i < corpo.length) {
            val c = corpo[i]
            if (c == '"') dentroAspas = !dentroAspas
            else if (c == ',' && !dentroAspas) { virgula = i; break }
            i++
        }
        val parteAttrs = if (virgula >= 0) corpo.substring(0, virgula) else corpo
        val nome = if (virgula >= 0) corpo.substring(virgula + 1).trim() else ""
        // 2ª volta: chave="valor" (aspas duplas). Regex simples basta aqui porque o valor não tem aspas dentro.
        ATRIBUTO.findAll(parteAttrs).forEach {
            attrs[it.groupValues[1].lowercase()] = it.groupValues[2].trim()
        }
        return attrs to nome
    }

    /**
     * chave="valor". O "(?<!...)" só deixa a chave começar no INÍCIO de uma palavra, e o "++" não volta atrás: assim a
     * busca é linear. Antes (fuzz de 10/11/2026), uma linha #EXTINF com 300 mil letras sem "=" fazia a expressão tentar
     * de novo a partir de cada letra (tempo quadrático: a carga da lista travava por minutos).
     */
    private val ATRIBUTO = Regex("""(?<![A-Za-z0-9_-])([A-Za-z0-9_-]++)\s*+=\s*+"([^"]*+)"""")

    private fun proximoLivre(usados: Set<Int>, inicio: Int): Int {
        var n = inicio
        while (n in usados) n++
        return n
    }

    internal fun desduplicarIds(canais: List<Canal>): List<Canal> {
        // Id pelo nome + categoria repetido (HD/SD, reserva): desempata pelo resumo da URL sem segredo, não pela posição,
        // senão a lista mudar de ordem passaria o favorito e o bloqueio para o outro canal. tvg-id repetido segue com
        // "#2", "#3" como antes de 10/11 (favoritos antigos usam esse id).
        val contagem = canais.groupingBy { it.id }.eachCount()
        val desempatados = canais.map { c ->
            if ((contagem[c.id] ?: 0) > 1 && IdCanal.porNome(c.id)) c.copy(id = "${c.id}#${IdCanal.resumoUrl(c.url)}") else c
        }
        val usados = HashSet<String>()
        val proximo = HashMap<String, Int>()   // próximo sufixo a tentar para cada id (não recomeça do #2 a cada repetido)
        return desempatados.map { c ->
            if (usados.add(c.id)) return@map c  // 1ª vez deste id: fica como está
            // Repetido: "id#2", "id#3"... pulando os que JÁ existem (fuzz de 10/11/2026: um tvg-id "a#2" de verdade na
            // lista colidia com o "a#2" inventado para o 2º "a", e dois canais ficavam com a mesma chave).
            var n = proximo[c.id] ?: 2
            while (!usados.add("${c.id}#$n")) n++
            proximo[c.id] = n + 1
            c.copy(id = "${c.id}#$n")
        }
    }
}

package br.paulo.tvlauncher

/**
 * A TV na tela "Rede" do Windows (14/11/2026, pedido do dono). O Windows lista ali os aparelhos UPnP que se anunciam
 * por SSDP (mensagens UDP para 239.255.255.250:1900); é assim que o Chromecast embutido já aparece como "Sala de estar".
 * Anunciamos um aparelho a mais, "<nome da TV> · arquivos", cujo duplo clique abre a página /unidade (o endereço para
 * mapear a unidade de rede e o passo a passo). Abrir direto como PASTA ali o Windows só faz com SMB na porta 445, que o
 * Android não deixa usar (docs/unidade-de-rede.md).
 *
 * Aqui só os textos do protocolo (UPnP Device Architecture 1.0), sem Android, para os testes JVM (SsdpTest).
 * Quem manda e recebe os pacotes é o AnuncioUpnp.kt.
 */
object Ssdp {
    const val GRUPO = "239.255.255.250"
    const val PORTA = 1900
    /** "Aparelho básico": o tipo genérico do UPnP; o Windows mostra em "Outros dispositivos". */
    const val TIPO = "urn:schemas-upnp-org:device:Basic:1"
    const val CAMINHO_DESCRICAO = "/upnp/onetv.xml"
    /** O anúncio vale 30 min; reenviamos a cada 10 (o Windows esquece o aparelho se o anúncio vencer). */
    const val VALIDADE_S = 1800
    private const val SERVIDOR = "Android UPnP/1.0 OneTV/1.0"

    /** Os três anúncios que a especificação manda: raiz, o aparelho (uuid) e o tipo dele. NT -> USN. */
    fun alvos(uuid: String): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice",
        "uuid:$uuid" to "uuid:$uuid",
        TIPO to "uuid:$uuid::$TIPO",
    )

    /** NOTIFY multicast: ssdp:alive (estou aqui) ou ssdp:byebye (saí). */
    fun notify(uuid: String, local: String, vivo: Boolean): List<String> = alvos(uuid).map { (nt, usn) ->
        "NOTIFY * HTTP/1.1\r\nHOST: $GRUPO:$PORTA\r\n" +
            (if (vivo) "CACHE-CONTROL: max-age=$VALIDADE_S\r\nLOCATION: $local\r\nSERVER: $SERVIDOR\r\n" else "") +
            "NT: $nt\r\nNTS: ${if (vivo) "ssdp:alive" else "ssdp:byebye"}\r\nUSN: $usn\r\n\r\n"
    }

    /**
     * Resposta (unicast) a uma busca M-SEARCH, ou lista vazia se a busca não é por nós. Busca malformada, sem
     * MAN: "ssdp:discover" ou grande demais: nada.
     */
    fun respostas(pedido: String, uuid: String, local: String): List<String> {
        if (pedido.length > 2048) return emptyList()
        val linhas = pedido.split("\r\n", "\n")
        if (!linhas.first().trim().equals("M-SEARCH * HTTP/1.1", ignoreCase = true)) return emptyList()
        val cab = linhas.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() }
        if (cab["MAN"]?.trim('"') != "ssdp:discover") return emptyList()
        val st = cab["ST"] ?: return emptyList()
        val escolhidos = if (st == "ssdp:all") alvos(uuid) else alvos(uuid).filter { it.first == st }
        return escolhidos.map { (nt, usn) ->
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$VALIDADE_S\r\nEXT:\r\nLOCATION: $local\r\nSERVER: $SERVIDOR\r\n" +
                "ST: $nt\r\nUSN: $usn\r\n\r\n"
        }
    }

    /** A descrição do aparelho (o XML que o LOCATION aponta). [pagina] é o que o duplo clique abre. */
    fun descricao(nome: String, uuid: String, pagina: String): String {
        fun e(t: String) = DavLogica.escaparXml(t)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>$TIPO</deviceType><friendlyName>${e(nome)}</friendlyName>" +
            "<manufacturer>One TV Launcher</manufacturer><modelName>One TV Launcher</modelName>" +
            "<modelDescription>${e("Arquivos da TV pela rede (WebDAV)")}</modelDescription>" +
            "<UDN>uuid:${e(uuid)}</UDN><presentationURL>${e(pagina)}</presentationURL></device></root>\n"
    }

    /** Nome que aparece no Windows: o da TV + "arquivos" (o Chromecast já usa o nome sozinho). Até 60 letras. */
    fun nomeVisivel(nomeTv: String): String = "${nomeTv.take(45)} · arquivos"

    /** uuid estável para esta TV (o Windows não duplica o ícone a cada religada), derivado do id curto da TV. */
    fun uuid(idTv: String): String = java.util.UUID.nameUUIDFromBytes("onetv-unidade-$idTv".toByteArray()).toString()
}

package br.paulo.tvlauncher.panel

/**
 * A TV na tela "Rede" do Windows (14/11/2026, pedido do dono). O Windows lista ali os aparelhos UPnP que se anunciam
 * por SSDP (mensagens UDP para 239.255.255.250:1900); é assim que o Chromecast embutido já aparece como "Sala de estar".
 * Anunciamos um aparelho a mais, "<nome da TV> · painel", cujo duplo clique abre o painel (era a página /unidade, com o endereço para
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
    fun alvos(uuid: String, tipo: String = TIPO): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice",
        "uuid:$uuid" to "uuid:$uuid",
        tipo to "uuid:$uuid::$tipo",
    )

    /** NOTIFY multicast: ssdp:alive (estou aqui) ou ssdp:byebye (saí). */
    fun notify(uuid: String, local: String, vivo: Boolean, tipo: String = TIPO, validade: Int = VALIDADE_S): List<String> = alvos(uuid, tipo).map { (nt, usn) ->
        "NOTIFY * HTTP/1.1\r\nHOST: $GRUPO:$PORTA\r\n" +
            (if (vivo) "CACHE-CONTROL: max-age=$validade\r\nLOCATION: $local\r\nSERVER: $SERVIDOR\r\n" else "") +
            "NT: $nt\r\nNTS: ${if (vivo) "ssdp:alive" else "ssdp:byebye"}\r\nUSN: $usn\r\n\r\n"
    }

    /**
     * Um aviso só, o do próprio aparelho (NT = uuid). Medido em 16/11/2026 com Windows 11 e a TV de verdade: é ESTE
     * pacote, sozinho, que o Windows obedece na hora. Com "saí" ele solta o aparelho em menos de 1 s; com "estou aqui"
     * 2 s depois ele reconecta em ~1,5 s. A rajada dos três anúncios começando pelo upnp:rootdevice não fazia nem uma
     * coisa nem outra (5 tentativas). Serve para o Windows largar a conexão velha quando o launcher reinicia: sem isso
     * ele fica preso nela e mostra a memória da TV vazia até alguém reiniciar o dispositivo à mão.
     */
    fun avisoDoAparelho(uuid: String, local: String, vivo: Boolean, validade: Int = VALIDADE_S): String =
        "NOTIFY * HTTP/1.1\r\nHOST: $GRUPO:$PORTA\r\n" +
            (if (vivo) "CACHE-CONTROL: max-age=$validade\r\nLOCATION: $local\r\nSERVER: $SERVIDOR\r\n" else "") +
            "NT: uuid:$uuid\r\nNTS: ${if (vivo) "ssdp:alive" else "ssdp:byebye"}\r\nUSN: uuid:$uuid\r\n\r\n"

    /** Espera entre o "saí" e o "estou aqui" da reconexão (2 s funcionou; menos não foi medido). */
    const val PAUSA_RECONEXAO_MS = 2000L

    /**
     * Resposta (unicast) a uma busca M-SEARCH, ou lista vazia se a busca não é por nós. Busca malformada, sem
     * MAN: "ssdp:discover" ou grande demais: nada.
     */
    fun respostas(pedido: String, uuid: String, local: String, tipo: String = TIPO, validade: Int = VALIDADE_S): List<String> {
        if (pedido.length > 2048) return emptyList()
        val linhas = pedido.split("\r\n", "\n")
        if (!linhas.first().trim().equals("M-SEARCH * HTTP/1.1", ignoreCase = true)) return emptyList()
        val cab = linhas.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() }
        if (cab["MAN"]?.trim('"') != "ssdp:discover") return emptyList()
        val st = cab["ST"] ?: return emptyList()
        val escolhidos = if (st == "ssdp:all") alvos(uuid, tipo) else alvos(uuid, tipo).filter { it.first == st }
        return escolhidos.map { (nt, usn) ->
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$validade\r\nEXT:\r\nLOCATION: $local\r\nSERVER: $SERVIDOR\r\n" +
                "ST: $nt\r\nUSN: $usn\r\n\r\n"
        }
    }

    /** A descrição do aparelho (o XML que o LOCATION aponta). [pagina] é o que o duplo clique abre. */
    fun descricao(nome: String, uuid: String, pagina: String): String {
        fun e(t: String) = DavLogica.escaparXml(t)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>$TIPO</deviceType><friendlyName>${e(nome)}</friendlyName>" +
            "<manufacturer>$MARCA</manufacturer><modelName>$MARCA</modelName>$ICONES" +
            "<modelDescription>${e("Painel da TV no navegador")}</modelDescription>" +
            "<UDN>uuid:${e(uuid)}</UDN><presentationURL>${e(pagina)}</presentationURL></device></root>\n"
    }

    // ---------------------------------------------------------------- a TV em "Este Computador" (MTP pela rede)

    /**
     * O tipo que o driver "MTP IP Device" do Windows procura (wpdmtp.inf: umb\urn:microsoft-com:device:mtp:1). Com ele
     * a TV vira um dispositivo portátil, como um celular no cabo; os arquivos vão pela porta 15740 (MtpIp.kt).
     */
    const val TIPO_MTP = "urn:microsoft-com:device:mtp:1"

    /** O nome do produto (16/11/2026), que o Windows mostra como fabricante e modelo do aparelho. */
    const val MARCA = "Sofaly"
    /** O ícone da marca na descrição UPnP (assets/upnp, servidos pelo painel). O Windows 11 não o busca (medido 16/11/2026). */
    const val CAMINHO_ICONES = "/upnp/"
    val ARQUIVOS_ICONE = mapOf("sofaly-48.png" to 48, "sofaly-120.png" to 120)
    val ICONES = "<iconList>" + ARQUIVOS_ICONE.entries.joinToString("") { (arq, lado) ->
        "<icon><mimetype>image/png</mimetype><width>$lado</width><height>$lado</height><depth>32</depth><url>$CAMINHO_ICONES$arq</url></icon>"
    } + "</iconList>"
    const val CAMINHO_MTP = "/upnp/onetv-mtp.xml"
    const val CAMINHO_MTP_SERVICO = "/upnp/onetv-mtp-servico.xml"

    /**
     * A descrição do aparelho MTP. As linhas pnpx dizem ao Windows qual driver usar (X_compatibleId) e que é um
     * aparelho de mídia; o serviço "nulo" existe só porque o UPnP exige pelo menos um. [pagina] = o painel web
     * (no Windows: botão direito no aparelho › Exibir página da Web do dispositivo).
     */
    fun descricaoMtp(nome: String, uuid: String, pagina: String, serie: String): String {
        fun e(t: String) = DavLogica.escaparXml(t)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\" " +
            "xmlns:pnpx=\"http://schemas.microsoft.com/windows/pnpx/2005/11\" " +
            "xmlns:df=\"http://schemas.microsoft.com/windows/2008/09/devicefoundation\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<pnpx:X_hardwareId>VEN_0ETV&amp;DEV_0001</pnpx:X_hardwareId>" +
            "<pnpx:X_compatibleId>$TIPO_MTP</pnpx:X_compatibleId>" +
            "<pnpx:X_deviceCategory>MediaDevices</pnpx:X_deviceCategory>" +
            "<df:X_deviceCategory>Multimedia.DMP</df:X_deviceCategory>" +
            "<deviceType>$TIPO_MTP</deviceType><friendlyName>${e(nome)}</friendlyName>" +
            "<manufacturer>$MARCA</manufacturer><modelName>$MARCA</modelName>$ICONES" +
            "<modelDescription>${e("Arquivos da TV")}</modelDescription><serialNumber>${e(serie)}</serialNumber>" +
            "<UDN>uuid:${e(uuid)}</UDN><serviceList><service>" +
            "<serviceType>urn:microsoft-com:service:MtpNullService:1</serviceType>" +
            "<serviceId>urn:microsoft-com:serviceId:MtpNullService</serviceId>" +
            "<SCPDURL>$CAMINHO_MTP_SERVICO</SCPDURL><controlURL>/upnp/onetv-mtp-controle</controlURL>" +
            "<eventSubURL>/upnp/onetv-mtp-eventos</eventSubURL></service></serviceList>" +
            "<presentationURL>${e(pagina)}</presentationURL></device></root>\n"
    }

    /** A descrição do serviço "nulo": nenhuma ação, uma variável de enfeite (o esquema exige a tabela). */
    const val SERVICO_MTP = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
        "<specVersion><major>1</major><minor>0</minor></specVersion><serviceStateTable>" +
        "<stateVariable sendEvents=\"no\"><name>Nada</name><dataType>string</dataType></stateVariable>" +
        "</serviceStateTable></scpd>\n"

    fun uuidMtp(idTv: String): String = java.util.UUID.nameUUIDFromBytes("onetv-mtp-$idTv".toByteArray()).toString()

    /**
     * Nome que aparece na tela "Rede" do Windows: o da TV + "painel" (o Chromecast já usa o nome sozinho). O duplo
     * clique abre o painel. Era "· arquivos" e abria a página /unidade; trocado em 16/11/2026 porque os arquivos já
     * aparecem sozinhos em Este Computador e o dono esperava o painel ali. Até 60 letras.
     */
    fun nomeVisivel(nomeTv: String): String = "${nomeNaUnidade(nomeTv).take(50)} · painel"   // "Sofaly · Sala · painel"

    /**
     * O nome da TV em "Este Computador" (16/11/2026): "Sofaly · Sala de estar". Com a marca na frente, as TVs da
     * casa ficam juntas na lista (ordem alfabética); o Windows não tem pasta que agrupe aparelhos MTP.
     */
    fun nomeNaUnidade(nomeTv: String): String {
        val n = nomeTv.trim()
        // quem já pôs a marca no nome da TV não ganha "Sofaly · Sofaly"
        return if (n.startsWith(MARCA, ignoreCase = true)) n.take(60) else "$MARCA · ${n.take(48)}"
    }

    /** uuid estável para esta TV (o Windows não duplica o ícone a cada religada), derivado do id curto da TV. */
    fun uuid(idTv: String): String = java.util.UUID.nameUUIDFromBytes("onetv-unidade-$idTv".toByteArray()).toString()
}

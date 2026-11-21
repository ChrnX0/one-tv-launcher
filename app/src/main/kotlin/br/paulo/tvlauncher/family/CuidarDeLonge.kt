package br.paulo.tvlauncher.family

import br.paulo.tvlauncher.Avisos
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.alarm.ProximoAlarme
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Cuidar de longe" (ideias 77, 78 e 79; aprovadas pelo Paulo como OPÇÃO DO PRODUTO em 15/11/2026). Três avisos que
 * chegam no celular de quem cuida (um filho, uma neta) de alguém que mora sozinho:
 *  - 77 AJUDA: quem está na TV segura uma tecla 3 s, confirma, e o celular recebe "pediu ajuda" com a foto da tela e o
 *    painel ao vivo (Controle). Só dispara por ação da pessoa.
 *  - 78 LEMBRETE: lembrete grande na hora marcada (remédio, água, consulta) com "Feito". Sem o Feito em [Config.prazoMin],
 *    o celular recebe "o lembrete das 20:00 não foi confirmado". Um aviso por lembrete, sem insistir; o texto do
 *    lembrete NÃO sai da casa (pode ser dado de saúde).
 *  - 79 NAO_LIGOU: a TV aprende a hora em que costuma ser ligada (ou o dono fixa) e, se até essa hora + margem ela não foi
 *    ligada, avisa: "a TV ainda não foi ligada hoje". Só o fato; nunca um porquê.
 *
 * As 4 regras fixas do pedido (pedido-2026-11-15.md):
 *  1. Desligado de fábrica: sem nome, aparelho e aviso escolhidos, nada sai da TV.
 *  2. Ligar exige o consentimento de quem mora na casa, dado NA TV ("Você aceita que Ana receba avisos desta TV?").
 *     O consentimento vale para aquele nome, aqueles aparelhos e aqueles avisos ([assinatura]): mudou um deles, a TV
 *     pergunta de novo. Retirar é a qualquer hora, na TV.
 *  3. Na TV da Sala nada é ligado sem o Paulo pedir (isto é regra de quem mexe, não de código).
 *  4. Letra grande (Estilo.Leitura) em tudo o que aparece na TV.
 *
 * Os lembretes em si (a tela grande com "Feito") funcionam sem consentimento: só o aviso à família depende dele.
 * Aqui mora só a lógica, sem Android, para testar no computador (CuidarDeLongeTest). O resto: CuidarDeLongeTv.
 */
object CuidarDeLonge {
    /**
     * Tecla que pode ACEITAR o consentimento (17/11/2026, decisão do Paulo: "o que funcionar melhor").
     *  - controle Bluetooth/IR do Chromecast: aparelho com id próprio (> 0, não o VIRTUAL_KEYBOARD -1);
     *  - controle da própria LG pelo HDMI-CEC: o Android injeta como VIRTUAL_KEYBOARD, mas com a fonte HDMI. O
     *    `input keyevent` do shell não tem fonte HDMI, então não passa por aqui.
     * O que ainda imitaria uma tecla física (um aparelho virtual criado pelo shell) é barrado à parte: com a pergunta
     * na tela, o shell do Claude recusa comandos ([CuidarDeLongeTv.consentimentoNaTela]).
     */
    fun teclaDeGente(deviceId: Int, source: Int, virtual: Int = -1, fonteHdmi: Int = 0x02000001): Boolean =
        (deviceId > 0 && deviceId != virtual) || (deviceId == virtual && source and fonteHdmi == fonteHdmi)

    /**
     * Depuração 18/11/2026: um teclado virtual criado pelo shell (uinput/uhid) ganha id > 0 e passaria por [teclaDeGente].
     * Por isso a tela guarda os aparelhos de entrada que existiam quando a pergunta ABRIU ([antes], de
     * InputDevice.getDeviceIds()) e recusa tecla de aparelho que apareceu depois. id <= 0 (o VIRTUAL_KEYBOARD do CEC)
     * fica com a regra de [teclaDeGente]. [antes] null (não deu para ler) = recusa.
     * Resíduo: um aparelho falso criado ANTES de a pergunta abrir continua passando; e um controle Bluetooth que
     * reconectou depois (TV acordando) é recusado: a pessoa fecha e abre a pergunta de novo.
     */
    fun aparelhoDeAntes(deviceId: Int, antes: Set<Int>?): Boolean = deviceId <= 0 || (antes != null && deviceId in antes)

    /**
     * ESTOU_BEM (16/11/2026, a 79 da Play): na primeira vez que a TV acende no dia, ela pergunta "Avisar Ana que está tudo
     * bem?" e só a resposta da PESSOA manda o aviso. Nada sai sem o botão: é o oposto de vigiar, e por isso cabe na Play.
     */
    enum class Aviso { AJUDA, LEMBRETE, NAO_LIGOU, ESTOU_BEM }

    /**
     * Avisos que esta build oferece. [familia] false (build da Play) = só o que parte da própria pessoa (ajuda e "estou
     * bem"). [extras] = o plugin Extras (app separado, baixado no site) está autorizado: ele traz de volta o "não ligou"
     * automático (79), que a Play não aceita dentro do app.
     */
    fun permitidos(familia: Boolean, extras: Boolean = false): Set<Aviso> = when {
        familia -> Aviso.values().toSet()
        extras -> setOf(Aviso.AJUDA, Aviso.ESTOU_BEM, Aviso.NAO_LIGOU)
        else -> setOf(Aviso.AJUDA, Aviso.ESTOU_BEM)
    }

    /** Tira os avisos que a build não oferece. Mudar os avisos muda a assinatura: um aceite antigo deixa de valer. */
    fun limitar(c: Config, familia: Boolean, extras: Boolean = false): Config =
        if (c.avisos.all { it in permitidos(familia, extras) }) c else c.copy(avisos = c.avisos.filter { it in permitidos(familia, extras) }.toSet())

    fun chaveBem(dia: Int) = "bem@$dia"

    /**
     * Tecla que, segurada [SEGURAR_MS], pede ajuda. VOLTAR é o padrão: é a tecla que todo mundo acha, e ninguém a segura
     * 3 s sem querer (o toque curto continua sendo "voltar"). HOME curto/longo já tem uso; os botões de app seguram 3 s
     * para trocar o app. Códigos do Android (KeyEvent) escritos à mão para a lógica ficar sem Android.
     */
    enum class Tecla(val codigo: Int) { VOLTAR(4), OK(23) }

    const val SEGURAR_MS = 3_000L
    val PRAZOS = listOf(10, 15, 20, 30, 60)
    const val PADRAO_PRAZO = 15
    val MARGENS = listOf(60, 120, 180, 240)
    const val PADRAO_MARGEM = 120
    const val MAX_LEMBRETES = 12
    const val MAX_TEXTO = 60
    const val MAX_NOME = 30
    const val TODO_DIA = 0b1111111

    /** Lembrete que não foi mostrado até isto depois da hora (TV sem energia, por exemplo) já não aparece. */
    const val MOSTRAR_ATE_MS = 2 * 60 * 60_000L
    /** Um aviso de lembrete não confirmado só sai até isto depois da hora (passou disso, é notícia velha). */
    const val AVISAR_ATE_MS = 6 * 60 * 60_000L
    /** Envio que falhou (TV acordando sem rede) tenta de novo depois disto, até [MAX_TENTATIVAS] vezes. */
    const val NOVA_TENTATIVA_MS = 5 * 60_000L
    const val MAX_TENTATIVAS = 3

    /** Hora de costume (79): o dia "começa" às 5h; ligar de madrugada não conta como a primeira do dia. */
    const val INICIO_DIA_MIN = 5 * 60
    /** Aviso de "não ligou" depois disto (23:30) não sai: tarde demais para ligar e perguntar. */
    const val LIMITE_AVISO_MIN = 23 * 60 + 30
    /** Precisa de pelo menos isto de dias com a TV ligada para aprender a hora (sem hora fixa). */
    const val MIN_AMOSTRAS = 5
    /** A hora de costume é a mediana dos últimos tantos dias ligados. */
    const val AMOSTRAS = 14
    const val GUARDAR_DIAS = 28

    data class Lembrete(val id: Int, val hora: Int, val minuto: Int, val dias: Int = TODO_DIA, val texto: String) {
        val hhmm: String get() = "%02d:%02d".format(hora, minuto)
    }

    data class Config(
        /** Nome de quem recebe ("Ana"). Vai na pergunta grande da TV. */
        val nome: String = "",
        /** Aparelhos que recebem: os ids (fim do endpoint) das inscrições de Avisos. Vazio = ninguém. */
        val aparelhos: List<String> = emptyList(),
        val avisos: Set<Aviso> = emptySet(),
        val tecla: Tecla = Tecla.VOLTAR,
        val prazoMin: Int = PADRAO_PRAZO,
        val margemMin: Int = PADRAO_MARGEM,
        /** Hora de costume fixada pelo dono (minuto do dia); -1 = aprender sozinha. */
        val horaFixa: Int = -1,
        val lembretes: List<Lembrete> = emptyList(),
        /** Acender a tela para mostrar o lembrete, se a TV estiver desligada (como o despertador). */
        val acender: Boolean = true,
        /** A [assinatura] que a pessoa aceitou na TV; vazio = nunca aceitou ou retirou. */
        val consentimento: String = "",
        val consentidoEm: Long = 0L,
    )

    // ---------------------------------------------------------------- consentimento

    /** O que a pessoa aceita: este nome, estes aparelhos e estes avisos. Mudou um deles, vale outra pergunta. */
    fun assinatura(c: Config): String = listOf(
        c.nome.trim(), c.aparelhos.sorted().joinToString(","), c.avisos.map { it.name }.sorted().joinToString(","),
    ).joinToString("|")

    /** O que falta para poder perguntar na TV, ou null se está pronto. */
    enum class Falta { NOME, APARELHO, AVISO }
    fun falta(c: Config): Falta? = when {
        c.nome.isBlank() -> Falta.NOME
        c.aparelhos.isEmpty() -> Falta.APARELHO
        c.avisos.isEmpty() -> Falta.AVISO
        else -> null
    }

    fun consentido(c: Config): Boolean = falta(c) == null && c.consentimento.isNotEmpty() && c.consentimento == assinatura(c)

    /** O aviso [a] sai da TV? Só com consentimento válido e o aviso escolhido. */
    fun ativo(c: Config, a: Aviso): Boolean = consentido(c) && a in c.avisos

    /** A pessoa disse OK na TV. Sem nome/aparelho/aviso não há o que aceitar: nada muda. */
    fun consentir(c: Config, agora: Long): Config = if (falta(c) != null) c else c.copy(consentimento = assinatura(c), consentidoEm = agora)

    fun retirar(c: Config): Config = c.copy(consentimento = "", consentidoEm = 0L)

    // ---------------------------------------------------------------- limpeza do que vem do painel

    fun limparNome(t: String): String = t.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(MAX_NOME)
    fun limparTexto(t: String): String = t.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(MAX_TEXTO)

    /** Id de aparelho do painel: os últimos 16 caracteres do endpoint de push (base64url). */
    fun idValido(id: String): Boolean = Regex("^[A-Za-z0-9_\\-]{1,16}$").matches(id)

    fun prazoValido(m: Int) = if (m in PRAZOS) m else PADRAO_PRAZO
    fun margemValida(m: Int) = if (m in MARGENS) m else PADRAO_MARGEM

    /** Grava (novo: id 0) um lembrete. Texto vazio ou lista cheia: a lista não muda. */
    fun salvarLembrete(lista: List<Lembrete>, l: Lembrete): List<Lembrete> {
        val texto = limparTexto(l.texto)
        if (texto.isEmpty() || l.hora !in 0..23 || l.minuto !in 0..59) return lista
        val dias = (l.dias and TODO_DIA).let { if (it == 0) TODO_DIA else it }
        val existe = l.id > 0 && lista.any { it.id == l.id }
        if (!existe && lista.size >= MAX_LEMBRETES) return lista
        // Id > 0 fica (editar, ou ler do arquivo sem renumerar: as marcas do dia usam o id); 0 = novo, o próximo livre.
        val id = if (l.id > 0) l.id else (lista.maxOfOrNull { it.id } ?: 0) + 1
        val novo = Lembrete(id, l.hora, l.minuto, dias, texto)
        return (lista.filterNot { it.id == id } + novo).sortedWith(compareBy({ it.hora }, { it.minuto }, { it.id }))
    }

    fun removerLembrete(lista: List<Lembrete>, id: Int) = lista.filterNot { it.id == id }

    /**
     * Depuração 18/11/2026: o id de um lembrete apagado volta no próximo novo (máximo + 1) e herdava as marcas do dia
     * ("confirmado"), então o novo não aparecia hoje. Quem remove apaga também as marcas "<id>@...".
     */
    /**
     * Depuração 18/11/2026: chegou outro lembrete (ou o "Estou bem?") com um lembrete/"Estou bem?" ainda na tela. Antes a
     * tela se refazia e o 1º sumia já anotado como mostrado (a família recebia "não confirmado"). Agora o novo ESPERA
     * numa fila e aparece quando a pessoa responde ao atual. Ajuda e consentimento (a pessoa pediu) passam na frente.
     */
    fun esperaNaFila(atual: String, novo: String): Boolean {
        val perguntas = setOf("lembrete", "estou_bem")
        return atual in perguntas && novo in perguntas
    }

    fun esquecerLembrete(e: Estado, id: Int) = e.copy(marcas = e.marcas.filterNot { it.chave.startsWith("$id@") })

    // ---------------------------------------------------------------- JSON da configuração

    fun paraJson(c: Config): JSONObject = JSONObject()
        .put("nome", c.nome).put("aparelhos", JSONArray(c.aparelhos))
        .put("avisos", JSONArray(c.avisos.map { it.name }.sorted()))
        .put("tecla", c.tecla.name).put("prazo", c.prazoMin).put("margem", c.margemMin).put("horaFixa", c.horaFixa)
        .put("acender", c.acender)
        .put("lembretes", JSONArray().apply {
            c.lembretes.forEach { put(JSONObject().put("id", it.id).put("hora", it.hora).put("minuto", it.minuto).put("dias", it.dias).put("texto", it.texto)) }
        })
        .put("consentimento", c.consentimento).put("consentidoEm", c.consentidoEm)

    fun deJson(texto: String?): Config {
        val o = try { JSONObject(texto ?: return Config()) } catch (_: Exception) { return Config() }
        fun lista(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        var lembretes = emptyList<Lembrete>()
        o.optJSONArray("lembretes")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                lembretes = salvarLembrete(lembretes, Lembrete(x.optInt("id"), x.optInt("hora", -1), x.optInt("minuto", -1),
                    x.optInt("dias", TODO_DIA), x.optString("texto")))
            }
        }
        val hf = o.optInt("horaFixa", -1)
        return Config(
            nome = limparNome(o.optString("nome")),
            aparelhos = lista("aparelhos").filter { idValido(it) }.distinct().take(Avisos.MAX_INSCRICOES),
            avisos = lista("avisos").mapNotNull { n -> Aviso.values().firstOrNull { it.name == n } }.toSet(),
            tecla = Tecla.values().firstOrNull { it.name == o.optString("tecla") } ?: Tecla.VOLTAR,
            prazoMin = prazoValido(o.optInt("prazo", PADRAO_PRAZO)),
            margemMin = margemValida(o.optInt("margem", PADRAO_MARGEM)),
            horaFixa = if (hf in 0 until 24 * 60) hf else -1,
            lembretes = lembretes,
            acender = o.optBoolean("acender", true),
            consentimento = o.optString("consentimento"),
            consentidoEm = o.optLong("consentidoEm"),
        )
    }

    // ---------------------------------------------------------------- estado (o que já aconteceu)

    /**
     * Uma marca por ocorrência: "<id do lembrete>@<AAAAMMDD>" ou "tv@<AAAAMMDD>" (79).
     * [mostrado]/[confirmado]/[avisado]: quando (ms); 0 = ainda não. [tentativas]/[tentouEm]: envios que falharam.
     */
    data class Marca(val chave: String, val mostrado: Long = 0L, val confirmado: Long = 0L, val avisado: Long = 0L,
                     val tentativas: Int = 0, val tentouEm: Long = 0L)

    /** [ligadas]: dia (AAAAMMDD) -> minuto do dia em que a TV foi ligada pela 1ª vez depois das 5h. */
    data class Estado(val marcas: List<Marca> = emptyList(), val ligadas: Map<Int, Int> = emptyMap()) {
        fun marca(chave: String) = marcas.firstOrNull { it.chave == chave } ?: Marca(chave)
        fun com(m: Marca) = copy(marcas = marcas.filterNot { it.chave == m.chave } + m)
    }

    fun estadoParaJson(e: Estado): JSONObject = JSONObject()
        .put("marcas", JSONArray().apply {
            e.marcas.forEach { put(JSONObject().put("c", it.chave).put("m", it.mostrado).put("f", it.confirmado).put("a", it.avisado)
                .put("t", it.tentativas).put("e", it.tentouEm)) }
        })
        .put("ligadas", JSONObject().apply { e.ligadas.forEach { (d, m) -> put(d.toString(), m) } })

    fun estadoDeJson(texto: String?): Estado {
        val o = try { JSONObject(texto ?: return Estado()) } catch (_: Exception) { return Estado() }
        val marcas = ArrayList<Marca>()
        o.optJSONArray("marcas")?.let { a ->
            for (i in 0 until minOf(a.length(), 200)) {
                val x = a.optJSONObject(i) ?: continue
                val c = x.optString("c").takeIf { it.isNotEmpty() } ?: continue
                marcas += Marca(c, x.optLong("m"), x.optLong("f"), x.optLong("a"), x.optInt("t"), x.optLong("e"))
            }
        }
        val ligadas = HashMap<Int, Int>()
        o.optJSONObject("ligadas")?.let { l -> l.keys().forEach { k -> k.toIntOrNull()?.let { d -> l.optInt(k, -1).takeIf { it in 0 until 24 * 60 }?.let { ligadas[d] = it } } } }
        return Estado(marcas.distinctBy { it.chave }, ligadas)
    }

    // ---------------------------------------------------------------- datas

    fun dia(ms: Long, fuso: TimeZone): Int = Instant.ofEpochMilli(ms).atZone(fuso.toZoneId()).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
    fun minutoDoDia(ms: Long, fuso: TimeZone): Int = Instant.ofEpochMilli(ms).atZone(fuso.toZoneId()).let { it.hour * 60 + it.minute }
    private fun data(dia: Int): LocalDate = LocalDate.of(dia / 10000, dia / 100 % 100, dia % 100)
    /** O instante de [minuto] no [dia] (AAAAMMDD), no fuso. */
    // Hora de RELÓGIO (atTime + atZone): "meia-noite + N minutos" errava 1 h no dia da troca do horário de verão.
    fun instante(dia: Int, minuto: Int, fuso: TimeZone): Long =
        data(dia).atTime(minuto / 60, minuto % 60).atZone(fuso.toZoneId()).toInstant().toEpochMilli()
    private fun diaDaSemana(dia: Int): Int = data(dia).dayOfWeek.value % 7   // domingo = 0, como em ProximoAlarme

    /** As ocorrências de [l] em [de, ate] (de hoje e de ontem, que é o que importa aqui), da mais velha para a mais nova. */
    fun ocorrencias(l: Lembrete, de: Long, ate: Long, fuso: TimeZone): List<Long> {
        val hoje = dia(ate, fuso)
        val ontem = dia(data(hoje).minusDays(1).atStartOfDay(fuso.toZoneId()).toInstant().toEpochMilli(), fuso)
        return listOf(ontem, hoje).filter { ProximoAlarme.tocaNoDia(l.dias, diaDaSemana(it)) }
            .map { instante(it, l.hora * 60 + l.minuto, fuso) }.filter { it in de..ate }
    }

    fun chave(l: Lembrete, momento: Long, fuso: TimeZone) = "${l.id}@${dia(momento, fuso)}"
    fun chaveTv(dia: Int) = "tv@$dia"

    // ---------------------------------------------------------------- 79: hora de costume

    /** Anota que a TV foi ligada em [agora]. Só a 1ª do dia depois das 5h conta; guarda [GUARDAR_DIAS] dias. */
    fun anotarLigou(ligadas: Map<Int, Int>, agora: Long, fuso: TimeZone): Map<Int, Int> {
        val m = minutoDoDia(agora, fuso)
        val d = dia(agora, fuso)
        if (m < INICIO_DIA_MIN || d in ligadas) return ligadas
        return (ligadas + (d to m)).entries.sortedByDescending { it.key }.take(GUARDAR_DIAS).associate { it.key to it.value }
    }

    /** Hora de costume (minuto do dia) para [hoje]: a fixa, ou a mediana dos últimos dias ligados (sem contar hoje). */
    fun horaCostume(c: Config, ligadas: Map<Int, Int>, hoje: Int): Int? {
        if (c.horaFixa >= 0) return c.horaFixa
        val amostras = ligadas.filterKeys { it < hoje }.entries.sortedByDescending { it.key }.take(AMOSTRAS).map { it.value }.sorted()
        if (amostras.size < MIN_AMOSTRAS) return null
        val meio = amostras.size / 2
        return if (amostras.size % 2 == 1) amostras[meio] else (amostras[meio - 1] + amostras[meio]) / 2
    }

    /** Minuto do dia em que avisar "não ligou" em [hoje]; null = não há hora (aprendendo) ou cai depois das 23:30. */
    fun minutoAviso(c: Config, ligadas: Map<Int, Int>, hoje: Int): Int? =
        horaCostume(c, ligadas, hoje)?.let { it + c.margemMin }?.takeIf { it <= LIMITE_AVISO_MIN }

    // ---------------------------------------------------------------- o que fazer agora

    sealed class Acao {
        data class Mostrar(val lembrete: Lembrete, val momento: Long, val chave: String) : Acao()
        data class AvisarLembrete(val lembrete: Lembrete, val momento: Long, val chave: String) : Acao()
        data class AvisarNaoLigou(val dia: Int, val chave: String) : Acao()
        /** Primeira vez que a TV acende no dia: perguntar "Estou bem?" (só a pessoa manda o aviso). */
        data class PerguntarEstouBem(val dia: Int, val chave: String) : Acao()
    }

    private fun podeTentar(m: Marca, agora: Long) =
        m.avisado == 0L && m.tentativas < MAX_TENTATIVAS && (m.tentouEm == 0L || agora >= m.tentouEm + NOVA_TENTATIVA_MS)

    /**
     * O que está devido em [agora]. [ligadaAgora]: a tela está acesa neste instante (conta como "ligou hoje").
     * Quem chama faz e anota (mostrado / avisado / tentativa) no [Estado].
     */
    fun devidos(c: Config, e: Estado, agora: Long, fuso: TimeZone, ligadaAgora: Boolean = false): List<Acao> {
        val r = ArrayList<Acao>()
        val prazo = c.prazoMin * 60_000L
        for (l in c.lembretes) for (m in ocorrencias(l, agora - AVISAR_ATE_MS, agora, fuso)) {
            val k = chave(l, m, fuso)
            val marca = e.marca(k)
            if (marca.mostrado == 0L && marca.confirmado == 0L && agora - m < MOSTRAR_ATE_MS) r += Acao.Mostrar(l, m, k)
            if (marca.confirmado == 0L && agora >= m + prazo && ativo(c, Aviso.LEMBRETE) && podeTentar(marca, agora))
                r += Acao.AvisarLembrete(l, m, k)
        }
        if (ativo(c, Aviso.NAO_LIGOU) && !ligadaAgora) {
            val hoje = dia(agora, fuso)
            val alvo = minutoAviso(c, e.ligadas, hoje)
            val k = chaveTv(hoje)
            if (alvo != null && hoje !in e.ligadas && minutoDoDia(agora, fuso) in alvo..LIMITE_AVISO_MIN && podeTentar(e.marca(k), agora))
                r += Acao.AvisarNaoLigou(hoje, k)
        }
        if (ativo(c, Aviso.ESTOU_BEM) && ligadaAgora && minutoDoDia(agora, fuso) in INICIO_DIA_MIN..LIMITE_AVISO_MIN) {
            val k = chaveBem(dia(agora, fuso))
            if (e.marca(k).mostrado == 0L) r += Acao.PerguntarEstouBem(dia(agora, fuso), k)
        }
        return r
    }

    /** Quando acordar de novo (o próximo lembrete, prazo, nova tentativa ou hora do "não ligou"); null = nada pela frente. */
    fun proximo(c: Config, e: Estado, agora: Long, fuso: TimeZone): Long? {
        val t = ArrayList<Long>()
        val prazo = c.prazoMin * 60_000L
        for (l in c.lembretes) {
            ProximoAlarme.calcular(l.hora, l.minuto, l.dias, agora, fuso)?.let { t += it }
            if (ativo(c, Aviso.LEMBRETE)) for (m in ocorrencias(l, agora - AVISAR_ATE_MS, agora, fuso)) {
                val marca = e.marca(chave(l, m, fuso))
                if (marca.confirmado != 0L || marca.avisado != 0L || marca.tentativas >= MAX_TENTATIVAS) continue
                t += maxOf(m + prazo, if (marca.tentouEm > 0) marca.tentouEm + NOVA_TENTATIVA_MS else 0L)
            }
        }
        if (ativo(c, Aviso.NAO_LIGOU)) {
            val hoje = dia(agora, fuso)
            val marca = e.marca(chaveTv(hoje))
            minutoAviso(c, e.ligadas, hoje)?.let { alvo ->
                if (hoje !in e.ligadas && marca.avisado == 0L && marca.tentativas < MAX_TENTATIVAS)
                    t += maxOf(instante(hoje, alvo, fuso), if (marca.tentouEm > 0) marca.tentouEm + NOVA_TENTATIVA_MS else 0L)
            }
            // Amanhã: a conta de amanhã usa as ligadas até hoje (a de hoje, se houver, entra na mediana).
            val amanha = dia(instante(hoje, 0, fuso) + 36 * 60 * 60_000L, fuso)
            minutoAviso(c, e.ligadas, amanha)?.let { t += instante(amanha, it, fuso) }
        }
        return t.filter { it > agora }.minOrNull() ?: t.minOrNull()?.let { agora + 1_000L }
    }

    /** Marca envio: [aceitos] > 0 = avisado; senão conta a tentativa (depois de [MAX_TENTATIVAS], desiste). */
    fun anotarEnvio(e: Estado, chave: String, aceitos: Int, agora: Long): Estado {
        val m = e.marca(chave)
        return e.com(if (aceitos > 0) m.copy(avisado = agora) else m.copy(tentativas = m.tentativas + 1, tentouEm = agora))
    }

    fun anotarMostrado(e: Estado, chave: String, agora: Long) = e.com(e.marca(chave).copy(mostrado = agora))
    fun anotarConfirmado(e: Estado, chave: String, agora: Long) = e.com(e.marca(chave).let { it.copy(confirmado = agora, mostrado = it.mostrado.takeIf { m -> m > 0 } ?: agora) })

    /** Tira marcas de mais de 3 dias (a chave tem o dia). */
    fun podar(e: Estado, agora: Long, fuso: TimeZone): Estado {
        val limite = dia(agora - 3 * 86_400_000L, fuso)
        return e.copy(marcas = e.marcas.filter { (it.chave.substringAfter('@').toIntOrNull() ?: 0) >= limite })
    }

    // ---------------------------------------------------------------- 77: segurar a tecla

    /**
     * Segurar a tecla de ajuda 3 s. O toque curto continua indo para o app (não se engole o DOWN: assim VOLTAR curto
     * segue sendo voltar). Quando completa 3 s ([venceu], chamado por um relógio, ou uma repetição com 3 s), dispara uma
     * vez e daí em diante engole as repetições e o UP: o app não recebe o "voltar" de quem só queria ajuda.
     */
    class Segurar(private val tempoMs: Long = SEGURAR_MS) {
        var tecla: Int = Tecla.VOLTAR.codigo
        private var desdeMs = -1L
        private var disparou = false

        /** Uma tecla. Devolve true se é para engolir. [aoDisparar] roda (uma vez) quando completa o tempo pela repetição. */
        fun evento(codigo: Int, desce: Boolean, repeticao: Int, agoraMs: Long, aoDisparar: () -> Unit): Boolean {
            if (codigo != tecla) return false
            if (desce) {
                if (repeticao == 0) { desdeMs = agoraMs; disparou = false; return false }
                if (disparou) return true
                if (desdeMs >= 0 && agoraMs - desdeMs >= tempoMs) { disparou = true; aoDisparar(); return true }
                return false
            }
            val engole = disparou
            desdeMs = -1L; disparou = false
            return engole
        }

        /** O relógio de [tempoMs] venceu. True = dispara agora (a tecla ainda está apertada e não disparou). */
        fun venceu(agoraMs: Long): Boolean {
            if (desdeMs < 0 || disparou || agoraMs - desdeMs < tempoMs) return false
            disparou = true
            return true
        }

        val apertada: Boolean get() = desdeMs >= 0 && !disparou
        fun soltar() { desdeMs = -1L; disparou = false }
    }

    /** "20:00" para o aviso da família (o texto do lembrete não vai). */
    fun textoHora(momento: Long, fuso: TimeZone): String = minutoDoDia(momento, fuso).let { "%02d:%02d".format(it / 60, it % 60) }
}

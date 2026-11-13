package br.paulo.tvlauncher

/**
 * Atualização vigiada (10/11/2026): as REGRAS, sem Android, para o teste JVM (VigiaRegrasTest).
 * Quem lê/grava o arquivo e mostra o aviso é o VigiaAtualizacao.kt.
 *
 * Por que vigiar em vez de voltar à versão anterior: o Android não deixa um app comum (nem Device Owner) instalar uma
 * versão MENOR por cima. Pedir isso (SessionParams.setRequestDowngrade) é API de sistema, que nem existe no android.jar
 * público (verificado no SDK 36 desta máquina); e o sistema só aceita o pedido se o app ou o aparelho forem "debuggable"
 * (lido no código do AOSP, PackageManagerServiceUtils.isDowngradePermitted; não verificado na TV). O nosso APK de
 * Release não é debuggable e a TV é de fábrica. Então, se a versão nova cai, o que dá para fazer é: marcar a versão
 * como ruim, parar de instalar sozinho, avisar e não abrir a tela que caiu até chegar uma versão corrigida.
 *
 * O ciclo:
 *  1. o launcher abre numa versão MAIOR que a última vista -> começa a vigia dessa versão ([aoIniciar]);
 *  2. durante a vigia ([DURACAO_VIGIA_MS]), cada queda é anotada ([aoCair]); [QUEDAS_PARA_RUIM] quedas dentro de
 *     [JANELA_QUEDAS_MS] = versão ruim: entra na lista de ruins, pausa a atualização automática e liga o modo seguro
 *     (a tela que aparece na pilha do erro não abre mais nesta versão);
 *  3. versão ruim nunca é instalada de novo ([podeInstalar]), nem pelo "Instalar agora";
 *  4. um Release com versionCode MAIOR que o ruim limpa a pausa ([aoVerRelease]); o modo seguro sai sozinho quando a
 *     versão instalada muda (a tela bloqueada só vale na versão que caiu).
 */
object VigiaRegras {
    const val JANELA_QUEDAS_MS = 10 * 60_000L        // "2 quedas em 10 min" (pedido do dono)
    const val QUEDAS_PARA_RUIM = 2
    const val DURACAO_VIGIA_MS = 48 * 60 * 60_000L   // dois dias sem cair de novo = versão aprovada
    const val MAX_RUINS = 20                         // a lista não cresce para sempre (versões velhas já não voltam)
    private const val PACOTE = "br.paulo.tvlauncher."
    /** Piso do relógio (01/11/2026 UTC, antes desta função existir): o Chromecast pode abrir com a hora errada antes
     *  do NTP. Abaixo disso a hora não vale, e a abertura/queda é ignorada (não começa, não encerra, não conta). */
    const val RELOGIO_MINIMO_MS = 1_793_491_200_000L

    /** Telas que NUNCA são bloqueadas: a HOME, as Configurações (onde se sai do modo seguro), o Diagnóstico (onde se
     *  vê o erro) e o despertador (melhor tentar tocar do que não tocar). */
    val NUNCA_BLOQUEAR = setOf("MainActivity", "ConfigLauncherActivity", "DiagnosticoActivity", "AlarmeTocandoActivity")

    data class Estado(
        val versaoVista: Long = 0L,      // última versionCode que abriu (para saber que "acabou de atualizar")
        val vigiando: Long = 0L,         // versão sob vigia (0 = nenhuma)
        val vigiaDesde: Long = 0L,
        val quedas: List<Long> = emptyList(),   // horários das quedas recentes da versão vigiada
        val ruins: List<Long> = emptyList(),
        val pausaPor: Long = 0L,         // versão que pausou a atualização automática (0 = não pausada)
        val telaSuspeita: String = "",   // nome da classe da tela que não abre (modo seguro); "" = nenhuma
        val seguroVersao: Long = 0L,     // a versão em que o modo seguro vale
        val avisoPendente: Boolean = false,     // falta mostrar o aviso na TV
        val registroPendente: Boolean = false,  // falta escrever no registro do painel
    ) {
        val pausada get() = pausaPor != 0L
    }

    /** O launcher abriu na versão [versao]. Começa/termina a vigia e desliga o modo seguro de outra versão. */
    fun aoIniciar(e: Estado, versao: Long, agora: Long): Estado {
        // Hora ainda errada (antes do NTP): não mexe em nada. A próxima abertura, com a hora certa, vê a versão nova.
        if (agora < RELOGIO_MINIMO_MS) return e
        var s = e
        // Vigia vencida (passou o prazo, ou o relógio voltou) ou de outra versão: acabou, a versão ficou aprovada.
        if (s.vigiando != 0L && (s.vigiando != versao || agora - s.vigiaDesde > DURACAO_VIGIA_MS || agora < s.vigiaDesde))
            s = s.copy(vigiando = 0L, vigiaDesde = 0L, quedas = emptyList())
        // Versão maior que a última vista = acabou de atualizar: vigia. (Primeira vez de todas, versaoVista = 0: não há
        // "antes" para comparar, então não vigia.)
        if (s.versaoVista != 0L && versao > s.versaoVista)
            s = s.copy(vigiando = versao, vigiaDesde = agora, quedas = emptyList())
        // O modo seguro só vale na versão que caiu: com outra instalada (nova, ou a antiga pelo PC), a tela volta.
        if (s.seguroVersao != 0L && s.seguroVersao != versao) s = s.copy(telaSuspeita = "", seguroVersao = 0L)
        // Rodando uma versão maior que a que caiu (veio pelo Atualizador ou pelo PC): a pausa também acaba.
        if (s.pausada && versao > s.pausaPor) s = s.copy(pausaPor = 0L)
        return s.copy(versaoVista = versao)
    }

    class Queda(val estado: Estado, val marcouRuim: Boolean)

    /**
     * O launcher caiu na versão [versao]. [tela] = a tela que aparece na pilha do erro ([telaDoErro]), ou null.
     * Faz antes o [aoIniciar], porque a queda pode vir antes de o launcher ter anotado que abriu.
     */
    fun aoCair(e0: Estado, versao: Long, agora: Long, tela: String?): Queda {
        if (agora < RELOGIO_MINIMO_MS) return Queda(e0, false)   // hora errada: a janela de 10 min não teria sentido
        val e = aoIniciar(e0, versao, agora)
        // Já marcada ruim e sem tela identificada ainda: aproveita esta queda para achar a tela.
        if (e.pausaPor == versao && e.telaSuspeita.isEmpty() && !tela.isNullOrEmpty())
            return Queda(e.copy(telaSuspeita = tela, seguroVersao = versao), false)
        if (e.vigiando != versao) return Queda(e, false)
        val quedas = (e.quedas + agora).filter { agora - it in 0..JANELA_QUEDAS_MS }.takeLast(10)
        if (quedas.size < QUEDAS_PARA_RUIM) return Queda(e.copy(quedas = quedas), false)
        val ruins = (e.ruins.filter { it != versao } + versao).takeLast(MAX_RUINS)
        return Queda(e.copy(
            vigiando = 0L, vigiaDesde = 0L, quedas = emptyList(), ruins = ruins, pausaPor = versao,
            telaSuspeita = tela.orEmpty(), seguroVersao = if (tela.isNullOrEmpty()) 0L else versao,
            avisoPendente = true, registroPendente = true,
        ), true)
    }

    /** O Release [codigo] pode ser instalado? Nunca uma versão ruim; com a pausa, só uma MAIOR que a que caiu. */
    fun podeInstalar(e: Estado, codigo: Long): Boolean = codigo !in e.ruins && (!e.pausada || codigo > e.pausaPor)

    /** O GitHub mostrou o Release [codigo]. Se for maior que a versão que caiu (e não ruim), a pausa acaba. */
    fun aoVerRelease(e: Estado, codigo: Long): Estado =
        if (e.pausada && codigo > e.pausaPor && codigo !in e.ruins) e.copy(pausaPor = 0L) else e

    /** A tela [classe] (nome curto, ex. "PlayerActivity") está bloqueada pelo modo seguro na versão [versao]? */
    fun telaBloqueada(e: Estado, versao: Long, classe: String): Boolean =
        e.seguroVersao == versao && e.telaSuspeita.isNotEmpty() && e.telaSuspeita == classe && classe !in NUNCA_BLOQUEAR

    /** O dono mandou religar (Configurações): sai do modo seguro e da pausa. A lista de ruins fica (nunca reinstala). */
    fun liberar(e: Estado): Estado = e.copy(pausaPor = 0L, telaSuspeita = "", seguroVersao = 0L, avisoPendente = false)

    /**
     * Qual tela causou o erro: as classes da pilha (do frame mais fundo para fora, com as causas depois), e vale a
     * primeira do launcher cujo nome termina em "Activity". "PlayerActivity\$carregar\$1" -> "PlayerActivity".
     * Se for uma das [NUNCA_BLOQUEAR] (ex.: a HOME), não dá para bloquear: devolve null.
     * No Release o R8 ofusca: as telas guardam o nome (estão no manifesto), mas lambdas e classes auxiliares ganham
     * nomes curtos fora de "br.paulo.tvlauncher.". Queda numa thread ou num callback sem frame da tela dá null: a
     * versão é marcada ruim (pausa + aviso) mas sem modo seguro.
     */
    fun telaDoErro(classes: List<String>): String? {
        for (c in classes) {
            if (!c.startsWith(PACOTE)) continue
            val curto = c.removePrefix(PACOTE).substringBefore('$')
            if (curto.contains('.') || !curto.endsWith("Activity")) continue
            return if (curto in NUNCA_BLOQUEAR) null else curto
        }
        return null
    }

    // Arquivo de texto "chave=valor" (um por linha). Não é SharedPreferences de propósito: o player roda em outro
    // processo (:player) e o cache das prefs de um processo apagaria o que o outro gravou.
    fun paraTexto(e: Estado): String = buildString {
        append("versao_vista=").append(e.versaoVista).append('\n')
        append("vigiando=").append(e.vigiando).append('\n')
        append("vigia_desde=").append(e.vigiaDesde).append('\n')
        append("quedas=").append(e.quedas.joinToString(",")).append('\n')
        append("ruins=").append(e.ruins.joinToString(",")).append('\n')
        append("pausa_por=").append(e.pausaPor).append('\n')
        append("tela=").append(e.telaSuspeita).append('\n')
        append("seguro_versao=").append(e.seguroVersao).append('\n')
        append("aviso=").append(e.avisoPendente).append('\n')
        append("registro=").append(e.registroPendente).append('\n')
    }

    /** O contrário de [paraTexto]. Texto estragado = valores padrão (nunca derruba o launcher). */
    fun deTexto(t: String?): Estado {
        if (t.isNullOrBlank()) return Estado()
        val m = t.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        fun n(k: String) = m[k]?.toLongOrNull() ?: 0L
        fun lista(k: String) = m[k].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        val tela = m["tela"].orEmpty().takeIf { Regex("[A-Za-z0-9_]{1,80}").matches(it) }.orEmpty()
        return Estado(
            versaoVista = n("versao_vista"), vigiando = n("vigiando"), vigiaDesde = n("vigia_desde"),
            quedas = lista("quedas").takeLast(10), ruins = lista("ruins").takeLast(MAX_RUINS), pausaPor = n("pausa_por"),
            telaSuspeita = tela, seguroVersao = n("seguro_versao"),
            avisoPendente = m["aviso"] == "true", registroPendente = m["registro"] == "true",
        )
    }
}

/**
 * Conferência do APK baixado, antes de instalar (pura, VigiaRegrasTest). O Atualizador lê o APK com
 * PackageManager.getPackageArchiveInfo e passa aqui o que achou.
 */
object ConferenciaApk {
    enum class Resultado { OK, ILEGIVEL, PACOTE, VERSAO_TAG, NAO_E_MAIOR, CHAVE, RUIM }

    fun conferir(
        legivel: Boolean, pacoteApk: String?, pacoteMeu: String,
        codigoApk: Long, codigoTag: Long, codigoInstalado: Long,
        mesmaChave: Boolean, podeInstalar: Boolean,
    ): Resultado = when {
        !legivel -> Resultado.ILEGIVEL
        pacoteApk != pacoteMeu -> Resultado.PACOTE                // outro app no lugar do launcher: nunca
        codigoApk != codigoTag -> Resultado.VERSAO_TAG            // o APK não é o que o Release diz ser
        codigoApk <= codigoInstalado -> Resultado.NAO_E_MAIOR     // igual ou mais velho: o Android recusaria mesmo
        !mesmaChave -> Resultado.CHAVE                            // outra chave: o Android recusaria (e não é nosso)
        !podeInstalar -> Resultado.RUIM                           // versão que já caiu nesta TV
        else -> Resultado.OK
    }
}

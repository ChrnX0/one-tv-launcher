package br.paulo.tvlauncher

/**
 * O que aparece ao segurar OK num app da tela inicial (05/11/2026). Fica separado da MainActivity para dar para testar
 * no computador, sem TV: aqui é só a regra, a tela monta o menu a partir de [acoes].
 *
 * Antes, segurar OK num app solto ia direto para o modo mover. Agora abre um menu, como no Google TV:
 *  - Mover (o mesmo modo de antes, um OK a mais);
 *  - Ocultar da tela inicial (o mesmo que ▲ no modo mover; o app continua instalado);
 *  - Desinstalar, só quando [podeDesinstalar] deixa.
 * Dentro da pasta "Ocultos" o menu troca "Mover/Ocultar" por "Mostrar na tela inicial".
 */
object MenuApp {
    /** Ver PainelApps.protegido: nunca ocultar nem desinstalar (sem eles a TV quebra ou o painel some). */
    val PROTEGIDOS_FIXOS = setOf(
        AppIdentity.NEW_PACKAGE, AppIdentity.LEGACY_PACKAGE, "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.apps.tv.launcherx", "com.google.android.gms", "com.google.android.gsf",
        "com.android.providers.settings", "com.android.shell",
    )

    /**
     * Congelar à mão qualquer pacote (Paulo, 20/11/2026: "às vezes a gente pode querer manualmente congelar"): o painel
     * deixa ocultar também app de sistema sem tela, menos os [PROTEGIDOS_FIXOS] (sem eles a TV não se conserta pela TV).
     * O freio passa a ser o PIN: só o que a ficha diz "seguro" oculta sem PIN; cuidado, essencial e sem informação pedem.
     */
    fun ocultarPedePin(risco: String) = risco != "seguro"

    enum class Acao { MOVER, OCULTAR, MOSTRAR, SOB_DEMANDA, TIRAR_SOB_DEMANDA, INSTALAR_EXTERNO, INSTALAR_DISPOSITIVO, DESINSTALAR }

    /**
     * [sobDemanda] (20/11/2026, OnDemand.kt): null = o app não pode ser congelado sob demanda (não aparece nada);
     * false = pode e ainda não é ("Congelar quando não estiver em uso"); true = já é ("Deixar de congelar").
     * [armazenamento] (20/11/2026, AppStorage.kt): null = não pode mudar de lugar; true = pode ir para o pen drive
     * adotado ("Instalar no armazenamento externo"); false = está nele e pode voltar ("Instalar no dispositivo").
     */
    fun acoes(naPastaOcultos: Boolean, desinstalavel: Boolean, sobDemanda: Boolean? = null, armazenamento: Boolean? = null): List<Acao> {
        val base = if (naPastaOcultos) listOf(Acao.MOSTRAR) else listOf(Acao.MOVER, Acao.OCULTAR)
        val demanda = when {
            naPastaOcultos || sobDemanda == null -> emptyList()
            sobDemanda -> listOf(Acao.TIRAR_SOB_DEMANDA)
            else -> listOf(Acao.SOB_DEMANDA)
        }
        val lugar = when (armazenamento) {
            null -> emptyList()
            true -> listOf(Acao.INSTALAR_EXTERNO)
            false -> listOf(Acao.INSTALAR_DISPOSITIVO)
        }
        return base + demanda + lugar + if (desinstalavel) listOf(Acao.DESINSTALAR) else emptyList()
    }

    /**
     * Desinstalar só app que o usuário instalou ([sistema] = false: app de fábrica do Chromecast não sai, o Android
     * recusa; para esses fica o "Ocultar") e que não está [protegido] (ver PainelApps.protegido). O próprio launcher é
     * barrado aqui também, mesmo que alguém o tire da lista de protegidos: ele carrega a HOME, o Device Owner (perder é
     * irreversível sem reset de fábrica), o ADB embutido e o serviço de acessibilidade.
     */
    fun podeDesinstalar(pacote: String, nosso: String, sistema: Boolean, protegido: Boolean): Boolean =
        pacote != nosso && !sistema && !protegido

    /**
     * Na pilha (grupo de cards), quais apps ganham a linha "Desinstalar <app>": os que [pode] deixa, com o da
     * [frente] (o que aparece no card) primeiro e os outros na ordem da pilha.
     */
    fun desinstalaveisDaPilha(pacotes: List<String>, frente: Int, pode: (String) -> Boolean): List<String> {
        val ordem = pacotes.getOrNull(frente)?.let { listOf(it) + (pacotes - it) } ?: pacotes
        return ordem.filter(pode)
    }
}

package br.paulo.tvlauncher.privilege

/**
 * Regras do empréstimo do Device Owner a outros apps (servidor do Dhizuku no launcher, 15/11/2026, "poder máximo").
 * Só lógica pura (sem Android), testada em RegrasDonoTest.
 *
 * Decisões do Paulo (15/11/2026): desligado de fábrica; cada app autorizado com o PIN na TV; o "poder total" é por app,
 * com PIN e aviso. Poder total = o que deixa o app rodar código DENTRO do uid do launcher (processo, serviço) ou agir
 * por fora do filtro (delegar escopos do DPM, chamar outros serviços do sistema como o launcher): com isso ele lê os
 * dados do launcher (hash do PIN, tokens do MCP, chave do Tailscale). Sem poder total, o app só chama o
 * DevicePolicyManager, e cada chamada passa pelo filtro [transacao]:
 *  - nunca: tirar o dono do launcher, apagar a TV, trocar de dono (lista [SEMPRE_BLOQUEADOS]). Isto vale para o que passa
 *    pelo filtro; com PODER TOTAL o app roda processo e serviço com o uid do launcher e pode chamar o sistema direto
 *    (inclusive apagar a TV): por isso o PIN do poder total diz isso com todas as letras;
 *  - nunca contra o próprio launcher: esconder, suspender, limpar dados, mexer nas permissões dele etc. ([MEXE_EM_PACOTE]);
 *  - se o nome do método ou os argumentos não puderem ser lidos nesta TV, a chamada é negada (falha segura).
 */
object RegrasDono {
    enum class Parado { NAO, DESLIGADO, PLANO, SEM_DONO }

    fun parado(ligado: Boolean, planoPermite: Boolean, somosDono: Boolean): Parado = when {
        !ligado -> Parado.DESLIGADO
        !planoPermite -> Parado.PLANO
        !somosDono -> Parado.SEM_DONO
        else -> Parado.NAO
    }

    /** Funções do Dhizuku que só o "poder total" usa (nomes que o DhizukuService passa). */
    val FUNCOES_PODER_TOTAL = setOf("remote_process", "bind_user_service", "unbind_user_service", "set_delegated_scopes")

    /** [func] é o nome que o DhizukuService passa (null = "tem permissão?"). */
    fun permite(func: String?, autorizado: Boolean, poderTotal: Boolean): Boolean = when {
        !autorizado -> false
        func in FUNCOES_PODER_TOTAL -> poderTotal
        else -> true
    }

    const val DPM = "android.app.admin.IDevicePolicyManager"

    /** Métodos do DevicePolicyManager que nenhum app emprestado chama: o launcher perderia o dono ou a TV seria apagada. */
    val SEMPRE_BLOQUEADOS = setOf(
        "wipeData", "wipeDataWithReason", "clearDeviceOwner", "clearProfileOwner", "transferOwnership",
        "removeActiveAdmin", "forceRemoveActiveAdmin", "setDeviceOwner", "setProfileOwner",
        "setFactoryResetProtectionPolicy", "setOrganizationOwnedProfile", "setActiveAdmin",
        // Depuração 17/11/2026: senha com apagamento (token + senha + "apagar após 1 erro" = a TV se apaga e perde o
        // dono) e usuários novos (criar e trocar tira a TV do usuário em que o launcher é a HOME).
        "setResetPasswordToken", "resetPasswordWithToken", "resetPassword", "setMaximumFailedPasswordsForWipe",
        "createAndManageUser", "switchUser",
    )

    /** Métodos do DPM que dão poder fora do filtro (o delegado chama o sistema direto): só com poder total. */
    val SO_PODER_TOTAL = setOf("setDelegatedScopes", "setDelegatedCertInstaller", "setCertInstallerPackage",
        "setDelegatedCertInstallerPackage", "setApplicationRestrictionsManagingPackage",
        // Depuração 17/11/2026: estes TROCAM a lista inteira (ou a VPN/HOME fixa). Olhar só se o launcher está na lista
        // nova não basta: uma lista sem ele apaga a proteção dele (botões travados, teclado e acessibilidade
        // permitidos, VPN fixa, HOME persistente). E a leitura dos alvos não separa sempre o pacote alvo do
        // callerPackage. Então só com poder total (falha segura: sem ele, a chamada é negada).
        "setUserControlDisabledPackages", "setPermittedInputMethods", "setPermittedAccessibilityServices",
        "setKeepUninstalledPackages", "setLockTaskPackages", "setMeteredDataDisabledPackages", "setAlwaysOnVpnPackage",
        "addPersistentPreferredActivity", "clearPackagePersistentPreferredActivities")

    /**
     * Configurações e restrições que derrubariam o shell próprio do launcher (depuração sem fio): com estes nomes nos
     * argumentos, a chamada é negada sem poder total.
     */
    val CHAVES_DO_SHELL = setOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled", "no_debugging_features")

    /** Métodos que mudam configuração ou restrição do sistema: os argumentos são lidos para achar [CHAVES_DO_SHELL]. */
    private val MEXE_EM_AJUSTE = Regex("^(setGlobalSetting|setSecureSetting|setSystemSetting|addUserRestriction.*|" +
        "setUserRestriction.*|clearUserRestriction.*)$")

    fun mexeEmAjuste(metodo: String) = MEXE_EM_AJUSTE.matches(metodo)

    /** Métodos que miram um pacote: negados quando o alvo é o launcher. */
    private val MEXE_EM_PACOTE = Regex(
        "^(setApplicationHidden.*|setPackagesSuspended.*|setUninstallBlocked|clearApplicationUserData|" +
            "setPermissionGrantState|setPermissionPolicy|setUserControlDisabledPackages|setMeteredDataDisabledPackages|" +
            "setLockTaskPackages|setAlwaysOnVpnPackage|setPermittedAccessibilityServices|setPermittedInputMethods|" +
            "setApplicationRestrictions|setKeepUninstalledPackages|setCrossProfilePackages|enableSystemApp.*|" +
            "installExistingPackage|setDelegatedScopes|setDefaultSmsApplication|setDefaultDialerApplication|" +
            "addPersistentPreferredActivity|clearPackagePersistentPreferredActivities|setPackagesProtected|" +
            "setApplicationExemptions|setPermittedCrossProfileNotificationListeners|setCertInstallerPackage|" +
            "setGlobalPrivateDns|setDelegatedCertInstaller.*)$")

    fun mexeEmPacote(metodo: String) = MEXE_EM_PACOTE.matches(metodo)

    enum class Veredito { PASSA, BLOQUEIA, BLOQUEIA_SEM_MAPA }

    /**
     * Uma transação para o binder de [descritor] pode passar? [metodo] = nome resolvido do código (null se esta TV não
     * tem o mapa), [alvos] = pacotes que a chamada mira (null se os argumentos não puderam ser lidos), [nosso] = pacote
     * do launcher.
     */
    fun transacao(descritor: String?, metodo: String?, alvos: List<String>?, nosso: String, poderTotal: Boolean,
                  textos: List<String>? = null, protegidos: Set<String> = emptySet()): Veredito {
        if (descritor != DPM) return if (poderTotal) Veredito.PASSA else Veredito.BLOQUEIA
        if (metodo == null) return Veredito.BLOQUEIA_SEM_MAPA
        if (metodo in SEMPRE_BLOQUEADOS) return Veredito.BLOQUEIA
        if (metodo in SO_PODER_TOTAL && !poderTotal) return Veredito.BLOQUEIA
        if (!poderTotal && mexeEmAjuste(metodo)) {
            if (textos == null) return Veredito.BLOQUEIA_SEM_MAPA
            if (textos.any { it in CHAVES_DO_SHELL }) return Veredito.BLOQUEIA
        }
        if (poderTotal || !mexeEmPacote(metodo)) return Veredito.PASSA
        if (alvos == null) return Veredito.BLOQUEIA_SEM_MAPA
        // Também os pacotes de que a TV depende (launcherx do Google, GMS, shell, Configurações): o painel já os
        // protege (PainelApps.protegido); um app só com "Permitir" podia escondê-los ou limpar os dados deles.
        return if (nosso in alvos || alvos.any { it in protegidos }) Veredito.BLOQUEIA else Veredito.PASSA
    }

    /** Um argumento lido do Parcel de uma chamada AIDL, só no que importa para achar o pacote alvo. */
    sealed interface Arg {
        data class Texto(val v: String?) : Arg
        data class Textos(val v: List<String?>) : Arg
        data class Componente(val pacote: String?) : Arg
        object Outro : Arg
    }

    /**
     * Pacotes que a chamada mira. No DPM moderno a assinatura é (ComponentName admin, String callerPackage, ...alvos):
     * o admin (1º) e o callerPackage (2º, quando há outro texto depois) são do próprio launcher e não contam. Sem outro
     * texto, o 2º É o alvo (ex.: clearApplicationUserData(admin, packageName, callback)).
     */
    /** Todos os textos dos argumentos (nomes de configuração/restrição), sem a regra do callerPackage de [alvos]. */
    fun textos(args: List<Arg>): List<String> = args.flatMap { a ->
        when (a) { is Arg.Texto -> listOfNotNull(a.v); is Arg.Textos -> a.v.filterNotNull(); else -> emptyList() }
    }

    fun alvos(args: List<Arg>): List<String> {
        val temAdmin = args.firstOrNull() is Arg.Componente
        val textos = args.count { it is Arg.Texto || it is Arg.Textos }
        val ignorar = if (temAdmin && args.getOrNull(1) is Arg.Texto && textos >= 2) 1 else -1
        val r = mutableListOf<String>()
        args.forEachIndexed { i, a ->
            when {
                i == ignorar -> {}
                a is Arg.Texto -> a.v?.let { r += it }
                a is Arg.Textos -> a.v.filterNotNull().forEach { r += it }
                a is Arg.Componente && !(i == 0 && temAdmin) -> a.pacote?.let { r += it }
                else -> {}
            }
        }
        return r
    }
}

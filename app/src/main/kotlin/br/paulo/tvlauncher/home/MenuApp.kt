package br.paulo.tvlauncher.home

import br.paulo.tvlauncher.AppIdentity

/**
 * As regras do menu de um app (05/11/2026), fora da MainActivity para dar para testar sem TV: quem não pode ser
 * desinstalado nem oculto ([PROTEGIDOS_FIXOS], [podeDesinstalar]) e quando ocultar pede o PIN ([ocultarPedePin]).
 * O menu em si é montado pela cruz (MainActivity.menuAppCruz), pela tela "Todos os apps" e pelo painel web.
 * 21/11/2026: a lista de ações da HOME antiga (mover, pasta Ocultos, pilhas) saiu com ela.
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

    /**
     * Desinstalar só app que o usuário instalou ([sistema] = false: app de fábrica do Chromecast não sai, o Android
     * recusa; para esses fica o "Ocultar") e que não está [protegido] (ver PainelApps.protegido). O próprio launcher é
     * barrado aqui também, mesmo que alguém o tire da lista de protegidos: ele carrega a HOME, o Device Owner (perder é
     * irreversível sem reset de fábrica), o ADB embutido e o serviço de acessibilidade.
     */
    fun podeDesinstalar(pacote: String, nosso: String, sistema: Boolean, protegido: Boolean): Boolean =
        pacote != nosso && !sistema && !protegido
}

package br.paulo.tvlauncher

/**
 * Os nomes do app (19/11/2026, decisão do Paulo: nenhum pacote público com o nome dele).
 *
 * - [NAMESPACE]: o pacote do CÓDIGO (classes, R, BuildConfig). Nunca muda: é o que aparece em nomes de classe como
 *   "br.paulo.tvlauncher.MainActivity", em qualquer instalação.
 * - [NEW_PACKAGE]: o applicationId do produto (Sofaly), o mesmo no APK do site e no da Play.
 * - [LEGACY_PACKAGE]: o applicationId antigo. Só existe no build `-Plegacy` (a "ponte"): a versão que as TVs antigas
 *   instalam e que passa o Device Owner e os ajustes para o app novo (PackageMigration.kt).
 *
 * Regra: nome de CLASSE usa [NAMESPACE]; nome de PACOTE usa `context.packageName` (ou [ours] para saber se um pacote
 * qualquer é um dos nossos). Antes as duas coisas eram a mesma string e muito código misturava.
 */
object AppIdentity {
    const val NAMESPACE = "br.paulo.tvlauncher"
    const val NEW_PACKAGE = "tv.sofaly.launcher"
    const val LEGACY_PACKAGE = "br.paulo.tvlauncher"

    /** Os dois pacotes que são este launcher (o atual e o da migração). */
    val PACKAGES = setOf(NEW_PACKAGE, LEGACY_PACKAGE)

    /** true se [pacote] é este launcher, em qualquer um dos dois nomes. */
    fun ours(pacote: String?): Boolean = pacote != null && pacote in PACKAGES

    /** "PlayerActivity" -> "br.paulo.tvlauncher.PlayerActivity" (classe do código, não importa o pacote instalado). */
    fun className(curto: String): String = "$NAMESPACE.$curto"
}

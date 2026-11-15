package br.paulo.tvlauncher

import rikka.shizuku.ShizukuProvider

/**
 * Provider por onde o servidor do Shizuku embutido (processo `shizuku_server`, uid 2000) entrega o binder dele ao
 * launcher, o "gerente". O servidor chama `content://<pacote>.shizuku` com o método "sendBinder"; a classe-mãe (da
 * API do Shizuku) guarda o binder e a partir daí `Shizuku.pingBinder()` dá true no launcher.
 * Só o sistema e o shell chamam (android:permission INTERACT_ACROSS_USERS_FULL no manifesto).
 */
class ProvedorShizuku : ShizukuProvider() {
    override fun onCreate(): Boolean {
        // Sui é uma variante do Shizuku que roda com root (Magisk). Não existe aqui; desligar evita procurar por ela.
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}

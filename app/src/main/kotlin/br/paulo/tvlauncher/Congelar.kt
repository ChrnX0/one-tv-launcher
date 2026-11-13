package br.paulo.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Congelar um app pelo SHELL PRÓPRIO, para quando o launcher não é Device Owner (12/11/2026, pedido do dono: "veja como o
 * Canta faz e copia").
 *
 * O que o Canta faz (li o código em https://github.com/samolego/Canta, PackageOperations.kt): DESINSTALA o app só para o
 * usuário atual, pelo PackageInstaller rodando como shell (o mesmo que `pm uninstall --user 0`), e "reinstala" com
 * installExistingPackage (o mesmo que `cmd package install-existing`). O APK de fábrica fica no aparelho, mas os DADOS do
 * app são apagados, porque ele não passa a opção de guardar dados.
 *
 * Aqui foi escolhido outro comando do mesmo shell: `pm disable-user` (desativar). O app some da tela, não abre e não roda,
 * igual ao ocultar do Device Owner, mas GUARDA os dados e volta com `pm enable` exatamente como estava. É o "congelar"
 * de verdade; o do Canta é uma limpeza. Não verificado na TV: algum app que o Android proteja pode recusar o desativar
 * (aí [definir] devolve false e nada muda).
 *
 * Não substitui o "travar parar/limpar" do Device Owner (setUserControlDisabledPackages): isso não tem comando de shell.
 */
object Congelar {
    private const val TAG = "TvLauncher.Congelar"

    // Nome de pacote Android: letras, números e "_" separados por pontos, com pelo menos um ponto. O nome entra num
    // comando de shell, então só passa o que tem esse formato (nada de espaço, ";" ou aspas que mudariam o comando).
    private val NOME_PACOTE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun nomeValido(pacote: String): Boolean = NOME_PACOTE.matches(pacote)

    /** O comando do shell. [usuario] = o usuário Android do launcher (0 na TV). Puro: testado no PC (CongelarTest). */
    fun comando(pacote: String, congelar: Boolean, usuario: Int): String? {
        if (!nomeValido(pacote)) return null
        return if (congelar) "pm disable-user --user $usuario $pacote" else "pm enable --user $usuario $pacote"
    }

    /** O app está desativado pelo usuário (o estado que `pm disable-user` deixa)? Leitura local, rápida. */
    fun congelado(ctx: Context, pacote: String): Boolean = try {
        ctx.packageManager.getApplicationEnabledSetting(pacote) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (e: Exception) { false }   // pacote que não existe: não está congelado

    /**
     * Congela (true) ou descongela (false). Roda um comando no shell próprio, então BLOQUEIA até ~20 s: nunca chamar na
     * thread da tela. Devolve true se o estado ficou como pedido. Recusa o próprio launcher.
     */
    fun definir(ctx: Context, pacote: String, congelar: Boolean): Boolean {
        if (pacote == ctx.packageName) return false
        // O usuário Android vem do uid do processo: uid = usuário × 100000 + número do app.
        val cmd = comando(pacote, congelar, android.os.Process.myUid() / 100_000) ?: return false
        if (congelado(ctx, pacote) == congelar) return true   // já está como pedido
        val saida = GuardiaoShizuku.comando(ctx, cmd)
        val ok = congelado(ctx, pacote) == congelar
        Log.i(TAG, "$cmd => ok=$ok saída=${saida?.trim()?.take(200)}")
        return ok
    }
}

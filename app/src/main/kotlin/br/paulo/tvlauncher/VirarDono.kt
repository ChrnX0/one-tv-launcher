package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * Virar Device Owner pela própria TV, sem PC e sem apagar a conta Google (13/11/2026, ideia do dono: "o jeito do Hail").
 *
 * Por que funciona (li o código do Android 14, DevicePolicyManagerService.hasIncompatibleAccountsOrNonAdbNoLock): para
 * recusar o `dpm set-device-owner` por "já há contas", o Android pergunta ao AccountManager quais contas existem. A conta
 * Google só aparece se o app que a cuida (o autenticador) estiver ativo; nesta TV o único autenticador do tipo com.google é
 * o com.google.android.gms (conferido em 13/11/2026 com dumpsys account, só leitura). Com ele DESATIVADO por alguns
 * segundos, a lista vem vazia e o comando passa. Depois ele é reativado e a conta volta como estava. É o mesmo truque que o
 * Hail e o Dhizuku ensinam no celular.
 *
 * Diferente do reset de 10/11/2026: lá o Google foi DESINSTALADO para o usuário, o que apagou a conta da TV. Desativar não
 * apaga dados.
 *
 * O outro bloqueio é "já há vários usuários": usuários além do 0 (perfis vazios que o Google TV cria) precisam sair antes.
 * Remover um usuário APAGA os dados dele; por isso a tela pede confirmação mostrando quais são.
 *
 * Quem roda tudo é o shell próprio (uid 2000): o Android só aceita o set-device-owner vindo do adb (isAdb = uid do shell).
 *
 * Segurança: se o launcher morrer com o Google desativado, a marca [MARCA] fica gravada e a autocura reativa o Google
 * ([recuperar]). NÃO TESTADO NA TV: a Sala já é dona; o 1º teste será numa TV nova, combinado com o dono.
 */
object VirarDono {
    private const val TAG = "TvLauncher.VirarDono"
    const val GOOGLE = "com.google.android.gms"
    private const val MARCA = "virar_dono_google_desativado"

    class Resultado(val ok: Boolean, val texto: String)

    /** Os usuários além do 0 na saída de `pm list users` ("UserInfo{11:new_user:1010}"). Puro: VirarDonoTest. */
    fun usuariosExtras(saida: String): List<Int> =
        Regex("UserInfo\\{(\\d+):").findAll(saida).map { it.groupValues[1].toInt() }.filter { it != 0 }.distinct().toList()

    /** O `dpm set-device-owner` deu certo? Ele responde "Success: Device owner set to package ...". */
    fun deuCerto(saida: String?): Boolean = saida?.contains("Success") == true

    /** Os usuários extras agora (para a tela de confirmação). null = sem shell. */
    fun usuariosAgora(ctx: Context): List<Int>? = GuardiaoShizuku.comando(ctx, "pm list users")?.let { usuariosExtras(it) }

    /**
     * A pergunta na tela (Configurações e boas-vindas): mostra quais usuários extras sairiam e, com OK, roda [executar].
     * [aviso] mostra textos curtos; [depois] roda na thread da tela quando termina.
     */
    fun perguntar(act: android.app.Activity, aviso: (String) -> Unit, depois: (Resultado) -> Unit) {
        val app = act.applicationContext
        Fundo.rodar {
            val extras = usuariosAgora(app)
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                if (extras == null) { aviso(act.getString(R.string.dono_sem_shell)); return@runOnUiThread }
                val texto = act.getString(R.string.dono_texto) +
                    if (extras.isEmpty()) "" else "\n\n" + act.getString(R.string.dono_usuarios, extras.joinToString())
                CaixaTv(act).setTitle(R.string.dono_titulo).setMessage(texto)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.dono_ok) { _, _ ->
                        aviso(act.getString(R.string.dono_andando))
                        Fundo.rodar {
                            val r = executar(app)
                            act.runOnUiThread { if (!act.isDestroyed) { aviso(r.texto); depois(r) } }
                        }
                    }.show()
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Chamado pela autocura: se uma tentativa morreu no meio e deixou o Google desativado, reativa. */
    fun recuperar(ctx: Context) {
        if (!prefs(ctx).getBoolean(MARCA, false)) return
        if (Congelar.definir(ctx, GOOGLE, false)) prefs(ctx).edit().putBoolean(MARCA, false).apply()
    }

    /** A sequência inteira. BLOQUEIA (até ~1 min): nunca na thread da tela. */
    fun executar(ctx: Context): Resultado {
        val app = ctx.applicationContext
        if (PrivilegioDO.somosDonos(app)) return Resultado(true, "o launcher já é o dono do aparelho")
        val extras = usuariosAgora(app) ?: return Resultado(false, "o shell próprio não respondeu (pareie em Configurações > Shell do launcher)")

        // 1) Usuários extras saem (a tela já mostrou quais e a pessoa confirmou).
        for (u in extras) Log.i(TAG, "pm remove-user $u => ${GuardiaoShizuku.comando(app, "pm remove-user $u")?.trim()}")
        val sobraram = usuariosAgora(app).orEmpty()
        if (sobraram.isNotEmpty()) return Resultado(false, "o Android não deixou remover o usuário ${sobraram.joinToString()}")

        // 2) Google desativado só durante o comando; o finally reativa aconteça o que acontecer.
        val desativei = !Congelar.congelado(app, GOOGLE)
        var saida: String? = null
        try {
            if (desativei) {
                prefs(app).edit().putBoolean(MARCA, true).commit()   // commit: grava já, antes de mexer no Google
                if (!Congelar.definir(app, GOOGLE, true)) return Resultado(false, "o Android não deixou desativar o Google por um instante")
                Thread.sleep(3_000)   // o AccountManager leva um momento para esquecer o autenticador
            }
            val admin = "${app.packageName}/${AdminReceiver::class.java.name}"
            saida = GuardiaoShizuku.comando(app, "dpm set-device-owner --user 0 $admin", 30_000)
            Log.i(TAG, "set-device-owner => ${saida?.trim()}")
        } finally {
            if (desativei) {
                // Até 3 tentativas: o Google precisa voltar (voz, Play Store, contas).
                repeat(3) { if (Congelar.congelado(app, GOOGLE)) Congelar.definir(app, GOOGLE, false) }
                if (!Congelar.congelado(app, GOOGLE)) prefs(app).edit().putBoolean(MARCA, false).apply()
            }
        }

        if (!deuCerto(saida) || !PrivilegioDO.somosDonos(app))
            return Resultado(false, "o Android recusou: " + (saida?.trim()?.lineSequence()?.lastOrNull()?.take(160) ?: "sem resposta"))
        PrivilegioDO.aplicar(app)   // HOME fixa, permissões fixas, travas: o que o dono faz ao nascer
        return Resultado(true, "pronto: o launcher é o dono do aparelho" +
            if (Congelar.congelado(app, GOOGLE)) " (o Google ainda está voltando; a autocura termina em até 10 min)" else "")
    }
}

package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Roda quando a TV termina de REINICIAR: religa a Depuração sem fio (Privilegio.religarDepuracaoSemFio) e abre o app
 * definido em Config.ABRIR_AO_LIGAR, se houver.
 * Não dispara quando o Chromecast só acorda do standby: nesse caso o Android não reinicia.
 *
 * ACAO_TESTE existe só para testar por adb sem reiniciar a TV (o shell não pode enviar BOOT_COMPLETED):
 *   adb shell am broadcast -n br.paulo.tvlauncher/.BootReceiver -a br.paulo.tvlauncher.TESTE_BOOT
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        const val ACAO_TESTE = "br.paulo.tvlauncher.TESTE_BOOT"
        private const val TAG = "TvLauncher.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACAO_TESTE) return
        Log.i(TAG, "recebido: ${intent.action}")   // prova no log de que o boot chegou (o receiver exige DUMP desde 04/11)
        Privilegio.religarDepuracaoSemFio(context)
        Privilegio.vigiarDepuracaoSemFio(context)   // e continua de olho: o Android pode desligá-la logo depois, no boot
        Rotinas.agendar(context)   // idem para as rotinas por horário (Rotinas.kt)
        Despertador.agendar(context)   // o Android esquece os alarmes agendados quando reinicia
        // ...e o agendar também confere se algum alarme passou da hora com a TV desligada: toca já (até 15 min) ou
        // mostra o aviso "não tocou porque a TV estava desligada" (AlarmeGarantia.conferir).
        Timer.reagendar(context)   // idem para o timer da cozinha (Timer.kt)
        LembretesTv.agendar(context)   // lembretes da grade (12/11/2026)
        ManutencaoNoturna.agendar(context)   // idem para a manutenção de madrugada (ManutencaoNoturna.kt)
        // Shell próprio: reconecta sozinho (espera o Wi-Fi e a depuração sem fio) se faltar algo, e fica vigiando.
        GuardiaoShizuku.vigiar(context)
        GuardiaoShizuku.garantir(context, "boot")
        McpServidor.ajustar(context); TunelTailscale.ajustar(context)   // MCP pela internet (se ligado)
        PainelWeb.ajustar(context)   // painel no navegador (se ligado): já de pé antes de alguém abrir a HOME
        BackupAutomatico.talvezGravar(context)   // cópia diária das configurações (ver BackupAutomatico.kt)
        BackupNoturno.agendar(context)   // o Android esquece os alarmes no boot (backup noturno cifrado, se ligado)
        HistoricoRede.iniciar(context)   // histórico de rede: mede desde o boot
        AutoDesligar.iniciar(context)
        val app = context.applicationContext
        Fundo.rodar { DebloatPosOta.talvezReaplicar(app) }   // a OTA desfez a limpeza? oculta de novo (DebloatPosOta.kt)
        if (Config.ABRIR_AO_LIGAR.isEmpty()) return
        val abrir = context.packageManager.getLeanbackLaunchIntentForPackage(Config.ABRIR_AO_LIGAR)
        if (abrir == null) {
            Log.w(TAG, "app ${Config.ABRIR_AO_LIGAR} não encontrado")
            return
        }
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.i(TAG, "abrindo ${Config.ABRIR_AO_LIGAR}")
        context.startActivity(abrir)
    }
}

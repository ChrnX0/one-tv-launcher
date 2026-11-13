package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * O relógio ou o fuso da TV mudou (auditoria de 10/11/2026). Os alarmes e as rotinas ficam agendados num instante
 * fixo, calculado com o fuso de ANTES: se o fuso muda, "07:00" passaria a tocar em outra hora. Aqui só reagenda
 * (o mesmo que o BootReceiver faz), sem subir o resto (Shizuku, túnel, painel).
 *
 * Ações no manifesto: android.intent.action.TIME_SET e android.intent.action.TIMEZONE_CHANGED (exported=false basta:
 * são avisos do próprio sistema, que podem chegar a receiver declarado no manifesto).
 */
class HoraMudouReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        Log.i("TvLauncher.Hora", "relógio ou fuso mudou (${intent.action}): reagendando")
        val app = context.applicationContext
        Despertador.agendar(app)   // também confere o alarme perdido: o Chromecast liga com o relógio velho e só acerta
                                   // a hora pela internet depois; é aqui que se descobre que as 6:30 já passaram
        Rotinas.agendar(app)
        Timer.reagendar(app)   // o fim do timer não muda; só arma de novo (ou limpa se já passou)
        LembretesTv.agendar(app)   // lembretes da grade (12/11/2026)
        ManutencaoNoturna.agendar(app)   // "4h" no fuso novo
        // Hora de dormir: confere a trava do relógio e, se o pulo caiu dentro do horário, fecha já o app proibido.
        TravaRelogio.aoMudarHora(app)
        Fundo.rodar { BackupNoturno.agendar(app) }   // "3:30" no fuso novo (lê as prefs do disco: fora da thread da tela)
    }
}

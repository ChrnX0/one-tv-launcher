package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import java.util.Calendar

/**
 * Hora de dormir das crianças (10/11/2026, 2ª leva das ideias): com a chave [Extras.Chave.HORA_DORMIR] ligada, no
 * horário escolhido (padrão 21:00 às 06:30) só os apps da lista abrem (e os essenciais, ver [HoraDormir.ESSENCIAIS]) e o
 * volume não passa do teto. Quem chama: o serviço de acessibilidade (janela nova e tecla). Desligar pede o PIN do painel.
 */
object HoraDormirTv {
    @Volatile private var ultimoAviso = 0L

    private fun agoraMin(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun diaHoje(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1   // 0 = domingo

    /** Os 7 dias (null = segue o padrão de [inicio]/[fim]). */
    fun dias(ctx: Context) = HoraDormir.lerDias(Extras.texto(ctx, HoraDormir.CHAVE_DIAS))
    fun definirDias(ctx: Context, dias: List<HoraDormir.Janela?>) = Extras.definirTexto(ctx, HoraDormir.CHAVE_DIAS, HoraDormir.escreverDias(dias))
    private fun padrao(ctx: Context) = HoraDormir.Janela(inicio(ctx), fim(ctx))

    /** Dentro do horário agora (sem olhar a chave), já com o horário de cada dia. */
    fun noHorario(ctx: Context): Boolean = HoraDormir.dentroNoDia(diaHoje(), agoraMin(), dias(ctx), padrao(ctx))

    fun inicio(ctx: Context) = Extras.numero(ctx, HoraDormir.CHAVE_INICIO, HoraDormir.PADRAO_INICIO)
    fun fim(ctx: Context) = Extras.numero(ctx, HoraDormir.CHAVE_FIM, HoraDormir.PADRAO_FIM)
    fun teto(ctx: Context) = Extras.numero(ctx, HoraDormir.CHAVE_TETO, HoraDormir.PADRAO_TETO)
    fun permitidos(ctx: Context) = HoraDormir.lerPermitidos(Extras.texto(ctx, HoraDormir.CHAVE_PERMITIDOS))

    /** Agora é hora de dormir (chave ligada e dentro do horário)? */
    fun valendo(ctx: Context): Boolean =
        Extras.ligado(ctx, Extras.Chave.HORA_DORMIR) && noHorario(ctx)

    /** Uma janela de [pacote] apareceu: se não pode agora, volta para a HOME e avisa (no máximo 1 aviso a cada 10 s). */
    fun janela(servico: AccessibilityService, pacote: CharSequence?) {
        val p = pacote?.toString() ?: return
        if (!valendo(servico) || p in HoraDormir.ESSENCIAIS || p in permitidos(servico)) return
        // O teclado escolhido na TV (pode não ser o do Google): a janela dele aparece com o pacote dele, por cima do app.
        if (p == tecladoAtual(servico)) return
        servico.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoAviso > 10_000) {
            ultimoAviso = agora
            AvisoTv.mostrar(servico, servico.getString(R.string.dormir_titulo), servico.getString(R.string.dormir_texto), 8,
                som = false, guardarSeApagada = false)
        }
    }

    /** Pacote do teclado em uso ("pacote/classe" nas configurações; fica só a parte antes da "/"). */
    private fun tecladoAtual(ctx: Context): String? = try {
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    } catch (e: Exception) { null }

    /** VOLUME + na hora de dormir: engole a tecla se passaria do teto (true = tecla usada). */
    fun tecla(ctx: Context, e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_UP || !valendo(ctx)) return false
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val atual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (!HoraDormir.passaDoTeto(atual, max, teto(ctx))) return false
        // Se já passou do teto (alguém subiu antes do horário), desce até ele.
        val limite = max * teto(ctx).coerceIn(0, 100) / 100
        if (e.action == KeyEvent.ACTION_DOWN && atual > limite) am.setStreamVolume(AudioManager.STREAM_MUSIC, limite, 0)
        return true
    }
}

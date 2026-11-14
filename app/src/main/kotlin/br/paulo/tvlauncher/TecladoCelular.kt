package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * Ideia 57 (14/11/2026): teclado do celular chamado pela TV. Quando um campo de texto ganha o foco na TV (busca do
 * YouTube, login de um app), o celular inscrito nos avisos recebe "Digitar na TV"; o toque abre o painel no Controle,
 * onde o texto vai com acento (ideia 56).
 *
 * Só escuta o foco de campo (TYPE_VIEW_FOCUSED) com o aviso "Campo de texto aberto na TV" ligado e algum celular
 * inscrito: o XML do serviço continua só com a troca de janela, e o tipo extra entra em tempo de execução
 * (serviceInfo), para a TV não pagar um evento por foco à toa. Regra de espera em TextoNaTv.deveAvisarCelular (pura, testada).
 */
object TecladoCelular {
    @Volatile private var ultimo = 0L
    @Volatile private var ultimoPacote = ""

    fun ativo(ctx: Context): Boolean =
        Avisos.ligado(ctx, Avisos.Tipo.DIGITAR) && Avisos.inscricoes(ctx).isNotEmpty()

    /** Liga ou desliga o evento de foco no serviço de acessibilidade, conforme [ativo]. */
    fun ajustar(s: AutorizaShizukuService) {
        runCatching {
            val info: AccessibilityServiceInfo = s.serviceInfo ?: return
            val tipos = if (ativo(s)) info.eventTypes or AccessibilityEvent.TYPE_VIEW_FOCUSED
                        else info.eventTypes and AccessibilityEvent.TYPE_VIEW_FOCUSED.inv()
            if (tipos != info.eventTypes) { info.eventTypes = tipos; s.serviceInfo = info }
        }
    }

    fun foco(ctx: Context, e: AccessibilityEvent) {
        val pacote = e.packageName?.toString().orEmpty()
        val editavel = runCatching { e.source?.isEditable == true }.getOrDefault(false) || e.className?.toString()?.endsWith("EditText") == true
        val agora = System.currentTimeMillis()
        if (!TextoNaTv.deveAvisarCelular(editavel, pacote, ultimoPacote, ultimo, agora)) return
        ultimo = agora; ultimoPacote = pacote
        val nome = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pacote, 0)).toString() }.getOrDefault(pacote)
        Avisos.enviar(ctx, Avisos.Tipo.DIGITAR, ctx.getString(R.string.l51_digitar_titulo), ctx.getString(R.string.l51_digitar_texto, nome))
    }
}

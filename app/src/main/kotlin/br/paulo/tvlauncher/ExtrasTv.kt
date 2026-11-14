package br.paulo.tvlauncher

import android.content.Context

/**
 * Ligar ou desligar uma chave de [Extras] pela TV, com o efeito colateral de cada uma. Um ponto só, usado pela tela
 * Extras e pelas abas das Configurações (ideia 46); antes isso ficava dentro da ExtrasActivity.
 */
object ExtrasTv {
    fun alternar(ctx: Context, c: Extras.Chave) {
        val app = ctx.applicationContext
        Extras.definir(app, c, !Extras.ligado(app, c))
        when (c) {
            Extras.Chave.FILTRO_NOTURNO -> FiltroNoturno.avaliar()
            Extras.Chave.JANELA_OTA, Extras.Chave.TRAVAR_CRITICOS, Extras.Chave.DIARIO_DNS ->
                Fundo.rodar { ExtrasDO.aplicar(app) }   // chamadas do DO fora da thread da tela
            else -> PainelExtras.aoMudar(app, c)
        }
    }

    /** O rótulo de cada chave vem de "extras_<NOME>" nas strings (pt e en). */
    fun rotulo(ctx: Context, c: Extras.Chave): String {
        val id = ctx.resources.getIdentifier("extras_${c.name}", "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else c.name
    }
}

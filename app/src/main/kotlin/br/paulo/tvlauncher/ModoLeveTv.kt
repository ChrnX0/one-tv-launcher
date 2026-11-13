package br.paulo.tvlauncher

import android.content.Context
import android.provider.Settings

/**
 * Modo leve (10/11/2026, 2ª leva das ideias; li no TV Remote, "Optimize TV" com "Undo"): as três escalas de animação do
 * Android vão para 0,5x, e o que havia antes fica guardado para o "desfazer". Escreve em Settings.Global com a
 * WRITE_SECURE_SETTINGS que o launcher já tem. Não pausa apps: isso mexeria no que a família usa.
 */
object ModoLeveTv {
    fun ligar(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        // Guarda o "antes" só na 1ª vez: ligar duas vezes não pode gravar 0,5 como o valor original.
        if (Extras.texto(ctx, ModoLeve.CHAVE_ANTES).isEmpty()) {
            Extras.definirTexto(ctx, ModoLeve.CHAVE_ANTES, ModoLeve.guardar(ModoLeve.CHAVES.associateWith { k ->
                try { Settings.Global.getFloat(cr, k) } catch (e: Settings.SettingNotFoundException) { null }
            }))
        }
        ModoLeve.CHAVES.forEach { Settings.Global.putFloat(cr, it, ModoLeve.VALOR) }
        true
    } catch (e: SecurityException) { false }

    fun desfazer(ctx: Context): Boolean = try {
        // Nada guardado = nada a desfazer: não mexe nas animações (ver ModoLeve.paraVoltar).
        val antes = ModoLeve.paraVoltar(Extras.texto(ctx, ModoLeve.CHAVE_ANTES)) ?: return true
        antes.forEach { (k, v) -> Settings.Global.putFloat(ctx.contentResolver, k, v) }
        Extras.definirTexto(ctx, ModoLeve.CHAVE_ANTES, "")
        true
    } catch (e: SecurityException) { false }

    /** Chamado quando a chave muda (tela Extras, painel, adb). */
    fun aplicar(ctx: Context): Boolean =
        if (Extras.ligado(ctx, Extras.Chave.MODO_LEVE)) ligar(ctx) else desfazer(ctx)
}

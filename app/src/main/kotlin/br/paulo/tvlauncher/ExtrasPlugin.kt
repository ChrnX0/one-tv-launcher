package br.paulo.tvlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import br.paulo.tvlauncher.family.CuidarDeLonge

/**
 * Plugin "Sofaly Extras" (16/11/2026, decisão da venda nº 4): um APP SEPARADO (módulo :extras, pacote [PACOTE]), grátis,
 * baixado no site por escolha da pessoa. O app da Play não aponta para ele. Ele traz de volta o que a Play não aceita
 * DENTRO do app: hoje, o aviso automático "a TV não foi ligada hoje" (ideia 79). O plugin não tem acessibilidade nem
 * leitor de notificações (o Play Protect barra APK de fora com isso).
 *
 * Confiança: o plugin também é um plugin de cartão (PluginCartao), então só vale depois do OK do dono com PIN em
 * Configurações › Tela inicial › Plugins, preso ao certificado daquele momento ([autorizado]). A conversa é pelo
 * [ExtrasProvider] (ContentProvider.call): o plugin pergunta o estado e pede a conferência; quem decide e manda o aviso
 * continua sendo o launcher, com a mesma regra e o mesmo consentimento da pessoa, dado na TV.
 */
object ExtrasPlugin {
    const val PACOTE = "tv.sofaly.extras"
    const val METODO_ESTADO = "estado"
    const val METODO_NAO_LIGOU = "conferir_nao_ligou"

    /** O plugin está instalado e o dono deu o OK (pacote + certificado). */
    fun autorizado(ctx: Context): Boolean = runCatching { PluginsTv.permitido(ctx, PACOTE) }.getOrDefault(false)
}

/** A porta do plugin Extras no launcher. Exportado, mas só responde ao [ExtrasPlugin.PACOTE] autorizado pelo dono. */
class ExtrasProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val quem = callingPackage
        if (quem != ExtrasPlugin.PACOTE || !ExtrasPlugin.autorizado(ctx)) {
            Log.w("TvLauncher.Extras", "chamada recusada de $quem (uid ${Binder.getCallingUid()})")
            return Bundle().apply { putBoolean("autorizado", false) }
        }
        val id = Binder.clearCallingIdentity()
        try {
            return when (method) {
                ExtrasPlugin.METODO_ESTADO -> {
                    val c = CuidarDeLongeTv.config(ctx)
                    Bundle().apply {
                        putBoolean("autorizado", true)
                        putBoolean("nao_ligou_ativo", CuidarDeLongeTv.ativo(ctx, CuidarDeLonge.Aviso.NAO_LIGOU))
                        putString("nome", c.nome)
                    }
                }
                // Roda fora do binder (rede): o plugin não espera o envio, só sabe se foi aceito o pedido.
                ExtrasPlugin.METODO_NAO_LIGOU -> Bundle().apply {
                    putBoolean("autorizado", true)
                    putBoolean("aceito", CuidarDeLongeTv.ativo(ctx, CuidarDeLonge.Aviso.NAO_LIGOU))
                    Fundo.rodar("extras-nao-ligou") { runCatching { CuidarDeLongeTv.rodarNaoLigou(ctx) } }
                }
                else -> null
            }
        } finally { Binder.restoreCallingIdentity(id) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

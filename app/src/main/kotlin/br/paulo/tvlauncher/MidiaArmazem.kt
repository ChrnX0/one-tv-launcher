package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Onde ficam as contas de mídia da rede (SMB e Jellyfin): files/player/midia.json, CIFRADO com a mesma chave do
 * Android Keystore das contas de TV (PlayerArmazem). Senha e token nunca vão para o log, o Intent ou o endereço do vídeo.
 * Fica fora do backup (como as contas de TV).
 */
object MidiaArmazem {
    private const val TAG = "TvLauncher.Midia"
    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), "midia.json")

    fun contas(ctx: Context): List<ContaMidia> {
        val f = arquivo(ctx)
        if (!f.exists()) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val claro = runCatching { PlayerArmazem.decifrar(o.optString("dados"), o.optString("iv")) }
            .onFailure { Log.w(TAG, "contas de mídia ilegíveis: ${it.javaClass.simpleName}") }.getOrNull()
        return MidiaContas.deJson(claro)
    }

    fun conta(ctx: Context, id: String): ContaMidia? = contas(ctx).firstOrNull { it.id == id }

    @Synchronized
    private fun gravar(ctx: Context, contas: List<ContaMidia>): Boolean = runCatching {
        val (dados, iv) = PlayerArmazem.cifrar(MidiaContas.paraJson(contas))
        val tmp = File(PlayerArmazem.pasta(ctx), "midia.json.tmp")
        tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
        tmp.renameTo(arquivo(ctx))
    }.onFailure { Log.w(TAG, "não gravou as contas de mídia: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Acrescenta (id novo) ou troca (mesmo id) uma conta. */
    fun salvar(ctx: Context, c: ContaMidia): ContaMidia? {
        val nova = if (c.id.isEmpty()) c.copy(id = UUID.randomUUID().toString().take(8)) else c
        val atuais = contas(ctx).filter { it.id != nova.id }
        if (atuais.size >= MidiaContas.MAX) return null
        return if (gravar(ctx, atuais + nova)) nova else null
    }

    fun apagar(ctx: Context, id: String): Boolean = gravar(ctx, contas(ctx).filter { it.id != id })
}

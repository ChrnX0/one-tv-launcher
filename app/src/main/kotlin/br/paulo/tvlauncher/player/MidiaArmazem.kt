package br.paulo.tvlauncher.player

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

    fun contas(ctx: Context): List<ContaMidia> = runCatching { lerOuFalhar(ctx) }.getOrDefault(emptyList())

    /**
     * Como [contas], mas lança se o arquivo existe e não deu para ler/decifrar (soluço do Keystore). Quem vai GRAVAR usa
     * esta: antes, uma renovação do Box num soluço gravava só a conta dela e apagava SMB, Plex, Jellyfin e as outras nuvens.
     */
    private fun lerOuFalhar(ctx: Context): List<ContaMidia> {
        val f = arquivo(ctx)
        if (!f.exists()) return emptyList()
        val o = JSONObject(f.readText())
        val claro = try { PlayerArmazem.decifrar(o.optString("dados"), o.optString("iv")) }
            catch (e: Exception) { Log.w(TAG, "contas de mídia ilegíveis: ${e.javaClass.simpleName}"); throw e }
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

    /**
     * Acrescenta (id novo) ou troca (mesmo id) uma conta. Ler e gravar sob a mesma trava: a tela editando uma conta e o
     * Box trocando o token ao mesmo tempo apagavam a escrita um do outro (e o token novo do Box se perdia).
     */
    @Synchronized
    fun salvar(ctx: Context, c: ContaMidia): ContaMidia? {
        val nova = if (c.id.isEmpty()) c.copy(id = UUID.randomUUID().toString().take(8)) else c
        val atuais = runCatching { lerOuFalhar(ctx) }.getOrElse { return null }.filter { it.id != nova.id }
        if (atuais.size >= MidiaContas.MAX) return null
        return if (gravar(ctx, atuais + nova)) nova else null
    }

    @Synchronized
    fun apagar(ctx: Context, id: String): Boolean = gravar(ctx, runCatching { lerOuFalhar(ctx) }.getOrElse { return false }.filter { it.id != id })
}

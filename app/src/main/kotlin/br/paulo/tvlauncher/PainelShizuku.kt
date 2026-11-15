package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cartão "Shell emprestado" do painel (aba Extras, emprestimo.js): quem pede o Shizuku, quem tem acesso, permitir e
 * revogar (15/11/2026; existiu de 05/11 a 12/11 como aba Shizuku). A decisão "permitido" mora no servidor
 * (AutorizacoesShizuku): com o servidor parado, a lista mostra os apps que PEDEM, sem o estado. Permitir dá poder de
 * shell (uid 2000) ao app, então o painel pede o PIN de novo; revogar não precisa.
 */
object PainelShizuku {
    fun estado(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val rodando = ShizukuEmbutido.rodando()
        val o = JSONObject()
            .put("modo", ShizukuEmbutido.modo(ctx).name)
            .put("parado", ShizukuEmbutido.parado(ctx).name)
            .put("rodando", rodando)
        val apps = JSONArray()
        for (pacote in ShizukuEmbutido.clientesInstalados(ctx).sorted()) {
            val ai = try { pm.getApplicationInfo(pacote, 0) } catch (_: Exception) { continue }
            val a = JSONObject().put("pacote", pacote).put("nome", try { ai.loadLabel(pm).toString() } catch (_: Exception) { pacote })
            if (rodando) try { a.put("permitido", AutorizacoesShizuku.permitido(ai.uid)) } catch (_: Throwable) {}
            apps.put(a)
        }
        return o.put("apps", apps)
    }

    /** Permite ([permitir] true) ou revoga. Devolve null se deu certo, ou o motivo. */
    fun mudar(ctx: Context, pacote: String, permitir: Boolean): String? {
        if (!ChecagensPainel.pacoteValido(pacote)) return "pacote inválido"
        if (pacote !in ShizukuEmbutido.clientesInstalados(ctx)) return "esse app não pede o Shizuku"
        if (!ShizukuEmbutido.rodando()) return "o empréstimo do shell está parado: ligue nas Configurações da TV ou abra o app"
        val uid = try { ctx.packageManager.getApplicationInfo(pacote, 0).uid } catch (_: Exception) { return "app não encontrado" }
        return try {
            if (permitir) AutorizacoesShizuku.permitir(uid) else AutorizacoesShizuku.revogar(uid); null
        } catch (e: Throwable) { "o servidor recusou: ${e.javaClass.simpleName}" }
    }
}

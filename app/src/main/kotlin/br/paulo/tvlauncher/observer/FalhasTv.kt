package br.paulo.tvlauncher.observer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.BackupNoturno
import br.paulo.tvlauncher.BackupNoturnoActivity
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.privilege.SaudeAcesso
import java.net.HttpURLConnection
import java.net.URL

/**
 * A parte Android do catálogo de falhas (elevação 2.3; regras em Falhas.kt): junta os sinais da TV, dá os textos no
 * idioma dela e faz o conserto do botão. Mostrada no placar ("Como esta TV está indo", seção "Precisa de atenção"),
 * no player (canal que não volta) e pelo `EXTRAS --es o falhas`.
 */
object FalhasTv {
    private const val TAG = "TvLauncher.Falhas"

    /** Lê os sinais. [comRede] = pergunta a hora à internet (até ~5 s): fora da thread da tela. */
    fun sinais(ctx: Context, comRede: Boolean): Falhas.Sinais {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val (hora, certErrado) = if (comRede) horaDaInternet() else null to false
        val vencidas = runCatching { PlayerArmazem.vencimentos(app).count { it.second * 1000 < agora } }.getOrDefault(0)
        return Falhas.Sinais(
            agora = agora,
            livreMb = runCatching { app.filesDir.usableSpace / 1_048_576 }.getOrNull(),
            horaDaInternet = hora, certificadoForaDaData = certErrado,
            listasVencidas = vencidas,
            shellOk = runCatching { if (SaudeAcesso.shellEsperado(app)) SaudeAcesso.shellOk() else null }.getOrNull(),
            tunelFaltaLogin = runCatching { SaudeAcesso.tunelFaltaLogin(app) }.getOrDefault(false),
            backupLigado = runCatching { BackupNoturno.ligado(app) }.getOrDefault(false),
            ultimoBackupOk = runCatching { BackupNoturno.ultimoOkEm(app) }.getOrDefault(0L),
            backupDesde = runCatching { BackupNoturno.ligadoDesde(app) }.getOrDefault(0L),
        )
    }

    fun atuais(ctx: Context, comRede: Boolean): List<Falhas.Tipo> = Falhas.detectar(sinais(ctx, comRede))

    /**
     * A hora do cabeçalho "Date" de um endereço do Google feito para isso (resposta vazia). TLS como sempre: se o
     * certificado "ainda não vale" ou "já venceu", é o relógio da TV que está fora (nunca desligamos a verificação).
     */
    private fun horaDaInternet(): Pair<Long?, Boolean> = try {
        val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "HEAD"
            c.responseCode
            Falhas.dataHttp(c.getHeaderField("Date")) to false
        } finally { c.disconnect() }
    } catch (e: Exception) {
        var x: Throwable? = e
        var cert = false
        while (x != null) {
            if (x is java.security.cert.CertificateNotYetValidException || x is java.security.cert.CertificateExpiredException) cert = true
            x = x.cause
        }
        null to cert
    }

    private fun id(ctx: Context, t: Falhas.Tipo, parte: String): Int =
        ctx.resources.getIdentifier("elv_f_${t.name.lowercase()}_$parte", "string", ctx.packageName)

    fun frase(ctx: Context, t: Falhas.Tipo) = ctx.getString(id(ctx, t, "frase"))
    fun causa(ctx: Context, t: Falhas.Tipo) = ctx.getString(id(ctx, t, "causa"))
    fun botao(ctx: Context, t: Falhas.Tipo) = ctx.getString(id(ctx, t, "botao"))

    /** O botão. [outroCanal] = o que "Ver outro canal" faz (só o player sabe abrir a lista). */
    fun consertar(a: Activity, t: Falhas.Tipo, outroCanal: (() -> Unit)? = null) {
        try {
            when (Falhas.conserto(t)) {
                Falhas.Conserto.OUTRO_CANAL -> outroCanal?.invoke()
                Falhas.Conserto.ABRIR_CONTA -> a.startActivity(Intent(a, ConfigLauncherActivity::class.java)
                    .putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_CONTA))
                Falhas.Conserto.ACERTAR_RELOGIO -> a.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                Falhas.Conserto.LIBERAR_ESPACO -> a.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                Falhas.Conserto.RELIGAR_SHELL -> {
                    GuardiaoShizuku.garantir(a, "falha 2.3", mesmoSemFalta = true)
                    AvisoTv.rapido(a.applicationContext, botao(a, t), a.getString(R.string.elv_f_religando))
                }
                Falhas.Conserto.ENTRAR_TUNEL -> a.startActivity(Intent(a, ConfigLauncherActivity::class.java)
                    .putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_REMOTO))
                Falhas.Conserto.REFAZER_BACKUP -> a.startActivity(Intent(a, BackupNoturnoActivity::class.java))
            }
        } catch (e: Exception) { Log.w(TAG, "conserto de $t: ${e.javaClass.simpleName}") }
    }

    /** Caixa com a frase, a causa e o botão (OK conserta, Voltar fecha). */
    fun mostrar(a: Activity, t: Falhas.Tipo, outroCanal: (() -> Unit)? = null) {
        if (a.isFinishing) return
        CaixaTv(a).setTitle(frase(a, t)).setMessage(causa(a, t))
            .setPositiveButton(botao(a, t)) { _, _ -> consertar(a, t, outroCanal) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** `EXTRAS --es o falhas`: os estados de agora, com a frase (para o roteiro e o dia do caos, 21.6). */
    fun textoAdb(ctx: Context): String {
        // Na thread da tela (o recebedor do adb), sem a pergunta à internet: ela pode levar segundos.
        val l = atuais(ctx, comRede = android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        return org.json.JSONArray().apply {
            l.forEach { put(org.json.JSONObject().put("tipo", it.name).put("frase", frase(ctx, it)).put("botao", botao(ctx, it))) }
        }.toString(2)
    }
}

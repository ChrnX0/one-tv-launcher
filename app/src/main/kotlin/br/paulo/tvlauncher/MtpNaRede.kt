package br.paulo.tvlauncher

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A parte Android da TV em "Este Computador" (o protocolo está em MtpIp.kt): abre a porta 15740, guarda os
 * computadores aceitos e pergunta na tela quando chega um novo. Liga e desliga junto com a unidade de rede
 * (AnuncioDav), que também cuida do anúncio (AnuncioUpnp).
 *
 * Sem PIN (o MTP não tem onde digitar senha; decisão do dono em 14/11/2026). As barreiras são: só a rede de casa
 * (a mesma conferência do painel), a chave desligada por padrão e o "Permitir?" na TV, uma vez por computador.
 * O identificador do computador pode ser copiado por quem escuta a rede: não é uma senha, é um "quem é você".
 */
object MtpNaRede {
    private const val TAG = "TvLauncher.Mtp"
    private const val PREFS = "mtp_permitidos"   // identificador do computador -> nome que ele disse ter
    private const val MAX_PERMITIDOS = 50
    @Volatile private var servico: MtpServico? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Os nomes dos computadores que já foram aceitos na TV. */
    fun permitidos(ctx: Context): List<String> = prefs(ctx).all.values.map { it.toString() }.sorted()

    /** Esquece todos e derruba quem está ligado: cada computador pergunta de novo na próxima vez. */
    fun esquecerTodos(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        val ligado = servico != null
        desligar()
        if (ligado) ligar(ctx)
    }

    @Synchronized fun ligar(ctx: Context) {
        if (servico != null) return
        val app = ctx.applicationContext
        val ss = try { ServerSocket(PtpIp.PORTA, 16) } catch (e: Exception) { Log.w(TAG, "porta ${PtpIp.PORTA} ocupada: $e"); return }
        val id = PainelVizinhos.idDaTv(app)
        val u = UUID.fromString(Ssdp.uuidMtp(id))
        val guid = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        val versao = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1" } catch (_: Exception) { "1" }
        val ap = Mtp.Aparelho(PainelVizinhos.nomeDaTv(app), Build.MODEL ?: "Android TV", versao, id, guid)
        val s = MtpServico(ss, ap, MtpLoja { PainelArquivos.avisarGaleria(app, it) },
            aceitaRede = { PainelWeb.daRedeDeCasa(it) },
            permitir = { pc, nome, ip -> permitir(app, pc, nome, ip) },
            registro = { Log.i(TAG, it) })
        servico = s
        Thread({
            // Sem a permissão de "todos os arquivos" o Android esconde as pastas: pede ao nosso shell antes de servir.
            try { PainelArquivos.garantirAcesso(app) } catch (e: Exception) { Log.w(TAG, "acesso aos arquivos: $e") }
            s.rodar()
        }, "Mtp-servidor").apply { isDaemon = true }.start()
        Log.i(TAG, "MTP pela rede ligado na porta ${PtpIp.PORTA}")
    }

    @Synchronized fun desligar() {
        servico?.parar()
        servico = null
    }

    /** Computador conhecido entra direto; novo, a TV pergunta (até 60 s; sem resposta = não). */
    private fun permitir(app: Context, pc: String, nome: String, ip: String): Boolean {
        if (prefs(app).contains(pc)) return true
        if (prefs(app).all.size >= MAX_PERMITIDOS) return false
        val sim = ConfirmacoesTv.perguntarSempre(app, app.getString(R.string.mtp_pergunta_titulo),
            app.getString(R.string.mtp_pergunta, nome), ip)
        if (sim) prefs(app).edit().putString(pc, nome.take(80)).apply()
        return sim
    }
}

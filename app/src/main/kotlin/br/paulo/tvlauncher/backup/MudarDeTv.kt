package br.paulo.tvlauncher.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.FilaAppsActivity
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.Diario
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PainelVizinhos
import br.paulo.tvlauncher.panel.PainelWeb
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * Elevação 19.7 (17/11/2026): "TV nova em 2 minutos". As regras e a criptografia estão em [MudarDeTvRegras]; aqui
 * fica o Android: a porta da TV nova ([iniciar]/[receber]), o envio da TV antiga ([enviar]) e a fila de apps.
 *
 * Nada liga sozinho: a TV nova só aceita alguma coisa enquanto a tela "Trazer de outra TV" está aberta (o dono abriu
 * com o controle); a TV antiga só manda quando o dono pede no painel e confirma o PIN de novo.
 */
object MudarDeTv {
    private const val TAG = "TvLauncher.MudarDeTv"
    private const val PREFS = "mudar_tv"   // a fila de apps (fora do backup: é desta TV, deste dia)

    // ---------------------------------------------------------------- TV nova: a porta

    /** A porta não está aberta (tela fechada, ou o segredo não foi conferido antes). */
    class NaoEsperando : Exception("esta TV não está esperando")

    enum class Estado { PARADO, ESPERANDO, RECEBENDO, PRONTO, ERRO }

    @Volatile private var par: KeyPair? = null
    @Volatile private var recepcao: MudarDeTvRegras.Recepcao? = null
    @Volatile var estado = Estado.PARADO; private set
    @Volatile var detalhe: String = ""; private set   // o nome da TV que mandou, ou o motivo do erro
    // Hora (elapsed) em que o segredo certo chegou; falta só o corpo. 0 = não. Vence em [PRAZO_CORPO_MS]: se a conexão
    // cair no meio do corpo, a porta não fica aberta sem PIN até a tela fechar (rodada 15 da depuração).
    @Volatile private var aceitoEm = 0L
    private const val PRAZO_CORPO_MS = 120_000L
    private fun aceito() = aceitoEm != 0L && android.os.SystemClock.elapsedRealtime() - aceitoEm < PRAZO_CORPO_MS

    /** Abre a porta e devolve o convite (QR). Null sem rede de casa. A chave privada vive só na memória. */
    @Synchronized fun iniciar(ctx: Context): MudarDeTvRegras.Convite? {
        val ip = PainelWeb.ipsDaRede().filter { MudarDeTvRegras.ipDeCasa(it) }.minOrNull() ?: return null
        val p = MudarDeTvRegras.novoPar()
        val r = MudarDeTvRegras.Recepcao(MudarDeTvRegras.novoSegredo(), System.currentTimeMillis())
        par = p; recepcao = r; aceitoEm = 0
        estado = Estado.ESPERANDO; detalhe = ""
        PainelWeb.ajustar(ctx)   // a porta do painel abre mesmo sem PIN enquanto esta tela está aberta ([recebendo])
        return MudarDeTvRegras.Convite(ip, Config.PAINEL_PORTA, r.segredo, MudarDeTvRegras.digital(p.public.encoded))
    }

    /** A tela saiu: o segredo e a chave morrem (salvo no meio de uma recepção, que termina sozinha). */
    @Synchronized fun parar(ctx: Context) {
        if (estado == Estado.RECEBENDO) return
        par = null; recepcao = null; aceitoEm = 0
        if (estado == Estado.ESPERANDO) estado = Estado.PARADO
        PainelWeb.ajustar(ctx)
    }

    /** A porta está aberta para receber? (PainelWeb.ajustar deixa o painel de pé, e só as rotas de mudar respondem.) */
    fun recebendo(): Boolean = par != null && (recepcao?.vale(System.currentTimeMillis()) == true || aceito())

    fun tempoRestanteMs(): Long = recepcao?.let { (it.criadoEm + MudarDeTvRegras.VALIDADE_MS - System.currentTimeMillis()).coerceAtLeast(0) } ?: 0

    fun chavePublica(): String? = par?.public?.encoded?.let { Base64.getEncoder().encodeToString(it) }

    /** Confere o segredo do cabeçalho ANTES de ler o corpo (chute errado não gasta memória). Conta os erros. */
    @Synchronized fun conferirSegredo(s: String?): MudarDeTvRegras.Conferencia {
        val r = recepcao ?: return MudarDeTvRegras.Conferencia.VENCIDO
        val c = r.conferir(s, System.currentTimeMillis())
        if (c == MudarDeTvRegras.Conferencia.OK) aceitoEm = android.os.SystemClock.elapsedRealtime()
        return c
    }

    /**
     * Abre o envelope e restaura. Lança IllegalArgumentException (nada mudou) se não abre ou o backup é recusado.
     * Devolve o nome da TV de onde veio. Quem chama reinicia o launcher depois (como no restaurar).
     */
    fun receber(ctx: Context, corpo: String): String {
        val (privada, segredo) = synchronized(this) {
            val k = par?.private
            val s = recepcao?.segredo
            if (!aceito() || k == null || s == null) throw NaoEsperando()
            aceitoEm = 0
            estado = Estado.RECEBENDO
            k to s
        }
        try {
            val env = runCatching { JSONObject(corpo) }.getOrNull() ?: throw IllegalArgumentException("conteúdo ilegível")
            val p = MudarDeTvRegras.lerPacote(MudarDeTvRegras.abrir(privada, env, segredo))
            BackupCompleto.importar(ctx, p.backup, p.senha)
            val instalados = runCatching { ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet() }.getOrDefault(emptySet())
            val fila = MudarDeTvRegras.fila(p.apps, instalados, ctx.packageName)
            prefs(ctx).edit().putString("fila", MudarDeTvRegras.appsParaJson(fila)).putStringSet("pulados", emptySet())
                .putString("de", p.de).putBoolean("abrir", fila.isNotEmpty()).commit()
            synchronized(this) { estado = Estado.PRONTO; detalhe = p.de; par = null; recepcao = null }
            Diario.anotar(ctx, "tv", ctx.getString(R.string.mtv_diario, p.de.ifBlank { "?" }, fila.size), "ok")
            return p.de
        } catch (e: Exception) {
            synchronized(this) { estado = Estado.ERRO; detalhe = e.message ?: e.javaClass.simpleName; par = null; recepcao = null }
            runCatching { PainelWeb.ajustar(ctx) }   // a porta aberta só para receber fecha já
            throw e
        }
    }

    // ---------------------------------------------------------------- TV antiga: o envio

    /**
     * Manda tudo para a TV nova do [codigo] (QR ou digitado). Bloqueia (export + envio + restauração lá): fora da
     * thread da tela. Lança IllegalArgumentException com o motivo para a pessoa.
     */
    fun enviar(ctx: Context, codigo: String): String {
        val app = ctx.applicationContext
        val c = MudarDeTvRegras.ler(codigo, Config.PAINEL_PORTA) ?: throw IllegalArgumentException("código inválido")
        val base = "http://${c.ip}:${c.porta}"
        // 1. A chave da TV nova, conferida pela digital que veio no QR: alguém na rede não troca a chave no meio.
        val chave = chamar(base, "/api/mudar/chave", null, null)
        val pub = chave.second?.optString("publica")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: throw IllegalArgumentException(if (chave.first == 0) "a TV nova não respondeu (mesma rede?)" else "a TV nova não está esperando (abra 'Trazer de outra TV' nela)")
        if (MudarDeTvRegras.digital(pub) != c.digital) throw IllegalArgumentException("a chave da TV nova não confere com o código")
        // 2. O backup completo com uma senha descartável (vai junto, dentro do envelope) e os apps que a pessoa usa.
        val senha = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val backup = BackupCompleto.exportar(app, senha)
        val lista = PainelApps.listar(app)
        val apps = (0 until lista.length()).mapNotNull { i ->
            val o = lista.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("sistema") || !o.optBoolean("abrivel")) null else MudarDeTvRegras.App(o.optString("pacote"), o.optString("nome"))
        }
        val env = MudarDeTvRegras.selar(pub, MudarDeTvRegras.pacote(senha, backup, apps, PainelVizinhos.nomeDaTv(app)), c.segredo)
        // 3. Envia. A TV nova confere o segredo antes de ler o corpo e restaura na hora.
        val r = chamar(base, "/api/mudar/receber", env.toString(), MudarDeTvRegras.prova(c.segredo))
        if (r.first != 200) throw IllegalArgumentException(r.second?.optString("erro")?.ifBlank { null } ?: "a TV nova recusou (${r.first})")
        Diario.anotar(app, "tv", app.getString(R.string.mtv_diario_enviado, apps.size), "ok")
        return r.second?.optString("de") ?: ""
    }

    /** GET (sem corpo) ou POST na outra TV da rede de casa. Devolve (código HTTP, JSON); 0 = sem resposta. */
    private fun chamar(base: String, caminho: String, corpo: String?, segredo: String?): Pair<Int, JSONObject?> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(base + caminho).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000; readTimeout = 90_000; instanceFollowRedirects = false; useCaches = false
                setRequestProperty("Accept", "application/json")
                if (corpo != null) {
                    requestMethod = "POST"; doOutput = true
                    // As mesmas checagens de qualquer POST do painel (Origin igual ao Host e X-Painel).
                    setRequestProperty("Origin", base); setRequestProperty("X-Painel", "1")
                    setRequestProperty("X-Mudar", segredo ?: "")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val b = corpo.toByteArray()
                    setFixedLengthStreamingMode(b.size)
                    outputStream.use { it.write(b) }
                }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.use { s -> s.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) }
            codigo to texto?.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: Exception) {
            Log.w(TAG, "$caminho: $e"); 0 to null
        } finally { c?.disconnect() }
    }

    // ---------------------------------------------------------------- a fila de apps (TV nova)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fila(ctx: Context): List<MudarDeTvRegras.App> =
        MudarDeTvRegras.appsDeJson(runCatching { org.json.JSONArray(prefs(ctx).getString("fila", "[]")) }.getOrNull())

    fun pulados(ctx: Context): Set<String> = prefs(ctx).getStringSet("pulados", emptySet()).orEmpty()

    fun instalado(ctx: Context, pacote: String) =
        runCatching { ctx.packageManager.getPackageInfo(pacote, 0); true }.getOrDefault(false)

    fun proximo(ctx: Context): MudarDeTvRegras.App? {
        val f = fila(ctx)
        return MudarDeTvRegras.proximo(f, f.filter { instalado(ctx, it.pacote) }.map { it.pacote }.toSet(), pulados(ctx))
    }

    /** Quantos ainda faltam (instalados e pulados saem da conta). */
    fun faltam(ctx: Context): Int { val p = pulados(ctx); return fila(ctx).count { it.pacote !in p && !instalado(ctx, it.pacote) } }

    fun pular(ctx: Context, pacote: String) { prefs(ctx).edit().putStringSet("pulados", pulados(ctx) + pacote).apply() }

    fun limpar(ctx: Context) { prefs(ctx).edit().clear().apply() }

    fun de(ctx: Context): String = prefs(ctx).getString("de", "") ?: ""

    /** Abre a página do app na Play Store (a pessoa toca em Instalar). false se não há loja. */
    fun abrirNaLoja(ctx: Context, pacote: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pacote")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** Depois do reinício: a HOME abre a fila uma vez. */
    fun abrirFilaSePendente(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("abrir", false)) return
        p.edit().putBoolean("abrir", false).apply()
        runCatching { ctx.startActivity(Intent(ctx, FilaAppsActivity::class.java)) }
    }
}

package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.Fundo
import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.ServicosFilmesActivity
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.ServicosFilmes.Espera
import br.paulo.tvlauncher.player.ServicosFilmes.Servico
import br.paulo.tvlauncher.weather.DataSources
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * As contas dos serviços de filmes e séries (ver [ServicosFilmes]): disco, rede e o login por código.
 *
 * Disco: files/player/servicos.json, cifrado pelo Keystore como as contas de IPTV (PlayerArmazem.cifrar). Não vai em
 * texto em backup nenhum; o backup completo (cifrado com a senha) leva o conteúdo em "segredos".
 *
 * Login por código (Trakt e Simkl): [entrar] pede o código ao serviço e uma thread fica perguntando, no intervalo que o
 * serviço manda, se a pessoa já digitou no celular. A tela da TV ([ServicosFilmesActivity]) e o painel web leem a mesma
 * [Sessao]: começar na TV e terminar olhando o painel funciona. Um login por serviço de cada vez.
 *
 * Nada de token, senha, chave ou nome de usuário no log (só o nome do serviço e o tipo do erro).
 */
object ServicosContas {
    /**
     * Falha que a pessoa vê (15/11/2026). A mensagem fica em português para o painel (que traduz no navegador, i18n.js);
     * a TV mostra o texto do [motivo] no idioma dela (ServicosFilmesActivity.textoFalha).
     */
    enum class Motivo { CHAVE_INVALIDA, CHAVE_RECUSADA, RESPONDEU, FALTA_DADOS, LONGO, SENHA_ERRADA, GRAVAR, DO_SERVICO }
    class FalhaConta(val motivo: Motivo, texto: String, val servico: String = "", val codigo: Int = 0) : Exception(texto)

    private const val TAG = "TvLauncher.Servicos"
    private const val ARQUIVO = "servicos.json"

    fun chavesApp(): ServicosFilmes.ChavesApp = ServicosFilmes.ChavesApp(
        BuildConfig.TMDB_CHAVE, BuildConfig.TRAKT_ID, BuildConfig.TRAKT_SEGREDO, BuildConfig.SIMKL_ID, BuildConfig.OPENSUBTITLES_CHAVE)

    fun disponivel(s: Servico): Boolean = chavesApp().disponivel(s)

    private val versao get() = BuildConfig.VERSION_CODE

    // ================================================================ disco

    private val trava = Any()

    fun contas(ctx: Context): Map<Servico, ServicosFilmes.Conta> = synchronized(trava) {
        runCatching {
            val o = JSONObject(File(PlayerArmazem.pasta(ctx), ARQUIVO).readText())
            ServicosFilmes.contasDeJson(PlayerArmazem.decifrar(o.getString("dados"), o.getString("iv")))
        }.getOrDefault(emptyMap())
    }

    fun conta(ctx: Context, s: Servico): ServicosFilmes.Conta? = contas(ctx)[s]

    private fun gravar(ctx: Context, contas: Map<Servico, ServicosFilmes.Conta>): Boolean = synchronized(trava) {
        runCatching {
            val arq = File(PlayerArmazem.pasta(ctx), ARQUIVO)
            if (contas.isEmpty()) { arq.delete(); return@runCatching }
            val (dados, iv) = PlayerArmazem.cifrar(ServicosFilmes.contasParaJson(contas))
            val tmp = File(arq.path + ".tmp")
            tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
            check(tmp.renameTo(arq))
        }.onFailure { Log.w(TAG, "não gravei as contas: ${it.javaClass.simpleName}") }.isSuccess
    }

    fun definir(ctx: Context, s: Servico, c: ServicosFilmes.Conta?): Boolean = synchronized(trava) {
        val todas = contas(ctx).toMutableMap()
        if (c == null) todas.remove(s) else todas[s] = c
        gravar(ctx, todas)
    }

    /** Para o backup completo (cifrado com a senha do backup). Null = nenhuma conta. */
    fun paraBackup(ctx: Context): String? = contas(ctx).takeIf { it.isNotEmpty() }?.let { ServicosFilmes.contasParaJson(it) }

    fun restaurarDoBackup(ctx: Context, json: String): Boolean = gravar(ctx, ServicosFilmes.contasDeJson(json))

    // ================================================================ estado (TV e painel)

    enum class Situacao { INDISPONIVEL, DESLIGADO, ENTRANDO, CONECTADO }

    data class Estado(val servico: Servico, val situacao: Situacao, val usuario: String = "")

    fun estado(ctx: Context, s: Servico): Estado {
        if (!disponivel(s)) {
            // OpenSubtitles: quem já tinha a chave própria cadastrada continua usando até o build ter a do app.
            if (s == Servico.OPENSUBTITLES && LegendaBaixar.temConta(ctx)) return Estado(s, Situacao.CONECTADO, LegendaBaixar.conta(ctx)?.usuario.orEmpty())
            return Estado(s, Situacao.INDISPONIVEL)
        }
        if (sessao(s)?.estado == EstadoLogin.ESPERANDO) return Estado(s, Situacao.ENTRANDO)
        val c = conta(ctx, s)
        return when {
            // O TMDB não tem conta da pessoa: basta a chave do app, o contrato e o plano (DataSources). Sem isso as fichas
            // vêm do Trakt e a linha some (não diz "ativo" nem mostra a atribuição de uma fonte que não está em uso).
            s == Servico.TMDB -> Estado(s, if (DataSources.tmdb(ctx)) Situacao.CONECTADO else Situacao.INDISPONIVEL)
            s == Servico.OPENSUBTITLES -> Estado(s, if (c != null && c.usuario.isNotBlank()) Situacao.CONECTADO else Situacao.DESLIGADO, c?.usuario.orEmpty())
            c != null -> Estado(s, Situacao.CONECTADO, c.usuario)
            else -> Estado(s, Situacao.DESLIGADO)
        }
    }

    fun estados(ctx: Context): List<Estado> = Servico.entries.map { estado(ctx, it) }

    // ================================================================ login por código (Trakt, Simkl)

    enum class EstadoLogin { ESPERANDO, OK, ERRO }

    class Sessao(val servico: Servico, val codigo: ServicosFilmes.Codigo, val expira: Long) {
        @Volatile var estado = EstadoLogin.ESPERANDO
        @Volatile var motivo: ServicosFilmes.Motivo? = null
        @Volatile var usuario: String = ""
    }

    private val sessoes = java.util.concurrent.ConcurrentHashMap<Servico, Sessao>()

    fun sessao(s: Servico): Sessao? = sessoes[s]?.takeIf { it.estado != EstadoLogin.ESPERANDO || it.expira > System.currentTimeMillis() }

    /** Pede o código ao serviço e começa a perguntar. Bloqueia (rede): fora da thread da tela. */
    fun entrar(ctx: Context, s: Servico): Sessao {
        val app = ctx.applicationContext
        val k = chavesApp()
        require(s == Servico.TRAKT || s == Servico.SIMKL) { "login por código só no Trakt e no Simkl" }
        check(k.disponivel(s)) { "serviço sem chave neste build" }
        cancelar(s)
        val codigo = when (s) {
            Servico.TRAKT -> pedir("${ServicosFilmes.TRAKT_AUTH}/oauth/device/code", ServicosFilmes.traktCabecalhos(k.traktId, versao),
                ServicosFilmes.traktCorpoCodigo(k.traktId)).let { r ->
                if (r.codigo !in 200..299) throw FalhaConta(Motivo.RESPONDEU, "o Trakt respondeu ${r.codigo}", "Trakt", r.codigo)
                ServicosFilmes.traktLerCodigo(r.corpo) ?: error("resposta do Trakt sem código")
            }
            else -> pedir("${ServicosFilmes.SIMKL_API}/oauth2/device", ServicosFilmes.simklCabecalhosForm(versao),
                ServicosFilmes.simklCorpoCodigo(k.simklId)).let { r ->
                if (r.codigo !in 200..299) throw FalhaConta(Motivo.RESPONDEU, "o Simkl respondeu ${r.codigo}", "Simkl", r.codigo)
                ServicosFilmes.simklLerCodigo(r.corpo) ?: error("resposta do Simkl sem código")
            }
        }
        val sessao = Sessao(s, codigo, System.currentTimeMillis() + codigo.expiraEmS * 1000L)
        sessoes[s] = sessao
        Fundo.rodar("Servicos-${s.id}") { esperar(app, sessao, k) }
        return sessao
    }

    fun cancelar(s: Servico) {
        sessoes.remove(s)?.let { if (it.estado == EstadoLogin.ESPERANDO) { it.estado = EstadoLogin.ERRO; it.motivo = ServicosFilmes.Motivo.VENCEU } }
    }

    private fun esperar(app: Context, sessao: Sessao, k: ServicosFilmes.ChavesApp) {
        var intervalo = sessao.codigo.intervaloS * 1000L
        while (sessao.estado == EstadoLogin.ESPERANDO) {
            Thread.sleep(intervalo)
            if (sessao.estado != EstadoLogin.ESPERANDO) return
            if (System.currentTimeMillis() > sessao.expira) { fim(sessao, ServicosFilmes.Motivo.VENCEU); return }
            val e = runCatching {
                when (sessao.servico) {
                    Servico.TRAKT -> pedir("${ServicosFilmes.TRAKT_AUTH}/oauth/device/token", ServicosFilmes.traktCabecalhos(k.traktId, versao),
                        ServicosFilmes.traktCorpoToken(sessao.codigo.codigoAparelho, k.traktId, k.traktSegredo))
                        .let { ServicosFilmes.traktLerEspera(it.codigo, it.corpo, System.currentTimeMillis()) }
                    else -> pedir("${ServicosFilmes.SIMKL_API}/oauth2/token", ServicosFilmes.simklCabecalhosForm(versao),
                        ServicosFilmes.simklCorpoEspera(sessao.codigo.codigoAparelho, k.simklId))
                        .let { ServicosFilmes.simklLerEspera(it.codigo, it.corpo, System.currentTimeMillis()) }
                }
            }.getOrElse { Espera.Pendente }   // rede caiu um instante: pergunta de novo no próximo intervalo
            when (e) {
                is Espera.Pendente -> Unit
                is Espera.MaisDevagar -> intervalo += 5_000
                is Espera.Fim -> { fim(sessao, e.motivo); return }
                is Espera.Token -> {
                    val usuario = runCatching { nomeDaConta(sessao.servico, e.acesso, k) }.getOrNull().orEmpty()
                    if (sessao.estado != EstadoLogin.ESPERANDO) return   // cancelaram enquanto perguntávamos o nome
                    if (!definir(app, sessao.servico, ServicosFilmes.Conta(e.acesso, e.renovar, e.expiraEmMs, usuario))) {
                        fim(sessao, ServicosFilmes.Motivo.ERRO); return
                    }
                    sessao.usuario = usuario
                    sessao.estado = EstadoLogin.OK
                    Log.i(TAG, "${sessao.servico.id}: conectado")
                    return
                }
            }
        }
    }

    private fun fim(s: Sessao, m: ServicosFilmes.Motivo) { s.motivo = m; s.estado = EstadoLogin.ERRO; Log.i(TAG, "${s.servico.id}: login terminou ($m)") }

    private fun nomeDaConta(s: Servico, token: String, k: ServicosFilmes.ChavesApp): String? = when (s) {
        Servico.TRAKT -> pedir("${ServicosFilmes.TRAKT_API}/users/settings", ServicosFilmes.traktCabecalhos(k.traktId, versao, token))
            .takeIf { it.codigo in 200..299 }?.let { ServicosFilmes.traktLerUsuario(it.corpo) }
        Servico.SIMKL -> pedir(ServicosFilmes.simklUrlUsuario(k.simklId, versao), ServicosFilmes.simklCabecalhos(k.simklId, versao, token), "{}")
            .takeIf { it.codigo in 200..299 }?.let { ServicosFilmes.simklLerUsuario(it.corpo) }
        else -> null
    }

    // ================================================================ sair, MDBList, OpenSubtitles, renovar

    /** Sai da conta: tira o acesso também no serviço (Trakt e Simkl revogam o token) e apaga da TV. Bloqueia (rede). */
    fun sair(ctx: Context, s: Servico) {
        cancelar(s)
        val c = conta(ctx, s)
        val k = chavesApp()
        if (s == Servico.TRAKT && c != null && k.disponivel(s)) runCatching {
            pedir("${ServicosFilmes.TRAKT_AUTH}/oauth/revoke", ServicosFilmes.traktCabecalhos(k.traktId, versao),
                ServicosFilmes.traktCorpoRevogar(c.acesso, k.traktId, k.traktSegredo))
        }
        if (s == Servico.SIMKL && c != null && k.disponivel(s)) runCatching {
            pedir("${ServicosFilmes.SIMKL_API}/oauth2/revoke", ServicosFilmes.simklCabecalhosForm(versao),
                ServicosFilmes.simklCorpoRevogar(c.renovar ?: c.acesso, k.simklId))
        }
        if (s == Servico.OPENSUBTITLES) LegendaBaixar.esquecerToken()
        definir(ctx, s, null)
        // A nota do Simkl só aparece com a conta conectada (licença; InfoExternaCache.noturnoSimkl).
        if (s == Servico.SIMKL && !DataSources.simklSemLogin(ctx)) runCatching { InfoExternaCache.esquecerSimkl(ctx) }
    }

    /** A chave do MDBList da pessoa (opcional). Null = não conectou. */
    fun chaveMdblist(ctx: Context): String? = conta(ctx, Servico.MDBLIST)?.acesso?.takeIf { it.isNotBlank() }

    /** MDBList: confere a chave pessoal no próprio MDBList (GET /user) e grava. Devolve o nome da conta ou o erro. */
    fun definirMdblist(ctx: Context, chave: String): Result<String> = runCatching {
        val ch = chave.trim()
        if (!ServicosFilmes.mdblistChaveValida(ch)) throw FalhaConta(Motivo.CHAVE_INVALIDA, "chave inválida (só letras e números)")
        val r = pedir(ServicosFilmes.mdblistUrlUsuario(ch), mapOf("User-Agent" to ServicosFilmes.agente(versao)))
        if (r.codigo == 401 || r.codigo == 403) throw FalhaConta(Motivo.CHAVE_RECUSADA, "o MDBList não aceitou esta chave", "MDBList")
        if (r.codigo !in 200..299) throw FalhaConta(Motivo.RESPONDEU, "o MDBList respondeu ${r.codigo}", "MDBList", r.codigo)
        val nome = ServicosFilmes.mdblistLerUsuario(r.corpo).orEmpty()
        if (!definir(ctx, Servico.MDBLIST, ServicosFilmes.Conta(ch, usuario = nome))) throw FalhaConta(Motivo.GRAVAR, "não consegui gravar")
        nome
    }

    /**
     * OpenSubtitles: usuário e senha da conta (opcional; sobe o limite de 5 para 20 legendas por dia). Confere entrando
     * de verdade (POST /login com a chave do app) antes de gravar.
     */
    fun definirOpenSubtitles(ctx: Context, usuario: String, senha: String): Result<Unit> = runCatching {
        if (usuario.isBlank() || senha.isEmpty()) throw FalhaConta(Motivo.FALTA_DADOS, "falta o usuário ou a senha")
        if (usuario.length > 100 || senha.length > 200) throw FalhaConta(Motivo.LONGO, "usuário ou senha longos demais")
        val chave = chavesApp().openSubtitles.ifBlank { LegendaBaixar.conta(ctx)?.chaveApi.orEmpty() }
        check(chave.isNotBlank()) { "este build não tem a chave do OpenSubtitles" }
        val r = pedir("${LegendaExterna.BASE}/login", mapOf("Api-Key" to chave, "User-Agent" to LegendaExterna.AGENTE,
            "Content-Type" to "application/json", "Accept" to "application/json"), LegendaExterna.corpoLogin(usuario.trim(), senha))
        if (r.codigo == 401) throw FalhaConta(Motivo.SENHA_ERRADA, "usuário ou senha errados")
        if (r.codigo !in 200..299) throw LegendaExterna.lerErro(r.corpo)?.let { FalhaConta(Motivo.DO_SERVICO, it) }
            ?: FalhaConta(Motivo.RESPONDEU, "o OpenSubtitles respondeu ${r.codigo}", "OpenSubtitles", r.codigo)
        if (!definir(ctx, Servico.OPENSUBTITLES, ServicosFilmes.Conta("", usuario = usuario.trim(), senha = senha))) throw FalhaConta(Motivo.GRAVAR, "não consegui gravar")
        LegendaBaixar.esquecerToken()
    }

    /**
     * Renova os tokens do Trakt (3 meses) e do Simkl (7 dias) quando falta menos de 1 dia (manutenção da madrugada e
     * ao abrir a tela dos serviços). Só o processo do launcher chama: renovar em dois processos derruba um ao outro.
     * O Trakt devolve um refresh token novo; o do Simkl é sempre o mesmo. Bloqueia (rede).
     */
    fun renovarSePreciso(ctx: Context) {
        for (s in listOf(Servico.TRAKT, Servico.SIMKL)) renovar(ctx, s, forcar = false)
    }

    /** Renova um token. [forcar] = a API respondeu 401 (token velho), mesmo longe de vencer. Devolve a conta nova ou null. */
    @Synchronized
    fun renovar(ctx: Context, s: Servico, forcar: Boolean): ServicosFilmes.Conta? {
        val c = conta(ctx, s) ?: return null
        val k = chavesApp()
        if (!k.disponivel(s) || c.renovar == null) return null
        if (!forcar && !ServicosFilmes.precisaRenovar(c.expiraEmMs, true, System.currentTimeMillis())) return c
        return runCatching {
            val r = if (s == Servico.TRAKT) pedir("${ServicosFilmes.TRAKT_AUTH}/oauth/token", ServicosFilmes.traktCabecalhos(k.traktId, versao),
                ServicosFilmes.traktCorpoRenovar(c.renovar, k.traktId, k.traktSegredo))
            else pedir("${ServicosFilmes.SIMKL_API}/oauth2/token", ServicosFilmes.simklCabecalhosForm(versao),
                ServicosFilmes.simklCorpoRenovar(c.renovar, k.simklId))
            Log.i(TAG, "${s.id}: renovação ${r.codigo}")
            val t = if (r.codigo in 200..299) ServicosFilmes.lerToken(r.corpo, System.currentTimeMillis()) else null
            when {
                t != null -> c.copy(acesso = t.acesso, renovar = t.renovar ?: c.renovar, expiraEmMs = t.expiraEmMs).also { definir(ctx, s, it) }
                r.codigo == 400 || r.codigo == 401 -> { definir(ctx, s, null); null }   // renovação recusada: a pessoa entra de novo
                else -> c
            }
        }.getOrElse { Log.w(TAG, "${s.id}: renovação falhou: ${it.javaClass.simpleName}"); c }
    }

    // ================================================================ rede

    /** [local]: o cabeçalho Location (redirecionamento não é seguido; o /redirect do Simkl manda o id nele). */
    class Resposta(val codigo: Int, val corpo: String, val local: String? = null)

    /** GET (sem corpo) ou POST (com corpo JSON). Só https; sem seguir redirecionamento. */
    fun pedir(url: String, cabecalhos: Map<String, String>, corpo: String? = null, maxBytes: Int = 2_000_000): Resposta {
        require(url.startsWith("https://"))
        if (url.startsWith(ServicosFilmes.SIMKL_API)) espacarSimkl()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            for ((k, v) in cabecalhos) c.setRequestProperty(k, v)
            if (corpo != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.outputStream.use { it.write(corpo.toByteArray()) }
            }
            val codigo = c.responseCode
            val fluxo = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = fluxo?.use { String(lerAte(it, maxBytes)) }.orEmpty()
            return Resposta(codigo, texto, c.getHeaderField("Location"))
        } finally { c.disconnect() }
    }

    private val travaSimkl = Any()
    private var ultimoSimklMs = 0L

    /**
     * Uma chamada ao Simkl de cada vez, com [ESPACO_SIMKL_MS] entre elas. O painel Debug do Simkl marca como rajada 3+
     * pedidos no mesmo segundo do mesmo IP (visto no teste de 18/11/2026: activities + all-items/movies + shows no
     * mesmo segundo; e 4 no mesmo segundo no Sincronizar agora: activities + all-items delta + ids_only + playback).
     * Com 600 ms cabem no máximo 2 em qualquer segundo do relógio (450 ms ainda deixava 0 / 0,45 / 0,9 s caírem
     * juntos); 4 chamadas levam ~1,8 s a mais, em segundo plano.
     */
    private fun espacarSimkl() = synchronized(travaSimkl) {
        val agora = android.os.SystemClock.elapsedRealtime()
        val falta = ultimoSimklMs + ESPACO_SIMKL_MS - agora
        if (ultimoSimklMs > 0 && falta in 1..ESPACO_SIMKL_MS) Thread.sleep(falta)
        ultimoSimklMs = android.os.SystemClock.elapsedRealtime()
    }

    private const val ESPACO_SIMKL_MS = 600L

    /** Lê até [max] bytes (readNBytes só existe no Android 13+). */
    private fun lerAte(e: java.io.InputStream, max: Int): ByteArray {
        val saida = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
        while (saida.size() < max) { val n = e.read(buf, 0, minOf(buf.size, max - saida.size())); if (n < 0) break; saida.write(buf, 0, n) }
        return saida.toByteArray()
    }
}

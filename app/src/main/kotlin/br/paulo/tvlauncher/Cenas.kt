package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cenas por link (10/11/2026, ideia I; li no tv-remote: "Scenes and links: several steps in one tap, Home Assistant,
 * Siri Shortcuts"). Uma cena é um nome + uma lista de ações do catálogo FECHADO do [ComandosIa] (abrir app, abrir tela,
 * volume, mídia, modo visita, despertador). Cada cena ganha um link secreto:
 *     POST http://<IP da TV>:8080/cena/<token>
 * que os Atalhos do iPhone, o Home Assistant (rest_command) ou um botão no celular chamam. Quem roda é o mesmo caminho
 * do MCP ([PedidoIa.propor]): a TV mostra o aviso do que fez, e as ações passam pela mesma conferência.
 *
 * Segurança:
 *  - o token tem 128 bits aleatórios e só aparece UMA vez, ao criar; a TV guarda só o SHA-256 dele;
 *  - a comparação é em tempo constante ([MessageDigest.isEqual]);
 *  - criar, listar e apagar cenas exige o PIN (painel) ou o adb; rodar exige só o token;
 *  - o painel só aceita conexão da rede de casa (regra que já existe no PainelWeb); de fora, só pelo túnel.
 *  - nada de comando livre: o que não está no catálogo é recusado pelo [ComandosIa.conferir].
 */
object Cenas {
    private const val PREFS = "cenas"
    private const val CHAVE = "lista"
    const val MAX_CENAS = 20
    private val NOME = Regex("^[\\p{L}\\p{N} _-]{1,40}$")

    class Cena(val nome: String, val acoes: String, val hash: String)

    fun nomeValido(n: String): Boolean = NOME.matches(n.trim())

    /** 32 caracteres hexadecimais (128 bits). */
    fun gerarToken(aleatorio: SecureRandom = SecureRandom()): String =
        ByteArray(16).also { aleatorio.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun resumo(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A cena cujo hash bate com o do [token], comparando em tempo constante. Pura. */
    fun achar(token: String, cenas: List<Cena>): Cena? {
        if (!Regex("^[0-9a-f]{32}$").matches(token)) return null
        val h = resumo(token).toByteArray()
        var achada: Cena? = null
        for (c in cenas) if (MessageDigest.isEqual(h, c.hash.toByteArray())) achada = c   // sem sair cedo
        return achada
    }

    fun lerJson(texto: String?): List<Cena> = try {
        val a = JSONArray(texto ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Cena(o.optString("nome"), o.optString("acoes"), o.optString("hash"))
        }
    } catch (e: Exception) { emptyList() }

    fun paraJson(cenas: List<Cena>): String = JSONArray().apply {
        cenas.forEach { put(JSONObject().put("nome", it.nome).put("acoes", it.acoes).put("hash", it.hash)) }
    }.toString()

    // ---------------------------------------------------------------- na TV

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lista(ctx: Context): List<Cena> = lerJson(prefs(ctx).getString(CHAVE, null))
    private fun gravar(ctx: Context, l: List<Cena>) { prefs(ctx).edit().putString(CHAVE, paraJson(l)).apply() }

    /**
     * Cria (ou substitui, pelo nome) uma cena. [acoesJson] = {"acoes":[...]} no formato do ComandosIa. Devolve o token
     * (mostrar UMA vez) ou o motivo da recusa começando com "erro:".
     */
    fun criar(ctx: Context, nome: String, acoesJson: String, apps: Map<String, String>): String {
        val n = nome.trim()
        if (!nomeValido(n)) return "erro: nome inválido (1 a 40 letras, números, espaço, _ ou -)"
        val plano = ComandosIa.conferir(acoesJson, apps)
        if (plano.acoes.isEmpty()) return "erro: nenhuma ação válida" + plano.recusadas.joinToString("; ", " (", ")")
        val atuais = lista(ctx).filter { it.nome != n }
        if (atuais.size >= MAX_CENAS) return "erro: limite de $MAX_CENAS cenas"
        val token = gerarToken()
        gravar(ctx, atuais + Cena(n, acoesJson, resumo(token)))
        return token
    }

    /** Apps que uma cena pode abrir: os que têm tela de abertura na TV (pacote -> nome). */
    fun appsInstalados(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(i, 0).associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }

    fun apagar(ctx: Context, nome: String): Boolean {
        val l = lista(ctx); val nova = l.filter { it.nome != nome }
        if (nova.size == l.size) return false
        gravar(ctx, nova); return true
    }

    /** Roda pelo token (link) — o caminho do painel. Devolve um texto curto do resultado. */
    fun rodarPorToken(ctx: Context, token: String, origem: String): String? {
        val c = achar(token, lista(ctx)) ?: return null
        return executar(ctx, c, origem)
    }

    /** Roda pelo nome — só adb (ComandosExtras). */
    fun rodar(ctx: Context, nome: String, origem: String): String {
        val c = lista(ctx).firstOrNull { it.nome == nome } ?: return "cena não encontrada"
        return executar(ctx, c, origem)
    }

    private fun executar(ctx: Context, c: Cena, origem: String): String {
        val p = PedidoIa.propor(ctx, "cena: ${c.nome}", origem, c.acoes)
        return "cena ${c.nome}: ${p.situacao} ${p.mensagem}"
    }
}

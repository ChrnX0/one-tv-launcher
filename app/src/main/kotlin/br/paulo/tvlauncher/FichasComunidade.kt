package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ideia 70 (14/11/2026): fichas de apps e lista de limpeza mantidas pela comunidade. As duas tabelas que vêm no APK
 * (`assets/fichas-apps.json`, 94 apps da Sala; `Debloat.LISTA`) podem ser completadas por arquivos do repositório do
 * projeto (ComunidadeApps baixa uma vez por semana). Quem quiser somar um app manda um pull request; a TV só lê o que
 * foi mesclado na main.
 *
 * Puro (org.json), testado na JVM (FichasComunidadeTest). Tudo o que vem de fora passa por aqui antes de valer:
 *  - pacote com nome de pacote Android, textos curtos, risco só dos três conhecidos;
 *  - a ficha de fora NUNCA baixa o risco da ficha do APK (essencial continua essencial), só sobe ou completa;
 *  - item de limpeza só entra com "testado em" preenchido e fora do Debloat.NUNCA; o que já está no código manda.
 */
object FichasComunidade {
    const val MAX_BYTES = 512 * 1024
    private val PACOTE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
    private val RISCOS = listOf(PainelFicha.SEGURO, PainelFicha.CUIDADO, PainelFicha.ESSENCIAL)   // do mais leve ao mais grave

    fun pacoteValido(p: String) = p.length <= 150 && PACOTE.matches(p)

    private fun texto(o: JSONObject, k: String, max: Int): String? =
        o.optString(k, "").trim().takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c < ' ' } }

    /** As fichas de fora que passam na conferência (as outras são ignoradas, uma a uma). Null = arquivo inteiro inválido. */
    fun validarFichas(json: String): JSONObject? {
        if (json.length > MAX_BYTES) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val saida = JSONObject()
        for (p in o.keys()) {
            if (!pacoteValido(p)) continue
            val x = o.optJSONObject(p) ?: continue
            val r = x.optString("r"); if (r !in RISCOS) continue
            val d = texto(x, "d", 300) ?: continue
            val q = texto(x, "q", 300) ?: continue
            val f = JSONObject().put("r", r).put("d", d).put("q", q)
            texto(x, "en", 120)?.let { f.put("en", it) }
            x.optJSONArray("dep")?.let { a ->
                val deps = (0 until a.length()).map { a.optString(it) }.filter(::pacoteValido).take(20)
                if (deps.isNotEmpty()) f.put("dep", JSONArray(deps))
            }
            saida.put(p, f)
        }
        return saida
    }

    /** Junta: o que só existe fora entra; o que existe nos dois fica com o texto do APK e o risco MAIS grave. */
    fun juntar(doApk: JSONObject, deFora: JSONObject): JSONObject {
        val r = JSONObject(doApk.toString())
        for (p in deFora.keys()) {
            val fora = deFora.getJSONObject(p)
            val local = r.optJSONObject(p)
            if (local == null) { r.put(p, fora); continue }
            val grave = maxOf(RISCOS.indexOf(local.optString("r")), RISCOS.indexOf(fora.optString("r")))
            if (grave >= 0) local.put("r", RISCOS[grave])
            if (!local.has("en") && fora.has("en")) local.put("en", fora.getString("en"))
        }
        return r
    }

    /** Itens de limpeza de fora: só os testados, fora do [nunca] e que o código ainda não tem ([jaTem]). */
    fun validarLimpeza(json: String, nunca: Set<String>, jaTem: Set<String>): List<Debloat.Item> {
        if (json.length > MAX_BYTES) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optString("pacote")
            if (!pacoteValido(p) || p in nunca || p in jaTem) return@mapNotNull null
            val nome = texto(o, "nome", 60) ?: return@mapNotNull null
            val perde = texto(o, "perde", 200) ?: return@mapNotNull null
            val testado = texto(o, "testadoEm", 40) ?: return@mapNotNull null
            Debloat.Item(p, nome, perde, testado, perdeEn = texto(o, "perdeEn", 200))
        }.distinctBy { it.pacote }.take(100)
    }

    /** Hora de buscar de novo? Uma vez a cada 7 dias (relógio voltando para trás também conta como "hora"). */
    fun deveBuscar(ultima: Long, agora: Long) = agora - ultima !in 0 until 7 * 24 * 3_600_000L
}

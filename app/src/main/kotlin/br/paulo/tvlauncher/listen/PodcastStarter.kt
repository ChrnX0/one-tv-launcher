package br.paulo.tvlauncher.listen

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.player.PlayerLogos
import java.util.Locale
import org.json.JSONObject

/**
 * Podcasts que já vêm no app (18/11/2026, Paulo: "queria que viesse já carregado com algumas").
 *
 * A lista é feita à mão em `assets/podcasts/starter.json`, separada por idioma. Cada feed foi aberto de verdade antes
 * de entrar (a data fica em "checked"): tem episódio recente, áudio e capa de até 2 MB (o limite do [PlayerLogos]).
 * Por enquanto: português, inglês e espanhol. Outro idioma cai no inglês.
 *
 * Dois usos:
 *  - a prateleira "Sugestões" na tela de Podcasts (só os que a pessoa ainda não segue; OK segue);
 *  - na primeira vez que a tela abre com menos de [MIN_JA_SEGUE] podcasts, o app segue sozinho os marcados
 *    "follow" (4 por idioma, nenhum explícito). Isso acontece uma vez só ([CHAVE]); sem internet, tenta de novo na
 *    próxima abertura.
 * Por que uma lista nossa: a busca (Podcast Index) depende do servidor e de licença ([PodcastBusca]), e as listas da
 * Apple e do Spotify só servem de referência. Lógica pura: PodcastStarterTest.
 */
object PodcastStarter {
    private const val TAG = "TvLauncher.Podcast"
    const val ARQUIVO = "podcasts/starter.json"
    /** Quem já segue isto ou mais não ganha podcast nenhum sozinho. */
    const val MIN_JA_SEGUE = 3
    /** Idioma sem lista própria usa este. */
    const val RESERVA = "en"
    private const val PREFS = "podcast_starter"
    private const val CHAVE = "seguiu_v1"

    data class Sugestao(val url: String, val titulo: String, val autor: String, val capa: String,
                        val explicito: Boolean, val seguir: Boolean)

    // ---------------------------------------------------------------- puro

    /** Lê o arquivo: idioma -> lista, na ordem do arquivo. Item sem endereço http(s) ou sem nome fica de fora. */
    fun ler(json: String?): Map<String, List<Sugestao>> = runCatching {
        val l = JSONObject(json ?: return emptyMap()).optJSONObject("languages") ?: return emptyMap()
        l.keys().asSequence().associate { idioma ->
            val a = l.optJSONArray(idioma)
            idioma.lowercase(Locale.ROOT) to (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val o = a!!.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").trim()
                val t = o.optString("title").trim()
                if (!PodcastFeed.urlValida(url) || t.isEmpty()) null
                else Sugestao(url, t, o.optString("author").trim(), o.optString("image").trim().takeIf { PodcastFeed.urlValida(it) }.orEmpty(),
                    o.optBoolean("explicit"), o.optBoolean("follow"))
            }.distinctBy { it.url }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    /** A lista do idioma da TV: "pt-br" se existir, senão "pt", senão a [RESERVA]. */
    fun doIdioma(mapa: Map<String, List<Sugestao>>, local: Locale): List<Sugestao> {
        val lingua = local.language.lowercase(Locale.ROOT)
        val pais = local.country.lowercase(Locale.ROOT)
        return listOf("$lingua-$pais", lingua, RESERVA).firstNotNullOfOrNull { mapa[it] }.orEmpty()
    }

    /** O que entra na prateleira: o que ainda não segue; explícito some com o modo infantil ou o controle dos pais. */
    fun paraMostrar(lista: List<Sugestao>, seguidos: Set<String>, protegido: Boolean) =
        lista.filter { it.url !in seguidos && !(protegido && it.explicito) }

    /** O que seguir sozinho na primeira vez (nunca explícito); nada se já fez ou se a pessoa já segue [MIN_JA_SEGUE] ou mais. */
    fun paraSeguir(lista: List<Sugestao>, seguidos: Set<String>, jaFez: Boolean): List<Sugestao> =
        if (jaFez || seguidos.size >= MIN_JA_SEGUE) emptyList()
        else lista.filter { it.seguir && it.url !in seguidos && !it.explicito }

    // ---------------------------------------------------------------- app

    @Volatile private var cache: Map<String, List<Sugestao>>? = null

    private fun mapa(ctx: Context): Map<String, List<Sugestao>> = cache ?: ler(runCatching {
        ctx.assets.open(ARQUIVO).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()).also { cache = it }

    /** As sugestões do idioma da TV. */
    fun lista(ctx: Context): List<Sugestao> = doIdioma(mapa(ctx), Locale.getDefault())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun jaFez(ctx: Context) = prefs(ctx).getBoolean(CHAVE, false)
    fun marcarFeito(ctx: Context) { prefs(ctx).edit().putBoolean(CHAVE, true).apply() }

    /**
     * Na primeira abertura, segue os sugeridos do idioma. Fora da thread da tela (rede). Devolve quantos seguiu.
     * Marca feito antes de começar (a tela pode abrir de novo no meio); se nenhum deu certo (sem internet), desmarca
     * para tentar na próxima vez.
     */
    fun seguirNaPrimeiraVez(ctx: Context, seguidos: Set<String>): Int {
        val fila = paraSeguir(lista(ctx), seguidos, jaFez(ctx))
        if (fila.isEmpty()) {
            if (!jaFez(ctx) && seguidos.size >= MIN_JA_SEGUE) marcarFeito(ctx)
            return 0
        }
        marcarFeito(ctx)
        val n = fila.count { s -> Podcasts.adicionar(ctx, s.url).onFailure { Log.w(TAG, "sugerido ${s.titulo}: ${it.message}") }.isSuccess }
        if (n == 0) prefs(ctx).edit().putBoolean(CHAVE, false).apply()
        return n
    }
}

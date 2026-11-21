package br.paulo.tvlauncher.home

import android.content.Context
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.Vod
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevação 7.1 (17/11/2026): biblioteca única. O mesmo filme pode estar na lista (IPTV), numa pasta do PC, no
 * Jellyfin/Plex, numa nuvem ou num addon; na busca ele aparece UMA vez, com as fontes ao lado e a melhor escolhida.
 *
 * Quem é o mesmo título (nesta ordem):
 *   1. mesmo id do TMDB, ou mesmo id do IMDb;
 *   2. mesmo nome ([Idioma.chaveTitulo]: ignora marca de dublado/legendado, acento e pontuação), mesmo tipo (filme ou
 *      série) e ano igual ou com 1 de diferença (lançamento em outro país);
 *   3. sem ano: só entra num grupo quando há UM grupo com esse nome. "Aladdin" sem ano, com Aladdin 1992 e 2019 na
 *      biblioteca, fica sozinho (melhor uma linha a mais que juntar dois filmes diferentes).
 *
 * A melhor fonte (a primeira de [Titulo.fontes]): na versão que a pessoa prefere (dublado/legendado), a de maior
 * qualidade; empatando, a de casa antes da internet (pasta, servidor da casa, nuvem, lista, addon), porque toca sem
 * depender da internet e sem anúncio. Lógica pura (BibliotecaUnicaTest); o índice das fontes que a TV só vê ao
 * navegar (pastas, servidores, addons) fica em [BibliotecaIndice].
 */
object BibliotecaUnica {
    /** Extra das telas Addons e Na rede: abrem já procurando este título. */
    const val EXTRA_BUSCA = "busca"

    enum class Origem(val peso: Int, val chave: String) {
        PASTA(0, "pasta"), SERVIDOR(1, "servidor"), NUVEM(2, "nuvem"), LISTA(3, "lista"), ADDON(4, "addon");
        companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } }
    }

    data class Fonte(
        val origem: Origem,
        /** De onde, para a pessoa: o nome da conta ("PC do escritório", "Minha lista", "Cinemeta"). */
        val onde: String,
        val titulo: String,
        val ano: Int?,
        val serie: Boolean,
        val tmdb: Int? = null,
        val imdb: String = "",
        /** Altura do vídeo, quando o nome diz (2160, 1080, 720, 480); 0 = não sei. */
        val qualidade: Int = 0,
        val versao: Idioma.Versao = Idioma.Versao.DESCONHECIDA,
        /** O que a tela usa para abrir (endereço do arquivo, nome no catálogo, id do addon). Nunca leva senha. */
        val abrir: String = "",
    )

    data class Titulo(val nome: String, val ano: Int?, val serie: Boolean, val fontes: List<Fonte>) {
        val melhor: Fonte get() = fontes.first()
        val tmdb: Int? get() = fontes.firstNotNullOfOrNull { it.tmdb }
    }

    private val reQualidade = Regex("""(?<![0-9])(2160|1440|1080|720|576|480|360)[pi](?![0-9a-z])|(?<![0-9a-z])(4k|uhd)(?![0-9a-z])""", RegexOption.IGNORE_CASE)

    /** A qualidade que o nome (ou a categoria) diz: "1080p" -> 1080, "4K"/"UHD" -> 2160, "FHD" -> 1080, "HD" -> 720. */
    fun qualidadeDoNome(texto: String): Int {
        reQualidade.find(texto)?.let { m -> return m.groupValues[1].toIntOrNull() ?: 2160 }
        val t = " " + texto.lowercase().replace(Regex("""[^a-z0-9]+"""), " ") + " "
        return when {
            " fhd " in t || " fullhd " in t || " full hd " in t -> 1080
            " hd " in t -> 720
            " sd " in t -> 480
            else -> 0
        }
    }

    private fun anosCasam(a: Int?, b: Int?) = a == null || b == null || kotlin.math.abs(a - b) <= 1

    /**
     * Junta as fontes em títulos únicos. [preferida]: a versão que a pessoa quer (DUBLADA/LEGENDADA). A ordem dos
     * títulos segue a primeira aparição de cada um em [fontes] (quem chama já mandou na ordem que importa).
     */
    fun juntar(fontes: List<Fonte>, preferida: Idioma.Versao = Idioma.Versao.DUBLADA): List<Titulo> {
        // Grupos: índice -> lista de fontes. Mapas para achar o grupo de cada identidade.
        val grupos = ArrayList<MutableList<Fonte>>()
        val porTmdb = HashMap<String, Int>()
        val porImdb = HashMap<String, Int>()
        val porNome = HashMap<String, MutableList<Int>>()   // "s|nome" -> grupos com esse nome (anos diferentes)
        val anoDo = ArrayList<Int?>()
        fun chaveNome(f: Fonte) = (if (f.serie) "s|" else "f|") + Idioma.chaveTitulo(f.titulo)
        fun registrar(g: Int, f: Fonte) {
            grupos[g].add(f)
            f.tmdb?.let { porTmdb[(if (f.serie) "s" else "f") + it] = g }
            if (f.imdb.isNotBlank()) porImdb[f.imdb] = g
            if (anoDo[g] == null && f.ano != null) anoDo[g] = f.ano
            val lista = porNome.getOrPut(chaveNome(f)) { ArrayList() }
            if (g !in lista) lista += g
        }
        fun novo(f: Fonte) { grupos.add(ArrayList()); anoDo.add(null); registrar(grupos.lastIndex, f) }
        val semAno = ArrayList<Fonte>()
        for (f in fontes) {
            if (Idioma.chaveTitulo(f.titulo).isEmpty()) continue
            val porId = f.tmdb?.let { porTmdb[(if (f.serie) "s" else "f") + it] } ?: f.imdb.takeIf { it.isNotBlank() }?.let { porImdb[it] }
            if (porId != null) { registrar(porId, f); continue }
            if (f.ano == null && f.tmdb == null && f.imdb.isBlank()) { semAno += f; continue }
            val candidatos = porNome[chaveNome(f)].orEmpty().filter { g ->
                anosCasam(anoDo[g], f.ano) &&
                    // Dois ids diferentes do TMDB nunca se juntam pelo nome (remake com o mesmo nome e ano perto).
                    (f.tmdb == null || grupos[g].all { it.tmdb == null || it.tmdb == f.tmdb })
            }
            if (candidatos.isNotEmpty()) registrar(candidatos.first(), f) else novo(f)
        }
        // Sem ano: só junta quando o nome aponta para UM grupo.
        for (f in semAno) {
            val candidatos = porNome[chaveNome(f)].orEmpty()
            if (candidatos.size == 1) registrar(candidatos[0], f) else novo(f)
        }
        return grupos.map { g ->
            val ordem = g.sortedWith(compareBy<Fonte>(
                { if (it.versao == preferida) 0 else if (it.versao == Idioma.Versao.DESCONHECIDA) 1 else 2 },
                { -it.qualidade }, { it.origem.peso }))
            val nome = (g.firstOrNull { it.origem == Origem.LISTA } ?: g.first()).titulo.let { Vod.nomeLimpo(it) }
            Titulo(nome, g.firstNotNullOfOrNull { it.ano }, g.first().serie, ordem.distinctBy { listOf(it.origem, it.onde, it.abrir) })
        }
    }
}

/**
 * O que a TV viu ao navegar pelas fontes que não ficam guardadas inteiras (pastas, servidores da casa, nuvens, addons),
 * para a busca única achar ali também. Fica só na TV (pasta do player), até [MAXIMO] títulos, o mais recente primeiro.
 * Guarda título, ano, ids e o endereço do arquivo SEM senha (Pastas usa o id da conta; endereço com usuário ou chave
 * não é guardado).
 */
object BibliotecaIndice {
    const val MAXIMO = 4000
    private const val ARQUIVO = "biblioteca.json"
    private val trava = Any()

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), ARQUIVO)

    /** Endereço que pode ser guardado: sem usuário/senha e sem token/chave na consulta. */
    fun enderecoGuardavel(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return true
        val semEsquema = u.substringAfter("://", u)
        if ('@' in semEsquema.substringBefore('/')) return false
        val consulta = u.substringAfter('?', "").lowercase()
        return listOf("token", "key", "api_key", "apikey", "password", "senha", "auth", "sig").none { "$it=" in consulta }
    }

    fun paraJson(f: BibliotecaUnica.Fonte): JSONObject = JSONObject().apply {
        put("o", f.origem.chave); put("w", f.onde); put("t", f.titulo); put("s", f.serie)
        f.ano?.let { put("a", it) }; f.tmdb?.let { put("tm", it) }
        if (f.imdb.isNotBlank()) put("im", f.imdb)
        if (f.qualidade > 0) put("q", f.qualidade)
        if (f.versao != Idioma.Versao.DESCONHECIDA) put("v", f.versao.name)
        if (f.abrir.isNotBlank() && enderecoGuardavel(f.abrir)) put("u", f.abrir)
    }

    fun deJson(o: JSONObject): BibliotecaUnica.Fonte? {
        val origem = BibliotecaUnica.Origem.de(o.optString("o")) ?: return null
        val t = o.optString("t").takeIf { it.isNotBlank() } ?: return null
        return BibliotecaUnica.Fonte(origem, o.optString("w"), t, o.optInt("a").takeIf { it > 0 }, o.optBoolean("s"),
            o.optInt("tm").takeIf { it > 0 }, o.optString("im"), o.optInt("q"),
            runCatching { Idioma.Versao.valueOf(o.optString("v")) }.getOrDefault(Idioma.Versao.DESCONHECIDA), o.optString("u"))
    }

    /** Junta [novas] à lista guardada: a mesma fonte (origem, onde, título, endereço) é trocada e vai para o topo. */
    fun mesclar(guardadas: List<BibliotecaUnica.Fonte>, novas: List<BibliotecaUnica.Fonte>, maximo: Int = MAXIMO): List<BibliotecaUnica.Fonte> {
        fun id(f: BibliotecaUnica.Fonte) = listOf(f.origem, f.onde, f.titulo, f.abrir, f.serie)
        val ids = novas.map { id(it) }.toHashSet()
        return (novas.distinctBy { id(it) } + guardadas.filter { id(it) !in ids }).take(maximo)
    }

    fun todas(ctx: Context): List<BibliotecaUnica.Fonte> = synchronized(trava) {
        runCatching {
            val a = JSONArray(arquivo(ctx).readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::deJson) }
        }.getOrDefault(emptyList())
    }

    fun anotar(ctx: Context, novas: List<BibliotecaUnica.Fonte>) {
        if (novas.isEmpty()) return
        synchronized(trava) {
            runCatching {
                val lista = mesclar(todas(ctx), novas)
                val tmp = File(PlayerArmazem.pasta(ctx), "$ARQUIVO.tmp")
                tmp.writeText(JSONArray().apply { lista.forEach { put(paraJson(it)) } }.toString())
                tmp.renameTo(arquivo(ctx))
            }
        }
    }
}

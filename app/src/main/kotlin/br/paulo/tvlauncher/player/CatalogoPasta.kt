package br.paulo.tvlauncher.player

import android.content.Context
import br.paulo.tvlauncher.input.LeitorNomes

/**
 * Elevação 8.2 (17/11/2026): a pasta vira catálogo. Os vídeos de uma pasta (PC, NAS, pendrive, WebDAV, DLNA, link)
 * ganham título limpo, temporada e episódio pelo [LeitorNomes], e, com a pessoa de acordo, pôster e sinopse do TMDB
 * (o mesmo cache das fichas do player, [InfoExternaCache]).
 *
 * Privacidade: nomes de arquivo podem ser pessoais ("Aniversário da vó.mp4"). Por isso a busca no TMDB nasce
 * DESLIGADA e liga por uma linha na própria pasta, que diz o que sai da TV: só o título lido (nunca o caminho, a pasta
 * ou o servidor). Ler o nome e arrumar a lista não manda nada para fora e vale sempre.
 *
 * Casamento errado se corrige na TV (segurar OK no vídeo): "não é este" (o TMDB dá o próximo candidato, mesmo
 * mecanismo de [InfoExternaCache.recusar]) ou "escrever o nome certo" (guardado aqui, vai no backup).
 */
object CatalogoPasta {
    private const val PREFS = "catalogo_pasta"
    private const val LIGADO = "ligado"
    private const val NOME = "nome:"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ligado(ctx: Context): Boolean = prefs(ctx).getBoolean(LIGADO, false)
    fun definir(ctx: Context, ligado: Boolean) { prefs(ctx).edit().putBoolean(LIGADO, ligado).apply() }

    /** A chave de um título lido (sem a correção): "pasta:s:breaking bad|0", "pasta:f:duna|2021". */
    fun chave(l: LeitorNomes.Lido): String = "pasta:" + (if (l.serie) "s:" else "f:") + InfoExterna.chaveNomeAno(l.nome, l.ano)

    fun nomeCorrigido(ctx: Context, l: LeitorNomes.Lido): String? = prefs(ctx).getString(NOME + chave(l), null)?.takeIf { it.isNotBlank() }

    /** Guarda o nome certo digitado pela pessoa; vazio volta ao automático. */
    fun corrigirNome(ctx: Context, l: LeitorNomes.Lido, nome: String) {
        val p = prefs(ctx).edit()
        if (nome.isBlank()) p.remove(NOME + chave(l)) else p.putString(NOME + chave(l), nome.trim().take(120))
        p.apply()
    }

    /** O que vai para a busca: o nome (corrigido, se houver) e o ano; e a chave do cache para esse par. */
    data class Busca(val nome: String, val ano: Int?, val serie: Boolean, val chave: String)

    fun busca(l: LeitorNomes.Lido, corrigido: String?): Busca? {
        if (l.extra) return null
        val lidoCorrigido = corrigido?.let { c -> LeitorNomes.ler(c).let { it.copy(nome = it.nome.ifBlank { c }) } }
        val nome = (lidoCorrigido?.nome ?: l.nome).trim()
        if (nome.length < 2) return null
        val ano = lidoCorrigido?.ano ?: if (corrigido != null) null else l.ano
        return Busca(nome, ano, l.serie, "pasta:" + (if (l.serie) "s:" else "f:") + InfoExterna.chaveNomeAno(nome, ano))
    }

    /** Vale a pena mostrar o nome lido no lugar do nome do arquivo? (achou ano, episódio, ou limpou sujeira). */
    fun leuAlgo(arquivo: String, l: LeitorNomes.Lido): Boolean {
        if (l.extra || l.nome.isBlank()) return false
        val semExt = arquivo.substringBeforeLast('.', arquivo)
        return l.ano != null || l.serie || l.nome != semExt
    }

    /**
     * Ordem de catálogo: pastas primeiro (como vieram); depois os vídeos lidos por título, temporada, episódio e data;
     * o que não foi lido mantém a ordem do servidor, no fim. [lido] devolve null para o que não é vídeo lido.
     */
    fun <T> ordenar(itens: List<T>, pasta: (T) -> Boolean, lido: (T) -> LeitorNomes.Lido?): List<T> {
        val pastas = itens.filter(pasta)
        val resto = itens.filterNot(pasta)
        // A chave do título é calculada uma vez por item (no comparador era a cada comparação: ~120 mil com 5000 vídeos).
        val chaves = resto.mapNotNull { e -> lido(e)?.let { l -> e to l } }.map { (e, l) -> Triple(e, l, Idioma.chaveTitulo(l.nome)) }
        val lidos = chaves.sortedWith(compareBy<Triple<T, LeitorNomes.Lido, String>>(
            { it.third }, { it.second.temporada ?: -1 }, { it.second.episodio ?: -1 }, { it.second.data ?: "" })).map { it.first }
        return pastas + lidos + resto.filter { lido(it) == null }
    }
}

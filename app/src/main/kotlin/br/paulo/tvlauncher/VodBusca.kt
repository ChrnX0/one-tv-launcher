package br.paulo.tvlauncher

/**
 * Filmes e Séries no catálogo de ações (14/11/2026, ideia 60): "põe Duna", "continua o que eu estava vendo", "põe o
 * próximo episódio", "o que eu estava vendo?". A parte pura (sem Android, VodBuscaTest): achar o título nas listas da
 * TV e escolher o que tocar. A parte Android (ler o disco, baixar a ficha da série, abrir o player) é o [VodTv].
 *
 * As listas que chegam aqui JÁ vêm só com as categorias visíveis (VodFiltro: adultas, escondidas e controle parental,
 * as mesmas travas das telas Filmes e Séries). Título bloqueado vira "não achei": não dizemos que ele existe.
 */
object VodBusca {
    const val MAX_RECENTES = 5

    /** O que abrir no player ([VodActivity]). [episodio] e [serieChave] só nas séries (para gravar o último aberto). */
    class Alvo(
        val tipo: String, val fonteId: String, val id: String, val extensao: String, val titulo: String,
        val doComeco: Boolean, val serieChave: String = "", val episodio: Episodio? = null,
    )

    /** O que a TV tem. [ficha] baixa a lista de episódios de uma série (rede): só é chamada quando precisa. */
    class Dados(
        val filmes: List<Filme>, val series: List<Serie>, val posicoes: Map<String, Posicao>,
        val ultimos: Map<String, UltimoEpisodio>, val ficha: (Serie) -> FichaSerie?,
    )

    sealed class Item(val quando: Long) {
        class DeFilme(val filme: Filme, quando: Long) : Item(quando)
        class DeSerie(val serie: Serie, quando: Long) : Item(quando)
    }

    /**
     * O título que mais combina com [termo] (todas as palavras, sem acento; a regra da busca das telas). Desempate:
     * nome igual, depois nome que começa com o termo, depois o visto mais recentemente, depois o nome mais curto.
     */
    fun achar(termo: String, d: Dados, soSeries: Boolean = false): Item? {
        val t = PlayerFiltro.normalizar(termo)
        if (t.isEmpty()) return null
        val itens = (if (soSeries) emptyList() else Vod.buscar(d.filmes, termo).map { Item.DeFilme(it, d.posicoes[it.chave]?.quando ?: 0) }) +
            Series.buscar(d.series, termo).map { Item.DeSerie(it, d.ultimos[it.chave]?.quando ?: 0) }
        fun nome(i: Item) = when (i) { is Item.DeFilme -> i.filme.nome; is Item.DeSerie -> i.serie.nome }
        fun nivel(i: Item): Int { val n = Vod.chaveNome(nome(i)); return if (n == t) 0 else if (n.startsWith(t)) 1 else 2 }
        return itens.minWithOrNull(compareBy<Item>({ nivel(it) }, { -it.quando }, { Vod.nomeLimpo(nome(it)).length }))
    }

    /** Os vistos por último: filmes com 1 min ou mais (ou até o fim) e séries com episódio aberto, do mais novo ao mais velho. */
    fun recentes(d: Dados, max: Int = MAX_RECENTES): List<Item> {
        val filmes = d.filmes.mapNotNull { f -> d.posicoes[f.chave]?.takeIf { Vod.assistido(it) }?.let { Item.DeFilme(f, it.quando) } }
        val series = d.series.mapNotNull { s -> d.ultimos[s.chave]?.let { Item.DeSerie(s, it.quando) } }
        return (filmes + series).sortedByDescending { it.quando }.take(max)
    }

    /** "Duna" ou "Ruptura (T2 E3)": uma linha da resposta do "o que eu estava vendo". */
    fun rotulo(i: Item, d: Dados): String = when (i) {
        is Item.DeFilme -> Vod.nomeLimpo(i.filme.nome)
        is Item.DeSerie -> Vod.nomeLimpo(i.serie.nome) + (d.ultimos[i.serie.chave]?.let { " (T${it.temporada} E${it.numero})" } ?: "")
    }

    fun alvoFilme(f: Filme): Alvo =
        Alvo(VodActivity.TIPO_FILME, f.fonteId, f.id, f.extensao, Vod.nomeLimpo(f.nome), doComeco = false)

    fun alvoEpisodio(s: Serie, ep: Episodio, doComeco: Boolean): Alvo =
        Alvo(VodActivity.TIPO_EPISODIO, s.fonteId, ep.id, ep.extensao, "${Vod.nomeLimpo(s.nome)} · ${ep.rotulo}", doComeco, s.chave, ep)

    /**
     * Troca as ações de Filmes e Séries do plano pelas resolvidas (com [Alvo]) ou as recusa com o motivo. [frase] monta
     * o texto do "o que eu estava vendo" a partir dos rótulos (vazio = nada visto); é do lado Android (idioma da TV).
     */
    fun resolver(plano: ComandosIa.Plano, d: Dados?, frase: (List<String>) -> String): ComandosIa.Plano {
        if (plano.acoes.none { ehVod(it) }) return plano
        val acoes = ArrayList<ComandosIa.Acao>()
        val recusadas = ArrayList(plano.recusadas)
        for (a in plano.acoes) {
            if (!ehVod(a)) { acoes += a; continue }
            val tipo = when (a) {
                is ComandosIa.Acao.TocarTitulo -> "tocar_titulo"; is ComandosIa.Acao.Continuar -> "continuar"
                is ComandosIa.Acao.ProximoEpisodio -> "proximo_episodio"; else -> "o_que_estava_vendo"
            }
            if (d == null || (d.filmes.isEmpty() && d.series.isEmpty())) {
                recusadas += "$tipo: a TV ainda não tem a lista de Filmes e Séries guardada (abra Filmes ou Séries uma vez)"; continue
            }
            when (a) {
                is ComandosIa.Acao.OQueEstavaVendo -> acoes += ComandosIa.Acao.OQueEstavaVendo(frase(recentes(d).map { rotulo(it, d) }))
                is ComandosIa.Acao.TocarTitulo -> {
                    val i = achar(a.pedido, d) ?: run { recusadas += "$tipo: não achei \"${a.pedido.take(40)}\" em Filmes e Séries"; null }
                    if (i != null) continuarItem(i, d)?.let { acoes += ComandosIa.Acao.TocarTitulo(a.pedido, it) }
                        ?: run { recusadas += "$tipo: a série não abriu (sem internet?)" }
                }
                is ComandosIa.Acao.Continuar -> {
                    val i = recentes(d, 1).firstOrNull()
                    if (i == null) recusadas += "$tipo: nada começado em Filmes e Séries"
                    else continuarItem(i, d)?.let { acoes += ComandosIa.Acao.Continuar(it) } ?: run { recusadas += "$tipo: a série não abriu (sem internet?)" }
                }
                is ComandosIa.Acao.ProximoEpisodio -> {
                    val s = if (a.pedido.isEmpty()) recentes(d, Int.MAX_VALUE).filterIsInstance<Item.DeSerie>().firstOrNull()?.serie
                        else (achar(a.pedido, d, soSeries = true) as? Item.DeSerie)?.serie
                    if (s == null) { recusadas += if (a.pedido.isEmpty()) "$tipo: nenhuma série vista ainda" else "$tipo: não achei a série \"${a.pedido.take(40)}\""; continue }
                    val f = d.ficha(s) ?: run { recusadas += "$tipo: a série não abriu (sem internet?)"; null } ?: continue
                    val ep = proximo(f, d.ultimos[s.chave])
                    if (ep == null) recusadas += "$tipo: não há episódio depois do último visto"
                    else acoes += ComandosIa.Acao.ProximoEpisodio(a.pedido, alvoEpisodio(s, ep, doComeco = true))
                }
                else -> {}
            }
        }
        return ComandosIa.Plano(acoes, plano.resposta, recusadas)
    }

    /** O episódio depois do último aberto; sem nenhum aberto, o primeiro. */
    fun proximo(f: FichaSerie, ultimo: UltimoEpisodio?): Episodio? {
        val todos = Series.emOrdem(f)
        if (ultimo == null) return todos.firstOrNull()
        val atual = todos.firstOrNull { it.id == ultimo.episodioId } ?: return null
        return Series.proximo(f, atual)
    }

    /** Filme: de onde parou (o player decide). Série: o que o botão principal da ficha ofereceria ([Series.sugestao]). */
    private fun continuarItem(i: Item, d: Dados): Alvo? = when (i) {
        is Item.DeFilme -> alvoFilme(i.filme)
        is Item.DeSerie -> d.ficha(i.serie)?.let { f -> Series.sugestao(f, i.serie.fonteId, d.ultimos[i.serie.chave], d.posicoes) }
            ?.let { (ep, _) -> alvoEpisodio(i.serie, ep, doComeco = false) }
    }

    private fun ehVod(a: ComandosIa.Acao) = a is ComandosIa.Acao.TocarTitulo || a is ComandosIa.Acao.Continuar ||
        a is ComandosIa.Acao.ProximoEpisodio || a is ComandosIa.Acao.OQueEstavaVendo
}

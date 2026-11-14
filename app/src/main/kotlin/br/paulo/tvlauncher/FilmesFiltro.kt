package br.paulo.tvlauncher

import kotlin.random.Random

/**
 * Dentro de Filmes (ideia 23, 14/11/2026): filtro combinado, coleções pelo nome e sorteio. Só lógica pura (sem Android),
 * testada em FilmesFiltroTest. Quem desenha os menus e roda a conta fora da thread da tela é a [FilmesActivity].
 *
 *  1. Filtro: gênero + época (ano ou década) + nota mínima + "só não vistos", aplicado sobre a categoria escolhida.
 *     O gênero vem do NOME DA CATEGORIA ("Filmes | Ação", "Terror Legendado"): a lista do Xtream não traz gênero por
 *     filme, e baixar a ficha de 30 mil filmes para descobrir seria inviável na TV.
 *  2. Coleção: os filmes com o mesmo nome-base ("Duna (2021)", "Duna: Parte Dois [L]", "Toy Story 3"). Parte da mesma
 *     chave que acha o par dublado x legendado ([Idioma.chaveTitulo]) e corta o subtítulo e o número da sequência.
 *  3. Sorteio: um filme ao acaso entre os da grade (com [Random] injetável), preferindo os Interesses e os não vistos.
 */
object FilmesFiltro {

    /** Gêneros reconhecidos no nome da categoria. Palavra com `*` no fim vale como começo de palavra. */
    enum class Genero(vararg val palavras: String) {
        ACAO("acao", "action"),
        AVENTURA("aventura*", "adventure*"),
        COMEDIA("comedia*", "comedy", "comedies"),
        DRAMA("drama*"),
        TERROR("terror", "horror"),
        SUSPENSE("suspense", "thriller*"),
        ROMANCE("romance*", "romantic*", "romantico*"),
        FICCAO("ficcao", "ficcoes", "sci", "scifi"),
        FANTASIA("fantasia*", "fantasy"),
        ANIMACAO("animacao", "animacoes", "animation*", "anime*", "desenho*"),
        INFANTIL("infantil", "infantis", "kids", "crianca*", "familia*", "family"),
        DOCUMENTARIO("documentario*", "documentary", "documentaries"),
        CRIME("crime*", "policia*"),
        GUERRA("guerra*"),
        FAROESTE("faroeste*", "western*"),
        MUSICAL("musical*", "musicais"),
    }

    /** O filtro em vigor. Tudo nulo/falso = sem filtro. [ano] e [decada] não andam juntos (a época é um ou outro). */
    data class Criterios(
        val genero: Genero? = null,
        val ano: Int? = null,
        val decada: Int? = null,      // 1990 = de 1990 a 1999
        val notaMin: Int? = null,     // 6, 7, 8 (de 0 a 10)
        val naoVistos: Boolean = false,
    ) {
        val ativo: Boolean get() = genero != null || ano != null || decada != null || notaMin != null || naoVistos
    }

    /** Opções do menu "Nota mínima". */
    val NOTAS = listOf(6, 7, 8)

    // ---------------------------------------------------------------- 1. filtro

    private fun palavras(texto: String): List<String> =
        Idioma.semAcento(texto).lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }

    private fun casa(palavra: String, p: String): Boolean =
        if (p.endsWith('*')) palavra.startsWith(p.dropLast(1)) else palavra == p || palavra == p + "s"

    /** Os gêneros que o nome da categoria indica (pode ser mais de um: "Ação e Aventura"). */
    fun generosDoGrupo(grupo: String): Set<Genero> {
        val ps = palavras(grupo)
        if (ps.isEmpty()) return emptySet()
        return Genero.entries.filterTo(LinkedHashSet()) { g -> g.palavras.any { p -> ps.any { casa(it, p) } } }
    }

    /** Gêneros que aparecem nas categorias destes filmes, na ordem do enum (para o menu mostrar só o que existe). */
    fun generosPresentes(filmes: List<Filme>): List<Genero> {
        val achados = HashSet<Genero>()
        val vistos = HashSet<String>()
        for (f in filmes) if (vistos.add(f.grupo)) achados += generosDoGrupo(f.grupo)
        return Genero.entries.filter { it in achados }
    }

    /** Épocas do menu: os anos [anoAtual] e o anterior (se houver filme deles) e as décadas presentes, da mais nova. */
    fun epocasPresentes(filmes: List<Filme>, anoAtual: Int): Pair<List<Int>, List<Int>> {
        val anos = HashSet<Int>(); val decadas = HashSet<Int>()
        for (f in filmes) f.ano?.let { anos += it; decadas += it / 10 * 10 }
        return listOf(anoAtual, anoAtual - 1).filter { it in anos } to decadas.sortedDescending()
    }

    /**
     * Aplica o filtro a [filmes] mantendo a ordem. O gênero de cada categoria é calculado uma vez (são poucas categorias
     * para dezenas de milhares de filmes). Filme sem ano ou sem nota sai quando o filtro pede ano ou nota.
     */
    fun filtrar(filmes: List<Filme>, c: Criterios, posicoes: Map<String, Posicao>): List<Filme> {
        if (!c.ativo) return filmes
        val generoDe = HashMap<String, Boolean>()
        return filmes.filter { f ->
            (c.genero == null || generoDe.getOrPut(f.grupo) { c.genero in generosDoGrupo(f.grupo) }) &&
                (c.ano == null || f.ano == c.ano) &&
                (c.decada == null || (f.ano != null && f.ano / 10 * 10 == c.decada)) &&
                (c.notaMin == null || (f.nota != null && f.nota >= c.notaMin)) &&
                (!c.naoVistos || !Vod.assistido(posicoes[f.chave]))
        }
    }

    // ---------------------------------------------------------------- 2. coleção pelo nome

    private val reSubtitulo = Regex("""\s*(:|\s-\s|\s–\s|\s—\s).*$""")
    private val reParte = Regex("""\s+(parte|part|capitulo|chapter|vol|volume|episodio|episode)\s+[a-z0-9]+$""")
    private val reNumeroFinal = Regex("""^(.+\S)\s+(\d{1,2}|ii|iii|iv|v|vi|vii|viii|ix)$""")

    /**
     * Nome-base da coleção: a chave do par dublado x legendado ([Idioma.chaveTitulo]) sem o subtítulo (depois de ":" ou
     * " - "), sem "Parte 2"/"Capítulo 3" e sem o número da sequência no fim ("Toy Story 3", "Rocky II"). Números de 3 ou
     * mais dígitos ficam ("300", "1917"): são o próprio título.
     */
    fun nomeBase(nome: String): String {
        // O subtítulo é cortado antes da chave (que tira a pontuação); a chave tira ano, marcas e prefixo de país.
        val semMarcas = Vod.nomeLimpo(nome)
        val cortado = semMarcas.replace(reSubtitulo, "").ifBlank { semMarcas }
        var k = Idioma.chaveTitulo(cortado).ifEmpty { Idioma.chaveTitulo(nome) }
        k = k.replace(reParte, "")
        reNumeroFinal.matchEntire(k)?.let { k = it.groupValues[1] }
        return k.trim()
    }

    /** Os filmes da mesma coleção que [alvo] (ele incluído), do mais antigo ao mais novo; sem ano vão para o fim. */
    fun colecao(filmes: List<Filme>, alvo: Filme): List<Filme> {
        val base = nomeBase(alvo.nome)
        if (base.isEmpty()) return listOf(alvo)
        return filmes.filter { it === alvo || nomeBase(it.nome) == base }
            .sortedWith(compareBy<Filme>({ it.ano ?: Int.MAX_VALUE }, { Vod.chaveNome(it.nome) }))
    }

    // ---------------------------------------------------------------- 3. sorteio

    /**
     * Um filme ao acaso entre [grade]. Se algum deles está nos Interesses, o sorteio fica só entre esses; depois, se
     * sobra algum não visto, só entre os não vistos (sortear o que já se viu não ajuda a escolher). Vazio = null.
     */
    fun sortear(grade: List<Filme>, interesses: Set<String>, posicoes: Map<String, Posicao>, random: Random = Random.Default): Filme? {
        if (grade.isEmpty()) return null
        val base = grade.filter { it.chave in interesses }.ifEmpty { grade }
        val novos = base.filter { !Vod.assistido(posicoes[it.chave]) }.ifEmpty { base }
        return novos[random.nextInt(novos.size)]
    }
}

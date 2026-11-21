package br.paulo.tvlauncher.listen

/**
 * Os assuntos dos podcasts (pedido do Paulo, 21/11/2026: "o player de podcast poderia ter um filtro de assunto").
 *
 * Porquê: quem abre Podcasts quer "algo de ciência" ou "notícias agora", e a tela só separava por estado (Continuar,
 * Novos, Meus). Para quê: achar o que ouvir pelo tema, nos seus e nos Em alta, sem digitar no controle.
 * Como: cada podcast já diz o assunto dele no feed (itunes:category, lido por [PodcastFeed]) e o Podcast Index devolve o
 * mesmo nome nas buscas ([PodcastBusca]); aqui os ~110 nomes da lista da Apple viram poucos assuntos grandes, com letra
 * grande, e o mesmo assunto pede o "Em alta" daquele tema ao servidor (cat=). Puro: PodcastAssuntosTest.
 */
object PodcastAssuntos {
    /** [id] estável (prefs, telas), [categorias] = nomes do itunes:category / Podcast Index que entram nele. */
    enum class Assunto(val id: String, val categorias: List<String>) {
        NOTICIAS("news", listOf("News", "Daily News", "News Commentary", "Politics", "Government")),
        HUMOR("comedy", listOf("Comedy", "Comedy Interviews", "Improv", "Stand-Up")),
        CIENCIA("science", listOf("Science", "Astronomy", "Chemistry", "Earth Sciences", "Life Sciences", "Mathematics",
            "Natural Sciences", "Nature", "Physics", "Social Sciences")),
        TECNOLOGIA("technology", listOf("Technology", "Tech News")),
        HISTORIA("history", listOf("History")),
        NEGOCIOS("business", listOf("Business", "Careers", "Entrepreneurship", "Investing", "Management", "Marketing",
            "Non-Profit", "Business News")),
        SAUDE("health", listOf("Health & Fitness", "Health", "Alternative Health", "Fitness", "Medicine", "Mental Health",
            "Nutrition", "Sexuality")),
        ESPORTES("sports", listOf("Sports", "Baseball", "Basketball", "Cricket", "Fantasy Sports", "Football", "Golf",
            "Hockey", "Rugby", "Running", "Soccer", "Swimming", "Tennis", "Volleyball", "Wilderness", "Wrestling", "Sports News")),
        CULTURA("culture", listOf("Society & Culture", "Society", "Culture", "Documentary", "Personal Journals", "Philosophy",
            "Places & Travel", "Relationships", "Arts", "Books", "Design", "Fashion & Beauty", "Food", "Performing Arts",
            "Visual Arts")),
        CRIMES("true-crime", listOf("True Crime")),
        TV_FILMES("tv-film", listOf("TV & Film", "After Shows", "Film History", "Film Interviews", "Film Reviews", "TV Reviews",
            "Entertainment News")),
        MUSICA("music", listOf("Music", "Music Commentary", "Music History", "Music Interviews")),
        EDUCACAO("education", listOf("Education", "Courses", "How To", "Language Learning", "Self-Improvement")),
        INFANTIL("kids", listOf("Kids & Family", "Kids", "Family", "Education for Kids", "Parenting", "Pets & Animals", "Stories for Kids")),
        FE("religion", listOf("Religion & Spirituality", "Religion", "Spirituality", "Buddhism", "Christianity", "Hinduism",
            "Islam", "Judaism")),
        LAZER("leisure", listOf("Leisure", "Games", "Video Games", "Hobbies", "Home & Garden", "Automotive", "Aviation",
            "Crafts", "Animation & Manga", "Fiction", "Drama", "Comedy Fiction", "Science Fiction")),
        ;

        companion object {
            fun deId(id: String?): Assunto? = entries.firstOrNull { it.id == id }
        }
    }

    private val porNome: Map<String, Assunto> = HashMap<String, Assunto>().apply {
        // Nome repetido em dois assuntos: vale o primeiro da lista (o mais específico vem antes).
        for (a in Assunto.entries) for (c in a.categorias) putIfAbsent(chave(c), a)
    }

    /** "Society &amp; Culture", " society & culture " e "Society and Culture" dão a mesma chave. */
    private fun chave(nome: String) = nome.lowercase().replace("&amp;", "&").replace(" and ", " & ").replace(Regex("\\s+"), " ").trim()

    /** Os assuntos de um podcast pelas categorias que ele declara (na ordem, sem repetir). Desconhecido não entra. */
    fun de(categorias: List<String>): List<Assunto> = categorias.mapNotNull { porNome[chave(it)] }.distinct()

    fun tem(categorias: List<String>, a: Assunto) = a in de(categorias)

    /** O "cat=" do Em alta: os nomes principais do assunto (o servidor aceita até 5). */
    fun paraServidor(a: Assunto): String = a.categorias.take(5).joinToString(",")
}

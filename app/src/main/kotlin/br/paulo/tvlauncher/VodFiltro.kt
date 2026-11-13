package br.paulo.tvlauncher

/**
 * Categorias ocultas em Filmes e Séries (auditoria de 10/11/2026). Antes só a TV ao vivo escondia as categorias adultas
 * e respeitava o controle parental: uma categoria "XXX Adultos" do VOD aparecia livre, sem PIN, na lista, na grade e na
 * busca. Agora as telas de Filmes e Séries usam a MESMA regra do player (PlayerFiltro.ocultasEfetivas) + as categorias
 * bloqueadas no controle parental, e só mostram tudo depois do PIN do painel, como o player faz.
 *
 * Só lógica pura (sem Android), testada em VodFiltroTest.
 */
object VodFiltro {
    /**
     * Nomes de categoria que ficam escondidos: as que o usuário escondeu no player + as adultas que ele não liberou
     * (mesma regra da TV ao vivo) + as categorias bloqueadas no controle parental, quando ele está ligado, + no modo
     * infantil, todas as que não são infantis.
     * Os canais bloqueados um a um não entram: são ids de canal, não valem para filme ou série.
     */
    fun ocultas(grupos: Collection<String>, escondidas: Set<String>, liberadas: Set<String>, parental: ControleParental.Regras): Set<String> {
        val presentes = grupos.asSequence().map { it.trim() }.toSet()
        val adultas = presentes.filter { it.isNotEmpty() && PlayerFiltro.ehAdulto(it) }.toSet()
        val base = escondidas + (adultas - liberadas)
        // A mesma regra da TV ao vivo (ControleParental.grupoBloqueado): categoria bloqueada ou, no modo infantil, não infantil.
        val todas = base + presentes.filter { ControleParental.grupoBloqueado(it, parental) }
        // Só as que existem neste catálogo: as escondidas e as do controle parental vêm da TV ao vivo, e uma categoria
        // que nem existe aqui faria aparecer à toa o "Mostrar categorias ocultas".
        return todas.intersect(presentes)
    }

    /** Tira da lista o que é de categoria oculta. `grupo` diz a categoria de cada item (Filme.grupo, Serie.grupo). */
    fun <T> visiveis(lista: List<T>, ocultas: Set<String>, grupo: (T) -> String): List<T> =
        if (ocultas.isEmpty()) lista else lista.filter { grupo(it).trim() !in ocultas }
}

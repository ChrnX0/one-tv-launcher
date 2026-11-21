package br.paulo.tvlauncher.player

import android.content.Context
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.entrar

/**
 * O menu do ▼ dos players: filmes, séries, arquivos da rede e TV ao vivo (20/11/2026, Paulo: "bonito, mas complicado
 * de usar"; "tem que ser algo totalmente intuitivo"; "padrão do nosso player").
 *
 * Antes, um ✓ queria dizer três coisas na mesma lista (a faixa escolhida, "sem legenda" e uma chave ligada) e 25 itens
 * vinham juntos. Agora há uma regra só, como nos menus da Netflix, da Apple TV e das Configurações do Google TV:
 * - [Tipo.ESCOLHA]: uma entre várias (áudio, legenda, velocidade). Só aqui aparece o ✓, na que está valendo.
 * - [Tipo.CHAVE]: liga/desliga. Mostra "Ligado"/"Desligado" à direita da linha; o OK troca e a lista fica aberta, no
 *   mesmo item, para a pessoa ver que mudou.
 * - [Tipo.ABRE]: abre outra lista. Mostra o valor de agora e "›" à direita (ex.: Legenda   Desligada ›).
 * - [Tipo.ACAO]: faz algo e fecha (buscar legenda, trocar de versão).
 * VOLTAR numa lista de dentro volta para a de cima ([abrir] com `voltar`).
 */
object PlayerMenu {
    enum class Tipo { ESCOLHA, CHAVE, ABRE, ACAO }

    class Item(val nome: String, val tipo: Tipo, val valor: String? = null, val escolhido: Boolean = false, val acao: () -> Unit)

    fun escolha(nome: String, escolhido: Boolean, acao: () -> Unit) = Item(nome, Tipo.ESCOLHA, escolhido = escolhido, acao = acao)
    fun abre(nome: String, valor: String?, acao: () -> Unit) = Item(nome, Tipo.ABRE, valor, acao = acao)
    fun acao(nome: String, valor: String? = null, acao: () -> Unit) = Item(nome, Tipo.ACAO, valor, acao = acao)
    fun chave(ctx: Context, nome: String, ligado: Boolean, acao: () -> Unit) =
        Item(nome, Tipo.CHAVE, ctx.getString(if (ligado) R.string.pm_ligado else R.string.pm_desligado), acao = acao)

    /** Onde vai o ✓: só nas escolhas que estão valendo (nunca numa chave nem num item que abre outra lista). */
    fun marcadas(itens: List<Item>): List<Int> = itens.indices.filter { itens[it].tipo == Tipo.ESCOLHA && itens[it].escolhido }

    /**
     * O que vai à direita da linha: o valor de agora; "›" quando abre outra lista (como na Apple TV e no Google TV).
     * A escolha não tem valor (o ✓ diz qual vale) e a ação também não (ela só faz).
     */
    fun valorNaLinha(item: Item): String? = when (item.tipo) {
        Tipo.ABRE -> listOfNotNull(item.valor?.takeIf { it.isNotBlank() }?.let(::maiuscula), "›").joinToString(" ")
        Tipo.CHAVE -> item.valor?.let(::maiuscula)
        else -> null
    }

    /** Valor sempre com a 1ª letra maiúscula (na TV saía "português", "padrão" ao lado de "Normal", "Ligado"). */
    fun maiuscula(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /**
     * De onde se entrou numa lista de dentro: ao voltar (VOLTAR), a lista de cima abre com o foco nesse item, e não no
     * 1º (visto na TV em 20/11/2026: voltar de "Legenda" caía em "Áudio"). Guarda o nome, não a posição: os itens mudam.
     */
    private var retorno: Pair<String, String>? = null

    /** Onde pôr o foco ao abrir [titulo]: o pedido, senão o item de onde se entrou numa lista de dentro. */
    internal fun focoAoAbrir(titulo: String, itens: List<Item>, pedido: Int?): Int? {
        if (pedido != null) return pedido
        val (t, nome) = retorno ?: return null
        if (t != titulo) return null
        retorno = null
        return itens.indexOfFirst { it.nome == nome }.takeIf { it >= 0 }
    }

    /** Depois do OK num item: abrir outra lista guarda o caminho de volta; o resto o esquece. */
    internal fun escolheu(titulo: String, item: Item) {
        retorno = if (item.tipo == Tipo.ABRE) titulo to item.nome else null
    }

    /** Depois do OK: a chave reabre a mesma lista (no mesmo item); o resto fecha. */
    fun reabre(item: Item) = item.tipo == Tipo.CHAVE

    /**
     * Mostra a lista. [montar] é chamado de novo quando uma chave muda, para a linha dizer o valor novo.
     * Uma ação com [Item.valor] o mostra numa 2ª linha menor ("Assistir legendado / Continua deste ponto").
     * [voltar]: o que VOLTAR faz (a lista de cima); sem ele, VOLTAR fecha.
     */
    fun abrir(ctx: Context, titulo: String, voltar: (() -> Unit)? = null, foco: Int? = null, entrar: Boolean = true,
              montar: () -> List<Item>) {
        val itens = montar()
        if (itens.isEmpty()) return
        val focoInicial = focoAoAbrir(titulo, itens, foco)
        Estilo.lista(ctx, titulo, itens.map { if (it.tipo == Tipo.ACAO && !it.valor.isNullOrBlank()) Estilo.comInfo(it.nome, it.valor) else it.nome }, marcadas(itens),
            focoInicial = focoInicial, entrar = entrar, aoVoltar = voltar,
            valores = itens.map { valorNaLinha(it) }) { i ->
            val item = itens[i]
            escolheu(titulo, item)
            item.acao()
            if (reabre(item)) abrir(ctx, titulo, voltar, foco = i, entrar = false, montar = montar)
        }
    }
}

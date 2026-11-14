package br.paulo.tvlauncher

import android.content.Context
import android.util.Log

/**
 * A parte Android de Filmes e Séries no catálogo de ações (14/11/2026, ideia 60; a lógica pura está no [VodBusca]):
 *  - [resolver]: lê do disco as listas (só o cache: nada de baixar a lista inteira no meio de um pedido), tira as
 *    categorias ocultas (VodFiltro, as mesmas travas das telas) e escolhe o título; a ficha da série vem da rede;
 *  - [tocar]: abre o player e grava o episódio como "último aberto", como a tela de Séries faz.
 */
object VodTv {
    private const val TAG = "TvLauncher.VodIa"

    /** Chamar FORA da thread da tela: lê arquivos e pode baixar a ficha de uma série. */
    fun resolver(app: Context, plano: ComandosIa.Plano): ComandosIa.Plano {
        if (plano.acoes.none { it is ComandosIa.Acao.TocarTitulo || it is ComandosIa.Acao.Continuar ||
                it is ComandosIa.Acao.ProximoEpisodio || it is ComandosIa.Acao.OQueEstavaVendo }) return plano
        val dados = try {
            val escondidas = PlayerArmazem.gruposEscondidos(app)
            val liberadas = PlayerArmazem.gruposLiberados(app)
            val parental = PlayerArmazem.parental(app)
            val filmes = PlayerCarga.filmes(app, soCache = true)
            val series = PlayerCarga.series(app, soCache = true)
            val ocultasF = VodFiltro.ocultas(filmes.map { it.grupo }, escondidas, liberadas, parental)
            val ocultasS = VodFiltro.ocultas(series.map { it.grupo }, escondidas, liberadas, parental)
            VodBusca.Dados(
                VodFiltro.visiveis(filmes, ocultasF) { it.grupo }, VodFiltro.visiveis(series, ocultasS) { it.grupo },
                PlayerArmazem.posicoes(app), PlayerArmazem.ultimosEpisodios(app),
            ) { s -> PlayerCarga.fichaSerie(app, s) }
        } catch (e: Exception) {
            Log.w(TAG, "listas: ${e.javaClass.simpleName}")
            null
        }
        return VodBusca.resolver(plano, dados) { l ->
            if (l.isEmpty()) ComandosIa.t(R.string.acao_vendo_nada) else ComandosIa.t(R.string.acao_vendo, l.joinToString(", "))
        }
    }

    /** Abre o player no título já resolvido. Null = deu certo. */
    fun tocar(app: Context, alvo: VodBusca.Alvo?): String? {
        if (alvo == null) return "título não conferido"
        val ep = alvo.episodio
        if (ep != null) Fundo.rodar {
            val u = Series.registrarUltimo(PlayerArmazem.ultimosEpisodios(app), alvo.serieChave,
                UltimoEpisodio(ep.id, ep.temporada, ep.numero, ep.titulo, ep.extensao, System.currentTimeMillis()))
            PlayerArmazem.definirUltimosEpisodios(app, u)
        }
        VodActivity.abrir(app, alvo.tipo, alvo.fonteId, alvo.id, alvo.extensao, alvo.titulo, alvo.doComeco)
        return null
    }
}

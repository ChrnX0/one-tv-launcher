package br.paulo.tvlauncher

/**
 * "Na rede" com cara de catálogo (ideia 29, 14/11/2026): o que tocar quando um vídeo ou música da pasta acaba.
 * Antes cada arquivo tocava sozinho e a tela voltava para a lista. Agora a pasta emenda: música segue direto (álbum),
 * vídeo segue com a contagem de 10 s e, depois de 3 seguidos sem tecla, pergunta "ainda está assistindo?" (as mesmas
 * regras das séries, FimDoVideo.depois). Lógica pura (teste JVM: FilaRedeTest).
 */
object FilaRede {
    /** Posição do próximo item do MESMO tipo depois de [depoisDe] em [tipos] (null = item que não toca), ou -1. */
    fun proximo(tipos: List<Upnp.Tipo?>, depoisDe: Int, tipo: Upnp.Tipo): Int {
        if (tipo != Upnp.Tipo.VIDEO && tipo != Upnp.Tipo.AUDIO) return -1
        for (i in depoisDe + 1 until tipos.size) if (tipos[i] == tipo) return i
        return -1
    }

    enum class Passo { NADA, TOCAR_JA, CONTAR, PERGUNTAR }

    /** Música emenda direto (sem contagem nem pergunta); vídeo segue as regras das séries. */
    fun depois(tipo: Upnp.Tipo, terminou: Boolean, temProximo: Boolean, desligou: Boolean, seguidosSemTecla: Int): Passo =
        if (tipo == Upnp.Tipo.AUDIO) {
            if (terminou && temProximo && !desligou) Passo.TOCAR_JA else Passo.NADA
        } else when (FimDoVideo.depois(terminou, temProximo, desligou, seguidosSemTecla)) {
            FimDoVideo.Passo.PROXIMO_CONTANDO -> Passo.CONTAR
            FimDoVideo.Passo.PERGUNTAR -> Passo.PERGUNTAR
            else -> Passo.NADA
        }
}

package br.paulo.tvlauncher

import android.view.accessibility.AccessibilityNodeInfo

/**
 * O texto que está na tela, pela árvore de acessibilidade (ideia grande 3, "o que é isso?", 10/11/2026). Serve para o
 * Claude entender a tela mesmo quando a FOTO sai preta: apps com DRM (Netflix etc.) escondem o vídeo da captura, mas o
 * título, o episódio e a sinopse do menu são texto comum.
 *
 * Exige android:canRetrieveWindowContent="true" no XML do serviço (ligado nesta mesma mudança). Só é lido quando alguém
 * chama a ferramenta ver_tela do MCP com a chave VER_TELA ligada; nada fica guardado.
 *
 * Não verificado: se os apps com DRM desta TV expõem esse texto para a acessibilidade.
 */
object TextoTela {
    const val MAX_CARACTERES = 4000
    private const val MAX_NOS = 600

    /** Junta os textos na ordem da tela: sem vazios, sem repetidos seguidos, até [max] caracteres. Pura. */
    fun juntar(textos: List<String>, max: Int = MAX_CARACTERES): List<String> {
        val saida = ArrayList<String>(); var total = 0
        for (t0 in textos) {
            val t = t0.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty() || saida.lastOrNull() == t) continue
            if (total + t.length > max) break
            saida += t; total += t.length
        }
        return saida
    }

    /** Os textos da janela ativa. Vazio se a acessibilidade estiver desligada ou sem permissão de ler a tela. */
    fun ler(): List<String> {
        val raiz = AutorizaShizukuService.instancia?.rootInActiveWindow ?: return emptyList()
        val brutos = ArrayList<String>()
        val fila = ArrayDeque<AccessibilityNodeInfo>().apply { add(raiz) }
        var vistos = 0
        while (fila.isNotEmpty() && vistos < MAX_NOS) {
            val n = fila.removeFirst(); vistos++
            if (n.isPassword) continue   // campo de senha: nunca
            n.text?.let { brutos += it.toString() }
            n.contentDescription?.let { brutos += it.toString() }
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        return juntar(brutos)
    }
}

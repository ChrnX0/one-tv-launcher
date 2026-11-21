package br.paulo.tvlauncher.player

import android.app.Activity
import android.content.Context
import kotlin.math.abs

/**
 * Taxa de quadros igual à do filme (ideia 86, 14/11/2026). Filme é gravado a 24 quadros por segundo (23,976 na
 * prática); a TV sai a 60 Hz. 60 não divide por 24, então um quadro fica 3 "batidas" na tela e o seguinte 2: num
 * movimento de câmera lento (travelling) a imagem anda aos trancos ("judder"). Kodi resolve trocando o modo do HDMI
 * para 23,976/24 Hz enquanto o filme toca; aqui é o mesmo: pedimos à janela o modo da tela (mesma resolução) cuja
 * frequência é múltipla da taxa do vídeo, e devolvemos o normal ao sair.
 *
 * A troca deixa a tela preta 1 a 2 s (é a TV mudando de modo), por isso só em filmes e séries, não na TV ao vivo
 * (trocar de canal piscaria toda vez). Chave nas Configurações › TV e filmes, no menu ▼ do player e no painel.
 * A escolha do modo é pura ([escolher], testada em TaxaQuadrosTest).
 */
object TaxaQuadros {
    data class Modo(val id: Int, val largura: Int, val altura: Int, val hz: Float)

    private const val CHAVE = "taxa_quadros"
    private const val TOLERANCIA = 0.002f   // 0,2%: 24 Hz serve para 23,976 (0,1%), 25 não serve para 24 (4%)

    /** Erro relativo entre a frequência e o múltiplo mais perto da taxa do vídeo (1x, 2x ou 3x). */
    private fun erro(hz: Float, fps: Float): Float = (1..3).minOf { k -> abs(hz - fps * k) / (fps * k) }

    /**
     * O modo para este vídeo, ou null para não trocar: taxa desconhecida, a tela atual já serve ou nenhum modo serve.
     * Só modos com a MESMA resolução da tela (trocar resolução demora mais e pode cortar a imagem). Entre os que servem,
     * o de menor erro; empate (ex.: 25 e 50 Hz para 25 qps), o de frequência maior (o menu do player fica mais suave).
     */
    fun escolher(fps: Float, atual: Modo, modos: List<Modo>): Modo? {
        if (fps.isNaN() || fps < 20f || fps > 61f) return null
        if (erro(atual.hz, fps) <= TOLERANCIA / 4) return null
        return modos.filter { it.largura == atual.largura && it.altura == atual.altura && erro(it.hz, fps) <= TOLERANCIA }
            .sortedWith(compareBy<Modo> { Math.round(erro(it.hz, fps) * 10_000) }.thenByDescending { it.hz })
            .firstOrNull()?.takeIf { it.id != atual.id }
    }

    // ---- preferência: ligada por padrão. MODE_MULTI_PROCESS: Configurações (processo principal) x player (:player).

    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PlayerAcessivel.PREFS, Context.MODE_MULTI_PROCESS)
    fun ligada(ctx: Context) = prefs(ctx).getBoolean(CHAVE, true)
    fun definir(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(CHAVE, v).apply()
    fun alternar(ctx: Context): Boolean = (!ligada(ctx)).also { definir(ctx, it) }

    // ---- aplicar na janela da tela do filme

    /** Pede o modo para [fps]. Devolve true se pediu uma troca. */
    fun aplicar(act: Activity, fps: Float): Boolean {
        if (!ligada(act)) return false
        @Suppress("DEPRECATION") val tela = runCatching { act.windowManager.defaultDisplay }.getOrNull() ?: return false   // act.display só no Android 11+
        val atual = tela.mode
        val modos = tela.supportedModes.map { Modo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val m = escolher(fps, Modo(atual.modeId, atual.physicalWidth, atual.physicalHeight, atual.refreshRate), modos) ?: return false
        act.window.attributes = act.window.attributes.apply { preferredDisplayModeId = m.id }
        return true
    }

    /** Volta ao modo de sempre (0 = a TV escolhe). */
    fun soltar(act: Activity) {
        if (act.window.attributes.preferredDisplayModeId == 0) return
        act.window.attributes = act.window.attributes.apply { preferredDisplayModeId = 0 }
    }
}

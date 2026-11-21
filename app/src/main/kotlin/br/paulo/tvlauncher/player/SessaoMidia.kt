package br.paulo.tvlauncher.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * Sessão de mídia dos nossos players (17/11/2026, teste pesado na Sala): sem ela o sistema não sabia o que tocava no
 * launcher. O MCP (`midia`), o "o que está passando?" do Gemini e o Tocando agora só enxergavam os apps de fora.
 *
 * [soControle] = false (TV ao vivo): quem está de fora só LÊ (título, tocando ou parado). Pausar o ao vivo por fora
 * pularia a PausaAoVivo (o selo "pausado" e o limite de 2 min); a pausa do ao vivo continua só pelo controle.
 * Filmes, séries e gravações aceitam tocar, pausar e pular, como qualquer player.
 *
 * O id leva o número do objeto: TV ao vivo e Filmes moram no mesmo processo (:player) e o Media3 recusa dois ids iguais.
 */
class SessaoMidia(private val ctx: Context, private val nome: String, private val soControle: Boolean) {
    private var sessao: MediaSession? = null

    /** Liga a sessão no player (ou troca o player, quando ele é refeito no mesmo ponto). */
    fun ligar(p: Player) {
        val s = sessao
        if (s != null) { runCatching { s.player = p }; return }
        sessao = runCatching {
            MediaSession.Builder(ctx, p).setId("$nome-${System.identityHashCode(this)}").apply {
                if (!soControle) setCallback(object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(SO_LER).build()
                })
            }.build()
        }.getOrNull()
    }

    /** Antes de soltar o player: a sessão não pode ficar segurando um player solto. */
    fun soltar() { runCatching { sessao?.release() }; sessao = null }

    private companion object {
        val SO_LER: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
        ).build()
    }
}

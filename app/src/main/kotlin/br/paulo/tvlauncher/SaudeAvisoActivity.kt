package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Aviso "Acesso remoto parado" (SaudeAcesso.kt): um cartão escuro no meio da tela, por cima da HOME, com o atalho para
 * refazer. Só abre com a nossa HOME na frente (nunca por cima de filme) e uma vez por queda.
 *
 * Botões: "Parear de novo" (shell próprio: PareamentoNaTv, a janela por cima das Opções do desenvolvedor), "Conectar"
 * (túnel: ConectarClaudeActivity, o passo a passo com QR) e "Agora não". O foco começa no botão do conserto; VOLTAR fecha.
 */
class SaudeAvisoActivity : Activity() {
    companion object {
        const val EXTRA_SHELL = "shell"
        const val EXTRA_TUNEL = "tunel"
        const val EXTRA_MINUTOS = "minutos"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro da atualização vigiada
        val shell = intent.getBooleanExtra(EXTRA_SHELL, false)
        val tunel = intent.getBooleanExtra(EXTRA_TUNEL, false)
        if (!shell && !tunel) { finish(); return }
        val minutos = intent.getIntExtra(EXTRA_MINUTOS, 0).coerceAtLeast(1)

        // Uma frase por item parado, já dizendo o que fazer (o dono não precisa saber o que é "shell" ou "túnel").
        val linhas = buildList {
            if (shell) add(getString(R.string.saude_texto_shell, minutos))
            if (tunel) add(getString(R.string.saude_texto_tunel))
        }
        val primeiro = if (shell) botao(getString(R.string.saude_parear)) {
            // Sem o serviço de acessibilidade a janela do pareamento não abre: leva para a aba Sistema, onde está o resto.
            if (!PareamentoNaTv.comecar(this)) startActivity(Intent(this, ConfigLauncherActivity::class.java).putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_AVANCADO))   // o shell fica no Avançado
            finish()
        } else null
        val conectar = if (tunel) botao(getString(R.string.saude_conectar)) {
            startActivity(Intent(this, ConectarClaudeActivity::class.java)); finish()
        } else null
        val depois = botao(getString(R.string.saude_depois)) { finish() }
        // Diálogo único do sistema de design (Estilo.dialogo); o conserto vem primeiro e ganha o foco.
        setContentView(Estilo.dialogo(this, null, getString(R.string.saude_titulo), linhas.joinToString("\n\n"),
            listOfNotNull(primeiro, conectar, depois), larguraDp = 720))
        (primeiro ?: conectar ?: depois).requestFocus()
    }

    private fun botao(rotulo: String, acao: () -> Unit) = Estilo.botao(this, rotulo).apply { setOnClickListener { acao() } }
}

package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela do backup noturno cifrado (BackupNoturno.kt). Abre pela linha "Backup automático" das Configurações > Sistema.
 * Mesmo jeito da tela de Extras: fundo escuro, uma linha por opção, OK muda, ◀ ▶ ajustam o horário.
 *
 * Com PIN definido (o do painel), qualquer mudança pede o PIN uma vez: sem isso, quem pegasse o controle trocaria o
 * destino ou desligaria o backup.
 */
class BackupNoturnoActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private val linhas = mutableListOf<Pair<TextView, () -> String>>()
    private var pinOk = false
    private var rodando = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro da atualização vigiada
        // Molde único de tela (Estilo.telaLista): título + explicação como subtítulo, fundo grafite da HOME.
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.bkn_titulo), getString(R.string.bkn_explica))
        coluna = col
        setContentView(raiz)
        montar()
    }

    override fun onResume() { super.onResume(); atualizar() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun montar() {
        linha({ getString(R.string.bkn_titulo) + ": " + getString(if (BackupNoturno.ligado(this)) R.string.bkn_ligado else R.string.bkn_desligado) }) {
            comPin {
                val ligar = !BackupNoturno.ligado(this)
                if (!BackupNoturno.definirLigado(this, ligar)) mostrar(getString(R.string.bkn_sem_senha))
            }
        }
        linha({ getString(R.string.bkn_senha) + ": " + getString(if (BackupNoturno.temSenha(this)) R.string.bkn_senha_ok else R.string.bkn_senha_falta) }) {
            comPin { pedirSenha() }
        }
        val horario = linha({ getString(R.string.bkn_horario) + ": " + BackupNoturnoRegras.hhmm(BackupNoturno.minutoDoDia(this)) }) {
            comPin { mudarHorario(30) }
        }
        horario.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { comPin { mudarHorario(-30) }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { comPin { mudarHorario(30) }; true }
                else -> false
            }
        }
        linha({ getString(R.string.bkn_extra) + ": " + BackupNoturno.rotuloDestino(this, BackupNoturno.destino(this)) }) {
            comPin {
                // Ler as contas decifra pelo Keystore: fora da thread da tela, e aplica o resultado nela.
                val app = applicationContext
                Fundo.rodar {
                    val contas = runCatching { BackupNoturno.contasSmb(app) }.getOrDefault(emptyList())
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        val novo = BackupNoturnoRegras.proximoDestino(BackupNoturno.destino(app), contas.map { it.id })
                        val nome = contas.firstOrNull { it.id == BackupNoturnoRegras.idSmb(novo) }?.nome.orEmpty()
                        BackupNoturno.definirDestino(app, novo, nome)
                        atualizar()
                    }
                }
            }
        }
        linha({ getString(R.string.bkn_agora) + if (rodando) " …" else "" }) {
            comPin {
                if (rodando) return@comPin
                if (!BackupNoturno.temSenha(this)) { mostrar(getString(R.string.bkn_sem_senha)); return@comPin }
                rodando = true
                BackupNoturno.fazerAgora(this) { r -> runOnUiThread { if (!isDestroyed) { rodando = false; mostrar(r); atualizar() } } }
            }
        }
        linha({ getString(R.string.bkn_ultimo) + ": " + BackupNoturno.ultimo(this).ifEmpty { getString(R.string.bkn_nunca) } }) {}
        linhas.firstOrNull()?.first?.requestFocus()
    }

    private fun mudarHorario(delta: Int) =
        BackupNoturno.definirMinutoDoDia(this, BackupNoturnoRegras.mudarHorario(BackupNoturno.minutoDoDia(this), delta))

    /** Linha padrão do sistema de design (Estilo.linhaTexto): a mesma de Extras, Casa e Configurações. */
    private fun linha(texto: () -> String, ao: () -> Unit): TextView {
        val t = Estilo.linhaTexto(this).apply { setOnClickListener { ao(); atualizar() } }
        coluna.addView(t, Estilo.lpLinha(this))
        linhas += t to texto
        return t
    }

    private fun atualizar() { for ((t, texto) in linhas) t.text = texto() }

    /** Roda [acao] direto se não há PIN ou já foi conferido nesta tela; senão pede o PIN antes. */
    private fun comPin(acao: () -> Unit) {
        if (pinOk || !PainelArmazem.temPin(this)) { acao(); atualizar(); return }
        PinTv.pedir(this, getString(R.string.bkn_pin)) { certo ->
            if (certo) { pinOk = true; acao(); atualizar() } else mostrar(getString(R.string.trava_home_errado))
        }
    }

    /** Senha digitada duas vezes (escondida). Esquecer a senha = backup que ninguém abre: a tela avisa para anotar. */
    private fun pedirSenha() {
        caixaSenha(getString(R.string.bkn_senha_nova)) { s1 ->
            if (!CofreBackup.senhaValida(s1)) { mostrar(getString(R.string.bkn_senha_curta)); return@caixaSenha }
            caixaSenha(getString(R.string.bkn_senha_repetir)) { s2 ->
                if (s2 != s1) { mostrar(getString(R.string.bkn_senha_diferente)); return@caixaSenha }
                Fundo.rodar {
                    val ok = BackupNoturno.definirSenha(applicationContext, s1)
                    runOnUiThread { if (!isDestroyed) { mostrar(getString(if (ok) R.string.bkn_senha_gravada else R.string.bkn_senha_erro)); atualizar() } }
                }
            }
        }
    }

    private fun caixaSenha(titulo: String, ok: (String) -> Unit) {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val moldura = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(titulo).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ -> ok(campo.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).create()
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun mostrar(texto: String) {
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(texto)
            .setPositiveButton(android.R.string.ok, null).show()
    }
}

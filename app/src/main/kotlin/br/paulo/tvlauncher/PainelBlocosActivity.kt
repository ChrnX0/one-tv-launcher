package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Escolher os blocos do painel rápido (ideia 42). Uma linha por bloco: OK mostra ou esconde; ◀ ▶ trocam de lugar com
 * o vizinho (só entre os mostrados). Grava na hora; o painel lê ao abrir. Abre por Configurações > Aparência.
 */
class PainelBlocosActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private var lista: List<String> = emptyList()
    private var focado: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.pb_titulo), getString(R.string.pb_subtitulo))
        coluna = col
        setContentView(raiz)
        lista = PainelBlocosTv.escolhidos(this)
        montar()
    }

    private fun nome(id: String): String = getString(when (id) {
        "wifi" -> R.string.pb_wifi
        "bluetooth" -> R.string.pb_bluetooth
        "timer" -> R.string.pr_timer
        "dormir" -> R.string.pr_dormir
        "protetor" -> R.string.pr_protetor
        "audio" -> R.string.pp_audio
        "despertador" -> R.string.pr_despertador
        "visita" -> R.string.pr_visita
        "casa" -> R.string.pr_casa
        "cenas" -> R.string.pr_cenas
        "filtro" -> R.string.pb_filtro
        else -> R.string.pb_dns
    })

    /** Redesenha as linhas na ordem da escolha e devolve o foco à linha que estava focada. */
    private fun montar() {
        coluna.removeAllViews()
        var focar: TextView? = null
        for (id in PainelBlocos.ordemDaEscolha(lista)) {
            val mostrado = id in lista
            val t = Estilo.linhaTexto(this, "${nome(id)}: " + getString(if (mostrado) R.string.pb_mostrado else R.string.pb_escondido))
            t.setOnClickListener { mudar(id, PainelBlocos.alternar(lista, id)) }
            t.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN || id !in lista) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { mudar(id, PainelBlocos.mover(lista, id, -1)); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { mudar(id, PainelBlocos.mover(lista, id, 1)); true }
                    else -> false
                }
            }
            coluna.addView(t, Estilo.lpLinha(this))
            if (focar == null && (focado == null || id == focado)) focar = t
        }
        val padrao = Estilo.linhaTexto(this, getString(R.string.pb_padrao)).apply {
            setOnClickListener { PainelBlocosTv.voltarAoPadrao(this@PainelBlocosActivity); lista = PainelBlocosTv.escolhidos(this@PainelBlocosActivity); focado = null; montar() }
        }
        coluna.addView(padrao, Estilo.lpLinha(this))
        (focar ?: padrao).requestFocus()
    }

    private fun mudar(id: String, nova: List<String>) {
        if (nova == lista) return
        lista = nova
        focado = id
        PainelBlocosTv.definir(this, lista)
        montar()
    }
}

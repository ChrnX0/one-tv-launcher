package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Automações (14/11/2026, ideia 1): o editor único de "quando X, faça Y" ([Automacoes]). Aberta pelas Configurações
 * do launcher, no lugar da antiga linha Rotinas. Quatro grupos:
 *  - Quando for hora: as rotinas; OK abre o editor da rotina (RotinasActivity direto no editor);
 *  - Quando apertar um botão: OK abre o editor do botão (TeclasActivity direto);
 *  - Quando algo acontecer: cada evento muda aqui mesmo com OK / ◀ ▶ entre nada e as cenas (grava na hora);
 *  - Por link: as cenas (criar e editar no painel web); OK abre a tela Cenas.
 * Volta a desenhar no onResume, para mostrar o que os editores mudaram. Visual igual ao das Rotinas.
 */
class AutomacoesActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        desenhar()
    }

    private fun desenhar() {
        val focoAntes = (currentFocus?.tag as? Linha)?.chave
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.aut_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.aut_det)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

        val regras = Automacoes.listar(this)
        val cenas = Cenas.lista(this).map { it.nome }

        secao(lista, R.string.aut_horario)
        adicionar(lista, linha("nova-rotina", getString(R.string.rot_nova_mais), getString(R.string.rot_nova_det)) {
            startActivity(Intent(this, RotinasActivity::class.java).putExtra(RotinasActivity.EXTRA_ROTINA, -1))
        })
        for (r in Rotinas.listar(this)) {
            adicionar(lista, linha("rotina:${r.id}", "%02d:%02d  ·  %s".format(r.hora, r.minuto, Rotinas.descricao(this, r)),
                Despertador.diasTexto(this, r.dias) + if (r.ativa) "" else getString(R.string.rot_desligada_sufixo)) {
                startActivity(Intent(this, RotinasActivity::class.java).putExtra(RotinasActivity.EXTRA_ROTINA, r.id))
            })
        }

        secao(lista, R.string.aut_botao)
        adicionar(lista, linha("novo-botao", getString(R.string.tec_nova), getString(R.string.tec_nova_det)) {
            startActivity(Intent(this, TeclasActivity::class.java).putExtra(TeclasActivity.EXTRA_TECLA, 0))
        })
        for (tecla in regras.mapNotNull { (it.quando as? Automacoes.Quando.Botao)?.tecla }.distinct()) {
            val det = regras.filter { (it.quando as? Automacoes.Quando.Botao)?.tecla == tecla }
                .joinToString("  ·  ") { "${nomeGesto((it.quando as Automacoes.Quando.Botao).gesto)}: ${rotulo(it.faz)}" }
            adicionar(lista, linha("tecla:$tecla", RemapTeclas.nome(this, tecla), det) {
                startActivity(Intent(this, TeclasActivity::class.java).putExtra(TeclasActivity.EXTRA_TECLA, tecla))
            })
        }

        secao(lista, R.string.aut_evento)
        val gatilhos = Gatilhos.todos(this).toMutableMap()
        for (e in Gatilhos.Evento.values()) {
            lateinit var v: TextView
            fun texto() = "${nomeEvento(e)}: ${gatilhos[e] ?: getString(R.string.rot_nenhum)}"
            fun girar(passo: Int) {
                val novo = Cenas.girar(cenas, gatilhos[e] ?: "", passo, comNenhuma = true)
                if (novo.isEmpty()) gatilhos.remove(e) else gatilhos[e] = novo
                Gatilhos.salvar(this, gatilhos)
                v.titulo(texto())
            }
            v = linha("evento:${e.chave}", texto(), getString(if (cenas.isEmpty()) R.string.rot_cena_sem else R.string.rot_app_det)) { girar(1) }
            v.setOnKeyListener { _, k, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { girar(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { girar(1); true }
                    else -> false
                }
            }
            adicionar(lista, v)
        }

        secao(lista, R.string.aut_link)
        adicionar(lista, linha("links", resources.getQuantityString(R.plurals.aut_links, cenas.size, cenas.size), getString(R.string.aut_link_det)) {
            startActivity(Intent(this, CenasActivity::class.java))
        })

        setContentView(raiz)
        val alvo = (0 until lista.childCount).map { lista.getChildAt(it) }.firstOrNull { (it.tag as? Linha)?.chave == focoAntes }
        (alvo ?: (0 until lista.childCount).map { lista.getChildAt(it) }.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun nomeGesto(g: RemapTeclas.Gesto) = getString(when (g) {
        RemapTeclas.Gesto.TOQUE -> R.string.tec_toque
        RemapTeclas.Gesto.SEGURAR -> R.string.tec_segurar
        RemapTeclas.Gesto.DUPLO -> R.string.tec_duplo
    })

    private fun nomeEvento(e: Gatilhos.Evento) = getString(when (e) {
        Gatilhos.Evento.MIDIA_TOCOU -> R.string.ev_midia_tocou
        Gatilhos.Evento.MIDIA_PAUSOU -> R.string.ev_midia_pausou
        Gatilhos.Evento.TV_LIGOU -> R.string.ev_tv_ligou
        Gatilhos.Evento.TODOS_SAIRAM -> R.string.ev_todos_sairam
        Gatilhos.Evento.CHUVA -> R.string.ev_chuva
    })

    private fun rotulo(faz: String): String = when {
        faz.startsWith("app:") -> faz.removePrefix("app:").let { Rotinas.rotuloApp(this, it) ?: it }
        faz.startsWith("cena:") -> getString(R.string.tec_cena, faz.removePrefix("cena:"))
        else -> faz
    }

    // ---------------------------------------------------------------- peças (iguais às das Rotinas)

    private fun secao(lista: LinearLayout, texto: Int) =
        lista.addView(Estilo.secao(this, getString(texto)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })

    private fun adicionar(lista: LinearLayout, v: View) =
        lista.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

    private class Linha(val chave: String, var titulo: String, val detalhe: String)

    private fun linha(chave: String, titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        tag = Linha(chave, titulo, detalhe)
        typeface = Estilo.fonte(500)
        titulo(titulo)
        isFocusable = true
        isClickable = true
        background = Estilo.superficie(context, false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            Estilo.focarLinha(v, temFoco)
            (v as TextView).titulo((v.tag as Linha).titulo)   // as cores estão nos spans: refaz com as do foco
            v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }

    private fun TextView.titulo(t: String) {
        val l = tag as Linha
        l.titulo = t
        val foco = isFocused
        val sb = SpannableStringBuilder(t)
        sb.setSpan(AbsoluteSizeSpan(dp(17), false), 0, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO), 0, sb.length, 0)
        if (l.detalhe.isNotEmpty()) {
            val ini = sb.length
            sb.append("\n").append(l.detalhe)
            sb.setSpan(AbsoluteSizeSpan(dp(13), false), ini, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_2), ini, sb.length, 0)
        }
        text = sb
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()
}

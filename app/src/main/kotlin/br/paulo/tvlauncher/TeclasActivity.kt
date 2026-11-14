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
 * "Botões do controle" (14/11/2026, ideia 6): escolhe o que cada tecla livre faz no toque, ao segurar e no toque duplo
 * (RemapTeclas). Aberta por Extras. Três passos, sem teclado:
 *  1. lista das teclas já configuradas + "Configurar uma tecla";
 *  2. "aperte a tecla": a tela recebe a tecla (RemapTeclas.capturando faz o funil deixá-la passar);
 *  3. editor: cada gesto gira com OK / ▶ (próximo) e ◀ (anterior) entre nada/padrão, os apps e as cenas.
 * Visual igual ao das Rotinas (Estilo), copiado de propósito.
 */
class TeclasActivity : Activity() {
    private enum class Passo { LISTA, CAPTURA, EDITOR }
    private var passo = Passo.LISTA
    private var avisoCaptura: TextView? = null

    companion object {
        /** Abre direto num botão (código da tecla; 0 = "aperte o botão") e fecha ao salvar/voltar (tela Automações). */
        const val EXTRA_TECLA = "tecla"
    }
    private var direto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        if (intent.hasExtra(EXTRA_TECLA)) {
            direto = true
            val t = intent.getIntExtra(EXTRA_TECLA, 0)
            if (RemapTeclas.livre(t)) mostrarEditor(t) else capturar()
        } else mostrarLista()
    }

    private fun fimEditor() { if (direto) finish() else mostrarLista() }

    // A captura nunca pode ficar ligada fora desta tela: as teclas livres parariam de funcionar.
    override fun onPause() { RemapTeclas.capturando = false; super.onPause() }
    override fun onResume() { super.onResume(); if (passo == Passo.CAPTURA) RemapTeclas.capturando = true }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (passo != Passo.CAPTURA) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
        when {
            RemapTeclas.livre(e.keyCode) -> { RemapTeclas.capturando = false; mostrarEditor(e.keyCode) }
            e.keyCode == KeyEvent.KEYCODE_BACK -> { RemapTeclas.capturando = false; fimEditor() }
            else -> avisoCaptura?.text = getString(R.string.tec_nao_pode, KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_"))
        }
        return true
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() {
        if (passo == Passo.EDITOR) { fimEditor(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tela(titulo: String, subtitulo: String): Pair<View, LinearLayout> {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, titulo))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, subtitulo), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        return raiz to lista
    }

    private fun mostrarLista() {
        passo = Passo.LISTA
        val (raiz, lista) = tela(getString(R.string.tec_titulo), getString(R.string.tec_det))
        val nova = linha(getString(R.string.tec_nova), getString(R.string.tec_nova_det)) { capturar() }
        adicionar(lista, nova)
        for ((tecla, g) in RemapTeclas.todas(this).toSortedMap()) {
            val det = RemapTeclas.Gesto.values().filter { it in g }.joinToString("  ·  ") { "${nomeGesto(it)}: ${rotulo(tecla, g[it] ?: "")}" }
            adicionar(lista, linha(RemapTeclas.nome(this, tecla), det) { mostrarEditor(tecla) })
        }
        setContentView(raiz)
        nova.requestFocus()
    }

    private fun capturar() {
        passo = Passo.CAPTURA
        RemapTeclas.capturando = true
        val (raiz, lista) = tela(getString(R.string.tec_aperte), getString(R.string.tec_aperte_det))
        avisoCaptura = Estilo.texto(this, Estilo.Papel.CORPO, "")
        lista.addView(avisoCaptura)
        setContentView(raiz)
    }

    private fun mostrarEditor(tecla: Int) {
        passo = Passo.EDITOR
        val cfg = RemapTeclas.da(this, tecla).toMutableMap()
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != packageName }
            .sortedBy { (Rotinas.rotuloApp(this, it) ?: it).lowercase() }
        val opcoes = listOf("") + apps.map { "app:$it" } + Cenas.lista(this).map { "cena:${it.nome}" }
        val (raiz, lista) = tela(RemapTeclas.nome(this, tecla),
            getString(if (RemapTeclas.soNaHome(tecla)) R.string.tec_so_home else R.string.tec_toda_tela))
        val linhas = HashMap<RemapTeclas.Gesto, TextView>()
        fun redesenhar() { for ((g, v) in linhas) v.titulo("${nomeGesto(g)}: ${rotulo(tecla, cfg[g] ?: "")}") }
        fun girar(g: RemapTeclas.Gesto, passo: Int) {
            val i = opcoes.indexOf(cfg[g] ?: "")
            val novo = opcoes[((if (i < 0) 0 else i) + passo).mod(opcoes.size)]
            if (novo.isEmpty()) cfg.remove(g) else cfg[g] = novo
            redesenhar()
        }
        for (g in RemapTeclas.Gesto.values()) {
            val v = linha("", getString(R.string.rot_app_det)) { girar(g, 1) }
            v.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { girar(g, -1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { girar(g, 1); true }
                    else -> false
                }
            }
            linhas[g] = v
            adicionar(lista, v)
        }
        redesenhar()
        adicionar(lista, linha(getString(R.string.rot_salvar), "") {
            RemapTeclas.salvar(this, tecla, cfg)
            AvisoTv.rapido(this, getString(R.string.tec_titulo), getString(R.string.tec_salvo, RemapTeclas.nome(this, tecla)), 3)
            fimEditor()
        })
        if (RemapTeclas.da(this, tecla).isNotEmpty()) adicionar(lista, linha(getString(R.string.tec_apagar), "") {
            RemapTeclas.salvar(this, tecla, emptyMap()); fimEditor()
        })
        setContentView(raiz)
        linhas[RemapTeclas.Gesto.TOQUE]?.requestFocus()
    }

    private fun nomeGesto(g: RemapTeclas.Gesto) = getString(when (g) {
        RemapTeclas.Gesto.TOQUE -> R.string.tec_toque
        RemapTeclas.Gesto.SEGURAR -> R.string.tec_segurar
        RemapTeclas.Gesto.DUPLO -> R.string.tec_duplo
    })

    /** Como mostrar um atalho. Vazio num botão de app = o de sempre (app salvo / lista); nas outras = nada. */
    private fun rotulo(tecla: Int, a: String): String = when {
        a.startsWith("app:") -> a.removePrefix("app:").let { Rotinas.rotuloApp(this, it) ?: it }
        a.startsWith("cena:") -> getString(R.string.tec_cena, a.removePrefix("cena:"))
        tecla in RemapTeclas.APP -> getString(R.string.tec_padrao)
        else -> getString(R.string.rot_nenhum)
    }

    // ---------------------------------------------------------------- peças (iguais às das Rotinas)

    private fun adicionar(lista: LinearLayout, v: View) =
        lista.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

    private class Linha(var titulo: String, val detalhe: String)

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        tag = Linha(titulo, detalhe)
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

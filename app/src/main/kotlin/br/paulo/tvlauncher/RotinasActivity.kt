package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela das rotinas por horário (Rotinas.kt), aberta pelas Configurações do launcher. Lista as rotinas; OK numa abre
 * o editor. No editor cada linha muda com OK (e ◀ ▶ onde faz sentido), para não precisar de teclado:
 *  - Hora: ◀ ▶ = 5 min para trás/frente, OK = +1 hora;
 *  - Dias, Ação, Condição: OK gira entre as opções; App: OK/▶ próximo, ◀ anterior.
 * Estilo igual ao das Configurações do launcher (vidro, mesmas cores), copiado de propósito.
 */
class RotinasActivity : Activity() {
    // Cores e fontes do sistema de design (Estilo).
    private val textoClaro = Estilo.TEXTO
    private val textoSuave = Estilo.TEXTO_2
    private val fonteMedia = Estilo.fonte(500)
    private var noEditor = false

    /** Presets de dias (o despertador tem as bolinhas; aqui basta isto). Bit 0 = domingo. */
    private val DIAS = listOf(0b1111111, 0b0111110, 0b1000001, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        mostrarLista()
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() {
        if (noEditor) { mostrarLista(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tela(titulo: String, subtitulo: String): Pair<View, LinearLayout> {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, titulo))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, subtitulo), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        return raiz to lista
    }

    private fun mostrarLista() {
        noEditor = false
        val prox = Rotinas.proxima(this)
        val (raiz, lista) = tela(getString(R.string.cfg_rotinas), if (prox == null) getString(R.string.rot_nenhuma) else
            getString(R.string.rot_proxima, Rotinas.descricao(this, prox.first).lowercase()) + " " +
                android.text.format.DateUtils.getRelativeTimeSpanString(prox.second, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
        val nova = linha(getString(R.string.rot_nova_mais), getString(R.string.rot_nova_det)) { mostrarEditor(null) }
        adicionar(lista, nova)
        for (r in Rotinas.listar(this)) {
            adicionar(lista, linha("%02d:%02d  ·  %s".format(r.hora, r.minuto, Rotinas.descricao(this, r)),
                Despertador.diasTexto(this, r.dias) + if (r.ativa) "" else getString(R.string.rot_desligada_sufixo)) { mostrarEditor(r) })
        }
        setContentView(raiz)
        (lista.getChildAt(1) ?: nova).requestFocus()
    }

    private fun mostrarEditor(original: Rotinas.Rotina?) {
        noEditor = true
        var r = original ?: Rotinas.Rotina(0, 7, 0, 0b0111110, Rotinas.Acao.LIGAR)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != packageName }
            .sortedBy { (Rotinas.rotuloApp(this, it) ?: it).lowercase() }
        val (raiz, lista) = tela(if (original == null) getString(R.string.rot_nova) else getString(R.string.rot_editar), getString(R.string.rot_editor_det))

        lateinit var lHora: TextView; lateinit var lDias: TextView; lateinit var lAcao: TextView
        lateinit var lApp: TextView; lateinit var lOcioso: TextView; lateinit var lAtiva: TextView; lateinit var lCena: TextView
        val cenas = Cenas.lista(this).map { it.nome }
        fun redesenhar() {
            lHora.titulo(getString(R.string.rot_hora, "%02d:%02d".format(r.hora, r.minuto)))
            lDias.titulo(getString(R.string.rot_dias, Despertador.diasTexto(this, r.dias)))
            lAcao.titulo(getString(R.string.rot_acao, getString(r.acao.rotulo)))
            lApp.titulo(getString(R.string.rot_app, Rotinas.rotuloApp(this, r.pacote) ?: getString(R.string.rot_nenhum)))
            lApp.visibility = if (r.acao == Rotinas.Acao.ABRIR) View.VISIBLE else View.GONE
            lCena.titulo(getString(R.string.rot_cena_linha, r.cena.ifBlank { getString(R.string.rot_nenhum) }))
            lCena.visibility = if (r.acao == Rotinas.Acao.CENA) View.VISIBLE else View.GONE
            lOcioso.titulo(getString(R.string.rot_ocioso, Rotinas.ociosoTexto(this, r.ocioso)))
            lOcioso.visibility = if (r.acao == Rotinas.Acao.DESLIGAR) View.VISIBLE else View.GONE
            lAtiva.titulo(if (r.ativa) getString(R.string.rot_ligada) else getString(R.string.rot_desligada))
        }
        fun mudarMinutos(delta: Int) {
            val total = ((r.hora * 60 + r.minuto + delta) % 1440 + 1440) % 1440
            r = r.copy(hora = total / 60, minuto = total % 60)
        }
        fun mudarApp(passo: Int) {
            if (apps.isEmpty()) return
            val i = apps.indexOf(r.pacote)
            r = r.copy(pacote = apps[((if (i < 0) -1 else i) + passo + apps.size) % apps.size])
        }

        lHora = linha("", getString(R.string.rot_hora_det)) { mudarMinutos(60); redesenhar() }
        lHora.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudarMinutos(-5); redesenhar(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudarMinutos(5); redesenhar(); true }
                else -> false
            }
        }
        lDias = linha("", getString(R.string.rot_dias_det)) {
            r = r.copy(dias = DIAS[(DIAS.indexOf(r.dias) + 1) % DIAS.size]); redesenhar()
        }
        lAcao = linha("", getString(R.string.rot_ok_muda)) {
            val a = Rotinas.Acao.values()
            r = r.copy(acao = a[(r.acao.ordinal + 1) % a.size])
            if (r.acao == Rotinas.Acao.ABRIR && r.pacote.isBlank()) mudarApp(1)
            if (r.acao == Rotinas.Acao.CENA && r.cena.isBlank()) r = r.copy(cena = Cenas.girar(cenas, "", 1, comNenhuma = false))
            redesenhar()
        }
        lCena = linha("", if (cenas.isEmpty()) getString(R.string.rot_cena_sem) else getString(R.string.rot_app_det)) {
            r = r.copy(cena = Cenas.girar(cenas, r.cena, 1, comNenhuma = false)); redesenhar()
        }
        lCena.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { r = r.copy(cena = Cenas.girar(cenas, r.cena, -1, comNenhuma = false)); redesenhar(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { r = r.copy(cena = Cenas.girar(cenas, r.cena, 1, comNenhuma = false)); redesenhar(); true }
                else -> false
            }
        }
        lApp = linha("", getString(R.string.rot_app_det)) { mudarApp(1); redesenhar() }
        lApp.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudarApp(-1); redesenhar(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudarApp(1); redesenhar(); true }
                else -> false
            }
        }
        lOcioso = linha("", getString(R.string.rot_ocioso_det)) {
            val o = Rotinas.OCIOSO_OPCOES
            r = r.copy(ocioso = o[(o.indexOf(r.ocioso) + 1) % o.size]); redesenhar()
        }
        lAtiva = linha("", getString(R.string.rot_ativa_det)) { r = r.copy(ativa = !r.ativa); redesenhar() }
        for (v in listOf(lHora, lDias, lAcao, lApp, lCena, lOcioso, lAtiva)) adicionar(lista, v)
        adicionar(lista, linha(getString(R.string.rot_salvar), "") {
            if (r.acao == Rotinas.Acao.ABRIR && r.pacote.isBlank()) { AvisoTv.rapido(this, getString(R.string.cfg_rotinas), getString(R.string.rot_escolha_app), 4); return@linha }
            if (r.acao == Rotinas.Acao.CENA && r.cena.isBlank()) { AvisoTv.rapido(this, getString(R.string.cfg_rotinas), getString(R.string.rot_cena_sem), 4); return@linha }
            Rotinas.salvar(this, r); mostrarLista()
        })
        if (original != null) adicionar(lista, linha(getString(R.string.rot_apagar), "") { Rotinas.apagar(this, original.id); mostrarLista() })
        redesenhar()
        setContentView(raiz)
        lHora.requestFocus()
    }

    // ---------------------------------------------------------------- peças (iguais às das Configurações)

    private fun adicionar(lista: LinearLayout, v: View) = lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        tag = Linha(titulo, detalhe)
        titulo(titulo)
        isFocusable = true
        isClickable = true
        background = vidro(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            Estilo.focarLinha(v, temFoco)
            // As cores estão nos spans: refaz com as cores do foco (texto escuro na pílula branca), senão some.
            (v as TextView).titulo((v.tag as Linha).titulo)
            v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }

    /** O que a linha mostra: o título muda (redesenhar), o detalhe é fixo. */
    private class Linha(var titulo: String, val detalhe: String)

    /** Título (CORPO) e o detalhe embaixo (LEGENDA), com as cores do foco quando a linha está focada. */
    private fun TextView.titulo(t: String) {
        val l = tag as Linha
        l.titulo = t
        val foco = isFocused
        val sb = SpannableStringBuilder(t)
        sb.setSpan(AbsoluteSizeSpan(dp(17), false), 0, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO else textoClaro), 0, sb.length, 0)
        if (l.detalhe.isNotEmpty()) {
            val ini = sb.length
            sb.append("\n").append(l.detalhe)
            sb.setSpan(AbsoluteSizeSpan(dp(13), false), ini, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO_2 else textoSuave), ini, sb.length, 0)
        }
        text = sb
        typeface = fonteMedia
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    /** Superfície padrão do sistema de design (Estilo.superficie); o foco é aplicado por Estilo.focarLinha. */
    private fun vidro(focado: Boolean): GradientDrawable = Estilo.superficie(this, focado)
}

package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Tela "Rádios" (ideia 87, 14/11/2026), dentro da TV ao vivo (coluna da esquerda, como Gravações). Uma lista só, em
 * letra grande:
 *  - "Tocando": o nome da rádio e a música que ela manda (metadados ICY, quando manda). OK pausa/continua.
 *  - Favoritas (números 1–9 do controle tocam direto), Buscar por nome, Gêneros, Outro país.
 *  - Perto de você (100 km da TV) e as mais ouvidas do país da TV. O resultado de uma busca aparece no alto.
 * Segurar OK numa rádio = favoritar/desfavoritar. Tocando e sem tecla por [ESCURECER_MS], a tela escurece (o som segue);
 * qualquer tecla acende. Catálogo e espelhos: [Radios].
 *
 * Desde 17/11/2026 ("Ouvir" parte 1) quem toca é o [OuvirServico]: sair da tela NÃO para o som (segue na HOME e no
 * protetor, com a pílula "Tocando" e as teclas ⏯ do controle). A linha "Parar" e o ajuste "Ao sair desta tela" (que
 * volta ao jeito antigo) ficam aqui. Esta tela só mostra o estado do serviço; não tem player próprio.
 */
class RadioActivity : Activity() {
    companion object {
        private const val ESCURECER_MS = 3 * 60_000L
        /** Elevação 3.11: aberta pela busca da HOME já procurando este nome. */
        const val EXTRA_BUSCA = "busca"
        private const val PREFS = "ouvir"   // no backup completo (BackupCompleto.PREFS_EXTRAS)
        private const val CHAVE_CONTINUAR = "continuar_fora"
        /** Sair da tela deixa o som tocando? Padrão sim. Prefs lidas só no ":player". */
        fun continuarFora(ctx: android.content.Context) =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CHAVE_CONTINUAR, true)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var coluna: LinearLayout
    private lateinit var subtitulo: TextView
    private lateinit var veu: LinearLayout
    private lateinit var veuNome: TextView
    private lateinit var veuMusica: TextView
    private val tocando: Radios.Radio? get() = OuvirServico.instancia?.radio
    private val musica: String get() = OuvirServico.instancia?.musica.orEmpty()
    /** O que a tela mostrou por último (id, música, pausado): só redesenha a lista quando muda. */
    private var visto: Triple<String?, String, Boolean>? = null
    private val mudou: () -> Unit = { aoMudarSom() }
    private var favoritas: List<Radios.Radio> = emptyList()
    private var perto: List<Radios.Radio>? = null
    private var doPais: List<Radios.Radio>? = null
    private var resultado: Pair<String, List<Radios.Radio>>? = null
    private val pais: String = Locale.getDefault().country.uppercase().takeIf { it.length == 2 } ?: ""
    private val escurecer = Runnable { if (OuvirServico.instancia?.tocando == true) veu.visibility = View.VISIBLE }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.ra_coluna), " ")
        coluna = col
        subtitulo = (raiz as LinearLayout).getChildAt(1) as TextView
        raiz.addView(Estilo.barraDicas(this, getString(R.string.grv_dica_ok) to getString(R.string.ra_dica_ouvir),
            getString(R.string.grv_dica_segurar) to getString(R.string.ra_dica_favoritar)),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@RadioActivity, Estilo.MARGEM_VERTICAL) })
        // Véu da tela escura: preto quase total, só o nome e a música, para o quarto ficar escuro com o rádio ligado.
        veuNome = Estilo.texto(this, Estilo.Papel.TITULO, "").apply { textSize = 34f; gravity = Gravity.CENTER; alpha = 0.55f }
        veuMusica = Estilo.texto(this, Estilo.Papel.CORPO, "").apply { textSize = 22f; gravity = Gravity.CENTER; alpha = 0.45f }
        veu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 0, 0, 0)); visibility = View.GONE
            addView(veuNome); addView(veuMusica, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Estilo.dp(this@RadioActivity, 12) })
        }
        setContentView(FrameLayout(this).apply {
            addView(raiz, FrameLayout.LayoutParams(-1, -1))
            addView(veu, FrameLayout.LayoutParams(-1, -1))
        })
        favoritas = Radios.favoritas(this)
        montar()
        carregarListas()
        intent.getStringExtra(EXTRA_BUSCA)?.trim()?.takeIf { it.length >= 2 }?.let { t ->
            buscar(getString(R.string.ra_resultado, t)) { Radios.urlNome(it, t) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Favoritas podem ter mudado pelo painel (rádio colada) com a tela atrás.
        val app = applicationContext
        Fundo.rodar("radios") { val f = Radios.favoritas(app); runOnUiThread { if (!isDestroyed && f != favoritas) { favoritas = f; montar() } } }
    }

    override fun onStart() {
        super.onStart()
        OuvirServico.ouvir(mudou)
        aoMudarSom()   // pode ter mudado com a tela atrás (pausado pela HOME, parado pelo protetor)
    }

    override fun onStop() {
        super.onStop()
        OuvirServico.esquecer(mudou)
        if (!::veu.isInitialized) return   // fechada pela atualização no onCreate
        ui.removeCallbacks(escurecer)
        veu.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // O jeito antigo, para quem escolheu: sair da tela para o som. Tela que só apagou também conta (o serviço já para).
        if (!continuarFora(this)) OuvirServico.instancia?.parar()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); OuvirServico.esquecer(mudou); super.onDestroy() }

    /** O serviço mudou (tocou, pausou, música nova, parou): véu, tela acesa e a lista. */
    private fun aoMudarSom() {
        if (isDestroyed || !::veu.isInitialized) return
        val s = OuvirServico.instancia
        val r = s?.radio
        veuNome.text = r?.nome.orEmpty(); veuMusica.text = s?.musica.orEmpty()
        // Tela acesa só com o som saindo E esta tela na frente; fora dela, o protetor de tela entra normalmente.
        if (s?.tocando == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (r == null) { ui.removeCallbacks(escurecer); veu.visibility = View.GONE }
        val agora = Triple(r?.id, s?.musica.orEmpty(), s?.pausado == true)
        if (agora != visto) { visto = agora; montar(); mexeu() }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            mexeu()
            if (veu.visibility == View.VISIBLE) { veu.visibility = View.GONE; return true }   // 1ª tecla só acende a tela
            val n = e.keyCode - KeyEvent.KEYCODE_1
            if (n in 0..8) { favoritas.getOrNull(n)?.let { tocar(it) }; return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun mexeu() {
        ui.removeCallbacks(escurecer)
        if (tocando != null) ui.postDelayed(escurecer, ESCURECER_MS)
    }

    // ---------------------------------------------------------------- dados

    private fun carregarListas() {
        val app = applicationContext
        subtitulo.text = getString(R.string.ra_carregando)
        Fundo.rodar("radios") {
            val ponto = runCatching { Localizacao.atual(app) { null } }.getOrNull()   // sem rede extra: só o que a TV já sabe
            val p = ponto?.let { pt -> Radios.estacoes(Radios.pedir { Radios.urlPerto(it, pt.lat, pt.lon, 30) }) }
            val d = if (pais.isNotEmpty()) Radios.estacoes(Radios.pedir { Radios.urlPais(it, pais) }) else
                Radios.estacoes(Radios.pedir { "https://$it/json/stations/topclick/${Radios.LIMITE}?hidebroken=true" })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                perto = p; doPais = d
                subtitulo.text = if (d.isEmpty() && p.isNullOrEmpty()) getString(R.string.ra_sem_catalogo) else getString(R.string.ra_subtitulo)
                montar()
            }
        }
    }

    private fun buscar(titulo: String, montarUrl: (String) -> String) {
        val app = applicationContext
        resultado = titulo to emptyList()
        subtitulo.text = getString(R.string.ra_carregando)
        Fundo.rodar("radios") {
            val r = Radios.estacoes(Radios.pedir(montarUrl))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                resultado = titulo to r
                subtitulo.text = getString(R.string.ra_subtitulo)
                montar(focarResultado = true)
                if (r.isEmpty()) AvisoTv.rapido(app, titulo, getString(R.string.ra_nada), 5)
            }
        }
    }

    // ---------------------------------------------------------------- tela

    private fun montar(focarResultado: Boolean = false) {
        val focoAntes = coluna.focusedChild?.tag
        coluna.removeAllViews()
        tocando?.let { r ->
            linha("tocando", "● " + r.nome, musica.ifEmpty { detalhe(r) },
                getString(if (OuvirServico.instancia?.pausado == true) R.string.ra_continuar else R.string.ra_pausar), cor = Estilo.ATENCAO,
                ok = { alternarPausa() }, segurar = { favoritar(r) })
            linha("parar", "■ " + getString(R.string.ra_parar),
                getString(if (continuarFora(this)) R.string.ra_parar_det else R.string.ra_parar_det_antigo), null,
                ok = { OuvirServico.instancia?.parar() }, segurar = null)
        }
        resultado?.let { (titulo, lista) ->
            coluna.addView(Estilo.secao(this, titulo))
            lista.forEach { linhaRadio("res:", it) }
        }
        // Podcasts (Paulo, 17/11/2026: no plano Básico; a tela "Ouvir" = rádio + podcasts, aberta pelo cartão da HOME).
        linha("podcasts", getString(R.string.pod_titulo), getString(R.string.ra_podcasts_det), null,
            ok = { startActivity(android.content.Intent(this, PodcastsActivity::class.java)) }, segurar = null)
        if (favoritas.isNotEmpty()) {
            coluna.addView(Estilo.secao(this, getString(R.string.ra_favoritas)))
            favoritas.forEachIndexed { i, r -> linhaRadio("fav:", r, numero = if (i < 9) i + 1 else null) }
        }
        coluna.addView(Estilo.secao(this, getString(R.string.ra_procurar)))
        linha("buscar", getString(R.string.ra_buscar), getString(R.string.ra_buscar_det), null, ok = { pedirNome() }, segurar = null)
        linha("generos", getString(R.string.ra_generos), Radios.GENEROS.take(5).joinToString(", ") + "…", null, ok = { escolherGenero() }, segurar = null)
        linha("paises", getString(R.string.ra_paises), getString(R.string.ra_paises_det), null, ok = { escolherPais() }, segurar = null)
        perto?.takeIf { it.isNotEmpty() }?.let { l ->
            coluna.addView(Estilo.secao(this, getString(R.string.ra_perto)))
            l.forEach { linhaRadio("perto:", it) }
        }
        doPais?.takeIf { it.isNotEmpty() }?.let { l ->
            val nomePais = if (pais.isEmpty()) "" else Locale("", pais).getDisplayCountry(Locale.getDefault())
            coluna.addView(Estilo.secao(this, if (nomePais.isEmpty()) getString(R.string.ra_mundo) else getString(R.string.ra_mais_ouvidas, nomePais)))
            l.forEach { linhaRadio("pais:", it) }
        }
        coluna.addView(Estilo.secao(this, getString(R.string.ra_ajustes)))
        linha("ao_sair", getString(R.string.ra_ao_sair), getString(R.string.ra_ao_sair_det),
            getString(if (continuarFora(this)) R.string.ra_ao_sair_continua else R.string.ra_ao_sair_para),
            ok = {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CHAVE_CONTINUAR, !continuarFora(this)).apply()
                montar()
            }, segurar = null)
        val filhos = (0 until coluna.childCount).map { coluna.getChildAt(it) }
        val alvo = if (focarResultado) filhos.firstOrNull { (it.tag as? String)?.startsWith("res:") == true }
            else filhos.firstOrNull { it.tag != null && it.tag == focoAntes }
        (alvo ?: filhos.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun detalhe(r: Radios.Radio): String = listOfNotNull(
        r.pais.takeIf { it.length == 2 }?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) },
        r.tags.ifEmpty { null }, if (r.bitrate in 8..512) "${r.bitrate} kbps" else null).joinToString(" · ")

    private fun linhaRadio(prefixo: String, r: Radios.Radio, numero: Int? = null) {
        val fav = favoritas.any { it.id == r.id }
        val titulo = (if (numero != null) "$numero  " else "") + r.nome
        val direita = when {
            tocando?.id == r.id -> "●"
            fav && numero == null -> "★"
            else -> null
        }
        linha(prefixo + r.id, titulo, detalhe(r), direita, ok = { tocar(r) }, segurar = { favoritar(r) })
    }

    private fun linha(chave: String, titulo: String, explica: String, direita: String?, cor: Int? = null, ok: () -> Unit, segurar: (() -> Unit)?) {
        val dir = direita?.let { Estilo.valor(this, it, seta = false) }
        val v = Estilo.linhaOpcao(this, titulo, explica.ifEmpty { null }, dir).apply {
            tag = chave
            setOnClickListener { ok() }
            if (segurar != null) setOnLongClickListener { segurar(); true }
        }
        if (cor != null) ((v.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(cor)
        coluna.addView(v, Estilo.lpLinha(this))
    }

    private fun pedirNome() {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true; textSize = 20f }
        val moldura = FrameLayout(this).apply { setPaddingRelative(Estilo.dp(this@RadioActivity, 20), Estilo.dp(this@RadioActivity, 8), Estilo.dp(this@RadioActivity, 20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.ra_buscar)).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = campo.text.toString().trim()
                if (t.length >= 2) buscar(getString(R.string.ra_resultado, t)) { Radios.urlNome(it, t) }
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun escolherGenero() {
        Estilo.lista(this, getString(R.string.ra_generos), Radios.GENEROS) { i ->
            val g = Radios.GENEROS[i]
            buscar(getString(R.string.ra_resultado, g)) { Radios.urlGenero(it, g) }
        }
    }

    private fun escolherPais() {
        val app = applicationContext
        Fundo.rodar("radios") {
            val lista = Radios.paises(Radios.pedir { Radios.urlPaises(it) }).take(80)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (lista.isEmpty()) { AvisoTv.rapido(app, getString(R.string.ra_paises), getString(R.string.ra_sem_catalogo), 5); return@runOnUiThread }
                val nomes = lista.map { (c, n, q) -> (Locale("", c).getDisplayCountry(Locale.getDefault()).ifEmpty { n }) + "  ·  $q" }
                Estilo.lista(this, getString(R.string.ra_paises), nomes) { i ->
                    val (c, n, _) = lista[i]
                    buscar(Locale("", c).getDisplayCountry(Locale.getDefault()).ifEmpty { n }) { Radios.urlPais(it, c) }
                }
            }
        }
    }

    private fun favoritar(r: Radios.Radio) {
        val app = applicationContext
        val era = favoritas.any { it.id == r.id }
        Fundo.rodar("radios") {
            val f = Radios.editarFavoritas(app) { Radios.alternar(it, r) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                favoritas = f; montar()
                AvisoTv.rapido(app, r.nome, getString(if (era) R.string.ra_desfavoritada else R.string.ra_favoritada), 3)
            }
        }
    }

    // ---------------------------------------------------------------- som (quem toca é o OuvirServico)

    private fun tocar(r: Radios.Radio) {
        if (tocando?.id == r.id) { alternarPausa(); return }
        OuvirServico.tocarRadio(this, r)
        veuNome.text = r.nome; veuMusica.text = ""
        mexeu()
    }

    private fun alternarPausa() { OuvirServico.instancia?.alternar() }
}

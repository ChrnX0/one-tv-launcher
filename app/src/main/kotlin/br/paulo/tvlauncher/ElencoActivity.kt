package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.player.ElencoTmdb
import br.paulo.tvlauncher.player.InfoExternaCache
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.VodFiltro
import br.paulo.tvlauncher.search.BuscaUnica

/**
 * Ideia 84 "Quem é esse ator?" (14/11/2026): o elenco do filme ou da série com foto e personagem (TMDB). Com o foco numa
 * pessoa, embaixo aparecem os outros títulos dela que estão na lista de canais (sem categorias ocultas); OK num deles abre
 * Filmes ou Séries já buscando o nome. Abre da ficha (botão Elenco) e do menu ▼ do player.
 */
class ElencoActivity : Activity() {
    companion object {
        private const val EXTRA_TMDB = "tmdb"
        private const val EXTRA_SERIE = "serie"
        private const val EXTRA_NOME = "nome"
        private const val ESPERA_FOCO_MS = 350L

        /** Só mostra o botão quando tem com quem falar: chave do TMDB nesta versão e id do título. */
        fun disponivel(tmdb: Int?): Boolean = tmdb != null && tmdb > 0 && InfoExternaCache.temTmdb()

        fun abrir(ctx: Context, tmdb: Int, serie: Boolean, nome: String) = ctx.startActivity(Intent(ctx, ElencoActivity::class.java)
            .putExtra(EXTRA_TMDB, tmdb).putExtra(EXTRA_SERIE, serie).putExtra(EXTRA_NOME, nome))
    }

    private val ui = Handler(Looper.getMainLooper())
    private val rede = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var fileira: LinearLayout
    private lateinit var coluna: LinearLayout
    private lateinit var titulos: LinearLayout
    private lateinit var aviso: TextView
    private var tmdb = 0
    private var serie = false
    @Volatile private var catalogo: List<ElencoTmdb.NaLista>? = null
    private var pessoaFocada = 0
    private var pendente: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tmdb = intent.getIntExtra(EXTRA_TMDB, 0)
        serie = intent.getBooleanExtra(EXTRA_SERIE, false)
        if (tmdb <= 0) { finish(); return }
        val (raiz, c) = Estilo.telaLista(this, getString(R.string.elenco_titulo), intent.getStringExtra(EXTRA_NOME).orEmpty())
        coluna = c
        aviso = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.elenco_carregando)).apply { textSize = Estilo.Leitura.AVISO }
        coluna.addView(aviso)
        fileira = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPaddingRelative(dp(4), dp(12), dp(4), dp(12)) }
        coluna.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(fileira) })
        coluna.addView(Estilo.secao(this, getString(R.string.elenco_na_lista)))
        titulos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        coluna.addView(titulos)
        setContentView(raiz)
        rede.execute {
            val elenco = InfoExternaCache.elenco(tmdb, serie)
            ui.post { if (!isDestroyed) desenharElenco(elenco) }
            catalogo = montarCatalogo()
            ui.post { if (!isDestroyed && pessoaFocada > 0) mostrarTitulos(pessoaFocada) }
        }
    }

    /** Filmes e séries da lista com id TMDB conhecido, sem as categorias ocultas (adultas, escondidas, parental). */
    private fun montarCatalogo(): List<ElencoTmdb.NaLista> {
        val filmes = PlayerCarga.filmes(this, soCache = true)
        val series = PlayerCarga.series(this, soCache = true)
        val ocultas = VodFiltro.ocultas(filmes.map { it.grupo } + series.map { it.grupo }, PlayerArmazem.gruposEscondidos(this),
            PlayerArmazem.gruposLiberados(this), PlayerArmazem.parental(this))
        val r = ArrayList<ElencoTmdb.NaLista>()
        // Também os sem id do TMDB (a maioria): o encontro é pelo nome+ano (ElencoTmdb.naLista).
        for (f in filmes) if (f.grupo.trim() !in ocultas) r += ElencoTmdb.NaLista(false, f.tmdb ?: InfoExternaCache.guardado(this, f.chave)?.tmdb, Vod.nomeLimpo(f.nome), f.ano)
        for (s in series) if (s.grupo.trim() !in ocultas) r += ElencoTmdb.NaLista(true, s.tmdb ?: InfoExternaCache.guardado(this, s.chave)?.tmdb, Vod.nomeLimpo(s.nome), s.ano)
        return r
    }

    private fun desenharElenco(elenco: List<ElencoTmdb.Pessoa>) {
        if (elenco.isEmpty()) { aviso.text = getString(R.string.elenco_vazio); return }
        aviso.text = getString(R.string.elenco_dica)
        for ((i, p) in elenco.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; setPaddingRelative(dp(6), dp(6), dp(6), dp(6))
            }
            val foto = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Estilo.SUPERFICIE_ALTA) }
            }
            if (p.foto.isNotEmpty()) PlayerLogos.carregar(this, p.foto, foto, lado = 300)
            card.addView(foto, LinearLayout.LayoutParams(dp(120), dp(170)))
            card.addView(TextView(this).apply {
                text = p.nome; textSize = Estilo.Leitura.CARD; setTextColor(Estilo.TEXTO); gravity = Gravity.CENTER
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(dp(130), -2))
            card.addView(TextView(this).apply {
                text = p.personagem; textSize = Estilo.Leitura.CARD - 1; setTextColor(Estilo.TEXTO_3); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(130), -2))
            Estilo.focoCard(card)
            val anterior = card.onFocusChangeListener
            card.setOnFocusChangeListener { v, foco ->
                anterior?.onFocusChange(v, foco)
                if (foco) {
                    pessoaFocada = p.id
                    pendente?.let { ui.removeCallbacks(it) }
                    pendente = Runnable { if (pessoaFocada == p.id) mostrarTitulos(p.id) }.also { ui.postDelayed(it, ESPERA_FOCO_MS) }
                }
            }
            card.setOnClickListener { titulos.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus() }
            fileira.addView(card, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            if (i == 0) card.requestFocus()
        }
    }

    private fun mostrarTitulos(pessoa: Int) {
        val cat = catalogo
        titulos.removeAllViews()
        titulos.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.elenco_procurando)).apply { textSize = Estilo.Leitura.AVISO })
        if (cat == null) return   // o catálogo ainda está carregando: volta aqui quando terminar
        rede.execute {
            val achados = ElencoTmdb.naLista(InfoExternaCache.creditos(pessoa), cat, serie to tmdb, intent.getStringExtra(EXTRA_NOME))
            ui.post {
                if (isDestroyed || pessoaFocada != pessoa) return@post
                titulos.removeAllViews()
                if (achados.isEmpty()) {
                    titulos.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.elenco_nada_na_lista)).apply { textSize = Estilo.Leitura.AVISO })
                    return@post
                }
                for (t in achados.take(12)) {
                    val det = listOfNotNull(getString(if (t.serie) R.string.elenco_serie else R.string.elenco_filme), t.ano?.toString()).joinToString(" · ")
                    val v = Estilo.linhaOpcao(this, t.nome, det, null)
                    v.setOnClickListener {
                        val tela = if (t.serie) SeriesActivity::class.java else FilmesActivity::class.java
                        runCatching { startActivity(Intent(this, tela).putExtra(BuscaUnica.EXTRA_TERMO, t.nome)) }
                    }
                    titulos.addView(v, Estilo.lpLinha(this))
                }
            }
        }
    }

    override fun onDestroy() { rede.shutdownNow(); ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

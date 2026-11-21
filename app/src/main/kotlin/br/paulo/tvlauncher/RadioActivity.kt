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
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.CruzOuvir
import br.paulo.tvlauncher.listen.IconeOuvir
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.weather.Localizacao
import java.util.Locale

/**
 * Tela "Rádio" (ideia 87, 14/11/2026; redesenhada 18/11/2026 no padrão da cruz da HOME, dono: "a tela de rádio está BEM
 * ruim"). Antes era uma lista de Configurações com 60 linhas; agora é uma cruz ([CruzOuvir]): ◀ ▶ trocam a prateleira,
 * ▲ ▼ andam nas rádios, OK toca, OK segurado guarda/tira das favoritas. Prateleiras, na ordem:
 *  - Tocando (só com som): Pausar/Continuar, Favoritar, Parar e "Ao sair da tela" (continua tocando ou para);
 *  - Resultado da última busca, gênero ou país (some ao sair da tela);
 *  - Favoritas (as teclas 1 a 9 do controle tocam direto, e a linha de baixo diz qual), Recentes;
 *  - Perto de você (100 km da TV), Mais ouvidas no país da TV, Gêneros, Países, Buscar pelo nome.
 * Cada rádio mostra o logo do catálogo (ou as iniciais sobre a cor dela), e o canto de cima à direita mostra a que está
 * tocando com a música do momento (metadados ICY, quando a rádio manda). Sem tecla por [ESCURECER_MS] tocando, a tela
 * escurece (o som segue); qualquer tecla acende. Catálogo e espelhos: [Radios].
 *
 * Quem toca é o [OuvirServico] (17/11/2026): sair da tela NÃO para o som, a não ser que o dono escolha o jeito antigo.
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

        /** Cores das rádios sem logo (as iniciais ficam sobre uma delas, sempre a mesma para a mesma rádio). */
        private val CORES = intArrayOf(0xFF45C2B1.toInt(), 0xFF7FB2FF.toInt(), 0xFFF2B84B.toInt(), 0xFF8E92FA.toInt(),
            0xFF9CD27E.toInt(), 0xFFF59E5B.toInt(), 0xFF6FC3B2.toInt(), 0xFFE6C46A.toInt())
        fun corDe(chave: String): Int = CORES[(chave.hashCode() and 0x7FFFFFFF) % CORES.size]
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var cruz: CruzOuvir
    private lateinit var veu: LinearLayout
    private lateinit var veuCapa: CapaOuvir
    private lateinit var veuNome: TextView
    private lateinit var veuMusica: TextView
    private val tocando: Radios.Radio? get() = OuvirServico.instancia?.radio
    /** O que a tela mostrou por último (id, música, pausado): só refaz quando muda. */
    private var visto: Triple<String?, String, Boolean>? = null
    private val mudou: () -> Unit = { aoMudarSom() }
    private var favoritas: List<Radios.Radio> = emptyList()
    private var recentes: List<Radios.Radio> = emptyList()
    private var perto: List<Radios.Radio>? = null
    private var doPais: List<Radios.Radio>? = null
    private var paises: List<Triple<String, String, Int>>? = null
    private var carregando = true
    private var resultado: Pair<String, List<Radios.Radio>>? = null
    private val pais: String = Locale.getDefault().country.uppercase().takeIf { it.length == 2 } ?: ""
    private val escurecer = Runnable { if (OuvirServico.instancia?.tocando == true) veuLigado(true) }

    /**
     * O véu cobre a tela; a cruz por baixo some junto (debug 20/11/2026): ela seguia desenhando o ícone "Tocando" a
     * 30 quadros por segundo debaixo do preto, no Chromecast, a noite toda. Ao acender, o foco volta para ela.
     */
    private fun veuLigado(ligado: Boolean) {
        if ((veu.visibility == View.VISIBLE) == ligado) return
        veu.visibility = if (ligado) View.VISIBLE else View.GONE
        cruz.visibility = if (ligado) View.INVISIBLE else View.VISIBLE
        if (!ligado) cruz.requestFocus()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val fonte = Fontes.nunito(this, 500)
        val forte = Fontes.nunito(this, 800)
        cruz = CruzOuvir(this, fonte, forte).apply {
            definirTitulo(getString(R.string.ra_coluna))
            definirDicas(getString(R.string.grv_dica_ok) to getString(R.string.ra_dica_ouvir),
                getString(R.string.grv_dica_segurar) to getString(R.string.ra_dica_favoritar),
                "▶❙❙" to getString(R.string.ra_pausar))
            aoSom = { s -> if (OuvirServico.instancia?.tocando != true) SonsCruz.tocar(this@RadioActivity, s, CruzLogica.Categoria.OUVIR) }
        }
        // Véu da tela escura: quase preto, só a capa, o nome e a música, para o quarto ficar escuro com o rádio ligado.
        veuCapa = CapaOuvir(this, 18f).apply { fonte(forte); alpha = 0.5f }
        veuNome = Estilo.texto(this, Estilo.Papel.TITULO, "").apply { textSize = 34f; gravity = Gravity.CENTER; alpha = 0.55f }
        veuMusica = Estilo.texto(this, Estilo.Papel.CORPO, "").apply { textSize = 22f; gravity = Gravity.CENTER; alpha = 0.45f }
        veu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 0, 0, 0)); visibility = View.GONE
            addView(veuCapa, LinearLayout.LayoutParams(Estilo.dp(this@RadioActivity, 120), Estilo.dp(this@RadioActivity, 120)))
            addView(veuNome, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Estilo.dp(this@RadioActivity, 20) })
            addView(veuMusica, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Estilo.dp(this@RadioActivity, 12) })
        }
        setContentView(FrameLayout(this).apply {
            background = Estilo.fundoTela(this@RadioActivity)
            addView(cruz, FrameLayout.LayoutParams(-1, -1))
            addView(veu, FrameLayout.LayoutParams(-1, -1))
        })
        montar(focarInicio = true)
        cruz.requestFocus()
        carregarListas()
        intent.getStringExtra(EXTRA_BUSCA)?.trim()?.takeIf { it.length >= 2 }?.let { t ->
            buscar(getString(R.string.ra_resultado, t)) { Radios.urlNome(it, t) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Favoritas podem ter mudado pelo painel (rádio colada) e recentes pelo serviço, com a tela atrás.
        val app = applicationContext
        Fundo.rodar("radios") {
            val f = Radios.favoritas(app); val r = Radios.recentes(app)
            runOnUiThread { if (!isDestroyed && (f != favoritas || r != recentes)) { favoritas = f; recentes = r; montar() } }
        }
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
        veuLigado(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resultado = null   // a busca não volta na próxima vez (a tela abre limpa)
        // O jeito antigo, para quem escolheu: sair da tela para o som. Tela que só apagou também conta (o serviço já para).
        if (!continuarFora(this)) OuvirServico.instancia?.parar()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); OuvirServico.esquecer(mudou); super.onDestroy() }

    /** O serviço mudou (tocou, pausou, música nova, parou): véu, tela acesa, o cartão e a prateleira Tocando. */
    private fun aoMudarSom() {
        if (isDestroyed || !::veu.isInitialized) return
        val s = OuvirServico.instancia
        val r = s?.radio
        veuNome.text = r?.nome.orEmpty(); veuMusica.text = s?.musica.orEmpty()
        r?.let { veuCapa.cor = corDe(it.id); veuCapa.monograma = CapaOuvir.iniciais(it.nome); veuCapa.carregar(it.logo) }
        // Tela acesa só com o som saindo E esta tela na frente; fora dela, o protetor de tela entra normalmente.
        if (s?.tocando == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (r == null) { ui.removeCallbacks(escurecer); veuLigado(false) }
        val agora = Triple(r?.id, s?.musica.orEmpty(), s?.pausado == true)
        if (agora != visto) {
            // Começou a tocar (OK numa rádio): o foco fica onde está; o cartão de cima à direita já mostra o que toca.
            visto = agora
            montar()
            mexeu()
        }
    }

    /** Já apertou alguma tecla aqui: a entrada não muda mais sozinha quando as listas chegam. */
    private var mexeuNaLista = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            mexeuNaLista = true
            mexeu()
            if (veu.visibility == View.VISIBLE) { veuLigado(false); return true }   // 1ª tecla só acende a tela
            val n = e.keyCode - KeyEvent.KEYCODE_1
            if (n in 0..8) { favoritas.getOrNull(n)?.let { tocar(it) }; return true }
            when (e.keyCode) {
                // ⏯ do controle: pausa/continua daqui também (a sessão de mídia já faz isso fora da tela)
                // ▶ e ⏸ separados (controle da LG pelo HDMI) fazem o que dizem; só ⏯ alterna (debug 20/11/2026).
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (tocando != null) { if (e.repeatCount == 0) OuvirServico.instancia?.continuar(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (tocando != null) { if (e.repeatCount == 0) OuvirServico.instancia?.pausar(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (tocando != null) { if (e.repeatCount == 0) OuvirServico.instancia?.alternar(); return true }
            }
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
        Fundo.rodar("radios") {
            val fav = Radios.favoritas(app); val rec = Radios.recentes(app)
            // A 1ª montagem (onCreate) roda com as listas ainda vazias e escolhia a prateleira errada (Sala, 18/11/2026: abria em
            // "Nenhuma favorita ainda" com 2 rádios em Recentes). Com as listas lidas, a entrada é escolhida de novo.
            runOnUiThread { if (!isDestroyed) { favoritas = fav; recentes = rec; montar(focarInicio = !mexeuNaLista) } }
            val ponto = runCatching { Localizacao.atual(app) { null } }.getOrNull()   // sem rede extra: só o que a TV já sabe
            val p = ponto?.let { pt -> Radios.estacoes(Radios.pedir { Radios.urlPerto(it, pt.lat, pt.lon, 30) }) }
            val d = if (pais.isNotEmpty()) Radios.estacoes(Radios.pedir { Radios.urlPais(it, pais) }) else
                Radios.estacoes(Radios.pedir { "https://$it/json/stations/topclick/${Radios.LIMITE}?hidebroken=true" })
            val ps = Radios.paises(Radios.pedir { Radios.urlPaises(it) }).take(60)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                perto = p; doPais = d; paises = ps; carregando = false
                montar()
            }
        }
    }

    private fun buscar(titulo: String, montarUrl: (String) -> String) {
        val app = applicationContext
        resultado = titulo to emptyList()
        montar(focar = "res" to null)
        Fundo.rodar("radios") {
            val r = Radios.estacoes(Radios.pedir(montarUrl))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                resultado = titulo to r
                montar(focar = "res" to null)
                if (r.isEmpty()) AvisoTv.rapido(app, titulo, getString(R.string.ra_nada), 5)
            }
        }
    }

    // ---------------------------------------------------------------- tela

    private fun montar(focar: Pair<String, String?>? = null, focarInicio: Boolean = false) {
        val l = ArrayList<CruzOuvir.Prateleira>()
        val s = OuvirServico.instancia
        val r = tocando
        if (r != null && s != null) {
            val fav = favoritas.any { it.id == r.id }
            l += CruzOuvir.Prateleira("tocando", getString(R.string.ouv_prat_tocando), IconeOuvir.Tipo.TOCANDO, listOf(
                CruzOuvir.Item("pausar", getString(if (s.pausado) R.string.ouv_continuar else R.string.ouv_pausar),
                    CruzLogica.linhas(r.nome, s.musica, detalhe(r)), if (s.pausado) IconeOuvir.Tipo.CONTINUAR else IconeOuvir.Tipo.PAUSAR,
                    abrir = { OuvirServico.instancia?.alternar() }),
                CruzOuvir.Item("favoritar", getString(if (fav) R.string.ouv_desfavoritar else R.string.ouv_favoritar),
                    if (fav) getString(R.string.ouv_tecla, favoritas.indexOfFirst { it.id == r.id } + 1).takeIf { favoritas.indexOfFirst { it.id == r.id } < 9 }.orEmpty()
                    else getString(R.string.ouv_favoritar_det),
                    IconeOuvir.Tipo.FAVORITAS, abrir = { favoritar(r) }),
                CruzOuvir.Item("parar", getString(R.string.ra_parar),
                    getString(if (continuarFora(this)) R.string.ra_parar_det else R.string.ra_parar_det_antigo), IconeOuvir.Tipo.PARAR,
                    abrir = { OuvirServico.instancia?.parar() }),
                CruzOuvir.Item("ao_sair", getString(R.string.ouv_ao_sair),
                    getString(if (continuarFora(this)) R.string.ouv_ao_sair_continua else R.string.ouv_ao_sair_para), IconeOuvir.Tipo.SAIR,
                    abrir = {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CHAVE_CONTINUAR, !continuarFora(this)).apply()
                        montar()
                    }),
            ))
        }
        resultado?.let { (titulo, lista) ->
            l += CruzOuvir.Prateleira("res", titulo, IconeOuvir.Tipo.BUSCAR,
                if (lista.isEmpty()) listOf(aviso("res:carregando", getString(R.string.ra_carregando), "", IconeOuvir.Tipo.BUSCAR))
                else lista.map { itemRadio("res:", it) })
        }
        l += CruzOuvir.Prateleira("fav", getString(R.string.ra_favoritas), IconeOuvir.Tipo.FAVORITAS,
            if (favoritas.isEmpty()) listOf(aviso("fav:vazio", getString(R.string.ouv_sem_favoritas), getString(R.string.ouv_sem_favoritas_det), IconeOuvir.Tipo.FAVORITAS))
            else favoritas.mapIndexed { i, x -> itemRadio("fav:", x, numero = if (i < 9) i + 1 else null) })
        l += CruzOuvir.Prateleira("rec", getString(R.string.ouv_recentes), IconeOuvir.Tipo.RECENTES,
            if (recentes.isEmpty()) listOf(aviso("rec:vazio", getString(R.string.ouv_sem_recentes), getString(R.string.ouv_sem_recentes_det), IconeOuvir.Tipo.RECENTES))
            else recentes.map { itemRadio("rec:", it) })
        if (carregando) {
            l += CruzOuvir.Prateleira("perto", getString(R.string.ra_perto), IconeOuvir.Tipo.PERTO,
                listOf(aviso("perto:carregando", getString(R.string.ra_carregando), "", IconeOuvir.Tipo.PERTO)))
        } else {
            perto?.takeIf { it.isNotEmpty() }?.let { lista ->
                l += CruzOuvir.Prateleira("perto", getString(R.string.ra_perto), IconeOuvir.Tipo.PERTO, lista.map { itemRadio("perto:", it) })
            }
            val d = doPais.orEmpty()
            val nomePais = if (pais.isEmpty()) "" else Locale("", pais).getDisplayCountry(Locale.getDefault())
            l += CruzOuvir.Prateleira("pais", nomePais.ifEmpty { getString(R.string.ra_mundo) }, IconeOuvir.Tipo.PAIS,
                if (d.isEmpty()) listOf(aviso("pais:nada", getString(R.string.ra_nada), getString(R.string.ra_sem_catalogo), IconeOuvir.Tipo.PAIS))
                else d.map { itemRadio("pais:", it) })
        }
        l += CruzOuvir.Prateleira("generos", getString(R.string.ra_generos), IconeOuvir.Tipo.GENEROS, Radios.GENEROS.map { g ->
            val nome = g.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            CruzOuvir.Item("gen:$g", nome, "", cor = corDe(g), monograma = CapaOuvir.iniciais(nome),
                abrir = { buscar(nome) { Radios.urlGenero(it, g) } })
        })
        paises?.takeIf { it.isNotEmpty() }?.let { ps ->
            l += CruzOuvir.Prateleira("paises", getString(R.string.ouv_paises), IconeOuvir.Tipo.PAIS, ps.map { (c, n, q) ->
                val nome = Locale("", c).getDisplayCountry(Locale.getDefault()).ifEmpty { n }
                CruzOuvir.Item("pa:$c", nome, getString(R.string.ouv_n_radios, q), cor = corDe(c), monograma = CapaOuvir.bandeira(c).ifEmpty { c },
                    abrir = { buscar(nome) { Radios.urlPais(it, c) } })
            })
        }
        l += CruzOuvir.Prateleira("buscar", getString(R.string.ra_procurar), IconeOuvir.Tipo.BUSCAR, listOf(
            CruzOuvir.Item("buscar", getString(R.string.ra_buscar), getString(R.string.ra_buscar_det), IconeOuvir.Tipo.BUSCAR, abrir = { pedirNome() })))
        // Abrindo: o que toca (se toca), senão as favoritas (se há), senão as mais ouvidas do país.
        val inicio = when {
            !focarInicio -> null
            r != null -> "tocando" to null
            favoritas.isNotEmpty() -> "fav" to null
            recentes.isNotEmpty() -> "rec" to null
            else -> "pais" to null
        }
        cruz.definir(l, focar ?: inicio)
        cruz.tocando(cartao())
    }

    private fun cartao(): CruzOuvir.Tocando? {
        val s = OuvirServico.instancia ?: return null
        val r = s.radio ?: return null
        return CruzOuvir.Tocando(r.nome, CruzLogica.linhas(s.musica, detalhe(r)), r.logo, corDe(r.id), CapaOuvir.iniciais(r.nome),
            cobrir = false, progresso = null, tempo = null, pausado = s.pausado,
            aoVivo = if (s.pausado) null else getString(R.string.ouv_ao_vivo).uppercase(), chave = r.id)
    }

    private fun aviso(id: String, nome: String, sub: String, icone: IconeOuvir.Tipo) = CruzOuvir.Item(id, nome, sub, icone)

    /** "Brasil": só o país (o "128 kbps" saiu: jargão que não ajuda a escolher a rádio; crítica de design 19/11/2026). */
    private fun detalhe(r: Radios.Radio): String =
        r.pais.takeIf { it.length == 2 }?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) }.orEmpty()

    private fun itemRadio(prefixo: String, r: Radios.Radio, numero: Int? = null): CruzOuvir.Item {
        val tocandoEsta = tocando?.id == r.id
        val s = OuvirServico.instancia
        val estado = when {
            tocandoEsta && s?.pausado == true -> getString(R.string.ouv_pausado)
            tocandoEsta -> "● " + getString(R.string.ouv_tocando_agora)
            numero != null -> getString(R.string.ouv_tecla, numero)
            favoritas.any { it.id == r.id } -> "★ " + getString(R.string.ra_favoritas)
            else -> null
        }
        return CruzOuvir.Item(prefixo + r.id, r.nome,
            CruzLogica.linhas(estado, if (tocandoEsta) s?.musica else null, r.tags.ifEmpty { null }, detalhe(r)),
            capa = r.logo, cor = corDe(r.id), monograma = CapaOuvir.iniciais(r.nome),
            abrir = { tocar(r) }, opcoes = { favoritar(r) })
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
        CampoTv.enterConfirma(campo, d)
        d.setOnShowListener { campo.requestFocus() }
        d.show()
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
        if (tocando?.id == r.id) { OuvirServico.instancia?.alternar(); return }
        OuvirServico.tocarRadio(this, r)
        veuNome.text = r.nome; veuMusica.text = ""
        // A recente nova entra na lista já (o serviço grava o arquivo; a tela não espera ele).
        recentes = Radios.comRecente(recentes, r)
        mexeu()
    }
}

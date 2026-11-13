package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela "Casa" (ideia grande 1): os aparelhos do Home Assistant com o controle. OK liga/desliga (cena e script: roda).
 * Configurar o endereço e o token é pelo painel (aba Extras), com PIN: digitar um token longo no controle não dá.
 *
 * Redesenho de 13/11/2026 (dono: "está muito feio"; antes era uma lista cinza "Nome · on"):
 *  - grade de cartões no molde dos tiles do painel rápido: ícone do tipo num círculo, nome e o estado embaixo;
 *  - ligado = círculo e estado no verde-água do sistema (o único acento); foco = cartão branco que cresce, como na HOME;
 *  - cenas e rotinas num grupo, aparelhos em outro, e o Google Home como um cartão a mais (antes era a 1ª linha);
 *  - OK muda o cartão na hora (otimista) e confirma com o Home Assistant logo depois;
 *  - sem configuração: estado vazio com a explicação, nunca uma tela só com o título.
 */
class CasaActivity : Activity() {
    private lateinit var grade: LinearLayout
    private lateinit var resumo: TextView
    private lateinit var rolagem: ScrollView
    /** Último resultado mostrado, para refazer a grade depois de um OK sem esperar a rede. */
    private var entidades: List<CasaHA.Entidade> = emptyList()
    /** Estado trocado na hora pelo OK, até o Home Assistant confirmar (id -> estado novo). */
    private val otimista = HashMap<String, String>()
    /** Cartão que tinha o foco, para devolver o foco ao mesmo aparelho quando a grade é refeita. */
    private var focoId: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(this@CasaActivity)
            clipChildren = false; clipToPadding = false
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.E3))
        }
        // Título e resumo à esquerda; o alto à direita é da hora (Moldura.kt).
        raiz.addView(Estilo.tituloTela(this, getString(R.string.casa_titulo)))
        resumo = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.casa_carregando))
        raiz.addView(resumo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        grade = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            // Folga em volta: o cartão focado cresce 5% e não pode ser cortado pela borda da rolagem.
            setPadding(dp(6), dp(Estilo.E1), dp(6), dp(Estilo.E3))
        }
        rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFillViewport = true
            addView(grade)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = -dp(6); rightMargin = -dp(6) })
        raiz.addView(Estilo.barraDicas(this, "OK" to getString(R.string.casa_dica_acionar),
            getString(R.string.cl_tecla_voltar) to getString(R.string.rede_dica_sair)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        setContentView(raiz)
        Estilo.entrar(raiz)
    }

    override fun onResume() { super.onResume(); carregar() }

    private fun carregar() {
        if (!CasaHA.configurado(this)) { mostrarSemConfig(); return }
        val app = applicationContext
        Fundo.rodar {
            val r = try { Result.success(CasaHA.listar(app)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread { if (!isFinishing) mostrar(r) }
        }
    }

    // ---------------------------------------------------------------- estados da tela

    private fun mostrarSemConfig() {
        resumo.text = ""
        grade.removeAllViews()
        val google = cartaoGoogle()
        grade.addView(Estilo.estadoVazio(this, IconeCardDrawable("casa"), getString(R.string.casa_sem_config_titulo),
            getString(R.string.casa_sem_config)))
        grade.addView(linha(listOf(google)))
        google.requestFocus()
    }

    private fun mostrar(r: Result<List<CasaHA.Entidade>>) {
        val lista = r.getOrElse {
            if (entidades.isEmpty()) montar(emptyList())   // ao menos o Google Home fica à mão
            resumo.text = getString(R.string.casa_erro, it.message ?: it.javaClass.simpleName)
            return
        }
        // O que o Home Assistant confirmou deixa de ser "otimista".
        for (e in lista) if (otimista[e.id] == e.estado) otimista.remove(e.id)
        entidades = lista
        montar(lista)
    }

    /** Refaz a grade: cenas e rotinas, aparelhos e o Google Home, em linhas de [COLUNAS] cartões. */
    private fun montar(lista: List<CasaHA.Entidade>) {
        val focoAntes = focoId
        grade.removeAllViews()
        val cenas = lista.filter { it.dominio == "scene" || it.dominio == "script" }
        val aparelhos = lista.filter { it.dominio != "scene" && it.dominio != "script" }
        val ligados = aparelhos.count { ligado(it) }
        resumo.text = when {
            lista.isEmpty() -> getString(R.string.casa_vazio)
            aparelhos.isEmpty() -> getString(R.string.casa_resumo_so_cenas, cenas.size)
            else -> getString(R.string.casa_resumo, ligados, aparelhos.size)
        }
        var alvo: View? = null
        var primeiro: View? = null
        fun grupo(nome: String, cartoes: List<View>) {
            if (cartoes.isEmpty()) return
            grade.addView(Estilo.secao(this, nome))
            cartoes.chunked(COLUNAS).forEach { grade.addView(linha(it)) }
            if (primeiro == null) primeiro = cartoes.first()
        }
        val cartoesAparelhos = aparelhos.map { e -> cartao(e).also { if (e.id == focoAntes) alvo = it } }
        val cartoesCenas = cenas.map { e -> cartao(e).also { if (e.id == focoAntes) alvo = it } }
        // Aparelhos primeiro: luz e tomada são o uso do dia a dia; cena é de vez em quando.
        grupo(getString(R.string.casa_secao_aparelhos), cartoesAparelhos)
        grupo(getString(R.string.casa_secao_cenas), cartoesCenas)
        val google = cartaoGoogle()
        grupo(getString(R.string.casa_secao_google), listOf(google))
        (alvo ?: primeiro ?: google).requestFocus()
    }

    // ---------------------------------------------------------------- cartões

    /** Uma linha da grade: [COLUNAS] lugares de mesma largura (os que faltam ficam vazios, sem esticar os outros). */
    private fun linha(cartoes: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false; clipToPadding = false
        for (i in 0 until COLUNAS) {
            val v = cartoes.getOrNull(i) ?: View(this@CasaActivity)
            addView(v, LinearLayout.LayoutParams(0, dp(ALTURA_DP), 1f).apply { if (i > 0) marginStart = dp(ESPACO_DP) })
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ESPACO_DP) }
    }

    private fun cartao(e: CasaHA.Entidade): View {
        val estado = otimista[e.id] ?: e.estado
        val acao = if (e.dominio == "scene" || e.dominio == "script") "ligar" else "alternar"
        val c = Cartao(CasaHA.iconeDe(e.dominio), e.nome, rotuloEstado(e.dominio, estado), CasaHA.ligado(e.dominio, estado),
            indisponivel = estado == "unavailable" || estado == "unknown")
        c.casca.setOnClickListener {
            if (c.indisponivel) return@setOnClickListener
            focoId = e.id
            // Na hora: o cartão já mostra o estado novo (a resposta do Home Assistant leva até ~1 s).
            if (acao == "alternar") {
                val novo = CasaHA.inverso(e.dominio, estado)
                if (novo != null) { otimista[e.id] = novo; c.mudar(rotuloEstado(e.dominio, novo), CasaHA.ligado(e.dominio, novo)) }
            }
            val app = applicationContext
            Fundo.rodar {
                val msg = try { CasaHA.acionar(app, e.id, acao); null } catch (ex: Exception) { ex.message ?: ex.javaClass.simpleName }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (msg != null) { otimista.remove(e.id); resumo.text = getString(R.string.casa_erro, msg) }
                    carregar()
                }
            }
        }
        c.casca.setOnFocusChangeListener { _, foco -> if (foco) focoId = e.id; c.pintar() }
        return c.casca
    }

    private fun cartaoGoogle(): View {
        val c = Cartao("casa", getString(R.string.casa_google_home), getString(R.string.casa_google_home_det), false)
        // Google Home (dono, 10/11/2026): abre o painel do Google TV (luzes, câmeras, timer), o mesmo do HOME segurado.
        c.casca.setOnClickListener { Home.abrirGoogleHome(this) }
        c.casca.setOnFocusChangeListener { _, foco -> if (foco) focoId = null; c.pintar() }
        return c.casca
    }

    /**
     * Cartão da grade: círculo com o ícone no alto, nome (2 linhas no máximo) e o estado embaixo.
     * Repouso = superfície do sistema; foco = branco que cresce 5%; ligado = círculo e estado em verde-água.
     */
    private inner class Cartao(tipoIcone: String, nome: String, estado: String, var ligado: Boolean,
                               val indisponivel: Boolean = false) {
        private val icone = IconeCard(this@CasaActivity, tipoIcone)
        private val circulo = FrameLayout(this@CasaActivity).apply {
            addView(icone, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        private val nomeTv = Estilo.texto(this@CasaActivity, Estilo.Papel.CORPO, nome).apply {
            textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setLineSpacing(0f, 1.05f)
        }
        private val estadoTv = Estilo.texto(this@CasaActivity, Estilo.Papel.LEGENDA, estado).apply {
            textSize = 12.5f; typeface = Estilo.fonte(500); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        val casca = LinearLayout(this@CasaActivity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(12), dp(12))
            addView(circulo, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(View(this@CasaActivity), LinearLayout.LayoutParams(0, 0, 1f))   // empurra o texto para baixo
            addView(nomeTv)
            addView(estadoTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            if (indisponivel) alpha = 0.5f
        }

        init { pintar() }

        fun mudar(estado: String, liga: Boolean) { estadoTv.text = estado; ligado = liga; pintar() }

        fun pintar() {
            val foco = casca.isFocused
            casca.background = Estilo.superficie(this@CasaActivity, foco)
            nomeTv.setTextColor(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            nomeTv.typeface = Estilo.fonte(if (foco) 600 else 500)
            circulo.background = Estilo.fundo(this@CasaActivity, when {
                ligado && foco -> Estilo.ACENTO_NO_FOCO
                ligado -> Estilo.ACENTO
                foco -> 0x140B0F17
                else -> Estilo.SUPERFICIE_ALTA
            }, Estilo.RAIO_PILULA)
            icone.definirCor(when {
                ligado && foco -> Estilo.FOCO
                ligado -> Estilo.TEXTO_NO_FOCO
                foco -> Estilo.TEXTO_NO_FOCO
                else -> Estilo.TEXTO
            })
            estadoTv.setTextColor(when {
                ligado && foco -> Estilo.ACENTO_NO_FOCO
                ligado -> Estilo.ACENTO
                foco -> Estilo.TEXTO_NO_FOCO_2
                else -> Estilo.TEXTO_3
            })
            casca.animate().scaleX(if (foco) ESCALA else 1f).scaleY(if (foco) ESCALA else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }

    // ---------------------------------------------------------------- textos e ícones (puros)

    private fun ligado(e: CasaHA.Entidade) = CasaHA.ligado(e.dominio, otimista[e.id] ?: e.estado)

    private fun rotuloEstado(dominio: String, estado: String): String = getString(when {
        estado == "unavailable" || estado == "unknown" -> R.string.casa_indisponivel
        dominio == "scene" || dominio == "script" -> R.string.casa_rodar
        dominio == "cover" -> if (estado == "closed" || estado == "closing") R.string.casa_fechada else R.string.casa_aberta
        estado == "on" -> R.string.casa_ligado
        else -> R.string.casa_desligado
    })

    /** Ícone de dentro do [Estilo.estadoVazio], que pede um Drawable: o mesmo desenho do IconeCard. */
    private inner class IconeCardDrawable(tipo: String) : android.graphics.drawable.Drawable() {
        private val v = IconeCard(this@CasaActivity, tipo)
        override fun draw(c: android.graphics.Canvas) {
            v.layout(0, 0, bounds.width(), bounds.height())
            c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); v.draw(c); c.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    companion object {
        private const val COLUNAS = 4
        private const val ALTURA_DP = 100
        private const val ESPACO_DP = 12
        private const val ESCALA = 1.05f
    }
}

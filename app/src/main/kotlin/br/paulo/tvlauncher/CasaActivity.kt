package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
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
 *  - aparelhos num grupo, cenas e rotinas em outro, e o Google Home como um cartão a mais (antes era a 1ª linha);
 *  - OK muda o cartão na hora (otimista) e confirma com o Home Assistant logo depois;
 *  - luz com brilho: SEGURAR OK entra no ajuste, ◀ ▶ muda de 10 em 10 %, OK ou VOLTAR sai (◀ ▶ soltos continuam
 *    andando entre os cartões, que é o que a pessoa espera numa grade);
 *  - luz com cor ou temperatura (ideia 50, 14/11/2026): no mesmo ajuste, ▲ ▼ trocam o tom (quente, neutra, fria e,
 *    na luz colorida, 7 cores; ver [CasaHA.CorLuz]);
 *  - ao vivo: com a tela aberta, relê o Home Assistant a cada [VIVO_MS] e atualiza só os cartões (sem refazer a grade,
 *    para o foco não pular); uma luz apagada pelo celular aparece apagada aqui;
 *  - sem configuração: estado vazio com a explicação, nunca uma tela só com o título.
 *
 * Por cômodo (13/11/2026, dono): com as áreas do Home Assistant, um grupo por cômodo com o nome dele no título, em ordem
 * alfabética, e "Sem cômodo" por último. As áreas vêm do POST /api/template ([CasaHA.areas]), relidas a cada [AREAS_MS]
 * (mudam pouco); se o token não puder ler (não é de administrador) ou a casa não tiver áreas, fica o agrupamento antigo.
 */
class CasaActivity : Activity() {
    private lateinit var grade: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var resumo: TextView
    private lateinit var dicas: FrameLayout
    private val ui = Handler(Looper.getMainLooper())

    /** Último resultado mostrado e os cartões de cada aparelho (id -> cartão), para atualizar sem refazer a grade. */
    private var entidades: List<CasaHA.Entidade> = emptyList()
    /** Cômodo de cada aparelho (null = não deu para ler) e quando foi lido (relógio do sistema). */
    private var areas: Map<String, String>? = null
    private var areasEm = 0L
    private val cartoes = HashMap<String, Cartao>()
    /** Mudança feita aqui e ainda não confirmada: o estado/brilho mostrado e até quando vale (relógio do sistema). */
    private val otimista = HashMap<String, Pendente>()
    private class Pendente(val estado: String, val brilho: Int?, val ate: Long)
    /** Cartão que tinha o foco, para devolver o foco ao mesmo aparelho quando a grade é refeita. */
    private var focoId: String? = null
    private var carregando = false
    private var aberta = false

    /** Ajuste de brilho em andamento (segurou OK numa luz): o cartão e o valor que está na tela. */
    private var ajuste: Cartao? = null
    private var ajustePct = 0
    /** O OK que abriu o ajuste ainda vai soltar: esse "soltar" não pode fechar o ajuste. */
    private var ignorarSoltarOk = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(this@CasaActivity)
            // A rolagem fica presa à própria área: sem isto os cartões de baixo eram desenhados por cima das dicas.
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
            setPadding(dp(FOLGA_DP), dp(Estilo.E1), dp(FOLGA_DP), dp(Estilo.E3))
        }
        rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true
            // Esmaece a borda de baixo quando há mais cartões: mostra que dá para descer.
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(Estilo.E3))
            addView(grade)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = -dp(FOLGA_DP); rightMargin = -dp(FOLGA_DP) })
        dicas = FrameLayout(this)
        raiz.addView(dicas, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        mostrarDicas(false)
        setContentView(raiz)
        Estilo.entrar(raiz)
    }

    override fun onResume() {
        super.onResume()
        aberta = true
        carregar()
        ui.postDelayed(vivo, VIVO_MS)
    }

    override fun onPause() {
        aberta = false
        ui.removeCallbacks(vivo)
        sairDoAjuste()
        super.onPause()
    }

    /** Releitura periódica enquanto a tela está na frente (para no onPause: nada roda com a tela fechada). */
    private val vivo = object : Runnable {
        override fun run() {
            if (!aberta) return
            if (ajuste == null) carregar()   // no meio do ajuste de brilho, a releitura brigaria com a pessoa
            ui.postDelayed(this, VIVO_MS)
        }
    }

    private fun carregar() {
        if (!CasaHA.configurado(this)) { if (grade.childCount == 0) mostrarSemConfig(); return }
        if (carregando) return
        carregando = true
        val app = applicationContext
        val lerAreas = areasEm == 0L || SystemClock.elapsedRealtime() - areasEm > AREAS_MS
        val antes = areas
        Fundo.rodar {
            val r = try { Result.success(CasaHA.listar(app)) } catch (e: Exception) { Result.failure(e) }
            // Áreas só quando a lista veio (o Home Assistant respondeu); falhar aqui não é erro de tela: fica sem cômodos.
            val novas = if (lerAreas && r.isSuccess) try { CasaHA.areas(app) } catch (e: Exception) { null } else antes
            runOnUiThread {
                carregando = false
                if (isFinishing) return@runOnUiThread
                if (lerAreas && r.isSuccess) areasEm = SystemClock.elapsedRealtime()
                if (novas != areas) { areas = novas; cartoes.clear() }   // cômodos mudaram: refaz a grade com os grupos novos
                mostrar(r)
            }
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
            if (entidades.isEmpty() && grade.childCount == 0) montar(emptyList())   // ao menos o Google Home fica à mão
            resumo.text = getString(R.string.casa_erro, it.message ?: it.javaClass.simpleName)
            return
        }
        val agora = SystemClock.elapsedRealtime()
        // O que o Home Assistant já confirmou (ou passou do prazo) deixa de ser "otimista".
        for (e in lista) otimista[e.id]?.let { p -> if (p.estado == e.estado || agora > p.ate) otimista.remove(e.id) }
        val mesmos = lista.map { it.id } == entidades.map { it.id } && cartoes.isNotEmpty()
        entidades = lista
        if (mesmos) { for (e in lista) cartoes[e.id]?.let { atualizar(it, e) }; escreverResumo() }
        else montar(lista)
    }

    private fun escreverResumo() {
        val aparelhos = entidades.filter { !cena(it) && !it.somenteLeitura && !it.camera }
        val ligados = aparelhos.count { CasaHA.ligado(it.dominio, estadoDe(it)) }
        resumo.text = when {
            entidades.isEmpty() -> getString(R.string.casa_vazio)
            aparelhos.isEmpty() -> getString(R.string.casa_resumo_so_cenas, entidades.size)
            else -> getString(R.string.casa_resumo, ligados, aparelhos.size)
        }
    }

    /**
     * Refaz a grade em linhas de [COLUNAS] cartões: um grupo por cômodo (com áreas) ou aparelhos e cenas (sem), e o
     * Google Home por último.
     */
    private fun montar(lista: List<CasaHA.Entidade>) {
        val focoAntes = focoId
        grade.removeAllViews()
        cartoes.clear()
        escreverResumo()
        var alvo: View? = null
        var primeiro: View? = null
        fun grupo(nome: String, vistas: List<View>) {
            if (vistas.isEmpty()) return
            grade.addView(Estilo.secao(this, nome).apply { tag = SECAO })
            vistas.chunked(COLUNAS).forEach { grade.addView(linha(it)) }
            if (primeiro == null) primeiro = vistas.first()
        }
        fun cartoesDe(l: List<CasaHA.Entidade>) = l.map { e -> cartao(e).casca.also { if (e.id == focoAntes) alvo = it } }
        val a = areas
        if (a != null && lista.any { it.id in a }) {
            for ((comodo, l) in CasaHA.agruparPorComodo(lista, a)) grupo(comodo ?: getString(R.string.casa_sem_comodo), cartoesDe(l))
        } else {
            // Sem cômodos: aparelhos primeiro (luz e tomada são o uso do dia a dia; cena é de vez em quando).
            grupo(getString(R.string.casa_secao_aparelhos), cartoesDe(lista.filter { !cena(it) }))
            grupo(getString(R.string.casa_secao_cenas), cartoesDe(lista.filter { cena(it) }))
        }
        val google = cartaoGoogle()
        grupo(getString(R.string.casa_secao_google), listOf(google))
        (alvo ?: primeiro ?: google).requestFocus()
        mostrarDicas(lista.any { it.regulavel }, lista.any { it.cores.isNotEmpty() }, lista.any { it.clima != null })
    }

    private fun mostrarDicas(temBrilho: Boolean, temCor: Boolean = false, temClima: Boolean = false) {
        val pares = mutableListOf("OK" to getString(R.string.casa_dica_acionar))
        if (temClima && !temBrilho) pares += getString(R.string.casa_tecla_segurar_ok) to getString(R.string.casa_dica_temp)
        if (temBrilho) pares += getString(R.string.casa_tecla_segurar_ok) to getString(if (temCor) R.string.casa_dica_brilho_cor else R.string.casa_dica_brilho)
        pares += getString(R.string.cl_tecla_voltar) to getString(R.string.rede_dica_sair)
        dicas.removeAllViews()
        dicas.addView(Estilo.barraDicas(this, *pares.toTypedArray()))
    }

    // ---------------------------------------------------------------- cartões

    /** Uma linha da grade: [COLUNAS] lugares de mesma largura (os que faltam ficam vazios, sem esticar os outros). */
    private fun linha(vistas: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false; clipToPadding = false
        for (i in 0 until COLUNAS) {
            val v = vistas.getOrNull(i) ?: View(this@CasaActivity)
            addView(v, LinearLayout.LayoutParams(0, dp(ALTURA_DP), 1f).apply { if (i > 0) marginStart = dp(ESPACO_DP) })
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ESPACO_DP) }
    }

    private fun cena(e: CasaHA.Entidade) = e.dominio == "scene" || e.dominio == "script"
    private fun estadoDe(e: CasaHA.Entidade) = otimista[e.id]?.estado ?: e.estado
    private fun brilhoDe(e: CasaHA.Entidade) = otimista[e.id]?.let { it.brilho } ?: e.brilho

    private fun cartao(e: CasaHA.Entidade): Cartao {
        val c = Cartao(CasaHA.iconeDe(e.dominio), e.nome)
        cartoes[e.id] = c
        atualizar(c, e)
        c.casca.setOnClickListener {
            val atual = entidades.firstOrNull { it.id == e.id } ?: e
            val estado = estadoDe(atual)
            if (estado == "unavailable" || estado == "unknown") return@setOnClickListener
            if (atual.somenteLeitura) { carregar(); return@setOnClickListener }   // ideia 51: sensor só se lê; OK relê
            if (atual.camera) {   // ideia 52: a foto de agora, por cima, como a da campainha
                resumo.text = getString(R.string.casa_camera_abrindo)
                val nome = atual.nome
                enviar(e.id) { CasaHA.mostrarCamera(it, e.id, nome) }
                return@setOnClickListener
            }
            focoId = e.id
            val acao = if (cena(atual)) "ligar" else "alternar"
            // Na hora: o cartão já mostra o estado novo (a resposta do Home Assistant leva até ~1 s).
            CasaHA.inverso(atual.dominio, estado)?.let { novo ->
                otimista[e.id] = Pendente(novo, if (novo == "on") brilhoDe(atual) else null, SystemClock.elapsedRealtime() + PRAZO_MS)
                atualizar(c, atual); escreverResumo()
            }
            enviar(e.id) { CasaHA.acionar(it, e.id, acao) }
        }
        if (e.regulavel || e.clima != null) {   // ideia 51: no ar-condicionado, segurar OK ajusta a temperatura
            c.casca.isLongClickable = true
            c.casca.setOnLongClickListener { entrarNoAjuste(e.id); true }
        }
        c.casca.setOnFocusChangeListener { v, foco -> if (foco) { focoId = e.id; mostrarTitulo(v) }; c.pintar() }
        return c
    }

    private fun atualizar(c: Cartao, e: CasaHA.Entidade) {
        if (c === ajuste) return   // o cartão em ajuste mostra o valor que a pessoa está escolhendo
        val estado = estadoDe(e)
        val liga = CasaHA.ligado(e.dominio, estado)
        val brilho = if (liga && e.regulavel) brilhoDe(e) else null
        c.mostrar(rotuloEstado(e, estado, brilho), liga, brilho,
            indisponivel = estado == "unavailable" || estado == "unknown")
    }

    /** Manda uma ação ao Home Assistant fora da tela; erro aparece no resumo e o cartão volta ao estado real. */
    private fun enviar(id: String, acao: (android.content.Context) -> Unit) {
        val app = applicationContext
        Fundo.rodar {
            val msg = try { acao(app); null } catch (ex: Exception) { ex.message ?: ex.javaClass.simpleName }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (msg != null) { otimista.remove(id); resumo.text = getString(R.string.casa_erro, msg) }
                // Confere logo depois (o Home Assistant leva um instante para refletir a mudança no estado).
                ui.postDelayed({ if (aberta && ajuste == null) carregar() }, 700)
            }
        }
    }

    private fun cartaoGoogle(): View {
        val c = Cartao("casa", getString(R.string.casa_google_home))
        c.mostrar(getString(R.string.casa_google_home_det), false, null, false)
        // Google Home (dono, 10/11/2026): abre o painel do Google TV (luzes, câmeras, timer), o mesmo do HOME segurado.
        c.casca.setOnClickListener { Home.abrirGoogleHome(this) }
        c.casca.setOnFocusChangeListener { v, foco -> if (foco) { focoId = null; mostrarTitulo(v) }; c.pintar() }
        return c.casca
    }

    /**
     * Subindo com o controle, o ScrollView mostra só o cartão focado e o título do cômodo fica escondido em cima: sem ele,
     * não dá para saber de que cômodo é a "Luz 1". Se o cartão está na 1ª linha de um grupo, rola até o título aparecer.
     */
    private fun mostrarTitulo(cartao: View) {
        val linha = cartao.parent as? View ?: return
        val i = grade.indexOfChild(linha)
        if (i <= 0) return
        val titulo = grade.getChildAt(i - 1)?.takeIf { it.tag == SECAO } ?: return
        // Depois da rolagem do próprio ScrollView (que vem no mesmo quadro), senão ela desfaz esta.
        ui.post { if (titulo.top < rolagem.scrollY) rolagem.smoothScrollTo(0, titulo.top) }
    }

    // ---------------------------------------------------------------- ajuste de brilho (segurar OK)

    private fun entrarNoAjuste(id: String) {
        val e = entidades.firstOrNull { it.id == id } ?: return
        val c = cartoes[id] ?: return
        val estado = estadoDe(e)
        if (estado == "unavailable" || estado == "unknown") return
        ajusteClima = e.clima
        if (e.clima != null) {   // ideia 51: ◀ ▶ muda a temperatura pedida
            ajusteTemp = e.clima.alvo ?: e.clima.atual?.let { Math.round(it).toDouble() } ?: 24.0
            ajuste = c; ajusteCores = emptyList(); ajusteCor = -1; ignorarSoltarOk = true
            c.mostrarTexto(getString(R.string.casa_temp_ajuste, formatarGraus(ajusteTemp)))
            return
        }
        ajustePct = if (CasaHA.ligado(e.dominio, estado)) brilhoDe(e) ?: 100 else 0
        ajuste = c
        ajusteCores = e.cores
        ajusteCor = -1
        ignorarSoltarOk = true
        c.mostrarAjuste(ajustePct, ajusteCores.isNotEmpty())
    }

    // Ideia 51: ar-condicionado em ajuste (null = é uma luz) e a temperatura que está na tela.
    private var ajusteClima: CasaHA.Clima? = null
    private var ajusteTemp = 24.0

    private fun formatarGraus(g: Double): String =
        java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(g) + " °"

    private val mandarTemp = Runnable {
        val c = ajuste ?: return@Runnable
        val id = cartoes.entries.firstOrNull { it.value === c }?.key ?: return@Runnable
        val g = ajusteTemp
        enviar(id) { CasaHA.ajustarTemp(it, id, g) }
    }

    // Ideia 50: os tons da luz em ajuste e o escolhido (-1 = nenhum ainda).
    private var ajusteCores: List<CasaHA.CorLuz> = emptyList()
    private var ajusteCor = -1

    /** ▲ ▼ no ajuste de uma luz com cor: troca o tom na tela na hora e manda 350 ms depois da última tecla. */
    private fun passoCor(passo: Int) {
        val c = ajuste ?: return
        if (ajusteCores.isEmpty()) return
        ajusteCor = CasaHA.proximaCor(ajusteCores.size, ajusteCor, passo)
        val tom = ajusteCores[ajusteCor]
        if (ajustePct == 0) ajustePct = 100   // escolher um tom acende a luz
        c.mostrarTom(nomeTom(tom), ajustePct)
        ui.removeCallbacks(mandarCor)
        ui.postDelayed(mandarCor, 350)
    }

    private val mandarCor = Runnable {
        val c = ajuste ?: return@Runnable
        val tom = ajusteCores.getOrNull(ajusteCor) ?: return@Runnable
        val id = cartoes.entries.firstOrNull { it.value === c }?.key ?: return@Runnable
        otimista[id] = Pendente("on", ajustePct, SystemClock.elapsedRealtime() + PRAZO_MS)
        enviar(id) { CasaHA.ajustarCor(it, id, tom) }
    }

    private fun nomeTom(t: CasaHA.CorLuz): String = getString(when (t) {
        CasaHA.CorLuz.QUENTE -> R.string.casa_cor_quente
        CasaHA.CorLuz.NEUTRA -> R.string.casa_cor_neutra
        CasaHA.CorLuz.FRIA -> R.string.casa_cor_fria
        CasaHA.CorLuz.VERMELHO -> R.string.casa_cor_vermelho
        CasaHA.CorLuz.LARANJA -> R.string.casa_cor_laranja
        CasaHA.CorLuz.AMARELO -> R.string.casa_cor_amarelo
        CasaHA.CorLuz.VERDE -> R.string.casa_cor_verde
        CasaHA.CorLuz.AZUL -> R.string.casa_cor_azul
        CasaHA.CorLuz.ROXO -> R.string.casa_cor_roxo
        CasaHA.CorLuz.ROSA -> R.string.casa_cor_rosa
    })

    private fun sairDoAjuste() {
        val c = ajuste ?: return
        ajuste = null
        ui.removeCallbacks(mandarBrilho)
        ui.removeCallbacks(mandarCor)
        ui.removeCallbacks(mandarTemp)
        ajusteClima = null
        c.ajustando = false
        entidades.firstOrNull { cartoes[it.id] === c }?.let { atualizar(c, it) }
        escreverResumo()
    }

    /** ◀ ▶ no ajuste: muda na tela na hora e manda ao Home Assistant 350 ms depois da última tecla. */
    private fun passoBrilho(passo: Int) {
        val c = ajuste ?: return
        ajusteClima?.let { clima ->
            ajusteTemp = CasaHA.proximaTemp(ajusteTemp, passo, clima)
            c.mostrarTexto(getString(R.string.casa_temp_ajuste, formatarGraus(ajusteTemp)))
            ui.removeCallbacks(mandarTemp)
            ui.postDelayed(mandarTemp, 500)
            return
        }
        ajustePct = CasaHA.proximoBrilho(ajustePct, passo)
        c.mostrarAjuste(ajustePct, ajusteCores.isNotEmpty())
        ui.removeCallbacks(mandarBrilho)
        ui.postDelayed(mandarBrilho, 350)
    }

    private val mandarBrilho = Runnable {
        val c = ajuste ?: return@Runnable
        val id = cartoes.entries.firstOrNull { it.value === c }?.key ?: return@Runnable
        val pct = ajustePct
        otimista[id] = Pendente(if (pct > 0) "on" else "off", pct.takeIf { it > 0 }, SystemClock.elapsedRealtime() + PRAZO_MS)
        enviar(id) { CasaHA.ajustarBrilho(it, id, pct) }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ajuste == null) return super.dispatchKeyEvent(ev)
        val ok = ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER
        when {
            ok && ev.action == KeyEvent.ACTION_UP -> { if (ignorarSoltarOk) ignorarSoltarOk = false else sairDoAjuste() }
            ok -> {}   // o OK ainda apertado (repetição do segurar) não faz nada
            ev.keyCode == KeyEvent.KEYCODE_BACK -> if (ev.action == KeyEvent.ACTION_UP) sairDoAjuste()
            ev.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (ev.action == KeyEvent.ACTION_DOWN) passoBrilho(-1)
            ev.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (ev.action == KeyEvent.ACTION_DOWN) passoBrilho(+1)
            ev.keyCode == KeyEvent.KEYCODE_DPAD_UP -> if (ev.action == KeyEvent.ACTION_DOWN) passoCor(-1)   // sem cor: fica no cartão
            ev.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> if (ev.action == KeyEvent.ACTION_DOWN) passoCor(+1)
            else -> return super.dispatchKeyEvent(ev)
        }
        return true
    }

    // ---------------------------------------------------------------- o cartão

    /**
     * Cartão da grade: círculo com o ícone no alto, nome (2 linhas no máximo), o estado e, na luz acesa com brilho,
     * uma barra fina. Repouso = superfície do sistema; foco = branco que cresce 5%; ligado = verde-água.
     */
    private inner class Cartao(tipoIcone: String, nome: String) {
        var ligado = false
        var indisponivel = false
        var ajustando = false
        private val icone = IconeCard(this@CasaActivity, tipoIcone)
        private val circulo = FrameLayout(this@CasaActivity).apply {
            addView(icone, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        private val nomeTv = Estilo.texto(this@CasaActivity, Estilo.Papel.CORPO, nome).apply {
            textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        private val estadoTv = Estilo.texto(this@CasaActivity, Estilo.Papel.LEGENDA, "").apply {
            textSize = 12.5f; typeface = Estilo.fonte(500); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        private val barra = BarraBrilho()
        val casca = LinearLayout(this@CasaActivity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(12), dp(10))
            addView(circulo, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(View(this@CasaActivity), LinearLayout.LayoutParams(0, 0, 1f))   // empurra o texto para baixo
            addView(nomeTv)
            addView(estadoTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            addView(barra, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })
        }

        fun mostrar(estado: String, liga: Boolean, brilho: Int?, indisponivel: Boolean) {
            estadoTv.text = estado
            ligado = liga
            this.indisponivel = indisponivel
            barra.pct = brilho
            pintar()
        }

        fun mostrarAjuste(pct: Int, comCor: Boolean = false) {
            ajustando = true
            ligado = pct > 0
            estadoTv.text = getString(if (comCor) R.string.casa_brilho_ajuste_cor else R.string.casa_brilho_ajuste, pct)
            barra.pct = pct
            pintar()
        }

        /** Ideia 51: texto livre no lugar do estado (temperatura do ar-condicionado em ajuste). */
        fun mostrarTexto(texto: String) {
            ajustando = true
            estadoTv.text = texto
            barra.pct = null
            pintar()
        }

        /** Ideia 50: o tom escolhido com ▲ ▼ no lugar do estado ("Luz quente · ▲ ▼"). */
        fun mostrarTom(nome: String, pct: Int) {
            ajustando = true
            ligado = true
            estadoTv.text = getString(R.string.casa_cor_ajuste, nome)
            barra.pct = pct
            pintar()
        }

        fun pintar() {
            val foco = casca.isFocused
            // Indisponível fica apagado, mas não com foco: o cartão branco a 50% virava cinza e parecia defeito.
            casca.alpha = if (indisponivel && !foco) 0.5f else 1f
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
                ligado || foco -> Estilo.TEXTO_NO_FOCO
                else -> Estilo.TEXTO
            })
            estadoTv.setTextColor(when {
                ligado && foco -> Estilo.ACENTO_NO_FOCO
                ligado -> Estilo.ACENTO
                foco -> Estilo.TEXTO_NO_FOCO_2
                else -> Estilo.TEXTO_3
            })
            barra.foco = foco
            barra.visibility = if (barra.pct != null || ajustando) View.VISIBLE else View.INVISIBLE
            barra.invalidate()
            val escala = if (foco) (if (ajustando) ESCALA_AJUSTE else ESCALA) else 1f
            casca.animate().scaleX(escala).scaleY(escala).setDuration(Estilo.FOCO_MS).start()
        }
    }

    /** Barra fina de brilho: trilho apagado e a parte acesa no acento (no cartão branco, acento escuro). */
    private inner class BarraBrilho : View(this) {
        var pct: Int? = null
        var foco = false
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
            tinta.color = if (foco) 0x220B0F17 else 0x26FFFFFF
            c.drawRoundRect(0f, 0f, w, h, r, r, tinta)
            val p = (pct ?: 0).coerceIn(0, 100)
            if (p > 0) {
                tinta.color = if (foco) Estilo.ACENTO_NO_FOCO else Estilo.ACENTO
                c.drawRoundRect(0f, 0f, maxOf(h, w * p / 100f), h, r, r, tinta)
            }
        }
    }

    // ---------------------------------------------------------------- textos e ícones

    private fun rotuloEstado(e: CasaHA.Entidade, estado: String, brilho: Int?): String {
        val dominio = e.dominio
        if (estado == "unavailable" || estado == "unknown") return getString(R.string.casa_indisponivel)
        return when (dominio) {   // ideia 51
            "sensor" -> e.valor ?: estado.take(20)
            CasaHA.CAMERA -> getString(R.string.casa_camera_ver)
            "binary_sensor" -> getString(when (e.classe) {
                "motion", "occupancy" -> if (estado == "on") R.string.casa_movimento else R.string.casa_sem_movimento
                "moisture" -> if (estado == "on") R.string.casa_molhado else R.string.casa_seco
                "smoke" -> if (estado == "on") R.string.casa_fumaca else R.string.casa_sem_fumaca
                else -> if (estado == "on") R.string.casa_aberta else R.string.casa_fechada
            })
            "climate" -> if (estado == "off") getString(R.string.casa_desligado) else {
                val alvo = e.clima?.alvo?.let { formatarGraus(it) }
                val ambiente = e.clima?.atual?.let { formatarGraus(it) }
                listOfNotNull(alvo, ambiente?.let { getString(R.string.casa_ambiente, it) }).joinToString(" · ").ifEmpty { getString(R.string.casa_ligado) }
            }
            else -> rotuloEstadoAntigo(dominio, estado, brilho)
        }
    }

    private fun rotuloEstadoAntigo(dominio: String, estado: String, brilho: Int?): String = when {
        dominio == "scene" || dominio == "script" -> getString(R.string.casa_rodar)
        dominio == "cover" -> getString(if (estado == "closed" || estado == "closing") R.string.casa_fechada else R.string.casa_aberta)
        estado == "on" && brilho != null -> getString(R.string.casa_ligado_pct, brilho)
        estado == "on" -> getString(R.string.casa_ligado)
        else -> getString(R.string.casa_desligado)
    }

    /** Ícone de dentro do [Estilo.estadoVazio], que pede um Drawable: o mesmo desenho do IconeCard. */
    private inner class IconeCardDrawable(tipo: String) : android.graphics.drawable.Drawable() {
        private val v = IconeCard(this@CasaActivity, tipo)
        override fun draw(c: Canvas) {
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
        private const val ALTURA_DP = 104
        private const val ESPACO_DP = 12
        /** Folga em volta da grade para o cartão focado (até 8% maior no ajuste de brilho) não ser cortado pela rolagem. */
        private const val FOLGA_DP = 12
        private const val ESCALA = 1.05f
        private const val ESCALA_AJUSTE = 1.08f   // no ajuste de brilho o cartão cresce mais: dá para ver que mudou de modo
        /** Releitura com a tela aberta. O Home Assistant é local; 5 s é o mesmo ritmo do vigia (CasaHA). */
        private const val VIVO_MS = 5_000L
        /** Quanto tempo o estado otimista vale sem confirmação (depois disso, vale o que o Home Assistant disser). */
        private const val PRAZO_MS = 6_000L
        /** Áreas (cômodos) mudam pouco: relidas a cada 2 min com a tela aberta, não a cada 5 s. */
        private const val AREAS_MS = 120_000L
        private const val SECAO = "secao"
    }
}

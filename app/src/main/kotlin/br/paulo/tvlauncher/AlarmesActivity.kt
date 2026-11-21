package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.paulo.tvlauncher.alarm.AlarmScreenLogic
import br.paulo.tvlauncher.alarm.AlarmeGarantia
import br.paulo.tvlauncher.alarm.Datas
import br.paulo.tvlauncher.alarm.Despertador
import br.paulo.tvlauncher.alarm.SomAlarme
import br.paulo.tvlauncher.alarm.VolumeAlarme
import br.paulo.tvlauncher.home.CrossColumn
import br.paulo.tvlauncher.home.HomeCruz
import br.paulo.tvlauncher.home.MolaViews
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.TimeWheel
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.LgNetcast
import br.paulo.tvlauncher.network.VolumeLg
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.player.salvar
import br.paulo.tvlauncher.settings.Fontes
import java.util.Calendar

/**
 * A tela do despertador: abre com OK no relógio do topo. Também é o "app de despertador" do Android: atende
 * AlarmClock.ACTION_SET_ALARM (o que o Gemini/Assistente pode mandar ao ouvir "me acorda às 7") e ACTION_SHOW_ALARMS.
 *
 * Redesenho no jeito da cruz (18/11/2026; Paulo: "a tela de configuração de alarme tem que melhorar, UI e UX"):
 *  - LISTA: uma coluna sem caixas ([CrossColumn]); o alarme escolhido grande com o ponto laranja e, embaixo, os dias e
 *    quando toca; à direita, o próximo alarme em letra grande. OK edita; OK segurado liga/desliga sem abrir o editor.
 *  - EDITOR: a hora em dois roletes grandes ([TimeWheel]) à esquerda (▲▼ giram, setas laranja à vista); ◀▶ passam de
 *    hora para minuto e daí para as opções, uma coluna da cruz à direita. Cada opção mostra o valor no laranja; OK abre
 *    a lista de escolha ([Estilo.lista]) em vez de girar às cegas. No alto, a frase do resultado ("Toca amanhã às 07:30").
 *    VOLTAR salva (alarme novo só se a pessoa mexeu em algo): ninguém perde o que acabou de acertar.
 * As teclas são tratadas aqui ([dispatchKeyEvent]); as colunas não usam o foco do Android.
 */
class AlarmesActivity : Activity() {
    private lateinit var conteudo: FrameLayout
    /** A prévia do som (editor). Para ao sair da tela, ao escolher outro e 4 s depois de começar. */
    private var previa: android.media.MediaPlayer? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Esconde [vistas] enquanto uma [Estilo.lista] está aberta: o véu da lista é translúcido e o que fica à direita da
     * tela (as opções do editor, o próximo alarme) aparecia por baixo das linhas, embaralhado (visto na Sala, 18/11/2026).
     * Devolve o "ao fechar". A contagem cobre a lista que reabre logo em seguida (dias avulsos): não pisca no meio.
     */
    private var listasAbertas = 0
    private fun cobrir(vistas: List<View>): () -> Unit {
        listasAbertas++
        vistas.forEach { it.animate().alpha(0f).setDuration(Estilo.LISTA_FOCO_MS).start() }
        return {
            listasAbertas--
            ui.post { if (listasAbertas == 0) vistas.forEach { it.animate().alpha(1f).setDuration(Estilo.LISTA_FOCO_MS).start() } }
        }
    }
    private val fimPrevia = Runnable { pararPrevia() }
    private fun pararPrevia() { ui.removeCallbacks(fimPrevia); previa?.release(); previa = null }

    /** O que está na tela: a lista ou o editor (null = nenhum, ex.: só a confirmação de um pedido). */
    private var lista: Lista? = null
    private var editor: Editor? = null

    /** OK segurado: hora em que desceu e se a ação de segurar já rodou (aí o soltar não faz o clique). */
    private var okDesceuEm = -1L
    private var okUsado = false

    override fun onStop() { super.onStop(); pararPrevia() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        conteudo = FrameLayout(this).apply {
            background = Estilo.fundoTela(context)   // o grafite da HOME
            // As colunas não usam o foco do Android; quem tem o foco é a tela, com o nome do que está escolhido (leitor de tela).
            isFocusable = true; isFocusableInTouchMode = true; defaultFocusHighlightEnabled = false
        }
        setContentView(conteudo)
        // Só trata o pedido na 1ª criação: recriada (ex.: girar/trocar config), a Activity recebe o MESMO intent e
        // criava o alarme de novo (achado 10 da revisão, 04/11/2026).
        if (savedInstanceState != null || !tratarPedido(intent)) mostrarLista()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!tratarPedido(intent)) mostrarLista()
    }

    /**
     * Pedido de outro app (Gemini): "criar alarme às H:M [nos dias X] [com nome Y]". Devolve true se fechou a tela
     * sozinho (quando o pedido diz para não mostrar a interface).
     */
    private fun tratarPedido(i: Intent?): Boolean {
        if (i?.action != AlarmClock.ACTION_SET_ALARM || !i.hasExtra(AlarmClock.EXTRA_HOUR)) return false
        // EXTRA_DAYS vem como Calendar.SUNDAY (1) .. Calendar.SATURDAY (7); no nosso formato, bit 0 = domingo.
        var dias = 0
        i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.forEach { d -> if (d in 1..7) dias = dias or (1 shl (d - 1)) }
        val novo = Despertador.Alarme(
            0, i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23), i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59),
            dias, i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), true)

        // Quem pediu? (achado 20 da revisão, 04/11/2026) A tela atende SET_ALARM de qualquer app, e com EXTRA_SKIP_UI
        // um app qualquer criava alarmes em silêncio. Agora só o Assistente e o sistema criam direto; qualquer outro
        // app passa por uma confirmação na tela. getLaunchedFromPackage (Android 14) vem do sistema — o app que chama
        // não consegue falsificar (ao contrário do "referrer").
        val quem = if (android.os.Build.VERSION.SDK_INT >= 34) launchedFromPackage else null
        android.util.Log.i("TvLauncher.Alarme", "SET_ALARM de $quem: ${Despertador.horaTexto(novo)}")
        if (quem == null || quem !in CONFIAVEIS) {
            confirmarPedido(novo, quem)
            return true
        }
        val a = Despertador.salvar(this, novo)
        Toast.makeText(this, getString(R.string.al_criado, Despertador.horaTexto(a)), Toast.LENGTH_LONG).show()
        if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) { finish(); return true }
        mostrarLista(focarId = a.id)
        return true
    }

    /** Pedido de app desconhecido: só cria se a pessoa apertar OK em "Criar". Voltar/Cancelar = não cria nada. */
    private fun confirmarPedido(novo: Despertador.Alarme, quem: String?) {
        val nomeApp = quem?.let { p -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p } }
            ?: getString(R.string.al_um_app)
        CaixaTv(this)
            .setTitle(getString(R.string.al_criar_q, Despertador.horaTexto(novo)))
            .setMessage(getString(R.string.al_pedido, nomeApp))
            .setPositiveButton(getString(R.string.al_criar)) { _, _ ->
                val a = Despertador.salvar(this, novo)
                Toast.makeText(this, getString(R.string.al_criado, Despertador.horaTexto(a)), Toast.LENGTH_LONG).show()
                mostrarLista(focarId = a.id)
            }
            .setNegativeButton(getString(R.string.comum_cancelar)) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        /** Quem cria alarme direto, sem confirmação: o Assistente da TV ("Ok Google, me acorda às 7") e o sistema. */
        private val CONFIAVEIS = setOf("com.google.android.katniss", "com.google.android.googlequicksearchbox", "android")
        /** OK segurado por este tempo = a ação de segurar (o mesmo da cruz). */
        private const val SEGURAR_MS = 500L
        /** A prévia do som toca este tanto. */
        private const val PREVIA_MS = 4000L
    }

    // ---------------------------------------------------------------- teclas

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (lista == null && editor == null) return super.dispatchKeyEvent(e)
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { okDesceuEm = e.eventTime; okUsado = false }
                    else if (okDesceuEm >= 0 && !okUsado && e.eventTime - okDesceuEm >= SEGURAR_MS) { okUsado = true; segurarOk() }
                KeyEvent.ACTION_UP -> { if (okDesceuEm >= 0 && !okUsado) apertarOk(); okDesceuEm = -1 }
            }
            return true
        }
        val seta = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!seta) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN) {
            // Segurando a tecla, o controle repete ~20 vezes por segundo: o rolete girava rápido demais para parar na
            // hora certa (Paulo, 18/11/2026). Repetições só passam no ritmo de AlarmScreenLogic.RITMO_SEGURANDO_MS.
            if (!AlarmScreenLogic.passaRepeticao(e.repeatCount, ultimaSetaEm, e.eventTime)) return true
            ultimaSetaEm = e.eventTime
            lista?.seta(k)
            editor?.seta(k, e.repeatCount > 0)
            nomear()
        }
        return true
    }

    private var ultimaSetaEm = 0L

    // Um só dono por vez: o OK da lista abre o editor, e o mesmo OK não pode chegar ao editor recém-aberto.
    private fun apertarOk() { val l = lista; if (l != null) l.ok() else editor?.ok(); nomear() }
    private fun segurarOk() { lista?.segurar(); nomear() }

    /** O nome da tela para o leitor de tela = o que está escolhido agora (e a tela fica com o foco). */
    private fun nomear() {
        conteudo.contentDescription = lista?.coluna?.descricao() ?: editor?.descricao()
        if (!conteudo.hasFocus()) conteudo.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val ed = editor
        if (ed != null) { ed.voltar(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun som(s: SonsCruz.Som) = SonsCruz.tocar(this, s)

    // ---------------------------------------------------------------- peças comuns

    private fun texto(sp: Float, cor: Int, peso: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor); typeface = Fontes.nunito(peso); includeFontPadding = false
    }

    /**
     * Título da tela no padrão de todas as outras (branco, caixa normal, tamanho de título). Antes era "DESPERTADOR 1 de 2"
     * em laranja, no mesmo estilo do rótulo "PRÓXIMO ALARME": dois rótulos e nenhum título (crítica de design 19/11/2026).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun cabecalho(nome: String, pos: Int, total: Int): CharSequence = nome

    private fun novoCabecalho() = texto(Estilo.Papel.TITULO.sp, Estilo.TEXTO, 700)

    /** "Repetir   Seg a sex": o nome da opção em branco e o valor no laranja (o acento = "o que está escolhido"). */
    private fun opcao(nome: String, valor: String): CharSequence {
        val sb = SpannableStringBuilder(nome)
        if (valor.isEmpty()) return sb
        sb.append("   ")
        val ini = sb.length
        sb.append(valor)
        // Valor em creme: o laranja fica só para marca, foco e progresso (crítica de design 19/11/2026).
        sb.setSpan(ForegroundColorSpan(Estilo.TEXTO_2), ini, sb.length, 0)
        return sb
    }

    /** "hoje às 07:00", "amanhã às 07:00" ou "seg., 3 às 07:00". */
    private fun quando(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        // Conta DATAS (Datas.diasAte), não milissegundos: a conta antiga esquecia os segundos de agora e "amanhã" saía "hoje".
        val dia = when (Datas.diasAte(ms, System.currentTimeMillis())) {
            0L -> getString(R.string.al_hoje)
            1L -> getString(R.string.al_amanha)
            else -> java.text.SimpleDateFormat("EEE, d", java.util.Locale.getDefault()).format(c.time)
        }
        return getString(R.string.al_dia_as, dia, "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun lp(larg: Int, alt: Int, x: Int, y: Int) = FrameLayout.LayoutParams(larg, alt, Gravity.TOP or Gravity.START).apply {
        marginStart = dp(x); topMargin = dp(y)
    }

    // ---------------------------------------------------------------- lista

    private fun mostrarLista(focarId: Int? = null) {
        pararPrevia()
        editor = null
        conteudo.removeAllViews()
        lista = Lista(focarId)
        nomear()
    }

    /** A lista dos alarmes, "Novo alarme" e o volume do toque, numa coluna da cruz; à direita, o próximo alarme. */
    private inner class Lista(focarId: Int?) {
        val alarmes = Despertador.listar(this@AlarmesActivity).sortedWith(compareBy({ it.hora }, { it.minuto }))
        val cab = novoCabecalho()
        val coluna = CrossColumn(this@AlarmesActivity)
        val proxRotulo = texto(18f, Estilo.TEXTO_3, 800).apply { letterSpacing = 0.14f }   // rótulo de seção: cinza, como nas outras telas
        val proxHora = texto(72f, Estilo.TEXTO, 700)
        val proxDia = texto(22f, HomeCruz.COR_SUB, 600).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        val lg = VolumeLg.configurado(this@AlarmesActivity) && VolumeLg.alarmeLigado(this@AlarmesActivity)
        val iNovo = alarmes.size
        val prox = LinearLayout(this@AlarmesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(proxRotulo)
            addView(proxHora, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(proxDia, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        val iVolume = alarmes.size + 1

        init {
            conteudo.addView(cab, lp(-2, -2, 48, 36))
            conteudo.addView(coluna, lp(dp(520), dp(384), 48, 92))
            coluna.topo = dp(72).toFloat()
            // O próximo alarme, em pilha (rótulo, hora grande, dia): um embaixo do outro, nunca por cima.
            conteudo.addView(prox, lp(dp(300), -2, 620, 184))
            conteudo.addView(CrossColumn.dicas(this@AlarmesActivity,
                getString(R.string.grv_dica_ok) to getString(R.string.al_dica_editar),
                getString(R.string.grv_dica_segurar) to getString(R.string.al_dica_ligar),
                getString(R.string.rede_tecla_voltar) to getString(R.string.al_dica_sair)), lp(-2, -2, 48, 490))
            val i = alarmes.indexOfFirst { it.id == focarId }.takeIf { it >= 0 } ?: if (alarmes.isEmpty()) iNovo else 0
            preencher(i)
            if (!MolaViews.semAnimacao(conteudo)) Estilo.entrar(conteudo)
        }

        fun itemAlarme(a: Despertador.Alarme): CrossColumn.Item {
            val linhas = ArrayList<String>()
            val dias = Despertador.diasTexto(this@AlarmesActivity, a.dias)
            val prox = Despertador.proximaVez(a)
            linhas += if (prox == null) "$dias · ${getString(R.string.al_desligado)}" else "$dias · ${getString(R.string.al_info_toca, quando(prox))}"
            if (a.nome.isNotBlank()) linhas += a.nome
            return CrossColumn.Item(Despertador.horaTexto(a), linhas.joinToString("\n"), apagado = !a.ativo, fonte = 44f)
        }

        fun rotuloVolume(pct: Int) = if (pct <= 0 || (lg && pct == LgNetcast.NAO_MEXER)) getString(R.string.al_volume_nao_mexe)
            else if (lg) "$pct" else "$pct%"
        fun volumeAtual() = if (lg) AlarmeGarantia.volumeLg(this@AlarmesActivity) else AlarmeGarantia.volumeMinimo(this@AlarmesActivity)

        fun preencher(escolher: Int) {
            val itens = alarmes.map { itemAlarme(it) } + listOf(
                CrossColumn.Item(getString(R.string.al_novo_mais), getString(R.string.al_novo_det)),
                CrossColumn.Item(opcao(getString(R.string.al_volume_rotulo), rotuloVolume(volumeAtual())),
                    getString(if (lg) R.string.al_volume_lg_det else R.string.al_volume_det)))
            coluna.definir(itens, escolher)
            cabecalhoEProximo()
        }

        fun cabecalhoEProximo() {
            cab.text = cabecalho(getString(R.string.al_titulo), coluna.escolhido, coluna.tamanho)
            val prox = Despertador.proximo(this@AlarmesActivity)
            proxRotulo.text = getString(R.string.al_proximo_rotulo).uppercase()
            if (prox == null) {
                proxHora.text = "--:--"; proxHora.alpha = 0.35f
                proxDia.text = getString(R.string.al_nenhum)
            } else {
                val c = Calendar.getInstance().apply { timeInMillis = prox.second }
                proxHora.text = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)); proxHora.alpha = 1f
                val dia = when (Datas.diasAte(prox.second, System.currentTimeMillis())) {
                    0L -> getString(R.string.al_hoje)
                    1L -> getString(R.string.al_amanha)
                    else -> java.text.SimpleDateFormat("EEEE, d", java.util.Locale.getDefault()).format(c.time)
                }.replaceFirstChar { it.uppercase() }
                proxDia.text = if (prox.first.nome.isBlank()) dia else dia + "\n" + prox.first.nome
            }
        }

        fun seta(k: Int) {
            val d = when (k) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
            if (d == 0) { som(SonsCruz.Som.BORDA); return }
            if (coluna.mover(d)) { som(SonsCruz.Som.ANDAR); cab.text = cabecalho(getString(R.string.al_titulo), coluna.escolhido, coluna.tamanho) }
            else som(SonsCruz.Som.BORDA)
        }

        fun ok() {
            val i = coluna.escolhido
            som(SonsCruz.Som.ABRIR)
            when {
                i < alarmes.size -> mostrarEditor(alarmes[i])
                i == iNovo -> mostrarEditor(null)
                i == iVolume -> escolherVolume()
            }
        }

        /** OK segurado num alarme: liga ou desliga ali mesmo (a coisa mais feita nesta tela, sem abrir o editor). */
        fun segurar() {
            val a = alarmes.getOrNull(coluna.escolhido) ?: run { ok(); return }
            val novo = Despertador.salvar(this@AlarmesActivity, a.copy(ativo = !a.ativo))
            som(SonsCruz.Som.ABRIR)
            Toast.makeText(this@AlarmesActivity, getString(if (novo.ativo) R.string.al_ligou else R.string.al_desligou, Despertador.horaTexto(novo)), Toast.LENGTH_SHORT).show()
            mostrarLista(focarId = novo.id)
        }

        /**
         * Volume mínimo ao tocar (alarme à prova de falha, ver AlarmeGarantia): vale para todos os alarmes. Com LG pareada,
         * o volume que conta é o da LG (o do Android é fixo no HDMI): a escolha é o volume FINAL da LG; o toque começa
         * baixo e sobe até ele na rampa de cada alarme. "Não mexer" deixa o volume e o mudo da TV como estiverem.
         */
        fun escolherVolume() {
            val opcoes = if (lg) LgNetcast.OPCOES_ALARME else VolumeAlarme.OPCOES
            val atual = opcoes.indexOf(volumeAtual())
            Estilo.lista(this@AlarmesActivity, getString(R.string.al_volume_rotulo), opcoes.map { rotuloVolume(it) },
                marcadas = if (atual >= 0) listOf(atual) else emptyList(), aoFechar = cobrir(listOf(prox))) { escolha ->
                if (lg) AlarmeGarantia.definirVolumeLg(this@AlarmesActivity, opcoes[escolha])
                else AlarmeGarantia.definirVolumeMinimo(this@AlarmesActivity, opcoes[escolha])
                preencher(coluna.escolhido)
            }
        }
    }

    // ---------------------------------------------------------------- editor

    private fun mostrarEditor(a: Despertador.Alarme?) {
        pararPrevia()
        lista = null
        conteudo.removeAllViews()
        editor = Editor(a)
        nomear()
    }

    /** As opções da coluna da direita do editor, na ordem em que aparecem. */
    private enum class Op { SALVAR, REPETIR, SOM, RAMPA, BOM_DIA, CENA, NOME, LIGADO, APAGAR, SAIR }

    /** Editor de um alarme ([orig] null = novo). Zona 0 = rolete da hora, 1 = do minuto, 2 = coluna de opções. */
    private inner class Editor(val orig: Despertador.Alarme?) {
        val agora: Calendar = Calendar.getInstance()
        var v = AlarmScreenLogic.Valores(
            orig?.hora ?: agora.get(Calendar.HOUR_OF_DAY), orig?.minuto ?: 0, orig?.dias ?: 0b0111110, orig?.nome.orEmpty(),
            orig?.ativo ?: true, orig?.som ?: 0, orig?.rampa ?: 0, orig?.bomDia ?: true, orig?.cena ?: "")
        val inicio = v
        val cenas = Cenas.lista(this@AlarmesActivity).map { it.nome }
        val ops = Op.entries.filter {
            when (it) {
                Op.CENA -> cenas.isNotEmpty() || v.cena.isNotEmpty()   // ideia 5: só com alguma cena criada
                Op.LIGADO, Op.APAGAR -> orig != null
                else -> true
            }
        }
        var zona = 0
        val cab = novoCabecalho()
        val resumo = texto(24f, Estilo.TEXTO, 600)
        val hora = TimeWheel(this@AlarmesActivity, 24)
        val minuto = TimeWheel(this@AlarmesActivity, 60)
        val coluna = CrossColumn(this@AlarmesActivity)

        init {
            cab.text = getString(if (orig == null) R.string.al_novo else R.string.al_editar)
            conteudo.addView(cab, lp(-2, -2, 48, 36))
            conteudo.addView(resumo, lp(dp(820), -2, 48, 84))
            conteudo.addView(hora, lp(dp(150), dp(370), 56, 116))
            // ":" entre os roletes, na altura do número do meio
            conteudo.addView(texto(TimeWheel.FONTE, Estilo.TEXTO, 600).apply { text = ":"; gravity = Gravity.CENTER; translationY = -dp(6).toFloat() },
                lp(dp(40), dp(370), 206, 116))
            conteudo.addView(minuto, lp(dp(150), dp(370), 246, 116))
            conteudo.addView(coluna, lp(dp(450), dp(356), 462, 122))
            coluna.topo = dp(110).toFloat()
            conteudo.addView(CrossColumn.dicas(this@AlarmesActivity,
                "▲▼" to getString(R.string.al_dica_mudar), "◀▶" to getString(R.string.al_dica_andar),
                getString(R.string.grv_dica_ok) to getString(R.string.lista_dica_escolher),
                getString(R.string.rede_tecla_voltar) to getString(R.string.al_dica_salvar)), lp(-2, -2, 48, 490))
            hora.definir(v.hora); minuto.definir(v.minuto)
            preencher(0)
            zona(0)
            if (!MolaViews.semAnimacao(conteudo)) Estilo.entrar(conteudo)
        }

        fun descricao(): String = when (zona) {
            0 -> getString(R.string.al_op_hora) + " " + hora.contentDescription
            1 -> getString(R.string.al_op_minuto) + " " + minuto.contentDescription
            else -> coluna.descricao()
        } + ". " + resumo.text

        fun zona(z: Int) {
            zona = z
            hora.ativo = z == 0; minuto.ativo = z == 1; coluna.ativa = z == 2
        }

        fun nomeDia(i: Int): String = java.text.DateFormatSymbols.getInstance().weekdays[i + 1].replaceFirstChar { it.uppercase() }

        fun item(op: Op): CrossColumn.Item {
            val sim = getString(R.string.al_sim); val nao = getString(R.string.al_nao)
            return when (op) {
                Op.SALVAR -> CrossColumn.Item(getString(R.string.rot_salvar), getString(R.string.al_op_salvar_det))
                Op.REPETIR -> CrossColumn.Item(opcao(getString(R.string.al_op_repetir), Despertador.diasTexto(this@AlarmesActivity, v.dias)), getString(R.string.al_op_repetir_det))
                Op.SOM -> CrossColumn.Item(opcao(getString(R.string.al_op_som), SomAlarme.NOMES.getOrElse(v.som) { SomAlarme.NOMES[0] }), getString(R.string.al_op_som_det))
                Op.RAMPA -> CrossColumn.Item(opcao(getString(R.string.al_op_rampa), SomAlarme.rampaTexto(v.rampa)), getString(R.string.al_op_rampa_det))
                Op.BOM_DIA -> CrossColumn.Item(opcao(getString(R.string.al_op_bom_dia), if (v.bomDia) sim else nao), getString(R.string.al_op_bom_dia_det))
                Op.CENA -> CrossColumn.Item(opcao(getString(R.string.al_op_cena), v.cena.ifBlank { getString(R.string.rot_nenhum) }), getString(R.string.al_op_cena_det))
                Op.NOME -> CrossColumn.Item(opcao(getString(R.string.al_op_nome), v.nome.ifBlank { getString(R.string.rot_nenhum) }), getString(R.string.al_op_nome_det))
                Op.LIGADO -> CrossColumn.Item(opcao(getString(R.string.al_op_alarme), getString(if (v.ativo) R.string.al_ligado else R.string.al_desligado)), getString(R.string.al_op_ligado_det))
                Op.APAGAR -> CrossColumn.Item(getString(R.string.al_op_apagar), getString(R.string.al_op_apagar_det))
                Op.SAIR -> CrossColumn.Item(getString(R.string.al_op_sair), getString(R.string.al_op_sair_det))
            }
        }

        fun preencher(escolher: Int = coluna.escolhido) {
            coluna.definir(ops.map { item(it) }, escolher)
            atualizarResumo()
        }

        fun atualizarResumo() {
            val a = alarme()
            val prox = Despertador.proximaVez(a)
            resumo.text = if (prox == null) getString(R.string.al_resumo_desligado)
                else getString(R.string.al_resumo, quando(prox)) + "  ·  " + Despertador.diasTexto(this@AlarmesActivity, v.dias)
        }

        fun alarme() = Despertador.Alarme(orig?.id ?: 0, v.hora, v.minuto, v.dias, v.nome.trim(), v.ativo, v.som, v.rampa, v.bomDia, v.cena)

        fun seta(k: Int, segurando: Boolean) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (zona > 0) { zona(zona - 1); som(SonsCruz.Som.ANDAR) } else som(SonsCruz.Som.BORDA)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (zona < 2) { zona(zona + 1); som(SonsCruz.Som.ANDAR) } else som(SonsCruz.Som.BORDA)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val sobe = k == KeyEvent.KEYCODE_DPAD_UP
                    when (zona) {
                        0 -> { v = v.copy(hora = AlarmScreenLogic.girarHora(v.hora, if (sobe) 1 else -1)); hora.definir(v.hora, if (sobe) 1 else -1) }
                        1 -> { v = v.copy(minuto = AlarmScreenLogic.girarMinuto(v.minuto, sobe, segurando)); minuto.definir(v.minuto, if (sobe) 1 else -1) }
                        else -> { if (coluna.mover(if (sobe) -1 else 1)) som(SonsCruz.Som.ANDAR) else som(SonsCruz.Som.BORDA); return }
                    }
                    if (!segurando) som(SonsCruz.Som.ANDAR)
                    atualizarResumo()
                }
            }
        }

        fun ok() {
            if (zona < 2) { zona(zona + 1); som(SonsCruz.Som.ANDAR); return }   // OK na hora = "pronto, próximo"
            som(SonsCruz.Som.ABRIR)
            when (ops[coluna.escolhido]) {
                Op.SALVAR -> salvar()
                Op.REPETIR -> escolherDias(null)
                Op.SOM -> escolher(getString(R.string.al_op_som), SomAlarme.NOMES, v.som) { v = v.copy(som = it); ouvir(it) }
                Op.RAMPA -> escolher(getString(R.string.al_op_rampa), SomAlarme.RAMPAS.map { SomAlarme.rampaTexto(it) },
                    SomAlarme.RAMPAS.indexOf(SomAlarme.rampaReal(v.rampa))) { v = v.copy(rampa = SomAlarme.RAMPAS[it]) }
                Op.BOM_DIA -> { v = v.copy(bomDia = !v.bomDia); preencher() }
                Op.CENA -> {
                    val nomes = listOf(getString(R.string.rot_nenhum)) + cenas
                    escolher(getString(R.string.al_op_cena), nomes, if (v.cena.isBlank()) 0 else cenas.indexOf(v.cena) + 1) {
                        v = v.copy(cena = if (it == 0) "" else cenas[it - 1])
                    }
                }
                Op.NOME -> escreverNome()
                Op.LIGADO -> { v = v.copy(ativo = !v.ativo); preencher() }
                Op.APAGAR -> confirmarApagar()
                Op.SAIR -> mostrarLista(focarId = orig?.id)
            }
        }

        fun escolher(titulo: String, nomes: List<String>, atual: Int, aoEscolher: (Int) -> Unit) {
            Estilo.lista(this@AlarmesActivity, titulo, nomes, marcadas = if (atual >= 0) listOf(atual) else emptyList(),
                aoFechar = cobrir(listOf(coluna))) {
                aoEscolher(it); preencher()
            }
        }

        /** "Repetir": os 4 prontos e, embaixo, os 7 dias por extenso (✓ = marcado). Um dia avulso reabre a lista nele. */
        fun escolherDias(focar: Int?) {
            val nomes = listOf(getString(R.string.dia_uma_vez), getString(R.string.dia_todo), getString(R.string.dia_seg_sex),
                getString(R.string.dia_fim)) + (0..6).map { nomeDia(it) }
            Estilo.lista(this@AlarmesActivity, getString(R.string.al_op_repetir), nomes, marcadas = AlarmScreenLogic.marcadasRepetir(v.dias),
                focoInicial = focar, entrar = focar == null, aoFechar = cobrir(listOf(coluna))) { linha ->
                val (dias, reabrir) = AlarmScreenLogic.escolherRepetir(v.dias, linha)
                v = v.copy(dias = dias)
                preencher()
                if (reabrir) escolherDias(linha)
            }
        }

        fun ouvir(som: Int) {
            pararPrevia()
            previa = SomAlarme.criar(this@AlarmesActivity, som)?.also { it.start() }
            // Um só "parar daqui a 4 s" por vez: o de uma prévia anterior cortaria a nova no meio.
            ui.postDelayed(fimPrevia, PREVIA_MS)
        }

        fun escreverNome() {
            val campo = EditText(this@AlarmesActivity).apply {
                setText(v.nome); setSelection(text.length)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
                hint = getString(R.string.al_op_nome_dica)
            }
            val d = CaixaTv(this@AlarmesActivity)
                .setTitle(getString(R.string.al_op_nome))
                .setView(campo)
                .setPositiveButton(getString(R.string.rot_salvar)) { _, _ -> v = v.copy(nome = campo.text.toString().trim()); preencher() }
                .setNegativeButton(getString(R.string.comum_cancelar), null)
                .show()
            CampoTv.enterConfirma(campo, d)
            campo.requestFocus()
        }

        fun confirmarApagar() {
            val a = orig ?: return
            CaixaTv(this@AlarmesActivity)
                .setTitle(getString(R.string.al_apagar_q, Despertador.horaTexto(alarme())))
                .setPositiveButton(getString(R.string.al_apagar)) { _, _ -> Despertador.apagar(this@AlarmesActivity, a.id); mostrarLista() }
                .setNegativeButton(getString(R.string.comum_cancelar), null)
                .show()
        }

        fun salvar() {
            val a = Despertador.salvar(this@AlarmesActivity, alarme())
            Toast.makeText(this@AlarmesActivity, getString(R.string.al_salvo, Despertador.horaTexto(a)), Toast.LENGTH_SHORT).show()
            mostrarLista(focarId = a.id)
        }

        /** VOLTAR salva: alarme existente sempre que mudou; novo só se a pessoa mexeu (abrir "Novo" e sair não cria nada). */
        fun voltar() {
            if (AlarmScreenLogic.mudou(inicio, v)) salvar() else mostrarLista(focarId = orig?.id)
        }
    }
}

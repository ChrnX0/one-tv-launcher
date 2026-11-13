package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela do "diagnóstico de 1 botão" (aberta pela 1ª linha das Configurações do launcher). Ao abrir, roda TODAS as
 * verificações de uma vez (Wi-Fi, internet, DNS, VPN e o histórico dos apps) e vai preenchendo a tela conforme cada
 * uma termina. OK num app mostra o que aconteceu com ele (texto completo do crash ou o motivo do encerramento).
 * Toda a lógica fica em Diagnostico.kt; aqui é só tela. Estilo copiado do ConfigLauncherActivity de propósito.
 */
class DiagnosticoActivity : Activity() {
    // 4 threads: as verificações de rede esperam a rede (até ~9 s o DNS), não a CPU; rodar juntas encurta a espera.
    private val trabalho = Executors.newFixedThreadPool(4)
    /** Só para o "em uma frase" (não espera na fila do [trabalho]); encerrado no onDestroy. */
    private val frasePool = Executors.newSingleThreadExecutor()
    private var fraseEmCurso: java.util.concurrent.Future<*>? = null
    private val ui = Handler(Looper.getMainLooper())

    // Cores e fontes do sistema de design (Estilo): as mesmas da HOME e das Configurações.
    private val textoClaro = Estilo.TEXTO
    private val textoSuave = Estilo.TEXTO_2
    private val cores = intArrayOf(Estilo.ACENTO, Estilo.ATENCAO, Estilo.ERRO)   // ok, atenção, falha
    private val fonteMedia = Fontes.manrope(600)

    private lateinit var principal: View
    private lateinit var lista: LinearLayout
    private var noDetalhe = false
    private var focoAntes: View? = null   // a linha do app aberto: volta o foco para ela no BACK
    // Número da rodada atual. "Verificar de novo" no meio da carga começa outra rodada; os resultados que chegarem da
    // rodada velha são jogados fora (senão apps e erros apareciam duas vezes na lista nova).
    private var geracao = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        principal = montarTela()
        setContentView(principal)
        verificar()
    }

    override fun onDestroy() {
        super.onDestroy()
        trabalho.shutdownNow()
        frasePool.shutdownNow()
    }

    /** BACK no detalhe de um app volta para a lista, não fecha a tela. */
    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() {
        if (noDetalhe) { noDetalhe = false; setContentView(principal); focoAntes?.requestFocus(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- tela principal

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.diag_titulo)))
        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        raiz.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(lista)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        return raiz
    }

    /** Limpa a lista e roda tudo de novo (é o que o "Verificar de novo" faz). */
    private fun verificar() {
        val g = ++geracao
        lista.removeAllViews()
        val refazer = linha(getString(R.string.diag_refazer), getString(R.string.diag_refazer_det), -1) { verificar() }
        adicionar(refazer)
        refazer.requestFocus()

        secao(getString(R.string.cfg_sec_rede))
        val resumo = info(getString(R.string.diag_resumo), getString(R.string.diag_verificando)).also { adicionar(it) }
        // "A internet caiu? Em uma frase" (InternetEmUmaFrase): junta histórico, roteador, DNS e teste HTTP. Thread
        // própria para não esperar na fila das 4 verificações acima.
        val frase = info(getString(R.string.diag_frase), getString(R.string.diag_verificando)).also { adicionar(it) }
        fraseEmCurso?.cancel(true)   // "Verificar de novo" não empilha medições: a anterior é largada
        fraseEmCurso = frasePool.submit {
            val r = try { DiagnosticoInternet.explicar(applicationContext) } catch (e: Exception) { null }
            ui.post {
                if (isDestroyed || g != geracao) return@post
                frase.mostrar(if (r == null) Diagnostico.Item(getString(R.string.diag_frase), "erro", 2)
                    else Diagnostico.Item(getString(R.string.diag_frase), r.frase + "\n" + r.sinais.joinToString(" · "), r.nivel))
            }
        }
        val wifi = info("Wi-Fi", getString(R.string.diag_verificando)).also { adicionar(it) }
        val internet = info("Internet", getString(R.string.diag_verificando)).also { adicionar(it) }
        val dns = info("DNS", getString(R.string.diag_verificando)).also { adicionar(it) }
        val vpn = info("VPN", getString(R.string.diag_verificando)).also { adicionar(it) }

        // Cada verificação numa thread; quando as quatro chegam, calcula o resumo.
        val resultados = arrayOfNulls<Diagnostico.Item>(4)
        fun rodar(i: Int, linha: TextView, f: () -> Diagnostico.Item) = trabalho.execute {
            val r = try { f() } catch (e: Exception) { Diagnostico.Item(linha.text.toString().substringBefore('\n'), "erro: ${e.javaClass.simpleName}", 2) }
            ui.post {
                if (isDestroyed || g != geracao) return@post
                linha.mostrar(r)
                resultados[i] = r
                if (resultados.all { it != null })
                    resumo.mostrar(Diagnostico.veredito(resultados[0]!!, resultados[1]!!, resultados[2]!!, resultados[3]!!))
            }
        }
        rodar(0, wifi) { Diagnostico.wifi(this) }
        rodar(1, internet) { Diagnostico.internet(this) }
        rodar(2, dns) { Diagnostico.dns(this) }
        rodar(3, vpn) { Diagnostico.vpn(this) }

        secao(getString(R.string.diag_apps))
        val carregando = info(getString(R.string.diag_lendo), "…").also { adicionar(it) }
        trabalho.execute {
            val apps = Diagnostico.appsDaTv(this)
            val ocorr = Diagnostico.ocorrencias(this, apps.map { it.first })
            val podeLer = Diagnostico.podeLerCrashes(this)
            ui.post {
                if (isDestroyed || g != geracao) return@post
                lista.removeView(carregando)
                if (!podeLer) adicionar(info(getString(R.string.diag_sem_perm),
                    getString(R.string.diag_sem_perm_det), 1))
                // Primeiro quem teve problema (mais recente no topo), depois o resto em ordem alfabética.
                val nomes = apps.toMap()
                val comProblema = ocorr.entries.sortedByDescending { it.value.first().quando }
                for ((pacote, l) in comProblema) {
                    val ult = l.first()
                    val nivel = if (l.any { it.grave }) 2 else 1
                    val quando = DateUtils.getRelativeTimeSpanString(ult.quando, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    // Apps fora do menu da TV (ex.: Google Play Services) não estão em [nomes]: pede o nome ao sistema.
                    val nome = nomes[pacote] ?: Diagnostico.rotuloDe(this, pacote)
                    val resumo = if (ult.resumo.isBlank()) "" else " — ${ult.resumo}"
                    adicionar(linha(nome, "$quando: ${ult.tipo}$resumo" + (if (l.size > 1) " (+${l.size - 1})" else ""), nivel) {
                        mostrarDetalhe(nome, pacote, l)
                    })
                }
                for ((pacote, nome) in apps) if (pacote !in ocorr)
                    adicionar(linha(nome, getString(R.string.diag_nada), 0) { mostrarDetalhe(nome, pacote, emptyList()) })
                mostrarErrosDoLauncher()
            }
        }
    }

    /**
     * Seção do registro local de erros (RegistroErros.kt): os crashes do PRÓPRIO launcher, gravados por ele mesmo.
     * Fica por último, depois dos apps, porque a lista dos apps chega depois (lida numa thread).
     */
    private fun mostrarErrosDoLauncher() {
        secao(getString(R.string.diag_erros))
        val erros = RegistroErros.listar(this)
        if (erros.isEmpty()) { adicionar(info(getString(R.string.diag_sem_erros), getString(R.string.diag_sem_erros_det, RegistroErros.MAXIMO), 0)); return }
        for (e in erros) {
            val quando = DateUtils.getRelativeTimeSpanString(e.quando, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            adicionar(linha(e.resumo, quando.toString(), 2) { mostrarTexto(getString(R.string.diag_erro), e.arquivo.name, e.texto) })
        }
        adicionar(linha(getString(R.string.diag_apagar), getString(R.string.diag_apagar_det), -1) {
            RegistroErros.apagarTodos(this)
            verificar()
        })
    }

    /** Mostra um texto longo (a pilha de um erro) na mesma tela de detalhe dos apps. */
    private fun mostrarTexto(titulo: String, subtitulo: String, conteudo: String) {
        focoAntes = currentFocus
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, titulo))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA).apply { text = getString(R.string.diag_rodape, subtitulo) })
        val corpo = texto(15f, textoClaro, Typeface.MONOSPACE).apply { text = conteudo; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val rolagem = ScrollView(this).apply { isFocusable = true; addView(corpo) }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        noDetalhe = true
        setContentView(raiz)
        rolagem.requestFocus()
    }

    // ---------------------------------------------------------------- detalhe de um app

    /** Troca a tela pelo histórico completo de um app. A rolagem é pelas setas do controle (a ScrollView tem o foco). */
    private fun mostrarDetalhe(nome: String, pacote: String, l: List<Diagnostico.Ocorrencia>) {
        focoAntes = currentFocus
        val sb = StringBuilder()
        if (l.isEmpty()) sb.append(getString(R.string.diag_app_ok))
        for (o in l) {
            sb.append("■ ").append(DateUtils.formatDateTime(this, o.quando,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY))
                .append(" — ").append(o.tipo).append("\n")
            // Do texto do DropBox, as primeiras 25 linhas bastam: cabeçalho + o erro + o começo da pilha.
            sb.append(o.texto.lines().take(25).joinToString("\n")).append("\n\n")
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, nome))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA).apply { text = getString(R.string.diag_rodape, pacote) })
        val corpo = texto(15f, textoClaro, Typeface.MONOSPACE).apply { text = sb; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val rolagem = ScrollView(this).apply {
            isFocusable = true
            isVerticalScrollBarEnabled = true
            addView(corpo)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        noDetalhe = true
        setContentView(raiz)
        rolagem.requestFocus()
    }

    // ---------------------------------------------------------------- peças

    private fun adicionar(v: View) = lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

    private fun secao(titulo: String) = lista.addView(Estilo.secao(this, titulo))

    private fun linha(titulo: String, detalhe: String, nivel: Int, aoClicar: () -> Unit): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe, nivel)
        isFocusable = true
        isClickable = true
        background = Estilo.superficie(context, false)
        setOnClickListener { aoClicar() }
        // Foco igual ao das outras telas: pílula branca e texto escuro. As cores do texto vêm de spans, então a linha
        // é remontada com as cores "no foco" (guardadas em [Conteudo]) em vez de só trocar a cor do TextView.
        setOnFocusChangeListener { v, temFoco ->
            v.background = Estilo.superficie(context, temFoco)
            (v.tag as? Conteudo)?.let { (v as TextView).montarLinha(it.titulo, it.detalhe, it.nivel, temFoco) }
            v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f)
                .setDuration(Estilo.FOCO_MS).start()
        }
    }

    private fun info(titulo: String, detalhe: String, nivel: Int = -1): TextView = TextView(this).apply {
        montarLinha(titulo, detalhe, nivel)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }

    private fun TextView.mostrar(item: Diagnostico.Item) = montarLinha(item.titulo, item.detalhe, item.nivel, hasFocus())

    /** O que a linha mostra, guardado na própria linha para remontar com as cores certas quando o foco muda. */
    private data class Conteudo(val titulo: String, val detalhe: String, val nivel: Int)

    /** "● Título" (bolinha na cor do nível; -1 = sem bolinha) e o detalhe embaixo, menor e suave. */
    private fun TextView.montarLinha(titulo: String, detalhe: String, nivel: Int, foco: Boolean = false) {
        tag = Conteudo(titulo, detalhe, nivel)
        val claro = if (foco) Estilo.TEXTO_NO_FOCO else textoClaro
        val suave = if (foco) Estilo.TEXTO_NO_FOCO_2 else textoSuave
        val sb = SpannableStringBuilder()
        if (nivel in 0..2) {
            sb.append("● ")
            sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO else cores[nivel]), 0, sb.length, 0)
            sb.setSpan(AbsoluteSizeSpan(dp(18), false), 0, sb.length, 0)
        }
        val ini0 = sb.length
        sb.append(titulo)
        sb.setSpan(AbsoluteSizeSpan(dp(18), false), ini0, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(claro), ini0, sb.length, 0)
        sb.setSpan(TypefaceSpan(fonteMedia), ini0, sb.length, 0)
        val ini = sb.length
        sb.append("\n").append(detalhe)
        sb.setSpan(AbsoluteSizeSpan(dp(14), false), ini, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(suave), ini, sb.length, 0)
        text = sb
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(20), dp(14), dp(20), dp(14))
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }
}

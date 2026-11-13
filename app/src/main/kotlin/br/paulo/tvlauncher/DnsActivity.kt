package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Tela "DNS privado": escolher o servidor de DNS da TV (Automático, Desligado, um público, ou um seu).
 * Navegação só com o controle: setas movem, OK escolhe, segurar OK num servidor salvo pergunta se quer remover.
 * A lógica de ler/gravar o DNS fica em Dns.kt; aqui só desenhamos a tela.
 *
 * O estilo (cores, vidro) é copiado do MainActivity de propósito, para não mexer lá; se surgir uma terceira tela,
 * vale mover isso para um arquivo comum.
 */
class DnsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()   // o teste de rede não pode rodar na thread da tela
    private var geracaoTeste = 0   // cada teste novo invalida o anterior (se você trocar de servidor no meio)

    // Cores e fontes do sistema de design (Estilo): nada de cor solta nesta tela.
    private val textoClaro = Estilo.TEXTO
    private val textoSuave = Estilo.TEXTO_2
    private val destaque = Estilo.ACENTO
    private val fonteMedia = Estilo.fonte(500)

    private lateinit var lista: LinearLayout
    private lateinit var txtStatus: TextView
    private var linhaMarcada: View? = null   // a linha do servidor em uso: recebe o foco ao desenhar a lista

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        setContentView(montarTela())
        atualizar()
        testar()
    }

    override fun onDestroy() {
        super.onDestroy()
        geracaoTeste++
        trabalho.shutdownNow()
    }

    // ---------------------------------------------------------------- tela

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cfg_dns)))
        txtStatus = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setPadding(0, dp(4), 0, dp(Estilo.E2)); maxLines = 2 }
        raiz.addView(txtStatus)

        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // a linha focada cresce um pouco; sem isso as bordas seriam cortadas
            clipToPadding = false   // sem isto o Android ainda corta o que passa da margem
            setPadding(dp(8), dp(4), dp(8), dp(4))   // folga lateral: a linha focada cresce 2% e a borda seria cortada
        }
        val rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(lista)
        }
        raiz.addView(rolagem, LinearLayout.LayoutParams(MATCH, 0, 1f))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.dns_dica)).apply {
            setTextColor(Estilo.TEXTO_3)
            setPadding(0, dp(10), 0, 0)
        })
        return raiz
    }

    /** Redesenha a lista inteira a partir do estado atual do sistema. */
    private fun atualizar() {
        Dns.guardarAtualSeDesconhecido(this)
        val atual = Dns.atual(this)
        fun emUso(host: String) = atual.modo == Dns.Modo.HOST && atual.host.equals(host, ignoreCase = true)

        lista.removeAllViews()
        linhaMarcada = null

        adicionar(linha(getString(R.string.dns_auto), getString(R.string.dns_auto_det),
            atual.modo == Dns.Modo.AUTOMATICO, { escolher(Dns.Modo.AUTOMATICO, null) }))
        adicionar(linha(getString(R.string.dns_desl), getString(R.string.dns_desl_det),
            atual.modo == Dns.Modo.DESLIGADO, { escolher(Dns.Modo.DESLIGADO, null) }))

        secao(getString(R.string.dns_publicos))
        for (s in Dns.PRESETS) {
            adicionar(linha(s.nome, s.host, emUso(s.host), { escolher(Dns.Modo.HOST, s.host) }))
        }

        secao(getString(R.string.dns_meus))
        val meus = Dns.salvos(this).filter { s -> Dns.PRESETS.none { it.host.equals(s.host, ignoreCase = true) } }
        for (s in meus) {
            adicionar(linha(s.nome, s.host, emUso(s.host), { escolher(Dns.Modo.HOST, s.host) },
                aoSegurar = { pedirRemocao(s, emUso(s.host)) }))
        }
        adicionar(linha(getString(R.string.dns_adicionar), getString(R.string.dns_adicionar_det), false, { dialogoAdicionar() }))

        lista.post { linhaMarcada?.requestFocus() }
        mostrarStatus("")
    }

    private fun adicionar(v: View) {
        lista.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun secao(titulo: String) {
        lista.addView(Estilo.secao(this, titulo).apply { setPadding(dp(4), dp(Estilo.E2), 0, dp(Estilo.E1)) })
    }

    /** Uma linha da lista: marca (●/○), nome e detalhe. [aoSegurar] é opcional (só os servidores salvos têm). */
    private fun linha(
        titulo: String, detalhe: String, marcada: Boolean,
        aoClicar: () -> Unit, aoSegurar: (() -> Unit)? = null,
    ): View {
        val marca = texto(20f, if (marcada) destaque else textoSuave).apply {
            text = if (marcada) "●" else "○"
            minWidth = dp(44)
        }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(17f, textoClaro, fonteMedia).apply {
                text = titulo; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(texto(13f, textoSuave, Estilo.fonte(400)).apply {
                text = detalhe; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = vidro(false)
            addView(marca)
            addView(coluna, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { aoClicar() }
            if (aoSegurar != null) setOnLongClickListener { aoSegurar(); true }
            setOnFocusChangeListener { v, temFoco ->
                Estilo.focarLinha(v, temFoco)
                val escala = if (temFoco) Estilo.ESCALA_LINHA else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(Estilo.FOCO_MS).start()
            }
        }
        if (marcada) linhaMarcada = l
        return l
    }

    // ---------------------------------------------------------------- ações

    private fun escolher(modo: Dns.Modo, host: String?) {
        val anterior = Dns.atual(this)   // para a rede de segurança (DnsSeguro): voltar a ele se o novo não resolver
        if (!Dns.aplicar(this, modo, host)) {
            txtStatus.text = getString(R.string.dns_sem_permissao) +
                "adb shell pm grant br.paulo.tvlauncher android.permission.WRITE_SECURE_SETTINGS"
            return
        }
        Dns.esquecerQueCaiu(this)   // a pessoa escolheu de novo: o aviso de "voltou para o automático" deixa de valer
        atualizar()
        testar()
        // Rede de segurança: se o servidor novo não resolver nomes em ~27 s, volta para o anterior sozinho.
        // Thread própria (não o executor da tela): tem de terminar mesmo se a pessoa sair desta tela logo.
        val novo = Dns.Estado(modo, host)
        val app = applicationContext
        Fundo.rodar {
            if (DnsSeguro.conferirDepoisDeTrocar(app, anterior, novo)) ui.post {
                if (!isDestroyed) { atualizar(); mostrarStatus(getString(R.string.dns_voltou_anterior)) }
            }
        }
    }

    private fun pedirRemocao(servidor: Dns.Servidor, emUso: Boolean) {
        if (emUso) {
            mostrarStatus(getString(R.string.dns_troque_antes))
            return
        }
        CaixaTv(this)
            .setTitle(getString(R.string.dns_remover_q, servidor.nome))
            .setMessage(servidor.host)
            .setPositiveButton(getString(R.string.dns_remover)) { _, _ ->
                Dns.remover(this, servidor.host)
                atualizar()
            }
            .setNegativeButton(getString(R.string.comum_cancelar), null)
            .show()
    }

    private fun dialogoAdicionar() {
        val campoNome = EditText(this).apply {
            hint = getString(R.string.dns_nome_opc)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val campoEndereco = EditText(this).apply {
            hint = getString(R.string.dns_endereco_ex)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(campoNome)
            addView(campoEndereco)
        }
        val dialogo = CaixaTv(this)
            .setTitle(getString(R.string.dns_adicionar_tit))
            .setView(caixa)
            .setPositiveButton(getString(R.string.dns_salvar_usar)) { _, _ ->
                val host = Dns.normalizar(campoEndereco.text.toString())
                if (host == null) {
                    txtStatus.text = getString(R.string.dns_invalido)
                    return@setPositiveButton
                }
                val nome = campoNome.text.toString().trim().ifEmpty { host }
                // Servidor público já está na lista fixa: só passa a usar, sem guardar uma cópia.
                if (Dns.PRESETS.none { it.host == host }) Dns.salvar(this, Dns.Servidor(nome, host))
                escolher(Dns.Modo.HOST, host)
            }
            .setNegativeButton(getString(R.string.comum_cancelar), null)
            .create()
        dialogo.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialogo.show()
    }

    // ---------------------------------------------------------------- status e teste

    private fun mostrarStatus(extra: String) {
        val e = Dns.atual(this)
        val em = when (e.modo) {
            Dns.Modo.AUTOMATICO -> getString(R.string.dns_auto)
            Dns.Modo.DESLIGADO -> getString(R.string.dns_desl)
            Dns.Modo.HOST -> Dns.nomeDe(this, e.host.orEmpty()) ?: e.host.orEmpty()
        }
        txtStatus.text = getString(R.string.dns_em_uso, em) + if (extra.isNotEmpty()) "   ·   $extra" else ""
    }

    /** Resolve um nome de verdade pela rede e mostra o resultado, sem travar a tela. */
    private fun testar() {
        val minha = ++geracaoTeste
        val modo = Dns.atual(this).modo
        mostrarStatus(getString(R.string.dns_testando))
        trabalho.execute {
            try {
                val ms = Dns.testarResolucao(this)
                val situacao = Dns.situacaoDaRede(this)
                ui.post {
                    if (minha == geracaoTeste && !isDestroyed) mostrarStatus(resultado(ms, situacao, modo))
                }
            } catch (e: Throwable) {
                // tela fechada no meio do teste: nada a mostrar
            }
        }
    }

    private fun resultado(ms: Long?, situacao: Dns.Situacao, modo: Dns.Modo): String = when {
        ms == null -> getString(R.string.dns_falha)
        modo == Dns.Modo.DESLIGADO -> getString(R.string.dns_res_sem_cripto, ms)
        situacao.tlsAtivo -> getString(R.string.dns_res_tls, ms) + (situacao.servidor?.let { getString(R.string.dns_res_com, it) } ?: "")
        else -> getString(R.string.dns_res_sem_tls, ms)
    }

    // ---------------------------------------------------------------- utilidades

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    /** Superfície padrão do sistema de design (Estilo.superficie); o foco é aplicado por Estilo.focarLinha. */
    private fun vidro(focado: Boolean): GradientDrawable = Estilo.superficie(this, focado)
}

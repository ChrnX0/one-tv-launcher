package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.ServicosContas.Situacao
import br.paulo.tvlauncher.ServicosFilmes.Servico

/**
 * Configurações > Conta > Serviços de filmes e séries (14/11/2026). Uma linha por serviço, com o que ele faz em uma
 * frase e o estado à direita ("Entrar", "entrando…", o nome da conta). OK numa linha desligada entra; numa conectada,
 * pergunta se quer sair. O painel web (aba Configurações) mostra e faz o mesmo (PainelServicos.kt).
 *
 * Trakt e Simkl: a tela troca para o código GRANDE + QR (o celular abre a página do serviço já com o código) e continua
 * sozinha quando a pessoa termina. MDBList: digitar a chave pessoal. OpenSubtitles: usuário e senha (opcional).
 * O TMDB não tem conta: só mostra "ligado" quando o build tem a chave (e a frase de atribuição que o TMDB exige).
 */
class ServicosFilmesActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val rede = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var lista: LinearLayout
    private var telaCodigo: View? = null
    private var servicoNoCodigo: Servico? = null

    private val volta = object : Runnable {
        override fun run() { atualizarCodigo(); if (telaCodigo != null) ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.sv_titulo), getString(R.string.sv_subtitulo))
        lista = coluna
        setContentView(raiz)
        rede.execute { ServicosContas.renovarSePreciso(this) }   // token do Trakt perto de vencer: renova já
        desenhar()
    }

    private fun detalhe(s: Servico) = getString(when (s) {
        Servico.TRAKT -> R.string.sv_trakt_det
        Servico.SIMKL -> R.string.sv_simkl_det
        Servico.MDBLIST -> R.string.sv_mdblist_det
        Servico.TMDB -> R.string.sv_tmdb_det
        Servico.OPENSUBTITLES -> R.string.sv_os_det
    })

    private fun desenhar(foco: Servico? = null) {
        rede.execute {
            val estados = ServicosContas.estados(this)
            ui.post {
                if (isDestroyed) return@post
                lista.removeAllViews()
                val visiveis = estados.filter { it.situacao != Situacao.INDISPONIVEL }
                if (visiveis.isEmpty()) {
                    lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sv_sem_chave)).apply { textSize = Estilo.Leitura.AVISO })
                    return@post
                }
                var focar: View? = null
                for (e in visiveis) {
                    val valor = when (e.situacao) {
                        Situacao.CONECTADO -> if (e.servico == Servico.TMDB || (e.servico == Servico.MDBLIST && e.usuario.isBlank())) getString(R.string.sv_ativo)
                            else e.usuario.ifBlank { getString(R.string.sv_conectado) }
                        Situacao.ENTRANDO -> getString(R.string.sv_entrando)
                        else -> getString(R.string.sv_desligado)
                    }
                    val det = detalhe(e.servico) + if (e.servico == Servico.TMDB) "\n" + getString(R.string.sv_tmdb_atribuicao) else ""
                    val linha = Estilo.linhaOpcao(this, e.servico.nome, det, Estilo.valor(this, valor, seta = e.servico != Servico.TMDB))
                    ampliar(linha)
                    linha.setOnClickListener { escolher(e) }
                    lista.addView(linha, Estilo.lpLinha(this))
                    if (e.servico == foco || focar == null) focar = linha
                }
                focar?.requestFocus()
            }
        }
    }

    /** Letra de LER (Estilo.Leitura): idosos usam a TV, a 3 m. */
    private fun ampliar(v: View) {
        if (v is TextView) v.textSize = if (v.textSize / resources.displayMetrics.scaledDensity > 14f) Estilo.Leitura.ITEM else Estilo.Leitura.ITEM_DETALHE
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) ampliar(v.getChildAt(i))
    }

    private fun escolher(e: ServicosContas.Estado) {
        val s = e.servico
        when {
            s == Servico.TMDB -> Unit
            // MDBList ligado pela chave do app: OK oferece pôr a chave da própria conta (opcional); com ela, sair.
            s == Servico.MDBLIST && e.situacao == Situacao.CONECTADO -> rede.execute {
                val propria = ServicosContas.conta(this, s) != null
                ui.post { if (!isDestroyed) { if (propria) perguntarSair(s) else escolher(e.copy(situacao = Situacao.DESLIGADO)) } }
            }
            e.situacao == Situacao.CONECTADO -> perguntarSair(s)
            s == Servico.TRAKT || s == Servico.SIMKL -> mostrarCodigo(s)
            s == Servico.MDBLIST -> pedirTexto(s, getString(R.string.sv_mdblist_titulo), getString(R.string.sv_mdblist_texto),
                listOf(getString(R.string.sv_mdblist_dica) to false)) { v -> ServicosContas.definirMdblist(this, v[0]).map { } }
            s == Servico.OPENSUBTITLES -> pedirTexto(s, getString(R.string.sv_os_titulo), getString(R.string.sv_os_texto),
                listOf(getString(R.string.sv_os_usuario) to false, getString(R.string.sv_os_senha) to true)) { v ->
                ServicosContas.definirOpenSubtitles(this, v[0], v[1])
            }
        }
    }

    private fun perguntarSair(s: Servico) {
        CaixaTv(this).setTitle(s.nome).setMessage(getString(R.string.sv_sair_pergunta, s.nome))
            .setPositiveButton(getString(R.string.sv_sair)) { _, _ -> rede.execute { ServicosContas.sair(this, s); ui.post { if (!isDestroyed) desenhar(s) } } }
            .setNegativeButton(getString(R.string.sv_cancelar), null)
            .show()
    }

    /** Caixa com campos ([dicas]: texto da dica e se é senha). [gravar] roda fora da tela e devolve o resultado. */
    private fun pedirTexto(s: Servico, titulo: String, texto: String, dicas: List<Pair<String, Boolean>>, erro: String? = null,
                           gravar: (List<String>) -> Result<Unit>) {
        val campos = dicas.map { (dica, senha) ->
            EditText(this).apply {
                hint = dica; setSingleLine(); textSize = Estilo.Leitura.ITEM
                inputType = InputType.TYPE_CLASS_TEXT or if (senha) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            }
        }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            addView(Estilo.texto(this@ServicosFilmesActivity, Estilo.Papel.LEGENDA, texto).apply { textSize = Estilo.Leitura.ITEM_DETALHE })
            if (erro != null) addView(Estilo.texto(this@ServicosFilmesActivity, Estilo.Papel.LEGENDA, erro).apply {
                textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(0xFFFF8A80.toInt())
            })
            campos.forEach { addView(it) }
        }
        CaixaTv(this).setTitle(titulo).setView(caixa)
            .setPositiveButton(getString(R.string.sv_gravar)) { _, _ ->
                val valores = campos.map { it.text.toString() }
                rede.execute {
                    val r = gravar(valores)
                    ui.post {
                        if (isDestroyed) return@post
                        r.onSuccess { desenhar(s) }
                            .onFailure { pedirTexto(s, titulo, texto, dicas, getString(R.string.sv_falhou, it.message ?: "?"), gravar) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.sv_cancelar), null)
            .show()
    }

    // ================================================================ código grande + QR (Trakt, Simkl)

    private lateinit var cTexto: TextView
    private lateinit var cCodigo: TextView
    private lateinit var cDica: TextView
    private lateinit var cQr: ImageView

    private fun mostrarCodigo(s: Servico) {
        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Estilo.fundoTela(context)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Estilo.tituloTela(this, s.nome))
        cTexto = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sv_pedindo, s.nome)).apply {
            textSize = Estilo.Leitura.SINOPSE; setLineSpacing(0f, 1.25f); setPadding(0, dp(Estilo.E2), dp(32), 0)
        }
        cCodigo = Estilo.texto(this, Estilo.Papel.DISPLAY, "").apply { letterSpacing = 0.18f; setPadding(0, dp(12), 0, dp(12)) }
        cDica = Estilo.texto(this, Estilo.Papel.LEGENDA, "").apply { textSize = Estilo.Leitura.AVISO }
        val fechar = Estilo.botao(this, getString(R.string.sv_fechar)).apply { textSize = Estilo.Leitura.BOTAO; setOnClickListener { fecharCodigo() } }
        col.addView(cTexto); col.addView(cCodigo); col.addView(cDica)
        col.addView(fechar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        tela.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        cQr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.INVISIBLE }
        tela.addView(cQr, LinearLayout.LayoutParams(dp(300), dp(300)))
        telaCodigo = tela
        servicoNoCodigo = s
        setContentView(tela)
        fechar.requestFocus()
        rede.execute {
            val r = runCatching { ServicosContas.entrar(this, s) }
            ui.post {
                if (isDestroyed || servicoNoCodigo != s) { ServicosContas.cancelar(s); return@post }
                val sessao = r.getOrElse { cTexto.text = getString(R.string.sv_falhou, it.message ?: "?"); return@post }
                cQr.setImageBitmap(runCatching { QrCodigo.bitmap(sessao.codigo.urlQr, dp(300)) }.getOrNull())
                cQr.visibility = View.VISIBLE
                ui.post(volta)
            }
        }
    }

    private fun atualizarCodigo() {
        val s = servicoNoCodigo ?: return
        val sessao = ServicosContas.sessao(s) ?: return
        when (sessao.estado) {
            ServicosContas.EstadoLogin.ESPERANDO -> {
                cTexto.text = getString(R.string.sv_passos, sessao.codigo.url.removePrefix("https://"), s.nome)
                cCodigo.text = sessao.codigo.codigoUsuario
                cDica.text = getString(R.string.sv_espera)
            }
            ServicosContas.EstadoLogin.OK -> {
                fimCodigo(getString(R.string.sv_ok, s.nome, if (sessao.usuario.isBlank()) "" else getString(R.string.sv_ok_como, sessao.usuario)))
            }
            ServicosContas.EstadoLogin.ERRO -> fimCodigo(getString(R.string.sv_falhou, getString(when (sessao.motivo) {
                ServicosFilmes.Motivo.RECUSADO -> R.string.sv_motivo_recusado
                ServicosFilmes.Motivo.USADO -> R.string.sv_motivo_usado
                ServicosFilmes.Motivo.ERRO -> R.string.sv_motivo_erro
                else -> R.string.sv_motivo_venceu
            })))
        }
    }

    private fun fimCodigo(msg: String) {
        ui.removeCallbacks(volta)
        cTexto.text = msg; cCodigo.text = ""; cDica.text = ""; cQr.visibility = View.INVISIBLE
        telaCodigo?.tag = "fim"
    }

    private fun fecharCodigo() {
        val s = servicoNoCodigo
        ui.removeCallbacks(volta)
        if (s != null && ServicosContas.sessao(s)?.estado == ServicosContas.EstadoLogin.ESPERANDO) ServicosContas.cancelar(s)
        telaCodigo = null; servicoNoCodigo = null
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.sv_titulo), getString(R.string.sv_subtitulo))
        lista = coluna
        setContentView(raiz)
        desenhar(s)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (telaCodigo != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) fecharCodigo()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onDestroy() {
        ui.removeCallbacks(volta)
        servicoNoCodigo?.let { s -> if (ServicosContas.sessao(s)?.estado == ServicosContas.EstadoLogin.ESPERANDO) ServicosContas.cancelar(s) }
        rede.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

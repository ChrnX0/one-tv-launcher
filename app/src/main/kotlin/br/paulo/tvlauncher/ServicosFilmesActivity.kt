package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.player.ServicosContas.Situacao
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.ServicosFilmes.Servico

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
    companion object {
        private const val EXTRA_ENTRAR = "entrar"
        /** Abre a tela já entrando no [s] (Trakt/Simkl: o código grande + QR). Usado pelo convite da ficha. */
        fun abrirEntrar(ctx: Context, s: Servico) =
            ctx.startActivity(Intent(ctx, ServicosFilmesActivity::class.java).putExtra(EXTRA_ENTRAR, s.id))
    }

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
        // Veio do convite da ficha: vai direto ao código (só Trakt/Simkl e só se ainda não conectou).
        Servico.de(intent.getStringExtra(EXTRA_ENTRAR).orEmpty())?.takeIf { it == Servico.TRAKT || it == Servico.SIMKL }?.let { s ->
            if (savedInstanceState == null && ServicosContas.disponivel(s) && ServicosContas.conta(this, s) == null) mostrarCodigo(s)
        }
    }

    /** Voltou da tela de uma conta (pode ter saído dela): redesenha. */
    private var jaDesenhou = false
    override fun onResume() { super.onResume(); if (jaDesenhou && telaCodigo == null) desenhar() else jaDesenhou = true }

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
                        Situacao.CONECTADO -> if (e.servico == Servico.TMDB) getString(R.string.sv_ativo)
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
            // Conta conectada: a tela dela (o que sincroniza, listas, sair). OpenSubtitles só tem o sair.
            e.situacao == Situacao.CONECTADO && s != Servico.OPENSUBTITLES -> SincronizarActivity.abrir(this, s)
            e.situacao == Situacao.CONECTADO -> perguntarSair(s)
            s == Servico.TRAKT || s == Servico.SIMKL -> mostrarCodigo(s)
            s == Servico.MDBLIST -> mostrarMdblist()
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
            orientation = LinearLayout.VERTICAL; setPaddingRelative(48, 16, 48, 0)
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
                        r.onSuccess { if (servicoNoCodigo == s) fecharCodigo() else desenhar(s) }
                            .onFailure { pedirTexto(s, titulo, texto, dicas, getString(R.string.sv_falhou, textoFalha(it)), gravar) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.sv_cancelar), null)
            .show()
    }

    // ================================================================ MDBList: 3 passos sem digitar no controle

    /**
     * Pedido do dono (14/11/2026, "tem q facilitar ao maximo"): 1. QR para a página da chave no MDBList; 2. QR para o
     * painel web (aba Configurações), onde a pessoa cola a chave no celular; 3. a TV confere na hora. Quem preferir digita
     * aqui mesmo. Quando a chave chega pelo painel, esta tela volta sozinha para a lista.
     */
    private fun mostrarMdblist() {
        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        tela.addView(Estilo.tituloTela(this, getString(R.string.sv_mdblist_titulo)))
        tela.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sv_mdblist_passos)).apply {
            textSize = Estilo.Leitura.SINOPSE; setLineSpacing(0f, 1.2f); setPaddingRelative(0, dp(Estilo.E2), 0, dp(Estilo.E2))
        })
        val qrs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun qr(url: String, rotulo: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(runCatching { QrCodigo.bitmap(url, dp(220)) }.getOrNull()) }, LinearLayout.LayoutParams(dp(220), dp(220)))
            addView(Estilo.texto(context, Estilo.Papel.LEGENDA, rotulo).apply { textSize = Estilo.Leitura.ITEM_DETALHE; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(260), -2))
        }
        qrs.addView(qr(ServicosFilmes.MDBLIST_PREFERENCIAS, getString(R.string.sv_mdblist_qr1)))
        PainelWeb.endereco()?.let { end ->
            qrs.addView(qr(end + "#tvfilmes", getString(R.string.sv_mdblist_qr2, end.removePrefix("http://").trimEnd('/'))),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(48) })
        }
        tela.addView(qrs)
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(0, dp(24), 0, 0) }
        val digitar = Estilo.botao(this, getString(R.string.sv_mdblist_digitar)).apply {
            textSize = Estilo.Leitura.BOTAO
            setOnClickListener {
                pedirTexto(Servico.MDBLIST, getString(R.string.sv_mdblist_titulo), getString(R.string.sv_mdblist_texto),
                    listOf(getString(R.string.sv_mdblist_dica) to false)) { v -> ServicosContas.definirMdblist(this@ServicosFilmesActivity, v[0]).map { } }
            }
        }
        val fechar = Estilo.botao(this, getString(R.string.sv_fechar), primario = false).apply { textSize = Estilo.Leitura.BOTAO; setOnClickListener { fecharCodigo() } }
        botoes.addView(digitar); botoes.addView(fechar, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        tela.addView(botoes)
        telaCodigo = tela; servicoNoCodigo = Servico.MDBLIST
        setContentView(tela)
        digitar.requestFocus()
        ui.post(volta)
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
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Estilo.tituloTela(this, s.nome))
        cTexto = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sv_pedindo, s.nome)).apply {
            textSize = Estilo.Leitura.SINOPSE; setLineSpacing(0f, 1.25f); setPaddingRelative(0, dp(Estilo.E2), dp(32), 0)
        }
        cCodigo = Estilo.texto(this, Estilo.Papel.DISPLAY, "").apply { letterSpacing = 0.18f; setPaddingRelative(0, dp(12), 0, dp(12)) }
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
                val sessao = r.getOrElse { cTexto.text = getString(R.string.sv_falhou, textoFalha(it)); return@post }
                cQr.setImageBitmap(runCatching { QrCodigo.bitmap(sessao.codigo.urlQr, dp(300)) }.getOrNull())
                cQr.visibility = View.VISIBLE
                ui.post(volta)
            }
        }
    }

    private fun atualizarCodigo() {
        val s = servicoNoCodigo ?: return
        // MDBList: a chave chegou (pelo painel ou digitada): volta para a lista mostrando "conectado".
        if (s == Servico.MDBLIST) {
            rede.execute { val tem = ServicosContas.conta(this, s) != null; ui.post { if (!isDestroyed && tem && servicoNoCodigo == s) fecharCodigo() } }
            return
        }
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

    /** A falha no idioma da TV. Erro de rede ou interno vira "o serviço não respondeu" em vez do texto cru do Java. */
    private fun textoFalha(e: Throwable): String {
        val f = e as? ServicosContas.FalhaConta ?: return getString(R.string.sv_motivo_erro)
        return when (f.motivo) {
            ServicosContas.Motivo.CHAVE_INVALIDA -> getString(R.string.sv_f_chave_invalida)
            ServicosContas.Motivo.CHAVE_RECUSADA -> getString(R.string.sv_f_chave_recusada, f.servico)
            ServicosContas.Motivo.RESPONDEU -> getString(R.string.sv_f_respondeu, f.servico, f.codigo)
            ServicosContas.Motivo.FALTA_DADOS -> getString(R.string.sv_f_falta)
            ServicosContas.Motivo.LONGO -> getString(R.string.sv_f_longo)
            ServicosContas.Motivo.SENHA_ERRADA -> getString(R.string.sv_f_senha)
            ServicosContas.Motivo.GRAVAR -> getString(R.string.sv_f_gravar)
            ServicosContas.Motivo.DO_SERVICO -> f.message ?: getString(R.string.sv_motivo_erro)   // o texto do próprio serviço (inglês)
        }
    }
}

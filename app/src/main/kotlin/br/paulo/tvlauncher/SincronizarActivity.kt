package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.player.ServicosFilmes.Servico
import br.paulo.tvlauncher.player.ServicosSync
import br.paulo.tvlauncher.player.SincronizarFilmes
import br.paulo.tvlauncher.player.SincronizarFilmes.ListaFora

/**
 * A tela de uma conta conectada (esboço sincronizar.png, 14/11/2026): Configurações › TV e filmes › Serviços de filmes
 * e séries › Trakt (ou Simkl, ou MDBList). Uma linha por sincronização, cada uma ligada separada, tudo começando
 * desligado; "Listas no catálogo" abre a escolha das listas; "Sincronizar agora"; "Sair". O painel web faz o mesmo
 * (PainelServicos). MDBList só tem as listas e o sair.
 */
class SincronizarActivity : Activity() {
    companion object {
        private const val EXTRA = "servico"
        const val PREFS_CONSENTIMENTO = "sync_consentimento"
        fun abrir(ctx: Context, s: Servico) = ctx.startActivity(Intent(ctx, SincronizarActivity::class.java).putExtra(EXTRA, s.id))
    }

    private val ui = Handler(Looper.getMainLooper())
    private val rede = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var servico: Servico
    private lateinit var raiz: View
    private lateinit var lista: LinearLayout
    private var naEscolha = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        servico = Servico.de(intent.getStringExtra(EXTRA).orEmpty())?.takeIf { it == Servico.TRAKT || it == Servico.SIMKL || it == Servico.MDBLIST }
            ?: run { finish(); return }
        val (r, c) = Estilo.telaLista(this, servico.nome, "")
        raiz = r; lista = c
        setContentView(raiz)
        desenhar()
    }

    private fun ligado(v: Boolean) = Estilo.valor(this, getString(if (v) R.string.sync_ligado else R.string.sync_desligado), seta = false).apply {
        if (!v) setTextColor(Estilo.TEXTO_3)
    }

    /** Letra de LER (Estilo.Leitura), como na tela dos serviços. */
    private fun ampliar(v: View) {
        if (v is TextView) v.textSize = if (v.textSize / resources.displayMetrics.scaledDensity > 14f) Estilo.Leitura.ITEM else Estilo.Leitura.ITEM_DETALHE
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) ampliar(v.getChildAt(i))
    }

    private fun linha(titulo: String, det: String?, direita: View?, foco: Boolean = false, aoOk: () -> Unit): View =
        Estilo.linhaOpcao(this, titulo, det, direita).also { v ->
            ampliar(v); v.setOnClickListener { aoOk() }
            lista.addView(v, Estilo.lpLinha(this))
            if (foco) v.requestFocus()
        }

    private fun desenhar(focar: Int = 0) {
        naEscolha = false
        rede.execute {
            val conta = ServicosContas.conta(this, servico)
            val o = ServicosSync.opcoes(this, servico)
            val listas = ServicosSync.listas(this)
            val ultima = ServicosSync.ultima(this, servico)
            val aviso = ServicosSync.aviso(this, servico)
            val bloqueado = ServicosSync.bloqueado(this)
            val outroPerfil = !ServicosSync.perfilCerto(this)
            ui.post {
                if (isDestroyed) return@post
                if (conta == null) {   // a conta saiu (ou nunca entrou): diz o que falta em vez de fechar calada
                    AvisoTv.rapido(this, getString(R.string.aviso_sync_titulo), getString(R.string.aviso_sync_texto, servico.nome)); finish(); return@post
                }
                lista.removeAllViews()
                val nome = conta.usuario.ifBlank { servico.nome }
                val sub = if (servico == Servico.MDBLIST) getString(R.string.sync_sub_mdblist, nome) else getString(R.string.sync_sub, nome) + " " +
                    (if (ultima > 0) getString(R.string.sync_ultima, DateUtils.getRelativeTimeSpanString(ultima, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    else getString(R.string.sync_nunca))
                lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, sub).apply { textSize = Estilo.Leitura.SINOPSE })
                val parado = when { bloqueado -> getString(R.string.sync_bloqueado); outroPerfil -> getString(R.string.sync_outro_perfil); else -> aviso }
                if (parado != null) lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, parado).apply {
                    textSize = Estilo.Leitura.AVISO; setTextColor(Estilo.ATENCAO); setPaddingRelative(0, dp(8), 0, 0)
                })
                var i = 0
                fun proxima() = (i++ == focar)
                if (servico != Servico.MDBLIST) {
                    val nInteresses = getString(if (servico == Servico.SIMKL) R.string.sync_interesses_simkl else R.string.sync_interesses)
                    // As três que MANDAM dados para fora pedem o consentimento antes de ligar (consentirEntao). Assistidos só traz.
                    linha(nInteresses, getString(R.string.sync_interesses_det), ligado(o.interesses), proxima()) {
                        consentirEntao(!o.interesses) { mudar(0) { it.copy(interesses = !it.interesses) } } }
                    linha(getString(R.string.sync_assistidos), getString(R.string.sync_assistidos_det), ligado(o.assistidos), proxima()) { mudar(1) { it.copy(assistidos = !it.assistidos) } }
                    linha(getString(R.string.sync_continuar), getString(R.string.sync_continuar_det), ligado(o.continuar), proxima()) {
                        consentirEntao(!o.continuar) { mudar(2) { it.copy(continuar = !it.continuar) } } }
                    linha(getString(R.string.sync_contar, servico.nome), getString(R.string.sync_contar_det), ligado(o.contar), proxima()) {
                        consentirEntao(!o.contar) { mudar(3) { it.copy(contar = !it.contar) } } }
                }
                if (servico != Servico.SIMKL) {
                    val det = if (listas.isEmpty()) getString(R.string.sync_listas_nenhuma) else getString(R.string.sync_listas_n, listas.size, listas.joinToString(", ") { it.nome })
                    linha(getString(R.string.sync_listas), det, Estilo.valor(this, "", seta = true), proxima()) { escolherListas() }
                }
                if (servico != Servico.MDBLIST) {
                    val idx = i
                    linha(getString(R.string.sync_agora), getString(R.string.sync_agora_det), null, proxima()) { sincronizarAgora(idx) }
                }
                linha(getString(R.string.sync_sair, servico.nome), null, null, proxima()) { sair() }
                // Atribuição que o Simkl e o Trakt pedem onde os dados deles aparecem.
                val fonte = when (servico) { Servico.TRAKT -> R.string.sync_fonte_trakt; Servico.SIMKL -> R.string.sync_fonte_simkl; else -> 0 }
                if (fonte != 0) lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(fonte)).apply {
                    textSize = Estilo.Leitura.ITEM_DETALHE; setPaddingRelative(0, dp(16), 0, 0)
                })
            }
        }
    }

    /**
     * Consentimento por serviço (16/11/2026, como os "addons de rastreamento" do Stremio): antes de ligar a primeira opção
     * que manda dados desta TV para o Trakt/Simkl, a pessoa lê o que vai e aceita. Vale para o serviço até sair da conta
     * (sair apaga o aceite: a próxima conta pergunta de novo). Desligar nunca pergunta. Fica fora do backup (prefs próprio).
     */
    private fun consentirEntao(ligando: Boolean, acao: () -> Unit) {
        val prefs = getSharedPreferences(PREFS_CONSENTIMENTO, MODE_PRIVATE)
        if (!ligando || prefs.getBoolean(servico.name, false)) { acao(); return }
        CaixaTv(this).setTitle(getString(R.string.cf_consent_titulo, servico.nome))
            .setMessage(getString(R.string.cf_consent_texto, servico.nome))
            .setPositiveButton(getString(R.string.cf_consent_sim)) { _, _ -> prefs.edit().putBoolean(servico.name, true).apply(); acao() }
            .setNegativeButton(getString(R.string.sv_cancelar), null)
            .show()
    }

    private fun mudar(foco: Int, f: (SincronizarFilmes.Opcoes) -> SincronizarFilmes.Opcoes) {
        rede.execute {
            ServicosSync.definirOpcoes(this, servico, f(ServicosSync.opcoes(this, servico)))
            ui.post { if (!isDestroyed) desenhar(foco) }
        }
    }

    private fun sincronizarAgora(foco: Int) {
        aviso(getString(R.string.sync_rodando))
        rede.execute {
            ServicosSync.sincronizarTudo(this)
            ui.post { if (!isDestroyed) { aviso(getString(R.string.sync_feito)); desenhar(foco) } }
        }
    }

    private fun sair() {
        CaixaTv(this).setTitle(servico.nome).setMessage(getString(R.string.sv_sair_pergunta, servico.nome))
            .setPositiveButton(getString(R.string.sv_sair)) { _, _ ->
                rede.execute {
                    ServicosContas.sair(this, servico); ServicosSync.esquecer(this, servico)
                    getSharedPreferences(PREFS_CONSENTIMENTO, MODE_PRIVATE).edit().remove(servico.name).apply()
                    ui.post { if (!isDestroyed) finish() }
                }
            }
            .setNegativeButton(getString(R.string.sv_cancelar), null)
            .show()
    }

    // ================================================================ escolher as listas

    private fun escolherListas() {
        naEscolha = true
        lista.removeAllViews()
        lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sync_listas_sub, SincronizarFilmes.MAX_LISTAS)).apply { textSize = Estilo.Leitura.SINOPSE })
        val carregando = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sync_listas_carregando)).apply { textSize = Estilo.Leitura.AVISO; setPaddingRelative(0, dp(16), 0, 0) }
        lista.addView(carregando)
        rede.execute {
            val disponiveis = ServicosSync.listasDisponiveis(this)
            ui.post {
                if (isDestroyed || !naEscolha) return@post
                lista.removeView(carregando)
                // As já escolhidas aparecem mesmo se a rede não trouxe (para poder desmarcar).
                val escolhidas = ServicosSync.listas(this).toMutableList()
                val todas = (escolhidas + disponiveis).distinctBy { it.chave }
                if (todas.isEmpty()) {
                    lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.sync_listas_vazio)).apply { textSize = Estilo.Leitura.AVISO; setPaddingRelative(0, dp(16), 0, 0) })
                    return@post
                }
                var primeira = true
                for (l in todas) {
                    val marca = Estilo.valor(this, if (escolhidas.any { it.chave == l.chave }) "✓" else "", seta = false)
                    val det = (if (l.servico == "trakt") "Trakt" else "MDBList") + " · " + getString(R.string.sync_lista_itens, l.total) +
                        (if (l.dono.isNotBlank()) " · ${l.dono}" else "")
                    linha(l.nome, det, marca, primeira) { alternar(l, escolhidas, marca) }
                    primeira = false
                }
            }
        }
    }

    private fun alternar(l: ListaFora, escolhidas: MutableList<ListaFora>, marca: TextView) {
        val i = escolhidas.indexOfFirst { it.chave == l.chave }
        if (i >= 0) escolhidas.removeAt(i)
        else if (escolhidas.size >= SincronizarFilmes.MAX_LISTAS) { aviso(getString(R.string.sync_listas_cheio, SincronizarFilmes.MAX_LISTAS)); return }
        else escolhidas += l
        marca.text = if (i >= 0) "" else "✓"
        val copia = escolhidas.toList()
        rede.execute { ServicosSync.definirListas(this, copia); ServicosSync.limparListas(this) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && naEscolha) {
            if (e.action == KeyEvent.ACTION_UP) desenhar(focar = 4)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onDestroy() { rede.shutdown(); ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun aviso(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

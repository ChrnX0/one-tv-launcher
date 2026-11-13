package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela "Casa" (ideia grande 1): os aparelhos do Home Assistant com o controle. OK liga/desliga (cena e script: roda).
 * Configurar o endereço e o token é pelo painel (aba Extras), com PIN: digitar um token longo no controle não dá.
 */
class CasaActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private lateinit var situacao: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Molde único de tela (Estilo.telaLista): fundo grafite da HOME, título e margens do sistema de design.
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.casa_titulo))
        coluna = col
        situacao = Estilo.texto(this, Estilo.Papel.LEGENDA)
        coluna.addView(situacao)
        // Google Home (dono, 10/11/2026): abre o painel do Google TV (luzes, câmeras, timer), o mesmo do HOME segurado.
        indiceGoogleHome = coluna.childCount
        coluna.addView(botao(getString(R.string.casa_google_home)) { Home.abrirPainelGoogle(this) })
        setContentView(raiz)
    }

    override fun onResume() { super.onResume(); carregar() }

    private fun carregar() {
        if (!CasaHA.configurado(this)) { situacao.text = getString(R.string.casa_sem_config); coluna.getChildAt(fixos - 1).requestFocus(); return }
        situacao.text = getString(R.string.casa_carregando)
        val app = applicationContext
        Fundo.rodar {
            val r = try { Result.success(CasaHA.listar(app)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread { if (!isFinishing) mostrar(r) }
        }
    }

    private fun mostrar(r: Result<List<CasaHA.Entidade>>) {
        while (coluna.childCount > fixos) coluna.removeViewAt(fixos)
        val lista = r.getOrElse { situacao.text = it.message ?: it.javaClass.simpleName; return }
        situacao.text = if (lista.isEmpty()) getString(R.string.casa_vazio) else ""
        for (e in lista) {
            val acao = if (e.dominio == "scene" || e.dominio == "script") "ligar" else "alternar"
            coluna.addView(botao("${e.nome}  ·  ${e.estado}") {
                val app = applicationContext
                Fundo.rodar {
                    val msg = try { CasaHA.acionar(app, e.id, acao); null } catch (ex: Exception) { ex.message }
                    runOnUiThread { if (msg != null) situacao.text = msg; carregar() }
                }
            })
        }
        coluna.getChildAt(if (coluna.childCount > fixos) fixos else fixos - 1).requestFocus()
    }

    /** Linha padrão do sistema de design (mesmo foco e superfície das outras telas). */
    private fun botao(texto: String, ao: () -> Unit) = Estilo.linhaTexto(this, texto).apply {
        layoutParams = Estilo.lpLinha(this@CasaActivity)
        setOnClickListener { ao() }
    }

    /** Filhos fixos do topo (o molde + situação + Google Home); a lista de aparelhos vem depois deles. */
    private val fixos get() = indiceGoogleHome + 1
    private var indiceGoogleHome = 0
}

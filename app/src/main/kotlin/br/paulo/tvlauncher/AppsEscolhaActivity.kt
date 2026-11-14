package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Uma linha por app de TV, com OK para mudar (14/11/2026). Dois usos, mesma tela:
 *  - [MODO_OCULTOS]: o que aparece na tela inicial. Substitui o card "Ocultos (N)" que ficava na HOME (o dono achou
 *    estranho um card escrito "oculto" na tela inicial). Os ocultos vêm primeiro.
 *  - [MODO_VISITA]: os apps que somem no modo visita (antes era uma lista longa aberta dentro das Configurações).
 * A lista de apps (PackageManager) é lida fora da thread da tela.
 */
class AppsEscolhaActivity : Activity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val MODO_OCULTOS = "ocultos"
        const val MODO_VISITA = "visita"
    }

    private lateinit var coluna: LinearLayout
    private var modo = MODO_OCULTOS
    private val linhas = mutableListOf<Pair<TextView, () -> String>>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_OCULTOS
        val visita = modo == MODO_VISITA
        val (raiz, col) = Estilo.telaLista(this, getString(if (visita) R.string.cl_apps_visita else R.string.oc_titulo),
            getString(if (visita) R.string.cfg2_apps_visita_sub else R.string.oc_subtitulo))
        coluna = col
        setContentView(raiz)
        coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.cl_carregando_apps)))
        Fundo.rodar("apps-escolha") {
            val apps = carregar()
            runOnUiThread { if (!isDestroyed) montar(apps) }
        }
    }

    /** Os mesmos apps que a HOME mostra (MainActivity.carregarApps): os do usuário e os de sistema em SEMPRE_MOSTRAR. */
    private fun carregar(): List<Pair<String, String>> = try {
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter {
                val pkg = it.activityInfo.packageName
                pkg != packageName && pkg != Config.PACOTE_CONFIG &&
                    ((it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pkg in Config.SEMPRE_MOSTRAR)
            }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    } catch (_: Exception) { emptyList() }

    private fun marcado(pacote: String): Boolean =
        if (modo == MODO_VISITA) ModoVisita.escondeNaVisita(this, pacote) else pacote in Organizacao.ocultos(this)

    private fun alternar(pacote: String) {
        if (modo == MODO_VISITA) ModoVisita.alternar(this, pacote)
        else if (pacote in Organizacao.ocultos(this)) Organizacao.mostrar(this, pacote) else Organizacao.ocultar(this, pacote)
    }

    private fun montar(apps: List<Pair<String, String>>) {
        coluna.removeAllViews()
        // Ocultos primeiro: quem abre esta tela quase sempre quer achar um que sumiu.
        val ordem = if (modo == MODO_OCULTOS) apps.sortedByDescending { marcado(it.first) } else apps
        for ((pacote, nome) in ordem) {
            val texto = {
                "$nome: " + getString(when {
                    modo == MODO_VISITA -> if (marcado(pacote)) R.string.cfg2_some_visita else R.string.cfg2_fica_visita
                    marcado(pacote) -> R.string.oc_oculto
                    else -> R.string.oc_aparece
                })
            }
            val t = Estilo.linhaTexto(this, texto()).apply {
                setOnClickListener { alternar(pacote); this.text = texto() }
            }
            coluna.addView(t, Estilo.lpLinha(this))
            linhas += t to texto
        }
        if (apps.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.oc_nenhum)))
        linhas.firstOrNull()?.first?.requestFocus()
    }
}

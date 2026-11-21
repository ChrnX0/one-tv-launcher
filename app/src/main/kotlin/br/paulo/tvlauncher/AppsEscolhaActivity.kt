package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.family.HospedeRegras
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.games.Jogos
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.home.Organizacao

/**
 * Uma linha por app de TV, com OK para mudar (14/11/2026). Dois usos, mesma tela:
 *  - [MODO_OCULTOS]: o que aparece na tela inicial. Substitui o card "Ocultos (N)" que ficava na HOME (o dono achou
 *    estranho um card escrito "oculto" na tela inicial). Os ocultos vêm primeiro.
 *  - [MODO_VISITA]: os apps que somem no modo visita (antes era uma lista longa aberta dentro das Configurações).
 *  - [MODO_JOGOS]: quais apps moram no card Jogos (14/11/2026): para o app de jogo que o launcher não reconheceu sozinho,
 *    ou para tirar um que não é jogo. Os do card vêm primeiro.
 * A lista de apps (PackageManager) é lida fora da thread da tela.
 */
class AppsEscolhaActivity : Activity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val MODO_OCULTOS = "ocultos"
        const val MODO_VISITA = "visita"
        const val MODO_JOGOS = "jogos"
        /** Modo hóspede (degrau 2): os apps que a limpeza da saída apaga (ModoHospede). */
        const val MODO_HOSPEDE = "hospede"
    }

    private lateinit var coluna: LinearLayout
    private var modo = MODO_OCULTOS
    private val linhas = mutableListOf<Pair<TextView, () -> String>>()
    @Volatile private var hospede: MutableSet<String> = HashSet()   // MODO_HOSPEDE: lido uma vez fora da tela

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        VigiaDesfazer.ligar(this, getString(R.string.desf_rot_apps))   // elevação 2.11
        modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_OCULTOS
        val visita = modo == MODO_VISITA
        val (raiz, col) = when (modo) {
            MODO_JOGOS -> Estilo.telaLista(this, getString(R.string.jogos_escolher), getString(R.string.jogos_escolher_sub))
            MODO_HOSPEDE -> Estilo.telaLista(this, getString(R.string.hos_apps), getString(R.string.hos_apps_sub))
            else -> Estilo.telaLista(this, getString(if (visita) R.string.cl_apps_visita else R.string.oc_titulo),
                getString(if (visita) R.string.cfg2_apps_visita_sub else R.string.oc_subtitulo))
        }
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
                    ((it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        // Hóspede (degrau 3): apps de fábrica também (a Netflix vem de fábrica), menos os protegidos.
                        (if (modo == MODO_HOSPEDE) pkg !in HospedeRegras.PROTEGIDOS else pkg in Config.SEMPRE_MOSTRAR))
            }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .also { if (modo == MODO_HOSPEDE) hospede = (ModoHospede.escolhidos(this) ?: ModoHospede.paraLimpar(this).toSet()).toMutableSet() }
            .also { l -> if (modo == MODO_JOGOS) l.forEach { JogosTv.ehJogo(this, it.first) } }   // deixa a resposta em cache, fora da tela
    } catch (_: Exception) { emptyList() }

    private fun marcado(pacote: String): Boolean = when (modo) {
        MODO_VISITA -> ModoVisita.escondeNaVisita(this, pacote)
        MODO_HOSPEDE -> pacote in hospede
        MODO_JOGOS -> JogosTv.ehJogo(this, pacote)
        else -> pacote in Organizacao.ocultos(this)
    }

    private fun alternar(pacote: String) {
        if (modo == MODO_JOGOS) JogosTv.escolher(this, pacote, if (marcado(pacote)) Jogos.FORA else Jogos.grupo(pacote, emptyMap()).name)
        else if (modo == MODO_VISITA) ModoVisita.alternar(this, pacote)
        else if (modo == MODO_HOSPEDE) { if (ModoHospede.alternarApp(this, pacote)) hospede.add(pacote) else hospede.remove(pacote) }
        else if (pacote in Organizacao.ocultos(this)) Organizacao.mostrar(this, pacote) else Organizacao.ocultar(this, pacote)
    }

    private fun montar(apps: List<Pair<String, String>>) {
        coluna.removeAllViews()
        // Ocultos primeiro: quem abre esta tela quase sempre quer achar um que sumiu.
        val ordem = if (modo == MODO_OCULTOS || modo == MODO_JOGOS) apps.sortedByDescending { marcado(it.first) } else apps
        for ((pacote, nome) in ordem) {
            val texto = {
                "$nome: " + getString(when {
                    modo == MODO_JOGOS -> if (marcado(pacote)) R.string.jogos_no_card else R.string.jogos_na_linha
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

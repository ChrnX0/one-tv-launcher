package br.paulo.tvlauncher.settings

import android.content.Intent
import android.widget.LinearLayout
import br.paulo.tvlauncher.AutomacoesActivity
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.EntradasTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.home.StartupApp
import br.paulo.tvlauncher.network.Cenas

/**
 * Linhas de "Ao ligar a TV" na aba Tela e energia (StartupApp.kt). Ficam juntas com o protetor e o "desligar sem uso"
 * porque é a mesma conversa: o que a TV faz quando acende e quando apaga.
 */
internal fun ConfigLauncherActivity.linhasAoLigar(c: LinearLayout) {
    // Nome legível de uma escolha (tela inicial, último app, entrada HDMI ou app).
    fun nome(v: String): String = when {
        v == StartupApp.HOME -> getString(R.string.su_home)
        v == StartupApp.LAST -> getString(R.string.su_last)
        EntradasTv.eh(v) -> EntradasTv.listar(this).firstOrNull { it.chave == v }?.nome ?: v.removePrefix(EntradasTv.PREFIXO)
        else -> Cenas.appsInstalados(this)[v] ?: v
    }
    val contas = Perfil.contas(this)
    val ativa = Perfil.ativa(this, contas)
    fun resumo(): String {
        val proprio = StartupApp.choiceForProfile(this, ativa)
        val v = nome(StartupApp.choice(this))
        // Com 2+ perfis, diz de quem é a escolha que vale agora (a do perfil ou a herdada da TV).
        return if (contas.size >= 2 && ativa != null && proprio != null) getString(R.string.su_so, Perfil.nome(this, ativa)) + " · " + v else v
    }

    lateinit var linhaApp: LinhaCfg
    linhaApp = linha(getString(R.string.su_titulo), getString(R.string.su_det), valor = resumo()) {
        // Tela inicial e Último app primeiro; depois entradas HDMI (só onde existem) e os apps em ordem alfabética.
        val apps = Cenas.appsInstalados(this).filterKeys { it != packageName }.entries.sortedBy { it.value.lowercase() }
        val valores = listOf(StartupApp.HOME, StartupApp.LAST) + EntradasTv.listar(this).map { it.chave } + apps.map { it.key }
        val atual = valores.indexOf(StartupApp.choice(this))
        Estilo.lista(this, getString(R.string.su_titulo), valores.map { nome(it) }, marcadas = listOfNotNull(atual.takeIf { it >= 0 })) { i ->
            val escolhido = valores[i]
            if (contas.size < 2 || ativa == null) {
                StartupApp.setChoice(this, escolhido, soPerfil = false); linhaApp.valor(resumo()); return@lista
            }
            // Com perfis: a escolha vale para a TV toda ou só para quem está vendo agora.
            val opcoes = listOf(getString(R.string.su_todos), getString(R.string.su_so, Perfil.nome(this, ativa)), getString(R.string.su_segue_tv))
            Estilo.lista(this, getString(R.string.su_quem), opcoes) { q ->
                when (q) {
                    0 -> StartupApp.setChoice(this, escolhido, soPerfil = false)
                    1 -> StartupApp.setChoice(this, escolhido, soPerfil = true)
                    else -> StartupApp.clearProfile(this)
                }
                linhaApp.valor(resumo())
            }
        }
    }
    c.addView(linhaApp, lp())

    lateinit var linhaAcordar: LinhaCfg
    linhaAcordar = linha(getString(R.string.su_acordar), getString(R.string.su_acordar_det), chave = StartupApp.onWake(this)) {
        val novo = !StartupApp.onWake(this)
        StartupApp.setOnWake(this, novo)
        linhaAcordar.ligado(novo)
    }
    c.addView(linhaAcordar, lp())

    lateinit var linhaEsperar: LinhaCfg
    linhaEsperar = linha(getString(R.string.su_esperar), getString(R.string.su_esperar_det),
        valor = getString(R.string.su_seg, StartupApp.delaySeconds(this))) {
        val novo = StartupApp.nextDelay(StartupApp.delaySeconds(this))
        Extras.definirNumero(this, StartupApp.KEY_DELAY, novo)
        linhaEsperar.valor(getString(R.string.su_seg, novo))
    }
    c.addView(linhaEsperar, lp())

    // Por horário não se repete aqui: as Automações já fazem ("TV ligou" + "Só se"), com feriados e dias da semana.
    c.addView(linha(getString(R.string.su_horario), getString(R.string.su_horario_det), seta = true) {
        startActivity(Intent(this, AutomacoesActivity::class.java))
    }, lp())
}

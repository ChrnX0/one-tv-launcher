package br.paulo.tvlauncher.settings

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.AboutActivity
import br.paulo.tvlauncher.AjudaActivity
import br.paulo.tvlauncher.Atualizador
import br.paulo.tvlauncher.BackupNoturno
import br.paulo.tvlauncher.BackupNoturnoActivity
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.ConferirTvActivity
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.Desfazer
import br.paulo.tvlauncher.DesfazerRegras
import br.paulo.tvlauncher.DiagnosticoActivity
import br.paulo.tvlauncher.ExtrasActivity
import br.paulo.tvlauncher.FilaAppsActivity
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.LicencasActivity
import br.paulo.tvlauncher.PlacarActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.ReceberTvActivity
import br.paulo.tvlauncher.ReceitasActivity
import br.paulo.tvlauncher.RelatarProblemaActivity
import br.paulo.tvlauncher.StorageActivity
import br.paulo.tvlauncher.TelaMudancas
import br.paulo.tvlauncher.VigiaAtualizacao
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.family.UsoLocal
import br.paulo.tvlauncher.family.UsoTv
import br.paulo.tvlauncher.home.SplashOverlay
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.observer.LimpezaMedida
import br.paulo.tvlauncher.observer.Recursos
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.privilege.Debloat
import br.paulo.tvlauncher.privilege.PareamentoNaTv
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.privilege.SaudeAcesso
import br.paulo.tvlauncher.privilege.ShellProprio
import br.paulo.tvlauncher.privilege.VirarDono

private const val PEDIDO_RECURSOS = 41   // código do requestPermissions dos recursos (Recursos.kt)

/** Aba Sistema das Configurações. Saiu da ConfigLauncherActivity na refatoração de 21/11/2026. */

internal fun ConfigLauncherActivity.abaSistema(c: LinearLayout) {
    // Degrau 2 (UsoTv): Simples / Completo / Para quem cuida. OK troca; as abas técnicas aparecem ou somem na hora.
    lateinit var linhaUso: LinhaCfg
    linhaUso = linha(getString(R.string.uso_titulo), getString(R.string.uso_det), valor = nomeUso(UsoTv.atual(this))) {
        val novo = UsoTv.proximo(UsoTv.atual(this))
        UsoTv.escolher(this, novo)
        linhaUso.valor(nomeUso(novo))
        mostrarAbasDoUso()
    }
    c.addView(linhaUso, lp())
    // Degrau 4: receitas prontas ("Para quem é esta TV?"), com Desfazer (ReceitasTv.kt).
    c.addView(linha(getString(R.string.rec_titulo), getString(R.string.rec_subtitulo), seta = true) {
        startActivity(Intent(this, ReceitasActivity::class.java))
    }, lp())
    // Placar de promessas (elevação 2.1): os 7 pilares com número medido nesta TV, alvo e data.
    c.addView(linha(getString(R.string.elv_placar_titulo), getString(R.string.elv_placar_det), seta = true) {
        startActivity(Intent(this, PlacarActivity::class.java))
    }, lp())
    // Diagnóstico de 1 botão (04/11/2026): o 1º da aba, para ser o OK mais rápido quando "o app parou".
    c.addView(linha(getString(R.string.cfg_diag), getString(R.string.cfg_diag_det), seta = true) {
        startActivity(Intent(this, DiagnosticoActivity::class.java))
    }, lp())
    // Degrau 3: "Nada sai da sua casa", o compromisso de privacidade em uma tela (o mesmo texto do site).
    c.addView(linha(getString(R.string.nsc_titulo), getString(R.string.nsc_det), seta = true) {
        CaixaTv(this).setTitle(getString(R.string.nsc_titulo)).setMessage(getString(R.string.nsc_texto))
            .setPositiveButton("OK", null).show()
    }, lp())
    // Degrau 3: relatar um problema pelo celular (QR com os dados técnicos, RelatarProblema.kt).
    c.addView(linha(getString(R.string.rel_titulo), getString(R.string.rel_linha_det), seta = true) {
        startActivity(Intent(this, RelatarProblemaActivity::class.java))
    }, lp())
    // Backup noturno cifrado (BackupNoturno.kt): as opções ficam numa tela própria; ao voltar, o resumo se atualiza.
    c.addView(linha(getString(R.string.bkn_titulo), BackupNoturno.resumo(this), seta = true) {
        reconstruirAoVoltar = true
        startActivity(Intent(this, BackupNoturnoActivity::class.java))
    }, lp())

    // Pen drive e armazenamento (20/11/2026, StorageActivity): comum, misto ou memória da TV, e quais apps vão para ele.
    c.addView(linha(getString(R.string.st_titulo), getString(R.string.st_linha_det), seta = true) {
        startActivity(Intent(this, StorageActivity::class.java))
    }, lp())

    // Uso local das funções (ideia 67): o que é usado e o que ninguém abre. Fica só na TV.
    c.addView(linha(getString(R.string.l51_uso_titulo), getString(R.string.l51_uso_det), seta = true) {
        UsoLocal.mostrar(this)
    }, lp())

    // Atualização (Atualizador.kt): o launcher confere o GitHub a cada 6 h e instala sozinho com a tela apagada.
    // "Procurar" só confere; "Instalar a versão N" só aparece quando há um Release que ESTA TV aceita (mesma chave).
    secao(c, getString(R.string.cfg_sec_atualizacao))
    lateinit var linhaAtualiza: LinhaCfg
    lateinit var linhaInstalar: LinhaCfg
    fun mostrarInstalar() {
        val n = Atualizador.pendente(this)
        linhaInstalar.visibility = if (n == null) View.GONE else View.VISIBLE
        if (n != null) linhaInstalar.montarLinha(getString(R.string.cfg_instalar, n), getString(R.string.cfg_instalar_det))
    }
    linhaAtualiza = linha(getString(R.string.cfg_atualizar), getString(R.string.cfg_atualizar_det, versaoInstalada().toString(), Atualizador.estado(this).substringBefore(" · OK")), seta = true) {
        linhaAtualiza.detalhe(getString(R.string.cfg_procurando))
        Atualizador.verificar(this, instalar = false) { r -> runOnUiThread { if (!isDestroyed) { linhaAtualiza.detalhe(r); mostrarInstalar() } } }
    }
    linhaInstalar = linha(getString(R.string.cfg_instalar, 0L), getString(R.string.cfg_instalar_det), seta = true) {
        linhaInstalar.detalhe(getString(R.string.cfg_instalando))
        // Baixa e confere de novo antes de instalar (o arquivo da procura não fica guardado). Device Owner: sem pergunta.
        Atualizador.verificar(this, instalar = true) { r -> runOnUiThread { if (!isDestroyed) linhaInstalar.detalhe(r) } }
    }
    c.addView(linhaAtualiza, lp())
    c.addView(linhaInstalar, lp())
    mostrarInstalar()
    // Esta versão (14/11/2026, ideias 45 e 63): o que mudou e o roteiro de conferência com o controle.
    val novidades = Novidades.itens(this)
    if (novidades.isNotEmpty()) c.addView(linha(getString(R.string.ver_novidades),
        getString(R.string.ver_novidades_det, novidades.size, versaoInstalada().toString()), seta = true) { Novidades.mostrar(this) }, lp())
    // Elevação 2.11: as mudanças dos últimos 7 dias, com OK para desfazer cada uma (Desfazer.kt).
    c.addView(linha(getString(R.string.desf_tela), getString(R.string.desf_tela_det), seta = true) {
        TelaMudancas.abrir(this) { recarregarAba() }
    }, lp())
    // Elevação 19.7: TV nova em 2 min (esta TV recebe tudo de outra) e, depois, a fila dos apps que faltam.
    c.addView(linha(getString(R.string.mtv_titulo), getString(R.string.mtv_cfg_det), seta = true) {
        reconstruirAoVoltar = true
        startActivity(Intent(this, ReceberTvActivity::class.java))
    }, lp())
    MudarDeTv.faltam(this).takeIf { it > 0 }?.let { n ->
        c.addView(linha(getString(R.string.mtv_fila_titulo), getString(R.string.mtv_fila_cfg_det, n), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, FilaAppsActivity::class.java))
        }, lp())
    }
    val idsRoteiro = ConferirTvActivity.ids(this)
    if (idsRoteiro.isNotEmpty()) c.addView(linha(getString(R.string.ver_conferir), getString(R.string.ver_conferir_det,
        RoteiroConferencia.conferidos(ConferirTvActivity.estado(this), idsRoteiro), idsRoteiro.size), seta = true) {
        reconstruirAoVoltar = true
        startActivity(Intent(this, ConferirTvActivity::class.java))
    }, lp())
    // Atualização vigiada (VigiaAtualizacao.kt): só aparece se uma versão caiu logo depois de instalar.
    VigiaAtualizacao.textoPausa(this)?.let { pausa ->
        lateinit var linhaSeguro: LinhaCfg
        linhaSeguro = linha(getString(R.string.cfg_vig_liberar), pausa + getString(R.string.cfg_vig_liberar_det), seta = true) {
            VigiaAtualizacao.liberar(this)
            linhaSeguro.visibility = View.GONE
        }
        c.addView(linhaSeguro, lp())
    }

    // Sobre: números úteis para saber se algo por trás dos panos está ok, sem precisar do logcat.
    secao(c, getString(R.string.cl_sobre))
    val versao = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
    val cartao = cartao()
    c.addView(cartao, lp())
    cartao.addView(valor(getString(R.string.cfg_versao), "${versao ?: getString(R.string.cfg_desconhecida)} (${versaoInstalada()})"))
    cartao.addView(valor(getString(R.string.cfg_depuracao), if (Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA) getString(R.string.cfg_depuracao_on) else getString(R.string.cfg_depuracao_android)))
    // Fora da thread da tela (revisão 04/11): na 1ª vez isto GERA a chave RSA-2048 (segundos num ARMv7).
    val linhaChave = valor(getString(R.string.cfg_chave), getString(R.string.cfg_calculando))
    cartao.addView(linhaChave)
    trabalho.execute {
        val fp = try { ShellProprio.impressaoDigital(this) } catch (e: Exception) { getString(R.string.cfg_erro, e.javaClass.simpleName) }
        ui.post { if (!isDestroyed) (linhaChave.getChildAt(1) as? TextView)?.text = fp }
    }
    // Sobre a Sofaly (18/11/2026): logo, slogan e versão numa tela própria; e a logo ao ligar a TV (SplashRules).
    c.addView(linha(getString(R.string.brand_about), getString(R.string.brand_about_det), seta = true) {
        startActivity(Intent(this, AboutActivity::class.java))
    }, lp())
    lateinit var linhaAbertura: LinhaCfg
    linhaAbertura = linha(getString(R.string.brand_splash), getString(R.string.brand_splash_det), chave = SplashOverlay.enabled(this)) {
        val v = !SplashOverlay.enabled(this); SplashOverlay.setEnabled(this, v); linhaAbertura.ligado(v)
    }
    c.addView(linhaAbertura, lp())
    // Licenças (05/11/2026): o texto fica em assets/licencas.txt, o mesmo que o repositório mostra.
    c.addView(linha(getString(R.string.cfg_licencas), getString(R.string.cfg_licencas_det), seta = true) {
        startActivity(Intent(this, LicencasActivity::class.java))
    }, lp())
}

/** Ajuda (14/11/2026): um assunto do manual por linha; OK abre o texto inteiro (AjudaActivity), com ◀ ▶ entre eles. */
internal fun ConfigLauncherActivity.abaAjuda(c: LinearLayout) {
    Manual.TOPICOS.forEachIndexed { i, id ->
        c.addView(linha(AjudaActivity.texto(this, id, Manual.Parte.TITULO), AjudaActivity.texto(this, id, Manual.Parte.RESUMO), seta = true) {
            startActivity(Intent(this, AjudaActivity::class.java).putExtra(AjudaActivity.EXTRA_TOPICO, i))
        }, lp())
    }
}

/**
 * Avançado (14/11/2026): o que quase nunca se mexe e exige saber o que é (rede, ferramentas, confirmações do Claude,
 * apps de fábrica, permissões e o shell). Fica na última aba, fora do caminho do dia a dia.
 */
internal fun ConfigLauncherActivity.abaAvancado(c: LinearLayout) {
    abaRede(c)
    secao(c, getString(R.string.cfg2_sec_ferramentas))
    // Extras (10/11/2026): as chaves das ideias da pesquisa ficam numa tela própria (ExtrasActivity).
    c.addView(linha(getString(R.string.extras_titulo), getString(R.string.extras_cfg_det), seta = true) {
        startActivity(Intent(this, ExtrasActivity::class.java))
    }, lp())
    extrasDaAba(c, ExtrasAbas.Aba.SISTEMA, comSecao = false)

    confirmacoes(c)
    emprestimoShell(c)

    // Apps ocultos pelo Device Owner (Config.APPS_OCULTAR/PrivilegioDO.ocultar): libera a memória sem desinstalar.
    // Chave ligada = app visível.
    if (Config.APPS_OCULTAR.isNotEmpty()) {
        secao(c, getString(R.string.cfg2_sec_ocultos_sistema))
        Config.APPS_OCULTAR.forEach { pacote ->
            val rotulo = try {
                // MATCH_UNINSTALLED_PACKAGES: oculto pelo dono, o app some do getApplicationInfo comum e a linha mostrava
                // "com.google.android.apps.tv.dreamx" em vez do nome (Sala, 17/11/2026).
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacote,
                    android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString()
            } catch (e: Exception) { pacote }
            lateinit var linhaApp: LinhaCfg
            // O detalhe acompanha a chave: dizia "visível na TV" também com o app escondido (Sala, 17/11/2026).
            fun detalheApp(visivel: Boolean) = getString(if (visivel) R.string.cl_visivel else R.string.cl_escondido)
            val visivelAgora = !PrivilegioDO.estaOculto(this, pacote)
            linhaApp = linha(rotulo, detalheApp(visivelAgora), chave = visivelAgora) {
                // Fora da thread da tela: sem Device Owner o ocultar passa pelo shell e pode demorar (Congelar.kt).
                val app = applicationContext
                Fundo.rodar {
                    val ocultoAntes = PrivilegioDO.estaOculto(app, pacote)
                    val mudou = PrivilegioDO.ocultar(app, pacote, !ocultoAntes)
                    val ocultoAgora = PrivilegioDO.estaOculto(app, pacote)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (mudou) { linhaApp.ligado(!ocultoAgora); linhaApp.detalhe(detalheApp(!ocultoAgora)) } else linhaApp.detalhe(getString(R.string.cfg_nao_mudou_do))
                    }
                }
            }
            c.addView(linhaApp, lp())
        }
    }

    // Limpeza de apps de fábrica (Debloat.kt, 05/11/2026): só os apps já testados nesta TV, com desfazer.
    // Chave ligada = app visível (como na seção de cima).
    secao(c, getString(R.string.cl_limpeza))
    val limpaveis = Debloat.disponiveis(this)
    if (limpaveis.isEmpty()) c.addView(info(getString(R.string.cl_nenhum_liberado), getString(R.string.cl_nenhum_liberado_det)), lp())
    limpaveis.forEach { item ->
        lateinit var linhaApp: LinhaCfg
        linhaApp = linha(nomeDebloat(item), getString(R.string.cl_ocultar_perde, perdeDebloat(item)), chave = !PrivilegioDO.estaOculto(this, item.pacote)) {
            val app = applicationContext
            Fundo.rodar {   // fora da thread da tela (sem Device Owner vai pelo shell, ver Congelar.kt)
                val ocultar = !PrivilegioDO.estaOculto(app, item.pacote)
                val mudou = Debloat.definir(app, item.pacote, ocultar)
                val ocultoAgora = PrivilegioDO.estaOculto(app, item.pacote)
                // Elevação 2.11: a limpeza desfaz pela ação contrária (mostrar o que ocultou e vice-versa).
                if (mudou) Desfazer.registrarLimpeza(app, item.pacote, nomeDebloat(item), ocultar)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (mudou) linhaApp.ligado(!ocultoAgora)
                    else linhaApp.detalhe(getString(R.string.cl_nao_mudou))
                    // sem aviso de desfazer aqui: o foco fica na linha; a volta está no Diário e no próprio OK
                }
            }
        }
        c.addView(linhaApp, lp())
    }
    // Antes e depois (elevação 14.3): memória livre e tempo de abrir os 3 apps mais usados, medidos a pedido.
    if (limpaveis.isNotEmpty()) {
        val antes = LimpezaMedida.antes(this)
        val det = when {
            antes == null -> getString(R.string.elv_lp_det_antes)
            else -> LimpezaMedida.ultimaFrase(this) ?: getString(R.string.elv_lp_det_depois)
        }
        lateinit var linhaMedir: LinhaCfg
        linhaMedir = linha(getString(R.string.elv_lp_titulo), det, seta = true) {
            val app = applicationContext
            val depois = LimpezaMedida.antes(app) != null
            linhaMedir.detalhe(getString(R.string.elv_lp_medindo))
            Fundo.rodar("limpeza-medir") {
                val frase = if (depois) LimpezaMedida.medirDepois(app) else { LimpezaMedida.medirAntes(app); null }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    linhaMedir.detalhe(frase ?: getString(R.string.elv_lp_det_depois))
                }
            }
        }
        c.addView(linhaMedir, lp())
    }
    if (Debloat.ocultosPorAqui(this).isNotEmpty()) {
        lateinit var linhaDesfazer: LinhaCfg
        linhaDesfazer = linha(getString(R.string.cl_mostrar_todos), getString(R.string.cl_mostrar_todos_det), seta = true) {
            val app = applicationContext
            Fundo.rodar {
                val antes = Debloat.ocultosPorAqui(app)
                val n = Debloat.desfazerTudo(app)
                val voltaram = antes.filter { !PrivilegioDO.estaOculto(app, it) }
                Desfazer.registrar(app, DesfazerRegras.Categoria.LIMPEZA, getString(R.string.cl_mostrar_todos),
                    emptyList(), voltaram.map { DesfazerRegras.reversoDe(it, ocultou = false) })
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // sem aviso de desfazer aqui: o foco fica na linha; a volta está no Diário e no próprio OK
                    linhaDesfazer.detalhe(getString(R.string.cl_apps_de_volta, n))
                    ui.postDelayed({ if (!isDestroyed) recarregarAba() }, 1200)
                }
            }
        }
        c.addView(linhaDesfazer, lp())
    }

    // Permissões: o que o launcher usa para funcionar sem o computador por perto. OK num que falta tenta ligar sem PC.
    secao(c, getString(R.string.cfg_sec_ligado, Recursos.resumo(this)))
    for (r in Recursos.listar(this)) {
        val detalhe = if (r.ativo) getString(R.string.cfg_ligado) else r.sem + when (r.pedir) {
            null -> getString(R.string.cfg_so_pc)
            else -> getString(R.string.cfg_tenta_ligar)
        }
        c.addView(if (r.ativo || r.pedir == null) info((if (r.ativo) "✓ " else "✗ ") + r.nome, detalhe)
            else linha("✗ " + r.nome, detalhe, seta = true) { ligarRecurso(r) }, lp())
    }
    if (PrivilegioDO.somosDonos(this))
        c.addView(info(getString(R.string.cfg_perm_fixas), Config.PERMISSOES_FIXAS.joinToString { it.substringAfterLast('.') }), lp())

    // Shell próprio (cliente ADB Wi-Fi embutido). Até 12/11/2026 esta seção era o "Shizuku do launcher" (servidor do
    // Shizuku embutido para outros apps); o servidor saiu e ficou só o shell que o próprio launcher usa.
    secao(c, getString(R.string.cfg_sec_shizuku))
    c.addView(info(getString(R.string.cfg_shell_estado), getString(
        if (SaudeAcesso.shellEsperado(this)) R.string.cfg_shell_pareado else R.string.cfg_shell_nunca)), lp())
    // Parear a depuração sem fio sem PC (ver PareamentoNaTv).
    lateinit var linhaParear: LinhaCfg
    linhaParear = linha(getString(R.string.cfg_parear), if (PareamentoNaTv.podeMostrar(this))
        getString(R.string.cfg_parear_det) else getString(R.string.cfg_acess_off), seta = true) {
        if (!PareamentoNaTv.comecar(this)) linhaParear.detalhe(getString(R.string.cfg_acess_off))
    }
    c.addView(linhaParear, lp())

}

internal fun ConfigLauncherActivity.ligarRecurso(r: Recursos.Recurso) {
    val p = r.pedir
    if (p == Recursos.VIRAR_DONO) {
        virarDono()
    } else if (p == Recursos.PEDIR_PERMISSAO) {
        val faltam = Recursos.perigosasFaltando(this)
        if (faltam.isNotEmpty()) requestPermissions(faltam, PEDIDO_RECURSOS)
    } else if (p is Intent) {
        // Algumas TVs não têm a tela de uso/sobreposição: cai nas Configurações gerais em vez de travar.
        reconstruirAoVoltar = true
        try { startActivity(p) } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) { reconstruirAoVoltar = false }
        }
    }
}

/**
 * Virar Device Owner pela TV (VirarDono.kt). Primeiro descobre se há usuários extras (sairiam, com os dados deles) e
 * pede confirmação dizendo exatamente o que vai acontecer. Tudo fora da thread da tela.
 */
internal fun ConfigLauncherActivity.virarDono() = VirarDono.perguntar(this, ::aviso) { if (it.ok) recarregarAba() }

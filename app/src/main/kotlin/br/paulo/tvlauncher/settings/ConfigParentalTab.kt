package br.paulo.tvlauncher.settings

import android.content.Intent
import android.widget.LinearLayout
import br.paulo.tvlauncher.BloqueioCanaisActivity
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.Companion.PIN_VALE_MS
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.ConfirmacoesTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.family.ControleParental
import br.paulo.tvlauncher.family.PinFamilia
import br.paulo.tvlauncher.family.TentativasPin
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PinSeguro
import br.paulo.tvlauncher.panel.conferirPin
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.DonoEmprestado
import br.paulo.tvlauncher.privilege.RegrasDono
import br.paulo.tvlauncher.privilege.RegrasEmprestimo
import br.paulo.tvlauncher.privilege.ShizukuEmbutido

/** Aba Controle parental das Configurações. Saiu da ConfigLauncherActivity na refatoração de 21/11/2026. */

internal fun ConfigLauncherActivity.abaParental(c: LinearLayout) {
    if (!PainelArmazem.temPin(this)) {
        c.addView(cartao().apply {
            addView(valor(getString(R.string.cl_precisa_pin), getString(R.string.cl_precisa_pin_det)))
        }, lp())
        c.addView(linha(getString(R.string.cl_definir_pin), getString(R.string.cl_definir_pin_det, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS), seta = true) {
            definirNovoPin(null) { recarregarAba() }
        }, lp())
        extrasDaAba(c, ExtrasAbas.Aba.PARENTAL)
        return
    }
    val r = PlayerArmazem.parental(this)
    fun resumo(r: ControleParental.Regras) = when {
        r.vazio -> getString(R.string.cl_nenhum_canal)
        else -> getString(R.string.cl_resumo_bloqueio, r.canais.size, r.grupos.size)
    }
    lateinit var chave: LinhaCfg
    chave = linha(getString(R.string.cl_aba_parental), if (r.ligado) getString(R.string.cl_ligado_resumo, resumo(r)) else getString(R.string.cl_desligado_resumo, resumo(r)), chave = r.ligado) {
        pedirPinParental {
            val atual = PlayerArmazem.parental(this)
            val nova = atual.copy(ligado = !atual.ligado)
            if (PlayerArmazem.definirParental(this, nova)) {
                chave.ligado(nova.ligado)
                chave.detalhe(if (nova.ligado) getString(R.string.cl_ligado_resumo, resumo(nova)) else getString(R.string.cl_desligado_resumo, resumo(nova)))
            } else chave.detalhe(getString(R.string.cl_nao_gravei_tente))
        }
    }
    c.addView(chave, lp())
    c.addView(linha(getString(R.string.cl_escolher_bloqueados), getString(R.string.cl_escolher_bloqueados_det), seta = true) {
        pedirPinParental {
            reconstruirAoVoltar = true
            startActivity(Intent(this, BloqueioCanaisActivity::class.java))
        }
    }, lp())
    if (!r.vazio) {
        c.addView(linha(getString(R.string.cl_liberar_todos), getString(R.string.cl_liberar_todos_det), seta = true) {
            pedirPinParental {
                CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(getString(R.string.cl_liberar_todos_q))
                    .setMessage(getString(R.string.cl_liberar_todos_msg))
                    .setPositiveButton(getString(R.string.cl_liberar)) { _, _ ->
                        // copy: o modo infantil e as categorias infantis ficam como estão
                        PlayerArmazem.definirParental(this, PlayerArmazem.parental(this).copy(canais = emptySet(), grupos = emptySet()))
                        recarregarAba()
                    }
                    .setNegativeButton(getString(R.string.cl_cancelar), null)
                    .show()
            }
        }, lp())
    }
    // Modo infantil (10/11/2026): só as categorias marcadas como infantis aparecem na TV ao vivo, Filmes e Séries.
    // Ligar e desligar pedem o PIN; o resto, com o modo ligado, só aparece com o PIN no player (como os bloqueados).
    lateinit var chaveInf: LinhaCfg
    fun detalheInf(r: ControleParental.Regras) = getString(if (r.infantil) R.string.cfg_infantil_ligado else R.string.cfg_infantil_desligado, r.infantis.size)
    chaveInf = linha(getString(R.string.cfg_infantil), detalheInf(r), chave = r.infantil) {
        pedirPinParental {
            val atual = PlayerArmazem.parental(this)
            val nova = atual.copy(infantil = !atual.infantil)
            if (PlayerArmazem.definirParental(this, nova)) { chaveInf.ligado(nova.infantil); chaveInf.detalhe(detalheInf(nova)) }
            else chaveInf.detalhe(getString(R.string.cl_nao_gravei_tente))
        }
    }
    c.addView(chaveInf, lp())
    c.addView(linha(getString(R.string.cfg_infantil_escolher), getString(R.string.cfg_infantil_escolher_det), seta = true) {
        pedirPinParental {
            reconstruirAoVoltar = true
            startActivity(Intent(this, BloqueioCanaisActivity::class.java).putExtra(BloqueioCanaisActivity.EXTRA_INFANTIS, true))
        }
    }, lp())
    // Ideia 91: idade máxima no modo infantil (classificação indicativa do TMDB). Escolher pede o PIN.
    fun nomeIdade(i: Int) = when (i) { Classificacao.DESLIGADA -> getString(R.string.idade_so_categorias); 0 -> getString(R.string.idade_livre); else -> getString(R.string.idade_anos, i) }
    lateinit var linhaIdade: LinhaCfg
    linhaIdade = linha(getString(R.string.idade_titulo), getString(R.string.idade_det), valor = nomeIdade(r.idadeMax)) {
        pedirPinParental {
            val atual = PlayerArmazem.parental(this)
            Estilo.lista(this, getString(R.string.idade_titulo), Classificacao.OPCOES.map { nomeIdade(it) },
                listOf(Classificacao.OPCOES.indexOf(atual.idadeMax))) { i ->
                val nova = PlayerArmazem.parental(this).copy(idadeMax = Classificacao.OPCOES[i])
                if (PlayerArmazem.definirParental(this, nova)) linhaIdade.valor(nomeIdade(nova.idadeMax))
                else linhaIdade.detalhe(getString(R.string.cl_nao_gravei_tente))
            }
        }
    }
    c.addView(linhaIdade, lp())
    // PIN das crianças (ideia 34): destrava na frente da criança sem entregar o PIN que administra a TV.
    c.addView(linha(getString(R.string.fam_pin), getString(R.string.fam_pin_det),
        valor = getString(if (PainelArmazem.temPinFamilia(this)) R.string.cl_definido else R.string.cl_nao_definido)) {
        pedirPinParental(getString(R.string.cl_pin_painel)) { pinFamilia() }
    }, lp())
    extrasDaAba(c, ExtrasAbas.Aba.PARENTAL)
    secao(c, getString(R.string.cl_como_funciona))
    c.addView(cartao().apply {
        addView(valor(getString(R.string.cl_cf_bloqueado), getString(R.string.cl_cf_bloqueado_det)))
        addView(valor(getString(R.string.cl_cf_assistir), getString(R.string.cl_cf_assistir_det)))
        addView(valor(getString(R.string.cl_cf_tranca), getString(R.string.cl_cf_tranca_det)))
        addView(valor(getString(R.string.cl_cf_pin), getString(R.string.cl_cf_pin_det)))
    }, lp())
}

/** Definir, trocar ou apagar o PIN das crianças (ideia 34). Quem chama já conferiu o PIN do painel. */
internal fun ConfigLauncherActivity.pinFamilia() {
    val definir = {
        pedirPin(getString(R.string.cl_novo_pin, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)) { pin ->
            pedirPin(getString(R.string.cl_pin_de_novo)) { pin2 ->
                if (pin2 != pin) { aviso(getString(R.string.cl_pin_nao_bateu)); return@pedirPin }
                aviso(getString(R.string.cl_gravando))
                trabalho.execute {
                    // Igual ao do painel não vale (PinFamilia.conferirNovo). PBKDF2: fora da thread da tela.
                    val r = PinFamilia.conferirNovo(pin, PinSeguro.valido(pin) && PainelArmazem.conferirPin(this, pin))
                    if (r == PinFamilia.Novo.OK) PainelArmazem.definirPinFamilia(this, pin)
                    ui.post {
                        if (isDestroyed) return@post
                        aviso(when (r) {
                            PinFamilia.Novo.OK -> getString(R.string.fam_pin_ok)
                            PinFamilia.Novo.IGUAL_AO_PAINEL -> getString(R.string.fam_pin_igual)
                            PinFamilia.Novo.INVALIDO -> getString(R.string.cl_pin_invalido, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)
                        })
                        recarregarAba()
                    }
                }
            }
        }
    }
    if (!PainelArmazem.temPinFamilia(this)) { definir(); return }
    Estilo.lista(this, getString(R.string.fam_pin), listOf(getString(R.string.fam_trocar), getString(R.string.fam_apagar))) { i ->
        if (i == 0) definir()
        else trabalho.execute { PainelArmazem.apagarPinFamilia(this); ui.post { if (!isDestroyed) { aviso(getString(R.string.fam_apagado)); recarregarAba() } } }
    }
}

/** Ação que exige o PIN quando o controle parental está valendo (ex.: apagar ou cadastrar conta). */
internal fun ConfigLauncherActivity.exigirPinSeParental(acao: () -> Unit) {
    if (PlayerArmazem.parental(this).ativo) pedirPinParental(acao = acao) else acao()
}

/**
 * Pede o PIN do painel e roda [acao] se acertar. Acertou: vale 2 min nesta tela (não pede a cada OK). Do 5º erro
 * em diante, espera crescente (TentativasPin), guardada mesmo saindo da tela. A conferência (PBKDF2) roda fora da thread da tela.
 */
internal fun ConfigLauncherActivity.lerTentativas() = TentativasPin.Estado(prefsPin.getInt("erros", 0), prefsPin.getLong("ate", 0), prefsPin.getLong("ultimo", 0))

internal fun ConfigLauncherActivity.gravarTentativas(e: TentativasPin.Estado) =
    prefsPin.edit().putInt("erros", e.erros).putLong("ate", e.bloqueadoAte).putLong("ultimo", e.ultimoErro).apply()

internal fun ConfigLauncherActivity.pedirPinParental(titulo: String = getString(R.string.cl_pin_parental), acao: () -> Unit) {
    if (!PainelArmazem.temPin(this)) { acao(); return }
    if (System.currentTimeMillis() < pinValidoAte) { acao(); return }
    val espera = TentativasPin.espera(lerTentativas(), System.currentTimeMillis())
    if (espera > 0) { aviso(getString(R.string.player_pin_espere, TentativasPin.minutos(espera))); return }
    pedirPin(titulo) { pin ->
        trabalho.execute {
            val certo = PainelArmazem.conferirPin(this, pin)
            ui.post {
                if (isDestroyed) return@post
                if (certo) { gravarTentativas(TentativasPin.acertou()); pinValidoAte = System.currentTimeMillis() + PIN_VALE_MS; acao() }
                else {
                    val e = TentativasPin.errou(lerTentativas(), System.currentTimeMillis())
                    gravarTentativas(e)
                    val falta = TentativasPin.espera(e, System.currentTimeMillis())
                    aviso(if (falta > 0) getString(R.string.player_pin_espere, TentativasPin.minutos(falta)) else getString(R.string.player_pin_errado))
                }
            }
        }
    }
}

/**
 * Confirmações (Confirmacoes.kt / ConfirmacoesTv.kt, 09/11/2026): o que pergunta na TV antes de agir. "Liberar tudo" é
 * para quem usa de longe; quem não sabe que isto existe fica com os padrões protegidos. Liberar mais pede o PIN;
 * ficar mais seguro, não.
 */
internal fun ConfigLauncherActivity.confirmacoes(c: LinearLayout) {
    secao(c, getString(R.string.cl_sec_confirmacoes))
    val r = ConfirmacoesTv.regras(this)
    fun mudar(novas: Confirmacoes.Regras) {
        val gravar = { ConfirmacoesTv.definir(this, novas); recarregarAba() }
        if (Confirmacoes.pedePin(ConfirmacoesTv.regras(this), novas)) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
    }
    c.addView(linha(getString(R.string.cl_liberar_tudo),
        getString(R.string.cl_liberar_tudo_det),
        chave = r.liberarTudo) { mudar(r.copy(liberarTudo = !r.liberarTudo)) }, lp())
    for (a in Confirmacoes.Acao.entries) {
        val atual = Confirmacoes.modo(r, a)
        c.addView(linha(tituloAcao(a), explicaAcao(a), valor = if (r.liberarTudo) getString(R.string.cl_liberado) else rotuloModo(atual)) {
            if (r.liberarTudo) aviso(getString(R.string.cl_desligue_liberar))
            else mudar(r.copy(modos = r.modos + (a to Confirmacoes.proximo(a, atual))))
        }, lp())
    }
    c.addView(info(getString(R.string.cl_perguntar_tv), getString(R.string.cl_perguntar_tv_det)), lp())
}

/**
 * Emprestar o shell a outros apps (15/11/2026, "poder máximo"; ver ShizukuEmbutido). Desligado de fábrica. Ligar pede
 * o PIN (liberar mais); desligar não. Cada app ainda pede na TV, com PIN, na primeira vez.
 */
internal fun ConfigLauncherActivity.emprestimoShell(c: LinearLayout) {
    secao(c, getString(R.string.emp_secao))
    val modo = ShizukuEmbutido.modo(this)
    val rotulo = when (modo) {
        RegrasEmprestimo.Modo.DESLIGADO -> getString(R.string.emp_modo_desligado)
        RegrasEmprestimo.Modo.SOB_DEMANDA -> getString(R.string.emp_modo_sob_demanda)
        RegrasEmprestimo.Modo.SEMPRE -> getString(R.string.emp_modo_sempre)
    }
    c.addView(linha(getString(R.string.emp_titulo), getString(R.string.emp_titulo_det), valor = rotulo) {
        val proximo = RegrasEmprestimo.Modo.values()[(modo.ordinal + 1) % RegrasEmprestimo.Modo.values().size]
        val gravar = { ShizukuEmbutido.definirModo(this, proximo); recarregarAba() }
        if (modo == RegrasEmprestimo.Modo.DESLIGADO) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
    }, lp())
    when (ShizukuEmbutido.parado(this)) {
        RegrasEmprestimo.Parado.SHIZUKU_OFICIAL -> c.addView(info(getString(R.string.emp_titulo), getString(R.string.emp_oficial)), lp())
        RegrasEmprestimo.Parado.PLANO -> c.addView(info(getString(R.string.emp_titulo), getString(R.string.emp_plano)), lp())
        else -> {}
    }
    // Device Owner emprestado (Dhizuku, DonoEmprestado.kt). Ligar pede o PIN; desligar vale na hora para todos os apps.
    val donoLigado = DonoEmprestado.ligado(this)
    c.addView(linha(getString(R.string.emd_titulo), getString(R.string.emd_titulo_det),
        valor = getString(if (donoLigado) R.string.emd_ligado else R.string.emp_modo_desligado)) {
        val gravar = { DonoEmprestado.definirLigado(this, !donoLigado); recarregarAba() }
        if (!donoLigado) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
    }, lp())
    if (donoLigado) when (DonoEmprestado.parado(this)) {
        RegrasDono.Parado.SEM_DONO -> c.addView(info(getString(R.string.emd_titulo), getString(R.string.emd_sem_dono)), lp())
        RegrasDono.Parado.PLANO -> c.addView(info(getString(R.string.emd_titulo), getString(R.string.emp_plano)), lp())
        else -> {}
    }
}

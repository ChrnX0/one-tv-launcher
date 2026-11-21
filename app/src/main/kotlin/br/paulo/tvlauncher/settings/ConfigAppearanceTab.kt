package br.paulo.tvlauncher.settings

import android.content.Intent
import android.widget.LinearLayout
import br.paulo.tvlauncher.AppsEscolhaActivity
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.PainelBlocosActivity
import br.paulo.tvlauncher.PluginsActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.VindoDoProjectivyActivity
import br.paulo.tvlauncher.home.CruzOnda3
import br.paulo.tvlauncher.home.FundoMeuDados
import br.paulo.tvlauncher.home.FundoMeuRegras
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.LayoutHome
import br.paulo.tvlauncher.home.Organizacao
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.home.PacoteIconesRegras
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.player.rotuloTamanho
import br.paulo.tvlauncher.weather.AvisoChuva
import br.paulo.tvlauncher.weather.AvisoTemperatura
import br.paulo.tvlauncher.weather.LocalClima

/** Aba Aparência das Configurações. Saiu da ConfigLauncherActivity na refatoração de 21/11/2026. */

internal fun ConfigLauncherActivity.abaAparencia(c: LinearLayout) {
    if (VindoDoProjectivyActivity.instalado(this)) c.addView(linha(getString(R.string.vp_titulo), getString(R.string.vp_cfg_det), seta = true) {
        reconstruirAoVoltar = true
        startActivity(Intent(this, VindoDoProjectivyActivity::class.java))
    }, lp())
    // Plugins (degrau 3, PluginsTv): cartões de outros apps na linha Assistir. Ligar um plugin pede o PIN parental.
    c.addView(linha(getString(R.string.plg_titulo), getString(R.string.wp_cfg_det), seta = true) {
        // Título padrão ("PIN do controle parental"): só "Plugins" sobre um campo vazio não dizia o que digitar (Sala, 17/11).
        pedirPinParental {
            reconstruirAoVoltar = true
            startActivity(Intent(this, PluginsActivity::class.java))
        }
    }, lp())
    lateinit var linhaTema: LinhaCfg
    linhaTema = linha(getString(R.string.cl_tema), getString(R.string.cfg_tema_det), valor = Tema.atual(this).nome) {
        Tema.proximo(this)
        linhaTema.valor(Tema.atual(this).nome)
        // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
    }
    c.addView(linhaTema, lp())
    // "Meu fundo" (degrau 1, 15/11/2026; FundoMeu.kt): escolher o arquivo já põe o tema; brilho e contraste em degraus.
    fun rotuloMeu() = FundoMeuDados.arquivo(this)?.name ?: getString(R.string.pi_nenhum)
    lateinit var linhaMeu: LinhaCfg
    linhaMeu = linha(getString(R.string.fm_titulo), getString(R.string.fm_det), valor = rotuloMeu()) {
        trabalho.execute {
            val arquivos = FundoMeuDados.candidatos()
            ui.post {
                if (isDestroyed) return@post
                if (arquivos.isEmpty()) { aviso(getString(R.string.fm_vazio)); return@post }
                val atual = FundoMeuDados.arquivo(this)
                // Mesmo nome em pastas diferentes (ex.: Download/v1.png e Pictures/v1.png): mostra a pasta junto,
                // senão a lista tem duas linhas iguais e não dá para saber qual é qual (visto na Sala, 17/11/2026).
                val repetidos = arquivos.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
                val nomes = arquivos.map { f -> if (f.name in repetidos) "${f.parentFile?.name ?: "?"}/${f.name}" else f.name }
                Estilo.lista(this, getString(R.string.fm_titulo), listOf(getString(R.string.fm_nenhum)) + nomes,
                    marcadas = listOf(arquivos.indexOfFirst { it == atual } + 1)) { i ->
                    FundoMeuDados.definirArquivo(this, arquivos.getOrNull(i - 1))
                    Tema.definir(this, if (i == 0) Tema.ESCURO else Tema.MEU)
                    recarregarAba()   // mostra (ou esconde) brilho e contraste
                }
            }
        }
    }
    c.addView(linhaMeu, lp())
    fun pct(v: Int) = if (v > 0) "+$v%" else "$v%"
    // Brilho e contraste só aparecem com um arquivo escolhido (sem "Meu fundo" não valem nada: conciso).
    if (FundoMeuDados.arquivo(this) != null) {
    lateinit var linhaBrilho: LinhaCfg
    linhaBrilho = linha(getString(R.string.fm_brilho), getString(R.string.fm_ajuste_det), valor = pct(FundoMeuDados.brilho(this))) {
        FundoMeuDados.definirBrilho(this, FundoMeuRegras.proximoDegrau(FundoMeuDados.brilho(this)))
        linhaBrilho.valor(pct(FundoMeuDados.brilho(this)))
    }
    c.addView(linhaBrilho, lp())
    lateinit var linhaContraste: LinhaCfg
    linhaContraste = linha(getString(R.string.fm_contraste), getString(R.string.fm_ajuste_det), valor = pct(FundoMeuDados.contraste(this))) {
        FundoMeuDados.definirContraste(this, FundoMeuRegras.proximoDegrau(FundoMeuDados.contraste(this)))
        linhaContraste.valor(pct(FundoMeuDados.contraste(this)))
    }
    c.addView(linhaContraste, lp())
    }
    // Tamanho do texto (ideia 47, TamanhoTexto): vale em todas as telas do launcher; esta tela se refaz para mostrar.
    fun rotuloTamanho(v: Int) = getString(when (v) { 110 -> R.string.tt_grande; 120 -> R.string.tt_maior; else -> R.string.tt_normal })
    c.addView(linha(getString(R.string.tt_titulo), getString(R.string.tt_det), valor = rotuloTamanho(TamanhoTexto.atual(this))) {
        TamanhoTexto.definir(this, TamanhoTexto.proximo(TamanhoTexto.atual(this)))
        recreate()
    }, lp())
    // Alto contraste (elevação 16.3): texto e bordas a 7:1 ou mais; esta tela se refaz para mostrar.
    c.addView(linha(getString(R.string.elv_ac_titulo), getString(R.string.elv_ac_det), chave = AltoContraste.ligado(this)) {
        AltoContraste.definir(this, !AltoContraste.ligado(this))
        reconstruirAoVoltar = true
        recreate()
    }, lp())
    // Tela inicial em cruz (17/11/2026), a única: ícones, apps, barra, arte, sons e a onda 3. A HOME se refaz ao voltar.
    fun rotuloIcones() = getString(when (LayoutHome.iconesCruz(this)) {
        IconeCruz.Estilo.OBJETO -> R.string.cruz_objeto
        IconeCruz.Estilo.SIMBOLO -> R.string.cruz_simbolo
        IconeCruz.Estilo.DUOTOM -> R.string.cruz_duotom
        IconeCruz.Estilo.LINHA -> R.string.cruz_linha
    })
    lateinit var linhaIcones: LinhaCfg
    linhaIcones = linha(getString(R.string.cruz_icones), getString(R.string.cruz_icones_det), valor = rotuloIcones()) {
        LayoutHome.proximoIconesCruz(this)
        linhaIcones.valor(rotuloIcones())
    }
    c.addView(linhaIcones, lp())
    // Como os apps aparecem na cruz (18/11/2026): banner de TV, redondo ou solto.
    fun rotuloApps() = getString(when (LayoutHome.iconeAppCruz(this)) {
        LayoutHome.IconeAppCruz.BANNER -> R.string.cruz_app_banner
        LayoutHome.IconeAppCruz.REDONDO -> R.string.cruz_app_redondo
        LayoutHome.IconeAppCruz.SOLTO -> R.string.cruz_app_solto
    })
    lateinit var linhaApps: LinhaCfg
    linhaApps = linha(getString(R.string.cruz_apps_icone), getString(R.string.cruz_apps_icone_det), valor = rotuloApps()) {
        LayoutHome.proximoIconeAppCruz(this)
        linhaApps.valor(rotuloApps())
    }
    c.addView(linhaApps, lp())
    // Barra de cima (Ilha, Recua, Leve, Relógio grande, Vidro ou a de sempre) e a arte do item em foco atrás da cruz.
    fun rotuloBarra() = getString(when (LayoutHome.barraCruz(this)) {
        LayoutHome.BarraCruz.RECUA -> R.string.cruz_barra_recua
        LayoutHome.BarraCruz.LEVE -> R.string.cruz_barra_leve
        LayoutHome.BarraCruz.RELOGIO -> R.string.cruz_barra_relogio
        LayoutHome.BarraCruz.HOJE -> R.string.cruz_barra_capsulas
        LayoutHome.BarraCruz.VIDRO -> R.string.cruz_barra_vidro
        LayoutHome.BarraCruz.ILHA -> R.string.cruz_barra_ilha
    })
    lateinit var linhaBarra: LinhaCfg
    linhaBarra = linha(getString(R.string.cruz_barra), getString(R.string.cruz_barra_det), valor = rotuloBarra()) {
        LayoutHome.proximaBarraCruz(this)
        linhaBarra.valor(rotuloBarra())
    }
    c.addView(linhaBarra, lp())
    fun rotuloArte() = getString(if (LayoutHome.arteCruz(this)) R.string.cruz_ligada else R.string.cruz_desligada)
    lateinit var linhaArte: LinhaCfg
    linhaArte = linha(getString(R.string.cruz_arte), getString(R.string.cruz_arte_det), valor = rotuloArte()) {
        LayoutHome.definirArteCruz(this, !LayoutHome.arteCruz(this))
        linhaArte.valor(rotuloArte())
    }
    c.addView(linhaArte, lp())
    // Sons ao andar na cruz (SonsCruz, 18/11/2026): ligados de fábrica, baixos, mudos com outro áudio tocando.
    fun rotuloSons() = getString(if (SonsCruz.ligado(this)) R.string.al_ligado else R.string.al_desligado)   // "Sons": masculino
    lateinit var linhaSons: LinhaCfg
    linhaSons = linha(getString(R.string.cruz_sons), getString(R.string.cruz_sons_det), valor = rotuloSons()) {
        SonsCruz.definir(this, !SonsCruz.ligado(this))
        if (SonsCruz.ligado(this)) SonsCruz.tocar(this, SonsCruz.Som.GRUPO)   // amostra do som ao ligar
        linhaSons.valor(rotuloSons())
    }
    c.addView(linhaSons, lp())
    // Onda 3 do estudo da HOME (18/11/2026): cada parte com a sua chave, todas ligadas de fábrica (CruzOnda3).
    fun linhaOnda3(titulo: Int, det: Int, chave: String) {
        fun rotulo() = getString(if (CruzOnda3.ligado(this, chave)) R.string.al_ligado else R.string.al_desligado)
        lateinit var l: LinhaCfg
        l = linha(getString(titulo), getString(det), valor = rotulo()) { CruzOnda3.alternar(this, chave); l.valor(rotulo()) }
        c.addView(l, lp())
    }
    linhaOnda3(R.string.cruz_ambiente, R.string.cruz_ambiente_det, CruzOnda3.CHAVE_AMBIENTE)
    linhaOnda3(R.string.cruz_paralaxe, R.string.cruz_paralaxe_det, CruzOnda3.CHAVE_PARALAXE)
    linhaOnda3(R.string.cruz_quem, R.string.cruz_quem_det, CruzOnda3.CHAVE_QUEM)
    linhaOnda3(R.string.cruz_dormir_atalho, R.string.cruz_dormir_atalho_det, CruzOnda3.CHAVE_DORMIR)
    // Pacote de ícones (degrau 1, 15/11/2026): desligado de fábrica; cada OK passa ao próximo instalado e volta a "Nenhum".
    val pacotes = PacoteIcones.instalados(this)
    fun rotuloPacote() = PacoteIcones.escolhido(this).let { if (it.isEmpty()) getString(R.string.pi_nenhum) else PacoteIcones.nome(this, it) }
    lateinit var linhaPacote: LinhaCfg
    linhaPacote = linha(getString(R.string.pi_titulo), getString(if (pacotes.isEmpty()) R.string.pi_det_vazio else R.string.pi_det),
        valor = rotuloPacote()) {
        PacoteIcones.escolher(this, PacoteIconesRegras.proximo(PacoteIcones.escolhido(this), pacotes.map { it.first }))
        linhaPacote.valor(rotuloPacote())   // a HOME redesenha sozinha ao voltar: o pacote entra na chave do cache
    }
    c.addView(linhaPacote, lp())
    // Visuais prontos (revisão dos degraus, 1b, 17/11/2026): cada OK aplica o próximo da lista (VisualCodigo.PRONTOS).
    fun rotuloPronto() = VisualCodigo.prontoAtual(VisualCodigoTv.atual(this))?.let { nomePronto(it.id) } ?: getString(R.string.vpr_meu)
    c.addView(linha(getString(R.string.vpr_titulo), getString(R.string.vpr_det), valor = rotuloPronto()) {
        val p = VisualCodigo.proximoPronto(VisualCodigoTv.atual(this))
        VisualCodigoTv.aplicar(this, VisualCodigo.codificar(p.visual))
        recreate()   // tema e tamanho do texto mudam a própria tela de Configurações
    }, lp())
    // Tema por QR (degrau 1, 15/11/2026): mostra o código do visual desta TV num QR; o celular abre a página pública.
    c.addView(linha(getString(R.string.vq_titulo), getString(R.string.vq_det), seta = true) { mostrarQrVisual() }, lp())
    // Apps ocultos (14/11/2026): o card "Ocultos (N)" saiu da HOME; mostrar de volta é aqui.
    val nOcultos = Organizacao.ocultos(this).size
    c.addView(linha(getString(R.string.oc_titulo), if (nOcultos == 0) getString(R.string.oc_nenhum) else
        getString(R.string.oc_quantos, nOcultos), seta = true) {
        reconstruirAoVoltar = true
        startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_OCULTOS))
    }, lp())
    // Ideia 42: quais blocos o painel rápido (HOME segurado) mostra, e em que ordem.
    c.addView(linha(getString(R.string.pb_titulo), getString(R.string.pb_cfg_det), seta = true) {
        startActivity(Intent(this, PainelBlocosActivity::class.java))
    }, lp())

    secao(c, getString(R.string.cl_clima))
    lateinit var linhaEndereco: LinhaCfg
    fun detalheEndereco() = LocalClima.ler(this)?.let { LocalClima.descrever(it) }
        ?: getString(R.string.cl_end_nenhum)
    linhaEndereco = linha(getString(R.string.cl_end_clima), detalheEndereco(), seta = true) {
        pedirEndereco { cep, numero ->
            linhaEndereco.detalhe(getString(R.string.cl_procurando_end))
            trabalho.execute {
                val r = LocalClima.definirPorCep(this, cep, numero)
                ui.post { if (!isDestroyed) linhaEndereco.detalhe(r.fold({ detalheEndereco() }, { getString(R.string.cl_nao_deu, it.message.toString()) })) }
            }
        }
    }
    c.addView(linhaEndereco, lp())
    // Aviso de chuva por cima de qualquer app (AvisoChuva.kt). O recado vem pelo painel e não tem chave aqui.
    lateinit var linhaChuva: LinhaCfg
    linhaChuva = linha(getString(R.string.cl_aviso_chuva),
        getString(R.string.cl_aviso_chuva_det, AvisoChuva.LIMIAR, AvisoChuva.HORAS_A_FRENTE),
        chave = AvisoChuva.ligado(this)) {
        AvisoChuva.definirLigado(this, !AvisoChuva.ligado(this))
        linhaChuva.ligado(AvisoChuva.ligado(this))
    }
    c.addView(linhaChuva, lp())
    // Ideia 94: calor ou frio forte ("Beba água"), no mesmo cartão do aviso de chuva.
    lateinit var linhaTemp: LinhaCfg
    linhaTemp = linha(getString(R.string.tp_cfg), getString(R.string.tp_cfg_det, AvisoTemperatura.CALOR.toInt(), AvisoTemperatura.FRIO.toInt()),
        chave = AvisoTemperatura.ligado(this)) {
        AvisoTemperatura.definirLigado(this, !AvisoTemperatura.ligado(this))
        linhaTemp.ligado(AvisoTemperatura.ligado(this))
    }
    c.addView(linhaTemp, lp())
    extrasDaAba(c, ExtrasAbas.Aba.APARENCIA)
}

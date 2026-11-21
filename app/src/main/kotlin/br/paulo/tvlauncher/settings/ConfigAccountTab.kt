package br.paulo.tvlauncher.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.AddonsActivity
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.FotoPerfilActivity
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.ServicosFilmesActivity
import br.paulo.tvlauncher.VodActivity
import br.paulo.tvlauncher.input.EfeitosSom
import br.paulo.tvlauncher.player.AddonInstallDialog
import br.paulo.tvlauncher.player.AddonsTv
import br.paulo.tvlauncher.player.Conforto
import br.paulo.tvlauncher.player.ConfortoRegras
import br.paulo.tvlauncher.player.ContaIptv
import br.paulo.tvlauncher.player.FonteTv
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.LegendaBaixar
import br.paulo.tvlauncher.player.PlayerAcessivel
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.TaxaQuadros
import br.paulo.tvlauncher.player.Xtream

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Aba Conta das Configurações. Saiu da ConfigLauncherActivity na refatoração de 21/11/2026. */

internal fun ConfigLauncherActivity.abaConta(c: LinearLayout) {
    // Versão da Play enxuta (PlayPolicy.Power.LIVE_TV_LISTS): sem contas de TV ao vivo, só o resto da aba.
    if (PlayPolicy.allowed(PlayPolicy.Power.LIVE_TV_LISTS, BuildConfig.PLAY)) contasTv(c)
    abaContaResto(c)
}

internal fun ConfigLauncherActivity.contasTv(c: LinearLayout) {
    // As contas são decifradas pelo Keystore (IPC): leitura fora da thread da tela (auditoria 10/11). A seção
    // "Gerenciar contas" já entra embaixo; as contas aparecem no lugar reservado quando a leitura terminar.
    val topo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    c.addView(topo, LinearLayout.LayoutParams(MATCH, WRAP))
    leitura.execute {
        val fontes = PlayerArmazem.fontes(this)
        ui.post { if (!isDestroyed) contasNoTopo(topo, fontes) }
    }
    // Gerenciar: cadastrar, apagar, testar (o que antes era a seção "TV ao vivo").
    secao(c, getString(R.string.cl_gerenciar_contas))
    val caixaContas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    c.addView(caixaContas, LinearLayout.LayoutParams(MATCH, WRAP))
    montarContasTv(caixaContas)
}

internal fun ConfigLauncherActivity.abaContaResto(c: LinearLayout) {
    // Áudio de filmes e séries (Idioma, 11/11/2026). OK alterna. Cada filme ou série lembra a troca feita no player.
    secao(c, getString(R.string.idioma_secao))
    var atual = Idioma.Pref.DUBLADO
    val linhaAudio = linha(getString(R.string.idioma_titulo), getString(R.string.idioma_det), valor = "…") {}
    linhaAudio.setOnClickListener {
        atual = if (atual == Idioma.Pref.ORIGINAL) Idioma.Pref.DUBLADO else Idioma.Pref.ORIGINAL
        linhaAudio.valor(IdiomaPlayer.rotulo(this, atual))
        val nova = atual
        leitura.execute { IdiomaPlayer.definirPadrao(this, nova) }
    }
    c.addView(linhaAudio)
    // Elevação 16.2: audiodescrição e legenda para surdos, escolhidas uma vez (valem em todo filme e série).
    var ad = false; var sdh = false
    lateinit var linhaAd: LinhaCfg; lateinit var linhaSdh: LinhaCfg
    linhaAd = linha(getString(R.string.elv_ad_titulo), getString(R.string.elv_ad_det), chave = false) {
        ad = !ad; linhaAd.ligado(ad); val v = ad; leitura.execute { IdiomaPlayer.definirAudiodescricao(this, v) }
    }
    linhaSdh = linha(getString(R.string.elv_sdh_titulo), getString(R.string.elv_sdh_det), chave = false) {
        sdh = !sdh; linhaSdh.ligado(sdh); val v = sdh; leitura.execute { IdiomaPlayer.definirSurdos(this, v) }
    }
    c.addView(linhaAd, lp()); c.addView(linhaSdh, lp())
    // Serviços de filmes e séries (14/11/2026): Trakt, Simkl, MDBList, TMDB e OpenSubtitles numa tela só. A chave do
    // OpenSubtitles agora é a do app; a linha antiga (chave do dono) só aparece para quem já tinha cadastrado uma,
    // enquanto o build não tiver a chave do app.
    c.addView(linha(getString(R.string.sv_linha), getString(R.string.sv_linha_det), seta = true) {
        startActivity(Intent(this, ServicosFilmesActivity::class.java))
    }, lp())
    lateinit var linhaLeg: LinhaCfg
    linhaLeg = linha(getString(R.string.legenda_conta_titulo), getString(R.string.legenda_conta_det), valor = "…") {
        pedirContaLegenda { tem -> linhaLeg.valor(getString(if (tem) R.string.legenda_conta_sim else R.string.legenda_conta_nao)) }
    }
    if (!ServicosContas.disponivel(ServicosFilmes.Servico.OPENSUBTITLES)) c.addView(linhaLeg)
    // Som e legenda para quem enxerga ou ouve pior (14/11/2026). As mesmas chaves do menu ▼ do player.
    secao(c, getString(R.string.acess_config_titulo))
    val chavesAcess = listOf(PlayerAcessivel.LEGENDA_FUNDO to R.string.acess_legenda_fundo, PlayerAcessivel.LEGENDA_AMARELA to R.string.acess_legenda_amarela) +
        (if (EfeitosSom.disponivel()) listOf(PlayerAcessivel.VOLUME_IGUAL to R.string.acess_volume, PlayerAcessivel.VOZ_CLARA to R.string.acess_voz) else emptyList())
    chavesAcess.forEachIndexed { i, (k, rot) ->
        lateinit var l: LinhaCfg
        l = linha(getString(rot), if (i == 0) getString(R.string.acess_config_texto) else "", chave = EfeitosSom.ligado(this, k)) {
            l.ligado(EfeitosSom.alternar(this, k))
        }
        c.addView(l)
    }
    // Ideia 86: filme na taxa de quadros dele (24 Hz), a mesma chave do menu ▼ do player e do painel.
    lateinit var linhaQuadros: LinhaCfg
    linhaQuadros = linha(getString(R.string.quadros_opcao), getString(R.string.quadros_det), chave = TaxaQuadros.ligada(this)) {
        linhaQuadros.ligado(TaxaQuadros.alternar(this))
    }
    c.addView(linhaQuadros)
    // Confortos do estudo do Stremio (16/11/2026; Conforto.kt): esconder spoiler, segundo idioma e a contagem do próximo.
    lateinit var linhaSpoiler: LinhaCfg
    linhaSpoiler = linha(getString(R.string.cf_spoiler), getString(R.string.cf_spoiler_det), chave = Conforto.semSpoiler(this)) {
        linhaSpoiler.ligado(Conforto.alternarSpoiler(this))
    }
    c.addView(linhaSpoiler)
    fun rotuloSegundo(cod: String?) = cod?.let { Conforto.nomeIdioma(it) } ?: getString(R.string.cf_nenhum)
    lateinit var linhaSegundo: LinhaCfg
    linhaSegundo = linha(getString(R.string.cf_segundo), getString(R.string.cf_segundo_det), valor = rotuloSegundo(Conforto.segundoIdioma(this))) {
        val opcoes = listOf<String?>(null) + ConfortoRegras.SEGUNDOS
        val atualS = Conforto.segundoIdioma(this)
        Estilo.lista(this, getString(R.string.cf_segundo), opcoes.map { rotuloSegundo(it) }, listOf(opcoes.indexOf(atualS).coerceAtLeast(0))) { i ->
            Conforto.definirSegundo(this, opcoes[i]); linhaSegundo.valor(rotuloSegundo(opcoes[i]))
        }
    }
    c.addView(linhaSegundo)
    fun rotuloContagem(s: Int) = if (s <= 0) getString(R.string.cf_proximo_nao) else getString(R.string.cf_segundos, s)
    lateinit var linhaContagem: LinhaCfg
    linhaContagem = linha(getString(R.string.cf_proximo), getString(R.string.cf_proximo_det), valor = rotuloContagem(Conforto.contagem(this))) {
        val v = ConfortoRegras.CONTAGENS
        Estilo.lista(this, getString(R.string.cf_proximo), v.map { rotuloContagem(it) }, listOf(v.indexOf(Conforto.contagem(this)).coerceAtLeast(0))) { i ->
            Conforto.definirContagem(this, v[i]); linhaContagem.valor(rotuloContagem(v[i]))
        }
    }
    c.addView(linhaContagem)
    // Addons (16/11/2026; 18/11 ganhou "Adicionar" aqui, com QR para o painel ou digitando: AddonInstallDialog).
    fun rotuloAddons() = AddonsTv.instalados(this).size.let { if (it == 0) getString(R.string.cf_nenhum) else it.toString() }
    lateinit var linhaAddons: LinhaCfg
    linhaAddons = linha(getString(R.string.ad_titulo), getString(R.string.ad_cfg_det), valor = rotuloAddons()) {
        val todos = AddonsTv.instalados(this)
        val atualizar = { linhaAddons.valor(rotuloAddons()) }
        // Na volta a aba se refaz (reconstruirAoVoltar), para a contagem seguir o que foi instalado lá.
        // Sem nenhum: a tela de Addons, que mostra os Recomendados por tipo e o "Adicionar" (visto na Sala, 19/11/2026:
        // daqui só abria o QR e os Recomendados ficavam escondidos de quem ainda não tem addon).
        if (todos.isEmpty()) { reconstruirAoVoltar = true; startActivity(Intent(this, AddonsActivity::class.java)); return@linha }
        // 1º item = adicionar, 2º = Recomendados; os outros = addons instalados (escolher um pergunta se remove).
        Estilo.lista(this, getString(R.string.ad_titulo),
            listOf(getString(R.string.ad_add_item), getString(R.string.ad_sug_secao)) + todos.map { it.m.nome }, emptyList()) { i ->
            if (i == 0) { AddonInstallDialog.mostrar(this, atualizar); return@lista }
            if (i == 1) { reconstruirAoVoltar = true; startActivity(Intent(this, AddonsActivity::class.java)); return@lista }
            val a = todos[i - 2]
            CaixaTv(this).setTitle(a.m.nome).setMessage(getString(R.string.ad_cfg_remover, a.m.nome))
                .setPositiveButton(getString(R.string.ad_cfg_remover_sim)) { _, _ -> AddonsTv.remover(this, a.base); atualizar() }
                .setNegativeButton(getString(R.string.sv_cancelar), null).show()
        }
    }
    c.addView(linhaAddons)
    // Foto da conta Google na bolinha do perfil (FotoPerfil, 13/11/2026): abre a tela do código para o celular.
    secao(c, getString(R.string.fp_secao))
    c.addView(linha(getString(R.string.fp_linha), getString(R.string.fp_linha_det), seta = true) {
        startActivity(Intent(this, FotoPerfilActivity::class.java))
    }, lp())
    leitura.execute {
        val todas = IdiomaPlayer.prefs(this)
        val p = todas.padrao
        val tem = LegendaBaixar.temConta(this)
        ui.post {
            if (isDestroyed) return@post
            atual = p; linhaAudio.valor(IdiomaPlayer.rotulo(this, p))
            ad = todas.audiodescricao; sdh = todas.surdos; linhaAd.ligado(ad); linhaSdh.ligado(sdh)
            linhaLeg.valor(getString(if (tem) R.string.legenda_conta_sim else R.string.legenda_conta_nao))
        }
    }
}

/**
 * Chave de API (obrigatória) e conta (opcional: com ela o limite diário de downloads sobe) do OpenSubtitles. A chave
 * não volta preenchida: fica cifrada e só o player a lê. Gravar com a chave vazia apaga.
 */
internal fun ConfigLauncherActivity.pedirContaLegenda(erro: String? = null, aoGravar: (Boolean) -> Unit) {
    val T = android.text.InputType.TYPE_CLASS_TEXT
    fun campo(dica: String, tipo: Int) = android.widget.EditText(this).apply {
        inputType = tipo; hint = dica; setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(100))
    }
    val chave = campo(getString(R.string.cl_os_chave), T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    val usuario = campo(getString(R.string.cl_os_usuario), T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    val senha = campo(getString(R.string.cl_os_senha), T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPaddingRelative(48, 16, 48, 0)
        if (erro != null) addView(Estilo.texto(this@pedirContaLegenda, Estilo.Papel.LEGENDA, erro).apply { setTextColor(coral) })
        addView(chave); addView(usuario); addView(senha)
    }
    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(getString(R.string.legenda_conta_titulo))
        .setView(caixa)
        .setPositiveButton(getString(R.string.cl_gravar)) { _, _ ->
            val conta = LegendaBaixar.Conta(chave.text.toString(), usuario.text.toString(), senha.text.toString())
            leitura.execute {
                val r = LegendaBaixar.definirConta(this, conta)
                val tem = LegendaBaixar.temConta(this)
                ui.post {
                    if (isDestroyed) return@post
                    r.onSuccess { aoGravar(tem) }.onFailure { pedirContaLegenda(getString(R.string.cl_nao_gravei, it.message.toString()), aoGravar) }
                }
            }
        }
        .setNeutralButton(getString(R.string.cl_apagar)) { _, _ ->
            leitura.execute { LegendaBaixar.definirConta(this, null); ui.post { if (!isDestroyed) aoGravar(false) } }
        }
        .setNegativeButton(getString(R.string.cl_cancelar), null)
        .show()
}

internal fun ConfigLauncherActivity.contasNoTopo(c: LinearLayout, fontes: List<FonteTv>) {
    if (fontes.isEmpty()) {
        c.addView(cartao().apply {
            addView(valor(getString(R.string.cl_nenhuma_conta), getString(R.string.cl_nenhuma_conta_det)))
        }, lp())
        // Sem lista ainda (11/11/2026, visto no 1 Play): um vídeo livre para ver o player funcionando antes de cadastrar.
        c.addView(linha(getString(R.string.cl_demo), getString(R.string.cl_demo_det), seta = true) {
            VodActivity.abrirUrl(this, Config.VIDEO_DEMONSTRACAO, "Sintel · Blender Foundation · CC BY 3.0")
        })
    }
    for (f in fontes) {
        secao(c, f.nome.ifEmpty { getString(R.string.cl_aba_conta) })
        val cartao = cartao().apply {
            // Focável (sem clique) só para o ▲ conseguir voltar até ele: senão o quadro sumia acima da tela.
            isFocusable = true
            // Foco = borda no acento, não o fundo branco das linhas: os textos do quadro são claros e sumiam no branco
            // (visto na Sala, 19/11/2026).
            setOnFocusChangeListener { v, temFoco ->
                v.background = if (temFoco) Estilo.fundo(this@contasNoTopo, Estilo.SUPERFICIE, Estilo.RAIO, Estilo.ACENTO, 2) else vidro(false)
                if (temFoco) mostrarDica(noMenu = false)
            }
        }
        c.addView(cartao, lp())
        fun preencher(s: Xtream.Situacao?, consultou: Boolean) {
            cartao.removeAllViews()
            for (l in ContaIptv.linhas(f, s, consultou, System.currentTimeMillis(), t = textosConta))
                cartao.addView(valor(l.rotulo, l.valor, if (l.alerta) coral else null))
        }
        preencher(null, consultou = false)
        val podeConsultar = ContaIptv.contaConsultavel(f) != null
        if (podeConsultar) {
            // Primeiro mostra o que já se sabe (nome, usuário, tipo); a situação chega quando o servidor responder.
            (cartao.getChildAt(cartao.childCount - 1) as? LinearLayout)?.let { (it.getChildAt(1) as? TextView)?.text = getString(R.string.cl_consultando) }
            lateinit var linhaConsultar: LinhaCfg
            fun consultar() {
                linhaConsultar.detalhe(getString(R.string.cl_consultando))
                trabalho.execute {
                    val s = PlayerCarga.situacao(f)
                    val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    ui.post {
                        if (isDestroyed) return@post
                        preencher(s, consultou = true)
                        linhaConsultar.detalhe(if (s != null) getString(R.string.cl_atualizado, hora) else getString(R.string.cl_sem_resposta, hora))
                    }
                }
            }
            linhaConsultar = linha(getString(R.string.cl_atualizar_conta), getString(R.string.cl_atualizar_conta_det), seta = true) { consultar() }
            c.addView(linhaConsultar, lp())
            consultar()
        }
    }
}

/** Lista de contas com apagar, cadastrar e testar. Refeita no lugar depois de mudar, sem perder a tela. */
internal fun ConfigLauncherActivity.montarContasTv(caixa: LinearLayout) {
    leitura.execute {   // Keystore fora da thread da tela (auditoria 10/11)
        val contas = PlayerArmazem.fontes(this)
        ui.post { if (!isDestroyed) montarContasTv(caixa, contas) }
    }
}

internal fun ConfigLauncherActivity.montarContasTv(caixa: LinearLayout, contas: List<FonteTv>) {
    caixa.removeAllViews()
    fun adicionar(v: View) = caixa.addView(v, lp())
    for (f in contas) {
        adicionar(linha(f.nome, "${ContaIptv.tipoLogin(f, textosConta)} · ${f.servidorVisivel}", valor = getString(R.string.cl_apagar)) {
            // Com o controle parental ligado, apagar e cadastrar pedem o PIN: cadastrar de novo muda o id dos canais e
            // os bloqueados por canal deixariam de valer.
            exigirPinSeParental {
                CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(getString(R.string.cl_apagar_conta_titulo, f.nome))
                    .setMessage(getString(R.string.cl_apagar_conta_msg))
                    .setPositiveButton(getString(R.string.cl_apagar)) { _, _ ->
                        PlayerArmazem.apagar(this, f.id)
                        recarregarAba()
                    }
                    .setNegativeButton(getString(R.string.cl_cancelar), null)
                    .show()
            }
        })
    }
    if (contas.size < 8) {
        adicionar(linha(getString(R.string.cl_cad_xtream), getString(R.string.cl_cad_xtream_det), seta = true) {
            exigirPinSeParental { pedirContaTv(FonteTv.TIPO_XTREAM) }
        })
        adicionar(linha(getString(R.string.cl_cad_m3u), getString(R.string.cl_cad_m3u_det), seta = true) {
            exigirPinSeParental { pedirContaTv(FonteTv.TIPO_M3U) }
        })
    }
    if (contas.isNotEmpty()) {
        lateinit var linhaTestar: LinhaCfg
        linhaTestar = linha(getString(R.string.cl_testar), getString(R.string.cl_testar_det), seta = true) {
            linhaTestar.detalhe(getString(R.string.cl_baixando))
            trabalho.execute {
                val agora = System.currentTimeMillis()
                val semCanal = PlayerArmazem.fontes(this).count { PlayerCarga.canaisDaFonte(this, it, forcar = true).isEmpty() }
                val canais = PlayerCarga.canais(this)
                val guia = PlayerCarga.guia(this, canais, forcar = true)
                val comGuia = guia.comProgramaAgora(agora).let { no -> canais.count { it.guiaId in no } }.also { guia.fechar() }   // 1 consulta; solta o banco
                val texto = getString(R.string.cl_teste_resultado, canais.size, comGuia) +
                    (if (semCanal > 0) getString(R.string.cl_teste_sem_canal, semCanal) else "")
                ui.post { if (!isDestroyed) linhaTestar.detalhe(texto) }
            }
        }
        adicionar(linhaTestar)
    }
}

/**
 * Caixa para cadastrar uma conta com o teclado da TV (AlertDialog: OK confirma, Voltar cancela). Xtream: servidor,
 * usuário e senha; lista: o link. O nome é opcional. Erro de validação reabre a caixa com o que já foi digitado.
 */
internal fun ConfigLauncherActivity.pedirContaTv(tipo: String, antes: List<String>? = null, erro: String? = null) {
    val xtream = tipo == FonteTv.TIPO_XTREAM
    fun campo(dica: String, max: Int, tipoTexto: Int, valor: String?) = android.widget.EditText(this).apply {
        inputType = tipoTexto
        filters = arrayOf(android.text.InputFilter.LengthFilter(max))
        hint = dica; setText(valor.orEmpty()); setSingleLine()
    }
    val T = android.text.InputType.TYPE_CLASS_TEXT
    val nome = campo(getString(R.string.cl_campo_nome), 40, T, antes?.getOrNull(0))
    val endereco = campo(if (xtream) getString(R.string.cl_campo_servidor) else getString(R.string.cl_campo_link), 2000,
        T or android.text.InputType.TYPE_TEXT_VARIATION_URI, antes?.getOrNull(1))
    val usuario = campo(getString(R.string.cl_campo_usuario), 200, T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, antes?.getOrNull(2))
    // A senha não volta preenchida num erro: digitar de novo é mais seguro que deixá-la na memória da caixa.
    val senha = campo(getString(R.string.cl_campo_senha), 200, T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, null)
    // Outros endereços do mesmo painel (o vendedor costuma mandar 2 ou 3): usados se o principal cair.
    val reservas = campo(getString(R.string.cl_campo_reservas), 600,
        T or android.text.InputType.TYPE_TEXT_VARIATION_URI, antes?.getOrNull(3))
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(48, 16, 48, 0)
        if (erro != null) addView(Estilo.texto(this@pedirContaTv, Estilo.Papel.LEGENDA, erro).apply { setTextColor(coral) })
        addView(endereco)
        if (xtream) { addView(usuario); addView(senha); addView(reservas) }
        addView(nome)
    }
    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(if (xtream) getString(R.string.cl_conta_xtream) else getString(R.string.cl_lista_canais))
        .setView(caixa)
        .setPositiveButton(getString(R.string.cl_cadastrar)) { _, _ ->
            val r = PlayerArmazem.adicionar(this, nome.text.toString(), tipo, endereco.text.toString(),
                usuario.text.toString(), senha.text.toString(), reservas = reservas.text.toString())
            r.onSuccess { recarregarAba() }.onFailure {
                pedirContaTv(tipo, listOf(nome.text.toString(), endereco.text.toString(), usuario.text.toString(), reservas.text.toString()),
                    getString(R.string.cl_nao_cadastrei, it.message.toString()))
            }
        }
        .setNegativeButton(getString(R.string.cl_cancelar), null)
        .show()
    endereco.requestFocus()
}

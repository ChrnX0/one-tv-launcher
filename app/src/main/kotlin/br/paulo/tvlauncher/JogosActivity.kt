package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.family.Uso
import br.paulo.tvlauncher.games.Jogos
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.games.TurmaDiscord
import br.paulo.tvlauncher.games.TurmaDiscordTv
import br.paulo.tvlauncher.home.CapaFoco
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.CruzOuvir
import br.paulo.tvlauncher.listen.IconeOuvir
import br.paulo.tvlauncher.network.AcordarPc
import br.paulo.tvlauncher.network.Rede
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.settings.Fontes

/**
 * Tela Jogos (Central de jogos). Redesenhada em 18/11/2026 no padrão da cruz da HOME, como Rádio e Podcasts
 * (o dono escolheu "Refazer no visual da cruz": era a única tela grande que ainda parecia outro app).
 * É uma [CruzOuvir]: ◀ ▶ trocam a prateleira, ▲ ▼ andam na coluna, OK abre e segurar OK (ou Y no controle) num jogo
 * troca o grupo dele ou tira ele daqui. Prateleiras, na ordem:
 *  - Jogados por último: os jogos (o ícone do app), o jogado por último primeiro, e "Mais jogos" (Play Store);
 *  - No sofá: os controles ligados, com o número do jogador e a bateria, e "Parear um controle";
 *  - Sua turma: a sala de voz do Discord (OK = QR grande para o celular entrar), quem está online e jogando o quê,
 *    "Chamar a turma" (com webhook) e a configuração;
 *  - Mais opções: testar a rede, jogar no PC, baixar jogos, escolher apps.
 * O cartão de cima à direita ("Esta semana") mostra a rede e quanto se jogou nos últimos 7 dias.
 * A lista de apps, o uso, os ícones e a turma são lidos fora da thread da tela.
 */
class JogosActivity : Activity() {
    private lateinit var cruz: CruzOuvir
    private val ui = Handler(Looper.getMainLooper())
    private var rede: JogosTv.Medida? = null
    private var jogos: List<JogosTv.App> = emptyList()   // já na ordem "jogado por último primeiro"
    private var uso: Map<String, Long> = emptyMap()
    /** Jogado nos últimos 7 dias (ms) e o jogo mais jogado. */
    private class Semana(val ms: Long, val topo: String?)
    private var semana: Semana? = null
    private var turma: TurmaDiscordTv.Estado? = null
    private var esperandoPc = false
    private var abrirNoSofa = false   // veio do item "Sofá" da tela em cruz: começa na turma
    private var focoPacote: String? = null   // voltou de mudar o grupo: o foco volta no mesmo jogo
    /** O jogo escolhido por último na 1ª prateleira: Chamar a turma e Jogar no PC usam ele. */
    private var escolhido: JogosTv.App? = null

    /** O que a tela desenha de cada app: o ícone e a cor dele. */
    private class Visual(val icone: Drawable?, val cor: Int)
    private val visuais = HashMap<String, Visual>()   // só na thread da tela

    private val ouvinteControles = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = montar()
        override fun onInputDeviceRemoved(id: Int) = montar()
        override fun onInputDeviceChanged(id: Int) = montar()
    }

    private val repetirTurma = object : Runnable {
        override fun run() { buscarTurma(); ui.postDelayed(this, 60_000) }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    /** Dicas do rodapé conforme a prateleira: "jogar / mudar de grupo" só vale nos jogos; nas outras, OK abre.
     *  Guarda a última para não refazer as views a cada passo do foco. */
    private var dicasDeJogo: Boolean? = null
    private fun dicasDa(jogo: Boolean) {
        if (dicasDeJogo == jogo) return
        dicasDeJogo = jogo
        if (jogo) cruz.definirDicas(getString(R.string.grv_dica_ok) to getString(R.string.jogos_tecla_jogar),
            getString(R.string.jogos_tecla_segurar) to getString(R.string.jogos_tecla_opcoes))
        else cruz.definirDicas(getString(R.string.grv_dica_ok) to getString(R.string.rede_dica_abrir))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        abrirNoSofa = intent?.getBooleanExtra(EXTRA_SOFA, false) == true
        cruz = CruzOuvir(this, Fontes.nunito(this, 500), Fontes.nunito(this, 800)).apply {
            definirTitulo(getString(R.string.jogos_titulo))
            aoSom = { s -> SonsCruz.tocar(this@JogosActivity, s, CruzLogica.Categoria.JOGAR) }
            aoMudar = { p, i ->
                if (p.id == P_JOGOS) jogos.firstOrNull { it.pacote == i.id }?.let { escolhido = it }
                dicasDa(p.id == P_JOGOS)
            }
        }
        dicasDa(true)
        setContentView(FrameLayout(this).apply {
            background = Estilo.fundoTela(this@JogosActivity)
            addView(cruz, FrameLayout.LayoutParams(-1, -1))
        })
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(ouvinteControles, ui)
        turma = TurmaDiscordTv.guardado(this)
        montar()
        if (abrirNoSofa) cruz.ir(P_TURMA, animar = false)
        cruz.requestFocus()
        testarRede()
    }

    override fun onResume() {
        super.onResume()
        carregar()   // ao voltar de um jogo ou da Play Store: a ordem mudou ou um app foi instalado
        ui.removeCallbacks(repetirTurma)
        repetirTurma.run()
    }

    override fun onPause() {
        ui.removeCallbacks(repetirTurma)   // tela fechada não fala com o Discord
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(ouvinteControles)
        esperandoPc = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun carregar() {
        val ja = visuais.keys.toSet()
        Fundo.rodar("jogos") {
            val ctx = applicationContext
            val lista = JogosTv.listar(ctx)
            val u = Uso.ultimoUso(ctx)
            val porPacote = lista.associateBy { it.pacote }
            val ordenada = Jogos.ordenarPorUso(lista.map { it.pacote }, u).mapNotNull { porPacote[it] }
            val tempos = Uso.tempoNaFrente(ctx).filterKeys { it in porPacote }
            val sem = Semana(tempos.values.sum(), tempos.maxByOrNull { it.value }?.key?.let { porPacote[it]?.nome })
            // Ícones só dos que ainda não foram lidos nesta tela (voltar de um jogo não relê nada).
            val novos = HashMap<String, Visual>()
            for (j in lista) if (j.pacote !in ja) novos[j.pacote] = lerVisual(j.pacote)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                visuais.putAll(novos); uso = u; jogos = ordenada; semana = sem; carregou = true
                if (escolhido == null) escolhido = ordenada.firstOrNull()   // a 1ª prateleira abre no jogado por último
                montar()
            }
        }
    }

    private fun lerVisual(pacote: String): Visual {
        val icone = runCatching { packageManager.getApplicationIcon(pacote) }.getOrNull() ?: OnDemandTv.icon(this, pacote)
        val cor = icone?.let { runCatching { CapaFoco.corDoIcone(it)[0] }.getOrNull() } ?: COR_JOGAR
        return Visual(icone, cor)
    }

    // ================================================================ a cruz

    /** Refaz as prateleiras e o cartão (a cruz guarda o item escolhido de cada uma e só redesenha o que mudou). */
    private fun montar() {
        if (!::cruz.isInitialized || isDestroyed) return
        val prats = listOf(prateleiraJogos(), prateleiraSofa(), prateleiraTurma(), prateleiraOpcoes())
        val foco = focoPacote?.let { P_JOGOS to it }
        focoPacote = null
        cruz.definir(prats, foco)
        cruz.tocando(cartaoSemana())
    }

    /** A lista real já chegou? Antes disso a 1ª prateleira mostra "Carregando…" (estudo global 20/11): montar com a lista
     *  vazia mostrava "Para começar" e trocava para "Jogados por último" na cara da pessoa. */
    private var carregou = false

    private fun prateleiraJogos(): CruzOuvir.Prateleira {
        val itens = ArrayList<CruzOuvir.Item>()
        if (!carregou) return CruzOuvir.Prateleira(P_JOGOS, getString(R.string.jogos_recentes), IconeOuvir.Tipo.CONTROLE,
            listOf(CruzOuvir.Item("carregando", getString(R.string.pp_carregando), "", icone = IconeOuvir.Tipo.CONTROLE, cor = COR_JOGAR)))
        val pronta = (turma as? TurmaDiscordTv.Estado.Pronta)?.turma
        for (j in jogos) {
            val v = visuais[j.pacote]
            // "Lucas e Mari jogando": quem da turma está neste jogo agora vai na linha de baixo.
            val daTurma = pronta?.let { TurmaDiscord.noApp(it, j.nome) }.orEmpty()
            val sub = listOf(subtitulo(j), if (daTurma.isEmpty()) "" else getString(R.string.turma_jogando, juntar(daTurma), j.nome))
                .filter { it.isNotEmpty() }.joinToString("\n")
            itens += CruzOuvir.Item(j.pacote, j.nome, sub, imagem = v?.icone, cor = v?.cor ?: COR_JOGAR,
                monograma = CapaOuvir.iniciais(j.nome), abrir = { abrirJogo(j) }, opcoes = { escolherGrupo(j) })
        }
        if (jogos.isEmpty()) {
            // Sem jogos: as sugestões, que abrem a página na Play Store.
            for (s in Jogos.SUGESTOES.filter { !JogosTv.instalado(this, it.pacote) }.take(6)) {
                itens += CruzOuvir.Item("loja:" + s.pacote, s.nome, nomeGrupo(s.grupo),
                    icone = IconeOuvir.Tipo.CONTROLE, cor = COR_JOGAR, abrir = { abrirNaLoja(s.pacote) })
            }
        }
        itens += CruzOuvir.Item("mais", getString(R.string.jogos_mais_jogos), getString(R.string.jogos_loja_explica),
            icone = IconeOuvir.Tipo.ADICIONAR, abrir = { mostrarLoja() })
        // Sem jogos instalados a prateleira é "Para começar" (as sugestões); com jogos, "Jogados por último".
        val nome = getString(if (jogos.isEmpty()) R.string.jogos_para_comecar else R.string.jogos_recentes)
        return CruzOuvir.Prateleira(P_JOGOS, nome, IconeOuvir.Tipo.CONTROLE, itens)
    }

    /** Um item por controle ligado (número do jogador na cor dele, bateria) e "Parear um controle". */
    private fun prateleiraSofa(): CruzOuvir.Prateleira {
        val itens = ArrayList<CruzOuvir.Item>()
        val c = runCatching { JogosTv.controlesComBateria() }.getOrDefault(emptyList()).take(4)
        for ((i, k) in c.withIndex()) {
            val fraca = k.bateria != null && k.bateria < 20
            val sub = getString(if (fraca) R.string.jogos_controle_carregar else R.string.jogos_controle_pronto) + (k.bateria?.let { " · $it%" } ?: "")
            itens += CruzOuvir.Item("controle:$i:${k.nome}", k.nome, sub, cor = CORES_JOGADOR[i % CORES_JOGADOR.size],
                monograma = "${i + 1}", abrir = { parear() })
        }
        itens += CruzOuvir.Item("parear", getString(R.string.jogos_parear_um),
            if (c.isEmpty()) getString(R.string.jogos_sem_controle) else "", icone = IconeOuvir.Tipo.ADICIONAR, abrir = { parear() })
        return CruzOuvir.Prateleira(P_SOFA, getString(R.string.jogos_no_sofa), IconeOuvir.Tipo.SOFA, itens)
    }

    /** A sala de voz (OK = QR grande), quem está online, "Chamar a turma" e a configuração. */
    private fun prateleiraTurma(): CruzOuvir.Prateleira {
        val itens = ArrayList<CruzOuvir.Item>()
        val config = CruzOuvir.Item("config", getString(R.string.turma_config_titulo), getString(R.string.turma_mostrar_texto),
            icone = IconeOuvir.Tipo.OPCOES, abrir = { configurarTurma() })
        when (val e = turma) {
            null -> itens += CruzOuvir.Item("procurando", getString(R.string.turma_procurando), "", icone = IconeOuvir.Tipo.TURMA)
            TurmaDiscordTv.Estado.SemServidor -> itens += CruzOuvir.Item("config", getString(R.string.turma_mostrar_titulo),
                getString(R.string.turma_mostrar_texto), icone = IconeOuvir.Tipo.TURMA, abrir = { configurarTurma() })
            TurmaDiscordTv.Estado.Desligado -> itens += CruzOuvir.Item("config", getString(R.string.turma_desligado_titulo),
                getString(R.string.turma_desligado_texto), icone = IconeOuvir.Tipo.TURMA, abrir = { configurarTurma() })
            TurmaDiscordTv.Estado.SemRede -> { itens += CruzOuvir.Item("semrede", getString(R.string.turma_sem_rede), "",
                icone = IconeOuvir.Tipo.REDE, abrir = { buscarTurma() }); itens += config }
            is TurmaDiscordTv.Estado.Pronta -> {
                val t = e.turma
                val servidor = TurmaDiscordTv.servidor(this)
                for (sala in t.salas) {
                    val nomes = sala.membros.map { it.nome }
                    itens += CruzOuvir.Item("sala:" + sala.id, getString(R.string.turma_na_voz) + " · " + sala.nome,
                        juntar(nomes) + "\n" + getString(R.string.turma_qr_dica), icone = IconeOuvir.Tipo.TURMA,
                        abrir = { mostrarQrGrande(sala, servidor) })
                }
                if (TurmaDiscordTv.temWebhook(this)) itens += CruzOuvir.Item("chamar", getString(R.string.turma_chamar),
                    getString(R.string.turma_chamado), icone = IconeOuvir.Tipo.CONTROLE, abrir = { chamarTurma(escolhido?.nome) })
                val gente = t.salas.flatMap { it.membros } + t.outros
                for ((i, m) in gente.withIndex()) itens += CruzOuvir.Item("membro:$i:" + m.nome, m.nome, statusMembro(m), capa = m.avatar.orEmpty(),
                    cobrir = true, cor = CORES_AVATAR[Math.floorMod(m.nome.hashCode(), CORES_AVATAR.size)],
                    monograma = m.nome.take(1).uppercase(), abrir = { m.canalVoz?.let { id -> t.salas.firstOrNull { it.id == id } }?.let { mostrarQrGrande(it, servidor) } })
                if (gente.isEmpty()) itens += CruzOuvir.Item("ninguem", getString(R.string.turma_ninguem), "", icone = IconeOuvir.Tipo.TURMA)
                else if (t.online > gente.size) itens += CruzOuvir.Item("mais_online", getString(R.string.turma_mais_online, t.online - gente.size),
                    t.servidor, icone = IconeOuvir.Tipo.TURMA)
                itens += config
            }
        }
        val nome = (turma as? TurmaDiscordTv.Estado.Pronta)?.turma?.let { t -> getString(R.string.turma_titulo) + "\n" + getString(R.string.turma_online, t.online) /* pente fino 19/11: " · " quebrava e a 2ª linha começava com "·" */ }
            ?: getString(R.string.turma_titulo)
        return CruzOuvir.Prateleira(P_TURMA, nome, IconeOuvir.Tipo.TURMA, itens)
    }

    /** "online", "Fortnite" ou "ocupado · EA FC": a palavra do status vai junto quando não está só online. */
    private fun statusMembro(m: TurmaDiscord.Membro): String {
        val status = getString(when (m.status) {
            TurmaDiscord.Status.ONLINE -> R.string.turma_status_online
            TurmaDiscord.Status.AUSENTE -> R.string.turma_ausente
            TurmaDiscord.Status.OCUPADO -> R.string.turma_ocupado
        })
        return when { m.jogo == null -> status; m.status == TurmaDiscord.Status.ONLINE -> m.jogo; else -> "$status · ${m.jogo}" }
    }

    private fun prateleiraOpcoes(): CruzOuvir.Prateleira {
        val m = rede
        val itens = listOf(
            CruzOuvir.Item("rede", getString(R.string.jogos_testar_rede), m?.let { textoRede(it) } ?: getString(R.string.jogos_medindo),
                icone = IconeOuvir.Tipo.REDE, abrir = { testarRede(avisar = true) }),
            CruzOuvir.Item("pc", getString(R.string.jogos_jogar_pc), getString(R.string.jogos_desc_pc),
                icone = IconeOuvir.Tipo.PC, abrir = { jogarNoPc(escolhido?.takeIf { it.grupo == Jogos.Grupo.PC }?.pacote) }),
            CruzOuvir.Item("baixar", getString(R.string.jogos_baixar), getString(R.string.jogos_loja_explica),
                icone = IconeOuvir.Tipo.ADICIONAR, abrir = { mostrarLoja() }),
            CruzOuvir.Item("escolher", getString(R.string.jogos_escolher), getString(R.string.jogos_escolher_sub),
                icone = IconeOuvir.Tipo.OPCOES, abrir = {
                    startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_JOGOS))
                }),
        )
        return CruzOuvir.Prateleira(P_OPCOES, getString(R.string.jogos_mais_opcoes), IconeOuvir.Tipo.OPCOES, itens)
    }

    /** Cartão de cima à direita: ESTA SEMANA, a rede e quanto se jogou (o "—" de antes virou frase). */
    private fun cartaoSemana(): CruzOuvir.Tocando {
        val m = rede
        val titulo = if (m == null) getString(R.string.jogos_chip_medindo) else getString(when (m.rede) {
            Jogos.Rede.BOA -> R.string.jogos_chip_boa
            Jogos.Rede.OK -> R.string.jogos_chip_ok
            Jogos.Rede.FRACA -> R.string.jogos_chip_fraca
            Jogos.Rede.SEM -> R.string.jogos_chip_sem
        }) // sem "· 16 ms": número de rede é jargão para quem joga no sofá (crítica de design 19/11/2026)
        val sem = semana
        val linhas = listOfNotNull(
            sem?.ms?.takeIf { it > 0 }?.let { duracao(it) + " · " + getString(R.string.jogos_semana_jogado) },
            sem?.topo?.takeIf { (sem.ms) > 0 }?.let { getString(R.string.jogos_semana_mais) + ": " + it },
        ).joinToString("\n")
        return CruzOuvir.Tocando(titulo, linhas, "", COR_JOGAR, "", false, null, null, false,
            // O rótulo diz o que está embaixo: "ESTA SEMANA" só quando há horas jogadas; senão o cartão é só a rede.
            aoVivo = getString(if (linhas.isNotEmpty()) R.string.jogos_semana else R.string.cfg_sec_rede).uppercase())
    }

    private fun parear() {
        if (!JogosTv.abrirPareamento(this)) AvisoTv.rapido(this, getString(R.string.jogos_parear), getString(R.string.jogos_parear_falhou))
    }

    private fun buscarTurma() {
        if (TurmaDiscordTv.servidor(this).isEmpty()) { turma = TurmaDiscordTv.Estado.SemServidor; montar(); return }
        val app = applicationContext
        Fundo.rodar("jogos-turma") {
            val e = TurmaDiscordTv.buscar(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // A rede caiu por um instante: fica a turma de antes em vez de piscar "sem conexão".
                if (e is TurmaDiscordTv.Estado.SemRede && turma is TurmaDiscordTv.Estado.Pronta) return@runOnUiThread
                turma = e; montar()
            }
        }
    }

    /** OK na sala de voz: o QR grande, para o celular ler de longe. */
    private fun mostrarQrGrande(sala: TurmaDiscord.Sala, servidor: String) {
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(this@JogosActivity).apply {
                setImageBitmap(QrCodigo.bitmap(TurmaDiscord.linkSala(servidor, sala.id), dp(240)))
                background = Estilo.fundo(this@JogosActivity, 0xFFFFFFFF.toInt(), 12)
                setPaddingRelative(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(240), dp(240)))
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, getString(R.string.turma_qr_dica)).apply { textSize = 18f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        }
        CaixaTv(this).setTitle(getString(R.string.turma_na_voz) + " · " + sala.nome).setView(corpo)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun textoRede(m: JogosTv.Medida): String = getString(when (m.rede) {
        Jogos.Rede.BOA -> R.string.jogos_rede_boa
        Jogos.Rede.OK -> R.string.jogos_rede_ok
        Jogos.Rede.FRACA -> R.string.jogos_rede_fraca
        Jogos.Rede.SEM -> R.string.jogos_rede_sem
    }) + (m.ms?.let { " ($it ms)" } ?: "")

    private fun testarRede(avisar: Boolean = false) {
        rede = null
        montar()
        Fundo.rodar("jogos-rede") {
            val m = JogosTv.medirRede(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rede = m; montar()
                if (avisar) AvisoTv.rapido(this, getString(R.string.jogos_testar_rede), textoRede(m))
            }
        }
    }

    private fun nomeGrupo(g: Jogos.Grupo) = getString(when (g) {
        Jogos.Grupo.NUVEM -> R.string.jogos_grupo_nuvem
        Jogos.Grupo.PC -> R.string.jogos_grupo_pc
        Jogos.Grupo.RETRO -> R.string.jogos_grupo_retro
        Jogos.Grupo.TV -> R.string.jogos_grupo_tv
    })

    /** "Na nuvem · hoje". */
    private fun subtitulo(j: JogosTv.App): String {
        val quando = uso[j.pacote]?.takeIf { it > 0 }?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().lowercase()
        }
        return listOfNotNull(nomeGrupo(j.grupo), quando).joinToString(" · ")
    }

    private fun juntar(nomes: List<String>): String = when (nomes.size) {
        0 -> ""
        1 -> nomes[0]
        2 -> getString(R.string.turma_dois, nomes[0], nomes[1])
        else -> getString(R.string.turma_e_mais, nomes[0], nomes.size - 1)
    }

    /** "3 h 20 min" / "45 min" / "menos de 1 min". */
    private fun duracao(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> getString(R.string.jogos_menos_de_1_min)
            min < 60 -> getString(R.string.jogos_min, min)
            else -> getString(R.string.jogos_h_min, min / 60, min % 60)
        }
    }

    // ================================================================ ações

    private fun abrir(pacote: String): Boolean {
        // Congelado sob demanda (OnDemandTv): descongela e abre fora da tela; volta a congelar 2 min depois de sair.
        if (OnDemandTv.marked(this, pacote)) { OnDemandTv.open(this, pacote) {
            if (!isDestroyed) AvisoTv.rapido(this, getString(R.string.jogos_titulo), getString(R.string.jogos_nao_abriu))
        }; return true }
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Jogo na nuvem com a rede fraca: avisa antes (ideia 4 da especificação). Os outros abrem direto. */
    private fun abrirJogo(j: JogosTv.App) {
        val r = rede?.rede
        if (j.grupo == Jogos.Grupo.NUVEM && (r == Jogos.Rede.FRACA || r == Jogos.Rede.SEM)) {
            CaixaTv(this).setTitle(if (r == Jogos.Rede.SEM) R.string.jogos_rede_sem else R.string.jogos_aviso_fraca_titulo)
                .setMessage(R.string.jogos_aviso_fraca)
                .setPositiveButton(R.string.jogos_jogar_assim) { _, _ -> abrirOuAvisar(j.pacote) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        abrirOuAvisar(j.pacote)
    }

    private fun abrirOuAvisar(pacote: String) {
        if (!abrir(pacote)) AvisoTv.rapido(this, getString(R.string.jogos_titulo), getString(R.string.jogos_nao_abriu))
    }

    /** Segurar OK num jogo: em que grupo ele fica, tirar do card ou voltar ao automático. */
    private fun escolherGrupo(j: JogosTv.App) {
        // App de PC: "Ligar o PC e jogar" com este app vem primeiro (era o botão ao lado do Jogar).
        val pc = if (j.grupo == Jogos.Grupo.PC) listOf(getString(R.string.jogos_ligar_pc_jogar) to { jogarNoPc(j.pacote) }) else emptyList()
        lista(j.nome, pc + Jogos.Grupo.values().map { g -> ((if (g == j.grupo) "✓ " else "") + nomeGrupo(g)) to { escolherValor(j, g.name) } } +
            listOf(getString(R.string.jogos_tirar) to { escolherValor(j, Jogos.FORA) }, getString(R.string.jogos_automatico) to { escolherValor(j, null) }) +
            sobDemanda(j))
    }

    /** Congelar sob demanda (OnDemandTv), como no menu dos apps da HOME: jogos moram aqui, não na lista de apps. */
    private fun sobDemanda(j: JogosTv.App): List<Pair<String, () -> Unit>> {
        val marcado = OnDemandTv.marked(this, j.pacote)
        if (!marcado && !OnDemandTv.eligible(this, j.pacote)) return emptyList()
        return listOf(getString(if (marcado) R.string.home_sob_demanda_tirar else R.string.home_sob_demanda) to {
            Fundo.rodar("sob-demanda-jogos") {
                val motivo = if (marcado) OnDemandTv.unmark(this, j.pacote) else OnDemandTv.mark(this, j.pacote)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    AvisoTv.rapido(this, j.nome, getString(when {
                        motivo != null -> R.string.sob_demanda_falhou
                        marcado -> R.string.sob_demanda_desligado
                        else -> R.string.sob_demanda_ligado
                    }))
                    focoPacote = j.pacote
                    carregar()
                }
            }
        })
    }

    private fun escolherValor(j: JogosTv.App, valor: String?) {
        JogosTv.escolher(this, j.pacote, valor)
        focoPacote = j.pacote
        carregar()
    }

    /** Lista de opções numa caixa: OK numa linha fecha a caixa e faz a ação. */
    private fun lista(titulo: String, itens: List<Pair<String, () -> Unit>>, mensagem: String? = null) {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var janela: CaixaTv.Janela? = null
        for ((rotulo, ao) in itens) corpo.addView(Estilo.linhaTexto(this, rotulo).apply {
            setOnClickListener { janela?.dismiss(); ao() }
        }, Estilo.lpLinha(this))
        janela = CaixaTv(this).setTitle(titulo).apply { if (mensagem != null) setMessage(mensagem) }.setView(corpo)
            .setNegativeButton(android.R.string.cancel, null).show()
        corpo.getChildAt(0)?.requestFocus()
    }

    /** Sugestões que ainda não estão na TV. A Play Store decide se roda neste aparelho (dono: "problema dele"). */
    private fun mostrarLoja() {
        val faltam = Jogos.SUGESTOES.filter { !JogosTv.instalado(this, it.pacote) }
        if (faltam.isEmpty()) { AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_loja_tudo)); return }
        lista(getString(R.string.jogos_baixar), faltam.map { s -> (s.nome + "  ·  " + nomeGrupo(s.grupo)) to { abrirNaLoja(s.pacote) } },
            getString(R.string.jogos_loja_explica))
    }

    private fun abrirNaLoja(pacote: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pacote")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {
            AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_sem_loja))
        }
    }

    // ================================================================ turma: configurar e chamar

    /**
     * O ID do servidor se digita aqui (só números; o teclado da TV abre no modo numérico). O webhook do "Chamar a
     * turma" é comprido demais para o controle: vai pelo painel web ou pelo adb (`EXTRAS --es o turma_webhook`).
     */
    private fun configurarTurma() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            hint = getString(R.string.turma_config_dica)
            setText(TurmaDiscordTv.servidor(this@JogosActivity))
        }
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, getString(R.string.turma_config_texto)).apply { textSize = 17f; setLineSpacing(0f, 1.2f) })
            addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        }
        val caixa = CaixaTv(this).setTitle(R.string.turma_config_titulo).setView(corpo)
            .setPositiveButton(R.string.turma_config_guardar) { _, _ -> guardarServidor(campo.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (TurmaDiscordTv.servidor(this).isNotEmpty()) caixa.setNeutralButton(getString(R.string.turma_config_tirar)) { _, _ -> guardarServidor("") }
        caixa.show()
        campo.requestFocus()
    }

    private fun guardarServidor(id: String) {
        if (!TurmaDiscordTv.guardarServidor(this, id)) {
            AvisoTv.rapido(this, getString(R.string.turma_config_titulo), getString(R.string.turma_id_invalido)); return
        }
        turma = if (id.isBlank()) TurmaDiscordTv.Estado.SemServidor else null
        montar()
        buscarTurma()
    }

    /** Manda "Bora jogar <jogo>?" no canal do webhook, depois de mostrar o texto e perguntar. */
    private fun chamarTurma(jogo: String?) {
        val texto = if (jogo.isNullOrBlank()) getString(R.string.turma_chamado) else getString(R.string.turma_chamado_jogo, jogo)
        CaixaTv(this).setTitle(R.string.turma_chamar_pergunta).setMessage(texto)
            .setPositiveButton(R.string.turma_mandar) { _, _ ->
                val app = applicationContext
                Fundo.rodar("jogos-chamar") {
                    val r = TurmaDiscordTv.chamar(app, texto)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        AvisoTv.rapido(this, getString(R.string.turma_chamar), getString(when (r) {
                            TurmaDiscordTv.Chamado.ENVIADO -> R.string.turma_enviado
                            TurmaDiscordTv.Chamado.ESPERE -> R.string.turma_espere
                            TurmaDiscordTv.Chamado.SEM_WEBHOOK, TurmaDiscordTv.Chamado.FALHOU -> R.string.turma_falhou
                        }))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ================================================================ jogar no PC (ideia 3)

    /** [pacote] = o app de PC em foco ("Ligar o PC e jogar"); null = o botão geral, que escolhe o app. */
    private fun jogarNoPc(pacote: String?) {
        if (AcordarPc.macGuardado(this).isEmpty()) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_mac)
                .setPositiveButton(android.R.string.ok, null).show(); return
        }
        if (pacote != null) { acordarEAbrir(pacote); return }
        val instalados = Jogos.APPS_PC.filter { JogosTv.instalado(this, it) }.toSet()
        val app = Jogos.appDoPc(instalados, JogosTv.appPcEscolhido(this))
        if (app == null) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_app)
                .setPositiveButton(R.string.jogos_baixar) { _, _ -> mostrarLoja() }
                .setNegativeButton(android.R.string.cancel, null).show(); return
        }
        if (instalados.size > 1 && JogosTv.appPcEscolhido(this) == null) { escolherAppPc(instalados.toList()); return }
        acordarEAbrir(app)
    }

    /** Mais de um app de PC instalado: pergunta uma vez qual usar (a escolha fica guardada). */
    private fun escolherAppPc(apps: List<String>) = lista(getString(R.string.jogos_pc_qual_app), apps.map { p ->
        val nome = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        nome to { JogosTv.escolherAppPc(this, p); acordarEAbrir(p) }
    })

    private fun acordarEAbrir(app: String) {
        if (esperandoPc) return
        esperandoPc = true
        val texto = Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.jogos_pc_acordando, 0)).apply { textSize = 19f }
        val janela = CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setView(texto)
            .setNegativeButton(android.R.string.cancel) { _, _ -> esperandoPc = false }
            .setOnCancelListener { esperandoPc = false }.show()
        val app0 = applicationContext
        Fundo.rodar("jogos-pc") {
            val ok = JogosTv.acordarEEsperar(app0, { s -> runOnUiThread { texto.text = getString(R.string.jogos_pc_acordando, s) } }, { !esperandoPc })
            runOnUiThread {
                if (isDestroyed || !esperandoPc) return@runOnUiThread
                esperandoPc = false
                runCatching { janela.dismiss() }
                if (!ok) AvisoTv.rapido(this, getString(R.string.jogos_jogar_pc), getString(R.string.jogos_pc_nao_respondeu))
                abrirOuAvisar(app)   // abre mesmo sem resposta: o app de PC mostra o próprio erro e deixa tentar de novo
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Botão Y/△ do controle de videogame em cima de um jogo = o mesmo que segurar OK (escolher o grupo).
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_UP) {
            val p = cruz.itemAtual()?.id
            jogos.firstOrNull { it.pacote == p }?.let { escolherGrupo(it); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Abrir já na turma (item "Sofá" da coluna Jogar da tela em cruz). */
        const val EXTRA_SOFA = "br.paulo.tvlauncher.jogos.SOFA"

        private const val P_JOGOS = "jogos"
        private const val P_SOFA = "sofa"
        private const val P_TURMA = "turma"
        private const val P_OPCOES = "opcoes"
        /** O violeta do Jogar na cruz ([IconeCruz.corLinha]). */
        private const val COR_JOGAR = 0xFF8E92FA.toInt()
        private val CORES_JOGADOR = intArrayOf(DesignTokens.WORLD_PLAY, 0xFF3D7BFF.toInt(), 0xFF2FA866.toInt(), 0xFFC98A12.toInt())
        // Avatares: tons claros, nenhum rosado (o dono detesta rosa).
        private val CORES_AVATAR = intArrayOf(0xFFFFB37A.toInt(), 0xFF9CC8FF.toInt(), 0xFFA8E6B8.toInt(), 0xFFF5DFA0.toInt(), 0xFF8FE0D0.toInt(), 0xFFC7D3E0.toInt())
    }
}

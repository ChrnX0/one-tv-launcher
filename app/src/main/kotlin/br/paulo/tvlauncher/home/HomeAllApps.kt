package br.paulo.tvlauncher.home

import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.ComunidadeApps
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.EntradasTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.MainActivity.Companion.CARTAO_TRANCA
import br.paulo.tvlauncher.MainActivity.Companion.COR_CONGELADOS
import br.paulo.tvlauncher.MainActivity.Companion.COR_ESCONDIDOS
import br.paulo.tvlauncher.MainActivity.Companion.DIAS_PARADO
import br.paulo.tvlauncher.MainActivity.Companion.PREFS_CRUZ_APPS
import br.paulo.tvlauncher.MainActivity.Companion.fichasHome
import br.paulo.tvlauncher.MainActivity.DadosTodos
import br.paulo.tvlauncher.MainActivity.Item
import br.paulo.tvlauncher.MainActivity.Opcao
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.PinTv
import br.paulo.tvlauncher.family.TravaHome
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.observer.ObserverTv
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelControle
import br.paulo.tvlauncher.panel.PainelPermissoes
import br.paulo.tvlauncher.panel.precisaPin
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.privilege.PrivilegioDO

/** "Todos os apps" da HOME (AllAppsScreen) e a contagem de aberturas dos apps da cruz. Saiu da MainActivity na refatoração de 21/11/2026: funções de extensão; o estado continua na MainActivity. */

internal fun MainActivity.abrirTodos() {
    val c = cruz ?: return
    todosAbertos = true
    escondidosLiberados = false
    c.visibility = View.GONE
    val tela = telaTodos ?: AllAppsScreen(this, hostTodos).also { t ->
        telaTodos = t
        (raizCruz ?: c.parent as? FrameLayout)?.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    tela.visibility = View.VISIBLE
    tela.bringToFront()
    if (dadosTodos != null) montarTodos(null)   // mostra já com o que sabia; os dados novos redesenham em seguida
    lerDadosTodos(null)
}

internal fun MainActivity.fecharTodos() {
    val c = cruz ?: return
    todosAbertos = false
    escondidosLiberados = false
    telaTodos?.visibility = View.GONE
    c.visibility = View.VISIBLE
    c.requestFocus()
}

internal fun MainActivity.lerDadosTodos(foco: String?) {
    Fundo.rodar("TodosApps") {
        val pm = packageManager
        if (catsFicha == null) catsFicha = runCatching { AppShelf.sheetCategories(ComunidadeApps.fichasJuntas(this)) }.getOrDefault(emptyMap())
        val sistema = instalados.filter { PainelApps.eSistema(this, it.pacote) }.map { it.pacote }.toSet()
        val congelados = runCatching { appsCongelados(pm) }.getOrDefault(emptyList())
        ui.post {
            if (isDestroyed) return@post
            dadosTodos = DadosTodos(sistema, congelados)
            if (todosAbertos) montarTodos(foco ?: (currentFocus?.tag as? AllAppsScreen.Card)?.pkg)
        }
    }
}

/** Apps de TV congelados (ocultos pelo dono ou desativados pelo shell): só os que têm tela de abrir. */
internal fun MainActivity.appsCongelados(pm: android.content.pm.PackageManager): List<Item> {
    val flags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
    val saida = ArrayList<Item>()
    for (ai in pm.getInstalledApplications(flags)) {
        val p = ai.packageName
        if (p == packageName || !PrivilegioDO.estaOculto(this, p)) continue
        val tela = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(p)
        if (pm.queryIntentActivities(tela, flags).isEmpty()) continue
        val icone = runCatching { DesenhoHome.iconeRedondo(resources, ai.loadIcon(pm)) }.getOrNull() ?: continue
        saida += Item(p, ai.loadLabel(pm).toString(), icone)
    }
    return saida.sortedBy { it.nome.lowercase() }
}

/** Monta as prateleiras com o que está na memória (rápido, na thread da tela). */
internal fun MainActivity.montarTodos(foco: String?) {
    val tela = telaTodos ?: return
    val dados = dadosTodos ?: DadosTodos(emptySet(), emptyList())
    val visita = ModoVisita.ativo(this)
    val daVisita = if (visita) ModoVisita.apps(this) else emptySet()
    val ocultos = Organizacao.ocultos(this)
    val apps = instalados.filter { it.pacote !in daVisita }
    val visiveis = apps.filter { it.pacote !in ocultos }
    val escondidos = if (visita) emptyList() else apps.filter { it.pacote in ocultos }
    val freq = frequenciaApps()
    val porPacote = (apps + dados.congelados).associateBy { it.pacote }
    val entradas = visiveis.map { AppShelf.Entry(it.pacote, it.nome, prateleira(it.pacote, dados), freq[it.pacote] ?: 0.0) }
    val (fileiras, unica) = AppShelf.rows(entradas)
    val secoes = ArrayList<AllAppsScreen.Section>()
    fun card(p: String, k: AllAppsScreen.Kind) = porPacote[p]?.let { AllAppsScreen.Card(p, it.nome, k, it.icone) }
    for (f in fileiras) secoes += AllAppsScreen.Section(if (unica) null else nomePrateleira(f.shelf), corPrateleira(f.shelf),
        f.pkgs.mapNotNull { card(it, AllAppsScreen.Kind.APP) })
    if (dados.congelados.isNotEmpty() && !visita) secoes += AllAppsScreen.Section(getString(R.string.aa_frozen), COR_CONGELADOS,
        dados.congelados.map { AllAppsScreen.Card(it.pacote, it.nome, AllAppsScreen.Kind.FROZEN, it.icone) })
    if (escondidos.isNotEmpty()) secoes += AllAppsScreen.Section(getString(R.string.aa_hidden), COR_ESCONDIDOS,
        if (escondidosLiberados) escondidos.sortedBy { it.nome.lowercase() }.map { AllAppsScreen.Card(it.pacote, it.nome, AllAppsScreen.Kind.HIDDEN, it.icone) }
        else listOf(AllAppsScreen.Card(CARTAO_TRANCA, getString(R.string.aa_lock, escondidos.size), AllAppsScreen.Kind.LOCK, null)))
    val conta = buildString {
        append(getString(R.string.cruz_apps_n, visiveis.size))
        if (escondidos.isNotEmpty()) append(" · ").append(getString(R.string.aa_count_hidden, escondidos.size))
        if (dados.congelados.isNotEmpty() && !visita) append(" · ").append(getString(R.string.aa_count_frozen, dados.congelados.size))
    }
    tela.show(conta, secoes, foco)
    if (secoes.all { it.cards.isEmpty() }) bolinhaPerfil?.requestFocus()   // nada na tela: o foco não fica sem dono
}

internal fun MainActivity.prateleira(p: String, dados: DadosTodos): AppShelf.Shelf {
    val mundo = CategoriaApps.escolhas(this)[p]?.let { id -> CategoriaApps.DESTINOS.firstOrNull { it.id == id } }
    return AppShelf.decide(p, prateleirasManuais()[p], JogosTv.ehJogo(this, p), catsFicha?.get(p), categoria(p), p in dados.sistema, mundo)
}

internal fun MainActivity.prateleirasManuais(): Map<String, String> = AppShelf.fromJson(prefsApps().getString("shelves", null))

internal fun MainActivity.nomePrateleira(s: AppShelf.Shelf): String = getString(when (s) {
    AppShelf.Shelf.ENTERTAINMENT -> R.string.aa_shelf_entertainment
    AppShelf.Shelf.VIDEO -> R.string.aa_shelf_video
    AppShelf.Shelf.SPORTS -> R.string.aa_shelf_sports
    AppShelf.Shelf.KIDS -> R.string.aa_shelf_kids
    AppShelf.Shelf.MUSIC_AUDIO -> R.string.aa_shelf_music_audio
    AppShelf.Shelf.NEWS -> R.string.aa_shelf_news
    AppShelf.Shelf.GAMES -> R.string.aa_shelf_games
    AppShelf.Shelf.EDUCATION -> R.string.aa_shelf_education
    AppShelf.Shelf.HEALTH_FITNESS -> R.string.aa_shelf_health_fitness
    AppShelf.Shelf.LIFESTYLE -> R.string.aa_shelf_lifestyle
    AppShelf.Shelf.TOOLS -> R.string.aa_shelf_tools
    AppShelf.Shelf.SYSTEM -> R.string.aa_shelf_system
    AppShelf.Shelf.OTHER -> R.string.aa_shelf_other
})

/** A cor do mundo da cruz em que a prateleira mora (decisão 3 da crítica de 19/11: cor fixa por mundo). */
internal fun MainActivity.corPrateleira(s: AppShelf.Shelf): Int = if (s == AppShelf.Shelf.SYSTEM) COR_ESCONDIDOS else when (s.world) {
    CruzLogica.Categoria.ASSISTIR -> DesignTokens.WORLD_WATCH
    CruzLogica.Categoria.OUVIR -> DesignTokens.WORLD_LISTEN
    CruzLogica.Categoria.JOGAR -> DesignTokens.WORLD_PLAY
    else -> DesignTokens.WORLD_APPS
}

/** Banner de TV de um app, também de um congelado (o Android só mostra esses com as flags abaixo). Fora da tela. */
internal fun MainActivity.bannerTodos(p: String): Drawable? {
    val pm = packageManager
    val flags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
    val ai = pm.getApplicationInfo(p, flags)
    val tela = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(p), flags)
        .firstOrNull()?.activityInfo
    val b = tela?.loadBanner(pm) ?: pm.getApplicationBanner(ai) ?: return null
    return runCatching { android.graphics.drawable.BitmapDrawable(resources, CapaFoco.bitmapReduzido(b)) }.getOrNull()
}

/** O painel do app em foco: o que ele é (ficha), quando abriu, quanto ocupa, o risco e a dica do Observador. */
internal fun MainActivity.infoTodos(card: AllAppsScreen.Card): AllAppsScreen.Info {
    val p = card.pkg
    val agora = System.currentTimeMillis()
    val linhas = ArrayList<String>()
    val portugues = resources.configuration.locales[0].language == "pt"
    fichasHome?.get(p)?.let { f -> (if (portugues) f.pt else f.en)?.let { linhas += it } }
    val ultimo = maxOf(ultimoUso[p] ?: 0L, lerFrequencia(prefsApps().getString("f:$p", null)).second)
    val dias = if (ultimo > 0) ((agora - ultimo) / 86_400_000L).toInt() else -1
    if (card.kind != AllAppsScreen.Kind.FROZEN) linhas += when {
        dias < 0 -> getString(R.string.aa_used_never)
        dias == 0 -> getString(R.string.aa_used_today)
        else -> getString(R.string.aa_used_days, dias)
    }
    PainelControle.espaco(this, p)?.let { e ->
        val total = e.optLong("app") + e.optLong("dados") + e.optLong("cache")
        if (total > 0) linhas += getString(R.string.aa_size, android.text.format.Formatter.formatShortFileSize(this, total))
    }
    val risco = PainelApps.risco(this, p)
    when (risco) {
        "seguro" -> linhas += getString(R.string.aa_risk_safe)
        "cuidado" -> linhas += getString(R.string.aa_risk_care)
        "essencial" -> linhas += getString(R.string.aa_risk_essential)
    }
    // Dica (Observador): app seguro, instalado há mais de 60 dias, sem abrir há 60 dias. Congelar dá para desfazer.
    val instalado = runCatching { packageManager.getPackageInfo(p, 0).firstInstallTime }.getOrDefault(agora)
    val parado = if (ultimo > 0) dias else ((agora - instalado) / 86_400_000L).toInt()
    val dica = if (card.kind == AllAppsScreen.Kind.APP && risco == "seguro" && podeCongelar() &&
        agora - instalado > DIAS_PARADO * 86_400_000L && parado >= DIAS_PARADO) getString(R.string.aa_tip_idle, parado) else null
    val ajuda = getString(if (card.kind == AllAppsScreen.Kind.FROZEN) R.string.aa_hint_frozen else R.string.aa_hint_app)
    return AllAppsScreen.Info(card.name, linhas, dica, ajuda)
}

internal fun MainActivity.podeCongelar() = PlayPolicy.allowed(PlayPolicy.Power.FREEZE_APPS, BuildConfig.PLAY)

/** PIN do painel quando existe um (o mesmo que administra a TV; o das crianças não vale). Sem PIN, segue direto. */
internal fun MainActivity.comPinDoDono(titulo: String, acao: () -> Unit) {
    if (!PainelArmazem.temPin(this)) { acao(); return }
    PinTv.pedir(this, titulo, familia = false) { certo ->
        if (certo) acao() else AvisoTv.rapido(this, titulo, getString(R.string.trava_home_errado), 4)
    }
}

internal fun MainActivity.liberarEscondidos() = comPinDoDono(getString(R.string.aa_pin_title)) {
    escondidosLiberados = true
    val primeiro = Organizacao.ocultos(this).mapNotNull { p -> instalados.firstOrNull { it.pacote == p } }.minByOrNull { it.nome.lowercase() }
    montarTodos(primeiro?.pacote)
}

internal fun MainActivity.menuTodos(card: AllAppsScreen.Card) {
    if (TravaHome.precisaPin(this)) { TravaHome.comPin(this) { menuTodos(card) }; return }
    val p = card.pkg
    val opcoes = mutableListOf(Opcao(getString(R.string.aa_open)) { abrir(p) })
    opcoes += Opcao(getString(R.string.cruz_mover)) { moverPrateleira(p) }
    opcoes += if (card.kind == AllAppsScreen.Kind.HIDDEN) Opcao(getString(R.string.home_mostrar)) { Organizacao.mostrar(this, p); atualizarAposMudanca(null); montarTodos(p) }
    else Opcao(getString(R.string.home_ocultar)) { Organizacao.ocultar(this, p); atualizarAposMudanca(null); montarTodos(null); avisarOculto() }
    if (podeCongelar() && !EntradasTv.eh(p)) opcoes += Opcao(getString(R.string.aa_freeze)) { congelarTodos(p) }
    opcoes += Opcao(getString(R.string.wp_trocar_icone)) { trocarIconeCruz(p) }
    val desinstala = PainelApps.podeDesinstalar(this, p)
    if (desinstala) opcoes += Opcao(getString(R.string.home_desinstalar)) { comPinDoDono(getString(R.string.home_desinstalar)) { confirmarDesinstalar(p) } }
    val titulo = nomeDe(p) + if (PainelApps.eSistema(this, p)) getString(R.string.home_app_sistema) else ""
    menuAberto = Estilo.lista(this, titulo, opcoes.map { it.rotulo }, separarUltimo = desinstala) { i -> opcoes[i].acao() }
}

/** "Mover para…" nas prateleiras: grava a prateleira e leva o mundo da cruz junto (um lugar só decide). */
internal fun MainActivity.moverPrateleira(p: String) {
    val dados = dadosTodos ?: DadosTodos(emptySet(), emptyList())
    val atual = prateleira(p, dados)
    val manual = prateleirasManuais()[p]
    val destinos = AppShelf.Shelf.entries.filter { it != AppShelf.Shelf.GAMES || JogosTv.cardLigado(this) || JogosTv.ehJogo(this, p) }
    val nomes = listOf<CharSequence>(getString(R.string.aa_move_auto) + if (manual == null) getString(R.string.pp_ativo) else "") +
        destinos.map { s -> nomePrateleira(s) + if (manual != null && s == atual) getString(R.string.pp_ativo) else "" }
    menuAberto = Estilo.lista(this, getString(R.string.cruz_mover_titulo, nomeDe(p)), nomes) { i ->
        val m = prateleirasManuais().toMutableMap()
        val eraJogo = JogosTv.ehJogo(this, p)
        if (i == 0) {
            m.remove(p)
            // Automático também apaga o mundo escolhido à mão na cruz (volta tudo a seguir a ficha).
            if (!eraJogo) CategoriaApps.escolher(this, p, CategoriaApps.automatica(p, categoria(p), catsFicha?.get(p)), categoria(p), false, catsFicha?.get(p))
        } else {
            val s = destinos[i - 1]
            m[p] = s.id
            CategoriaApps.escolher(this, p, s.world, categoria(p), eraJogo, catsFicha?.get(p))
        }
        prefsApps().edit().putString("shelves", AppShelf.toJson(m)).apply()
        atualizarAposMudanca(null)
        montarTodos(p)
    }
}

/** Congela pela tela: PIN se a ficha não diz "seguro"; o Observador guarda as conferências de antes e confere em 10 min. */
internal fun MainActivity.congelarTodos(p: String) {
    val nome = nomeDe(p)
    Fundo.rodar("TodosRisco") {
        val risco = PainelApps.risco(this, p) ?: "sem informação"
        ui.post {
            if (isDestroyed) return@post
            val fazer = {
                mostrarAviso(getString(R.string.aa_freezing, nome))
                Fundo.rodar("Congelar") {
                    val antes = runCatching { ObserverTv.beforeFreeze(this) }.getOrNull()
                    val motivo = PainelApps.ocultar(this, p, true)
                    if (motivo == null && antes != null) runCatching { ObserverTv.startTrial(this, p, antes) }
                    Log.i("TvLauncher.Apps", "congelar $p pela tela Todos os apps => ${motivo ?: "ok"}")
                    ui.post {
                        if (isDestroyed) return@post
                        mostrarAviso(if (motivo == null) getString(R.string.aa_frozen_done, nome) else getString(R.string.aa_freeze_failed, nome, motivo))
                        preencherApps(); lerDadosTodos(null)
                    }
                }
            }
            if (MenuApp.ocultarPedePin(risco)) comPinDoDono(getString(R.string.aa_freeze)) { fazer() } else fazer()
        }
    }
}

/** Congelado: descongela (com o PIN do dono, como no painel) e abre; pode pedir para congelar de novo na volta. */
internal fun MainActivity.abrirCongelado(card: AllAppsScreen.Card) {
    val opcoes = listOf<CharSequence>(getString(R.string.aa_unfreeze_open), getString(R.string.aa_unfreeze_refreeze))
    menuAberto = Estilo.lista(this, card.name, opcoes) { i ->
        comPinDoDono(getString(R.string.aa_unfreeze_open)) {
            mostrarAviso(getString(R.string.aa_unfreezing, card.name))
            Fundo.rodar("Descongelar") {
                val motivo = PainelApps.ocultar(this, card.pkg, false)
                Log.i("TvLauncher.Apps", "descongelar ${card.pkg} pela tela Todos os apps => ${motivo ?: "ok"}")
                ui.post {
                    if (isDestroyed) return@post
                    if (motivo != null) { mostrarAviso(getString(R.string.aa_unfreeze_failed, card.name, motivo)); return@post }
                    if (i == 1) prefsApps().edit().putStringSet("recongelar", (prefsApps().getStringSet("recongelar", null).orEmpty() + card.pkg)).apply()
                    preencherApps(); lerDadosTodos(card.pkg)
                    abrir(card.pkg)
                }
            }
        }
    }
}

/** Volta à HOME: congela de novo o que foi aberto com "congelar de novo ao voltar" (com a prova do Observador). */
internal fun MainActivity.recongelarPendentes() {
    val pendentes = prefsApps().getStringSet("recongelar", null).orEmpty()
    if (pendentes.isEmpty()) return
    prefsApps().edit().remove("recongelar").apply()
    Fundo.rodar("Recongelar") {
        for (p in pendentes) {
            val antes = runCatching { ObserverTv.beforeFreeze(this) }.getOrNull()
            val motivo = PainelApps.ocultar(this, p, true)
            if (motivo == null && antes != null) runCatching { ObserverTv.startTrial(this, p, antes) }
            Log.i("TvLauncher.Apps", "congelar de novo $p na volta => ${motivo ?: "ok"}")
        }
        ui.post { if (!isDestroyed) { preencherApps(); if (todosAbertos) lerDadosTodos(null) } }
    }
}

/** Segurar OK num app da cruz: as opções de hoje que fazem sentido fora da grade (ocultar, desinstalar). */
internal fun MainActivity.menuAppCruz(pacote: String) {
    if (TravaHome.precisaPin(this)) { TravaHome.comPin(this) { menuAppCruz(pacote) }; return }
    val fixados = fixadosCruz()
    val fixado = pacote in fixados
    val opcoes = mutableListOf(Opcao(getString(if (fixado) R.string.cruz_soltar else R.string.cruz_fixar)) {
        guardarFixadosCruz(if (fixado) fixados - pacote else fixados + pacote)
        atualizarAposMudanca(null)
    })
    opcoes += Opcao(getString(R.string.cruz_mover)) { moverAppCruz(pacote) }
    opcoes += Opcao(getString(R.string.wp_trocar_icone)) { trocarIconeCruz(pacote) }
    opcoes += Opcao(getString(R.string.home_ocultar)) { Organizacao.ocultar(this, pacote); atualizarAposMudanca(null); avisarOculto() }
    // Congelar sob demanda (OnDemandTv): só existia no menu da HOME antiga; na cruz ficava sem caminho (21/11/2026).
    when {
        OnDemandTv.marked(this, pacote) -> opcoes += Opcao(getString(R.string.home_sob_demanda_tirar)) { mudarSobDemanda(pacote, false) }
        OnDemandTv.eligible(this, pacote) -> opcoes += Opcao(getString(R.string.home_sob_demanda)) { mudarSobDemanda(pacote, true) }
    }
    when (AppStorage.opcaoMenu(this, pacote)) {
        true -> opcoes += Opcao(getString(R.string.home_instalar_externo)) { mudarArmazenamento(pacote, true) }
        false -> opcoes += Opcao(getString(R.string.home_instalar_dispositivo)) { mudarArmazenamento(pacote, false) }
        null -> {}
    }
    if (PainelPermissoes.arquivosBloqueados(this, pacote))
        opcoes += Opcao(getString(R.string.home_liberar_arquivos)) { liberarArquivos(pacote) }
    AppStorage.atualizarEmFundo(this)
    val desinstala = PainelApps.podeDesinstalar(this, pacote)
    if (desinstala) opcoes += Opcao(getString(R.string.home_desinstalar)) { confirmarDesinstalar(pacote) }
    val titulo = nomeDe(pacote) + if (PainelApps.eSistema(this, pacote)) getString(R.string.home_app_sistema) else ""
    menuAberto = Estilo.lista(this, titulo, opcoes.map { it.rotulo }, separarUltimo = desinstala) { i -> opcoes[i].acao() }
}

/** Congelar sob demanda (OnDemandTv): congela fora da tela (sem dono o shell leva segundos) e redesenha a lista. */
internal fun MainActivity.mudarSobDemanda(pacote: String, ligar: Boolean) {
    val nome = nomeDe(pacote)
    Fundo.rodar("sob-demanda-menu") {
        val motivo = if (ligar) OnDemandTv.mark(this, pacote) else OnDemandTv.unmark(this, pacote)
        ui.post {
            if (isDestroyed) return@post
            AvisoTv.rapido(this, nome, when {
                motivo != null -> getString(R.string.sob_demanda_falhou)
                ligar -> getString(R.string.sob_demanda_ligado)
                else -> getString(R.string.sob_demanda_desligado)
            })
            atualizarAposMudanca(pacote)
        }
    }
}

/** O card "Ocultos" saiu da HOME (14/11/2026): ao ocultar, diz onde mostrar de volta. */
internal fun MainActivity.avisarOculto() = AvisoTv.rapido(this, getString(R.string.oc_aviso_titulo), getString(R.string.oc_aviso))

internal fun MainActivity.abrir(pacote: String) {
    if (EntradasTv.eh(pacote)) { EntradasTv.abrir(this, pacote); return }   // entrada HDMI/AV (EntradasTv)
    // Congelado sob demanda (OnDemandTv): descongela, espera a tela dele e abre; volta a congelar depois de usado.
    if (OnDemandTv.marked(this, pacote)) { OnDemandTv.open(this, pacote) { if (!todosAbertos) cruz?.naoAbriu() }; contarAbertura(pacote); return }
    val intent = packageManager.getLeanbackLaunchIntentForPackage(pacote)
        ?: packageManager.getLaunchIntentForPackage(pacote)
        ?: run { if (!todosAbertos) cruz?.naoAbriu(); return }   // sem tela para abrir: a cruz avisa, não falha calada
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    contarAbertura(pacote)
}

internal fun MainActivity.prefsApps() = getSharedPreferences(PREFS_CRUZ_APPS, android.content.Context.MODE_PRIVATE)

internal fun MainActivity.contarAbertura(pacote: String) {
    val p = prefsApps()
    val agora = System.currentTimeMillis()
    val (pontos, desde) = lerFrequencia(p.getString("f:$pacote", null))
    p.edit().putString("f:$pacote", "${CruzLogica.somarAbertura(pontos, desde, agora)};$agora").apply()
}

internal fun MainActivity.lerFrequencia(v: String?): Pair<Double, Long> {
    val partes = v?.split(';') ?: return 0.0 to 0L
    return (partes.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (partes.getOrNull(1)?.toLongOrNull() ?: 0L)
}

internal fun MainActivity.frequenciaApps(): Map<String, Double> {
    val agora = System.currentTimeMillis()
    return prefsApps().all.mapNotNull { (k, v) ->
        if (!k.startsWith("f:")) return@mapNotNull null
        val (pontos, desde) = lerFrequencia(v as? String)
        k.substring(2) to CruzLogica.frequenciaAgora(pontos, desde, agora)
    }.toMap()
}

internal fun MainActivity.fixadosCruz(): List<String> = prefsApps().getString("fixados", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

internal fun MainActivity.guardarFixadosCruz(l: List<String>) { prefsApps().edit().putString("fixados", l.joinToString(",")).apply() }

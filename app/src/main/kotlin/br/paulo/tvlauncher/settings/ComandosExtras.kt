package br.paulo.tvlauncher.settings

import br.paulo.tvlauncher.AppIdentity
import android.content.Context
import android.content.Intent
import br.paulo.tvlauncher.Atualizador
import br.paulo.tvlauncher.AutorizaShizukuService
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.CalibracaoActivity
import br.paulo.tvlauncher.CompatibilidadeTv
import br.paulo.tvlauncher.ConferirTvActivity
import br.paulo.tvlauncher.ConhecerProActivity
import br.paulo.tvlauncher.CuidarDeLongeTv
import br.paulo.tvlauncher.ExtrasActivity
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.ManutencaoNoturna
import br.paulo.tvlauncher.ModoHospede
import br.paulo.tvlauncher.ModoSeguro
import br.paulo.tvlauncher.PackageMigration
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.PlacarActivity
import br.paulo.tvlauncher.RecadoVideo
import br.paulo.tvlauncher.RecadoVideoActivity
import br.paulo.tvlauncher.ReceitasActivity
import br.paulo.tvlauncher.ReceitasTv
import br.paulo.tvlauncher.SessaoJunto
import br.paulo.tvlauncher.TesteTeclasActivity
import br.paulo.tvlauncher.TocandoAgoraTv
import br.paulo.tvlauncher.alarm.HoraDormir
import br.paulo.tvlauncher.family.RecadoImagem
import br.paulo.tvlauncher.family.TravaRelogio
import br.paulo.tvlauncher.games.TurmaDiscordTv
import br.paulo.tvlauncher.home.LayoutHome
import br.paulo.tvlauncher.home.MovimentoAdaptativo
import br.paulo.tvlauncher.home.WatchNextTv
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.TeclaFalsa
import br.paulo.tvlauncher.input.TesteTeclas
import br.paulo.tvlauncher.listen.OuvirEstado
import br.paulo.tvlauncher.network.AcordarComLg
import br.paulo.tvlauncher.network.AcordarPc
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.ConsumoRedeTv
import br.paulo.tvlauncher.network.DiarioDns
import br.paulo.tvlauncher.network.EntreTvs
import br.paulo.tvlauncher.network.LgNetcast
import br.paulo.tvlauncher.network.VolumeLg
import br.paulo.tvlauncher.observer.Caches
import br.paulo.tvlauncher.observer.FalhasTv
import br.paulo.tvlauncher.observer.LeakWatch
import br.paulo.tvlauncher.observer.LevezaTv
import br.paulo.tvlauncher.observer.LimpezaMedida
import br.paulo.tvlauncher.observer.MedidasTv
import br.paulo.tvlauncher.observer.ObserverTv
import br.paulo.tvlauncher.observer.SofyDrawable
import br.paulo.tvlauncher.observer.SofyPolicy
import br.paulo.tvlauncher.observer.SofyTv
import br.paulo.tvlauncher.observer.SofyVoice
import br.paulo.tvlauncher.observer.VitaisTv
import br.paulo.tvlauncher.panel.PainelExtras
import br.paulo.tvlauncher.player.IdadesTmdb
import br.paulo.tvlauncher.player.InfoExternaCache
import br.paulo.tvlauncher.player.MatrizAmostrasTv
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.privilege.ExtrasDO
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.privilege.SaudeAcesso
import java.io.File

/**
 * Comandos de adb das ideias de 10/11/2026, todos numa ação só para mexer o mínimo no ControleShizukuReceiver:
 *   am broadcast -a br.paulo.tvlauncher.EXTRAS --es o <comando> [extras] -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
 * (o receiver do adb exige a permissão DUMP: app nenhum chama isto, só o adb.)
 *
 * Comandos:
 *  - teclas_armar          arma o testador de teclas por 2 min (aperte o controle e depois leia com "teclas")
 *  - teclas                JSON com as teclas registradas
 *  - teclas_tela           abre a tela do testador na TV
 *  - calibracao            abre os padrões de calibração
 *  - extras                abre a tela das chaves novas
 *  - diario_dns [--es dia AAAA-MM-DD]   as consultas de DNS mais frequentes do dia
 *  - wol_mac --es mac AA:BB:CC:DD:EE:FF   guarda o MAC do PC (fica só na TV)
 *  - turma_id --es id <ID do servidor> / turma_webhook --es url <webhook> / turma   turma do Discord da tela Jogos
 *  - acordar_pc            manda o pacote mágico
 *  - recado_foto --es arquivo <caminho> [--es texto "..."] [--ei segundos 60]
 *  - cena --es nome <cena>   roda uma cena (ver Cenas.kt)
 *  - cena_criar --es nome <cena> --es acoes '{"acoes":[...]}'   cria; devolve o token do link (só aparece agora)
 *  - cena_apagar --es nome <cena> · cenas (lista os nomes)
 *  - estado                resumo das chaves
 * 2ª leva (10/11/2026):
 *  - caches                limpa o cache de todos os apps
 *  - consumo [--ei dias N]  internet por app desde a meia-noite (ou N dias)
 *  - tocando · tocando_acao --es acao alternar|proxima|anterior · ouvir_parar (a nossa rádio, Ouvir)
 *  - chave --es nome <CHAVE> --ez ligado true|false   (pelo adb não pede PIN: o adb já é o dono)
 *  - dormir_apps --es apps "pacote1,pacote2"
 *  - recado_link           cria (ou troca) o link do recado; devolve o token (só aparece agora)
 * Saúde (10/11/2026):
 *  - saude                 JSON da saúde do acesso remoto (SaudeAcesso.json)
 *  - manutencao            roda a manutenção noturna agora, em segundo plano (relatório depois em "saude")
 *  - manutencao_resumo     mostra o resumo da última manutenção (só se houver algo e a HOME estiver na frente)
 * Versões (14/11/2026):
 *  - canal --es canal teste|normal   teste = esta TV instala também os pré-lançamentos (ver CanalAtualizacao)
 * Conferência (14/11/2026):
 *  - tecla --es tecla HOME [--el ms 1500]   entrega uma tecla falsa ao funil das teclas da acessibilidade (TeclaFalsa).
 *    Aceita o nome sem "KEYCODE_" ou o número. Ex.: HOME segurado = --es tecla HOME --el ms 1500.
 *  - tela --es classe <NomeActivity>   abre qualquer tela deste app (teste de telas; o am start só abre as exportadas)
 *  - roteiro               respostas do roteiro "Conferir na TV" desta versão (JSON; ver RoteiroConferencia)
 * Compatibilidade (15/11/2026, degrau 1 contra o Projectivy):
 *  - compat                JSON do aparelho para a página "Funciona no seu aparelho?" (Compatibilidade.kt)
 *  - compat_texto          o mesmo em uma linha por teste
 *  - planos / planos_texto   tela "Conhecer o Pro" / plano atual e o que cada recurso exige (Planos.kt)
 *  - visual                link com o código do visual desta TV (tema por QR, VisualCodigo)
 *  - visual_aplicar --es codigo <código ou link>   aplica um visual recebido
 *  - watchnext             o Watch Next dos outros apps é legível neste aparelho? (WatchNextTv)
 * Sofy (20/11/2026, SofyDrawable):
 *  - sofy [--es expr HAPPY|QUESTION|…] [--es balao aspas|musica]   prévia: a Sofy aparece na pílula com a expressão
 *    e o movimento dela (sem histórico; só para conferir o desenho na TV). Sem expr: lista as expressões.
 *    Com --es texto "...": ela também fala esse texto (boca mexendo) mesmo com a chave desligada.
 *  - sofy_voz [--ez ligar true|false]   mede a voz da TV (motor, língua, vozes) e mostra/troca a chave "Sofy fala".
 *  - sofy_olhar            passa a fila de recados do Observador pela regra agora (SofyTv.present)
 * Volume da LG pela rede (14/11/2026, ideia 49; ver VolumeLg). Demoram (rede): o adb espera a resposta.
 *  - lg_procurar --es ip <qualquer IP da casa>   lista as LG da mesma rede /24
 *  - lg_pedir_codigo --es ip <IP da LG>         a LG mostra o código na tela dela
 *  - lg_parear --es ip <IP da LG> --es codigo <números>   guarda (só nesta TV) e passa a usar a LG no volume
 *  - lg_estado · lg_esquecer · lg_tecla --es tecla subir|descer|mudo [--ei vezes N]
 *  - lg_alarme --ez ligado true|false   o despertador sobe a LG (até 30)? Desligado por padrão.
 *  - lg_desligar --ez ligado true|false   o timer de sono desliga a LG pela rede? Ligado por padrão.
 *  - lg_acordar [--ez ligado true|false]  a LG ligou na entrada do Chromecast: acordar o Chromecast? Ligado por padrão.
 *                                          Sem "ligado": mostra o estado e a entrada que a LG mostra agora.
 *  - lg_acordar --es entrada HDMI1         diz em que entrada da LG está o Chromecast (normalmente aprende sozinho).
 */
object ComandosExtras {
    fun tratar(ctx: Context, i: Intent): String = when (i.getStringExtra("o")) {
        "sofy" -> previaSofy(ctx, i)
        "sofy_voz" -> vozSofy(ctx, i)
        "sofy_olhar" -> { SofyTv.present(ctx); SofyTv.now(ctx.applicationContext).let { "olhou; falas nas últimas 24 h: ${it.shownAt.size}" } }
        "teclas_armar" -> { TesteTeclas.armar(); "armado por ${TesteTeclas.JANELA_MS / 1000} s" }
        "teclas" -> TesteTeclas.json().toString()
        "teclas_tela" -> abrir(ctx, TesteTeclasActivity::class.java)
        "calibracao" -> abrir(ctx, CalibracaoActivity::class.java)
        "extras" -> abrir(ctx, ExtrasActivity::class.java)
        "tela" -> abrirTela(ctx, i.getStringExtra("classe"))
        "diario_dns" -> DiarioDns.resumo(ctx, i.getStringExtra("dia") ?: DiarioDns.diaDe(System.currentTimeMillis()))
        "wol_mac" -> if (AcordarPc.guardarMac(ctx, i.getStringExtra("mac") ?: "")) "ok" else "MAC inválido"
        // Turma do Discord na tela Jogos (17/11/2026): ID do servidor e webhook do "Chamar a turma" ("" apaga).
        "turma_id" -> if (TurmaDiscordTv.guardarServidor(ctx, i.getStringExtra("id") ?: "")) "ok" else "ID inválido (17 a 20 números)"
        "turma_webhook" -> if (TurmaDiscordTv.guardarWebhook(ctx, i.getStringExtra("url") ?: "")) "ok" else "webhook inválido (só https://discord.com/api/webhooks/...)"
        "turma" -> "servidor=" + TurmaDiscordTv.servidor(ctx).ifEmpty { "-" } + " webhook=" + (if (TurmaDiscordTv.temWebhook(ctx)) "sim" else "não")
        "acordar_pc" -> if (AcordarPc.acordar(ctx)) "enviado" else "falhou (MAC guardado? rede?)"
        "recado_foto" -> {
            val f = File(i.getStringExtra("arquivo") ?: "")
            if (!f.isFile || f.length() > RecadoImagem.MAX_BYTES) "arquivo ausente ou maior que 4 MB"
            else RecadoImagem.mostrar(ctx, i.getStringExtra("texto"), f.readBytes(),
                if (i.hasExtra("segundos")) i.getIntExtra("segundos", 60) else null)?.name ?: "imagem ou texto inválido"
        }
        "cena" -> Cenas.rodar(ctx, i.getStringExtra("nome") ?: "", "adb")
        "cena_criar" -> Cenas.criar(ctx, i.getStringExtra("nome") ?: "", i.getStringExtra("acoes") ?: "", Cenas.appsInstalados(ctx))
        "cena_apagar" -> if (Cenas.apagar(ctx, i.getStringExtra("nome") ?: "")) "apagada" else "não existe"
        "cenas" -> Cenas.lista(ctx).joinToString("\n") { it.nome }.ifEmpty { "nenhuma" }
        "caches" -> Caches.limpar(ctx)
        "fichas" -> InfoExternaCache.resumoAdb(ctx, i.getIntExtra("n", 15))   // fichas de filme/série guardadas (Trakt/TMDB), só leitura
        "saude" -> SaudeAcesso.json(ctx).toString()   // estado do shell próprio e do túnel + última manutenção
        // Roda a manutenção noturna agora. Numa thread própria: pode passar de 90 s (shell + limpeza de cache), mais que o
        // limite de um broadcast; o relatório sai depois por "saude" (campo "manutencao").
        "manutencao" -> { val app = ctx.applicationContext; Fundo.rodar("Manutencao-adb") { ManutencaoNoturna.rodar(app) }; "iniciado; ver depois com saude" }
        // Modo infantil por idade: quantos títulos já têm classificação; "--ez rodar true" classifica agora (a mesma
        // rotina da madrugada, numa thread própria; o fim sai no log TvLauncher.Idades).
        "idades" -> {
            val app = ctx.applicationContext
            if (i.getBooleanExtra("rodar", false)) Fundo.rodar("Idades-adb") { IdadesTmdb.catalogo(app) }
            val (f, s) = IdadesTmdb.contagem(app)
            "filmes=$f series=$s" + if (i.getBooleanExtra("rodar", false)) " (classificando)" else ""
        }
        "manutencao_resumo" -> { ManutencaoNoturna.talvezMostrarResumo(ctx); "ok" }   // mostra o resumo (se tiver algo e a HOME na frente)
        "consumo" -> ConsumoRedeTv.texto(ctx, i.getIntExtra("dias", 1))
        "tocando" -> TocandoAgoraTv.texto(ctx)
        "tocando_acao" -> if (TocandoAgoraTv.controlar(ctx, i.getStringExtra("acao") ?: "")) "ok" else "nada tocando"
        // Ouvir (17/11/2026): o estado do nosso som aparece em "tocando"; aqui, parar de vez (alternar = tocando_acao).
        "ouvir_parar" -> if (OuvirEstado.ler(ctx) != null && OuvirEstado.comandar(ctx, OuvirEstado.PARAR)) "ok" else "nada tocando"
        "chave" -> Extras.Chave.values().firstOrNull { it.name == i.getStringExtra("nome") }?.let { c ->
            Extras.definir(ctx, c, i.getBooleanExtra("ligado", false))
            when (c) {
                Extras.Chave.FILTRO_NOTURNO -> android.os.Handler(android.os.Looper.getMainLooper()).post { FiltroNoturno.avaliar() }
                Extras.Chave.JANELA_OTA, Extras.Chave.TRAVAR_CRITICOS, Extras.Chave.DIARIO_DNS -> ExtrasDO.aplicar(ctx)
                else -> PainelExtras.aoMudar(ctx.applicationContext, c)
            }
            "${c.name}=${Extras.ligado(ctx, c)}"
        } ?: "chave desconhecida"
        "dormir_apps" -> HoraDormir.lerPermitidos(i.getStringExtra("apps")).joinToString(",").also {
            Extras.definirTexto(ctx, HoraDormir.CHAVE_PERMITIDOS, it) }.ifEmpty { "lista vazia: só os essenciais abrem" }
        "recado_link" -> PainelExtras.novoLinkRecado(ctx)
        "tecla" -> teclaFalsa(i)
        // Barra de cima da cruz (ilha, recua, leve, relogio, hoje, vidro) e o movimento dos ícones (MovimentoAdaptativo).
        "barra" -> (i.getStringExtra("nome") ?: "").let { n ->
            LayoutHome.BarraCruz.entries.firstOrNull { it.name.equals(n, true) }?.let { LayoutHome.definirBarraCruz(ctx, it); "barra=${it.name} (aperte HOME)" }
                ?: "barra=${LayoutHome.barraCruz(ctx)}; opções: ${LayoutHome.BarraCruz.entries.joinToString { it.name.lowercase() }}"
        }
        "movimento" -> MovimentoAdaptativo.comando(ctx, i.getStringExtra("nivel"))
        // Casa (Home Assistant): sem endereço mostra o atual; com endereço troca só ele (o token fica).
        "casa_procurar" -> CasaHA.procurarNaRede(ctx)   // procura o Home Assistant na rede (HomeAssistantFinder), ~6 s
        "casa_endereco" -> i.getStringExtra("endereco").let { e ->
            if (e.isNullOrBlank()) "casa=${CasaHA.endereco(ctx) ?: "-"} configurado=${CasaHA.configurado(ctx)}"
            else CasaHA.trocarEndereco(ctx, e)?.let { "não trocou: $it" } ?: "casa=${CasaHA.endereco(ctx)}"
        }
        // Tela inicial em cruz (17/11/2026): saída de emergência para a HOME antiga até a Cruz passar no aceite na TV.
        // Cuidar de longe (ideias 77-79): estado, rodar o relógio agora, abrir a pergunta de consentimento, teste de envio.
        "cuidar" -> CuidarDeLongeTv.texto(ctx)
        "cuidar_rodar" -> { val app = ctx.applicationContext; Fundo.rodar("cuidar-adb") { CuidarDeLongeTv.rodar(app) }; "rodando; ver depois com cuidar" }
        "cuidar_perguntar" -> CuidarDeLongeTv.pedirConsentimento(ctx)?.let { "falta: ${it.name}" } ?: "pergunta aberta na TV"
        "cuidar_desligar" -> { CuidarDeLongeTv.retirar(ctx, "adb"); "consentimento retirado" }
        "roteiro" -> ConferirTvActivity.relatorio(ctx)
        "compat" -> CompatibilidadeTv.json(ctx).toString(2)   // relatório da página "Funciona no seu aparelho?"
        "compat_texto" -> CompatibilidadeTv.texto(ctx)
        // Modo de segurança da HOME (degrau 2): ligar à mão para ver a HOME mínima; sair; estado.
        "modo_seguro_testar" -> { ModoSeguro.ligarParaTeste(ctx); "modo de segurança ligado: aperte HOME" }
        "modo_seguro_sair" -> { ModoSeguro.sair(ctx); "modo de segurança desligado" }
        "leveza" -> LevezaTv.json(ctx).toString()   // degrau 2: memória e tempo da HOME (volte à HOME umas vezes antes)
        "observer" -> ObserverTv.report(ctx)
        // Congelar sob demanda (OnDemandTv) e a prova do Observador: estado; marcar/desmarcar um app; congelar com prova (teste da fase 3).
        "ondemand" -> OnDemandTv.report(ctx)
        "ondemand_mark" -> i.getStringExtra("pacote")?.let { OnDemandTv.mark(ctx, it) ?: "ok: $it sob demanda" } ?: "falta --es pacote"
        "ondemand_unmark" -> i.getStringExtra("pacote")?.let { OnDemandTv.unmark(ctx, it) ?: "ok: $it descongelado" } ?: "falta --es pacote"
        "freeze_trial" -> i.getStringExtra("pacote")?.let { p ->
            if (PainelApps.protegido(ctx, p)) "protegido" else {
                val antes = ObserverTv.beforeFreeze(ctx)
                if (PrivilegioDO.ocultar(ctx, p, true)) { ObserverTv.startTrial(ctx, p, antes); "congelado; prova aberta: $antes" } else "não congelou"
            }
        } ?: "falta --es pacote"   // Observador: sinais, o que está fora do normal, recados e registro
        "vazamentos" -> LeakWatch.relatorio()   // telas fechadas que continuam na memória (LeakWatch.kt)
        "vitais" -> VitaisTv.textoAdb(ctx)   // degrau 2: quedas e travadas do launcher por dia (VitaisLocais.kt)
        // Elevação, onda 0: placar dos 7 pilares (JSON + séries cruas); a tela "Como esta TV está indo"; antes e depois da limpeza.
        "placar" -> MedidasTv.textoAdb(ctx)
        "falhas" -> FalhasTv.textoAdb(ctx)   // elevação 2.3: estados de falha de agora (frase + botão)
        "placar_tela" -> abrir(ctx, PlacarActivity::class.java)
        "limpeza_medir" -> { Fundo.rodar("limpeza-medir") { LimpezaMedida.medirAgora(ctx) }; "medindo (abre os 3 apps mais usados)" }
        // Bancada da madrugada (elevação 2.2, scripts/bancada.py): só roda em aparelho marcado. NUNCA marcar a Sala.
        "bancada" -> if (MedidasTv.bancada(ctx)) "sim" else "nao"
        // Elevação 6.1: matriz "toca tudo" (scripts/amostras.py). Só na bancada; base = a pasta servida pelo PC.
        "matriz" -> MatrizAmostrasTv.iniciar(ctx, i.getStringExtra("base"))
        "matriz_resultado" -> MatrizAmostrasTv.resultado(ctx)
        "bancada_marcar" -> { MedidasTv.marcarBancada(ctx, true); "marcado como bancada" }
        "bancada_desmarcar" -> { MedidasTv.marcarBancada(ctx, false); "desmarcado" }
        "modo_seguro" -> if (ModoSeguro.ativo(ctx)) "ligado" else "desligado"
        // Modo hóspede (degrau 2). O adb já é autenticado: desligar aqui não pede o PIN.
        // Degrau 4: receitas prontas (Receitas.kt). receitas_aplicar --es receita VO|CRIANCAS|GAMER|ALUGUEL (adb já é do dono: sem PIN).
        "receitas" -> ReceitasTv.estado(ctx)
        "receitas_tela" -> abrir(ctx, ReceitasActivity::class.java)
        "receitas_aplicar" -> Receitas.dePalavra(i.getStringExtra("receita"))?.let { r ->
            ReceitasTv.aplicar(ctx, r).joinToString(", ") { "${it.first}=${it.second}" }.ifEmpty { "nada mudou" }
        } ?: "receita desconhecida (VO, CRIANCAS, GAMER, ALUGUEL)"
        "receitas_desfazer" -> ReceitasTv.desfazer(ctx).joinToString(", ") { "${it.first}=${it.second}" }.ifEmpty { "nada a desfazer" }
        // Degrau 4: recado em vídeo. videos_aceitar --ez sim true|false liga o opt-in (adb já é do dono).
        "videos" -> RecadoVideo.estado(ctx)
        "videos_tela" -> abrir(ctx, RecadoVideoActivity::class.java)
        "entre_tvs" -> "ligado=${EntreTvs.ligado(ctx)} eu=${EntreTvs.meuHost(ctx)} outras=${EntreTvs.outras(ctx)} junto=${SessaoJunto.texto()}"
        "entre_tvs_ligar" -> { EntreTvs.definir(ctx, i.getBooleanExtra("sim", false)); "entre_tvs=${i.getBooleanExtra("sim", false)}" }
        "junto_sair" -> { val app = ctx.applicationContext; Fundo.rodar("junto-adb") { SessaoJunto.encerrar(app, avisarOutra = true) }; "saindo" }
        "videos_aceitar" -> { RecadoVideo.definirAceita(ctx, i.getBooleanExtra("sim", false)); RecadoVideo.estado(ctx) }
        "hospede" -> "ativo=${ModoHospede.ativo(ctx)} saida=${ModoHospede.textoSaida(ctx)} apps=${ModoHospede.paraLimpar(ctx)} ultima=${ModoHospede.ultimaLimpeza(ctx)}"
        "hospede_ligar" -> if (ModoHospede.ligar(ctx)) "modo hóspede ligado" else "sem PIN do painel: defina o PIN antes de ligar o modo hóspede"
        "hospede_desligar" -> { ModoHospede.desligar(ctx); "modo hóspede desligado" }
        "hospede_encerrar" -> { val app = ctx.applicationContext; Fundo.rodar("hospede-adb") { ModoHospede.encerrarEstadia(app) }; "limpando; ver depois com hospede" }
        "planos" -> abrir(ctx, ConhecerProActivity::class.java)   // tela "Conhecer o Pro" (venda ainda desligada)
        "planos_texto" -> Planos.texto(Planos.atual(ctx))
        "visual" -> VisualCodigoTv.link(ctx)   // tema por QR: o link com o código do visual desta TV
        "visual_aplicar" -> VisualCodigoTv.aplicar(ctx, i.getStringExtra("codigo") ?: "")?.joinToString("\n") ?: "código inválido"
        "watchnext" -> WatchNextTv.diagnostico(ctx).toString(2)   // dá para ler o "continuar" dos outros apps aqui?
        "lg_procurar" -> VolumeLg.procurar(i.getStringExtra("ip") ?: "").joinToString("\n") { (ip, m) -> "$ip ${m.nome} ${m.modelo}" }
            .ifEmpty { "nenhuma LG respondeu (ligada? mesmo /24?)" }
        "lg_pedir_codigo" -> VolumeLg.pedirCodigo(i.getStringExtra("ip") ?: "")
        "lg_parear" -> VolumeLg.parear(ctx, i.getStringExtra("ip") ?: "", i.getStringExtra("codigo") ?: "")
        "lg_estado" -> VolumeLg.estado(ctx)
        "lg_alarme" -> if (i.hasExtra("ligado")) { VolumeLg.definirAlarme(ctx, i.getBooleanExtra("ligado", false)); "alarme_lg=${VolumeLg.alarmeLigado(ctx)}" }
            else "alarme_lg=${VolumeLg.alarmeLigado(ctx)} (mude com --ez ligado true|false)"
        "lg_desligar" -> if (i.hasExtra("ligado")) { VolumeLg.definirDesligarJunto(ctx, i.getBooleanExtra("ligado", true)); "desligar_lg=${VolumeLg.desligarJunto(ctx)}" }
            else "desligar_lg=${VolumeLg.desligarJunto(ctx)} (mude com --ez ligado true|false)"
        "lg_acordar" -> if (i.hasExtra("entrada")) {
                val e = i.getStringExtra("entrada").orEmpty().trim()
                if (Regex("HDMI[0-9]", RegexOption.IGNORE_CASE).matches(e)) { VolumeLg.aprenderEntrada(ctx, e.uppercase()); "entrada do Chromecast = ${e.uppercase()}" }
                else "entrada: HDMI1, HDMI2..."
            } else if (i.hasExtra("ligado")) { VolumeLg.definirAcordarJunto(ctx, i.getBooleanExtra("ligado", true)); "acordar_com_lg=${VolumeLg.acordarJunto(ctx)}" }
            else "acordar_com_lg=${VolumeLg.acordarJunto(ctx)}; ${AcordarComLg.estado(ctx)}"
        "lg_esquecer" -> { VolumeLg.esquecer(ctx); "esquecida" }
        "lg_tecla" -> when (i.getStringExtra("tecla")) {
            "subir" -> LgNetcast.TECLA_SUBIR; "descer" -> LgNetcast.TECLA_DESCER; "mudo" -> LgNetcast.TECLA_MUDO; else -> null
        }?.let { if (VolumeLg.tecla(ctx, it, i.getIntExtra("vezes", 1))) "ok" else "a LG não aceitou (pareada? ligada?)" }
            ?: "tecla: subir, descer ou mudo"
        // Troca de pacote (19/11/2026, PackageMigration.kt). "migration" = estado. "migration_start" (no antigo) e
        // "migration_rollback" (no novo) rodam em fundo: o resultado aparece no "migration" uns segundos depois.
        // "migration_remove_legacy" (no novo) DESINSTALA o antigo: irreversível, some o caminho de volta.
        "migration" -> PackageMigration.status(ctx)
        "migration_start" -> { val app = ctx.applicationContext; Fundo.rodar("migracao") { PackageMigration.note(app, PackageMigration.start(app, manual = true)) }; "started (see: migration)" }
        "migration_rollback" -> { val app = ctx.applicationContext; Fundo.rodar("migracao-volta") { PackageMigration.note(app, PackageMigration.rollback(app)) }; "started (see: migration)" }
        "migration_remove_legacy" -> PackageMigration.removeLegacy(ctx)
        "migration_auto" -> { PackageMigration.setAuto(ctx, i.getBooleanExtra("ligado", false)); PackageMigration.status(ctx) }
        "canal" -> when (i.getStringExtra("canal")) {
            "teste" -> { Atualizador.definirCanalTeste(ctx, true); "canal=teste" }
            "normal" -> { Atualizador.definirCanalTeste(ctx, false); "canal=normal" }
            else -> "canal=${if (Atualizador.canalTeste(ctx)) "teste" else "normal"} (mude com --es canal teste|normal)"
        }
        "estado" -> Extras.Chave.values().joinToString("\n") { "${it.name}=${Extras.ligado(ctx, it)}" } +
            "\ndiario_dns_erro=${DiarioDns.erro(ctx)}" + "\nrelogio_travado=${TravaRelogio.travado(ctx)}" +
            "\ncanal=${if (Atualizador.canalTeste(ctx)) "teste" else "normal"}"
        else -> "comando desconhecido; ver ComandosExtras.kt"
    }

    /** Tecla falsa (63): agenda o plano na thread principal, onde o sistema entrega as teclas de verdade. */
    private fun teclaFalsa(i: Intent): String {
        val servico = AutorizaShizukuService.instancia ?: return "acessibilidade desligada: o funil das teclas não está rodando"
        val nome = i.getStringExtra("tecla")?.trim().orEmpty()
        val codigo = nome.toIntOrNull() ?: android.view.KeyEvent.keyCodeFromString("KEYCODE_" + nome.uppercase().removePrefix("KEYCODE_"))
        if (codigo <= 0) return "tecla desconhecida: $nome"
        val ms = TeclaFalsa.duracao(if (i.hasExtra("ms")) i.getLongExtra("ms", TeclaFalsa.PADRAO_MS) else null)
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        val inicio = android.os.SystemClock.uptimeMillis()
        for (ev in TeclaFalsa.plano(ms)) principal.postAtTime({
            val agora = android.os.SystemClock.uptimeMillis()
            val acao = if (ev.desce) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
            try { servico.tecla(android.view.KeyEvent(inicio, agora, acao, codigo, ev.repeticao)) }
            catch (e: Exception) { android.util.Log.w("TvLauncher.Tecla", "tecla falsa falhou: $e") }
        }, inicio + ev.emMs)
        return "${android.view.KeyEvent.keyCodeToString(codigo)} por $ms ms"
    }

    /**
     * Teste de telas pelo adb (21/11/2026, refatoração C). O `am start` do shell só abre telas exportadas (2 de 44);
     * este comando abre qualquer tela DESTE app por dentro do processo. Só nome simples de classe e só o que o
     * manifesto declara como tela nossa: nada de fora, nada de caminho.
     */
    private fun abrirTela(ctx: Context, classe: String?): String {
        val nome = classe?.takeIf { Regex("[A-Za-z0-9_]+").matches(it) } ?: return "falta --es classe <NomeActivity>"
        val comp = android.content.ComponentName(ctx.packageName, "${AppIdentity.NAMESPACE}.$nome")
        runCatching { ctx.packageManager.getActivityInfo(comp, 0) }.getOrNull() ?: return "não é tela deste app: $nome"
        ctx.startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "aberto"
    }

    private fun abrir(ctx: Context, c: Class<*>): String {
        ctx.startActivity(Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "aberto"
    }

    private fun previaSofy(ctx: Context, i: Intent): String {
        val nome = i.getStringExtra("expr") ?: return SofyPolicy.Expression.entries.joinToString(" ") { it.name }
        val e = SofyPolicy.Expression.entries.firstOrNull { it.name.equals(nome, true) } ?: return "expressão desconhecida: $nome"
        val balao = when (i.getStringExtra("balao")) { "aspas" -> SofyDrawable.QUOTE; "musica" -> SofyDrawable.MUSIC; else -> null }
        val face = SofyDrawable(ctx.applicationContext, e, balao)
        val fala = i.getStringExtra("texto")
        val r = AvisoTv.mostrar(ctx, "Sofy", fala ?: "${e.name.lowercase()}  ${e.eyes} ${e.mouth}".trim(),
            if (fala != null) SofyPolicy.secondsOnScreen(fala) + 2 else 6, false, false, icone = face, historico = false)
        if (fala != null && r == AvisoTv.Resultado.MOSTRADO) SofyVoice.say(ctx, SofyPolicy.Voice.SPEAK, fala, face)
        return "${e.name}: $r"
    }

    private fun vozSofy(ctx: Context, i: Intent): String {
        if (i.hasExtra("ligar")) SofyVoice.setVoiceOn(ctx, i.getBooleanExtra("ligar", false))
        val fim = java.util.concurrent.CountDownLatch(1)
        var r = org.json.JSONObject()
        SofyVoice.status(ctx) { r = it; fim.countDown() }
        if (!fim.await(8, java.util.concurrent.TimeUnit.SECONDS)) r.put("error", "o motor de voz não respondeu em 8 s")
        return r.toString()
    }
}

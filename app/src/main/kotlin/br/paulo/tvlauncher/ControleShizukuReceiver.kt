package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Comandos do launcher por adb (o nome é histórico: até 12/11/2026 controlava o servidor do Shizuku embutido, que saiu).
 *
 *   br.paulo.tvlauncher.shizuku.INICIAR  — reconecta o shell próprio e roda a autocura (o antigo "sobe o servidor")
 *   br.paulo.tvlauncher.shizuku.PARAR    — não faz mais nada (não há servidor para parar); fica para não quebrar scripts
 *   br.paulo.tvlauncher.shizuku.ESTADO   — "shell ok" ou "sem shell" (resultData do broadcast)
 *
 * Só o [ControleShizukuAdbReceiver] está no manifest, protegido por DUMP (só o adb e o sistema têm). O receiver dos
 * apps (permissão API_V23 do Shizuku) saiu com o servidor; a tabela [AcoesControle] continua como segunda trava.
 *
 * Teste por adb:  am broadcast -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver -a br.paulo.tvlauncher.shizuku.ESTADO
 */
open class ControleShizukuReceiver : BroadcastReceiver() {
    companion object {
        const val INICIAR = "br.paulo.tvlauncher.shizuku.INICIAR"
        const val PARAR = "br.paulo.tvlauncher.shizuku.PARAR"
        const val ESTADO = "br.paulo.tvlauncher.shizuku.ESTADO"
        const val ROTULOS = "br.paulo.tvlauncher.ROTULOS"   // nomes dos apps para o painel (só adb)
        const val OCULTAR = "br.paulo.tvlauncher.OCULTAR"   // ocultar/reexibir um app pelo DO (só adb)
        const val REINICIAR = "br.paulo.tvlauncher.REINICIAR"   // reiniciar o processo do launcher (só adb)
        const val PAINEL_PIN = "br.paulo.tvlauncher.PAINEL_PIN"   // definir o PIN do painel e ligá-lo (só adb, para testes)
        const val RECADO = "br.paulo.tvlauncher.RECADO"   // recado na tela da TV (só adb; o painel usa /api/recado)
        const val CHUVA = "br.paulo.tvlauncher.CHUVA"     // verificar o aviso de chuva agora, ou simular (só adb, testes)
        const val LOCAL_CLIMA = "br.paulo.tvlauncher.LOCAL_CLIMA"   // lugar do clima (só adb; o painel usa /api/clima)
        const val ERROS = "br.paulo.tvlauncher.ERROS"           // listar o registro local de erros (só adb)
        const val TESTE_ERRO = "br.paulo.tvlauncher.TESTE_ERRO" // gravar um erro de teste no registro (só adb)
        const val ROTINA = "br.paulo.tvlauncher.ROTINA"         // rodar uma rotina agora / listar (só adb)
        const val DEBLOAT = "br.paulo.tvlauncher.DEBLOAT"       // candidatos da limpeza e o estado deles (só adb)
        const val IA_TESTE = "br.paulo.tvlauncher.IA_TESTE"     // confirmação de ações do catálogo, sem MCP (só adb)
        const val IA_ESTADO = "br.paulo.tvlauncher.IA_ESTADO"   // situação do último pedido (só adb)
        const val AUTO_DESLIGAR = "br.paulo.tvlauncher.AUTO_DESLIGAR"   // desligar sozinha: ver/definir/testar (só adb)
        const val PLAYER_FONTE = "br.paulo.tvlauncher.PLAYER_FONTE"     // conta do player: cadastrar/apagar (só adb)
        const val PLAYER_ESTADO = "br.paulo.tvlauncher.PLAYER_ESTADO"   // contas e canais do player, sem senha (só adb)
        const val HOME_TESTE = "br.paulo.tvlauncher.HOME_TESTE"   // ligar/desligar partes da HOME para medir a GPU (só adb)
        const val UNIFI = "br.paulo.tvlauncher.UNIFI"     // verificar aparelho novo no roteador agora (só adb, testes)
        const val EXTRAS = "br.paulo.tvlauncher.EXTRAS"   // comandos das ideias de 10/11/2026 (só adb; ver ComandosExtras.kt)

        /** O shell próprio responde agora? Roda um `echo` na fila do guardião (até 8 s: o broadcast tem ~10 s). */
        fun estado(ctx: Context): String = if (GuardiaoShizuku.comando(ctx, "echo ok", 8_000)?.contains("ok") == true) "shell ok" else "sem shell"

        /** Reconecta o shell próprio e roda a autocura (o que faltar de permissão). Usado pelo INICIAR. */
        fun ligar(ctx: Context) = GuardiaoShizuku.garantir(ctx, "INICIAR", mesmoSemFalta = true)
    }

    override fun onReceive(recebido: Context, intent: Intent) {
        val ctx = recebido.applicationContext
        ServidorShizuku.anotar(ctx, "[controle] ${intent.action}")
        // Auditoria 10/11/2026: o receiver dos apps do Shizuku (API_V23) também recebia OCULTAR, REINICIAR, ROTINA etc.,
        // porque um intent EXPLÍCITO (-n) ignora o filtro de ação do manifest. Agora só passa o que a tabela permite.
        if (!AcoesControle.permitida(intent.action, viaAdb = this is ControleShizukuAdbReceiver)) {
            ServidorShizuku.anotar(ctx, "[controle] recusado (só pelo adb): ${intent.action}")
            return
        }
        when (intent.action) {
            INICIAR -> {
                ligar(ctx)
                resultData = "iniciando"
            }
            PARAR -> resultData = "nada a parar: o servidor do Shizuku embutido saiu em 12/11/2026"
            ESTADO -> {
                val pendente = goAsync()   // o echo passa pela fila do adb: fora da thread principal
                Fundo.rodar { try { pendente.resultData = estado(ctx) } finally { pendente.finish() } }
            }
            // Só pelo adb: define o PIN do painel no navegador e o liga, sem precisar do controle remoto (testes).
            // Ex.: am broadcast -a br.paulo.tvlauncher.PAINEL_PIN --es pin 123456 -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            // O "is" barra o receiver dos apps do Shizuku: um intent EXPLÍCITO ignora o filtro de ação do manifest.
            PAINEL_PIN -> if (this is ControleShizukuAdbReceiver) {
                val pin = intent.getStringExtra("pin")
                if (pin == null || !PinSeguro.valido(pin)) {
                    resultData = "pin inválido (6 a 12 dígitos)"
                } else {
                    val pendente = goAsync()   // PBKDF2 demora: fora da thread principal, segurando o broadcast
                    Fundo.rodar {
                        try {
                            PainelArmazem.definirPin(ctx, pin)
                            PainelArmazem.definirLigado(ctx, true)
                            PainelWeb.recomecar(ctx)
                            Thread.sleep(500)   // a porta abre numa thread à parte
                            pendente.resultData = "ok ligado=${PainelWeb.ligado()} endereco=${PainelWeb.endereco()}"
                        } finally { pendente.finish() }
                    }
                }
            }
            // Só pelo adb: testa a tela de confirmação e a execução do catálogo (ComandosIa). A "resposta" é o JSON das
            // ações, que passa pela mesma conferência que o servidor MCP vai usar. Ex.:
            //   am broadcast -a br.paulo.tvlauncher.IA_TESTE --es frase "abre a previsão"
            //     --es resposta '{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}],"resposta":"ok"}'
            //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            IA_TESTE -> if (this is ControleShizukuAdbReceiver) {
                val r = intent.getStringExtra("resposta")
                resultData = if (r == null) "falta --es resposta" else PedidoIa.propor(ctx, intent.getStringExtra("frase") ?: "teste", "adb", r).let {
                    "id=${it.id} situacao=${it.situacao} ${it.mensagem}"
                }
            }
            IA_ESTADO -> if (this is ControleShizukuAdbReceiver) {
                resultData = "ultimo=${PedidoIa.ultimo()?.json() ?: "nenhum"}"
            }
            // Só chega pelo receiver do adb (o filtro de ROTULOS está só nele): o painel do PC pergunta o nome real
            // dos apps, que o adb não sabe dizer. Resposta: JSON {pacote: nome}.
            ROTULOS -> resultData = try {
                val pm = ctx.packageManager
                org.json.JSONObject().apply {
                    for (ai in pm.getInstalledApplications(0)) put(ai.packageName, pm.getApplicationLabel(ai).toString())
                }.toString()
            } catch (e: Exception) { "{}" }
            // Só pelo adb (filtro só no receiver DUMP): ocultar/reexibir um app pelo Device Owner, para testar e desfazer
            // sem rebuild. Ex.: am broadcast -a br.paulo.tvlauncher.OCULTAR --es pacote <pkg> --ez oculto false
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            OCULTAR -> {
                val pacote = intent.getStringExtra("pacote")
                val oculto = intent.getBooleanExtra("oculto", true)
                if (pacote.isNullOrBlank()) resultData = "falta --es pacote"
                else {
                    // Sem Device Owner o ocultar vai pelo shell e pode levar até ~20 s: fora da thread principal, com
                    // goAsync (o broadcast espera a resposta sem travar a tela e sem estourar o prazo de 10 s).
                    val pendente = goAsync()
                    Fundo.rodar {
                        try {
                            pendente.resultData = "mudou=${PrivilegioDO.ocultar(ctx, pacote, oculto)} " +
                                "oculto_agora=${PrivilegioDO.estaOculto(ctx, pacote)}"
                        } finally { pendente.finish() }
                    }
                }
            }
            // Só pelo adb: reinicia o processo do launcher (04/11/2026). O shell não consegue matá-lo (force-stop, am kill
            // e am crash não derrubam: ele segura o serviço de acessibilidade e é Device Owner), e o código recém-compilado
            // (cmd package compile) só vale num processo novo. O sistema sobe a HOME e religa a acessibilidade sozinho.
            // Só pelo adb. Ex.: am broadcast -a br.paulo.tvlauncher.RECADO --es texto "Jantar pronto" --ei segundos 30
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            RECADO -> if (this is ControleShizukuAdbReceiver) {
                val t = Recado.limpar(intent.getStringExtra("texto"))
                resultData = if (t == null) "texto vazio ou com mais de ${Recado.MAX_CARACTERES} caracteres"
                else AvisoTv.mostrar(ctx, "Recado", t, Recado.duracao(intent.getIntExtra("segundos", 60)),
                    intent.getBooleanExtra("som", true), guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO).name.lowercase()
            }
            // Só pelo adb: o endereço do clima (LocalClima.kt) pelo CEP e o número. Sem --es cep, apaga o endereço.
            // Ex.: am broadcast -a br.paulo.tvlauncher.LOCAL_CLIMA --es cep 14015060 --es numero 100
            //   -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
            LOCAL_CLIMA -> if (this is ControleShizukuAdbReceiver) {
                val cep = intent.getStringExtra("cep")
                if (cep.isNullOrBlank()) { LocalClima.limpar(ctx); resultData = "endereço apagado" }
                else {
                    val pendente = goAsync()   // ViaCEP + Nominatim: rede, fora da thread principal
                    Fundo.rodar {
                        try {
                            pendente.resultData = LocalClima.definirPorCep(ctx, cep, intent.getStringExtra("numero")).fold(
                                { "ok ${LocalClima.descrever(it)} ${it.lat},${it.lon}" }, { "erro: ${it.message}" })
                        } finally { pendente.finish() }
                    }
                }
            }
            // Só pelo adb: --ez simular true mostra o cartão de teste (sem consultar a previsão nem mexer no silêncio);
            // sem ele, roda a verificação de verdade e responde o que decidiu (ex.: "sem chuva à vista").
            CHUVA -> if (this is ControleShizukuAdbReceiver) {
                if (intent.getBooleanExtra("simular", false)) resultData = AvisoChuva.simular(ctx).name.lowercase()
                else {
                    val pendente = goAsync()   // a verificação faz HTTP: fora da thread principal
                    Fundo.rodar { try { pendente.resultData = AvisoChuva.verificar(ctx) } finally { pendente.finish() } }
                }
            }
            // Só pelo adb: desligar sozinha. Sem extras responde o valor atual. --ei minutos N grava (0, 30, 60, 90, 120,
            // 180, 240); --ez aviso true mostra o aviso de 60 s agora (teste: deixe correr para a TV apagar, ou aperte
            // um botão do controle para cancelar).
            AUTO_DESLIGAR -> if (this is ControleShizukuAdbReceiver) {
                if (intent.hasExtra("minutos")) AutoDesligar.definir(ctx, intent.getIntExtra("minutos", -1))
                if (intent.getBooleanExtra("aviso", false)) { AutoDesligar.iniciar(ctx); AutoDesligar.testarAviso() }
                resultData = "minutos=${AutoDesligar.minutos(ctx)}"
            }
            // Só pelo adb (06/11/2026), até o painel ganhar a aba do player. Cadastrar:
            //   --es tipo m3u --es endereco <link> [--es nome X] [--es guia <link xmltv>]
            //   --es tipo xtream --es endereco <servidor> --es usuario U --es senha S [--es nome X]
            // Apagar: --es apagar <id>. A resposta nunca repete o link nem a senha.
            PLAYER_FONTE -> if (this is ControleShizukuAdbReceiver) {
                val apagar = intent.getStringExtra("apagar")
                resultData = if (apagar != null) { if (PlayerArmazem.apagar(ctx, apagar)) "apagada" else "não existe" }
                else PlayerArmazem.adicionar(ctx, intent.getStringExtra("nome").orEmpty(), intent.getStringExtra("tipo").orEmpty(),
                    intent.getStringExtra("endereco").orEmpty(), intent.getStringExtra("usuario").orEmpty(),
                    intent.getStringExtra("senha").orEmpty(), intent.getStringExtra("guia").orEmpty())
                    .fold({ "ok id=${it.id} ${it.tipo} ${it.servidorVisivel}" }, { "erro: ${it.message}" })
            }
            // Só pelo adb: as contas (sem link nem senha) e, com --ez carregar true, baixa e conta canais e programas.
            PLAYER_ESTADO -> if (this is ControleShizukuAdbReceiver) {
                val pendente = goAsync()   // pode baixar lista e guia: fora da thread principal
                Fundo.rodar {
                    try {
                        val fontes = PlayerArmazem.fontes(ctx)
                        val b = StringBuilder(fontes.joinToString("\n") { "${it.id} ${it.tipo} ${it.nome} ${it.servidorVisivel}" }.ifEmpty { "nenhuma conta" })
                        if (intent.getBooleanExtra("carregar", false)) {
                            val t0 = System.currentTimeMillis()
                            val canais = PlayerCarga.canais(ctx, forcar = true)
                            val t1 = System.currentTimeMillis()
                            val guia = PlayerCarga.guia(ctx, canais, forcar = true)
                            val agora = System.currentTimeMillis()
                            val comGuia = guia.comProgramaAgora(agora).let { no -> canais.count { it.guiaId in no } }.also { guia.fechar() }   // 1 consulta; solta o banco
                            b.append("\ncanais=${canais.size} em ${t1 - t0} ms; com programa agora=$comGuia; guia em ${agora - t1} ms")
                        }
                        pendente.resultData = b.toString()
                    } finally { pendente.finish() }
                }
            }
            // Só pelo adb, para medir engasgo (06/11/2026): esconde partes da HOME até o processo reiniciar. Ex.:
            //   am broadcast -a br.paulo.tvlauncher.HOME_TESTE --ez capa false --ez destaque true --ez faixa true --ez fundo true
            //     -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver      (sem extras: só mostra o estado)
            HOME_TESTE -> if (this is ControleShizukuAdbReceiver) {
                TesteHome.capa = intent.getBooleanExtra("capa", TesteHome.capa)
                TesteHome.destaque = intent.getBooleanExtra("destaque", TesteHome.destaque)
                TesteHome.faixa = intent.getBooleanExtra("faixa", TesteHome.faixa)
                TesteHome.fundo = intent.getBooleanExtra("fundo", TesteHome.fundo)
                TesteHome.capaAnima = intent.getBooleanExtra("capaAnima", TesteHome.capaAnima)
                TesteHome.capaCamada = intent.getBooleanExtra("capaCamada", TesteHome.capaCamada)
                TesteHome.aplicar?.invoke()
                resultData = TesteHome.run { "capa=$capa destaque=$destaque faixa=$faixa fundo=$fundo capaAnima=$capaAnima capaCamada=$capaCamada" }
            }
            UNIFI -> if (this is ControleShizukuAdbReceiver) {
                val pendente = goAsync()   // HTTP: fora da thread principal
                val antiga = intent.getBooleanExtra("antiga", false)   // --ez antiga true: testa a API antiga (sinal da TV)
                Fundo.rodar { try { pendente.resultData = if (antiga) UnifiCliente.testarApiAntiga(ctx) else UnifiCliente.verificarNovos(ctx) }
                    finally { pendente.finish() } }
            }
            REINICIAR -> {
                resultData = "reiniciando pid=${android.os.Process.myPid()}"
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 500)
            }
            // Só pelo adb (05/11/2026): o registro local de erros (RegistroErros.kt). Resposta: um erro por linha.
            ERROS -> resultData = RegistroErros.listar(ctx).joinToString("\n") { "${it.arquivo.name} ${it.resumo}" }.ifEmpty { "nenhum" }
            // Só pelo adb: testar o registro. Sem extra, grava um erro de mentira (nada cai). Com --ez derrubar true,
            // lança a exceção numa thread de verdade: o processo do launcher CAI (como um crash real) e o sistema o
            // sobe de novo, igual ao REINICIAR. Serve para provar que o tratador grava antes do Android fechar o app.
            TESTE_ERRO -> {
                val erro = IllegalStateException("teste do registro de erros (adb)")
                if (intent.getBooleanExtra("derrubar", false)) {
                    resultData = "derrubando pid=${android.os.Process.myPid()}"
                    Fundo.rodar("teste-erro") { throw erro }
                } else {
                    RegistroErros.gravar(ctx, "adb", erro)
                    resultData = "gravado"
                }
            }
            // Só pelo adb (05/11/2026): sem extra, lista as rotinas e a próxima; com --ei id N, roda a rotina N agora.
            ROTINA -> {
                val id = intent.getIntExtra("id", 0)
                if (id > 0) { Rotinas.rodar(ctx, id); resultData = "rodou $id" }
                else resultData = Rotinas.paraJson(Rotinas.listar(ctx)) + " proxima=" + (Rotinas.proxima(ctx)?.let { "${it.first.id}@${it.second}" } ?: "nenhuma") +
                    " semTecla=${Rotinas.minutosSemTecla()}min"
            }
            // Só pelo adb (05/11/2026): para testar candidatos da limpeza (Debloat.kt) antes de liberá-los na TV.
            DEBLOAT -> resultData = Debloat.relatorio(ctx)
            EXTRAS -> if (this is ControleShizukuAdbReceiver) {
                val pendente = goAsync()   // alguns comandos fazem rede ou disco: fora da thread principal
                Fundo.rodar { try { pendente.resultData = ComandosExtras.tratar(ctx, intent) } finally { pendente.finish() } }
            }
        }
    }
}

/**
 * Quem pode pedir o quê (pura, para o teste JVM ControleShizukuReceiverTest). Pelo adb (receiver DUMP): tudo. Pelos apps
 * com a permissão do Shizuku (receiver API_V23, que qualquer app ganha com um "Permitir" na tela): só ligar, desligar e
 * perguntar o estado do Shizuku. O resto (ocultar app pelo Device Owner, reiniciar o launcher, rotinas...) é do dono.
 */
object AcoesControle {
    val DOS_APPS = setOf(ControleShizukuReceiver.INICIAR, ControleShizukuReceiver.PARAR, ControleShizukuReceiver.ESTADO)

    fun permitida(acao: String?, viaAdb: Boolean): Boolean = viaAdb || acao in DOS_APPS
}

/** O mesmo controle, para o adb (permission=DUMP no manifest). */
class ControleShizukuAdbReceiver : ControleShizukuReceiver()

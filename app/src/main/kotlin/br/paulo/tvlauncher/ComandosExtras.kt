package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
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
 *  - acordar_pc            manda o pacote mágico
 *  - recado_foto --es arquivo <caminho> [--es texto "..."] [--ei segundos 60]
 *  - cena --es nome <cena>   roda uma cena (ver Cenas.kt)
 *  - cena_criar --es nome <cena> --es acoes '{"acoes":[...]}'   cria; devolve o token do link (só aparece agora)
 *  - cena_apagar --es nome <cena> · cenas (lista os nomes)
 *  - estado                resumo das chaves
 * 2ª leva (10/11/2026):
 *  - caches                limpa o cache de todos os apps
 *  - consumo [--ei dias N]  internet por app desde a meia-noite (ou N dias)
 *  - tocando · tocando_acao --es acao alternar|proxima|anterior
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
 *  - roteiro               respostas do roteiro "Conferir na TV" desta versão (JSON; ver RoteiroConferencia)
 * Volume da LG pela rede (14/11/2026, ideia 49; ver VolumeLg). Demoram (rede): o adb espera a resposta.
 *  - lg_procurar --es ip <qualquer IP da casa>   lista as LG da mesma rede /24
 *  - lg_pedir_codigo --es ip <IP da LG>         a LG mostra o código na tela dela
 *  - lg_parear --es ip <IP da LG> --es codigo <números>   guarda (só nesta TV) e passa a usar a LG no volume
 *  - lg_estado · lg_esquecer · lg_tecla --es tecla subir|descer|mudo [--ei vezes N]
 */
object ComandosExtras {
    fun tratar(ctx: Context, i: Intent): String = when (i.getStringExtra("o")) {
        "teclas_armar" -> { TesteTeclas.armar(); "armado por ${TesteTeclas.JANELA_MS / 1000} s" }
        "teclas" -> TesteTeclas.json().toString()
        "teclas_tela" -> abrir(ctx, TesteTeclasActivity::class.java)
        "calibracao" -> abrir(ctx, CalibracaoActivity::class.java)
        "extras" -> abrir(ctx, ExtrasActivity::class.java)
        "diario_dns" -> DiarioDns.resumo(ctx, i.getStringExtra("dia") ?: DiarioDns.diaDe(System.currentTimeMillis()))
        "wol_mac" -> if (AcordarPc.guardarMac(ctx, i.getStringExtra("mac") ?: "")) "ok" else "MAC inválido"
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
        "saude" -> SaudeAcesso.json(ctx).toString()   // estado do shell próprio e do túnel + última manutenção
        // Roda a manutenção noturna agora. Numa thread própria: pode passar de 90 s (shell + limpeza de cache), mais que o
        // limite de um broadcast; o relatório sai depois por "saude" (campo "manutencao").
        "manutencao" -> { val app = ctx.applicationContext; Fundo.rodar("Manutencao-adb") { ManutencaoNoturna.rodar(app) }; "iniciado; ver depois com saude" }
        "manutencao_resumo" -> { ManutencaoNoturna.talvezMostrarResumo(ctx); "ok" }   // mostra o resumo (se tiver algo e a HOME na frente)
        "consumo" -> ConsumoRedeTv.texto(ctx, i.getIntExtra("dias", 1))
        "tocando" -> TocandoAgoraTv.texto(ctx)
        "tocando_acao" -> if (TocandoAgoraTv.controlar(ctx, i.getStringExtra("acao") ?: "")) "ok" else "nada tocando"
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
        "roteiro" -> ConferirTvActivity.relatorio(ctx)
        "lg_procurar" -> VolumeLg.procurar(i.getStringExtra("ip") ?: "").joinToString("\n") { (ip, m) -> "$ip ${m.nome} ${m.modelo}" }
            .ifEmpty { "nenhuma LG respondeu (ligada? mesmo /24?)" }
        "lg_pedir_codigo" -> VolumeLg.pedirCodigo(i.getStringExtra("ip") ?: "")
        "lg_parear" -> VolumeLg.parear(ctx, i.getStringExtra("ip") ?: "", i.getStringExtra("codigo") ?: "")
        "lg_estado" -> VolumeLg.estado(ctx)
        "lg_esquecer" -> { VolumeLg.esquecer(ctx); "esquecida" }
        "lg_tecla" -> when (i.getStringExtra("tecla")) {
            "subir" -> LgNetcast.TECLA_SUBIR; "descer" -> LgNetcast.TECLA_DESCER; "mudo" -> LgNetcast.TECLA_MUDO; else -> null
        }?.let { if (VolumeLg.tecla(ctx, it, i.getIntExtra("vezes", 1))) "ok" else "a LG não aceitou (pareada? ligada?)" }
            ?: "tecla: subir, descer ou mudo"
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

    private fun abrir(ctx: Context, c: Class<*>): String {
        ctx.startActivity(Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "aberto"
    }
}

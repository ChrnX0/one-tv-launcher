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
        "estado" -> Extras.Chave.values().joinToString("\n") { "${it.name}=${Extras.ligado(ctx, it)}" } +
            "\ndiario_dns_erro=${DiarioDns.erro(ctx)}" + "\nrelogio_travado=${TravaRelogio.travado(ctx)}"
        else -> "comando desconhecido; ver ComandosExtras.kt"
    }

    private fun abrir(ctx: Context, c: Class<*>): String {
        ctx.startActivity(Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "aberto"
    }
}

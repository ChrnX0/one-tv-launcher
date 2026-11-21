package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import br.paulo.tvlauncher.alarm.AutoDesligar
import br.paulo.tvlauncher.alarm.HoraDormirTv
import br.paulo.tvlauncher.alarm.NaoPerturbeTv
import br.paulo.tvlauncher.family.TempoTelaTv
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.home.Home
import br.paulo.tvlauncher.input.BotoesControle
import br.paulo.tvlauncher.input.SomNavegacaoTv
import br.paulo.tvlauncher.input.TecladoCelular
import br.paulo.tvlauncher.input.TesteTeclas
import br.paulo.tvlauncher.mcp.McpServidor
import br.paulo.tvlauncher.mcp.TunelTailscale
import br.paulo.tvlauncher.network.AcordarComLg
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.network.HistoricoRede
import br.paulo.tvlauncher.network.UnifiCliente
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.privilege.JanelaNaFrente
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.privilege.SaudeAcesso
import br.paulo.tvlauncher.privilege.ShizukuEmbutido
import br.paulo.tvlauncher.settings.FiltroNoturno
import br.paulo.tvlauncher.weather.AvisoChuva

/**
 * Serviço de acessibilidade do launcher. Hoje só trata teclas do controle remoto (ver [onKeyEvent]) — chegou a
 * aprovar sozinho os diálogos de permissão do Shizuku, mas esse app foi desinstalado em 03/11 (o launcher migrou
 * para o Device Owner para a maior parte do que precisava, ver [PrivilegioDO]) e esse código foi removido.
 */
class AutorizaShizukuService : AccessibilityService() {
    companion object {
        /** O serviço ligado, para quem precisa de uma janela de acessibilidade (ver PareamentoNaTv). null = desligado. */
        @Volatile var instancia: AutorizaShizukuService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected(); instancia = this
        // O sistema religa este serviço sempre que o processo do launcher sobe de novo (reinício, restauração de
        // backup, falta de memória), mesmo sem a HOME na frente. Então é daqui que o painel no navegador volta.
        PainelWeb.ajustar(this)
        McpServidor.ajustar(this); TunelTailscale.ajustar(this)   // MCP pela internet (se ligado)
        AvisoChuva.vigiar(this)   // aviso de chuva por cima de qualquer tela (a cada 30 min, só com a tela acesa)
        HistoricoRede.iniciar(this)   // histórico de rede (para o painel) e o "desligar sozinha": também voltam daqui
        AutoDesligar.iniciar(this)
        AcordarComLg.iniciar(this)   // LG ligada pelo controle dela acorda o Chromecast: volta também quando o processo renasce com a tela apagada
        UnifiCliente.vigiar(this) // aparelho novo no roteador UniFi (a cada 5 min, só com o aviso ligado no painel)
        FiltroNoturno.iniciar(this)
        SaudeAcesso.vigiar(this)   // aviso "acesso remoto parado" (shell próprio / túnel), ver SaudeAcesso.kt
        ManutencaoNoturna.vigiar(this); ManutencaoNoturna.agendar(this)   // manutenção de madrugada + resumo de manhã
        CuidarDeLongeTv.iniciar(this)   // Cuidar de longe: lê a tecla de ajuda e agenda o próximo lembrete/aviso
        Gatilhos.iniciar(this)   // eventos como gatilho de cena (ideia 7): tela acesa e sessões de mídia
        br.paulo.tvlauncher.home.StartupApp.iniciar(this)   // "Ao ligar a TV": abre o app escolhido ao acordar do standby
        TecladoCelular.ajustar(this)   // ideia 57: foco de campo de texto só com o aviso ligado
        JogosTv.iniciarControles(this)   // card Jogos: ligar um controle abre a tela Jogos (só com a chave ligada)
        CasaHA.iniciar(this)   // Home Assistant: publica o estado da TV e vigia a campainha (só se configurado)   // película âmbar à noite (só com a chave ligada em Extras)
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instancia = null; FiltroNoturno.parar(); JogosTv.pararControles(); return super.onUnbind(intent) }
    override fun onDestroy() { instancia = null; super.onDestroy() }

    // Só TYPE_WINDOW_STATE_CHANGED (ver autoriza_acessibilidade.xml): poucas por minuto, quando uma tela abre.
    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Home.janelaApareceu(this, evento.packageName, evento.className)
            JanelaNaFrente.apareceu(evento.packageName, evento.className)   // quem está na frente (MCP, rotinas, Casa, saúde do acesso)
            ShizukuEmbutido.janelaApareceu(this, evento.packageName)   // shell emprestado sob demanda: cliente na frente sobe o servidor
            SomNavegacaoTv.pacoteNaFrente = evento.packageName?.toString()
            OnDemandTv.onWindow(this, evento.packageName)   // sob demanda: saiu da frente -> congela de novo depois de 2 min
            SaudeAcesso.janela(this, evento.packageName, evento.className)   // HOME na frente: hora boa para aviso pendente
            HoraDormirTv.janela(this, evento.packageName)   // hora de dormir: app fora da lista volta para a HOME
            TempoTelaTv.janela(this, evento.packageName)   // tempo de tela por dia (ideia 33): conta e fecha se acabou
            JogosTv.janela(this)   // modo jogo: entrou ou saiu de um jogo -> o filtro noturno se reavalia
            NaoPerturbeTv.janela(this)   // elevação 11.4: o filme acabou -> o resumo dos avisos que esperaram
            VigiaTrailer.janela(this, evento.packageName, evento.className)   // trailer no YouTube: saiu dele por outro caminho -> fecha o YouTube
        } else if (evento?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            TecladoCelular.foco(this, evento)   // ideia 57: só chega com o aviso "Digitar na TV" ligado (TecladoCelular.ajustar)
        }
    }

    /**
     * Teclas do controle: o serviço de acessibilidade vê cada tecla ANTES do app da frente e antes dos botões
     * "globais" (Netflix, YouTube), que o sistema manda direto para outro app sem passar pelo launcher.
     * Os botões Netflix e YouTube vão para BotoesControle (true = o sistema não os vê); o resto segue normal (false).
     * Para descobrir o código de um botão novo: adb logcat -s TvLauncher.Tecla (registra só em modo depuração do log, nível D).
     */
    override fun onKeyEvent(e: KeyEvent): Boolean = tecla(e)

    /** O funil das teclas. Também recebe a tecla falsa do adb (EXTRAS "tecla", ver TeclaFalsa), sempre na thread principal. */
    fun tecla(e: KeyEvent): Boolean {
        // Só registra com o log da tag ligado (adb shell setprop log.tag.TvLauncher.Tecla DEBUG). Antes saía sempre:
        // com um teclado Bluetooth, o que se digitava (senhas inclusive) ia parar no logcat (revisão de 04/11/2026).
        if (Log.isLoggable("TvLauncher.Tecla", Log.DEBUG))
            Log.d("TvLauncher.Tecla", "tecla=${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}) scan=${e.scanCode} " +
                "acao=${if (e.action == KeyEvent.ACTION_DOWN) "desce" else "sobe"} repete=${e.repeatCount}")
        TesteTeclas.registrar(e)   // testador de teclas: só grava se armado (máx. 2 min, só na memória)
        Rotinas.tecla()   // rotina "desligar se ninguém mexeu há N min" (só grava a hora; ver Rotinas.kt)
        // Desligar sozinha: toda tecla conta como "alguém está usando"; com o aviso na tela, a tecla só o cancela.
        if (AutoDesligar.tecla(e)) return true
        // Cuidar de longe (77): segurar a tecla de ajuda 3 s. Toque curto passa; só depois dos 3 s a tecla é nossa.
        if (CuidarDeLongeTv.tecla(this, e)) return true
        if (AvisoTv.teclaOk(e)) return true   // OK com um recado da família na tela: "❤ Vi" (quem mandou fica sabendo)
        if (AvisoTv.teclaVoltar(e)) return true   // VOLTAR com um recado/aviso na tela: fecha o aviso, não o filme
        if (HoraDormirTv.tecla(this, e)) return true   // hora de dormir: VOLUME + não passa do teto
        SomNavegacaoTv.tecla(this, e)   // clique de navegação (só nas telas do launcher, se ligado)
        return BotoesControle.tratar(this, e)
    }

    override fun onInterrupt() {}
}
package br.paulo.tvlauncher

import android.app.Application
import br.paulo.tvlauncher.family.UsoLocal
import br.paulo.tvlauncher.home.IdeiasInicio
import br.paulo.tvlauncher.home.Moldura
import br.paulo.tvlauncher.home.SugestoesTv
import br.paulo.tvlauncher.input.VozFoco
import br.paulo.tvlauncher.mcp.BuildInfo
import br.paulo.tvlauncher.mcp.PedidoIa
import br.paulo.tvlauncher.observer.LeakWatch
import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.ObserverTv
import br.paulo.tvlauncher.observer.RegistroErros
import br.paulo.tvlauncher.observer.VitaisTv
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.privilege.CompilarAot
import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.settings.CobrancaPlay
import br.paulo.tvlauncher.settings.TamanhoTexto

/**
 * Classe do app (05/11/2026). Roda uma vez em cada processo do launcher, antes de qualquer tela ou receiver.
 * Hoje só liga o registro local de erros (RegistroErros.kt), para que um crash em QUALQUER ponto (HOME, boot,
 * acessibilidade, painel) fique gravado. Mantenha leve: tudo aqui atrasa a abertura da HOME.
 */
class LauncherApp : Application() {
    companion object {
        /** O app, para as frases traduzidas de quem não tem Context (Textos.kt). Vale desde o onCreate do processo. */
        lateinit var instancia: LauncherApp
            private set
    }

    /**
     * O Android avisa quando falta memória (12/11/2026, Sala: ZRAM 100% cheia e travadinhas até nas Configurações do
     * Android). Antes o launcher não devolvia nada. Agora solta os caches de imagem que se refazem do disco.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ObserverTv.publish(ObserverCore.Signal.MEMORY_PRESSURE, level.toLong())   // o Observador anota o aperto (só no processo principal)
        if (Memoria.soltarCaches(level)) {
            PlayerLogos.soltarMemoria()
            br.paulo.tvlauncher.home.IconSetArt.soltar()   // arte dos conjuntos de ícones: volta do disco no próximo desenho
            android.util.Log.i("TvLauncher.Memoria", "onTrimMemory($level): caches soltos")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        // O processo ":dhizuku_usuario" roda código de OUTRO app (UserService do Dhizuku): uma exceção dele não pode
        // contar como queda do launcher (RegistroErros/VigiaAtualizacao marcariam a versão como ruim), e nada da tela
        // do launcher precisa existir lá.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":dhizuku_usuario")) return
        // Troca de pacote (PackageMigration.kt): o processo ":migration" só copia dados. Nada pode subir nele, e nada pode
        // ler preferências antes da cópia (elas ficariam velhas na memória).
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":migration")) return
        RegistroErros.instalar(this)
        // Atualização vigiada: anota a versão que abriu (versão nova = começa a vigiar) e liga o modo seguro, que não
        // deixa abrir a tela que derrubou uma versão ruim. Logo depois do RegistroErros: uma queda já conta.
        VigiaAtualizacao.aoIniciar(this)
        VigiaAtualizacao.vigiarTelas(this)
        Moldura.instalar(this)   // a hora da HOME no mesmo lugar nas telas de lista (Moldura.kt)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) = Estilo.tirarCorDaJanelaSeCoberta(a)   // Estilo.kt
            // Ideia 47 (TamanhoTexto.kt): o tamanho do texto escolhido, antes do onCreate de cada tela (Android 10+).
            override fun onActivityPreCreated(a: android.app.Activity, salvo: android.os.Bundle?) {
                TamanhoTexto.aplicar(a)
                AltoContraste.aplicar(a)   // elevação 16.3
            }
            override fun onActivityCreated(a: android.app.Activity, salvo: android.os.Bundle?) { if (salvo == null) UsoLocal.contarTela(a) }   // ideia 67
            override fun onActivityResumed(a: android.app.Activity) {
                VozFoco.aoRetomar(a)   // ideia 48 (VozFoco.kt)
                if (a is MainActivity) SugestoesTv.aoRetomarHome(a)   // ideia 44 (SugestoesTv.kt): no máximo 1 vez por dia
            }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) = LeakWatch.aoDestruir(a)   // adb: EXTRAS --es o vazamentos
        })
        // A IA na nuvem saiu em 05/11/2026: apaga a chave cifrada que ela tenha deixado (fora da thread da tela).
        Fundo.rodar("limpa-ia") { PedidoIa.apagarRestosDaNuvem(this) }
        // Backup noturno: reagenda a cada processo novo (uma atualização do app apaga os alarmes dele). Só no processo
        // principal (o player roda em ":player") e fora da thread da tela (lê preferências do disco).
        if (android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName) {
            Fundo.rodar("backup-noturno") { runCatching { BackupNoturno.agendar(this) } }
            // Versão nova ainda sem AOT: compila quando a tela apagar (CompilarAot.kt). Só registra um receiver.
            CompilarAot.iniciar(this)
            CobrancaPlay.atualizar(this)   // build da Play: confere com a loja o plano comprado (no-op no APK do site)
            ObserverTv.start(this)   // Observador (ObserverCore.kt): só neste processo os sensores publicam
            CompatibilidadeTv.vigiar(this)   // mede o "acordar" do relatório de compatibilidade (CompatibilidadeTv.kt)
            // Ideias de 12/11/2026: lembretes reagendados (atualizar o app apaga os alarmes), aviso no celular de versão
            // nova e de erro novo, e a guarda de desempenho (anota a versão para comparar os quadros com a anterior).
            Fundo.rodar("ideias-12-11") { runCatching { IdeiasInicio.aoIniciar(this) } }
            // Modo hóspede (degrau 2): atualizar o app apaga os alarmes; reagenda a hora da saída.
            Fundo.rodar("vitais") { runCatching { VitaisTv.aoIniciar(this) } }   // degrau 2: quedas e travadas por dia
            Fundo.rodar("hospede") { runCatching { ModoHospede.agendar(this) }; runCatching { AgendaPerfisTv.agendar(this) } }
        }
        BuildInfo.versao = BuildConfig.VERSION_NAME   // o MCP informa a versão no "initialize"
    }
}

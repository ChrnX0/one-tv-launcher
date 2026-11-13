package br.paulo.tvlauncher

import android.app.Application

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
        if (Memoria.soltarCaches(level)) {
            PlayerLogos.soltarMemoria()
            android.util.Log.i("TvLauncher.Memoria", "onTrimMemory($level): caches soltos")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        RegistroErros.instalar(this)
        // Atualização vigiada: anota a versão que abriu (versão nova = começa a vigiar) e liga o modo seguro, que não
        // deixa abrir a tela que derrubou uma versão ruim. Logo depois do RegistroErros: uma queda já conta.
        VigiaAtualizacao.aoIniciar(this)
        VigiaAtualizacao.vigiarTelas(this)
        Moldura.instalar(this)   // a hora da HOME no mesmo lugar nas telas de lista (Moldura.kt)
        // A IA na nuvem saiu em 05/11/2026: apaga a chave cifrada que ela tenha deixado (fora da thread da tela).
        Fundo.rodar("limpa-ia") { PedidoIa.apagarRestosDaNuvem(this) }
        // Backup noturno: reagenda a cada processo novo (uma atualização do app apaga os alarmes dele). Só no processo
        // principal (o player roda em ":player") e fora da thread da tela (lê preferências do disco).
        if (android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName) {
            Fundo.rodar("backup-noturno") { runCatching { BackupNoturno.agendar(this) } }
            // Versão nova ainda sem AOT: compila quando a tela apagar (CompilarAot.kt). Só registra um receiver.
            CompilarAot.iniciar(this)
            // Ideias de 12/11/2026: lembretes reagendados (atualizar o app apaga os alarmes), aviso no celular de versão
            // nova e de erro novo, e a guarda de desempenho (anota a versão para comparar os quadros com a anterior).
            Fundo.rodar("ideias-12-11") { runCatching { IdeiasInicio.aoIniciar(this) } }
        }
        BuildInfo.versao = BuildConfig.VERSION_NAME   // o MCP informa a versão no "initialize"
    }
}

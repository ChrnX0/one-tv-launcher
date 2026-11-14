package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.media.AudioManager
import android.os.PowerManager
import android.os.storage.StorageManager
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.File
import java.util.EnumSet
import java.util.concurrent.Executors

/**
 * Backup automático CIFRADO (10/11/2026, melhoria aprovada pelo dono). Toda noite, no horário escolhido (padrão 3:30)
 * e só com a TV ociosa, monta o backup completo (BackupCompleto, o mesmo do "Baixar backup completo" do painel),
 * tranca com a senha do dono (CofreBackup) e guarda:
 *  1. na memória da TV: filesDir/backups-cifrados, as [BackupNoturnoRegras.MANTER] últimas;
 *  2. opcionalmente, uma cópia extra numa pasta SMB do NAS (as contas de "Mídias na rede"), num pendrive ou no Google
 *     Drive (DriveBackup, 13/11/2026), na pasta "OneTV-backup", também com as 7 últimas. A cópia de FORA é a que sobrevive a um reset de fábrica.
 *
 * A senha: o painel pede a senha a cada download e não guarda. Aqui ela precisa ficar guardada (a TV gera o backup
 * sozinha de madrugada), então vai cifrada pelo Android Keystore (a mesma chave das contas de TV, PlayerArmazem).
 * Sem senha definida pelo dono, a opção NÃO liga: nada de senha inventada que ninguém sabe.
 *
 * Diferente do BackupAutomatico.kt (cópia diária em JSON aberto, só das configurações comuns, que continua igual).
 *
 * Agendamento: um alarme do Android (inexato, acorda a TV do standby), reagendado no boot, ao abrir o processo e
 * depois de cada execução. TV em uso na hora: tenta de novo a cada 30 min, até 4 vezes; depois desiste da noite.
 */
object BackupNoturno {
    private const val TAG = "TvLauncher.BackupNoturno"
    private const val PREFS = "backup_noturno"   // fora do BackupCompleto de propósito: guarda a senha do próprio cofre
    const val EXTRA_TENTATIVA = "tentativa"
    private val fila = Executors.newSingleThreadExecutor()   // uma execução por vez (alarme e "Fazer agora" juntos)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pasta(ctx: Context) = File(ctx.filesDir, "backups-cifrados")

    // ---------------------------------------------------------------- configuração

    /** Ligado de verdade só com senha guardada: se a senha sumir (Keystore apagado), desliga sozinho. */
    fun ligado(ctx: Context): Boolean = prefs(ctx).getBoolean("ligado", false) && temSenha(ctx)

    /** Devolve false (e não liga) se ainda não há senha. */
    fun definirLigado(ctx: Context, sim: Boolean): Boolean {
        if (sim && !temSenha(ctx)) return false
        prefs(ctx).edit().putBoolean("ligado", sim).apply { if (sim) putLong("ligado_desde", System.currentTimeMillis()) }.apply()
        agendar(ctx)
        return true
    }

    fun minutoDoDia(ctx: Context): Int = prefs(ctx).getInt("minuto_do_dia", BackupNoturnoRegras.PADRAO_MINUTO_DO_DIA)
    fun definirMinutoDoDia(ctx: Context, m: Int) {
        prefs(ctx).edit().putInt("minuto_do_dia", Math.floorMod(m, 24 * 60)).apply()
        agendar(ctx)
    }

    /** "" = só na TV; "pendrive"; "drive"; "smb:<id>". Ver BackupNoturnoRegras. */
    fun destino(ctx: Context): String = prefs(ctx).getString("destino", BackupNoturnoRegras.SO_TV) ?: BackupNoturnoRegras.SO_TV
    /** [nome] = nome da pasta do NAS, guardado só para mostrar (ler as contas decifra pelo Keystore: fora da tela). */
    fun definirDestino(ctx: Context, d: String, nome: String = "") {
        prefs(ctx).edit().putString("destino", d).putString("destino_nome", nome).apply()
    }

    /** As pastas SMB cadastradas em "Mídias na rede" (Jellyfin não serve: não recebe arquivo). */
    fun contasSmb(ctx: Context): List<ContaMidia> = MidiaArmazem.contas(ctx).filter { it.tipo == ContaMidia.SMB }

    fun temSenha(ctx: Context): Boolean = prefs(ctx).contains("senha_dados")

    /** Guarda a senha cifrada pelo Keystore. False se a senha é fraca (CofreBackup.senhaValida) ou o Keystore falhou. */
    fun definirSenha(ctx: Context, senha: String): Boolean {
        if (!CofreBackup.senhaValida(senha)) return false
        return try {
            val (dados, iv) = PlayerArmazem.cifrar(senha)
            prefs(ctx).edit().putString("senha_dados", dados).putString("senha_iv", iv).commit()
        } catch (e: Exception) { Log.w(TAG, "não consegui cifrar a senha: ${e.javaClass.simpleName}"); false }
    }

    /** A senha em claro, só para trancar o backup. Null sem senha ou se o Keystore perdeu a chave. Nunca vai para o log. */
    private fun senha(ctx: Context): String? {
        val p = prefs(ctx)
        val dados = p.getString("senha_dados", null) ?: return null
        val iv = p.getString("senha_iv", null) ?: return null
        return runCatching { PlayerArmazem.decifrar(dados, iv) }.getOrNull()
    }

    /** Última execução, para a tela: "10/11 03:30 · ok: TV + NAS" ou o erro. Vazio = nunca rodou. */
    fun ultimo(ctx: Context): String = prefs(ctx).getString("ultimo_texto", "") ?: ""
    private fun ultimoOk(ctx: Context): Long = prefs(ctx).getLong("ultimo_ok_em", 0L)

    /** As cópias guardadas na TV, da mais nova para a mais velha. */
    fun listar(ctx: Context): List<File> =
        pasta(ctx).listFiles { f -> BackupNoturnoRegras.nomeValido(f.name) }?.sortedByDescending { it.name } ?: emptyList()

    // ---------------------------------------------------------------- agendar

    private fun pendente(ctx: Context, tentativa: Int) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, BackupNoturnoReceiver::class.java).putExtra(EXTRA_TENTATIVA, tentativa),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Agenda a próxima noite (ou cancela, se desligado). Barato: pode chamar no boot e ao abrir o processo.
     * Um adiamento ainda no futuro (TV em uso, guardado nas prefs) é respeitado: o alarme é um só (mesmo PendingIntent)
     * e, sem isso, um processo novo de madrugada (atualização, REINICIAR) trocava o adiamento pela noite seguinte.
     */
    fun agendar(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(app)
        if (!ligado(app)) { am.cancel(pendente(app, 0)); limparAdiamento(app); return }
        val agora = System.currentTimeMillis()
        val pendenteEm = p.getLong("adiado_para", 0L)
        if (BackupNoturnoRegras.adiamentoValido(pendenteEm, agora)) {
            armar(am, app, pendenteEm, p.getInt("adiado_tentativa", 1)); return
        }
        val quando = BackupNoturnoRegras.quandoAgendar(agora, minutoDoDia(app), ultimoOk(app), p.getLong("ligado_desde", 0L),
            ultimoAlarme = p.getLong("ultimo_alarme_em", 0L))
        armar(am, app, quando, 0)
    }

    private fun limparAdiamento(ctx: Context) =
        prefs(ctx).edit().remove("adiado_para").remove("adiado_tentativa").apply()

    private fun armar(am: AlarmManager, ctx: Context, quando: Long, tentativa: Int) {
        // Inexato de propósito (não precisa ser às 3:30 em ponto) e "AllowWhileIdle": acorda a TV do standby.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx, tentativa))
        Log.i(TAG, "próximo backup noturno em ${(quando - System.currentTimeMillis()) / 60_000} min (tentativa $tentativa)")
    }

    // ---------------------------------------------------------------- rodar

    /** Chamado pelo alarme: confere o ócio, roda e agenda a próxima. [fim] roda no final (libera o goAsync). */
    fun peloAlarme(ctx: Context, tentativa: Int, fim: () -> Unit) {
        val app = ctx.applicationContext
        fila.execute {
            try {
                // Este alarme já disparou: o adiamento guardado (se era ele) acabou; e marca a hora, para o "atrasado"
                // não adiantar de novo hoje (BackupNoturnoRegras.quandoAgendar).
                prefs(app).edit().remove("adiado_para").remove("adiado_tentativa")
                    .putLong("ultimo_alarme_em", System.currentTimeMillis()).apply()
                if (!ligado(app)) return@execute
                if (!tvOciosa(app)) {
                    val de = BackupNoturnoRegras.proximaTentativa(System.currentTimeMillis(), tentativa)
                    if (de != null) {
                        prefs(app).edit().putLong("adiado_para", de).putInt("adiado_tentativa", tentativa + 1).commit()
                        app.getSystemService(AlarmManager::class.java)?.let { armar(it, app, de, tentativa + 1) }
                        Log.i(TAG, "TV em uso: backup adiado 30 min")
                    } else {
                        registrar(app, false, app.getString(R.string.bn_em_uso))
                        agendar(app)
                    }
                    return@execute
                }
                executar(app)
                agendar(app)
            } catch (e: Throwable) {
                Log.w(TAG, "backup noturno falhou", e)
                runCatching { agendar(app) }
            } finally { fim() }
        }
    }

    /** Linha "Fazer agora" da tela: roda já, sem esperar o ócio (quem apertou está ali). [fim] recebe o resultado. */
    fun fazerAgora(ctx: Context, fim: (String) -> Unit) {
        val app = ctx.applicationContext
        fila.execute {
            val r = try { executar(app) } catch (e: Throwable) { "falhou: ${e.javaClass.simpleName}" }
            limparAdiamento(app)   // já fez agora: um adiamento desta noite não precisa rodar de novo
            runCatching { agendar(app) }
            fim(r)
        }
    }

    /** Monta, tranca e guarda. Devolve o texto do resultado (o mesmo que vai para o registro do painel). */
    private fun executar(app: Context): String {
        val senha = senha(app) ?: run {
            // Havia senha guardada mas o Keystore não a abre mais (chave perdida): apaga e desliga, em vez de falhar
            // toda noite com "ligado" na tela. O dono define a senha de novo.
            if (temSenha(app)) {
                prefs(app).edit().remove("senha_dados").remove("senha_iv").putBoolean("ligado", false).commit()
                return registrar(app, false, app.getString(R.string.bn_keystore))
            }
            return registrar(app, false, app.getString(R.string.bn_sem_senha))
        }
        val agora = System.currentTimeMillis()
        val nome = BackupNoturnoRegras.nomeDoDia(agora)
        val bytes = try {
            BackupCompleto.exportar(app, senha).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            RegistroErros.gravar(app, "backup-noturno", e)   // aparece no Diagnóstico
            return registrar(app, false, app.getString(R.string.bn_montar, e.javaClass.simpleName))
        }
        // 1. Na TV (sempre). Temporário + renomear: queda de energia no meio não deixa arquivo pela metade com nome válido.
        try {
            gravarLocal(pasta(app), nome, bytes)
        } catch (e: Exception) {
            RegistroErros.gravar(app, "backup-noturno", e)
            return registrar(app, false, app.getString(R.string.bn_gravar, e.javaClass.simpleName))
        }
        // 2. A cópia extra, se escolhida. Falhar aqui não apaga a da TV: o resultado diz "parcial".
        val destino = destino(app)
        val extra: String? = try {
            when {
                destino == BackupNoturnoRegras.PENDRIVE -> { enviarPendrive(app, nome, bytes); "pendrive" }
                destino == BackupNoturnoRegras.DRIVE -> { DriveBackup.enviar(app, nome, bytes); "Google Drive" }
                BackupNoturnoRegras.idSmb(destino) != null -> {
                    val conta = contasSmb(app).firstOrNull { it.id == BackupNoturnoRegras.idSmb(destino) }
                        ?: throw IllegalStateException("a pasta do NAS foi apagada")
                    enviarSmb(conta, nome, bytes); "NAS (${conta.nome})"
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "cópia extra falhou: ${e.javaClass.simpleName}")
            prefs(app).edit().putLong("ultimo_ok_em", agora).apply()   // a da TV valeu: não conta como "atrasado"
            return registrar(app, false, "parcial: TV ok; ${rotuloDestino(app, destino)} falhou (${e.message ?: e.javaClass.simpleName})")
        }
        return registrar(app, true, "ok: TV" + (extra?.let { " + $it" } ?: "") + " (${bytes.size / 1024} KB)")
    }

    /** Guarda o resultado para a tela e põe no registro do painel (aba Registro), como as ações de lá. */
    private fun registrar(app: Context, ok: Boolean, texto: String): String {
        val agora = System.currentTimeMillis()
        val quando = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(agora))
        prefs(app).edit().putString("ultimo_texto", "$quando · $texto").apply { if (ok) putLong("ultimo_ok_em", agora) }.apply()
        runCatching { PainelArmazem.registrar(app, "TV", "backup noturno", texto) }
        if (ok) Log.i(TAG, texto) else Log.w(TAG, texto)
        return texto
    }

    internal fun gravarLocal(dir: File, nome: String, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("não criei a pasta ${dir.name}")
        val tmp = File(dir, "$nome.tmp")
        tmp.writeBytes(bytes)
        val destino = File(dir, nome)
        if (destino.exists()) destino.delete()   // 2ª vez no mesmo dia ("Fazer agora"): a nova substitui
        if (!tmp.renameTo(destino)) { tmp.delete(); throw IllegalStateException("não renomeei $nome") }
        for (velho in BackupNoturnoRegras.quaisApagar(dir.list()?.toList() ?: emptyList())) File(dir, velho).delete()
    }

    // ---------------------------------------------------------------- destinos de fora

    /**
     * Pendrive: o 1º volume removível montado. Escrever direto lá exige o acesso a todos os arquivos
     * (MANAGE_EXTERNAL_STORAGE), que o launcher se concede pelo shell próprio (ver PainelArquivos).
     */
    private fun enviarPendrive(app: Context, nome: String, bytes: ByteArray) {
        val raiz = pastaPendrive(app) ?: throw IllegalStateException("nenhum pendrive conectado")   // < Android 11 = null
        if (!Environment.isExternalStorageManager()) throw IllegalStateException("sem acesso a arquivos")
        gravarLocal(File(raiz, BackupNoturnoRegras.PASTA_EXTERNA), nome, bytes)
    }

    fun pastaPendrive(app: Context): File? {
        if (Build.VERSION.SDK_INT < 30) return null   // StorageVolume.directory é do Android 11; a TV é Android 14
        val sm = app.getSystemService(StorageManager::class.java) ?: return null
        return sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.directory
    }

    /** NAS: grava em <pasta da conta>/OneTV-backup com nome temporário, renomeia e apaga as cópias além das 7. */
    private fun enviarSmb(conta: ContaMidia, nome: String, bytes: ByteArray) {
        Smb.abrir(conta).use { a ->
            val base = MidiaContas.juntar(Smb.inicio(conta), BackupNoturnoRegras.PASTA_EXTERNA)
            // Cria cada nível que faltar (a subpasta da conta pode ser "Backups/TV").
            var caminho = ""
            for (parte in base.split('/')) {
                caminho = MidiaContas.juntar(caminho, parte)
                val win = caminho.replace('/', '\\')
                if (!a.pasta.folderExists(win)) a.pasta.mkdir(win)
            }
            val dir = base.replace('/', '\\')
            a.pasta.openFile("$dir\\$nome.tmp", EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.write(bytes, 0L)
                f.rename("$dir\\$nome", true)   // true = substitui a do mesmo dia
            }
            val nomes = a.pasta.list(dir).map { it.fileName }
            for (velho in BackupNoturnoRegras.quaisApagar(nomes)) runCatching { a.pasta.rm("$dir\\$velho") }
        }
    }

    // ---------------------------------------------------------------- ócio e textos

    /**
     * Tela apagada, protetor na frente, ou (tela acesa) nada tocando e ninguém mexeu no controle há 30 min. O "nada
     * tocando" é o AudioManager.isMusicActive: filme ou série tocando não aperta tecla, e não pode virar "ociosa".
     */
    private fun tvOciosa(app: Context): Boolean {
        val acesa = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        val tocando = runCatching { app.getSystemService(AudioManager::class.java)?.isMusicActive == true }.getOrDefault(true)
        return BackupNoturnoRegras.tvOciosa(acesa, protetorNaTela(app), Rotinas.minutosSemTecla(),
            AutorizaShizukuService.instancia != null, tocando)
    }

    /** O nosso protetor (um DreamService) roda no nosso processo enquanto aparece (mesma conta de Rotinas). */
    @Suppress("DEPRECATION")
    private fun protetorNaTela(app: Context): Boolean = try {
        app.getSystemService(android.app.ActivityManager::class.java)?.getRunningServices(50)
            ?.any { it.service.className == ProtetorTela::class.java.name } == true
    } catch (e: Exception) { false }

    fun rotuloDestino(ctx: Context, d: String): String = when {
        d == BackupNoturnoRegras.PENDRIVE -> ctx.getString(R.string.bkn_destino_pendrive)
        d == BackupNoturnoRegras.DRIVE -> ctx.getString(R.string.drv_destino)
        BackupNoturnoRegras.idSmb(d) != null ->
            ctx.getString(R.string.bkn_destino_nas, prefs(ctx).getString("destino_nome", null)?.ifEmpty { null } ?: "?")
        else -> ctx.getString(R.string.bkn_destino_tv)
    }

    /** Resumo da linha nas Configurações: "desligado" ou "ligado · 03:30 · NAS (Casa)". */
    fun resumo(ctx: Context): String =
        if (!ligado(ctx)) ctx.getString(if (temSenha(ctx)) R.string.bkn_desligado else R.string.bkn_sem_senha)
        else ctx.getString(R.string.bkn_ligado) + " · " + BackupNoturnoRegras.hhmm(minutoDoDia(ctx)) + " · " + rotuloDestino(ctx, destino(ctx))
}

/** Recebe o alarme do backup noturno (não exportado: só o PendingIntent do próprio launcher chega aqui). */
class BackupNoturnoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // O trabalho (PBKDF2 + gravar + SMB com até 45 s de espera, e talvez um "Fazer agora" na fila) pode passar do
        // limite de ~60 s de um broadcast com goAsync, e aí o Android mataria o processo (a HOME). Então: wake lock
        // parcial com teto de 10 min (a CPU fica acordada até o fim) e o broadcast termina já.
        val wl = ctx.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlauncher:backupNoturno")
            ?.apply { setReferenceCounted(false); acquire(10 * 60_000L) }
        BackupNoturno.peloAlarme(ctx, intent.getIntExtra(BackupNoturno.EXTRA_TENTATIVA, 0)) {
            runCatching { if (wl?.isHeld == true) wl.release() }
        }
    }
}

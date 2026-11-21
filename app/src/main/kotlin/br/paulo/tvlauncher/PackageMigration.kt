package br.paulo.tvlauncher

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import br.paulo.tvlauncher.PackageMigrationRules.Step
import br.paulo.tvlauncher.backup.BackupCompleto
import br.paulo.tvlauncher.panel.PainelTls
import br.paulo.tvlauncher.privilege.Congelar
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.privilege.PrivilegioDO
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Troca de pacote SEM reset (19/11/2026, decisão do Paulo): br.paulo.tvlauncher (antigo, "source") passa o Device Owner
 * e TODOS os dados para tv.sofaly.launcher (novo, "target"). O mesmo código roda nos dois; o papel vem do packageName.
 *
 * Quem conduz é o ANTIGO ([start]), numa thread de fundo:
 *  1. confere: somos o dono, o novo está instalado e assinado com a MESMA chave (sem isso, nada);
 *  2. pelo shell próprio: `pm grant` WRITE_SECURE_SETTINGS ao novo e `dpm set-active-admin` do AdminReceiver dele
 *     (transferOwnership exige que o alvo já seja administrador ativo, de outro pacote, com <support-transfer-ownership/>);
 *  3. pede ao novo "import" ([MigrationProvider], processo ":migration" do novo): o novo puxa do antigo um zip com
 *     shared_prefs/files/databases/no_backup/Android-data e os segredos do Keystore em claro (só entre apps da nossa
 *     chave: permissão de assinatura), regrava tudo nos dados dele e tranca os segredos no Keystore dele;
 *  4. tira a HOME persistente do antigo e chama transferOwnership(antigo -> novo). É atômico: se falhar, o antigo
 *     continua dono e a TV fica como estava (o novo é ocultado);
 *  5. o novo recebe [onOwnershipReceived] (AdminReceiver): oculta o antigo (sem desinstalar), troca a acessibilidade,
 *     fixa a HOME dele e reinicia o próprio processo.
 *
 * Volta ([rollback], só pelo adb no novo): mostra o antigo de novo, ativa o admin dele, devolve o dono e a acessibilidade.
 * Os dados do antigo nunca são apagados aqui; desinstalar o antigo é um comando à parte ([removeLegacy]), irreversível,
 * só quando o Paulo mandar.
 */
object PackageMigration {
    private const val TAG = "TvLauncher.Migration"
    private const val STEP = "step"
    private const val MESSAGE = "message"
    private const val AT = "at"
    private const val PAUSED = "paused"           // no antigo, depois de uma volta: não migra sozinho de novo
    private const val AUTO = "auto"               // no antigo: migrar sozinho de madrugada (desligado: só pedindo)
    private const val FILES = "files"
    private const val BYTES = "bytes"
    const val METHOD_IMPORT = "import"
    const val METHOD_SECRETS = "secrets"
    const val DATA_FILE = "data.zip"
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024
    private const val ADMIN_CLASS = "${AppIdentity.NAMESPACE}.AdminReceiver"

    enum class Role { SOURCE, TARGET, NONE }

    fun role(ctx: Context): Role = when (ctx.packageName) {
        AppIdentity.LEGACY_PACKAGE -> Role.SOURCE
        AppIdentity.NEW_PACKAGE -> Role.TARGET
        else -> Role.NONE
    }

    /** O outro lado: para o antigo, o novo; para o novo, o antigo. */
    fun otherPackage(ctx: Context): String? = when (role(ctx)) {
        Role.SOURCE -> AppIdentity.NEW_PACKAGE
        Role.TARGET -> AppIdentity.LEGACY_PACKAGE
        Role.NONE -> null
    }

    fun authority(pacote: String) = "$pacote.migration"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PackageMigrationRules.PREFS, Context.MODE_PRIVATE)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(pacote: String) = ComponentName(pacote, ADMIN_CLASS)

    private fun mark(ctx: Context, step: Step, msg: String) {
        Log.i(TAG, "$step: $msg")
        prefs(ctx).edit().putString(STEP, step.name).putString(MESSAGE, msg).putLong(AT, System.currentTimeMillis()).commit()
    }

    /** Só o texto (a etapa fica): o resultado de um comando do adb, para o "migration" mostrar. */
    fun note(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        prefs(ctx).edit().putString(MESSAGE, msg).putLong(AT, System.currentTimeMillis()).commit()
    }

    fun step(ctx: Context): Step = runCatching { Step.valueOf(prefs(ctx).getString(STEP, null) ?: "IDLE") }.getOrDefault(Step.IDLE)

    /**
     * No antigo, pelo Atualizador. [sozinho] = a verificação automática de madrugada: só com "migration_auto" ligado e sem
     * pausa de uma volta (19/11/2026: a Sala muda na hora combinada com o Paulo, não sozinha). Pedido por alguém (botão
     * "Instalar agora", MCP atualizar_launcher, "migration_start"): sempre.
     */
    fun allowed(ctx: Context, sozinho: Boolean): Boolean = role(ctx) == Role.SOURCE &&
        (!sozinho || (prefs(ctx).getBoolean(AUTO, false) && !prefs(ctx).getBoolean(PAUSED, false)))

    /** Liga/desliga a migração automática de madrugada (só adb: EXTRAS migration_auto --ez ligado true). */
    fun setAuto(ctx: Context, ligado: Boolean) { prefs(ctx).edit().putBoolean(AUTO, ligado).commit() }

    fun installed(ctx: Context, pacote: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pacote, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Texto para o comando "migration" (adb/MCP): papel, etapa, dono, outro pacote. */
    fun status(ctx: Context): String {
        val p = prefs(ctx)
        val outro = otherPackage(ctx)
        val dono = PrivilegioDO.somosDonos(ctx)
        val quando = p.getLong(AT, 0L).takeIf { it > 0 }?.let { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(it)) }
        return "role=${role(ctx)} package=${ctx.packageName} owner=$dono step=${step(ctx)}" +
            (quando?.let { " at=$it" } ?: "") + " message=${p.getString(MESSAGE, "")}" +
            (outro?.let { " other=$it installed=${installed(ctx, it)} hidden=${runCatching { dono && dpm(ctx).isApplicationHidden(ComponentName(ctx, AdminReceiver::class.java), it) }.getOrDefault(false)}" } ?: "") +
            " files=${p.getInt(FILES, 0)} bytes=${p.getLong(BYTES, 0L)} paused=${p.getBoolean(PAUSED, false)} auto=${p.getBoolean(AUTO, false)}"
    }

    // ------------------------------------------------------------------------------------------------ lado ANTIGO

    /**
     * Roda a migração inteira, BLOQUEANDO (chamar fora da thread da tela). Devolve o texto do resultado. [manual] = pedido
     * pelo adb/MCP: tira a pausa deixada por uma volta.
     */
    @Synchronized
    fun start(ctx: Context, manual: Boolean = false): String {
        val app = ctx.applicationContext
        if (role(app) != Role.SOURCE) return "not the legacy app (${app.packageName})"
        if (android.os.Build.VERSION.SDK_INT < 28) return "Android 9+ needed"
        if (!PrivilegioDO.somosDonos(app)) return fail(app, "this app is not the device owner")
        if (manual) prefs(app).edit().putBoolean(PAUSED, false).commit()
        val novo = AppIdentity.NEW_PACKAGE
        if (!installed(app, novo)) return fail(app, "$novo is not installed")
        PrivilegioDO.ocultar(app, novo, false)   // uma tentativa anterior que falhou deixou o novo oculto (oculto, nem a assinatura se lê)
        // Um app de outra chave nunca recebe o dono nem os dados (o provider já exige a assinatura; isto para antes).
        if (app.packageManager.checkSignatures(app.packageName, novo) != PackageManager.SIGNATURE_MATCH)
            return fail(app, "$novo is signed with another key")
        mark(app, Step.INSTALLED, "starting")

        // 2. Poderes que só o shell dá: escrever Settings.Secure (acessibilidade, protetor) e virar administrador ativo.
        GuardiaoShizuku.comando(app, "pm grant $novo android.permission.WRITE_SECURE_SETTINGS")
        GuardiaoShizuku.comando(app, "dpm set-active-admin --user ${Process.myUid() / 100_000} $novo/$ADMIN_CLASS")
        if (!dpm(app).isAdminActive(admin(novo))) return fail(app, "could not make $novo an active admin (shell off?)")
        if (app.packageManager.checkPermission("android.permission.WRITE_SECURE_SETTINGS", novo) != PackageManager.PERMISSION_GRANTED)
            return fail(app, "could not grant WRITE_SECURE_SETTINGS to $novo")
        mark(app, Step.ADMIN, "$novo is an active admin")

        // 3. O novo copia tudo daqui (ele chama de volta o nosso MigrationProvider).
        val r = try {
            app.contentResolver.call(Uri.parse("content://${authority(novo)}"), METHOD_IMPORT, app.packageName, null)
        } catch (e: Exception) { return fail(app, "import failed: ${e.javaClass.simpleName}: ${e.message}") }
        if (r?.getBoolean("ok") != true) return fail(app, "import failed: ${r?.getString("message") ?: "no answer"}")
        prefs(app).edit().putInt(FILES, r.getInt("files")).putLong(BYTES, r.getLong("bytes")).commit()
        mark(app, Step.IMPORTED, "copied ${r.getInt("files")} files (${r.getLong("bytes") / 1024} KB)")

        // 4. Transferência. A HOME persistente é do pacote que a pôs: sai antes, o novo põe a dele ao receber o dono.
        val nossoAdmin = ComponentName(app, AdminReceiver::class.java)
        runCatching { dpm(app).clearPackagePersistentPreferredActivities(nossoAdmin, app.packageName) }
        try {
            val extras = PersistableBundle().apply { putString("from", app.packageName); putLong("at", System.currentTimeMillis()) }
            dpm(app).transferOwnership(nossoAdmin, admin(novo), extras)
        } catch (e: Exception) {
            PrivilegioDO.aplicar(app)   // a HOME volta para nós
            return fail(app, "transferOwnership failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!dpm(app).isDeviceOwnerApp(novo)) {
            PrivilegioDO.aplicar(app)
            return fail(app, "transfer returned but $novo is not the owner")
        }
        mark(app, Step.TRANSFERRED, "$novo is the device owner")
        // O novo nos oculta ao receber o dono (o Android mata este processo). Se não ocultar em 20 s, saímos assim mesmo
        // para não haver duas HOMEs e dois painéis na mesma porta.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ killOwnProcesses(app, includeSelf = true) }, 20_000)
        return "migrated to $novo"
    }

    private fun fail(app: Context, msg: String): String {
        mark(app, Step.FAILED, msg)
        // Ainda somos donos (a transferência é atômica): o novo não pode ficar visível como segunda HOME.
        if (role(app) == Role.SOURCE && PrivilegioDO.somosDonos(app) && installed(app, AppIdentity.NEW_PACKAGE))
            PrivilegioDO.ocultar(app, AppIdentity.NEW_PACKAGE, true)
        return "failed: $msg"
    }

    /** Segredos que o Keystore deste app tranca, em claro (só para o provider, que só responde ao app da nossa chave). */
    fun exportSecrets(ctx: Context): Bundle {
        val app = ctx.applicationContext
        val b = Bundle()
        b.putString("backup", BackupCompleto.migrationSecrets(app).toString())
        val certs = PainelTls.certificates(app)
        val chave = PainelTls.plainKey(app)
        if (certs != null && chave != null) {
            b.putByteArray("painel_tls_key", chave)
            b.putByteArray("painel_tls_ca", certs.first)
            b.putByteArray("painel_tls_server", certs.second)
        }
        return b
    }

    // ------------------------------------------------------------------------------------------------ lado NOVO

    /**
     * Copia os dados do antigo para cá (chamado pelo provider, no processo ":migration" do novo). Recusa se este app já
     * é dono: uma segunda cópia apagaria o que mudou depois da migração.
     */
    fun importFromLegacy(ctx: Context): Bundle {
        val app = ctx.applicationContext
        val r = Bundle()
        fun erro(msg: String): Bundle { mark(app, Step.FAILED, msg); r.putBoolean("ok", false); r.putString("message", msg); return r }
        if (role(app) != Role.TARGET) return erro("not the new app")
        if (PrivilegioDO.somosDonos(app)) return erro("already the device owner: refusing to overwrite data")
        val antigo = AppIdentity.LEGACY_PACKAGE
        // O resto do app (tela, serviços) não pode estar rodando com as preferências antigas na memória.
        killOwnProcesses(app, includeSelf = false)
        val segredos = try {
            app.contentResolver.call(Uri.parse("content://${authority(antigo)}"), METHOD_SECRETS, null, null)
        } catch (e: Exception) { return erro("secrets: ${e.javaClass.simpleName}: ${e.message}") } ?: return erro("secrets: no answer")

        val dados = File(app.applicationInfo.dataDir)
        val externo = app.getExternalFilesDir(null)
        val staging = File(app.noBackupFilesDir.parentFile, "migration-staging").apply { deleteRecursively(); mkdirs() }
        var arquivos = 0; var total = 0L
        try {
            // 1. Tudo vai primeiro para uma pasta à parte: um zip estragado no meio não deixa metade dos dados trocada.
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse("content://${authority(antigo)}/$DATA_FILE"), "r")
                ?: return erro("data: no file")
            ZipInputStream(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val rel = e.name
                    if (!PackageMigrationRules.shouldCopy(rel)) { Log.w(TAG, "skipped $rel"); continue }
                    // Espaço: a cópia ocupa o dobro por uns segundos (os dados do antigo + a cópia). Com pouco disco, para
                    // antes de encher (a TV toda sofre com o disco cheio) e nada muda.
                    if (staging.usableSpace < MIN_FREE_BYTES) throw IllegalStateException("not enough free space")
                    val destino = File(staging, rel)
                    destino.parentFile?.mkdirs()
                    var n = 0L
                    destino.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val k = zip.read(buf); if (k < 0) break
                            n += k; total += k
                            if (n > PackageMigrationRules.MAX_FILE_BYTES) throw IllegalStateException("$rel is too big")
                            if (total > PackageMigrationRules.MAX_TOTAL_BYTES) throw IllegalStateException("data is too big")
                            out.write(buf, 0, k)
                        }
                    }
                    if (PackageMigrationRules.shouldRewrite(rel) && n <= PackageMigrationRules.MAX_REWRITE_BYTES) {
                        val t = destino.readText()
                        val novo = PackageMigrationRules.rewritePackage(t)
                        if (novo != t) destino.writeText(novo)
                    }
                    arquivos++
                }
            }
            if (arquivos == 0) return erro("data: empty (legacy app sent nothing)")

            // 2. Troca: apaga os dados de fábrica deste app (instalado agora, nunca usado) e põe os copiados.
            for (raiz in PackageMigrationRules.COPIED_ROOTS) {
                val alvo = if (raiz == "external") externo else File(dados, raiz)
                val veio = File(staging, raiz)
                if (alvo == null) continue
                if (raiz == "shared_prefs") {
                    // O estado desta migração fica (é deste lado); o resto sai.
                    alvo.listFiles()?.filter { it.name != "${PackageMigrationRules.PREFS}.xml" }?.forEach { it.deleteRecursively() }
                } else alvo.deleteRecursively()
                if (!veio.exists()) continue
                alvo.mkdirs()
                veio.walkTopDown().filter { it.isFile }.forEach { f ->
                    val d = File(alvo, f.relativeTo(veio).path)
                    d.parentFile?.mkdirs()
                    if (!f.renameTo(d)) { f.copyTo(d, overwrite = true); f.delete() }
                }
            }
        } catch (e: Exception) {
            return erro("data: ${e.javaClass.simpleName}: ${e.message}")
        } finally { staging.deleteRecursively() }

        // 3. Segredos: trancados de novo pelo Keystore deste app (as cópias cifradas pelo antigo não abrem aqui).
        try {
            segredos.getString("backup")?.let { BackupCompleto.applyMigrationSecrets(app, org.json.JSONObject(it)) }
        } catch (e: Exception) { Log.w(TAG, "secrets: $e") }
        try {
            val chave = segredos.getByteArray("painel_tls_key"); val ca = segredos.getByteArray("painel_tls_ca")
            val srv = segredos.getByteArray("painel_tls_server")
            if (chave != null && ca != null && srv != null) PainelTls.restorePlainKey(app, chave, ca, srv)
        } catch (e: Exception) {
            // Os ajustes já estão aqui; só um segredo não passou (ex.: o Home Assistant pede o token de novo). Não trava.
            Log.w(TAG, "secrets: $e")
        }
        prefs(app).edit().putInt(FILES, arquivos).putLong(BYTES, total).commit()
        mark(app, Step.IMPORTED, "copied $arquivos files from $antigo")
        r.putBoolean("ok", true); r.putInt("files", arquivos); r.putLong("bytes", total)
        return r
    }

    /** AdminReceiver.onTransferOwnershipComplete: este app acabou de virar dono (migração, ou a volta no antigo). */
    fun onOwnershipReceived(ctx: Context, extras: PersistableBundle?) {
        val app = ctx.applicationContext
        val de = extras?.getString("from")
        Log.i(TAG, "device owner received from $de")
        val outro = otherPackage(app) ?: return
        swapAccessibility(app, de = outro)
        when (role(app)) {
            Role.TARGET -> mark(app, Step.DONE, "device owner received from $de; $outro hidden")
            Role.SOURCE -> { prefs(app).edit().putBoolean(PAUSED, true).commit(); mark(app, Step.ROLLED_BACK, "device owner back from $de; $outro hidden") }
            Role.NONE -> {}
        }
        PrivilegioDO.aplicar(app)   // HOME persistente, permissões fixas, otimizações
        // A nossa HOME na frente agora (Device Owner pode abrir tela do fundo) e, 5 s depois, o processo recomeça do zero
        // como no REINICIAR (o Android o sobe de novo pela acessibilidade ligada): painel, MCP e túnel sobem limpos, com
        // o outro app já fora das portas.
        runCatching {
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(app, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "home: $it") }
        // Fora da thread da tela (o shell espera até 20 s): o papel de HOME e o outro app fora de cena.
        Thread({ leaveStage(app, outro) }, "migration-hide").start()
        // Recomeço DEPOIS que o outro lado saiu (ele sai sozinho em 20 s). Na Sala (20/11/2026) o recomeço em 5 s achou as
        // portas 8080/8091 e o túnel ainda com o antigo: painel e MCP não subiram até um REINICIAR.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ killOwnProcesses(app, includeSelf = true) }, RESTART_AFTER_MS)
    }

    /** O recomeço do app que recebeu o dono: depois dos 20 s em que o outro lado ainda pode estar de pé. */
    private const val RESTART_AFTER_MS = 25_000L

    /**
     * O outro app sai de cena sem perder nada. Antes, o papel de HOME passa para nós: com ele, o Android NÃO deixa o
     * dono ocultar o outro (visto na Sala, 20/11/2026: setApplicationHidden voltou false e o "One TV" ficou na lista).
     * Se mesmo assim não ocultar, congela pelo shell (pm disable-user; a volta descongela em [PrivilegioDO.ocultar]).
     */
    private fun leaveStage(app: Context, outro: String) {
        try {
            GuardiaoShizuku.comando(app, "cmd role add-role-holder android.app.role.HOME ${app.packageName} 0")
            if (!PrivilegioDO.ocultar(app, outro, true) && !Congelar.definir(app, outro, true))
                Log.w(TAG, "$outro is still visible (hide and freeze failed)")
        } catch (e: Exception) { Log.w(TAG, "leave stage: $e") }
    }

    /** Nosso serviço de acessibilidade no lugar do de [de] (precisa de WRITE_SECURE_SETTINGS, dada pelo shell). */
    private fun swapAccessibility(app: Context, de: String) {
        try {
            val cr = app.contentResolver
            val meu = ComponentName(app, AutorizaShizukuService::class.java).flattenToString()
            val atual = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                PackageMigrationRules.swapAccessibility(atual, de, meu))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: Exception) { Log.w(TAG, "accessibility: $e") }
    }

    /**
     * A volta (só pelo adb no app NOVO): devolve o dono ao antigo, que volta com os dados que tinha na hora da migração.
     * O antigo fica pausado (não tenta migrar sozinho de novo); "migration_start" nele tenta outra vez.
     */
    @Synchronized
    fun rollback(ctx: Context): String {
        val app = ctx.applicationContext
        if (role(app) != Role.TARGET) return "rollback runs in the new app"
        if (!PrivilegioDO.somosDonos(app)) return "this app is not the device owner"
        val antigo = AppIdentity.LEGACY_PACKAGE
        if (!installed(app, antigo)) return "$antigo is not installed: no way back without it"
        PrivilegioDO.ocultar(app, antigo, false)
        GuardiaoShizuku.comando(app, "dpm set-active-admin --user ${Process.myUid() / 100_000} $antigo/$ADMIN_CLASS")
        if (!dpm(app).isAdminActive(admin(antigo))) return "could not make $antigo an active admin (shell off?)"
        val nossoAdmin = ComponentName(app, AdminReceiver::class.java)
        runCatching { dpm(app).clearPackagePersistentPreferredActivities(nossoAdmin, app.packageName) }
        try {
            dpm(app).transferOwnership(nossoAdmin, admin(antigo),
                PersistableBundle().apply { putString("from", app.packageName); putBoolean("rollback", true) })
        } catch (e: Exception) {
            PrivilegioDO.aplicar(app)
            return "transferOwnership failed: ${e.javaClass.simpleName}: ${e.message}"
        }
        if (!dpm(app).isDeviceOwnerApp(antigo)) { PrivilegioDO.aplicar(app); return "transfer returned but $antigo is not the owner" }
        mark(app, Step.ROLLED_BACK, "device owner returned to $antigo")
        // O antigo nos oculta ao receber o dono. Até lá a acessibilidade dele volta por ele mesmo (onOwnershipReceived).
        return "device owner returned to $antigo"
    }

    /**
     * Desinstala o antigo (só no novo, dono, depois da migração). IRREVERSÍVEL: some o caminho de volta. Só pelo adb,
     * quando o Paulo mandar.
     */
    fun removeLegacy(ctx: Context): String {
        val app = ctx.applicationContext
        if (role(app) != Role.TARGET || !PrivilegioDO.somosDonos(app)) return "only the new app, as device owner"
        if (step(app) != Step.DONE) return "migration not done (step=${step(app)})"
        val antigo = AppIdentity.LEGACY_PACKAGE
        if (!installed(app, antigo)) return "$antigo is not installed"
        PrivilegioDO.ocultar(app, antigo, false)   // oculto, o PackageInstaller não o acha
        val pi = android.app.PendingIntent.getBroadcast(app, 7231, Intent("${AppIdentity.NAMESPACE}.MIGRATION_UNINSTALLED").setPackage(app.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        app.packageManager.packageInstaller.uninstall(antigo, pi.intentSender)
        Log.i(TAG, "uninstall of $antigo requested")
        return "uninstall of $antigo requested"
    }

    /** Mata os outros processos deste app (e este, com [includeSelf]). Só processos do nosso uid. */
    private fun killOwnProcesses(app: Context, includeSelf: Boolean) {
        val eu = Process.myPid()
        val am = app.getSystemService(ActivityManager::class.java)
        am?.runningAppProcesses?.filter { it.uid == Process.myUid() && it.pid != eu }?.forEach {
            Log.i(TAG, "stopping ${it.processName}"); Process.killProcess(it.pid)
        }
        if (includeSelf) Process.killProcess(eu)
    }

    /** No antigo, pelo Atualizador: instala o APK novo (mesma chave conferida antes), em silêncio (Device Owner). */
    fun installNewApk(app: Context, arquivo: File, aviso: android.content.IntentSender) {
        val instalador = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(AppIdentity.NEW_PACKAGE)
            setSize(arquivo.length())
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            try {
                sessao.openWrite("sofaly", 0, arquivo.length()).use { saida ->
                    arquivo.inputStream().use { it.copyTo(saida) }
                    sessao.fsync(saida)
                }
            } catch (e: Exception) { runCatching { sessao.abandon() }; throw e }
            sessao.commit(aviso)
        }
    }
}

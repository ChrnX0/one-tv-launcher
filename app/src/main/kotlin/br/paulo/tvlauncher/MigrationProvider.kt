package br.paulo.tvlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * O canal da troca de pacote (PackageMigration.kt), nos dois apps. Roda no processo ":migration" (LauncherApp não
 * sobe nada nele) e só atende quem tem a permissão `tv.sofaly.permission.MIGRATION`, de nível ASSINATURA: na prática,
 * só o outro app da troca, assinado com a nossa chave. Além disso confere o pacote de quem chama.
 *
 *  - No ANTIGO: "secrets" (segredos do Keystore em claro) e o arquivo "data.zip" (os dados do app, em fluxo).
 *  - No NOVO: "import" (copiar tudo do antigo agora; quem chama é o antigo, de dentro do [PackageMigration.start]).
 */
class MigrationProvider : ContentProvider() {
    private val tag = "TvLauncher.Migration"

    override fun onCreate() = true

    /** Quem chama é o outro lado da troca? (A permissão de assinatura já barrou o resto; isto barra outro app nosso.) */
    private fun callerIsOther(): Boolean {
        val ctx = context ?: return false
        val outro = PackageMigration.otherPackage(ctx) ?: return false
        return ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains(outro) == true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!callerIsOther()) { Log.w(tag, "refused $method from uid ${Binder.getCallingUid()}"); return null }
        val role = PackageMigration.role(ctx)
        return when {
            method == PackageMigration.METHOD_SECRETS && role == PackageMigration.Role.SOURCE ->
                limpo { PackageMigration.exportSecrets(ctx) }
            method == PackageMigration.METHOD_IMPORT && role == PackageMigration.Role.TARGET ->
                limpo { PackageMigration.importFromLegacy(ctx) }
            else -> null
        }
    }

    /** Roda com a identidade deste app (não a de quem chamou): ler/gravar os próprios dados e chamar o outro provider. */
    private fun <T> limpo(bloco: () -> T): T {
        val id = Binder.clearCallingIdentity()
        try { return bloco() } finally { Binder.restoreCallingIdentity(id) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (!callerIsOther() || PackageMigration.role(ctx) != PackageMigration.Role.SOURCE) throw SecurityException("not allowed")
        if (mode != "r" || uri.lastPathSegment != PackageMigration.DATA_FILE) throw java.io.FileNotFoundException(uri.toString())
        val (leitura, escrita) = ParcelFileDescriptor.createReliablePipe()
        val app = ctx.applicationContext
        Thread({
            try {
                ZipOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(escrita).buffered()).use { zip -> escrever(app, zip) }
            } catch (e: Exception) {
                Log.w(tag, "data.zip: $e")
                // O lado que lê recebe o erro (não um zip curto que pareceria completo).
                runCatching { escrita.closeWithError("${e.javaClass.simpleName}: ${e.message}") }
            }
        }, "migration-export").start()
        return leitura
    }

    /** Escreve o zip: as pastas de [PackageMigrationRules.COPIED_ROOTS], arquivo por arquivo, só o que a regra deixa. */
    private fun escrever(app: android.content.Context, zip: ZipOutputStream) {
        val dados = File(app.applicationInfo.dataDir)
        var total = 0L; var n = 0
        for (raiz in PackageMigrationRules.COPIED_ROOTS) {
            val pasta = if (raiz == "external") app.getExternalFilesDir(null) else File(dados, raiz)
            if (pasta == null || !pasta.isDirectory) continue
            pasta.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.forEach { f ->
                val rel = "$raiz/" + f.relativeTo(pasta).invariantSeparatorsPath
                if (!PackageMigrationRules.shouldCopy(rel)) return@forEach
                if (f.length() > PackageMigrationRules.MAX_FILE_BYTES) { Log.w(tag, "too big, left out: $rel"); return@forEach }
                total += f.length()
                if (total > PackageMigrationRules.MAX_TOTAL_BYTES) throw IllegalStateException("data is too big")
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        Log.i(tag, "data.zip: $n files, ${total / 1024} KB")
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

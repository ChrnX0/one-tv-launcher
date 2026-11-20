package br.paulo.tvlauncher

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.Process
import android.util.Log

/**
 * Estado e ações da tela "Pen drive e armazenamento" (StorageActivity). A regra pura fica em StorageModeRules.kt.
 * Tudo que mexe no disco passa pelo shell próprio (GuardiaoShizuku, uid 2000): `sm` e `pm move-primary-storage`
 * não existem para apps comuns. Rode as funções que chamam o shell FORA da thread da tela.
 */
object StorageMode {
    private const val TAG = "TvLauncher.Storage"
    private const val PREFS = "app_storage"
    private const val CHAVE_MISTOS = "mixed_public_uuids"

    /** Um pen drive/cartão: [disco] = "disk:8,0"; [comum] = caminho da parte comum montada (para medir e copiar). */
    data class Pen(
        val disco: String,
        val modo: StorageModeRules.Modo,
        val volumes: List<StorageModeRules.Volume>,
        val tamanho: Long,
        val comum: String?,
        val adotadoUuid: String?,
    )

    data class Estado(val pens: List<Pen>, val livreTv: Long, val totalTv: Long, val primarioNoPen: Boolean)

    /** Volume gravável, no formato combinado com a thread de players (GravacaoDestino). */
    data class Gravavel(val id: String, val nome: String, val caminho: String, val livres: Long, val tipo: Tipo)
    enum class Tipo { PUBLIC, ADOPTED, MIXED_PUBLIC }

    private fun statfs(caminho: String): Pair<Long, Long>? = try {
        val s = StatFs(caminho); s.totalBytes to s.availableBytes
    } catch (_: Exception) { null }

    /** Lê discos e volumes pelo shell. null = sem shell. */
    fun ler(ctx: Context): Estado? {
        val saida = GuardiaoShizuku.comando(ctx,
            "sm list-disks adoptable; echo ---; sm list-volumes all; echo ---; sm get-primary-storage-uuid; echo ---; df -k /mnt/expand/* 2>/dev/null",
            15_000) ?: return null
        val partes = saida.split(Regex("(?m)^---\\s*$"))
        val discos = StorageModeRules.discos(partes.getOrElse(0) { "" })
        val vols = StorageModeRules.volumes(partes.getOrElse(1) { "" })
        val primario = partes.getOrElse(2) { "" }.trim()
        val tamanhosExpand = StorageModeRules.df(partes.getOrElse(3) { "" })
        val sm = ctx.getSystemService(StorageManager::class.java)
        val montados = sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
        val pens = discos.map { d ->
            val dv = StorageModeRules.doDisco(d, vols)
            val pub = dv.firstOrNull { it.tipo == StorageModeRules.Tipo.PUBLICO && it.montado }
            val pri = dv.firstOrNull { it.tipo == StorageModeRules.Tipo.PRIVADO && it.montado }
            val caminhoPub = pub?.fsUuid?.let { u -> montados.firstOrNull { it.uuid.equals(u, true) }?.directory?.absolutePath }
            val tamPub = caminhoPub?.let { statfs(it)?.first } ?: 0L
            val tamPri = pri?.fsUuid?.let { tamanhosExpand["/mnt/expand/$it"]?.first } ?: 0L
            Pen(d, StorageModeRules.modo(dv), dv, tamPub + tamPri, caminhoPub, pri?.fsUuid)
        }
        // Parte comum de um misto: guardada para gravaveis() (que roda sem shell) dizer MIXED_PUBLIC.
        val mistos = pens.filter { it.modo == StorageModeRules.Modo.MISTO }.flatMap { p ->
            p.volumes.filter { it.tipo == StorageModeRules.Tipo.PUBLICO }.mapNotNull { it.fsUuid } }.toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(CHAVE_MISTOS, mistos).apply()
        val tv = statfs(Environment.getDataDirectory().absolutePath) ?: (0L to 0L)
        return Estado(pens, livreTv = tv.second, totalTv = tv.first,
            primarioNoPen = primario.isNotEmpty() && primario != "null" && pens.any { it.adotadoUuid == primario })
    }

    /**
     * Mede a gravação na parte comum (32 MB com fsync, depois apaga). MB/s ou null. Só antes de formatar: depois de
     * adotado, o próprio Android mede.
     */
    fun medir(ctx: Context, comum: String): Double? {
        if (!StorageModeRules.caminhoSeguro(comum)) return null
        val f = "$comum/.sofaly-speed-test"
        val saida = GuardiaoShizuku.comando(ctx, "dd if=/dev/zero of='$f' bs=1048576 count=32 conv=fsync 2>&1; rm -f '$f'", 60_000)
        return saida?.let { StorageModeRules.velocidadeDd(it) }
    }

    /** Formata. APAGA o pen drive. null = deu certo; senão o motivo. Até 20 min (o Android mede o disco ao adotar). */
    fun formatar(ctx: Context, disco: String, modo: StorageModeRules.Modo, comumPorcento: Int): String? {
        val cmd = StorageModeRules.comandoParticao(disco, modo, comumPorcento) ?: return "invalid"
        val saida = GuardiaoShizuku.comando(ctx, cmd, 20 * 60_000L)
        val motivo = StorageModeRules.falhaParticao(saida)
        Log.i(TAG, "$cmd => ${motivo ?: "ok"}")
        if (motivo == null) AppStorage.atualizarEmFundo(ctx)
        return motivo
    }

    /**
     * Leva a área compartilhada (fotos, downloads, gravações da memória da TV) para o volume adotado [uuid], ou de volta
     * ([uuid] null). O caminho continua /storage/emulated/0; muda o disco por baixo. Até 30 min.
     */
    fun moverCompartilhado(ctx: Context, uuid: String?): String? {
        val alvo = uuid ?: "internal"
        if (uuid != null && !StorageModeRules.uuidSeguro(uuid)) return "invalid"
        val saida = GuardiaoShizuku.comando(ctx, "pm move-primary-storage $alvo", 30 * 60_000L)
        return AppStorageRules.falha(saida)
    }

    /** Apps que podem ir para o pen drive (ou voltar), do maior para o menor. Tamanho = app + dados + cache. */
    data class AppMovel(val pacote: String, val nome: String, val bytes: Long, val noExterno: Boolean)

    fun appsMoveis(ctx: Context): List<AppMovel> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            val pode = AppStorageRules.podeMover(ai.packageName, ctx.packageName, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                PainelApps.protegido(ctx, ai.packageName) || AppIdentity.ours(ai.packageName))
            if (!pode) return@mapNotNull null
            val bytes = try {
                val s = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle())
                s.appBytes + s.dataBytes + s.cacheBytes
            } catch (_: Exception) { 0L }
            AppMovel(ai.packageName, ai.loadLabel(pm).toString(), bytes, ai.storageUuid != StorageManager.UUID_DEFAULT)
        }.sortedByDescending { it.bytes }
    }

    /** Volumes onde dá para gravar (para GravacaoDestino dos players), sem shell. */
    fun gravaveis(ctx: Context): List<Gravavel> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val mistos = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CHAVE_MISTOS, emptySet()).orEmpty()
        val out = mutableListOf<Gravavel>()
        for (v in sm.storageVolumes) {
            val dir = v.directory ?: continue
            if (v.state != Environment.MEDIA_MOUNTED) continue
            val livres = statfs(dir.absolutePath)?.second ?: continue
            val tipo = when {
                v.isPrimary && v.isEmulated -> if (sm.getUuidForPath(dir) != StorageManager.UUID_DEFAULT) Tipo.ADOPTED else continue
                v.isRemovable -> if (v.uuid != null && mistos.any { it.equals(v.uuid, true) }) Tipo.MIXED_PUBLIC else Tipo.PUBLIC
                else -> continue
            }
            out += Gravavel(v.uuid ?: "primary", v.getDescription(ctx), dir.absolutePath, livres, tipo)
        }
        return out
    }
}

package br.paulo.tvlauncher.backup

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import br.paulo.tvlauncher.AppIdentity
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.home.AppStorage
import br.paulo.tvlauncher.home.AppStorageRules
import br.paulo.tvlauncher.privilege.GuardiaoShizuku

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
        /** Gravação medida (MB/s) da parte comum, guardada por volume; null = ainda não medida. */
        val velocidade: Double? = null,
        /** fsUuid da parte comum (chave da medida guardada). */
        val comumUuid: String? = null,
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
        val montados = sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.folder != null }
        val pens = discos.map { d ->
            val dv = StorageModeRules.doDisco(d, vols)
            val pub = dv.firstOrNull { it.tipo == StorageModeRules.Tipo.PUBLICO && it.montado }
            val pri = dv.firstOrNull { it.tipo == StorageModeRules.Tipo.PRIVADO && it.montado }
            val caminhoPub = pub?.fsUuid?.let { u -> montados.firstOrNull { it.uuid.equals(u, true) }?.folder?.absolutePath }
            val tamPub = caminhoPub?.let { statfs(it)?.first } ?: 0L
            val tamPri = pri?.fsUuid?.let { tamanhosExpand["/mnt/expand/$it"]?.first } ?: 0L
            Pen(d, StorageModeRules.modo(dv), dv, tamPub + tamPri, caminhoPub, pri?.fsUuid,
                pub?.fsUuid?.let { velocidadeGuardada(ctx, it) }, pub?.fsUuid)
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
        val v = saida?.let { StorageModeRules.velocidadeDd(it) } ?: return null
        // Guarda pelo nome do volume (o fim do caminho): a tela e o painel recomendam sozinhos da próxima vez.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("speed:" + comum.substringAfterLast('/').lowercase(), v.toFloat()).apply()
        return v
    }

    fun velocidadeGuardada(ctx: Context, fsUuid: String): Double? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = "speed:" + fsUuid.lowercase()
        return if (p.contains(k)) p.getFloat(k, 0f).toDouble() else null
    }

    @Volatile private var medindo = false
    /** Mede em segundo plano, uma vez por vez (painel: o cartão mostra a recomendação certa na próxima leitura). */
    fun medirEmFundo(ctx: Context, comum: String) {
        synchronized(this) { if (medindo) return; medindo = true }
        Fundo.rodar("StorageSpeed") { try { medir(ctx.applicationContext, comum) } finally { medindo = false } }
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
    data class AppMovel(val pacote: String, val nome: String, val bytes: Long, val noExterno: Boolean, val partes: Int = 1) {
        /** Grande demais para mover sem aviso (AppStorageRules.arriscado). */
        val arriscado get() = AppStorageRules.arriscado(bytes, partes)
    }

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
            AppMovel(ai.packageName, ai.loadLabel(pm).toString(), bytes, ai.storageUuid != StorageManager.UUID_DEFAULT,
                1 + (ai.splitSourceDirs?.size ?: 0))
        }.sortedByDescending { it.bytes }
    }

    /** Um app só, para o menu do OK segurado. null = não achou. Fora da thread da tela. */
    fun appMovel(ctx: Context, pacote: String): AppMovel? = try {
        val ai = ctx.packageManager.getApplicationInfo(pacote, 0)
        val bytes = try {
            val st = ctx.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(ai.storageUuid, pacote, Process.myUserHandle())
            st.appBytes + st.dataBytes + st.cacheBytes
        } catch (_: Exception) { 0L }
        AppMovel(pacote, ai.loadLabel(ctx.packageManager).toString(), bytes, ai.storageUuid != StorageManager.UUID_DEFAULT,
            1 + (ai.splitSourceDirs?.size ?: 0))
    } catch (_: Exception) { null }

    /** Volumes onde dá para gravar (para GravacaoDestino dos players), sem shell. */
    fun gravaveis(ctx: Context): List<Gravavel> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val mistos = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CHAVE_MISTOS, emptySet()).orEmpty()
        val out = mutableListOf<Gravavel>()
        for (v in sm.storageVolumes) {
            val dir = v.folder ?: continue
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

    /**
     * Sofy, memória quase cheia (proposta C): com menos de 1 GB livre na TV e um pen drive já como memória da TV, sugere
     * o maior app que ainda está na TV. Uma vez por dia, no máximo. Roda fora da thread da tela. null = nada a dizer.
     */
    fun sugestao(ctx: Context): Pair<String, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        if (agora - p.getLong(CHAVE_SUGESTAO, 0L) < 24 * 3600_000L) return null
        val livre = statfs(Environment.getDataDirectory().absolutePath)?.second ?: return null
        if (livre >= 1024L * 1024 * 1024) return null
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("adopted_uuid", null) == null) return null
        val maior = appsMoveis(ctx).firstOrNull { !it.noExterno && it.bytes >= 100L * 1024 * 1024 } ?: return null
        p.edit().putLong(CHAVE_SUGESTAO, agora).apply()
        return maior.nome to maior.bytes
    }
    private const val CHAVE_SUGESTAO = "advice_at"
}

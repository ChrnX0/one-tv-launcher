package br.paulo.tvlauncher.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.storage.StorageManager
import android.util.Log
import br.paulo.tvlauncher.AppIdentity
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.privilege.GuardiaoShizuku

/**
 * "Instalar no armazenamento externo" / "Instalar no dispositivo" no menu de segurar OK num app (pedido do Paulo,
 * 20/11/2026). Porquê: Chromecast e outras TVs baratas têm 4-8 GB e enchem (a Sala estava com 881 MB livres). Para quê:
 * tirar da memória da TV os apps pesados (jogos, emuladores) sem desinstalar nada. Como: um pen drive ADOTADO (modo
 * "memória da TV" ou a parte privada do "misto", feito por `sm partition`) aceita apps; o Android move com
 * `pm move-package`, que só o shell (uid 2000) pode rodar, então vai pela fila única do adb (GuardiaoShizuku).
 * `force_allow_on_external=1` deixa mover também os apps que não declaram installLocation (é o "Forçar permissão em
 * armazenamento externo" das Opções do desenvolvedor).
 *
 * A parte pura (sem Android) fica em AppStorageRules.kt, testada no computador (AppStorageTest).
 */
object AppStorage {
    private const val TAG = "TvLauncher.AppStorage"
    private const val PREFS = "app_storage"
    private const val CHAVE_UUID = "adopted_uuid"

    /** Para o MenuApp: null = sem opção; true = pode ir para o externo; false = pode voltar ao dispositivo. */
    fun opcaoMenu(ctx: Context, pacote: String): Boolean? = when (destino(ctx, pacote)) {
        AppStorageRules.Destino.EXTERNO -> true
        AppStorageRules.Destino.DISPOSITIVO -> false
        AppStorageRules.Destino.NENHUM -> null
    }

    private fun info(ctx: Context, pacote: String): ApplicationInfo? =
        try { ctx.packageManager.getApplicationInfo(pacote, 0) } catch (_: Exception) { null }

    /** O app mora num volume que não é a memória interna (storageUuid é API pública desde o Android 8). */
    fun noExterno(ctx: Context, pacote: String): Boolean =
        info(ctx, pacote)?.let { it.storageUuid != StorageManager.UUID_DEFAULT } ?: false

    private fun uuidGuardado(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_UUID, null)

    private fun guardarUuid(ctx: Context, uuid: String?) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (uuid == null) remove(CHAVE_UUID) else putString(CHAVE_UUID, uuid)
        }.apply()

    /** O que o menu mostra para o app. Rápido (sem shell): usa o último volume adotado visto por [atualizar]. */
    fun destino(ctx: Context, pacote: String): AppStorageRules.Destino {
        val ai = info(ctx, pacote) ?: return AppStorageRules.Destino.NENHUM
        val pode = AppStorageRules.podeMover(pacote, ctx.packageName, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            PainelApps.protegido(ctx, pacote) || AppIdentity.ours(pacote))
        return AppStorageRules.destino(pode, ai.storageUuid != StorageManager.UUID_DEFAULT, uuidGuardado(ctx) != null)
    }

    /** Pergunta ao Android quais volumes adotados estão montados e guarda o 1º. Fora da thread da tela. */
    fun atualizar(ctx: Context): String? {
        val saida = GuardiaoShizuku.comando(ctx, "sm list-volumes private", 10_000) ?: return uuidGuardado(ctx)
        val uuid = AppStorageRules.adotados(saida).firstOrNull()
        guardarUuid(ctx, uuid)
        // Apps que já estão no pen drive (movidos por aqui, pelo shell ou pelas Configurações do Android) entram na lista
        // do aviso "o pen drive saiu", com o nome guardado enquanto o Android ainda o conhece.
        runCatching {
            ctx.packageManager.getInstalledApplications(0).filter { it.storageUuid != StorageManager.UUID_DEFAULT }
                .forEach { lembrar(ctx, it.packageName, fora = true) }
        }
        return uuid
    }

    /** Em segundo plano, para o menu já saber na próxima vez (abrir a HOME, pen drive entrou ou saiu). */
    fun atualizarEmFundo(ctx: Context) {
        val app = ctx.applicationContext
        Fundo.rodar("AppStorage") { try { atualizar(app) } catch (e: Throwable) { Log.w(TAG, "atualizar: ${e.message}") } }
    }

    /**
     * Move o app. Bloqueia (até 15 min: 1 GB num pen drive de ~11 MB/s leva ~1,5 min, um lento bem mais); rode fora da
     * thread da tela. Devolve null se deu certo, ou o motivo.
     */
    fun mover(ctx: Context, pacote: String, paraExterno: Boolean): String? {
        val uuid = if (paraExterno) (atualizar(ctx) ?: return "sem_adotado") else null
        if (paraExterno) GuardiaoShizuku.comando(ctx, "settings put global force_allow_on_external 1", 10_000)
        val cmd = AppStorageRules.comando(pacote, uuid) ?: return "invalid"
        val saida = GuardiaoShizuku.comando(ctx, cmd, 15 * 60_000L)
        val motivo = AppStorageRules.falha(saida)
        Log.i(TAG, "$cmd => ${motivo ?: "ok"}")
        if (motivo == null) lembrar(ctx, pacote, paraExterno)
        return motivo
    }

    private const val CHAVE_MOVIDOS = "moved_packages"
    @Volatile private var ultimaConferencia = 0L

    private fun lembrar(ctx: Context, pacote: String, fora: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val atual = p.getStringSet(CHAVE_MOVIDOS, emptySet()).orEmpty().toMutableSet()
        if (fora) atual += pacote else atual -= pacote
        // O nome guardado serve para o aviso quando o pen drive sai (aí o Android não acha mais o app nem o nome).
        val nome = info(ctx, pacote)?.loadLabel(ctx.packageManager)?.toString()
        p.edit().putStringSet(CHAVE_MOVIDOS, atual).apply { if (nome != null) putString("nome:$pacote", nome) }.apply()
    }

    /**
     * Apps que movemos para o pen drive e que o Android não acha agora (pen drive fora): a HOME avisa quais sumiram.
     * No máximo uma conferência por minuto, e só avisa quando a lista muda; sem shell. Vazia = nada novo a avisar.
     */
    fun ausentes(ctx: Context): List<String> {
        val agora = System.currentTimeMillis()
        if (agora - ultimaConferencia < 60_000) return emptyList()
        ultimaConferencia = agora
        val movidos = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CHAVE_MOVIDOS, emptySet()).orEmpty()
        val faltam = movidos.filter { info(ctx, it) == null }.toSet()
        if (faltam == ultimosAvisados) return emptyList()   // avisa só quando muda (não a cada volta à HOME)
        ultimosAvisados = faltam
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return faltam.map { p.getString("nome:$it", null) ?: it }
    }
    @Volatile private var ultimosAvisados: Set<String> = emptySet()
}

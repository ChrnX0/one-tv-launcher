package br.paulo.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import java.io.File

/**
 * Lado Android da biblioteca retrô ([RetroLibrary] tem a lógica). Lê os volumes pelo NOSSO shell (uid 2000), igual à
 * aba Arquivos: funciona também no build da Play, que não tem "acesso a todos os arquivos". Sem shell pareado a
 * biblioteca fica vazia e a tela explica (nada é copiado para a memória da TV, que é pequena).
 *
 * Guarda a última leitura num arquivo do cache (abrir a tela Jogos não espera o pen drive) e a "última vez" de cada
 * jogo nas prefs "jogos" (vai no backup completo junto com as escolhas dos apps).
 */
object RetroLibraryTv {
    private const val CACHE = "retro-library.txt"
    private const val PREFS = "jogos"
    private const val CHAVE_ULTIMA = "retro_ultima"

    /** Extensões procuradas (as de [RetroLibrary.Console] + genéricas + imagens de capa da própria pessoa). */
    private fun padroes(): List<String> =
        (RetroLibrary.Console.values().flatMap { it.extensoes } + RetroLibrary.GENERICAS + listOf("png", "jpg", "jpeg"))
            .distinct()

    /** `find` em cada volume de /storage (menos o "self"), só com as extensões que interessam, sem a pasta Android. */
    fun comandoBusca(): String {
        val nomes = padroes().joinToString(" -o ") { "-iname '*.$it'" }
        return "for v in /storage/*; do case \"\$v\" in */self) continue;; esac; " +
            "find \"\$v\" -path '*/Android' -prune -o -type f \\( $nomes \\) -print 2>/dev/null; done"
    }

    /** Lê os volumes agora (bloqueia até ~40 s; chamar fora da thread da tela). Null = sem shell. */
    fun ler(ctx: Context): List<RetroLibrary.Jogo>? {
        val saida = GuardiaoShizuku.comando(ctx, comandoBusca(), 40_000) ?: return null
        try { File(ctx.cacheDir, CACHE).writeText(saida) } catch (_: Exception) {}
        return RetroLibrary.montar(saida)
    }

    /** A última leitura guardada (instantâneo para abrir a tela). */
    fun guardada(ctx: Context): List<RetroLibrary.Jogo> = try {
        RetroLibrary.montar(File(ctx.cacheDir, CACHE).readText())
    } catch (_: Exception) { emptyList() }

    fun ultimaVez(ctx: Context): Map<String, Long> = try {
        val o = org.json.JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_ULTIMA, "{}")!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (_: Exception) { emptyMap() }

    private fun marcarJogado(ctx: Context, caminho: String) {
        val m = (ultimaVez(ctx) + (caminho to System.currentTimeMillis())).entries
            .sortedByDescending { it.value }.take(50).associate { it.key to it.value }   // só os 50 mais recentes
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHAVE_ULTIMA, org.json.JSONObject(m as Map<*, *>).toString()).apply()
    }

    enum class Resultado { ABRIU, SEM_NUCLEO, FALHOU }

    /**
     * Abre [jogo] no RetroArch. Caminho 2 (escolha do dono): copia o jogo para a pasta do próprio RetroArch NO MESMO
     * pen drive (o RetroArch da Play só lê a pasta dele) e abre com ROM + o primeiro núcleo do console que ele tem
     * instalado. Sem núcleo nenhum: abre o RetroArch para a pessoa baixar (o download é dele, pela Play).
     * Bloqueia (cópia pelo shell): chamar fora da thread da tela.
     * Não verificado na TV até o teste da Sala (21/11/2026); o caminho 1 (abrir direto do pen drive) entra aqui se o
     * teste mostrar que o RetroArch aceita.
     */
    fun abrir(ctx: Context, jogo: RetroLibrary.Jogo): Resultado {
        val destino = RetroLibrary.destinoNoEmulador(jogo.caminho) ?: return Resultado.FALHOU
        // Pasta dos núcleos e módulos instalados (mudam a cada atualização do RetroArch: perguntar sempre).
        val info = try { ctx.packageManager.getApplicationInfo(RetroLibrary.RETROARCH, 0) }
            catch (_: Exception) { return Resultado.FALHOU }
        val nucleo = RetroLibrary.nucleoInstalado(jogo.console, info.splitNames?.toList().orEmpty())
        if (nucleo == null) {
            val i = ctx.packageManager.getLaunchIntentForPackage(RetroLibrary.RETROARCH)
                ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(RetroLibrary.RETROARCH)
            try { i?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Exception) {}
            return Resultado.SEM_NUCLEO
        }
        val r = GuardiaoShizuku.comando(ctx, RetroLibrary.comandoCopiar(jogo.caminho, destino), 40_000)
        if (r?.contains("ok") != true) return Resultado.FALHOU
        val i = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(RetroLibrary.RETROARCH, RetroLibrary.RETROARCH_ATIVIDADE)
            for ((k, v) in RetroLibrary.extrasRetroArch(destino, nucleo, info.nativeLibraryDir)) putExtra(k, v)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try { ctx.startActivity(i); marcarJogado(ctx, jogo.caminho); Resultado.ABRIU }
            catch (_: Exception) { Resultado.FALHOU }
    }
}

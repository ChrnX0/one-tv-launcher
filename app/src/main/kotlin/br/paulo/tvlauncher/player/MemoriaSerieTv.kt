package br.paulo.tvlauncher.player

import android.content.Context
import java.io.File

/**
 * Onde a memória por série (MemoriaSerie, elevação 6.4) mora: player/series.json, ao lado do idioma.json. Arquivo e não
 * SharedPreferences pelo mesmo motivo do IdiomaPlayer (processo :player e principal veem o mesmo arquivo).
 */
object MemoriaSerieTv {
    private const val ARQUIVO = "series.json"

    private fun todas(ctx: Context) = MemoriaSerie.deJson(runCatching { File(PlayerArmazem.pasta(ctx), ARQUIVO).readText() }.getOrNull())

    fun ler(ctx: Context, serie: String): MemoriaSerie.Escolha? = if (serie.isEmpty()) null else todas(ctx)[serie]

    @Synchronized fun lembrar(ctx: Context, serie: String, f: (MemoriaSerie.Escolha) -> MemoriaSerie.Escolha) {
        val novo = MemoriaSerie.lembrar(todas(ctx), serie, System.currentTimeMillis(), f)
        runCatching {
            val tmp = File(PlayerArmazem.pasta(ctx), "$ARQUIVO.tmp")
            tmp.writeText(MemoriaSerie.paraJson(novo))
            tmp.renameTo(File(PlayerArmazem.pasta(ctx), ARQUIVO))
        }
    }
}

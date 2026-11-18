package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * A parte Android do não perturbe (elevação 11.4; regra em NaoPerturbe.kt). A fila mora nas prefs "nao_perturbe"
 * (fora do backup: passageira). O serviço de acessibilidade chama [janela] a cada tela que abre: acabou o filme,
 * o resumo aparece; a madrugada que acaba solta na próxima tela que abrir (ou quando a TV acende de manhã).
 */
object NaoPerturbeTv {
    private const val TAG = "TvLauncher.NaoPerturbe"
    private const val PREFS = "nao_perturbe"

    fun motivoAgora(ctx: Context): NaoPerturbe.Motivo? {
        val app = ctx.applicationContext
        if (!Extras.ligado(app, Extras.Chave.NAO_PERTURBE)) return null
        val p = JanelaNaFrente.pacote
        val filme = (p == app.packageName && JanelaNaFrente.classe == VodActivity::class.java.name) || p in NaoPerturbe.APPS_FILME
        val c = Calendar.getInstance()
        return NaoPerturbe.motivo(true, filme, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            runCatching { ModoVisita.ativo(app) }.getOrDefault(false))
    }

    /** true = o aviso ficou na fila (não mostrar agora). */
    @Synchronized fun segurar(ctx: Context, titulo: String, texto: String, tipo: NaoPerturbe.Tipo): Boolean {
        if (NaoPerturbe.passa(tipo)) return false
        val m = motivoAgora(ctx) ?: return false
        val pr = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fila = NaoPerturbe.guardar(NaoPerturbe.ler(pr.getString("fila", null)), NaoPerturbe.Guardado(titulo, texto, System.currentTimeMillis()))
        pr.edit().putString("fila", NaoPerturbe.texto(fila)).putString("motivo", m.name).apply()
        Log.i(TAG, "segurado (motivo=$m, tipo=$tipo)")   // sem o título: aviso pode ter conteúdo pessoal
        return true
    }

    /** Uma tela abriu: se o motivo acabou e há fila, mostra o resumo. */
    fun janela(ctx: Context) {
        val app = ctx.applicationContext
        val pr = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bruto = pr.getString("fila", null) ?: return
        if (motivoAgora(app) != null) return
        val motivo = NaoPerturbe.Motivo.entries.firstOrNull { it.name == pr.getString("motivo", null) } ?: NaoPerturbe.Motivo.FILME
        pr.edit().clear().apply()
        val fila = NaoPerturbe.validos(NaoPerturbe.ler(bruto), System.currentTimeMillis())
        if (fila.isEmpty()) return
        val titulo = app.resources.getQuantityString(when (motivo) {
            NaoPerturbe.Motivo.FILME -> R.plurals.elv_np_resumo_filme
            NaoPerturbe.Motivo.MADRUGADA -> R.plurals.elv_np_resumo_noite
            NaoPerturbe.Motivo.VISITA -> R.plurals.elv_np_resumo_visita
        }, fila.size, fila.size)
        val texto = if (fila.size == 1) "${fila[0].titulo}: ${fila[0].texto}"
            else NaoPerturbe.textoResumo(fila) { app.getString(R.string.elv_np_mais, it) }
        // Os avisos já estão um a um na central de avisos (painel rápido): o resumo não entra de novo no histórico.
        AvisoTv.mostrar(app, titulo, texto, 20, som = false, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO,
            historico = false, prioridade = NaoPerturbe.Tipo.REGRA)
    }
}

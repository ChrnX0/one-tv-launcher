package br.paulo.tvlauncher.observer

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.alarm.AcertoAlarme
import br.paulo.tvlauncher.alarm.Cronometros
import br.paulo.tvlauncher.player.Serie
import java.io.File

/**
 * Onde as medidas do placar (Placar.kt) moram: filesDir/medidas/<chave>.json, um arquivo por série. Medida da própria
 * TV: fica fora do backup (como o vitais.json), porque o número de outra TV não diz nada sobre esta.
 *
 * Cada chave é escrita por UM processo só (a troca de canal pelo :player, a HOME pelo principal): sem trava entre
 * processos. Gravar é tmp + rename, então quem lê de outro processo vê a série velha ou a nova, nunca metade.
 * [anotar] já sai da thread de quem chamou (a tela não espera o disco).
 */
object MedidasTv {
    private const val TAG = "TvLauncher.Medidas"
    private fun pasta(ctx: Context) = File(ctx.filesDir, "medidas").apply { mkdirs() }
    private fun arquivo(ctx: Context, chave: String) = File(pasta(ctx), "$chave.json")

    @Synchronized fun ler(ctx: Context, chave: String): Placar.Serie =
        Placar.ler(try { arquivo(ctx, chave).takeIf { it.exists() }?.readText() } catch (_: Exception) { null })

    @Synchronized fun gravarTexto(ctx: Context, chave: String, texto: String) {
        try {
            val tmp = File(pasta(ctx), "$chave.json.tmp")
            tmp.writeText(texto)
            if (!tmp.renameTo(arquivo(ctx, chave))) Log.w(TAG, "rename $chave falhou")
        } catch (e: Exception) { Log.w(TAG, "gravar $chave: $e") }
    }

    @Synchronized fun lerTexto(ctx: Context, chave: String): String? =
        try { arquivo(ctx, chave).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    /** Soma um ponto à série (fora da thread de quem chamou). */
    fun anotar(ctx: Context, chave: String, valor: Long, em: Long = System.currentTimeMillis()) {
        val app = ctx.applicationContext
        Fundo.rodar("medida-$chave") { anotarAgora(app, chave, valor, em) }
    }

    @Synchronized fun anotarAgora(ctx: Context, chave: String, valor: Long, em: Long = System.currentTimeMillis()) {
        gravarTexto(ctx, chave, Placar.texto(ler(ctx, chave).somar(Placar.Ponto(valor, em))))
        Log.i(TAG, "$chave=$valor")
    }

    // ---------------------------------------------------------------- 20.4 cronômetro da instalação

    /** Primeira abertura do launcher nesta TV (BoasVindas). Só vale a primeira vez: depois disso não mexe. */
    fun instalacaoComecou(ctx: Context) {
        val app = ctx.applicationContext
        Fundo.rodar("medida-instalacao") {
            if (ler(app, Placar.Chave.INSTALACAO_INICIO).ultimo == null) anotarAgora(app, Placar.Chave.INSTALACAO_INICIO, 0)
        }
    }

    /** O primeiro canal tocou (processo :player). Grava o tempo uma vez só, se a instalação foi cronometrada. */
    fun primeiroCanalTocou(ctx: Context) {
        val app = ctx.applicationContext
        Fundo.rodar("medida-instalacao") {
            val inicio = ler(app, Placar.Chave.INSTALACAO_INICIO).ultimo?.em ?: return@rodar
            val agora = System.currentTimeMillis()
            val d = Cronometros.Instalacao.duracao(inicio, agora, ler(app, Placar.Chave.INSTALACAO).ultimo != null) ?: return@rodar
            anotarAgora(app, Placar.Chave.INSTALACAO, d, agora)
        }
    }

    // ---------------------------------------------------------------- 10.2 acerto do despertador

    private const val ALARME = "alarme"

    @Synchronized fun anotarAlarme(ctx: Context, previsto: Long, resultado: AcertoAlarme.Resultado) {
        val l = AcertoAlarme.ler(lerTexto(ctx, ALARME))
        val novo = AcertoAlarme.anotar(l, AcertoAlarme.Toque(previsto, resultado))
        if (novo !== l) gravarTexto(ctx, ALARME, AcertoAlarme.texto(novo))
    }

    fun resumoAlarme(ctx: Context): AcertoAlarme.Resumo = AcertoAlarme.resumo(AcertoAlarme.ler(lerTexto(ctx, ALARME)))

    // ---------------------------------------------------------------- 2.2 bancada

    /** O aparelho de teste é marcado à mão pelo adb; a bancada recusa qualquer outro (a Sala nunca é marcada). */
    fun bancada(ctx: Context) = File(pasta(ctx), "bancada").exists()
    fun marcarBancada(ctx: Context, sim: Boolean) { val f = File(pasta(ctx), "bancada"); if (sim) f.writeText("1") else f.delete() }

    // ---------------------------------------------------------------- o placar inteiro

    fun entradas(ctx: Context): Placar.Entradas {
        val v = runCatching { VitaisTv.resumo30(ctx) }.getOrNull()
        return Placar.Entradas(
            home = ler(ctx, Placar.Chave.HOME),
            quedas30 = v?.let { it.quedas + it.travadas }, diasComUso30 = v?.diasComUso ?: 0,
            canal = ler(ctx, Placar.Chave.CANAL),
            alarme = resumoAlarme(ctx),
            instalacao = ler(ctx, Placar.Chave.INSTALACAO),
        )
    }

    fun linhas(ctx: Context): List<Placar.Linha> = Placar.linhas(entradas(ctx))

    /** `EXTRAS --es o placar`: o JSON do placar e as séries cruas (para a bancada e o roteiro do Release). */
    fun textoAdb(ctx: Context): String {
        val o = Placar.json(linhas(ctx))
        for (c in listOf(Placar.Chave.HOME, Placar.Chave.HOME_FRIA, Placar.Chave.CANAL, Placar.Chave.INSTALACAO, Placar.Chave.LIMPEZA,
                Placar.Chave.VOD, Placar.Chave.VOD_TRAVADAS, Placar.Chave.VOD_PERDIDOS))
            o.put("serie_$c", org.json.JSONArray(Placar.texto(ler(ctx, c))))
        LimpezaMedida.ultimaFrase(ctx)?.let { o.put("limpeza_frase", it) }
        return o.toString(2)
    }
}

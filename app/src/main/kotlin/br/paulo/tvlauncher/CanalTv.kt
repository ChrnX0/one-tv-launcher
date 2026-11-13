package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.util.TimeZone

/**
 * A parte Android do "coloca na Globo" / "o que passa na Globo" (a lógica pura está no CanalBusca):
 *  - [resolver]: lê do disco os canais e as regras do player e troca as ações de canal do pedido pelas resolvidas;
 *  - [tocar]: abre o player no canal escolhido;
 *  - [voz]: frase falada -> ação do catálogo -> PedidoIa (o mesmo caminho do Claude, com as mesmas Confirmações).
 *    Ainda SEM entrada: o alias de voz saiu na revisão (qualquer app podia chamá-lo). Ligar só com um caminho de voz que
 *    confira quem chamou (pacote do Assistente).
 */
object CanalTv {
    private const val TAG = "TvLauncher.Canal"

    /** Chamar FORA da thread da tela: lê arquivos (nunca baixa nada). */
    fun resolver(app: Context, plano: ComandosIa.Plano): ComandosIa.Plano {
        if (plano.acoes.none { it is ComandosIa.Acao.TocarCanal || it is ComandosIa.Acao.OQuePassa }) return plano
        val dados = try {
            val canais = PlayerCarga.canaisDoCache(app)
            // Ids antigos (URL) do parental/favoritos: migra já, senão um canal bloqueado pelo id antigo passaria aqui
            // antes de o player ser aberto pela 1ª vez depois da atualização.
            PlayerArmazem.migrarIdsAntigos(app, canais)
            // As mesmas travas do player: categoria oculta (as adultas inclusive) e controle parental.
            val ocultas = PlayerFiltro.ocultasEfetivas(canais, PlayerArmazem.gruposEscondidos(app), PlayerArmazem.gruposLiberados(app))
            val parental = PlayerArmazem.parental(app)
            CanalBusca.Dados(canais, { c -> c.grupo.trim() !in ocultas && !ControleParental.bloqueado(c, parental) },
                { ids -> PlayerCarga.guiaDoCache(app, ids) })
        } catch (e: Exception) {
            Log.w(TAG, "canais: ${e.javaClass.simpleName}")
            null
        }
        return CanalBusca.resolver(plano, dados, System.currentTimeMillis(), TimeZone.getDefault())
    }

    /** Abre o player no canal (o player está noutro processo e confere o controle parental de novo). Null = deu certo. */
    fun tocar(app: Context, a: ComandosIa.Acao.TocarCanal): String? {
        if (a.canalId.isEmpty()) return "canal não conferido"
        app.startActivity(Intent(app, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CANAL, a.canalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /**
     * Frase falada ("coloca na Globo", "o que está passando no SBT"). [semVerboTocar]: a frase veio do "tocar X" do
     * Assistente, que manda só o nome. Devolve false se a frase não for de canal (quem chamou avisa).
     */
    fun voz(ctx: Context, frase: String, semVerboTocar: Boolean): Boolean {
        val p = CanalBusca.interpretarFrase(frase, semVerboTocar) ?: return false
        PedidoIa.propor(ctx, frase, ORIGEM_VOZ, CanalBusca.acoesDaVoz(p))
        return true
    }

    const val ORIGEM_VOZ = "voz"

    fun avisar(ctx: Context, texto: String) =
        try { Toast.makeText(ctx.applicationContext, texto, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
}

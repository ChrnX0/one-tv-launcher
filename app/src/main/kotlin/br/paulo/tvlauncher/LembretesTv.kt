package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lembretes da grade na TV (12/11/2026, ideia 1). A regra fica em [Lembretes]; aqui o que mexe no Android:
 *  - a lista mora num arquivo (files/lembretes.json), e não em SharedPreferences, porque o guia em grade roda no processo
 *    ":player" e a HOME no principal: os dois escrevem, e cada escrita tranca o arquivo ([editar], trava entre processos);
 *  - um alarme do Android acorda o [LembreteReceiver] 2 min antes do próximo programa;
 *  - na hora: com a tela acesa, a [LembreteActivity] pergunta "Assistir?". Com a tela apagada, só o lembrete marcado
 *    À MÃO acorda a TV (quem marcou quer ver); o do "Meu time" não acende a TV de madrugada sozinho.
 */
object LembretesTv {
    private const val TAG = "TvLauncher.Lembrete"
    const val CHAVE_PALAVRAS = "lembrete_palavras"   // nas prefs "extras" (só o processo principal lê)
    const val ACAO_AVISAR = "br.paulo.tvlauncher.LEMBRETE_AVISAR"
    const val ACAO_VARRER = "br.paulo.tvlauncher.LEMBRETE_VARRER"

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "lembretes.json")
    private fun trava(ctx: Context) = File(ctx.filesDir, "lembretes.lock")

    fun listar(ctx: Context): List<Lembretes.Lembrete> =
        Lembretes.deJson(runCatching { arquivo(ctx).readText() }.getOrNull())

    /** Lê, aplica [mudar] e grava, trancando o arquivo contra o outro processo. Devolve a lista nova. */
    fun editar(ctx: Context, mudar: (List<Lembretes.Lembrete>) -> List<Lembretes.Lembrete>): List<Lembretes.Lembrete> {
        val app = ctx.applicationContext
        return synchronized(this) {
            RandomAccessFile(trava(app), "rw").use { raf ->
                raf.channel.lock().use {
                    val nova = mudar(listar(app))
                    PlayerCarga.gravarAtomico(arquivo(app), Lembretes.paraJson(nova))
                    nova
                }
            }
        }
    }

    fun palavras(ctx: Context): List<String> = Lembretes.lerPalavras(Extras.texto(ctx, CHAVE_PALAVRAS))

    fun definirPalavras(ctx: Context, texto: String) {
        Extras.definirTexto(ctx, CHAVE_PALAVRAS, Lembretes.lerPalavras(texto).joinToString(", "))
        copiarPalavras(ctx, palavras(ctx))
        varrerDepois(ctx)
    }

    // Cópia das palavras em arquivo (ideia 16): o guia é baixado no ":player", que não vê as prefs do processo principal
    // atualizadas. O principal grava a cópia ao salvar e a cada varredura; o player só lê.
    private fun arquivoPalavras(ctx: Context) = File(ctx.applicationContext.filesDir, "meutime_palavras.txt")

    private fun copiarPalavras(ctx: Context, lista: List<String>) {
        val texto = lista.joinToString(", ")
        if (runCatching { arquivoPalavras(ctx).readText() }.getOrNull() != texto) PlayerCarga.gravarAtomico(arquivoPalavras(ctx), texto)
    }

    /** As palavras do "Meu time" para quem baixa o guia (qualquer processo). Sem a cópia ainda: as prefs deste processo. */
    fun palavrasParaGuia(ctx: Context): List<String> =
        runCatching { Lembretes.lerPalavras(arquivoPalavras(ctx).readText()) }.getOrNull() ?: palavras(ctx)

    /**
     * OK num programa futuro do guia em grade: marca ou desmarca. Chamar FORA da thread da tela (disco).
     * Devolve true se ficou marcado.
     */
    fun alternar(ctx: Context, canal: Canal, p: Programa): Boolean {
        var marcou = false
        editar(ctx) { l ->
            val (nova, sim) = Lembretes.alternar(Lembretes.limpar(l, System.currentTimeMillis()),
                Lembretes.Lembrete(canal.id, canal.nome, p.titulo, p.inicio, p.fim))
            marcou = sim; nova
        }
        agendar(ctx)
        return marcou
    }

    /** Apaga um lembrete (painel, MCP). */
    fun apagar(ctx: Context, chave: String): Boolean {
        var achou = false
        editar(ctx) { l -> achou = l.any { it.chave == chave }; l.filterNot { it.chave == chave } }
        agendar(ctx)
        return achou
    }

    /**
     * Pede ao processo principal para refazer os lembretes automáticos (pode ser chamado do ":player", que não enxerga as
     * palavras salvas: as SharedPreferences não se atualizam entre processos).
     */
    fun varrerDepois(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(Intent(ctx, LembreteReceiver::class.java).setAction(ACAO_VARRER))
        }
    }

    /**
     * Lê o guia guardado e refaz os automáticos (as palavras do "Meu time"). Disco: FORA da thread da tela.
     * Canal bloqueado pelo controle parental não gera lembrete.
     */
    fun varrer(ctx: Context): Int {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val palavras = palavras(app)
        copiarPalavras(app, palavras)
        val candidatos = ArrayList<Lembretes.Candidato>()
        if (palavras.isNotEmpty()) {
            val regras = runCatching { PlayerArmazem.parental(app) }.getOrNull()
            val porGuia = PlayerCarga.canaisDoCache(app)
                .filter { it.guiaId.isNotEmpty() && (regras == null || !ControleParental.bloqueado(it, regras)) }
                .groupBy { it.guiaId }
            PlayerCarga.varrerGuia(app, agora, agora + Lembretes.HORIZONTE_MS) { p ->
                // Só guarda o que casa (o guia pode ter dezenas de milhares de programas).
                if (palavras.none { Lembretes.casa(p.titulo, it) }) return@varrerGuia
                porGuia[p.guiaId]?.minByOrNull { it.numero }?.let { c ->
                    candidatos += Lembretes.Candidato(c.id, c.nome, c.numero, p)
                }
            }
            // Ideia 16: as partidas além das ~14 h do guia (até 7 dias), guardadas à parte no último download.
            // O mesmo jogo vindo do guia e da lista vira um lembrete só (Lembretes.gerar junta por título e início).
            for (pa in MeuTimeAdiante.validas(PlayerCarga.partidasAdiante(app), palavras, agora)) {
                porGuia[pa.guiaId]?.minByOrNull { it.numero }?.let { c ->
                    candidatos += Lembretes.Candidato(c.id, c.nome, c.numero, Programa(pa.guiaId, pa.inicio, pa.fim, pa.titulo))
                }
            }
        }
        val nova = editar(app) { l -> Lembretes.gerar(l, candidatos.asSequence(), palavras, agora) }
        agendar(app)
        Log.i(TAG, "varredura: ${palavras.size} palavras, ${candidatos.size} programas, ${nova.size} lembretes")
        return nova.count { !it.manual }
    }

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 41, Intent(ctx, LembreteReceiver::class.java).setAction(ACAO_AVISAR),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** (Re)agenda o próximo aviso. Sem nada por avisar, cancela. Idempotente. */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val quando = Lembretes.proximoAviso(listar(ctx), System.currentTimeMillis())
        if (quando == null) { am.cancel(pendente(ctx)); return }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
        }
    }

    /** O alarme tocou: avisa os devidos e agenda o próximo. */
    fun avisar(ctx: Context) {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val devidos = Lembretes.devidos(listar(app), agora)
        if (devidos.isNotEmpty()) {
            editar(app) { l -> Lembretes.marcarAvisados(Lembretes.limpar(l, agora), devidos.mapTo(HashSet()) { it.chave }) }
            val acesa = app.getSystemService(PowerManager::class.java)?.isInteractive != false
            // Dois ao mesmo tempo (raro): mostra o que começa primeiro; o outro vira aviso curto.
            val primeiro = devidos.minByOrNull { it.inicio }!!
            if (acesa || primeiro.manual) abrirPergunta(app, primeiro, acordar = !acesa)
            else Log.i(TAG, "tela apagada: ${primeiro.titulo} (automático) não acorda a TV")
            devidos.filter { it !== primeiro }.forEach {
                if (acesa) AvisoTv.rapido(app, txt(R.string.lb_comeca_as, hora(it.inicio)), "${it.titulo} · ${it.canalNome}", 12)
            }
            Avisos.enviar(app, Avisos.Tipo.LEMBRETE, txt(R.string.lb_comeca_as, hora(primeiro.inicio)), "${primeiro.titulo} · ${primeiro.canalNome}")
        }
        agendar(app)
    }

    private fun abrirPergunta(app: Context, l: Lembretes.Lembrete, acordar: Boolean) {
        try {
            app.startActivity(Intent(app, LembreteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("canal", l.canalId).putExtra("nome", l.canalNome).putExtra("titulo", l.titulo)
                .putExtra("inicio", l.inicio).putExtra("acordar", acordar))
        } catch (e: Exception) {
            Log.w(TAG, "não abri a pergunta: $e")
            AvisoTv.rapido(app, txt(R.string.lb_comeca_as, hora(l.inicio)), "${l.titulo} · ${l.canalNome}", 15)
        }
    }

    fun hora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    /**
     * Ideia 12: o próximo jogo do "Meu time" nas próximas 24 h, já curto ("Flamengo × Palmeiras · 21:30"), para a cápsula
     * de avisos da HOME e o protetor. Null = nada a mostrar (ou modo visita). Lê disco: FORA da thread da tela.
     */
    fun proximoJogo(ctx: Context, agora: Long = System.currentTimeMillis()): String? {
        if (ModoVisita.ativo(ctx)) return null
        val palavras = palavras(ctx)
        if (palavras.isEmpty()) return null
        val itens = listar(ctx).map { ProximoJogo.Item(it.titulo, it.inicio, it.fim, it.palavra) }
        return ProximoJogo.escolher(itens, palavras, agora)?.let { ProximoJogo.texto(it, java.util.TimeZone.getDefault()) }
    }

    /** Para o painel e o MCP. */
    fun json(ctx: Context): org.json.JSONObject = org.json.JSONObject()
        .put("palavras", Extras.texto(ctx, CHAVE_PALAVRAS))
        .put("lembretes", org.json.JSONArray().apply {
            for (l in Lembretes.limpar(listar(ctx), System.currentTimeMillis())) put(org.json.JSONObject()
                .put("chave", l.chave).put("canal", l.canalNome).put("titulo", l.titulo).put("inicio", l.inicio)
                .put("hora", hora(l.inicio)).put("palavra", l.palavra).put("avisado", l.avisado))
        })
}

/** Alarme dos lembretes e o pedido de varredura vindo do ":player". Não exportado. */
class LembreteReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val r = goAsync()
        Fundo.rodar("lembrete") {
            try {
                when (intent.action) {
                    LembretesTv.ACAO_AVISAR -> LembretesTv.avisar(app)
                    LembretesTv.ACAO_VARRER -> LembretesTv.varrer(app)
                }
            } catch (e: Exception) {
                Log.w("TvLauncher.Lembrete", "falhou: $e")
            } finally { r.finish() }
        }
    }
}

/**
 * "Começa em 2 min: Palmeiras x Santos, no SporTV. Assistir?" Foco em Assistir (quem marcou quer ver); sem resposta em
 * 90 s fecha sozinha. Com [acordar], acende a tela como o despertador.
 */
class LembreteActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return
        if (intent.getBooleanExtra("acordar", false)) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val canal = intent.getStringExtra("canal").orEmpty()
        val titulo = intent.getStringExtra("titulo").orEmpty()
        val nome = intent.getStringExtra("nome").orEmpty()
        val inicio = intent.getLongExtra("inicio", 0L)
        val assistir = Estilo.botao(this, getString(R.string.lb_assistir)).apply {
            setOnClickListener {
                CanalTv.tocar(applicationContext, ComandosIa.Acao.TocarCanal(nome, canalId = canal, nomeCanal = nome))
                finish()
            }
        }
        val fechar = Estilo.botao(this, getString(R.string.lb_agora_nao), primario = false).apply { setOnClickListener { finish() } }
        val quando = if (inicio <= System.currentTimeMillis()) getString(R.string.lb_comecou) else getString(R.string.lb_comeca_as, LembretesTv.hora(inicio))
        setContentView(Estilo.dialogo(this, getString(R.string.lb_lembrete), titulo, getString(R.string.lb_quando_em, quando, nome), listOf(fechar, assistir)))
        assistir.requestFocus()
        ui.postDelayed({ if (!isFinishing) finish() }, 90_000L)
    }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null) }
}

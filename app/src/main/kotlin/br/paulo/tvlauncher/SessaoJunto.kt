package br.paulo.tvlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import br.paulo.tvlauncher.home.tocandoAgora
import br.paulo.tvlauncher.listen.SomNoFone
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.EntreTvs
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.JuntoRegras
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sessão junto (degrau 4, 17/11/2026): duas casas assistem a mesma coisa ao mesmo tempo. Pausou aqui, pausou lá;
 * reações (5 emojis) aparecem no canto. Só comandos (título, posição, tocando/pausado, reação) passam, pelo túnel
 * Tailscale que a família conectada já usa ([EntreTvs]); cada casa toca da própria fonte. Regras em [JuntoRegras].
 *
 * Dois processos: o player (VodActivity, PlayerActivity) roda em ":player"; o servidor do túnel e o serviço de
 * acessibilidade (que mostra as reações) rodam no principal. Eles conversam por broadcast DENTRO do app
 * ([ACAO_LOCAL] player -> principal; [ACAO_REMOTO] principal -> player), nunca exportado.
 *
 * Fluxo: no player, "Assistir junto" -> escolhe a TV -> o principal cria o token e manda o convite. A outra TV confere
 * de volta que o convite existe, mostra o cartão ("Sala chama para assistir X junto") e, no OK, guarda a sessão.
 * Quando o player de lá começa o mesmo título (mesma chave), as duas andam juntas. Qualquer lado sai a qualquer hora.
 */
object SessaoJunto {
    private const val TAG = "TvLauncher.Junto"
    const val ACAO_LOCAL = "br.paulo.tvlauncher.JUNTO_LOCAL"
    const val ACAO_REMOTO = "br.paulo.tvlauncher.JUNTO_REMOTO"

    /** A sessão (só no processo principal). [aceita] = a outra TV já mandou um evento (ou somos o convidado e aceitamos). */
    data class Sessao(val token: String, val par: String, val titulo: String, @Volatile var chave: String, val anfitriao: Boolean,
                      val criada: Long, @Volatile var aceita: Boolean = false, @Volatile var ultimo: Long = criada,
                      @Volatile var seqRecebido: Long = -1, @Volatile var atraso: Long = 0)

    @Volatile var atual: Sessao? = null; private set
    @Volatile private var convitePendente: JuntoRegras.Convite? = null
    private val convitesHora = ArrayList<Long>()

    // ---------------------------------------------------------------- lado do anfitrião

    /** Player pediu "Assistir junto" com [host]. Bloqueia (rede): fora da thread da tela. Devolve null ou o erro. */
    fun convidar(ctx: Context, host: String, titulo: String, chave: String): String? {
        val app = ctx.applicationContext
        val eu = EntreTvs.meuHost(app) ?: return "sem_tunel"
        if (JuntoRegras.tipoDaChave(chave) == null) return "titulo"
        if (EntreTvs.outras(app).none { it.host == host }) return "tv"
        val s = Sessao(Cenas.gerarToken(), host, titulo, chave, anfitriao = true, criada = System.currentTimeMillis())
        atual = s
        val r = EntreTvs.chamar(app, host, "/junto/convite",
            JuntoRegras.conviteParaJson(JuntoRegras.Convite(s.token, eu, titulo, chave, s.criada)))
        if (r.codigo != 200) { atual = null; return if (r.codigo == 0) "rede" else "recusado" }
        avisarPlayer(app, s, null)
        return null
    }

    // ---------------------------------------------------------------- servidor (rotas do túnel, McpServidor)

    /** POST /junto/convite. Devolve (código HTTP, JSON). */
    fun receberConvite(ctx: Context, corpo: JSONObject?): Pair<Int, JSONObject> {
        val app = ctx.applicationContext
        if (!EntreTvs.ligado(app)) return 403 to JSONObject().put("erro", "desligado")
        val agora = System.currentTimeMillis()
        synchronized(convitesHora) {
            val (pode, nova) = JuntoRegras.contarConvite(convitesHora, agora)
            convitesHora.clear(); convitesHora.addAll(nova)
            if (!pode) return 429 to JSONObject().put("erro", "limite")
        }
        val c = JuntoRegras.conviteDeJson(corpo, agora) ?: return 400 to JSONObject().put("erro", "convite")
        if (!JuntoRegras.aceitarConvite(c, EntreTvs.outras(app), agora)) return 403 to JSONObject().put("erro", "tv")
        // Confere de volta: a TV que diz ter mandado tem mesmo este convite?
        val volta = EntreTvs.chamar(app, c.de, "/junto/${c.token}/estado")
        if (volta.codigo != 200 || volta.json?.optBoolean("ok") != true) return 403 to JSONObject().put("erro", "conferir")
        convitePendente = c
        try {
            app.startActivity(Intent(app, SessaoJuntoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SessaoJuntoActivity.EXTRA_MODO, SessaoJuntoActivity.MODO_CONVITE))
        } catch (_: Exception) {
            AvisoTv.mostrar(app, app.getString(R.string.junto_convite_titulo, JuntoRegras.nomeBonito(c.de)), c.titulo, 30,
                som = true, guardarSeApagada = false)
        }
        return 200 to JSONObject().put("ok", true)
    }

    fun convitePendente(): JuntoRegras.Convite? = convitePendente?.takeIf { System.currentTimeMillis() - it.quando < JuntoRegras.CONVITE_MS }

    /** O convidado disse OK: guarda a sessão; quando o player começar o mesmo título, entra sozinho. */
    fun aceitar(ctx: Context) {
        val c = convitePendente() ?: return
        convitePendente = null
        val s = Sessao(c.token, c.de, c.titulo, c.chave, anfitriao = false, criada = System.currentTimeMillis(), aceita = true)
        atual = s
        avisarPlayer(ctx.applicationContext, s, null)
    }

    fun recusar() { convitePendente = null }
    internal fun conviteParaTeste(c: JuntoRegras.Convite?) { convitePendente = c }

    /** GET /junto/<token>/estado: a outra TV conferindo que o convite é nosso. */
    fun estado(token: String): Pair<Int, JSONObject> {
        val s = atual?.takeIf { java.security.MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }
            ?: return 404 to JSONObject().put("erro", "não existe")
        return 200 to JSONObject().put("ok", true).put("titulo", s.titulo)
    }

    /** POST /junto/<token>/evento e /sair vindos da outra TV. */
    fun receberEvento(ctx: Context, token: String, acao: String, corpo: JSONObject?): Pair<Int, JSONObject> {
        val app = ctx.applicationContext
        val s = atual?.takeIf { java.security.MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }
            ?: return 404 to JSONObject().put("erro", "não existe")
        if (acao == "sair") {
            encerrar(app, avisarOutra = false)
            AvisoTv.rapido(app, app.getString(R.string.junto_titulo), app.getString(R.string.junto_saiu, JuntoRegras.nomeBonito(s.par)))
            return 200 to JSONObject().put("ok", true)
        }
        val e = JuntoRegras.eventoDeJson(corpo) ?: return 400 to JSONObject().put("erro", "evento")
        if (!JuntoRegras.novoEvento(s.seqRecebido, e.seq)) return 200 to JSONObject().put("ok", true)   // atrasado: ignora
        s.seqRecebido = e.seq; s.ultimo = System.currentTimeMillis()
        if (e.chave != null && e.chave != s.chave && JuntoRegras.valeNaSessao(s.chave, e.chave)) s.chave = e.chave
        if (!s.aceita) {
            s.aceita = true
            AvisoTv.rapido(app, app.getString(R.string.junto_titulo), app.getString(R.string.junto_entrou, JuntoRegras.nomeBonito(s.par)))
        }
        e.reacao?.let { AvisoTv.rapido(app, JuntoRegras.nomeBonito(s.par), it, 4) }
        avisarPlayer(app, s, e)
        return 200 to JSONObject().put("ok", true)
    }

    // ---------------------------------------------------------------- ponte com o processo do player

    /** Principal -> player: o estado da sessão e, se houver, o evento que chegou. */
    private fun avisarPlayer(app: Context, s: Sessao?, e: JuntoRegras.Evento?) {
        val i = Intent(ACAO_REMOTO).setPackage(app.packageName)
            .putExtra("ativa", s != null).putExtra("chave", s?.chave).putExtra("par", s?.par?.let(JuntoRegras::nomeBonito))
        if (e != null) i.putExtra("evento", JuntoRegras.eventoParaJson(e).toString())
        app.sendBroadcast(i)
    }

    /** Player -> principal ([JuntoReceiver]): convidar, evento local, reação, sair, consultar. */
    fun doPlayer(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        when (i.getStringExtra("op")) {
            "consultar" -> avisarPlayer(app, atual, null)
            "convidar" -> Fundo.rodar("junto-convite") {
                val erro = convidar(app, i.getStringExtra("host") ?: "", i.getStringExtra("titulo") ?: "", i.getStringExtra("chave") ?: "")
                val nome = JuntoRegras.nomeBonito(i.getStringExtra("host") ?: "")
                AvisoTv.rapido(app, app.getString(R.string.junto_titulo),
                    if (erro == null) app.getString(R.string.junto_convidou, nome) else app.getString(R.string.junto_falhou, nome))
            }
            "evento" -> {
                val s = atual ?: return
                val e = JuntoRegras.eventoDeJson(i.getStringExtra("evento")?.let { JsonSeguro.objeto(it) }) ?: return
                // Filme/arquivo: só o MESMO título. Canal: qualquer canal (a outra TV acompanha). Série: qualquer episódio
                // da mesma série (a sessão segue para o próximo episódio).
                if (!JuntoRegras.valeNaSessao(s.chave, e.chave)) return
                s.chave = e.chave!!
                Fundo.rodar("junto-evento") {
                    val r = EntreTvs.chamar(app, s.par, "/junto/${s.token}/evento", JuntoRegras.eventoParaJson(e.copy(atrasoMs = s.atraso)))
                    if (r.codigo == 200) s.atraso = JuntoRegras.atraso(r.ms)
                    if (r.codigo == 404) { encerrar(app, avisarOutra = false) }
                }
            }
            "sair" -> Fundo.rodar("junto-sair") { encerrar(app, avisarOutra = true) }
        }
    }

    fun encerrar(app: Context, avisarOutra: Boolean) {
        val s = atual ?: return
        atual = null
        if (avisarOutra) EntreTvs.chamar(app, s.par, "/junto/${s.token}/sair", JSONObject())
        avisarPlayer(app, null, null)
        Log.i(TAG, "sessão encerrada")
    }

    /** Para o adb. */
    fun texto(): String = atual?.let { "par=${it.par} chave=${it.chave} anfitriao=${it.anfitriao} aceita=${it.aceita} atraso=${it.atraso}ms" }
        ?: (convitePendente()?.let { "convite de ${it.de}: ${it.titulo}" } ?: "nenhuma")
}

/** Player -> principal (não exportado; o player está em ":player"). */
class JuntoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            SessaoJunto.ACAO_LOCAL -> SessaoJunto.doPlayer(ctx, intent)
            ContinuarOutraTv.ACAO_ENVIAR -> ContinuarOutraTv.doPlayer(ctx, intent)
        }
    }
}

/**
 * O lado do player (processo ":player"): uma instância por tela de vídeo. Recebe os eventos da outra TV e manda os
 * daqui. Quem usa: VodActivity (filmes, séries, arquivos) e PlayerActivity (canais: só "mesmo canal" e reações).
 *
 * [aplicar] recebe o evento já com o alvo calculado: (tocando, posição alvo ou null = não pular). Ao aplicar um evento
 * de lá, os eventos daqui ficam calados por [ECO_MS] (senão a pausa voltaria como eco e as TVs brigariam).
 */
class JuntoNoPlayer(private val a: Activity, private val aplicar: (tocando: Boolean, alvoMs: Long?) -> Unit,
                    private val posicao: () -> Long, private val tocandoAgora: () -> Boolean,
                    private val trocarCanal: ((nomeNormalizado: String) -> Unit)? = null) {
    companion object { const val ECO_MS = 1_200L }

    @Volatile var ativa = false; private set
    @Volatile var chaveSessao: String? = null; private set
    @Volatile var par: String? = null; private set
    private var chaveLocal: String? = null
    private var avisadoEpisodio: String? = null
    private var seq = System.currentTimeMillis()   // cresce mesmo entre telas
    private var caladoAte = 0L
    private val tela = android.os.Handler(android.os.Looper.getMainLooper())

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val antes = ativa
            ativa = i.getBooleanExtra("ativa", false)
            chaveSessao = i.getStringExtra("chave"); par = i.getStringExtra("par")
            val e = JuntoRegras.eventoDeJson(i.getStringExtra("evento")?.let { JsonSeguro.objeto(it) })
            // Canal ao vivo: "mesmo canal". A outra TV trocou (ou acabamos de entrar): troca aqui também.
            val canalLa = (e?.chave ?: chaveSessao?.takeIf { !antes })?.takeIf { JuntoRegras.tipoDaChave(it) == JuntoRegras.Tipo.CANAL }
            if (ativa && canalLa != null && canalLa != chaveLocal && trocarCanal != null) {
                caladoAte = System.currentTimeMillis() + ECO_MS
                trocarCanal.invoke(canalLa.removePrefix("canal:")); return
            }
            if (e == null || !ativa) return
            // Série: a outra TV já está em outro episódio. Avisa uma vez; quando este player chegar lá, voltam a andar juntas.
            if (e.chave != chaveLocal && JuntoRegras.mesmaSerie(e.chave, chaveLocal) && e.chave != avisadoEpisodio) {
                avisadoEpisodio = e.chave
                JuntoRegras.episodio(e.chave)?.let { (t, ep) ->
                    android.widget.Toast.makeText(a, a.getString(R.string.junto_outro_episodio, par ?: "", t, ep), android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }
            if (e.chave != chaveLocal) return
            val alvo = JuntoRegras.alvo(posicao(), e.posMs, e.tocando, e.atrasoMs, 0, e.chave)
            if (alvo == null && e.tocando == tocandoAgora()) return
            caladoAte = System.currentTimeMillis() + ECO_MS
            aplicar(e.tocando, alvo)
        }
    }

    private val pulso = object : Runnable {
        override fun run() {
            if (ativa && tocandoAgora()) enviar(true, posicao(), null)
            tela.postDelayed(this, JuntoRegras.PULSO_MS)
        }
    }

    fun ligar() {
        SomNoFone.playerAbriu()
        val f = IntentFilter(SessaoJunto.ACAO_REMOTO)
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receptor, f, Context.RECEIVER_NOT_EXPORTED) else a.registerReceiver(receptor, f)
        local("consultar")
        tela.postDelayed(pulso, JuntoRegras.PULSO_MS)
    }

    fun desligar() {
        SomNoFone.playerFechou()   // som no fone vive enquanto há player (com folga para o próximo episódio)
        tela.removeCallbacks(pulso)
        try { a.unregisterReceiver(receptor) } catch (_: Exception) {}
    }

    /** O que está tocando aqui (chave de JuntoRegras.chave). Mudou para o título da sessão: entra. */
    fun tocandoTitulo(chave: String?) { chaveLocal = chave }

    /** Play/pause/pulo daqui. */
    fun mudou(tocando: Boolean, posMs: Long) {
        if (!ativa || System.currentTimeMillis() < caladoAte) return
        enviar(tocando, posMs, null)
    }

    fun reagir(r: String) { if (ativa) enviar(tocandoAgora(), posicao(), r) }

    private fun enviar(tocando: Boolean, posMs: Long, reacao: String?) {
        val e = JuntoRegras.Evento(++seq, tocando, posMs.coerceAtLeast(0), 0, chaveLocal, reacao)
        local("evento") { putExtra("evento", JuntoRegras.eventoParaJson(e).toString()) }
    }

    fun convidar(host: String, titulo: String) {
        val chave = chaveLocal ?: return
        local("convidar") { putExtra("host", host).putExtra("titulo", titulo).putExtra("chave", chave) }
    }

    fun sair() = local("sair")

    /** "Enviar para outra TV" (ContinuarOutraTv): escolhe a TV e manda título, posição e, se for da rede da casa, o endereço. */
    fun enviarPara(titulo: String, tvs: List<String>, posMs: Long, url: String?) {
        val chave = chaveLocal ?: run { android.widget.Toast.makeText(a, a.getString(R.string.junto_sem_titulo), android.widget.Toast.LENGTH_LONG).show(); return }
        if (tvs.isEmpty()) { Estilo.lista(a, a.getString(R.string.cont_titulo), listOf(a.getString(R.string.junto_sem_tvs))) {}; return }
        Estilo.lista(a, a.getString(R.string.cont_escolher), tvs.map(JuntoRegras::nomeBonito)) { i ->
            ContinuarOutraTv.enviarDoPlayer(a, tvs[i], titulo, chave, posMs, url)
        }
    }

    private fun local(op: String, extra: Intent.() -> Unit = {}) {
        a.sendBroadcast(Intent(SessaoJunto.ACAO_LOCAL).setClassName(a.packageName, JuntoReceiver::class.java.name)
            .putExtra("op", op).apply(extra))
    }

    /**
     * O menu "Assistir junto" do player: sem sessão, a lista das TVs da conta (para convidar); com sessão, reações e
     * "Sair". As TVs vêm por [tvs] (o processo do player não tem a lista do túnel: ela vem do principal no broadcast).
     */
    fun menu(titulo: String, tvs: List<String>) {
        if (chaveLocal == null) { android.widget.Toast.makeText(a, a.getString(R.string.junto_sem_titulo), android.widget.Toast.LENGTH_LONG).show(); return }
        if (ativa) {
            val itens = JuntoRegras.REACOES + a.getString(R.string.junto_sair)
            Estilo.lista(a, a.getString(R.string.junto_com, par ?: ""), itens) { i ->
                if (i < JuntoRegras.REACOES.size) reagir(JuntoRegras.REACOES[i]) else sair()
            }
            return
        }
        if (tvs.isEmpty()) {
            Estilo.lista(a, a.getString(R.string.junto_titulo), listOf(a.getString(R.string.junto_sem_tvs))) {}
            return
        }
        Estilo.lista(a, a.getString(R.string.junto_escolher), tvs.map(JuntoRegras::nomeBonito)) { i -> convidar(tvs[i], titulo) }
    }
}

/**
 * Cartões da Sessão junto no processo principal: o convite recebido ("Sala chama para assistir X junto · Entrar /
 * Agora não"). Também guarda a lista de TVs para o player (ele não tem a do túnel): ver [JuntoTvsProvider].
 */
class SessaoJuntoActivity : Activity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val MODO_CONVITE = "convite"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val c = SessaoJunto.convitePendente() ?: run { finish(); return }
        val nome = JuntoRegras.nomeBonito(c.de)
        val canal = JuntoRegras.tipoDaChave(c.chave) == JuntoRegras.Tipo.CANAL
        val agoraNao = Estilo.botao(this, getString(R.string.junto_agora_nao), primario = false).apply {
            setOnClickListener { SessaoJunto.recusar(); finish() }
        }
        val entrar = Estilo.botao(this, getString(R.string.junto_entrar)).apply {
            setOnClickListener {
                SessaoJunto.aceitar(this@SessaoJuntoActivity)
                // Canal: abre a TV ao vivo; ela procura o canal pelo nome (o id muda de lista para lista).
                if (canal) try {
                    startActivity(Intent(this@SessaoJuntoActivity, PlayerActivity::class.java))
                } catch (_: Exception) {}
                finish()
            }
        }
        val texto = getString(if (canal) R.string.junto_convite_canal else R.string.junto_convite_texto, c.titulo)
        setContentView(Estilo.dialogo(this, null, getString(R.string.junto_convite_titulo, nome), texto, listOf(agoraNao, entrar), larguraDp = 760))
        entrar.post { entrar.requestFocus() }
    }
}

/** Lista das TVs da conta para o processo do player (JSON curto), lida por ContentResolver.call. Não exportado. */
class JuntoTvsProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (method != "tvs") return null
        val l = EntreTvs.outras(ctx).filter { it.online }.map { it.host }
        return Bundle().apply { putString("tvs", JSONArray(l).toString()) }
    }
    override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(u: android.net.Uri): String? = null
    override fun insert(u: android.net.Uri, v: android.content.ContentValues?) = null
    override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun autoridade(ctx: Context) = "${ctx.packageName}.juntotvs"
        /** Do processo do player: as TVs online da conta (bloqueia pouco: só lê memória do principal). */
        fun tvs(ctx: Context): List<String> = try {
            val b = ctx.contentResolver.call(android.net.Uri.parse("content://${autoridade(ctx)}"), "tvs", null, null)
            val a = JSONArray(b?.getString("tvs") ?: "[]")
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}

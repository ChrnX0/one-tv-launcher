package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelControle
import br.paulo.tvlauncher.settings.Planos
import java.io.File
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Cuidar de longe" na TV (ideias 77, 78, 79): guardar, agendar, avisar. As regras moram em [CuidarDeLonge] (puras,
 * testadas); aqui só o que precisa do Android.
 *
 * - Prefs "cuidar_de_longe": FORA dos backups (BackupConfig/BackupCompleto só levam a lista deles). Restaurar numa
 *   outra TV não pode levar junto o consentimento de quem mora aqui.
 * - Um alarme só (setExactAndAllowWhileIdle, RTC_WAKEUP) para o próximo evento: lembrete, prazo do Feito, nova
 *   tentativa de envio ou a hora do "não ligou". Ele acorda o Chromecast em espera; quem decide o que fazer é
 *   [CuidarDeLonge.devidos], olhando também se a tela está acesa agora.
 * - A tecla de ajuda chega pelo funil do serviço de acessibilidade ([tecla]); o teste sem controle é a tecla falsa
 *   (`EXTRAS --es o tecla --es tecla BACK --el ms 3500`).
 */
object CuidarDeLongeTv {
    private const val TAG = "TvLauncher.Cuidar"
    private const val PREFS = "cuidar_de_longe"
    /** Foto do último pedido de ajuda: some depois disto (o painel deixa de mostrar e o arquivo é apagado). */
    const val FOTO_VALE_MS = 24 * 60 * 60_000L

    private val principal = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val fuso: TimeZone get() = TimeZone.getDefault()

    // ---------------------------------------------------------------- configuração e estado

    fun config(ctx: Context): CuidarDeLonge.Config =
        CuidarDeLonge.limitar(CuidarDeLonge.deJson(prefs(ctx).getString("config", null)), BuildConfig.CUIDAR_FAMILIA, ExtrasPlugin.autorizado(ctx))

    @Synchronized
    fun mudar(ctx: Context, f: (CuidarDeLonge.Config) -> CuidarDeLonge.Config): CuidarDeLonge.Config {
        val antes = config(ctx)
        val novo = f(antes)
        prefs(ctx).edit().putString("config", CuidarDeLonge.paraJson(novo).toString()).commit()
        ajudaLigada = Planos.permite(ctx, Planos.Recurso.CUIDAR_DE_LONGE) && CuidarDeLonge.ativo(novo, CuidarDeLonge.Aviso.AJUDA)
        teclaAjuda = novo.tecla.codigo
        agendar(ctx)
        return novo
    }

    fun estado(ctx: Context): CuidarDeLonge.Estado = CuidarDeLonge.estadoDeJson(prefs(ctx).getString("estado", null))

    @Synchronized
    /** Remove o lembrete e as marcas do dia dele (o id pode voltar num lembrete novo: [CuidarDeLonge.esquecerLembrete]). */
    fun removerLembrete(ctx: Context, id: Int) {
        mudar(ctx) { it.copy(lembretes = CuidarDeLonge.removerLembrete(it.lembretes, id)) }
        mudarEstado(ctx) { CuidarDeLonge.esquecerLembrete(it, id) }
    }

    private fun mudarEstado(ctx: Context, f: (CuidarDeLonge.Estado) -> CuidarDeLonge.Estado): CuidarDeLonge.Estado {
        val novo = CuidarDeLonge.podar(f(estado(ctx)), System.currentTimeMillis(), fuso)
        prefs(ctx).edit().putString("estado", CuidarDeLonge.estadoParaJson(novo).toString()).commit()
        return novo
    }

    /** O aviso sai? Consentimento + aviso escolhido + plano (Ultra; hoje tudo liberado, ver Planos). */
    fun ativo(ctx: Context, a: CuidarDeLonge.Aviso): Boolean =
        Planos.permite(ctx, Planos.Recurso.CUIDAR_DE_LONGE) && CuidarDeLonge.ativo(config(ctx), a)

    /** Nome da TV que vai nos avisos ("Sala"); o mesmo do Avisos.enviarUm. */
    private fun nomeTv(ctx: Context): String =
        runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "TV"

    /** Nomes dos aparelhos inscritos nos avisos do celular (id -> nome), para escolher quem recebe. */
    fun aparelhos(ctx: Context): List<Pair<String, String>> = Avisos.inscricoes(ctx).map { it.endpoint.takeLast(16) to it.nome }

    // ---------------------------------------------------------------- consentimento (só na TV)

    /** Abre a pergunta grande na TV. Devolve o que falta (nome, aparelho, aviso) ou null se abriu. */
    fun pedirConsentimento(ctx: Context): CuidarDeLonge.Falta? {
        CuidarDeLonge.falta(config(ctx))?.let { return it }
        abrir(ctx, CuidarTelaActivity.MODO_CONSENTIMENTO)
        return null
    }

    /** A pessoa apertou "Aceito" na TV (CuidarTelaActivity). Só por lá: o painel e o Claude não chegam aqui. */
    fun consentir(ctx: Context) {
        val c = mudar(ctx) { CuidarDeLonge.consentir(it, System.currentTimeMillis()) }
        PainelArmazem.registrar(ctx, "tv", "cuidar de longe: consentimento dado", c.nome)
    }

    /**
     * Retira o consentimento (na TV, ou "Desligar" no painel). Antes, avisa os aparelhos que recebiam: quem cuida não
     * pode continuar achando que vai saber se algo acontecer.
     */
    fun retirar(ctx: Context, origem: String) {
        val antes = config(ctx)
        val estava = Planos.permite(ctx, Planos.Recurso.CUIDAR_DE_LONGE) && CuidarDeLonge.consentido(antes)
        mudar(ctx) { CuidarDeLonge.retirar(it) }
        PainelArmazem.registrar(ctx, origem, "cuidar de longe: desligado", "ok")
        if (estava) {
            val app = ctx.applicationContext
            Fundo.rodar("cuidar-desligado") {
                Avisos.enviarPara(app, antes.aparelhos, "CUIDAR", app.getString(R.string.cuidar_push_desligado_titulo),
                    app.getString(R.string.cuidar_push_desligado, nomeTv(app)))
            }
        }
    }

    // ---------------------------------------------------------------- 77: pedido de ajuda

    /** A pergunta do consentimento está na frente (CuidarTelaActivity): o shell do MCP recusa comandos enquanto isso. */
    @Volatile var consentimentoNaTela = false
    @Volatile private var ajudaLigada = false
    @Volatile private var teclaAjuda = CuidarDeLonge.Tecla.VOLTAR.codigo
    private val segurar = CuidarDeLonge.Segurar()
    private var relogio: Runnable? = null
    @Volatile private var fotoPendente: ByteArray? = null

    /** Lê a configuração uma vez (o funil de teclas não pode ler prefs a cada tecla). Chamado ao ligar o serviço. */
    fun iniciar(ctx: Context) {
        val c = config(ctx)
        ajudaLigada = Planos.permite(ctx, Planos.Recurso.CUIDAR_DE_LONGE) && CuidarDeLonge.ativo(c, CuidarDeLonge.Aviso.AJUDA)
        teclaAjuda = c.tecla.codigo
        agendar(ctx)
    }

    /** Funil de teclas (AutorizaShizukuService.tecla). True = a tecla é nossa (só depois de segurar 3 s). */
    /** A ajuda está ligada e usa [codigo] (o VOLTAR segurado do painel rápido cede a tecla a ela). */
    fun usaTecla(codigo: Int): Boolean = ajudaLigada && teclaAjuda == codigo

    fun tecla(ctx: Context, e: KeyEvent): Boolean {
        if (!ajudaLigada) return false
        segurar.tecla = teclaAjuda
        if (e.keyCode != teclaAjuda) return false
        val desce = e.action == KeyEvent.ACTION_DOWN
        val app = ctx.applicationContext
        if (desce && e.repeatCount == 0) {
            relogio?.let { principal.removeCallbacks(it) }
            relogio = Runnable { relogio = null; if (segurar.venceu(SystemClock.uptimeMillis())) disparar(app) }
                .also { principal.postDelayed(it, CuidarDeLonge.SEGURAR_MS) }
        } else if (!desce) {
            relogio?.let { principal.removeCallbacks(it) }; relogio = null
        }
        return segurar.evento(e.keyCode, desce, e.repeatCount, e.eventTime) { relogio?.let { principal.removeCallbacks(it) }; disparar(app) }
    }

    /** Segurou 3 s: tira a foto da tela ANTES da pergunta (senão a foto seria a pergunta) e então pergunta. */
    private fun disparar(app: Context) {
        Log.i(TAG, "tecla de ajuda segurada")
        Fundo.rodar("cuidar-foto") {
            fotoPendente = try { PainelControle.foto().first } catch (e: Exception) { null }
            principal.post { abrir(app, CuidarTelaActivity.MODO_AJUDA) }
        }
    }

    /** Pergunta de ajuda abandonada (Desistir/VOLTAR): a foto não fica em lugar nenhum. */
    fun desistirAjuda() { fotoPendente = null }

    /**
     * OK na pergunta: grava a foto (para o painel mostrar) e avisa os aparelhos. BLOQUEIA (rede). Devolve (aceitos, total).
     */
    fun mandarAjuda(ctx: Context): Pair<Int, Int> {
        val app = ctx.applicationContext
        val c = config(app)
        if (!ativo(app, CuidarDeLonge.Aviso.AJUDA)) return 0 to 0
        val jpg = fotoPendente.also { fotoPendente = null }
        val agora = System.currentTimeMillis()
        val arq = arquivoFoto(app)
        try {
            if (jpg != null) { arq.parentFile?.mkdirs(); arq.writeBytes(jpg) } else arq.delete()
        } catch (e: Exception) { Log.w(TAG, "foto não gravada: $e") }
        prefs(app).edit().putLong("ajuda_em", agora).putBoolean("ajuda_foto", jpg != null).apply()
        val hora = CuidarDeLonge.textoHora(agora, fuso)
        val texto = app.getString(R.string.cuidar_push_ajuda, hora) +
            (if (jpg == null) " " + app.getString(R.string.cuidar_push_ajuda_sem_foto) else " " + app.getString(R.string.cuidar_push_ajuda_drm))
        val r = Avisos.enviarPara(app, c.aparelhos, "AJUDA", app.getString(R.string.cuidar_push_ajuda_titulo), texto)
        PainelArmazem.registrar(app, "tv", "cuidar de longe: pedido de ajuda", "${r.first} de ${r.second} aceitos")
        return r
    }

    private fun arquivoFoto(ctx: Context) = File(ctx.noBackupFilesDir, "cuidar/ajuda.jpg")

    /** A foto do último pedido de ajuda (para o painel), se ainda vale. Velha: apaga. */
    fun fotoAjuda(ctx: Context): File? {
        val f = arquivoFoto(ctx)
        val em = prefs(ctx).getLong("ajuda_em", 0L)
        if (System.currentTimeMillis() - em > FOTO_VALE_MS) { f.delete(); return null }
        return f.takeIf { it.isFile }
    }

    // ---------------------------------------------------------------- 78 e 79: o relógio

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, CuidarReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** (Re)agenda o próximo evento. Sem nada pela frente, cancela. */
    fun agendar(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val agora = System.currentTimeMillis()
        val prox = CuidarDeLonge.proximo(config(app), estado(app), agora, fuso)
        if (prox == null) { am.cancel(pendente(app)); return }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prox, pendente(app))
            Log.i(TAG, "próximo em ${(prox - agora) / 60_000} min")
        } catch (e: SecurityException) {
            // Sem alarme exato (não deve acontecer: o launcher é dono do aparelho), o inexato ainda serve (minutos de folga).
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prox, pendente(app))
        }
    }

    private fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** A tela acendeu (SCREEN_ON, HOME na frente): anota "ligou hoje" e mostra lembrete que ficou pendente. */
    fun tvLigou(ctx: Context) {
        val app = ctx.applicationContext
        Fundo.rodar("cuidar-ligou") { rodar(app) }
    }

    /**
     * Faz o que está devido agora. BLOQUEIA (rede, nos avisos): fora da thread principal. Chamado pelo alarme, pela tela
     * acendendo e pelo adb (`EXTRAS --es o cuidar_rodar`).
     */
    /**
     * Trava só do [rodar] (os avisos vão pela rede, até 25 s por aparelho). Antes era a do objeto, a mesma de [mudar] e
     * [mudarEstado]: o "Feito" e o "Aceito", na thread da tela, esperavam a rede e davam ANR.
     */
    private val travaRodar = Any()

    fun rodar(ctx: Context) = synchronized(travaRodar) { rodarTravado(ctx) }

    private fun rodarTravado(ctx: Context) {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val acesa = telaAcesa(app)
        if (acesa) mudarEstado(app) { it.copy(ligadas = CuidarDeLonge.anotarLigou(it.ligadas, agora, fuso)) }
        val c = config(app)
        val permitido = Planos.permite(app, Planos.Recurso.CUIDAR_DE_LONGE)
        // O plano pode ter mudado desde iniciar() (compra, ou o relógio certo depois de um boot sem rede): a tecla de
        // ajuda segue o plano de agora, sem esperar alguém mexer na configuração.
        ajudaLigada = permitido && CuidarDeLonge.ativo(c, CuidarDeLonge.Aviso.AJUDA)
        for (a in CuidarDeLonge.devidos(c, estado(app), agora, fuso, ligadaAgora = acesa)) when (a) {
            is CuidarDeLonge.Acao.Mostrar -> {
                // Tela apagada e "acender" desligado: espera a TV ligar (tvLigou roda de novo), até 2 h depois da hora.
                if (!acesa && !c.acender) continue
                mudarEstado(app) { CuidarDeLonge.anotarMostrado(it, a.chave, agora) }
                principal.post { abrir(app, CuidarTelaActivity.MODO_LEMBRETE, a.chave, a.lembrete.texto, a.momento) }
            }
            is CuidarDeLonge.Acao.AvisarLembrete -> if (permitido) {
                val r = Avisos.enviarPara(app, c.aparelhos, "LEMBRETE_CUIDAR", app.getString(R.string.cuidar_push_lembrete_titulo),
                    app.getString(R.string.cuidar_push_lembrete, CuidarDeLonge.textoHora(a.momento, fuso)))
                mudarEstado(app) { CuidarDeLonge.anotarEnvio(it, a.chave, r.first, System.currentTimeMillis()) }
                PainelArmazem.registrar(app, "tv", "cuidar de longe: lembrete das ${CuidarDeLonge.textoHora(a.momento, fuso)} não confirmado",
                    "${r.first} de ${r.second} aceitos")
            }
            is CuidarDeLonge.Acao.PerguntarEstouBem -> {
                mudarEstado(app) { CuidarDeLonge.anotarMostrado(it, a.chave, agora) }
                principal.post { abrir(app, CuidarTelaActivity.MODO_ESTOU_BEM, a.chave) }
            }
            // Build da Play: o "não ligou" automático só sai quando o plugin Extras pede (rodarNaoLigou), nunca sozinho.
            is CuidarDeLonge.Acao.AvisarNaoLigou -> if (permitido && (BuildConfig.CUIDAR_FAMILIA || pedidoDoExtras)) {
                val costume = CuidarDeLonge.horaCostume(c, estado(app).ligadas, a.dia) ?: 0
                val r = Avisos.enviarPara(app, c.aparelhos, "TV_NAO_LIGOU", app.getString(R.string.cuidar_push_nao_ligou_titulo),
                    app.getString(R.string.cuidar_push_nao_ligou, nomeTv(app), "%02d:%02d".format(costume / 60, costume % 60)))
                mudarEstado(app) { CuidarDeLonge.anotarEnvio(it, a.chave, r.first, System.currentTimeMillis()) }
                PainelArmazem.registrar(app, "tv", "cuidar de longe: TV não ligada até a hora", "${r.first} de ${r.second} aceitos")
            }
        }
        agendar(app)
    }

    @Volatile private var pedidoDoExtras = false

    /**
     * O plugin Extras (app separado) pediu a conferência do "não ligou" (79). BLOQUEIA (rede). Só com o plugin autorizado
     * pelo dono (PIN) e o aviso aceito na TV pela pessoa; a regra (hora de costume, margem) é a mesma de sempre.
     */
    fun rodarNaoLigou(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!ExtrasPlugin.autorizado(app) || !ativo(app, CuidarDeLonge.Aviso.NAO_LIGOU)) return false
        synchronized(travaRodar) { pedidoDoExtras = true; try { rodarTravado(app) } finally { pedidoDoExtras = false } }
        return true
    }

    /** "Estou bem" apertado na TV: avisa os aparelhos. BLOQUEIA (rede). Devolve (aceitos, total). */
    fun mandarEstouBem(ctx: Context, chave: String): Pair<Int, Int> {
        val app = ctx.applicationContext
        val c = config(app)
        if (!ativo(app, CuidarDeLonge.Aviso.ESTOU_BEM)) return 0 to 0
        mudarEstado(app) { CuidarDeLonge.anotarConfirmado(it, chave, System.currentTimeMillis()) }
        val r = Avisos.enviarPara(app, c.aparelhos, "ESTOU_BEM", app.getString(R.string.cuidar_push_bem_titulo),
            app.getString(R.string.cuidar_push_bem, nomeTv(app), CuidarDeLonge.textoHora(System.currentTimeMillis(), fuso)))
        PainelArmazem.registrar(app, "tv", "cuidar de longe: estou bem", "${r.first} de ${r.second} aceitos")
        return r
    }

    /** "Feito" no lembrete. */
    fun confirmar(ctx: Context, chave: String) {
        val app = ctx.applicationContext
        mudarEstado(app) { CuidarDeLonge.anotarConfirmado(it, chave, System.currentTimeMillis()) }
        agendar(app)
    }

    /** Teste do painel/TV: manda "teste" só para os aparelhos escolhidos. BLOQUEIA. */
    fun testar(ctx: Context): Pair<Int, Int> {
        val app = ctx.applicationContext
        val c = config(app)
        return Avisos.enviarPara(app, c.aparelhos, "CUIDAR", app.getString(R.string.cuidar_push_teste_titulo),
            app.getString(R.string.cuidar_push_teste, nomeTv(app)))
    }

    private fun abrir(ctx: Context, modo: String, chave: String = "", texto: String = "", momento: Long = 0L) {
        try {
            ctx.startActivity(Intent(ctx, CuidarTelaActivity::class.java)
                .putExtra(CuidarTelaActivity.EXTRA_MODO, modo).putExtra(CuidarTelaActivity.EXTRA_CHAVE, chave)
                .putExtra(CuidarTelaActivity.EXTRA_TEXTO, texto).putExtra(CuidarTelaActivity.EXTRA_MOMENTO, momento)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { Log.w(TAG, "não abriu a tela ($modo): $e") }
    }

    // ---------------------------------------------------------------- painel e adb

    /** Tudo o que o painel mostra. O endpoint dos aparelhos não sai (só o id de 16 caracteres, como no Avisos.json). */
    fun json(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val c = config(app)
        val e = estado(app)
        val agora = System.currentTimeMillis()
        val hoje = CuidarDeLonge.dia(agora, fuso)
        val lembretesHoje = JSONArray()
        for (l in c.lembretes) for (m in CuidarDeLonge.ocorrencias(l, agora - 18 * 3_600_000L, agora, fuso)) {
            val k = CuidarDeLonge.chave(l, m, fuso)
            val mk = e.marca(k)
            lembretesHoje.put(JSONObject().put("id", l.id).put("hora", CuidarDeLonge.textoHora(m, fuso)).put("texto", l.texto)
                .put("confirmado", mk.confirmado).put("avisado", mk.avisado))
        }
        val emAjuda = prefs(app).getLong("ajuda_em", 0L)
        val o = CuidarDeLonge.paraJson(c)
        o.remove("consentimento")   // a assinatura aceita não interessa ao painel; "consentido" diz se vale
        return o.put("consentido", CuidarDeLonge.consentido(c))
            .put("falta", CuidarDeLonge.falta(c)?.name ?: JSONObject.NULL)
            .put("plano", Planos.permite(app, Planos.Recurso.CUIDAR_DE_LONGE))
            .put("familia", BuildConfig.CUIDAR_FAMILIA)   // false na build da Play: só o pedido de ajuda
            // Os avisos que esta build oferece: o painel mostra exatamente estes (antes faltava o "Estou bem" e
            // gravar pelo painel o apagava, mudando a configuração e derrubando o consentimento sem avisar).
            .put("oferecidos", JSONArray(CuidarDeLonge.permitidos(BuildConfig.CUIDAR_FAMILIA, ExtrasPlugin.autorizado(app)).map { it.name }))
            .put("disponiveis", JSONArray().apply { aparelhos(app).forEach { (id, nome) -> put(JSONObject().put("id", id).put("nome", nome)) } })
            .put("costume", CuidarDeLonge.horaCostume(c, e.ligadas, hoje) ?: JSONObject.NULL)
            .put("amostras", e.ligadas.keys.count { it < hoje })
            .put("ligouHoje", e.ligadas[hoje] ?: JSONObject.NULL)
            .put("lembretesHoje", lembretesHoje)
            .put("ajuda", if (agora - emAjuda <= FOTO_VALE_MS) JSONObject().put("em", emAjuda).put("foto", fotoAjuda(app) != null) else JSONObject.NULL)
    }

    /** Para o adb (`EXTRAS --es o cuidar`). */
    fun texto(ctx: Context): String = json(ctx).toString(2)
}

/** O alarme do Cuidar de longe (não exportado: só o PendingIntent do próprio launcher chega). */
class CuidarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val fim = goAsync()   // o envio é rede: segura o processo (e o despertar do alarme) até acabar; transmissão de fundo tem até 60 s
        // Solta o broadcast no máximo em 50 s (depuração 18/11/2026: vários avisos seguidos passavam de 60 s = ANR). O
        // trabalho continua na thread de fundo; o launcher é a HOME com acessibilidade, o processo fica de pé.
        val solto = java.util.concurrent.atomic.AtomicBoolean(false)
        val soltar = { if (solto.compareAndSet(false, true)) fim.finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ soltar() }, 50_000L)
        Fundo.rodar("cuidar-alarme") {
            try { CuidarDeLongeTv.rodar(app) } catch (e: Exception) { Log.w("TvLauncher.Cuidar", "rodar: $e") } finally { soltar() }
        }
    }
}

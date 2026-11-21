package br.paulo.tvlauncher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import br.paulo.tvlauncher.DesfazerRegras.Categoria
import br.paulo.tvlauncher.DesfazerRegras.Mudanca
import br.paulo.tvlauncher.DesfazerRegras.Passo
import br.paulo.tvlauncher.family.Diario
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.privilege.Debloat
import br.paulo.tvlauncher.privilege.PrivilegioDO

/**
 * Elevação 2.11: guarda os passos (prefs "desfazer", fora do backup: é sobre ESTA TV e estes dias) e desfaz.
 * Regras puras em [DesfazerRegras]. Quem cria passos: [VigiaDesfazer] (as telas de ajuste, organização e automação) e
 * a limpeza de apps ([registrarLimpeza]). Quem desfaz: a [BarraDesfazer] (10 s), a tela "Mudanças recentes" das
 * Configurações, o painel (Diário) e o MCP.
 */
object Desfazer {
    private const val PREFS = "desfazer"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Hora do último desfazer: a vigia ignora o que ela mesma provocou. */
    @Volatile var desfezEm = 0L; private set

    fun lista(ctx: Context): List<Passo> =
        DesfazerRegras.podar(DesfazerRegras.deJson(prefs(ctx).getString("passos", null)), System.currentTimeMillis())

    fun passo(ctx: Context, id: String): Passo? = lista(ctx).firstOrNull { it.id == id }

    /** Grava o passo (ou junta ao último). Um passo NOVO entra também no Diário, com o id para desfazer de lá. */
    private val aleatorio = java.security.SecureRandom()

    @Synchronized fun registrar(ctx: Context, categoria: Categoria, rotulo: String, mudancas: List<Mudanca>,
                                reverso: List<String> = emptyList()): Passo? {
        val agora = System.currentTimeMillis()
        val antes = lista(ctx)
        // Hora + 4 letras ao acaso, sem repetir na lista: dois passos no mesmo milissegundo com a lista cheia (50) davam o
        // mesmo id, e desfazer um marcava os dois (rodada 15).
        var id: String
        do { id = java.lang.Long.toString(agora, 36) + java.lang.Long.toString(1_679_616L + aleatorio.nextInt(1_679_616 * 35), 36).takeLast(4) }
        while (antes.any { it.id == id })
        val (nova, passo) = DesfazerRegras.registrar(antes, categoria, rotulo, mudancas, reverso, agora, id)
        prefs(ctx).edit().putString("passos", DesfazerRegras.paraJson(nova)).apply()
        if (passo != null && passo.id == id)
            Diario.anotar(ctx, "tv", ctx.getString(R.string.desf_diario, nomeCategoria(ctx, categoria), rotulo), "desfazer:$id")
        return passo
    }

    /** Ocultar/mostrar um app pela limpeza: o desfazer é a ação contrária. */
    fun registrarLimpeza(ctx: Context, pacote: String, nome: String, ocultou: Boolean): Passo? =
        registrar(ctx, Categoria.LIMPEZA, ctx.getString(if (ocultou) R.string.desf_ocultou else R.string.desf_mostrou, nome),
            emptyList(), listOf(DesfazerRegras.reversoDe(pacote, ocultou)))

    data class Resultado(val voltaram: Int, val ficaram: Int)

    /** Desfaz o passo [id]. Null = não existe ou já foi desfeito. Rodar fora da thread da tela (a limpeza usa o shell). */
    @Synchronized fun desfazer(ctx: Context, id: String, origem: String = "tv"): Resultado? {
        val todos = lista(ctx)
        val passo = todos.firstOrNull { it.id == id && !it.desfeito } ?: return null
        val cache = HashMap<String, SharedPreferences>()
        // MULTI_PROCESS relê do disco: o processo :player grava em "player" e "catalogo_pasta", e o cache velho deste
        // processo, gravado inteiro no commit, apagava o que o player tinha acabado de salvar (rodada 15).
        @Suppress("DEPRECATION")
        fun sp(nome: String) = cache.getOrPut(nome) { ctx.getSharedPreferences(nome, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS) }
        val (voltam, ficaram) = DesfazerRegras.planoDesfazer(passo) { p, k -> sp(p).all[k] }
        desfezEm = System.currentTimeMillis()
        voltam.groupBy { it.prefs }.forEach { (nome, ms) ->
            val e = sp(nome).edit()
            for (m in ms) when (val v = m.antes) {
                null -> e.remove(m.chave)
                is Boolean -> e.putBoolean(m.chave, v)
                is Int -> e.putInt(m.chave, v)
                is Long -> e.putLong(m.chave, v)
                is Float -> e.putFloat(m.chave, v)
                is Set<*> -> e.putStringSet(m.chave, v.map { it.toString() }.toHashSet())
                else -> e.putString(m.chave, v.toString())
            }
            e.commit()
        }
        var rev = 0
        for (r in passo.reverso) {
            val pacote = r.substringAfter(':')
            val ocultar = r.startsWith("ocultar:")
            if (Debloat.definir(ctx, pacote, ocultar) || PrivilegioDO.estaOculto(ctx, pacote) == ocultar) rev++
        }
        desfezEm = System.currentTimeMillis()
        prefs(ctx).edit().putString("passos", DesfazerRegras.paraJson(DesfazerRegras.marcarDesfeito(todos, id))).apply()
        val r = Resultado(voltam.size + rev, ficaram + (passo.reverso.size - rev))
        PainelArmazem.registrar(ctx, origem, ctx.getString(R.string.desf_diario_desfeito, passo.rotulo),
            if (r.ficaram == 0) "ok" else "${r.voltaram}/${r.voltaram + r.ficaram}")
        return r
    }

    fun nomeCategoria(ctx: Context, c: Categoria): String = ctx.getString(when (c) {
        Categoria.AJUSTE -> R.string.desf_cat_ajuste
        Categoria.ORGANIZACAO -> R.string.desf_cat_organizacao
        Categoria.AUTOMACAO -> R.string.desf_cat_automacao
        Categoria.LIMPEZA -> R.string.desf_cat_limpeza
    })

    /** Frase do resultado, para a barra, a tela e o painel. */
    fun frase(ctx: Context, r: Resultado?): String = when {
        r == null -> ctx.getString(R.string.desf_nada)
        r.ficaram == 0 -> ctx.getString(R.string.desf_feito)
        else -> ctx.getString(R.string.desf_parcial, r.ficaram)
    }
}

/**
 * Vigia uma tela: tira a foto das prefs ao entrar, e a cada mudança (agrupada em 400 ms) grava o passo e mostra a
 * [BarraDesfazer]. Liga-se no onCreate com uma linha ([ligar]) e acompanha a tela sozinha (resumir/pausar).
 * [categorias] null = todas; a HOME vigia só a organização (o resto que ela grava é estado da tela).
 */
class VigiaDesfazer private constructor(
    private val activity: Activity,
    private val rotulo: String,
    private val categorias: Set<Categoria>?,
    private val aoDesfazer: () -> Unit,
    private val mostrarBarra: Boolean,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val ui = Handler(Looper.getMainLooper())
    private val fotos = HashMap<String, Map<String, *>>()
    private val vigiadas = HashMap<SharedPreferences, String>()
    private var ativa = false

    companion object {
        /**
         * [mostrarBarra] = false: a mudança só entra no Diário ("Mudanças recentes"), sem o aviso que pega o foco.
         * Nas Configurações cada linha já mostra o próprio valor e outro OK volta atrás; o aviso tirava o foco da opção
         * a cada troca (Paulo, 20/11/2026; Material "snackbar não rouba o foco", WCAG 3.2.2 "mudar um ajuste não muda o contexto").
         */
        fun ligar(activity: Activity, rotulo: String, categorias: Set<Categoria>? = null, mostrarBarra: Boolean = true,
                  aoDesfazer: () -> Unit = { activity.recreate() }): VigiaDesfazer {
            val v = VigiaDesfazer(activity, rotulo, categorias, aoDesfazer, mostrarBarra)
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { if (a === activity) v.iniciar() }
                override fun onActivityPaused(a: Activity) { if (a === activity) v.parar() }
                override fun onActivityDestroyed(a: Activity) {
                    if (a === activity) { v.parar(); a.application.unregisterActivityLifecycleCallbacks(this) }
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            })
            return v
        }
    }

    private fun iniciar() {
        if (ativa) return
        ativa = true
        for (nome in DesfazerRegras.PREFS_VIGIADAS) {
            val sp = activity.getSharedPreferences(nome, Context.MODE_PRIVATE)
            vigiadas[sp] = nome
            fotos[nome] = HashMap(sp.all)
            sp.registerOnSharedPreferenceChangeListener(this)
        }
    }

    private fun parar() {
        if (!ativa) return
        anotar()   // o que ficou pendente
        ativa = false
        vigiadas.keys.forEach { it.unregisterOnSharedPreferenceChangeListener(this) }
        vigiadas.clear()
    }

    private val anotarDepois = Runnable { anotar() }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, chave: String?) {
        ui.removeCallbacks(anotarDepois)
        ui.postDelayed(anotarDepois, 400)
    }

    private fun anotar() {
        ui.removeCallbacks(anotarDepois)
        if (!ativa) return
        val ctx = activity.applicationContext
        val proprio = System.currentTimeMillis() - Desfazer.desfezEm < 1500
        val mudancas = ArrayList<Mudanca>()
        for ((sp, nome) in vigiadas) {
            val agora = HashMap(sp.all)
            if (!proprio) mudancas += DesfazerRegras.diferencas(nome, fotos[nome].orEmpty(), agora)
            fotos[nome] = agora
        }
        var ultimo: Passo? = null
        mudancas.groupBy { DesfazerRegras.categoria(it.prefs, it.chave)!! }
            .filterKeys { categorias == null || it in categorias }
            .forEach { (cat, ms) -> Desfazer.registrar(ctx, cat, rotulo, ms)?.let { ultimo = it } }
        if (mostrarBarra) ultimo?.let { BarraDesfazer.mostrar(activity, it, aoDesfazer) }
    }
}

/**
 * O aviso de 10 s embaixo da tela: "Mudou: <o quê>   [Desfazer]". O botão pega o foco (o controle não tem outra tecla
 * livre), mas qualquer seta devolve o foco para onde estava e segue o caminho normal da tecla: quem continua mexendo
 * não perde nenhum toque. OK desfaz; VOLTAR só fecha o aviso.
 */
object BarraDesfazer {
    private val ui = Handler(Looper.getMainLooper())
    private var atual: View? = null
    private var anterior: View? = null

    fun mostrar(activity: Activity, passo: Passo, aoDesfazer: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val raiz = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        fechar(devolverFoco = false)
        val focoAntes = activity.currentFocus
        val texto = Estilo.texto(activity, Estilo.Papel.CORPO, activity.getString(R.string.desf_barra, passo.rotulo)).apply {
            maxLines = 2
        }
        val botao = Estilo.botao(activity, activity.getString(R.string.desf_botao))
        val barra = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val d = Estilo.dp(activity, 16)
            setPaddingRelative(d * 2, d, d, d)
            background = Estilo.fundo(activity, 0xF2121528.toInt(), Estilo.RAIO_PILULA, Estilo.VIDRO_BORDA)   // quase sólido: legível sobre qualquer tela
            addView(texto, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = d
            })
            contentDescription = texto.text
        }
        raiz.addView(barra, FrameLayout.LayoutParams(Estilo.dp(activity, 720), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Estilo.dp(activity, 40) })
        atual = barra; anterior = focoAntes
        botao.setOnClickListener {
            fechar(devolverFoco = true)
            val app = activity.applicationContext
            Fundo.rodar("desfazer") {
                val r = Desfazer.desfazer(app, passo.id)
                ui.post {
                    AvisoTv.rapido(app, activity.getString(R.string.desf_botao), Desfazer.frase(app, r))
                    if (!activity.isDestroyed) aoDesfazer()
                }
            }
        }
        botao.setOnKeyListener { _, codigo, ev ->
            when (codigo) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                KeyEvent.KEYCODE_BACK -> { if (ev.action == KeyEvent.ACTION_UP) fechar(devolverFoco = true); true }
                else -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        fechar(devolverFoco = true)
                        activity.dispatchKeyEvent(ev)   // a seta segue para a tela, como se o aviso não estivesse ali
                    }
                    true
                }
            }
        }
        // Leitor de tela: anuncia o aviso inteiro (elevação 16.1).
        barra.announceForAccessibility(activity.getString(R.string.desf_barra, passo.rotulo) + ". " + activity.getString(R.string.desf_botao))
        botao.requestFocus()
        ui.postDelayed({ if (atual === barra) fechar(devolverFoco = true) }, DesfazerRegras.AVISO_MS)
    }

    fun fechar(devolverFoco: Boolean) {
        val b = atual ?: return
        val tinhaFoco = b.hasFocus()
        (b.parent as? ViewGroup)?.removeView(b)
        atual = null
        val volta = anterior; anterior = null
        if (devolverFoco && tinhaFoco && volta != null && volta.isAttachedToWindow) volta.requestFocus()
    }
}

/**
 * "Mudanças recentes" (Configurações > Sistema): o Diário das mudanças desta TV com OK para desfazer cada uma.
 * A mais nova primeiro; a já desfeita aparece apagada e sem OK.
 */
object TelaMudancas {
    fun abrir(activity: Activity, aoDesfazer: () -> Unit) {
        val passos = Desfazer.lista(activity)
        val coluna = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val caixa = CaixaTv(activity).setTitle(activity.getString(R.string.desf_tela))
        if (passos.isEmpty()) caixa.setMessage(activity.getString(R.string.desf_tela_vazia))
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        lateinit var janela: CaixaTv.Janela
        for (p in passos.take(20)) {
            val det = Desfazer.nomeCategoria(activity, p.categoria) + " · " + fmt.format(java.util.Date(p.quando)) +
                if (p.desfeito) " · " + activity.getString(R.string.desf_ja_desfeita) else ""
            val linha = Estilo.linhaOpcao(activity, p.rotulo, det)
            if (p.desfeito) linha.alpha = 0.5f
            else linha.setOnClickListener {
                janela.dismiss()
                val app = activity.applicationContext
                Fundo.rodar("desfazer") {
                    val r = Desfazer.desfazer(app, p.id)
                    activity.runOnUiThread {
                        AvisoTv.rapido(app, activity.getString(R.string.desf_botao), Desfazer.frase(app, r))
                        if (!activity.isDestroyed) aoDesfazer()
                    }
                }
            }
            coluna.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Estilo.dp(activity, 6) })
        }
        if (passos.isNotEmpty()) caixa.setView(android.widget.ScrollView(activity).apply { addView(coluna) })
        janela = caixa.setNegativeButton(activity.getString(R.string.cl_cancelar), null).create()
        janela.show()
    }
}

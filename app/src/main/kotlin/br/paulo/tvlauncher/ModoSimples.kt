package br.paulo.tvlauncher

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Modo simples (12/11/2026, ideia 10), para quem tem dificuldade com o controle: no lugar da HOME, até [MAX] cards
 * grandes (a TV ao vivo e os apps escolhidos), o nome do card em foco é falado, e um card "Pedir ajuda" manda um aviso
 * para o celular da família (Avisos, tipo AJUDA).
 *
 * Feito para o Quarto, mas DESLIGADO (chave [Extras.Chave.MODO_SIMPLES]) até o dono avisar. Ligar é livre; desligar
 * pede o PIN (na TV e no painel), senão quem está usando sai sem querer.
 * Os apps vêm da lista escolhida no painel ([CHAVE_APPS]); sem lista, os mais usados nos últimos 14 dias.
 */
object ModoSimples {
    const val CHAVE_APPS = "simples_apps"
    const val MAX = 6

    /**
     * Regra pura (ModoSimplesTest): os escolhidos que estão instalados, na ordem, completados com os mais usados, sem
     * repetir, até [max]. [vagas] = quantos cards sobram para apps (a TV ao vivo ocupa um quando há conta).
     */
    fun escolher(escolhidos: List<String>, instalados: Set<String>, maisUsados: List<String>, vagas: Int = MAX): List<String> =
        (escolhidos + maisUsados).filter { it in instalados }.distinct().take(vagas.coerceAtLeast(0))

    fun lerLista(texto: String): List<String> = texto.split(',', '\n').map { it.trim() }
        .filter { Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(it) }.distinct().take(MAX)

    fun escolhidos(ctx: Context): List<String> = lerLista(Extras.texto(ctx, CHAVE_APPS))
    fun definirEscolhidos(ctx: Context, pacotes: List<String>) = Extras.definirTexto(ctx, CHAVE_APPS, lerLista(pacotes.joinToString(",")).joinToString(","))

    fun ligado(ctx: Context) = Extras.ligado(ctx, Extras.Chave.MODO_SIMPLES)

    /** Pacotes com tela de abrir pela HOME (TV ou celular), sem o próprio launcher. */
    fun instalados(ctx: Context): Map<String, String> = try {
        val pm = ctx.packageManager
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        }.filter { it.activityInfo.packageName != ctx.packageName }
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    } catch (_: Exception) { emptyMap() }

    /** Mais usados (tempo na frente) nos últimos 14 dias. */
    fun maisUsados(ctx: Context): List<String> = try {
        val agora = System.currentTimeMillis()
        ctx.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(agora - 14 * 86_400_000L, agora)
            .filterValues { it.totalTimeInForeground > 60_000L }.entries.sortedByDescending { it.value.totalTimeInForeground }.map { it.key }
    } catch (_: Exception) { emptyList() }
}

/** A HOME do modo simples. A [MainActivity] manda para cá enquanto a chave estiver ligada. */
class ModoSimplesActivity : Activity() {
    private var voz: TextToSpeech? = null
    private var vozPronta = false
    private lateinit var grade: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, coluna) = Estilo.telaLista(this, "Olá!", "Escolha com as setas e aperte OK.")
        grade = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        coluna.addView(grade, LinearLayout.LayoutParams(-1, -2))
        setContentView(raiz)
        voz = TextToSpeech(this) { s ->
            if (s != TextToSpeech.SUCCESS) return@TextToSpeech
            val r = voz?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            vozPronta = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ModoSimples.ligado(this)) { finish(); return }
        Fundo.rodar("simples") {
            val instalados = ModoSimples.instalados(this)
            val temTv = PlayerArmazem.fontes(this).isNotEmpty()
            val pacotes = ModoSimples.escolher(ModoSimples.escolhidos(this), instalados.keys, ModoSimples.maisUsados(this),
                ModoSimples.MAX - if (temTv) 1 else 0)
            runOnUiThread { if (!isDestroyed) montar(temTv, pacotes.map { it to (instalados[it] ?: it) }) }
        }
    }

    override fun onDestroy() { super.onDestroy(); voz?.shutdown(); voz = null }

    /** VOLTAR não sai daqui: esta é a HOME. */
    @Deprecated("Activity") override fun onBackPressed() {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun montar(temTv: Boolean, apps: List<Pair<String, String>>) {
        grade.removeAllViews()
        val cards = ArrayList<View>()
        if (temTv) cards += card("TV", null, 0xFFFF7A6E.toInt()) {
            try { startActivity(Intent(this, PlayerActivity::class.java)) } catch (_: Exception) {}
        }
        for ((pacote, nome) in apps) cards += card(nome, pacote, 0xFF6FA8FF.toInt()) {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote)
            if (i != null) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        cards += card("Pedir ajuda", null, Estilo.ATENCAO) {
            val tv = android.provider.Settings.Global.getString(contentResolver, "device_name") ?: "TV"
            Avisos.enviar(applicationContext, Avisos.Tipo.AJUDA, "Pedido de ajuda", "Alguém pediu ajuda na TV $tv.")
            falar("Pronto. Avisei a família.")
            AvisoTv.rapido(applicationContext, "Pedido de ajuda", "Avisei a família no celular.")
        }
        // 3 por linha, cards grandes (o dobro dos da HOME).
        cards.chunked(3).forEach { linha ->
            grade.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                linha.forEach { addView(it, LinearLayout.LayoutParams(dp(250), dp(150)).apply { marginEnd = dp(24); bottomMargin = dp(24) }) }
            })
        }
        // Sair: linha discreta no fim, com o PIN do painel.
        grade.addView(Estilo.linhaTexto(this, "Voltar à tela completa").apply {
            textSize = 14f
            setOnClickListener {
                val sair = { Extras.definir(this@ModoSimplesActivity, Extras.Chave.MODO_SIMPLES, false); finish() }
                if (PainelArmazem.temPin(this@ModoSimplesActivity)) PinTv.pedir(this@ModoSimplesActivity, getString(R.string.player_pin_titulo)) { certo -> if (certo) sair() }
                else sair()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        cards.firstOrNull()?.requestFocus()
    }

    private fun card(nome: String, pacote: String?, cor: Int, abrir: () -> Unit): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Estilo.matiz(cor, 0.6f), Estilo.matiz(cor, 0.35f)))
            .apply { cornerRadius = dp(Estilo.RAIO).toFloat() }
        if (pacote != null) {
            val icone = try { packageManager.getApplicationBanner(pacote) ?: packageManager.getApplicationIcon(pacote) } catch (_: Exception) { null }
            if (icone != null) addView(ImageView(context).apply { setImageDrawable(icone); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(24), dp(16), dp(24), dp(48)) })
        }
        addView(TextView(context).apply {
            text = nome; setTextColor(Estilo.TEXTO); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Estilo.fonte(700); maxLines = 1
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(18), 0, dp(12), dp(12)) })
        setOnClickListener { abrir() }
        setOnFocusChangeListener { v, foco ->
            v.foreground = if (foco) GradientDrawable().apply { cornerRadius = dp(Estilo.RAIO).toFloat(); setStroke(dp(5), Estilo.FOCO) } else null
            v.animate().scaleX(if (foco) 1.06f else 1f).scaleY(if (foco) 1.06f else 1f).setDuration(Estilo.FOCO_MS).start()
            if (foco) falar(nome)
        }
    }

    private fun falar(t: String) {
        if (vozPronta) voz?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "simples")
    }
}

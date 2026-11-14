package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * A tela que aparece quando um alarme toca: acende a tela (e, pelo HDMI-CEC, normalmente liga a TV), mostra a hora
 * e toca o som até alguém apertar Parar ou Soneca. Sem ninguém, para sozinha em 10 min.
 * Voltar = Soneca (para um toque sem querer não desligar o alarme).
 * Também é a tela do fim do timer (EXTRA_TIMER, ver Timer.kt): aí só tem "Parar" e o Voltar também para.
 */
class AlarmeTocandoActivity : Activity() {
    companion object { const val EXTRA_TIMER = "timer" }
    private var timer = false
    private var nomeTimer = ""
    private val ui = Handler(Looper.getMainLooper())
    private var id = 0
    private var player: MediaPlayer? = null
    private var bip: ToneGenerator? = null
    private var volume: AlarmeGarantia.Volume? = null   // o que o alarme mudou no volume da TV, para devolver ao parar

    // Plano B do som: se o arquivo não abrir, um bipe a cada 1,2 s.
    private val bipar = object : Runnable {
        override fun run() {
            bip?.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
            ui.postDelayed(this, 1200)
        }
    }
    private val desistir = Runnable { parar(falar = false) }   // ninguém apareceu: sem "Bom dia" para a sala vazia
    private var rampa: Runnable? = null   // guardado para poder parar a rampa de volume quando a tela fecha

    /**
     * Toques que chegaram enquanto esta tela já tocava (ex.: o timer acaba durante o alarme). A tela é "singleInstance":
     * o Android entrega o pedido novo em onNewIntent, na MESMA tela, e antes ele era ignorado (o timer se perdia).
     * Agora fica na fila e abre logo depois que esta fechar.
     */
    private val fila = ArrayList<Intent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Acende a tela mesmo com o Chromecast em standby e não deixa apagar enquanto toca.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id = intent.getIntExtra(Despertador.EXTRA_ID, 0)
        timer = intent.getBooleanExtra(EXTRA_TIMER, false)
        // savedInstanceState != null = a MESMA tela sendo recriada (mudança de configuração quando a TV liga pelo HDMI-CEC,
        // ou o Android refazendo a tela): o toque já foi conferido e marcado na 1ª vez, e precisa continuar tocando.
        if (!timer && savedInstanceState == null) {
            // Alarme à prova de falha (AlarmeGarantia): o mesmo toque entregue duas vezes não toca de novo, e um toque
            // que chegou tarde demais (o relógio pulou para frente) não acorda a casa fora de hora: vira o aviso
            // "não tocou porque a TV estava desligada". Aberto pela conferência (EXTRA_JA_CONFERIDO): ela já decidiu
            // que ainda dá para tocar, então não confere o atraso de novo (uns segundos a mais não podem calar o alarme).
            val previsto = intent.getLongExtra(AlarmeGarantia.EXTRA_PREVISTO, 0L)
            if (AlarmeGarantia.jaTocou(this, previsto)) { Log.i("TvLauncher.Alarme", "toque repetido (id $id): ignorado"); finish(); return }
            if (!intent.getBooleanExtra(AlarmeGarantia.EXTRA_JA_CONFERIDO, false) && AlarmeGarantia.atrasado(previsto)) {
                Log.i("TvLauncher.Alarme", "toque chegou ${(System.currentTimeMillis() - previsto) / 60000} min atrasado: vira aviso")
                AlarmeGarantia.desistiuAtrasado(this, id, previsto)
                finish(); return
            }
            AlarmeGarantia.marcarTocou(this, previsto)
        }
        if (timer) {
            nomeTimer = getSharedPreferences("timer", MODE_PRIVATE).getString("nome", "") ?: ""
            Timer.limpar(this)   // acabou: some da HOME
        }
        setContentView(montar())
        tocarSom()
        ui.postDelayed(desistir, 10 * 60_000L)
        Log.i("TvLauncher.Alarme", if (timer) "timer acabou ('$nomeTimer')" else "tocando (id $id)")
    }

    private fun montar(): View {
        val alarme = Despertador.listar(this).firstOrNull { it.id == id }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false   // o botao com foco cresce um pouco: nao pode ser cortado
            clipToPadding = false
        }
        // Rótulo pequeno em cima (SECAO), a hora no desenho da HOME e do protetor (hora cheia forte, ":" apagado, minuto
        // fino; RelogioTela) e o nome do despertador embaixo, no tamanho de título (sistema de design, 12/11/2026).
        coluna.addView(Estilo.texto(this, Estilo.Papel.SECAO, getString(if (timer) R.string.at_timer else R.string.at_despertador)).apply {
            gravity = Gravity.CENTER
            setTextColor(Estilo.ACENTO)
        })
        coluna.addView(RelogioTela(this, data = false).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f)
            setTextColor(Estilo.TEXTO)
            typeface = Fontes.manrope(200)
            includeFontPadding = false
            gravity = Gravity.CENTER   // numa coluna vertical cada linha ocupa a largura toda: o texto precisa se centralizar
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        coluna.addView(Estilo.texto(this, Estilo.Papel.TITULO,
            if (timer) nomeTimer.ifBlank { "Tempo esgotado" } else alarme?.nome?.takeIf { it.isNotBlank() } ?: getString(R.string.al_titulo)).apply {
            setPadding(0, dp(8), 0, dp(40))
            gravity = Gravity.CENTER
        })
        val parar = botao(getString(R.string.al_parar)) { parar() }
        val soneca = botao(getString(R.string.al_soneca)) { soneca() }
        coluna.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL   // no timer só há um botão: centralizado embaixo da hora
            clipChildren = false
            addView(parar, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (!timer) marginEnd = dp(24) })
            if (!timer) addView(soneca, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        ui.post { parar.requestFocus() }
        return FrameLayout(this).apply {
            clipChildren = false
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            addView(coluna, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun tocarSom() {
        val alarme = if (timer) null else Despertador.listar(this).firstOrNull { it.id == id }
        val rampa = alarme?.rampa ?: 0
        player = SomAlarme.criar(this, alarme?.som ?: 0)
        // Volume garantido: tira do mudo e sobe a TV até o mínimo escolhido (só no despertador; o timer fica como está).
        if (!timer) volume = AlarmeGarantia.subirVolume(this)
        if (player != null) {
            // Rampa: o volume do player vai de 5% a 100% em [rampa] segundos, em passos de 1 s (curva ao quadrado:
            // o ouvido percebe o volume em escala logarítmica, então linear soaria "pulando" no começo).
            val inicio = System.currentTimeMillis()
            fun volume(): Float = if (rampa <= 0) 1f else {
                val x = ((System.currentTimeMillis() - inicio) / (rampa * 1000f)).coerceIn(0f, 1f)
                0.05f + 0.95f * x * x
            }
            player?.setVolume(volume(), volume())
            player?.start()
            if (rampa > 0) this.rampa = object : Runnable {
                override fun run() {
                    val v = volume()
                    player?.setVolume(v, v)
                    if (v < 1f) ui.postDelayed(this, 1000)
                }
            }.also { ui.post(it) }
            return
        }
        // Último recurso (arquivo não abriu): bipe pelo stream de música, que sai pelo HDMI.
        Log.i("TvLauncher.Alarme", "som do app falhou: usando bipe")
        bip = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        ui.post(bipar)
    }

    private fun pararSom() {
        player?.release()
        player = null
        rampa?.let { ui.removeCallbacks(it) }   // sem isso a rampa seguia rodando 1x/s depois de a tela fechar
        rampa = null
        ui.removeCallbacks(bipar)
        bip?.release()
        bip = null
        AlarmeGarantia.devolverVolume(this, volume)   // devolve como estava (se ninguém mexeu no volume durante o alarme)
        volume = null
    }

    private fun parar(falar: Boolean = true) {
        if (timer) { finish(); return }
        val bomDia = Despertador.listar(this).firstOrNull { it.id == id }?.bomDia == true && falar
        // Com o "Bom dia" falando logo em seguida, o volume da TV só volta ao que era depois que ele termina.
        if (bomDia) { AlarmeGarantia.devolverVolume(this, volume, AlarmeGarantia.ESPERA_BOM_DIA_MS); volume = null }
        Despertador.tocou(this, id)
        // "Bom dia" (05/11/2026): abre a previsão, que fala a saudação, a hora, o tempo e a agenda de hoje.
        // Só no Parar: a soneca (e o Voltar, que é soneca) não fala nada, a pessoa quer dormir mais 10 min.
        if (bomDia) startActivity(Intent(this, PrevisaoActivity::class.java)
            .putExtra(PrevisaoActivity.EXTRA_BOM_DIA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun soneca() {
        Despertador.soneca(this, id)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { if (timer) parar() else soneca(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(novo: Intent) {
        super.onNewIntent(novo)
        val novoTimer = novo.getBooleanExtra(EXTRA_TIMER, false)
        val mesmo = novoTimer == timer && (timer || novo.getIntExtra(Despertador.EXTRA_ID, 0) == id)
        if (mesmo) return   // o mesmo toque de novo (ex.: o Android repetiu o pedido): já está tocando
        Log.i("TvLauncher.Alarme", "outro toque chegou durante este: fica para depois")
        fila.add(novo)
    }

    override fun onDestroy() {
        pararSom()
        ui.removeCallbacks(desistir)
        // Abre os toques da fila só agora, com esta tela já fechada (senão o Android os entregaria a ela de novo).
        if (isFinishing) for (i in fila) {
            try { applicationContext.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Log.w("TvLauncher.Alarme", "não reabri o toque da fila: $e") }
        }
        fila.clear()
        super.onDestroy()
    }

    // Botão em pílula, como os botões do resto do sistema (Estilo.botao), só que maior: aqui é a única coisa na tela e
    // tem de ser lido de longe, meio dormindo. Foco = pílula branca com texto escuro e cresce um pouco.
    private fun botao(rotulo: String, aoClicar: () -> Unit) = Estilo.texto(this, Estilo.Papel.CORPO, rotulo).apply {
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isFocusable = true
        isClickable = true
        val normal = Estilo.fundo(context, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PILULA, Estilo.BORDA)
        val focado = Estilo.fundo(context, Estilo.FOCO, Estilo.RAIO_PILULA)
        background = normal
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, f ->
            v.background = if (f) focado else normal
            (v as TextView).setTextColor(if (f) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            v.typeface = Estilo.fonte(if (f) 600 else 500)
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }


    private fun texto(sp: Float, cor: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

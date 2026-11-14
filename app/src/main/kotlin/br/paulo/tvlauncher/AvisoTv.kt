package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Aviso por cima de QUALQUER tela (Netflix, YouTube, a HOME…): o recado mandado pelo painel e o aviso de chuva
 * (05/11/2026). Uma pílula de vidro no canto de cima à direita que some sozinha depois de alguns segundos.
 *
 * Por que a janela é do serviço de acessibilidade (TYPE_ACCESSIBILITY_OVERLAY) e não uma janela flutuante comum: é a
 * mesma escolha do PareamentoNaTv — medido em 04/11/2026, há telas do sistema que escondem as janelas flutuantes de
 * apps, e a de acessibilidade não entra nesse bloqueio. Sem o serviço ligado não dá para mostrar ([mostrar] = false).
 *
 * A janela NÃO pega o foco (FLAG_NOT_FOCUSABLE): quem está vendo um filme continua com o controle no filme. Para
 * fechar antes da hora, o VOLTAR do controle fecha o aviso (o serviço de teclas pergunta a [teclaVoltar] primeiro);
 * esse VOLTAR não chega ao app da frente, para não sair do filme sem querer.
 *
 * TV com a tela apagada: o recado fica GUARDADO e aparece quando a tela acender (até [VALIDADE_PENDENTE_MS]);
 * o aviso de chuva não espera ([mostrar] com guardarSeApagada = false), porque passaria da hora.
 */
object AvisoTv {
    private const val TAG = "TvLauncher.Aviso"
    private const val PREFS = "aviso_tv"
    /** Último aviso mostrado (lido pelo ProtetorGaleria). Fora do backup: é passageiro. */
    const val PREFS_ULTIMO = "aviso_tv_ultimo"
    private const val VALIDADE_PENDENTE_MS = 12 * 60 * 60_000L   // recado de mais de 12 h atrás já não serve

    private val principal = Handler(Looper.getMainLooper())
    private var janela: View? = null
    private var fecharDepois: Runnable? = null
    private var ouvindoTela = false

    enum class Resultado { MOSTRADO, GUARDADO, DESCARTADO, SEM_ACESSIBILIDADE }

    /**
     * Mostra o aviso agora (ou guarda até a tela acender). Pode chamar de qualquer thread.
     * [segundos] = quanto tempo fica na tela; [som] = dois bipes curtos para chamar a atenção.
     */
    fun mostrar(
        ctx: Context, titulo: String, texto: String, segundos: Int, som: Boolean, guardarSeApagada: Boolean,
        imagem: android.graphics.Bitmap? = null,   // recado com foto (10/11/2026, RecadoImagem.kt); não é guardado com a tela apagada
        icone: android.graphics.drawable.Drawable? = null,   // ícone de linha à esquerda (sistema de design); null = ponto de luz
        fica: AvisoRegras.Fica = AvisoRegras.Fica.PASSAGEIRO,   // quanto tempo o cartão fica no protetor (12/11/2026)
    ): Resultado {
        val app = ctx.applicationContext
        // Guarda o último aviso (recado ou chuva) para o protetor de tela mostrar como cartão por algumas horas
        // ("quadro da família", 10/11/2026). Só o texto: a foto do recado não é guardada. Quanto tempo fica: [fica]
        // (só o que pede atenção fica horas; confirmação como "O Claude olhou a tela" sai em 2 min).
        app.getSharedPreferences(PREFS_ULTIMO, Context.MODE_PRIVATE).edit()
            .putString("titulo", titulo).putString("texto", texto).putLong("em", System.currentTimeMillis())
            .putString("fica", fica.name).apply()
        NotificacoesTv.avisarMudanca()
        if (AutorizaShizukuService.instancia == null) return Resultado.SEM_ACESSIBILIDADE
        if (!telaAcesa(app)) {
            if (!guardarSeApagada) return Resultado.DESCARTADO.also { Log.i(TAG, "tela apagada: aviso descartado ($titulo)") }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("titulo", titulo).putString("texto", texto).putInt("segundos", segundos)
                .putBoolean("som", som).putString("fica", fica.name).putLong("em", System.currentTimeMillis()).apply()
            principal.post { esperarTelaAcender(app) }
            return Resultado.GUARDADO
        }
        principal.post { abrirJanela(titulo, texto, segundos, som, imagem, icone) }
        return Resultado.MOSTRADO
    }

    /**
     * Aviso curto de confirmação ("Claude abriu a Previsão", "Despertador salvo"): substitui os Toasts do Android, que
     * eram outro formato e outro lugar da tela. Sem o serviço de acessibilidade, cai no Toast (melhor que nada).
     */
    fun rapido(ctx: Context, titulo: String, texto: String, segundos: Int = 5) {
        val r = mostrar(ctx, titulo, texto, segundos, som = false, guardarSeApagada = false)
        if (r == Resultado.SEM_ACESSIBILIDADE) principal.post {
            try { android.widget.Toast.makeText(ctx.applicationContext, "$titulo: $texto", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    /** O serviço de teclas chama isto antes de tudo: VOLTAR com um aviso na tela fecha o aviso (true = tecla usada). */
    fun teclaVoltar(e: KeyEvent): Boolean {
        if (janela == null || e.keyCode != KeyEvent.KEYCODE_BACK) return false
        // Fecha no "sobe" da tecla; o "desce" também é engolido, senão o app da frente veria só metade da tecla.
        if (e.action == KeyEvent.ACTION_UP) principal.post { fechar() }
        return true
    }

    private fun telaAcesa(app: Context) = app.getSystemService(PowerManager::class.java)?.isInteractive != false

    // ---------------------------------------------------------------- recado guardado (tela apagada)

    private fun esperarTelaAcender(app: Context) {
        if (ouvindoTela) return
        ouvindoTela = true
        // Receiver registrado em código (SCREEN_ON não chega a receiver do manifest). Vive enquanto o processo vive;
        // como o serviço de acessibilidade segura o processo, é suficiente. Sai assim que entrega o recado.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                ouvindoTela = false
                // 3 s: dá tempo da imagem voltar na TV antes do cartão aparecer.
                principal.postDelayed({ entregarGuardado(app) }, 3000)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun entregarGuardado(app: Context) {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val em = p.getLong("em", 0)
        val titulo = p.getString("titulo", null)
        val texto = p.getString("texto", null)
        val segundos = p.getInt("segundos", 60)
        val som = p.getBoolean("som", true)
        val fica = AvisoRegras.Fica.values().firstOrNull { it.name == p.getString("fica", null) } ?: AvisoRegras.Fica.ATENCAO
        p.edit().clear().apply()
        if (titulo == null || texto == null || System.currentTimeMillis() - em > VALIDADE_PENDENTE_MS) return
        val h = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(em))
        mostrar(app, app.getString(R.string.av_enviado_as, titulo, h), texto, segundos, som, guardarSeApagada = true, fica = fica)
    }

    // ---------------------------------------------------------------- janela

    /**
     * O aviso é a PÍLULA do sistema de design (dono, 10/11/2026: "gosto dela… só adapta"): a mesma pílula de vidro do
     * topo da HOME, do mesmo tamanho, à direita logo abaixo da linha do topo (onde o protetor mostra as notificações). Um formato só para
     * tudo que a TV avisa: Claude, chuva, recado, campainha, hora de dormir.
     *
     * Vidro sem desfoque: o Android só desfoca o que está atrás de uma janela de Activity (Window.setBackgroundBlurRadius);
     * esta é uma janela solta por cima de outro app, então o "vidro" é a tinta escura translúcida + fio claro, igual à
     * pílula da HOME (a mesma Estilo.vidro). Recado comprido ou com foto vira cartão de raio 16.
     */
    private fun abrirJanela(
        titulo: String, texto: String, segundos: Int, som: Boolean,
        imagem: android.graphics.Bitmap? = null, icone: android.graphics.drawable.Drawable? = null,
    ) {
        val svc = AutorizaShizukuService.instancia ?: return
        fechar()   // um aviso por vez: o novo substitui o anterior
        fun dp(v: Int) = Estilo.dp(svc, v)
        val largura = (svc.resources.displayMetrics.widthPixels * 0.5).toInt()   // não tapa o filme: no máximo metade da largura (com 42% a frase de chuva quebrava em 2 linhas)

        // Mesma pílula do topo da HOME (dono, 10/11/2026: "deveria aparecer como uma pílula igual às outras"): 32 dp de
        // altura, ícone de 20 dp, uma linha só: "Chuva · 70% de chance…". O nome do aviso vem claro e forte, como o "27°"
        // da pílula do clima; a mensagem vem logo depois, no mesmo tamanho.
        val maxTexto = largura - dp(12 + 14 + 20 + 8)
        val linha = android.text.SpannableStringBuilder().apply {
            append(titulo, object : android.text.style.MetricAffectingSpan() {   // TypefaceSpan(Typeface) só existe no Android 9+
                override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Estilo.fonte(600) }
                override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Estilo.fonte(600) }
            }, 0)
            append("  ·  ")
            append(texto)
        }
        val msg = Estilo.texto(svc, Estilo.Papel.CORPO, "").apply {
            text = linha; textSize = 13f; includeFontPadding = false
            maxWidth = maxTexto
        }
        // Cabe numa linha = pílula. Recado comprido não cabe: aí vira cartão (raio 16, até 3 linhas), em vez de cortar
        // o texto que alguém mandou. Com foto, idem.
        val cabe = android.text.Layout.getDesiredWidth(linha, msg.paint) <= maxTexto   // mede com o negrito do nome (o measureText simples media a menos e cortava o fim, medido na TV)
        val cartao = imagem != null || !cabe
        if (cartao) { msg.maxLines = 3; msg.ellipsize = android.text.TextUtils.TruncateAt.END; msg.setLineSpacing(0f, 1.15f) }
        else { msg.isSingleLine = true; msg.ellipsize = android.text.TextUtils.TruncateAt.END }
        val coluna = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(msg)
            if (imagem != null) addView(android.widget.ImageView(svc).apply {
                setImageBitmap(imagem); adjustViewBounds = true
                maxHeight = (svc.resources.displayMetrics.heightPixels * 0.4).toInt()
                maxWidth = largura
                clipToOutline = true
                background = Estilo.fundo(svc, 0, Estilo.RAIO_PEQUENO)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Estilo.E1 + 4)
            })
        }
        val pilula = Estilo.pilula(svc, icone ?: Estilo.fundo(svc, Estilo.ACENTO, Estilo.RAIO_PILULA), focavel = false).apply {
            // sem ícone: um ponto no acento, menor que o ícone (os 20 dp da pílula ficam para ícone de verdade)
            if (icone == null) (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = dp(8); height = dp(8); marginEnd = dp(10) }
            if (cartao) {
                background = Estilo.vidro(svc, Estilo.RAIO)
                setPadding(dp(14), dp(12), dp(16), dp(12))
                gravity = Gravity.TOP
            }
            addView(coluna, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Logo ABAIXO da linha do topo da HOME (que termina em ~76 dp), alinhada à direita: não cobre a voz, o painel
            // nem o perfil (dono, 10/11: "cobre os ícones atrás").
            x = dp(Estilo.MARGEM_LATERAL); y = dp(84)
            windowAnimations = android.R.style.Animation_Toast   // entra e sai esmaecendo, como as notificações do sistema
        }
        try {
            svc.getSystemService(WindowManager::class.java).addView(pilula, p)
        } catch (e: Exception) {
            Log.w(TAG, "não consegui abrir a janela: $e"); return
        }
        janela = pilula
        if (som) bipar()
        fecharDepois = Runnable { fechar() }.also { principal.postDelayed(it, segundos * 1000L) }
        Log.i(TAG, "aviso na tela por ${segundos}s: $titulo")
    }

    private fun fechar() {
        fecharDepois?.let { principal.removeCallbacks(it) }
        fecharDepois = null
        janela?.let { j -> try { j.context.getSystemService(WindowManager::class.java).removeView(j) } catch (_: Exception) {} }
        janela = null
    }

    /** Dois bipes curtos no volume de notificação. Não pede o foco do áudio: o som do filme não pausa. */
    private fun bipar() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            principal.postDelayed({ t.release() }, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "sem som: $e")   // alguns aparelhos não têm o gerador de tons; o aviso aparece mesmo assim
        }
    }
}

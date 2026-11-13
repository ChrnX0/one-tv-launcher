package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import java.util.Calendar

/**
 * Escurecer e esquentar a tela à noite (10/11/2026, ideia L; li no tv-remote: "dim or blue light filter").
 *
 * Uma película do tamanho da tela, por cima de tudo, sem foco e sem toque: âmbar semitransparente. A janela é do
 * serviço de acessibilidade (TYPE_ACCESSIBILITY_OVERLAY), igual ao AvisoTv: nenhum app consegue escondê-la e não precisa
 * da permissão de sobreposição. Liga e desliga sozinha pelo horário ([inicioMin]..[fimMin], pode passar da meia-noite),
 * conferido a cada minuto, só com a chave [Extras.Chave.FILTRO_NOTURNO] ligada.
 *
 * Não verificado: se algum app com DRM (Netflix) se recusa a tocar com uma janela por cima. A película não é "segura"
 * (não usa FLAG_SECURE), então foto da tela pelo painel continua saindo, já com o âmbar.
 */
object FiltroNoturno {
    const val CHAVE_INICIO = "filtro_inicio_min"
    const val CHAVE_FIM = "filtro_fim_min"
    const val CHAVE_FORCA = "filtro_forca"   // 0..100
    const val PADRAO_INICIO = 22 * 60
    const val PADRAO_FIM = 6 * 60
    const val PADRAO_FORCA = 35

    private val ui by lazy { Handler(Looper.getMainLooper()) }   // lazy: os testes JVM usam só as regras puras
    private var pelicula: View? = null
    private var servico: AccessibilityService? = null

    /** Regra pura (FiltroNoturnoTest): [minuto] (0..1439) está dentro da janela? A janela pode atravessar a meia-noite. */
    fun dentro(minuto: Int, inicio: Int, fim: Int): Boolean = when {
        inicio == fim -> false
        inicio < fim -> minuto in inicio until fim
        else -> minuto >= inicio || minuto < fim
    }

    /** Cor da película para a força 0..100: âmbar (luz azul cortada) com transparência proporcional. Máx. 70% opaca. */
    fun cor(forca: Int): Int {
        val f = forca.coerceIn(0, 100)
        val alfa = (f * 0.70f * 255 / 100).toInt()
        return Color.argb(alfa, 40, 18, 0)
    }

    private val relogio = object : Runnable {
        override fun run() { avaliar(); ui.postDelayed(this, 60_000) }
    }

    /** Chamado quando o serviço de acessibilidade conecta. */
    fun iniciar(svc: AccessibilityService) {
        servico = svc
        ui.removeCallbacks(relogio)
        ui.post(relogio)
    }

    fun parar() { ui.removeCallbacks(relogio); tirar(); servico = null }

    /** Liga ou tira a película conforme a chave e o horário. Também chamado ao mexer nos ajustes. */
    fun avaliar() {
        val svc = servico ?: return
        val c = Calendar.getInstance()
        val minuto = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val ligar = Extras.ligado(svc, Extras.Chave.FILTRO_NOTURNO) && dentro(minuto,
            Extras.numero(svc, CHAVE_INICIO, PADRAO_INICIO), Extras.numero(svc, CHAVE_FIM, PADRAO_FIM))
        if (ligar) por(svc, Extras.numero(svc, CHAVE_FORCA, PADRAO_FORCA)) else tirar()
    }

    private fun por(svc: AccessibilityService, forca: Int) {
        pelicula?.let { it.setBackgroundColor(cor(forca)); return }
        val v = View(svc).apply { setBackgroundColor(cor(forca)) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try { svc.getSystemService(WindowManager::class.java).addView(v, p); pelicula = v } catch (_: Exception) {}
    }

    private fun tirar() {
        val v = pelicula ?: return
        pelicula = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }
}

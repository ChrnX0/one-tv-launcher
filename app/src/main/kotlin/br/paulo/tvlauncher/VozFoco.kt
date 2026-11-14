package br.paulo.tvlauncher

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap

/**
 * Ideia 48 (14/11/2026): com a chave LER_FOCO ligada, toda tela do launcher fala o que ganhou o foco. Um ponto só,
 * sem tocar em cada tela: o [LauncherApp] chama [aoRetomar] em cada Activity, que ganha um ouvinte global de foco.
 *
 * Antes só o modo simples falava o card em foco; ele continua com a voz dele (a tela dele fica de fora daqui, para
 * não falar duas vezes). Fala depois de [ESPERA_MS] parado no item: descer uma lista rápido não vira uma enxurrada.
 * O motor de voz (o do Google, já na TV) só liga com a chave ligada e é solto quando ela desliga.
 */
object VozFoco {
    private const val ESPERA_MS = 350L
    private val comOuvinte = WeakHashMap<View, ViewTreeObserver.OnGlobalFocusChangeListener>()
    private var voz: TextToSpeech? = null
    private var pronta = false
    private var pendente: Runnable? = null

    fun aoRetomar(a: Activity) {
        if (a is ModoSimplesActivity) return
        val ligado = Extras.ligado(a, Extras.Chave.LER_FOCO)
        if (!ligado) { soltar(); return }
        val raiz = a.window?.decorView ?: return
        if (comOuvinte.containsKey(raiz)) return
        val ouvinte = ViewTreeObserver.OnGlobalFocusChangeListener { _, novo ->
            if (novo == null || !Extras.ligado(a, Extras.Chave.LER_FOCO)) return@OnGlobalFocusChangeListener
            pendente?.let { raiz.removeCallbacks(it) }
            val r = Runnable { if (novo.isFocused) falar(a, textoDe(novo)) }
            pendente = r
            raiz.postDelayed(r, ESPERA_MS)
        }
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener(ouvinte)
        comOuvinte[raiz] = ouvinte
        ligar(a)
    }

    /** O que a view em foco diz: a descrição dela, ou os textos visíveis dentro dela (nome, detalhe, valor). */
    fun textoDe(v: View): String {
        v.contentDescription?.takeIf { it.isNotBlank() }?.let { return FalaFoco.frase(listOf(it)) }
        val partes = ArrayList<CharSequence?>()
        fun juntar(x: View, fundo: Int) {
            if (x.visibility != View.VISIBLE || fundo > 4) return
            when {
                x is TextView -> partes += x.text
                x.contentDescription != null && x !== v -> partes += x.contentDescription
                x is ViewGroup -> for (i in 0 until x.childCount) juntar(x.getChildAt(i), fundo + 1)
            }
        }
        juntar(v, 0)
        return FalaFoco.frase(partes)
    }

    private fun ligar(a: Activity) {
        if (voz != null) return
        val app = a.applicationContext
        voz = TextToSpeech(app) { s ->
            if (s != TextToSpeech.SUCCESS) return@TextToSpeech
            val r = voz?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            pronta = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun falar(a: Activity, frase: String) {
        if (frase.isEmpty() || !pronta) return
        try { voz?.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "foco") } catch (_: Exception) {}
    }

    /** Chave desligada: tira os ouvintes e solta o motor de voz. */
    private fun soltar() {
        for ((raiz, o) in comOuvinte) try { raiz.viewTreeObserver.removeOnGlobalFocusChangeListener(o) } catch (_: Exception) {}
        comOuvinte.clear()
        voz?.shutdown(); voz = null; pronta = false
    }
}

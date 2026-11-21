package br.paulo.tvlauncher.input

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import br.paulo.tvlauncher.LauncherApp
import br.paulo.tvlauncher.ModoSimplesActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.settings.Extras
import java.lang.ref.WeakReference
import java.util.Locale

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
    // Vazamento corrigido (20/11/2026): antes era um WeakHashMap<tela, ouvinte>, mas o ouvinte (o valor) segura a
    // própria tela (a chave), e aí o mapa nunca soltava nenhuma: cada tela aberta com a leitura ligada ficava presa na
    // memória para sempre. Agora o ouvinte mora na própria tela (uma etiqueta da view) e aqui só fica uma referência
    // fraca, que não impede o Android de jogar a tela fechada fora.
    private val telas = ArrayList<WeakReference<View>>()
    private var voz: TextToSpeech? = null
    private var pronta = false
    // Fraca também: a fala pendente segura a tela (e a Activity) até rodar.
    private var pendente: WeakReference<Runnable>? = null

    fun aoRetomar(a: Activity) {
        if (a is ModoSimplesActivity) return
        val ligado = Extras.ligado(a, Extras.Chave.LER_FOCO)
        if (!ligado) { soltar(); return }
        val raiz = a.window?.decorView ?: return
        if (raiz.getTag(R.id.voice_focus_listener) != null) return
        val ouvinte = ViewTreeObserver.OnGlobalFocusChangeListener { _, novo ->
            if (novo == null || !Extras.ligado(a, Extras.Chave.LER_FOCO)) return@OnGlobalFocusChangeListener
            pendente?.get()?.let { raiz.removeCallbacks(it) }
            val r = Runnable { if (novo.isFocused) falar(a, textoDe(novo)) }
            pendente = WeakReference(r)
            raiz.postDelayed(r, ESPERA_MS)
        }
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener(ouvinte)
        raiz.setTag(R.id.voice_focus_listener, ouvinte)
        telas.removeAll { it.get() == null }
        telas += WeakReference(raiz)
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
        for (ref in telas) {
            val raiz = ref.get() ?: continue
            val o = raiz.getTag(R.id.voice_focus_listener) as? ViewTreeObserver.OnGlobalFocusChangeListener ?: continue
            try { raiz.viewTreeObserver.removeOnGlobalFocusChangeListener(o) } catch (_: Exception) {}
            raiz.setTag(R.id.voice_focus_listener, null)
        }
        telas.clear()
        pendente = null
        voz?.shutdown(); voz = null; pronta = false
    }
}

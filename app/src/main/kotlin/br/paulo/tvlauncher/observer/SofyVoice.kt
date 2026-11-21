package br.paulo.tvlauncher.observer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import org.json.JSONObject
import java.util.Locale

/**
 * A voz da Sofy (20/11/2026, proposta B + acessibilidade). Por quê: quem enxerga pouco não lê a pílula a 3 m. Para quê:
 * a fala chega pelo ouvido. Como, conforme [SofyPolicy.Voice]:
 *  - ANNOUNCE: o TalkBack está ligado, então quem lê é ele (voz, velocidade e língua que a pessoa já escolheu);
 *  - SPEAK: a chave "Sofy fala" ligada sem leitor de tela: o motor de voz da TV fala, e a boca mexe a cada palavra.
 * Voz desligada de fábrica (decisão do Paulo, 20/11). O motor só é aberto quando há o que falar e é solto 10 s depois.
 */
object SofyVoice {
    private const val TAG = "TvLauncher.SofyVoz"
    private const val PREFS = "sofy"
    private const val SOLTAR_MS = 10_000L

    private val principal = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var pronta = false
    private var pendente: Pair<String, SofyDrawable?>? = null
    private var boca: SofyDrawable? = null
    private val soltar = Runnable { soltarMotor() }

    /** A chave "Sofy fala" (desligada de fábrica). */
    fun voiceOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("voice", false)
    fun setVoiceOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply()

    /** Algum leitor de tela que fala (TalkBack ou outro) está ligado? Aí ele lê, e a nossa voz fica quieta. */
    fun screenReaderOn(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        am.isEnabled && am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    }.getOrDefault(false)

    /** Entrega a fala do jeito que a regra escolheu. [face] = a Sofy na tela, para a boca mexer junto (só no SPEAK). */
    fun say(ctx: Context, voice: SofyPolicy.Voice, text: String, face: SofyDrawable? = null) {
        when (voice) {
            SofyPolicy.Voice.NONE -> Unit
            SofyPolicy.Voice.ANNOUNCE -> announce(ctx, text)
            SofyPolicy.Voice.SPEAK -> principal.post { speak(ctx.applicationContext, text, face) }
        }
    }

    private fun announce(ctx: Context, text: String) {
        runCatching {
            val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return
            if (!am.isEnabled) return
            @Suppress("DEPRECATION")
            val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                packageName = ctx.packageName; className = javaClass.name; this.text.add(text)
            }
            am.sendAccessibilityEvent(e)
        }.onFailure { Log.w(TAG, "anúncio falhou: $it") }
    }

    private fun speak(app: Context, text: String, face: SofyDrawable?) {
        principal.removeCallbacks(soltar)
        if (tts != null && pronta) { falarJa(text, face); return }
        pendente = text to face
        if (tts != null) return   // ainda abrindo: fala quando ficar pronta
        tts = TextToSpeech(app) { s ->
            principal.post {
                val r = if (s == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
                        else TextToSpeech.LANG_NOT_SUPPORTED
                pronta = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!pronta) { Log.w(TAG, "sem voz para ${Locale.getDefault()} (r=$r)"); soltarMotor(); return@post }
                tts?.setOnUtteranceProgressListener(ouvinte)
                pendente?.let { (t, f) -> falarJa(t, f) }; pendente = null
            }
        }
    }

    private fun falarJa(text: String, face: SofyDrawable?) {
        boca = face
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sofy") }
        principal.postDelayed(soltar, SOLTAR_MS + text.length * 90L)
    }

    /** A boca abre e fecha a cada palavra que o motor começa (onRangeStart) e para quando a fala acaba. */
    private val ouvinte = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { principal.post { boca?.talking = true } }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { principal.post { boca?.word() } }
        override fun onDone(id: String?) { principal.post { boca?.talking = false; boca = null } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { onDone(id) }
    }

    private fun soltarMotor() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; pronta = false; boca?.talking = false; boca = null
    }

    /**
     * Para medir na TV (adb `EXTRAS --es o sofy_voz`): motor padrão, se a língua do sistema tem voz instalada e quantas
     * vozes há nela, a chave e o leitor de tela. Abre o motor só para olhar e solta logo depois.
     */
    fun status(ctx: Context, done: (JSONObject) -> Unit) {
        val app = ctx.applicationContext
        val o = JSONObject().put("voice_on", voiceOn(app)).put("screen_reader", screenReaderOn(app))
            .put("locale", Locale.getDefault().toLanguageTag())
        principal.post {
            var t: TextToSpeech? = null
            t = TextToSpeech(app) { s ->
                runCatching {
                    o.put("engine", t?.defaultEngine).put("init", s == TextToSpeech.SUCCESS)
                    val r = t?.isLanguageAvailable(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    o.put("language", when (r) {
                        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE -> "país"
                        TextToSpeech.LANG_AVAILABLE -> "língua"
                        TextToSpeech.LANG_MISSING_DATA -> "falta baixar"
                        else -> "não tem"
                    })
                    val lang = Locale.getDefault().language
                    val vozes = t?.voices.orEmpty().filter { it.locale.language == lang }
                    o.put("voices", vozes.size).put("voices_offline", vozes.count { !it.isNetworkConnectionRequired })
                }.onFailure { o.put("error", it.toString()) }
                runCatching { t?.shutdown() }
                done(o)
            }
        }
    }
}

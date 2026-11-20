package br.paulo.onetv.celular

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * O controle nativo da TV (estudo global 20/11/2026, ideia 14).
 *
 * Por quê: o app abria numa lista de TVs e, para mexer na cruz, a pessoa entrava no painel e rolava até o Controle.
 * Para quê: o uso mais comum do celular (controlar a TV) fica a 1 toque, como no app do Google TV e no da Apple TV.
 * Como: uma área grande de deslizar (deslizar = setas, tocar = OK), vibração em cada tecla, Voltar/Início/⏯ embaixo,
 * os botões de volume do celular mudam o volume da TV, e o teclado do celular abre sozinho quando a TV pede texto
 * (ideia 15: o painel diz "pedeTexto"; perguntamos a cada 2 s só com esta tela aberta, sem gastar bateria fora dela).
 * A notificação e o bloco das Configurações rápidas (ideia 16) ficam em [ControleNotificacao] e [ControleBloco].
 */
class ControleActivity : Activity() {
    companion object {
        const val EXTRA_CHAVE = "chave"
        private const val PERGUNTAR_MS = 2_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tv: Pareamento.Tv
    private lateinit var situacao: TextView
    private lateinit var campo: EditText
    private var tecladoAberto = false
    private var abriuPainel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Visual.atualizar(this)
        tv = Tvs.porChave(this, intent.getStringExtra(EXTRA_CHAVE)) ?: ApiTv.ultima(this) ?: run { finish(); return }
        if (!ApiTv.podeUsar(tv)) { abrirPainel(); finish(); return }
        ApiTv.lembrar(this, tv)
        window.statusBarColor = Visual.FUNDO; window.navigationBarColor = Visual.FUNDO
        if (Visual.claro) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(montar())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onResume() { super.onResume(); ui.post(perguntar); ControleNotificacao.mostrar(this, tv) }
    override fun onPause() { ui.removeCallbacks(perguntar); super.onPause() }

    private fun dp(v: Int) = Visual.dp(this, v)

    private fun montar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Visual.FUNDO)
        setPadding(dp(20), dp(24), dp(20), dp(20))
        // Cabeçalho: o nome da TV (Nunito, como na TV) e o painel completo à direita, como link discreto.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Visual.texto(context, tv.nome.ifEmpty { tv.ip }, 24f, negrito = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Visual.texto(context, getString(R.string.cel_painel_completo), 16f, Visual.TEXTO_2).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
                isClickable = true; setOnClickListener { abrirPainel() }
            })
        })
        situacao = Visual.texto(context, getString(R.string.cel_ctrl_dica), 15f, Visual.TEXTO_2).apply { setPadding(0, dp(4), 0, dp(12)) }
        addView(situacao)
        addView(areaDeslizar(), LinearLayout.LayoutParams(-1, 0, 1f))
        campo = EditText(context).apply {
            visibility = View.GONE; hint = getString(R.string.cel_ctrl_digite); setSingleLine()
            setTextColor(Visual.TEXTO); setHintTextColor(Visual.TEXTO_2); textSize = 18f
            background = Visual.cartao(context); setPadding(dp(16), dp(14), dp(16), dp(14))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> mandarTexto(); true }
        }
        addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        // Fila de baixo: os 3 botões que não cabem num gesto. ⏯ é o principal (o único preenchido), como na TV.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun lp() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
            addView(Visual.botao(context, getString(R.string.cel_ctrl_voltar), primario = false) { tecla("voltar") }, lp())
            addView(Visual.botao(context, getString(R.string.cel_ctrl_inicio), primario = false) { tecla("inicio") }, lp())
            addView(Visual.botao(context, "⏯") { tecla("play") }, lp())
            addView(Visual.botao(context, "⌨", primario = false) { mostrarTeclado(!tecladoAberto) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun lp() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
            addView(Visual.botao(context, getString(R.string.cel_ctrl_vol_menos), primario = false) { tecla("volume-") }, lp())
            addView(Visual.botao(context, getString(R.string.cel_ctrl_mudo), primario = false) { tecla("mudo") }, lp())
            addView(Visual.botao(context, getString(R.string.cel_ctrl_vol_mais), primario = false) { tecla("volume+") }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** A área de deslizar: um cartão grande. Deslizar para um lado = a seta daquele lado; tocar = OK; segurar = Menu. */
    private fun areaDeslizar(): View = FrameLayout(this).apply {
        background = Visual.cartao(context)
        contentDescription = getString(R.string.cel_ctrl_area)
        addView(TextView(context).apply {
            text = getString(R.string.cel_ctrl_area); setTextColor(Visual.TEXTO_2); textSize = 16f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        val gestos = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tecla("ok", this@apply); return true }
            override fun onLongPress(e: MotionEvent) { tecla("menu", this@apply) }
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = b.x - (a?.x ?: b.x); val dy = b.y - (a?.y ?: b.y)
                if (abs(dx) < dp(24) && abs(dy) < dp(24)) return false
                tecla(if (abs(dx) > abs(dy)) (if (dx > 0) "direita" else "esquerda") else (if (dy > 0) "baixo" else "cima"), this@apply)
                return true
            }
        })
        setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) v.performClick(); gestos.onTouchEvent(e) }
    }

    /** Uma tecla: vibra na hora (resposta do dedo) e manda em segundo plano; erro aparece na linha de situação. */
    private fun tecla(nome: String, origem: View? = null) {
        (origem ?: window.decorView).performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        ApiTv.emSegundoPlano {
            val r = ApiTv.tecla(tv, nome)
            ui.post { tratar(r.codigo) }
        }
    }

    private fun tratar(codigo: Int) {
        when (codigo) {
            in 200..299 -> situacao.text = getString(R.string.cel_ctrl_dica)
            401 -> entrarPrimeiro()
            0 -> situacao.text = getString(R.string.cel_ctrl_sem_tv)
            else -> situacao.text = getString(R.string.cel_ctrl_recusou)
        }
    }

    /** Sem sessão (nunca entrou ou passou 30 min sem uso): o painel abre para o PIN, e o controle volta a valer. */
    private fun entrarPrimeiro() {
        situacao.text = getString(R.string.cel_ctrl_entrar)
        if (!abriuPainel) { abriuPainel = true; abrirPainel() }
    }

    private fun abrirPainel() = startActivity(Intent(this, PainelActivity::class.java)
        .putExtra(PainelActivity.EXTRA_IP, tv.ip).putExtra(PainelActivity.EXTRA_CHAVE, Pareamento.chave(tv)))

    private fun mostrarTeclado(abrir: Boolean) {
        tecladoAberto = abrir
        campo.visibility = if (abrir) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (abrir) { campo.requestFocus(); imm.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT) }
        else imm.hideSoftInputFromWindow(campo.windowToken, 0)
    }

    private fun mandarTexto() {
        val t = campo.text.toString()
        if (t.isEmpty()) return
        campo.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ApiTv.emSegundoPlano {
            val r = ApiTv.texto(tv, t)
            ui.post { tratar(r.codigo); if (r.codigo in 200..299) { campo.setText(""); ApiTv.emSegundoPlano { ApiTv.tecla(tv, "entrar") } } }
        }
    }

    /** A cada 2 s, com a tela aberta: a TV está pedindo texto? Abre o teclado uma vez por pedido (e vibra). */
    private val perguntar = object : Runnable {
        private var pediaAntes = false
        override fun run() {
            ApiTv.emSegundoPlano {
                val r = ApiTv.estado(tv)
                ui.post {
                    if (isFinishing) return@post
                    if (r.codigo == 200 && r.json?.optBoolean("logado") == false) { entrarPrimeiro(); return@post }
                    val pede = r.json?.optBoolean("pedeTexto") == true
                    if (pede && !pediaAntes) { window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); mostrarTeclado(true) }
                    if (!pede && pediaAntes && campo.text.isEmpty()) mostrarTeclado(false)
                    pediaAntes = pede
                    ui.postDelayed(this, PERGUNTAR_MS)
                }
            }
        }
    }

    /** Botões de volume do celular = volume da TV (como no painel). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val sobe = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (sobe || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.action == KeyEvent.ACTION_DOWN) tecla(Pareamento.teclaDoVolume(sobe))
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}

package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import br.paulo.tvlauncher.settings.BackgroundStyle
import br.paulo.tvlauncher.settings.CardStyle
import br.paulo.tvlauncher.settings.ClockStyle
import br.paulo.tvlauncher.settings.FontChoice

/**
 * Ajuste fino com prévia ao vivo (21/11/2026, proposta B do plano para passar o Projectivy).
 *
 * Porquê: no Projectivy cada ajuste visual se vê na hora; na Sofaly era mudar em Configurações e voltar para ver.
 * Para quê: ajustar letra, hora, cor de destaque, cantos, brilho, tamanho da cruz e o fundo VENDO a tela inicial.
 * Como: um painel de vidro à direita (janela translúcida) por cima da HOME. Cada linha troca o valor com OK, ▶ ou ◀;
 * a HOME ouve as preferências e se refaz atrás do painel (MainActivity.ouvinteAjuste). Abre a HOME antes, para que a
 * prévia seja dela e não da tela de Configurações. Movimento: painel entra em 400 ms e sai em 350 ms, curva
 * "smooth-out" (0.22, 1, 0.36, 1), os tokens de painel do transitions.dev.
 * Liga com: as mesmas chaves das linhas de Configurações > Aparência (ConfigFineTuneRows.kt).
 */
class FineTuneActivity : Activity() {

    private lateinit var painel: LinearLayout
    private var fechando = false
    private val curva = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Uma linha: título e o valor atual; [trocar] recebe +1/-1 e devolve o nome do novo valor. */
    private class Ajuste(val titulo: Int, val valor: () -> String, val trocar: (Int) -> String)

    override fun onCreate(salvo: Bundle?) {
        super.onCreate(salvo)
        aberto = true
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(28), dp(32), dp(28), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEB121528.toInt())   // vidro bem escuro: o texto fica legível sobre qualquer fundo da HOME
                val r = dp(Estilo.RAIO + 8).toFloat()
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)   // canto arredondado só do lado da HOME
                setStroke(dp(1), Estilo.VIDRO_BORDA)
            }
            addView(Estilo.tituloTela(this@FineTuneActivity, getString(R.string.ft_titulo)))
            addView(Estilo.texto(this@FineTuneActivity, Estilo.Papel.LEGENDA, getString(R.string.ft_dica)),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
            addView(ScrollView(this@FineTuneActivity).apply {
                isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFocusable = false
                isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(32))
                addView(lista)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        for (a in ajustes()) lista.addView(linha(a), Estilo.lpLinha(this))

        setContentView(FrameLayout(this).apply {
            addView(painel, FrameLayout.LayoutParams(dp(LARGURA), -1, Gravity.END))
        })
        if (salvo == null) {   // refeito por troca de letra: sem entrar de novo
            painel.translationX = dp(LARGURA).toFloat()
            painel.alpha = 0f
            painel.animate().translationX(0f).alpha(1f).setDuration(ABRIR_MS).setInterpolator(curva).start()
        }
        lista.getChildAt(0)?.requestFocus()
    }

    private fun linha(a: Ajuste): View {
        val valor = Estilo.valor(this, a.valor(), seta = false)
        fun mostrar(t: String) { valor.text = "‹  $t  ›" }
        mostrar(a.valor())
        return Estilo.linhaOpcao(this, getString(a.titulo), null, valor).apply {
            setOnClickListener { mostrar(a.trocar(1)) }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { mostrar(a.trocar(1)); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { mostrar(a.trocar(-1)); true }
                    else -> false
                }
            }
        }
    }

    private fun ajustes(): List<Ajuste> = listOf(
        Ajuste(R.string.fc_titulo, { FontChoice.current(this).name }) { passo -> trocarFonte(passo) },
        Ajuste(R.string.ck_formato, { nomeFormato(ClockStyle.format(this)) }) { passo ->
            nomeFormato(ClockStyle.Format.entries[Math.floorMod(ClockStyle.format(this).ordinal + passo, ClockStyle.Format.entries.size)]
                .also { br.paulo.tvlauncher.settings.Extras.definirNumero(this, ClockStyle.KEY_FORMAT, it.ordinal) })
        },
        Ajuste(R.string.ck_cor, { nomeCorHora(ClockStyle.color(this)) }) { passo ->
            nomeCorHora(ClockStyle.Color.entries[Math.floorMod(ClockStyle.color(this).ordinal + passo, ClockStyle.Color.entries.size)]
                .also { br.paulo.tvlauncher.settings.Extras.definirNumero(this, ClockStyle.KEY_COLOR, it.ordinal) })
        },
        Ajuste(R.string.cs_destaque, { nomeDestaque(CardStyle.accent(this)) }) { passo ->
            nomeDestaque(CardStyle.next(this, CardStyle.KEY_ACCENT, CardStyle.accent(this), passo))
        },
        Ajuste(R.string.cs_cantos, { nomeCantos(CardStyle.corners(this)) }) { passo ->
            nomeCantos(CardStyle.next(this, CardStyle.KEY_CORNERS, CardStyle.corners(this), passo))
        },
        Ajuste(R.string.cs_brilho, { nomeBrilho(CardStyle.glow(this)) }) { passo ->
            nomeBrilho(CardStyle.next(this, CardStyle.KEY_GLOW, CardStyle.glow(this), passo))
        },
        Ajuste(R.string.cs_tamanho, { nomeTamanho(CardStyle.size(this)) }) { passo ->
            nomeTamanho(CardStyle.next(this, CardStyle.KEY_SIZE, CardStyle.size(this), passo))
        },
        // Fundo (BackgroundStyle, item 2): cor por cima / sólida, cor, desfoque, transição e "novo fundo agora".
        Ajuste(R.string.bg_tintura, { nomeTintura(BackgroundStyle.tint(this)) }) { passo ->
            nomeTintura(BackgroundStyle.Tint.entries[BackgroundStyle.next(this, BackgroundStyle.KEY_TINT, BackgroundStyle.tint(this), BackgroundStyle.Tint.entries.size, passo)])
        },
        Ajuste(R.string.bg_cor, { nomeCorFundo(BackgroundStyle.tintColor(this)) }) { passo ->
            nomeCorFundo(BackgroundStyle.TintColor.entries[BackgroundStyle.next(this, BackgroundStyle.KEY_TINT_COLOR, BackgroundStyle.tintColor(this), BackgroundStyle.TintColor.entries.size, passo)])
        },
    ) + (if (BackgroundStyle.blurPossivel) listOf(
        Ajuste(R.string.bg_desfoque, { nomeDesfoque(BackgroundStyle.blur(this)) }) { passo ->
            nomeDesfoque(BackgroundStyle.Blur.entries[BackgroundStyle.next(this, BackgroundStyle.KEY_BLUR, BackgroundStyle.blur(this), BackgroundStyle.Blur.entries.size, passo)])
        }) else emptyList()) + listOf(
        Ajuste(R.string.bg_transicao, { nomeTransicao(BackgroundStyle.fade(this)) }) { passo ->
            nomeTransicao(BackgroundStyle.Fade.entries[BackgroundStyle.next(this, BackgroundStyle.KEY_FADE, BackgroundStyle.fade(this), BackgroundStyle.Fade.entries.size, passo)])
        },
        Ajuste(R.string.bg_novo, { "OK" }) { _ ->
            getString(if (BackgroundStyle.proximoFundo?.invoke() == true) R.string.bg_trocado else R.string.bg_nao_troca)
        },
    ) + (if (br.paulo.tvlauncher.network.CasaHA.configurado(this)) listOf(
        // Câmera da casa (Home Assistant) no fundo, e a da campainha quando ela toca (CameraBackground).
        Ajuste(R.string.bg_camera, { nomeCamera }) { _ -> escolherCamera(); nomeCamera },
        Ajuste(R.string.bg_campainha, { getString(if (BackgroundStyle.doorbell(this)) R.string.bg_ligado else R.string.cs_sem) }) { _ ->
            BackgroundStyle.setDoorbell(this, !BackgroundStyle.doorbell(this))
            getString(if (BackgroundStyle.doorbell(this)) R.string.bg_ligado else R.string.cs_sem)
        },
    ) else emptyList())

    /** Nome da câmera escolhida (o id do HA, sem o "camera."), ou "Nenhuma". */
    private val nomeCamera: String get() = BackgroundStyle.camera(this).removePrefix("camera.").ifEmpty { getString(R.string.bg_nenhuma) }

    /** Busca as câmeras no Home Assistant (fora da tela) e abre a lista; a escolha refaz a HOME atrás e este painel. */
    private fun escolherCamera() {
        Fundo.rodar("camera-fundo") {
            val cams = runCatching { br.paulo.tvlauncher.network.CasaHA.listar(applicationContext).filter { it.camera } }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val ids = listOf("") + cams.map { it.id }
                val nomes = listOf(getString(R.string.bg_nenhuma)) + cams.map { it.nome }
                Estilo.lista(this, getString(R.string.bg_camera), nomes, marcadas = listOf(ids.indexOf(BackgroundStyle.camera(this)).coerceAtLeast(0))) { i ->
                    BackgroundStyle.setCamera(this, ids[i])
                    recreate()
                }
            }
        }
    }

    private fun nomeTintura(k: BackgroundStyle.Tint) = getString(when (k) {
        BackgroundStyle.Tint.OFF -> R.string.cs_sem
        BackgroundStyle.Tint.SOFT -> R.string.cs_suave
        BackgroundStyle.Tint.STRONG -> R.string.cs_forte
        BackgroundStyle.Tint.SOLID -> R.string.bg_solida
    })
    private fun nomeCorFundo(k: BackgroundStyle.TintColor) = getString(when (k) {
        BackgroundStyle.TintColor.ACCENT -> R.string.bg_destaque
        BackgroundStyle.TintColor.NIGHT -> R.string.bg_noite
        BackgroundStyle.TintColor.FOREST -> R.string.bg_mata
        BackgroundStyle.TintColor.EMBER -> R.string.bg_brasa
        BackgroundStyle.TintColor.GRAPHITE -> R.string.bg_grafite
    })
    private fun nomeDesfoque(k: BackgroundStyle.Blur) = getString(when (k) {
        BackgroundStyle.Blur.OFF -> R.string.cs_sem
        BackgroundStyle.Blur.SOFT -> R.string.cs_suave
        BackgroundStyle.Blur.STRONG -> R.string.cs_forte
    })
    private fun nomeTransicao(k: BackgroundStyle.Fade) = getString(when (k) {
        BackgroundStyle.Fade.CUT -> R.string.bg_corte
        BackgroundStyle.Fade.SOFT -> R.string.cs_suave
        BackgroundStyle.Fade.SLOW -> R.string.bg_lenta
    })

    /** Fonte: a próxima da lista; se precisar baixar, mostra "Baixando…" e só troca quando o arquivo chegou. */
    private fun trocarFonte(passo: Int): String {
        val fontes = FontChoice.FONTS
        val i = Math.floorMod(fontes.indexOfFirst { it.id == FontChoice.currentId(this) } + passo, fontes.size)
        FontChoice.choose(this, fontes[i].id) { ok ->
            if (isDestroyed) return@choose
            if (ok) recreate() else AvisoTv.rapido(this, getString(R.string.ft_titulo), getString(R.string.fc_falhou), 4)
        }
        return if (FontChoice.ready(this, fontes[i])) fontes[i].name else getString(R.string.fc_baixando)
    }

    private fun nomeFormato(f: ClockStyle.Format) = getString(when (f) {
        ClockStyle.Format.AUTO -> R.string.ck_auto
        ClockStyle.Format.H24 -> R.string.ck_24
        ClockStyle.Format.H12 -> R.string.ck_12
        ClockStyle.Format.H12_AMPM -> R.string.ck_12ampm
        ClockStyle.Format.SECONDS -> R.string.ck_seg
    })
    private fun nomeCorHora(k: ClockStyle.Color) = getString(when (k) {
        ClockStyle.Color.DEFAULT -> R.string.ck_padrao
        ClockStyle.Color.ORANGE -> R.string.ck_laranja
        ClockStyle.Color.CREAM -> R.string.ck_creme
        ClockStyle.Color.AMBER -> R.string.ck_ambar
        ClockStyle.Color.TEAL -> R.string.ck_verde
    })

    override fun onDestroy() {
        if (!isChangingConfigurations) aberto = false
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { fechar(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun fechar() {
        if (fechando) return
        fechando = true
        painel.animate().translationX(painel.width.toFloat()).alpha(0f).setDuration(FECHAR_MS).setInterpolator(curva)
            .withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }

    companion object {
        /** O painel está na tela: a HOME atrás mostra o ponto e o brilho do escolhido mesmo sem o foco da janela. */
        @Volatile var aberto = false
        const val LARGURA = 400       // dp: a cruz (coluna em 225 dp) fica toda à vista à esquerda
        const val ABRIR_MS = 400L     // transitions.dev: panel open = --duration-slow
        const val FECHAR_MS = 350L    // panel close = --duration-medium

        /** Abre a HOME e o painel por cima dela (a prévia é da tela inicial, não da tela de onde veio). */
        fun abrir(a: Activity) {
            val home = android.content.Intent(a, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            a.startActivities(arrayOf(home, android.content.Intent(a, FineTuneActivity::class.java)))
        }

        fun Activity.nomeDestaque(k: CardStyle.Accent) = getString(when (k) {
            CardStyle.Accent.ORANGE -> R.string.ck_laranja
            CardStyle.Accent.AMBER -> R.string.ck_ambar
            CardStyle.Accent.TEAL -> R.string.ck_verde
            CardStyle.Accent.SKY -> R.string.cs_ceu
            CardStyle.Accent.LILAC -> R.string.cs_lilas
        })
        fun Activity.nomeCantos(k: CardStyle.Corners) = getString(when (k) {
            CardStyle.Corners.SQUARE -> R.string.cs_retos
            CardStyle.Corners.DEFAULT -> R.string.ck_padrao
            CardStyle.Corners.ROUND -> R.string.cs_redondos
        })
        fun Activity.nomeBrilho(k: CardStyle.Glow) = getString(when (k) {
            CardStyle.Glow.OFF -> R.string.cs_sem
            CardStyle.Glow.SOFT -> R.string.cs_suave
            CardStyle.Glow.STRONG -> R.string.cs_forte
        })
        fun Activity.nomeTamanho(k: CardStyle.Size) = getString(when (k) {
            CardStyle.Size.SMALL -> R.string.cs_menor
            CardStyle.Size.DEFAULT -> R.string.ck_padrao
            CardStyle.Size.LARGE -> R.string.cs_maior
        })
    }
}

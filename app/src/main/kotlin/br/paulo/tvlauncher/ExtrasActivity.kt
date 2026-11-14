package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tela das chaves das ideias de 10/11/2026 ([Extras]). Fica separada da tela de Configurações de propósito: aquela está
 * em vários PRs abertos e mexer nela agora daria conflito. Uma linha por chave: OK liga ou desliga.
 *
 * Linhas de número (início e fim do filtro noturno, força): OK avança, ◀ ▶ ajustam para trás e para a frente.
 * Abre pelo adb (`--es o extras`, ver [ComandosExtras]) e, depois da costura final, por uma entrada nas Configurações.
 */
class ExtrasActivity : Activity() {
    private lateinit var coluna: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Molde de tela do sistema de design: fundo e margem da HOME, título padrão.
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.extras_titulo))
        coluna = col
        setContentView(raiz)
        montar()
    }

    override fun onResume() { super.onResume(); atualizarTextos() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val linhas = mutableListOf<Pair<TextView, () -> String>>()

    private fun montar() {
        for (c in Extras.Chave.values()) {
            linha({ "${rotulo(c)}: ${if (Extras.ligado(this, c)) getString(R.string.extras_ligado) else getString(R.string.extras_desligado)}" }) {
                // As mesmas chaves que o painel protege pedem o PIN aqui também (antes só a hora de dormir pedia, e quem
                // pegasse o controle desligava o PIN da HOME ou as travas). Hora de dormir: só desligar pede.
                if (Extras.precisaPinNaTv(c, Extras.ligado(this, c), PainelArmazem.temPin(this))) {
                    val titulo = if (c == Extras.Chave.HORA_DORMIR) R.string.dormir_pin else R.string.player_pin_titulo
                    PinTv.pedir(this, getString(titulo)) { certo -> if (certo) { alternar(c); atualizarTextos() } }
                } else alternar(c)
            }
        }
        numero(R.string.extras_filtro_inicio, FiltroNoturno.CHAVE_INICIO, FiltroNoturno.PADRAO_INICIO, 30, 0, 24 * 60 - 30, hora = true)
        numero(R.string.extras_filtro_fim, FiltroNoturno.CHAVE_FIM, FiltroNoturno.PADRAO_FIM, 30, 0, 24 * 60 - 30, hora = true)
        numero(R.string.extras_filtro_forca, FiltroNoturno.CHAVE_FORCA, FiltroNoturno.PADRAO_FORCA, 5, 5, 100, hora = false)
        // Hora de dormir por dia: a linha "dia" escolhe o que as duas de baixo editam (todos = o padrão; um dia = só ele).
        linhaDormir({ getString(R.string.dormir_dia) + ": " + textoDia() }, 1, protegida = false) { delta ->
            diaSel = Math.floorMod(diaSel + 1 + delta, 8) - 1   // -1 (todos), 0 (dom) .. 6 (sáb)
        }
        linhaDormir({ "${getString(R.string.dormir_inicio)}: ${hhmm(janelaSel().inicio)}" }, 30) { d -> mudarJanela(d, 0) }
        linhaDormir({ "${getString(R.string.dormir_fim)}: ${hhmm(janelaSel().fim)}" }, 30) { d -> mudarJanela(0, d) }
        linhaDormir({ getString(R.string.dormir_voltar_padrao) }, 0) { _ ->
            if (diaSel >= 0) HoraDormirTv.definirDias(this, HoraDormirTv.dias(this).toMutableList().also { it[diaSel] = null })
        }
        linhaDormir({ "${getString(R.string.dormir_teto)}: ${Extras.numero(this, HoraDormir.CHAVE_TETO, HoraDormir.PADRAO_TETO)}%" }, 5) { d ->
            val v = Math.floorMod(Extras.numero(this, HoraDormir.CHAVE_TETO, HoraDormir.PADRAO_TETO) + d, 105)   // 0..100, dá a volta
            Extras.definirNumero(this, HoraDormir.CHAVE_TETO, v)
        }
        linha({ getString(R.string.extras_caches) + " (" + getString(R.string.extras_livre, Caches.livreTexto()) + ")" }) {
            emSegundoPlano { Caches.limpar(it) }
        }
        linha({ getString(R.string.extras_consumo) }) { emSegundoPlano(dialogo = R.string.extras_consumo) { ConsumoRedeTv.texto(it) } }
        linha({ getString(R.string.extras_tocando) }) { emSegundoPlano(dialogo = R.string.extras_tocando) { TocandoAgoraTv.texto(it) } }
        linha({ getString(R.string.tec_titulo) }) { startActivity(Intent(this, TeclasActivity::class.java)) }   // ideia 6
        linha({ getString(R.string.extras_teclas) }) { startActivity(Intent(this, TesteTeclasActivity::class.java)) }
        linha({ getString(R.string.extras_calibracao) }) { startActivity(Intent(this, CalibracaoActivity::class.java)) }
        linha({ getString(R.string.casa_titulo) }) { startActivity(Intent(this, CasaActivity::class.java)) }
        linha({ getString(R.string.extras_acordar_pc) + AcordarPc.macGuardado(this).let { if (it.isEmpty()) "" else " ($it)" } }) {
            acordarPc()
        }
        linha({ getString(R.string.extras_diario_ver) + DiarioDns.erro(this).let { if (it.isEmpty()) "" else " — $it" } }) {
            verDiario()
        }
        linha({ getString(R.string.extras_diario_apagar) }) {
            Fundo.rodar { DiarioDns.apagarTudo(applicationContext) }
            aviso(getString(R.string.extras_diario_apagado))
        }
        linha({ if (ExtrasDO.atualizacaoPendente(this) != null) getString(R.string.extras_ota_pendente) else getString(R.string.extras_ota_nenhuma) }) {}
        linhas.firstOrNull()?.first?.requestFocus()
    }

    /** O rótulo de cada chave vem de "extras_<NOME>" nas strings (pt e en). */
    private fun rotulo(c: Extras.Chave): String {
        val id = resources.getIdentifier("extras_${c.name}", "string", packageName)
        return if (id != 0) getString(id) else c.name
    }

    private fun linha(texto: () -> String, ao: () -> Unit): TextView {
        // A linha padrão do sistema de design (a mesma das Configurações).
        val t = Estilo.linhaTexto(this).apply { setOnClickListener { ao(); atualizarTextos() } }
        coluna.addView(t, Estilo.lpLinha(this))
        linhas += t to texto
        return t
    }

    private fun numero(rotulo: Int, chave: String, padrao: Int, passo: Int, min: Int, max: Int, hora: Boolean) {
        fun valor() = Extras.numero(this, chave, padrao)
        fun mudar(delta: Int) {
            var v = valor() + delta
            if (v > max) v = min; if (v < min) v = max   // dá a volta
            Extras.definirNumero(this, chave, v)
            FiltroNoturno.avaliar()
            atualizarTextos()
        }
        val t = linha({ "${getString(rotulo)}: ${if (hora) hhmm(valor()) else "${valor()}%"}" }) { mudar(passo) }
        t.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudar(-passo); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudar(passo); true }
                else -> false
            }
        }
    }

    /** Dia que as linhas de horário editam: -1 = todos (o padrão), 0 = domingo ... 6 = sábado. */
    private var diaSel = -1

    private fun padraoDormir() = HoraDormir.Janela(HoraDormirTv.inicio(this), HoraDormirTv.fim(this))
    private fun janelaSel(): HoraDormir.Janela =
        if (diaSel < 0) padraoDormir() else HoraDormir.doDia(diaSel, HoraDormirTv.dias(this), padraoDormir())

    private fun textoDia(): String {
        if (diaSel < 0) return getString(R.string.dormir_todos)
        val proprio = HoraDormirTv.dias(this)[diaSel] != null
        return HoraDormir.NOMES_DIAS[diaSel] + " (" + getString(if (proprio) R.string.dormir_proprio else R.string.dormir_segue) + ")"
    }

    /** Anda o início/fim do dia escolhido (ou do padrão) em [di]/[df] minutos, dando a volta no relógio. */
    private fun mudarJanela(di: Int, df: Int) {
        val j = janelaSel()
        val nova = HoraDormir.Janela(Math.floorMod(j.inicio + di, 24 * 60), Math.floorMod(j.fim + df, 24 * 60))
        if (diaSel < 0) {
            Extras.definirNumero(this, HoraDormir.CHAVE_INICIO, nova.inicio); Extras.definirNumero(this, HoraDormir.CHAVE_FIM, nova.fim)
        } else HoraDormirTv.definirDias(this, HoraDormirTv.dias(this).toMutableList().also { it[diaSel] = nova })
    }

    /**
     * Linha da hora de dormir: OK = [passo] para a frente; ← → = para trás/para a frente. Com a hora de dormir ligada,
     * mudar pede o PIN (senão a criança adianta o fim); escolher o dia ([protegida] = false) não muda nada e não pede.
     */
    private fun linhaDormir(texto: () -> String, passo: Int, protegida: Boolean = true, aplicar: (Int) -> Unit) {
        fun mudar(delta: Int) {
            val fazer = { aplicar(delta); atualizarTextos() }
            if (protegida && Extras.ligado(this, Extras.Chave.HORA_DORMIR) && PainelArmazem.temPin(this))
                PinTv.pedir(this, getString(R.string.dormir_pin)) { certo -> if (certo) fazer() }
            else fazer()
        }
        val t = linha(texto) { mudar(passo) }
        t.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { mudar(-passo); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mudar(passo); true }
                else -> false
            }
        }
    }

    /** Roda [tarefa] fora da thread da tela e mostra o resultado (aviso curto, ou caixa com [dialogo] de título). */
    private fun emSegundoPlano(dialogo: Int? = null, tarefa: (android.content.Context) -> String) {
        val app = applicationContext
        aviso(getString(R.string.extras_aguarde))
        Fundo.rodar {
            val r = tarefa(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (dialogo == null) aviso(r)
                else CaixaTv(this).setTitle(dialogo).setMessage(r).setPositiveButton(android.R.string.ok, null).show()
                atualizarTextos()
            }
        }
    }

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun alternar(c: Extras.Chave) {
        Extras.definir(this, c, !Extras.ligado(this, c))
        when (c) {
            Extras.Chave.FILTRO_NOTURNO -> FiltroNoturno.avaliar()
            Extras.Chave.JANELA_OTA, Extras.Chave.TRAVAR_CRITICOS, Extras.Chave.DIARIO_DNS -> {
                val app = applicationContext
                Fundo.rodar { ExtrasDO.aplicar(app) }   // chamadas do DO fora da thread da tela
            }
            else -> PainelExtras.aoMudar(applicationContext, c)
        }
    }

    private fun acordarPc() {
        if (AcordarPc.macGuardado(this).isEmpty()) {
            aviso(getString(R.string.extras_acordar_sem_mac)); return
        }
        val app = applicationContext
        Fundo.rodar {
            val ok = AcordarPc.acordar(app)
            runOnUiThread { aviso(getString(if (ok) R.string.extras_acordar_ok else R.string.extras_acordar_falhou)) }
        }
    }

    private fun verDiario() {
        val app = applicationContext
        Fundo.rodar {
            val texto = DiarioDns.resumo(app)
            runOnUiThread {
                // A tela pode ter fechado enquanto o diário carregava: mostrar a caixa numa tela destruída derruba o app.
                if (isFinishing || isDestroyed) return@runOnUiThread
                CaixaTv(this).setTitle(R.string.extras_diario_ver).setMessage(texto)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    /** Aviso padrão (a pílula do sistema de design) em vez do Toast do Android. */
    private fun aviso(texto: String) = AvisoTv.rapido(this, getString(R.string.extras_titulo), texto)

    private fun atualizarTextos() { for ((t, f) in linhas) t.text = f() }
}

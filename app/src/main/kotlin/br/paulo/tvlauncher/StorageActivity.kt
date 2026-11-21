package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.backup.StorageBackup
import br.paulo.tvlauncher.backup.StorageMode
import br.paulo.tvlauncher.backup.StorageModeRules
import br.paulo.tvlauncher.family.PinTv
import br.paulo.tvlauncher.home.AppStorage
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.player.ContaMidia

/**
 * "Pen drive e armazenamento" (pedido do Paulo, 20/11/2026, proposta C). Abre pela linha das Configurações > Sistema.
 *
 * Porquê: TVs com 4-8 GB enchem. Para quê: escolher, sem jargão, se o pen drive é comum, misto ou memória da TV, e
 * levar para ele os apps pesados e a área compartilhada. Como: StorageMode (shell próprio: `sm`, `pm`), regras puras
 * em StorageModeRules. Liga com: menu do app (AppStorage, segurar OK), "Gravar onde?" dos players (StorageMode.gravaveis)
 * e a unidade de rede (só a parte comum aparece no Windows como drive separado).
 *
 * Referências do desenho: o assistente "Formatar como armazenamento do dispositivo" do Android TV (recomendação, aviso
 * de pen drive lento, mover dados depois) e o molde Estilo.telaLista das telas do Sofaly (BackupNoturnoActivity).
 * Formatar APAGA o pen drive: caixa com o foco no Cancelar + PIN do painel (quando há PIN).
 */
class StorageActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private var estado: StorageMode.Estado? = null
    private var velocidade = mutableMapOf<String, Double>()
    private val medindo = mutableSetOf<String>()
    private val autoMedidos = mutableSetOf<String>()
    private var misto = 50
    private var ocupado = false
    private var pinOk = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.st_titulo), getString(R.string.st_explica))
        coluna = col
        setContentView(raiz)
        recarregar()
    }

    private fun tam(b: Long) = Formatter.formatShortFileSize(this, b)

    private fun recarregar() {
        val app = applicationContext
        Fundo.rodar("Storage") {
            val e = runCatching { StorageMode.ler(app) }.getOrNull()
            runOnUiThread { if (!isDestroyed) { estado = e; montar() } }
        }
    }

    private fun linha(texto: String, ao: (() -> Unit)?): TextView {
        val t = Estilo.linhaTexto(this, texto).apply {
            if (ao != null) setOnClickListener { if (!ocupado) ao() } else isFocusable = false
        }
        coluna.addView(t, Estilo.lpLinha(this))
        return t
    }

    private fun secao(texto: String) = coluna.addView(Estilo.secao(this, texto))

    private fun nomeModo(m: StorageModeRules.Modo) = getString(when (m) {
        StorageModeRules.Modo.COMUM -> R.string.st_modo_comum
        StorageModeRules.Modo.MISTO -> R.string.st_modo_misto
        StorageModeRules.Modo.MEMORIA_TV -> R.string.st_modo_tv
        StorageModeRules.Modo.DESCONHECIDO -> R.string.st_modo_desconhecido
    })

    private fun nomeMotivo(m: StorageModeRules.Motivo) = getString(when (m) {
        StorageModeRules.Motivo.PEN_LENTO -> R.string.st_motivo_lento
        StorageModeRules.Motivo.PEN_PEQUENO -> R.string.st_motivo_pequeno
        StorageModeRules.Motivo.TV_FOLGADA -> R.string.st_motivo_folgada
        StorageModeRules.Motivo.PEN_GRANDE -> R.string.st_motivo_grande
        StorageModeRules.Motivo.TV_APERTADA -> R.string.st_motivo_apertada
    })

    private fun montar() {
        coluna.removeAllViews()
        val e = estado
        if (e == null) {
            linha(getString(R.string.st_sem_shell)) { recarregar() }.requestFocus()
            return
        }
        linha(getString(R.string.st_tv_livre, tam(e.livreTv), tam(e.totalTv)), null)
        if (e.pens.isEmpty()) {
            linha(getString(R.string.st_sem_pen)) { recarregar() }.requestFocus()
            return
        }
        var primeira: TextView? = null
        for (pen in e.pens) {
            secao(getString(R.string.st_pen, tam(pen.tamanho), nomeModo(pen.modo)))
            pen.velocidade?.let { velocidade.putIfAbsent(pen.disco, it) }
            // Detecta sozinho: sem medida guardada, mede uma vez ao abrir (32 MB, alguns segundos) e recomenda por ela.
            if (velocidade[pen.disco] == null && pen.comum != null && autoMedidos.add(pen.disco)) {
                medir(pen.disco, pen.comum)   // o aviso de que mede e para quê vai na própria linha (abaixo)
            }
            val rec = StorageModeRules.recomendar(e.livreTv, pen.tamanho, velocidade[pen.disco])
            val textoRec = getString(R.string.st_recomendado, nomeModo(rec.modo), nomeMotivo(rec.motivo)) +
                (velocidade[pen.disco]?.let { "  ·  " + getString(R.string.st_medido, "%.1f".format(it)) }
                    // Medindo agora: a linha diz isso e para quê (antes dizia "OK mede" durante a medição, e um aviso
                    // solto cobria o topo da tela; teste de 21/11/2026).
                    ?: (if (pen.disco in medindo) "  ·  " + getString(R.string.st_medindo) else null)
                    ?: pen.comum?.let { "  ·  " + getString(R.string.st_medir_dica) } ?: "")
            // Medir só faz sentido com a parte comum montada (antes de formatar).
            val l = linha(textoRec, pen.comum?.let { c -> { medir(pen.disco, c) } })
            if (primeira == null) primeira = l
            if (pen.modo != StorageModeRules.Modo.COMUM)
                linha(getString(if (pen.adotadoUuid != null) R.string.st_voltar_comum else R.string.st_usar_comum)) { confirmarFormatar(pen, StorageModeRules.Modo.COMUM) }
            val lm = linha(getString(R.string.st_usar_misto, misto)) { confirmarFormatar(pen, StorageModeRules.Modo.MISTO) }
            lm.setOnKeyListener { v, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val passo = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -25; KeyEvent.KEYCODE_DPAD_RIGHT -> 25; else -> return@setOnKeyListener false }
                misto = (misto + passo).coerceIn(25, 75)
                (v as TextView).text = getString(R.string.st_usar_misto, misto)
                true
            }
            if (pen.modo != StorageModeRules.Modo.MEMORIA_TV)
                linha(getString(R.string.st_usar_tv)) { confirmarFormatar(pen, StorageModeRules.Modo.MEMORIA_TV) }
            pen.adotadoUuid?.let { uuid ->
                secao(getString(R.string.st_apps))
                linha(getString(R.string.st_apps_det)) { escolherApps() }
                val onde = getString(if (e.primarioNoPen) R.string.st_no_pen else R.string.st_na_tv)
                linha(getString(R.string.st_compartilhado, onde)) { confirmarCompartilhado(if (e.primarioNoPen) null else uuid) }
            }
        }
        primeira?.requestFocus()
    }

    private fun mostrar(t: String) = AvisoTv.rapido(this, getString(R.string.st_titulo), t)

    private fun trabalhar(aviso: String, tarefa: () -> String?, aoTerminar: ((String?) -> Unit)? = null) {
        ocupado = true
        mostrar(aviso)
        Fundo.rodar("Storage") {
            val motivo = runCatching { tarefa() }.getOrElse { it.javaClass.simpleName }
            runOnUiThread {
                ocupado = false
                if (isDestroyed) return@runOnUiThread
                val legivel = when (motivo) {
                    "shell" -> getString(R.string.st_sem_shell)
                    "sem_adotado" -> getString(R.string.home_instalar_lugar_sem_pendrive)
                    else -> motivo
                }
                mostrar(if (motivo == null) getString(R.string.st_pronto) else getString(R.string.st_falhou, legivel))
                aoTerminar?.invoke(motivo)
                recarregar()
            }
        }
    }

    private fun medir(disco: String, comum: String, depois: (() -> Unit)? = null) {
        val app = applicationContext
        ocupado = true
        medindo += disco
        Fundo.rodar("Storage") {
            val v = runCatching { StorageMode.medir(app, comum) }.getOrNull()
            runOnUiThread {
                ocupado = false
                medindo -= disco
                if (isDestroyed) return@runOnUiThread
                if (v != null) velocidade[disco] = v else mostrar(getString(R.string.st_falhou, "dd"))
                montar()
                if (v != null) depois?.invoke()
            }
        }
    }

    private fun comPin(acao: () -> Unit) {
        if (pinOk || !PainelArmazem.temPin(this)) { acao(); return }
        PinTv.pedir(this, getString(R.string.st_pin)) { certo ->
            if (certo) { pinOk = true; acao() } else mostrar(getString(R.string.trava_home_errado))
        }
    }

    /**
     * Formatar, em 3 passos: (1) com arquivos na parte comum, oferece copiar antes (pasta do Na rede ou pelo PC);
     * (2) caixa "Apagar o pen drive?" com o foco no Cancelar + PIN; (3) se o pen drive já era memória da TV, traz os
     * apps e a área compartilhada de volta ANTES (senão eles sumiriam junto) e só então formata.
     */
    private fun confirmarFormatar(pen: StorageMode.Pen, modo: StorageModeRules.Modo) {
        // Passo 0 (Sala, 20/11/2026): pen drive lento não vira memória da TV. Sem medida ainda, mede e volta aqui.
        if (modo != StorageModeRules.Modo.COMUM && pen.comum != null) {
            val v = velocidade[pen.disco]
            if (v == null) { mostrar(getString(R.string.st_medindo)); medir(pen.disco, pen.comum) { confirmarFormatar(pen, modo) }; return }
            if (!StorageModeRules.podeFormatar(modo, v, medivel = true)) {
                CaixaTv(this).setTitle(getString(R.string.st_lento_titulo))
                    .setMessage(getString(R.string.st_lento_bloqueado, "%.1f".format(v), StorageModeRules.ADOTAR_MIN_MBS.toInt()))
                    .setPositiveButton("OK", null).show()
                return
            }
        }
        val comum = pen.comum?.let { java.io.File(it) }
        if (comum == null) { avisoApagar(pen, modo); return }
        val app = applicationContext
        ocupado = true
        Fundo.rodar("Storage") {
            val (bytes, n) = runCatching { StorageBackup.medir(comum) }.getOrDefault(0L to 0)
            val contas = runCatching { StorageBackup.destinos(app) }.getOrDefault(emptyList())
            runOnUiThread {
                ocupado = false
                if (isDestroyed) return@runOnUiThread
                if (n == 0) { avisoApagar(pen, modo); return@runOnUiThread }
                val itens = contas.map { getString(R.string.st_copiar_para, it.nome) } +
                    getString(R.string.st_copiar_pc) + getString(R.string.st_nao_copiar)
                Estilo.lista(this, getString(R.string.st_copiar_titulo, tam(bytes)), itens, separarUltimo = true) { i ->
                    when {
                        i < contas.size -> copiarEDepois(comum, contas[i], pen, modo)
                        i == contas.size -> CaixaTv(this).setTitle(getString(R.string.st_copiar_pc))
                            .setMessage(getString(R.string.st_copiar_pc_texto)).setPositiveButton("OK", null).show()
                        else -> avisoApagar(pen, modo)
                    }
                }
            }
        }
    }

    private fun copiarEDepois(raiz: java.io.File, conta: ContaMidia, pen: StorageMode.Pen, modo: StorageModeRules.Modo) {
        val app = applicationContext
        var ultimo = 0L
        trabalhar(getString(R.string.st_copiando_inicio), {
            StorageBackup.copiar(app, raiz, conta) { feito, total ->
                val agora = System.currentTimeMillis()
                if (agora - ultimo > 10_000) {   // um aviso a cada 10 s, não a cada bloco
                    ultimo = agora
                    runOnUiThread { if (!isDestroyed) mostrar(getString(R.string.st_copiando, tam(feito), tam(total))) }
                }
            }
        }) { motivo -> if (motivo == null) avisoApagar(pen, modo) }
    }

    private fun avisoApagar(pen: StorageMode.Pen, modo: StorageModeRules.Modo) {
        val pct = misto
        val primarioNoPen = estado?.primarioNoPen == true && pen.adotadoUuid != null
        val d = CaixaTv(this)
            .setTitle(getString(R.string.st_aviso_titulo))
            .setMessage(getString(R.string.st_aviso, nomeModo(modo)) +
                (if (modo != StorageModeRules.Modo.COMUM) "\n\n" + getString(R.string.st_aviso_boot) else ""))
            .setNegativeButton(R.string.home_cancelar, null)
            .setPositiveButton(R.string.st_apagar) { _, _ ->
                comPin {
                    val app = applicationContext
                    trabalhar(getString(R.string.st_formatando), {
                        val volta = if (pen.adotadoUuid != null) StorageBackup.trazerTudoDeVolta(app, primarioNoPen) { nome ->
                            runOnUiThread { if (!isDestroyed) mostrar(getString(R.string.st_trazendo, nome)) }
                        } else null
                        volta ?: StorageMode.formatar(app, pen.disco, modo, pct)
                    }) { motivo ->
                        // Virou memória da TV ou misto: o próximo passo natural é escolher os apps.
                        if (motivo == null && modo != StorageModeRules.Modo.COMUM) mostrar(getString(R.string.st_depois))
                    }
                }
            }
            .show()
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun confirmarCompartilhado(uuid: String?) {
        val d = CaixaTv(this)
            .setTitle(getString(R.string.st_compartilhado, getString(if (uuid == null) R.string.st_na_tv else R.string.st_no_pen)))
            .setMessage(getString(R.string.st_compartilhado_aviso))
            .setNegativeButton(R.string.home_cancelar, null)
            .setPositiveButton(R.string.st_mover) { _, _ ->
                comPin {
                    val app = applicationContext
                    trabalhar(getString(R.string.st_movendo), { StorageMode.moverCompartilhado(app, uuid) })
                }
            }
            .show()
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    /** Lista dos apps que podem mudar de lugar, do maior para o menor; OK move (ou traz de volta) o escolhido. */
    private fun escolherApps() {
        val app = applicationContext
        ocupado = true
        Fundo.rodar("Storage") {
            val apps = runCatching { StorageMode.appsMoveis(app) }.getOrDefault(emptyList())
            runOnUiThread {
                ocupado = false
                if (isDestroyed) return@runOnUiThread
                if (apps.isEmpty()) { mostrar(getString(R.string.st_apps_nenhum)); return@runOnUiThread }
                val rotulos = apps.map { a -> "${a.nome}  ·  ${tam(a.bytes)}" }
                val marcadas = apps.indices.filter { apps[it].noExterno }
                Estilo.lista(this, getString(R.string.st_apps), rotulos, marcadas = marcadas,
                    rodape = getString(R.string.st_apps_rodape)) { i ->
                    val a = apps[i]
                    val vai = !a.noExterno
                    val mover = { trabalhar(getString(R.string.home_instalando_lugar, a.nome), { AppStorage.mover(app, a.pacote, vai) }) }
                    if (a.arriscado) avisoGrande(this, a.nome, tam(a.bytes), mover) else mover()
                }
            }
        }
    }

    companion object {
        /** Aviso antes de mover um app grande (AppStorageRules.arriscado); o foco fica no Cancelar. Também no menu da home. */
        fun avisoGrande(a: android.app.Activity, nome: String, tamanho: String, continuar: () -> Unit) {
            val d = CaixaTv(a)
                .setTitle(a.getString(R.string.home_grande_titulo, nome))
                .setMessage(a.getString(R.string.home_grande_aviso, nome, tamanho))
                .setNegativeButton(R.string.home_cancelar, null)
                .setPositiveButton(R.string.st_mover) { _, _ -> continuar() }
                .show()
            d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
    }
}

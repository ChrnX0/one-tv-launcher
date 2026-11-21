package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.alarm.Datas
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.panel.confirmar
import br.paulo.tvlauncher.player.GravacaoDestino
import br.paulo.tvlauncher.player.GravacaoRegras
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.RecordingCloudCopy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela "Gravações" (14/11/2026, esboço 1 aprovado; fica dentro de "TV"): o que está gravando agora, as agendadas e as
 * gravadas no pendrive (ou na pasta da rede de reserva). OK numa gravada assiste (as partes em sequência); segurar OK
 * apaga, com confirmação. Nunca apaga sozinha (decisão do dono).
 *
 * Roda no ":player": lê a gravação em andamento (Gravacoes.atual) do mesmo processo do serviço.
 */
class GravacoesActivity : Activity() {
    companion object { private const val CODIGO_TOCAR = 7 }

    private lateinit var coluna: LinearLayout
    private lateinit var subtitulo: TextView
    private var tocando: List<String> = emptyList()   // as partes da gravação que está tocando
    private var parteTocando = 0
    private var uris: Map<String, String> = emptyMap()
    private val mudou: () -> Unit = { if (!isDestroyed) carregar() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.grv_tela), " ")
        coluna = col
        subtitulo = (raiz as LinearLayout).getChildAt(1) as TextView
        raiz.addView(Estilo.barraDicas(this, getString(R.string.grv_dica_ok) to getString(R.string.grv_dica_assistir),
            getString(R.string.grv_dica_segurar) to getString(R.string.grv_dica_apagar)),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@GravacoesActivity, Estilo.MARGEM_VERTICAL) })
        setContentView(raiz)
        Gravacoes.ouvir(mudou)
    }

    override fun onResume() { super.onResume(); carregar() }

    override fun onDestroy() { Gravacoes.esquecer(mudou); super.onDestroy() }

    private var nomeNuvem: String? = null
    private var copiasNaFila = 0

    private class Gravada(val partes: List<String>, val inicio: Long?, val titulo: String, val bytes: Long, val destino: String, val onde: String)

    /** Lê o destino (SMB = rede) fora da thread da tela e redesenha. */
    private fun carregar() {
        val app = applicationContext
        Fundo.rodar("gravacoes") {
            val ag = runCatching { Gravacoes.agendadas(app) }.getOrDefault(emptyList()).sortedBy { it.inicio }
            var livre = -1L
            var nomeDestino: String? = null
            var gravadas = emptyList<Gravada>()
            val mapa = HashMap<String, String>()
            // O lugar de agora (o da última escolha, senão o automático) vai no subtítulo com o espaço livre.
            runCatching {
                GravacaoDestino.abrir(app, GravacaoDestino.ultimo(app))?.use { d -> nomeDestino = d.nome; livre = d.livre() }
            }
            // 20/11/2026: as gravações podem estar em vários lugares (cada uma onde a pessoa escolheu): lista todos.
            val todas = ArrayList<Gravada>()
            for (o in runCatching { GravacaoDestino.opcoes(app) }.getOrDefault(emptyList())) runCatching {
                GravacaoDestino.abrir(app, o.id)?.takeIf { it.id == o.id }?.use { d ->
                    val itens = d.listar()
                    val tam = itens.associate { it.nome to it.tamanho }
                    GravacaoRegras.agrupar(itens.map { it.nome }).forEach { partes ->
                        partes.forEach { mapa["${d.id}|$it"] = d.uri(it) }
                        val (ini, tit) = GravacaoRegras.lerNome(partes.first())
                        todas += Gravada(partes, ini, tit, partes.sumOf { tam[it] ?: 0L }, d.id, d.nome)
                    }
                }
            }
            gravadas = todas.sortedByDescending { it.inicio ?: 0L }
            val variosLugares = gravadas.map { it.destino }.distinct().size > 1
            if (!variosLugares) gravadas = gravadas.map { Gravada(it.partes, it.inicio, it.titulo, it.bytes, it.destino, "") }
            val nuvem = runCatching { RecordingCloudCopy.nomeDoAlvo(app, RecordingCloudCopy.alvo(app)) }.getOrNull()
            val naFila = runCatching { RecordingCloudCopy.fila(app).size }.getOrDefault(0)
            runOnUiThread { if (!isDestroyed) { uris = mapa; nomeNuvem = nuvem; copiasNaFila = naFila; montar(ag, gravadas, nomeDestino, livre) } }
        }
    }

    private fun montar(agendadas: List<GravacaoRegras.Agendada>, gravadas: List<Gravada>, destino: String?, livre: Long) {
        val focoAntes = coluna.focusedChild?.tag
        coluna.removeAllViews()
        subtitulo.text = if (destino == null) (GravacaoDestino.problemaPendrive(this) ?: getString(R.string.grv_sem_destino))
            // Long.MAX_VALUE = a pasta da rede não disse o espaço (o gravador segue gravando); não mostrar "9223372036 GB".
            else if (livre < 0 || livre == Long.MAX_VALUE) getString(R.string.grv_onde_sem_livre, destino)
            else getString(R.string.grv_onde, destino, gb(livre), GravacaoRegras.horasQueCabem(livre))
        Gravacoes.atual?.let { g ->
            linha("agora", "● " + getString(R.string.grv_agora_titulo, g.canalNome),
                listOfNotNull(g.programa.ifEmpty { null }, getString(R.string.grv_ate_hora, Gravacoes.hora(g.fim)), gb(g.bytes)).joinToString(" · "),
                getString(R.string.grv_assistir), cor = Estilo.ERRO,
                ok = { startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CANAL, g.canalId)) },
                segurar = { confirmar(getString(R.string.grv_parar_pergunta, g.canalNome), getString(R.string.grv_parar)) { Gravacoes.parar(this) } })
        }
        if (agendadas.isNotEmpty()) {
            coluna.addView(Estilo.secao(this, getString(R.string.grv_agendadas)))
            for (a in agendadas) linha("ag:" + a.id, a.programa.ifEmpty { a.canalNome },
                "${dia(a.inicio)} ${Gravacoes.hora(a.inicio)} – ${Gravacoes.hora(a.fim)} · ${a.canalNome}",
                getString(if (a.semanal) R.string.grv_toda_semana else R.string.grv_agendada_selo),
                ok = { confirmar(getString(R.string.grv_cancelar_pergunta, a.programa), getString(R.string.grv_cancelar_agendada)) { cancelar(a) } },
                segurar = { confirmar(getString(R.string.grv_cancelar_pergunta, a.programa), getString(R.string.grv_cancelar_agendada)) { cancelar(a) } })
        }
        coluna.addView(Estilo.secao(this, getString(R.string.grv_gravadas)))
        if (gravadas.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA,
            getString(if (destino == null) R.string.grv_vazio_sem_destino else R.string.grv_vazio)).apply { setPaddingRelative(0, Estilo.dp(this@GravacoesActivity, 8), 0, 0) })
        for (g in gravadas) {
            val partes = if (g.partes.size > 1) " · " + getString(R.string.grv_partes, g.partes.size) else ""
            linha("gr:" + g.destino + "|" + g.partes.first(), g.titulo,
                listOfNotNull(g.inicio?.let { "${dia(it)} ${Gravacoes.hora(it)}" }, gb(g.bytes), g.onde.ifEmpty { null }).joinToString(" · ") + partes,
                null, ok = { tocar(g.partes.map { "${g.destino}|$it" }, 0) },
                segurar = { confirmar(getString(R.string.grv_apagar_pergunta, g.titulo), getString(R.string.grv_apagar)) { apagar(g) } })
        }
        coluna.addView(Estilo.secao(this, getString(R.string.grv_reserva_secao)))
        val reserva = GravacaoDestino.pastaRede(this).takeIf { it.isNotEmpty() }?.let { id -> MidiaArmazem.conta(this, id)?.nome }
        linha("reserva", getString(R.string.grv_reserva), getString(R.string.grv_reserva_explica), reserva ?: getString(R.string.grv_nenhuma),
            ok = { escolherReserva() }, segurar = null)
        // 20/11/2026: cópia para a nuvem ao terminar (Drive, OneDrive, Dropbox, Box).
        val explicaNuvem = getString(R.string.grv_nuvem_explica) +
            if (copiasNaFila > 0) " · " + getString(R.string.grv_na_fila, copiasNaFila) else ""
        linha("nuvem", getString(R.string.grv_nuvem), explicaNuvem, nomeNuvem ?: getString(R.string.grv_nenhuma),
            ok = { escolherNuvem() }, segurar = null)
        if (nomeNuvem != null) {
            val apagar = RecordingCloudCopy.apagarDepois(this)
            linha("nuvem_apagar", getString(R.string.grv_apagar_depois), getString(R.string.grv_apagar_depois_explica),
                getString(if (apagar) R.string.rot_ligada else R.string.rot_desligada),
                ok = { RecordingCloudCopy.definirApagar(this, !apagar); carregar() }, segurar = null)
        }
        val alvo = (0 until coluna.childCount).map { coluna.getChildAt(it) }.firstOrNull { it.tag != null && it.tag == focoAntes }
            ?: (0 until coluna.childCount).map { coluna.getChildAt(it) }.firstOrNull { it.isFocusable }
        alvo?.requestFocus()
    }

    private fun linha(chave: String, titulo: String, explica: String, direita: String?, cor: Int? = null, ok: () -> Unit, segurar: (() -> Unit)?) {
        val dir = direita?.let { Estilo.valor(this, it, seta = false) }
        val v = Estilo.linhaOpcao(this, titulo, explica, dir).apply {
            tag = chave
            setOnClickListener { ok() }
            if (segurar != null) setOnLongClickListener { segurar(); true }
        }
        if (cor != null) ((v.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(cor)
        coluna.addView(v, Estilo.lpLinha(this))
    }

    private fun confirmar(pergunta: String, sim: String, acao: () -> Unit) {
        Estilo.lista(this, pergunta, listOf(sim, getString(android.R.string.cancel))) { i -> if (i == 0) acao() }
    }

    private fun cancelar(a: GravacaoRegras.Agendada) {
        val app = applicationContext
        Fundo.rodar("gravacoes") { runCatching { Gravacoes.cancelar(app, a.id) }; runOnUiThread { if (!isDestroyed) carregar() } }
    }

    private fun apagar(g: Gravada) {
        val app = applicationContext
        Fundo.rodar("gravacoes") {
            runCatching { GravacaoDestino.abrir(app, g.destino)?.takeIf { it.id == g.destino }?.use { d -> g.partes.forEach { d.apagar(it) } } }
            runOnUiThread { if (!isDestroyed) carregar() }
        }
    }

    /** Pasta da rede para quando não há pendrive: as contas do "Na rede" que aceitam gravação (SMB, FTP, SFTP). */
    private fun escolherReserva() {
        val contas = MidiaArmazem.contas(this).filter { Pastas.gravavel(it) }
        if (contas.isEmpty()) { AvisoTv.rapido(this, getString(R.string.grv_reserva), getString(R.string.grv_sem_smb), 8); return }
        val atual = GravacaoDestino.pastaRede(this)
        val itens = listOf(getString(R.string.grv_nenhuma)) + contas.map { it.nome }
        val marcada = contas.indexOfFirst { it.id == atual }.let { if (it < 0) 0 else it + 1 }
        Estilo.lista(this, getString(R.string.grv_reserva), itens, listOf(marcada)) { i ->
            GravacaoDestino.escolherPastaRede(this, if (i == 0) "" else contas[i - 1].id)
            carregar()
        }
    }

    /** A nuvem que recebe a cópia de cada gravação (ou nenhuma). */
    private fun escolherNuvem() {
        val app = applicationContext
        Fundo.rodar("gravacoes") {
            val alvos = runCatching { RecordingCloudCopy.alvos(app) }.getOrDefault(emptyList())
            val atual = RecordingCloudCopy.alvo(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (alvos.isEmpty()) { AvisoTv.rapido(this, getString(R.string.grv_nuvem), getString(R.string.grv_sem_nuvem), 10); return@runOnUiThread }
                val itens = listOf(getString(R.string.grv_nenhuma)) + alvos.map { it.nome }
                val marcada = alvos.indexOfFirst { it.id == atual }.let { if (it < 0) 0 else it + 1 }
                Estilo.lista(this, getString(R.string.grv_nuvem), itens, listOf(marcada)) { i ->
                    RecordingCloudCopy.definir(this, if (i == 0) "" else alvos[i - 1].id)
                    carregar()
                }
            }
        }
    }

    // ---------------------------------------------------------------- assistir (partes em sequência)

    private fun tocar(partes: List<String>, i: Int) {
        val uri = uris[partes.getOrNull(i) ?: return] ?: return
        tocando = partes; parteTocando = i
        val (_, titulo) = GravacaoRegras.lerNome(partes.first().substringAfter('|'))
        VodActivity.abrirUrlComResultado(this, CODIGO_TOCAR, uri,
            titulo + if (partes.size > 1) " (${getString(R.string.grv_parte, i + 1, partes.size)})" else "")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, dados: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(codigo, resultado, dados)
        if (codigo != CODIGO_TOCAR) return
        val terminou = resultado == RESULT_OK && dados?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
        if (terminou && parteTocando + 1 < tocando.size) tocar(tocando, parteTocando + 1)
    }

    // ---------------------------------------------------------------- textos

    // Abaixo de 0,1 GB em MB: uma gravação que falhou no começo aparecia como "0,0 GB" (visto na Sala, 17/11/2026).
    private fun gb(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 100_000_000L -> String.format(Locale.getDefault(), "%d MB", (bytes + 999_999) / 1_000_000)
        else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    }

    private fun dia(ms: Long): String = when (Datas.diasAte(ms, System.currentTimeMillis())) {
        0L -> getString(R.string.tvv_hoje)
        -1L -> getString(R.string.tvv_ontem)
        1L -> getString(R.string.grv_amanha)
        else -> SimpleDateFormat("EEE dd/MM", Locale.getDefault()).format(Date(ms))
    }

}

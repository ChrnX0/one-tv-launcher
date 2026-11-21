package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.panel.DestinoPainel
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelWeb

/**
 * Configurações > Família > Cuidar de longe (ideias 77, 78, 79). Tudo desligado de fábrica. Aqui se escolhe quem recebe,
 * quais avisos, a tecla de ajuda, prazos e a hora de costume; os lembretes se escrevem pelo celular (painel, mais fácil
 * de digitar) e aqui só se veem e apagam. Ligar = a pergunta grande de consentimento (CuidarTelaActivity), que só aceita
 * o controle de verdade. Desligar retira o consentimento na hora.
 */
class CuidarDeLongeActivity : Activity() {
    private lateinit var lista: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.cuidar_titulo), getString(R.string.cuidar_subtitulo))
        lista = coluna
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        montar()   // volta do consentimento ou do QR: o estado pode ter mudado
    }

    private fun linha(titulo: String, detalhe: String?, valor: String? = null, acao: () -> Unit) {
        val l = Estilo.linhaOpcao(this, titulo, detalhe, valor?.let { Estilo.valor(this, it) }).apply { setOnClickListener { acao() } }
        // Letra de leitura (Estilo.Leitura): quem configura pode ser quem vai receber os avisos, mas pode ser quem mora aqui.
        (l.getChildAt(0) as? LinearLayout)?.let { col ->
            (col.getChildAt(0) as? android.widget.TextView)?.textSize = Estilo.Leitura.ITEM + 1
            (col.getChildAt(1) as? android.widget.TextView)?.textSize = Estilo.Leitura.ITEM_DETALHE
        }
        (l.getChildAt(1) as? android.widget.TextView)?.textSize = Estilo.Leitura.ITEM + 1
        lista.addView(l, Estilo.lpLinha(this))
    }

    private fun secao(nome: String) = lista.addView(Estilo.secao(this, nome).apply { setPaddingRelative(Estilo.dp(context, 8), Estilo.dp(context, Estilo.E3), 0, Estilo.dp(context, Estilo.E1)) })

    private fun sim(b: Boolean) = getString(if (b) R.string.cuidar_sim else R.string.cuidar_nao)
    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun montar() {
        val foco = currentFocus?.let { lista.indexOfChild(it) } ?: -1
        lista.removeAllViews()
        val c = CuidarDeLongeTv.config(this)
        val e = CuidarDeLongeTv.estado(this)
        val consentido = CuidarDeLonge.consentido(c)

        // Estado e o botão principal (ligar = perguntar; desligar = retirar)
        val estado = when {
            consentido -> getString(R.string.cuidar_estado_ligado, c.nome, android.text.format.DateFormat.getDateFormat(this).format(java.util.Date(c.consentidoEm)))
            c.consentimento.isNotEmpty() -> getString(R.string.cuidar_estado_mudou)
            else -> getString(R.string.cuidar_estado_desligado)
        }
        if (consentido) linha(getString(R.string.cuidar_desligar), estado) {
            // Confirma: é a 1ª linha (onde o foco cai ao entrar); um OK por hábito não pode desligar o cuidado.
            Estilo.lista(this, getString(R.string.cuidar_desligar_pergunta, c.nome), listOf(getString(R.string.cuidar_desligar), getString(R.string.cuidar_cancelar)),
                listOf(1)) { i -> if (i == 0) { CuidarDeLongeTv.retirar(this, "tv"); montar() } }
        } else linha(getString(R.string.cuidar_ligar), estado) {
            when (CuidarDeLongeTv.pedirConsentimento(this)) {
                CuidarDeLonge.Falta.NOME -> pedirNome()
                CuidarDeLonge.Falta.APARELHO -> escolherAparelhos()
                CuidarDeLonge.Falta.AVISO -> AvisoTv.rapido(this, getString(R.string.cuidar_titulo), getString(R.string.cuidar_falta_aviso))
                null -> {}
            }
        }

        secao(getString(R.string.cuidar_sec_quem))
        linha(getString(R.string.cuidar_nome), getString(R.string.cuidar_nome_det), c.nome.ifEmpty { getString(R.string.cuidar_ninguem) }) { pedirNome() }
        val nomes = CuidarDeLongeTv.aparelhos(this).filter { it.first in c.aparelhos }.joinToString(", ") { it.second }
        linha(getString(R.string.cuidar_aparelhos), getString(R.string.cuidar_aparelhos_det), nomes.ifEmpty { getString(R.string.cuidar_ninguem) }) { escolherAparelhos() }

        secao(getString(R.string.cuidar_sec_avisos))
        fun alternar(a: CuidarDeLonge.Aviso) { CuidarDeLongeTv.mudar(this) { it.copy(avisos = if (a in it.avisos) it.avisos - a else it.avisos + a) }; montar() }
        val tecla = getString(if (c.tecla == CuidarDeLonge.Tecla.OK) R.string.cuidar_tecla_ok else R.string.cuidar_tecla_voltar)
        linha(getString(R.string.cuidar_aviso_ajuda), getString(R.string.cuidar_aviso_ajuda_det, tecla), sim(CuidarDeLonge.Aviso.AJUDA in c.avisos)) { alternar(CuidarDeLonge.Aviso.AJUDA) }
        if (BuildConfig.CUIDAR_FAMILIA) linha(getString(R.string.cuidar_aviso_lembrete), getString(R.string.cuidar_aviso_lembrete_det, c.prazoMin), sim(CuidarDeLonge.Aviso.LEMBRETE in c.avisos)) { alternar(CuidarDeLonge.Aviso.LEMBRETE) }
        if (BuildConfig.CUIDAR_FAMILIA || ExtrasPlugin.autorizado(this)) linha(getString(R.string.cuidar_aviso_nao_ligou), getString(R.string.cuidar_aviso_nao_ligou_det), sim(CuidarDeLonge.Aviso.NAO_LIGOU in c.avisos)) { alternar(CuidarDeLonge.Aviso.NAO_LIGOU) }
        linha(getString(R.string.cuidar_aviso_bem), getString(R.string.cuidar_aviso_bem_det), sim(CuidarDeLonge.Aviso.ESTOU_BEM in c.avisos)) { alternar(CuidarDeLonge.Aviso.ESTOU_BEM) }
        if (c.aparelhos.isNotEmpty()) linha(getString(R.string.cuidar_testar), getString(R.string.cuidar_testar_det)) { testar() }

        secao(getString(R.string.cuidar_sec_ajuda))
        linha(getString(R.string.cuidar_tecla), getString(R.string.cuidar_tecla_det), tecla) {
            val opcoes = CuidarDeLonge.Tecla.values().toList()
            Estilo.lista(this, getString(R.string.cuidar_tecla), opcoes.map { getString(if (it == CuidarDeLonge.Tecla.OK) R.string.cuidar_tecla_ok else R.string.cuidar_tecla_voltar) },
                listOf(opcoes.indexOf(c.tecla))) { i -> CuidarDeLongeTv.mudar(this) { it.copy(tecla = opcoes[i]) }; montar() }
        }

        secao(getString(R.string.cuidar_sec_lembretes))
        if (c.lembretes.isEmpty()) lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.cuidar_sem_lembretes)).apply {
            textSize = Estilo.Leitura.ITEM_DETALHE; setPaddingRelative(Estilo.dp(context, 8), 0, 0, 0) })
        for (l in c.lembretes) linha("${l.hhmm}  ${l.texto}", diasTexto(l.dias)) {
            Estilo.lista(this, "${l.hhmm}  ${l.texto}", listOf(getString(R.string.cuidar_apagar), getString(R.string.cuidar_cancelar))) { i ->
                if (i == 0) { CuidarDeLongeTv.removerLembrete(this, l.id); montar() }
            }
        }
        linha(getString(R.string.cuidar_lembrete_celular), getString(R.string.cuidar_lembrete_celular_det)) { qrPainel() }
        linha(getString(R.string.cuidar_prazo), getString(R.string.cuidar_prazo_det), getString(R.string.cuidar_minutos, c.prazoMin)) {
            Estilo.lista(this, getString(R.string.cuidar_prazo), CuidarDeLonge.PRAZOS.map { getString(R.string.cuidar_minutos, it) },
                listOf(CuidarDeLonge.PRAZOS.indexOf(c.prazoMin))) { i -> CuidarDeLongeTv.mudar(this) { it.copy(prazoMin = CuidarDeLonge.PRAZOS[i]) }; montar() }
        }
        linha(getString(R.string.cuidar_acender), getString(R.string.cuidar_acender_det), sim(c.acender)) {
            CuidarDeLongeTv.mudar(this) { it.copy(acender = !it.acender) }; montar()
        }

        secao(getString(R.string.cuidar_sec_costume))
        val hoje = CuidarDeLonge.dia(System.currentTimeMillis(), java.util.TimeZone.getDefault())
        val costume = CuidarDeLonge.horaCostume(c, e.ligadas, hoje)
        val amostras = e.ligadas.keys.count { it < hoje }
        val valorCostume = when {
            c.horaFixa >= 0 -> hhmm(c.horaFixa)
            costume != null -> getString(R.string.cuidar_aprendida, hhmm(costume))
            else -> getString(R.string.cuidar_aprendendo, amostras, CuidarDeLonge.MIN_AMOSTRAS)
        }
        linha(getString(R.string.cuidar_costume), getString(R.string.cuidar_costume_det), valorCostume) {
            val horas = (5 * 60..14 * 60 step 30).toList()
            val itens = listOf(getString(R.string.cuidar_aprender)) + horas.map { hhmm(it) }
            val marcada = if (c.horaFixa < 0) 0 else horas.indexOf(c.horaFixa).let { if (it < 0) 0 else it + 1 }
            Estilo.lista(this, getString(R.string.cuidar_costume), itens, listOf(marcada)) { i ->
                CuidarDeLongeTv.mudar(this) { it.copy(horaFixa = if (i == 0) -1 else horas[i - 1]) }; montar()
            }
        }
        linha(getString(R.string.cuidar_margem), getString(R.string.cuidar_margem_det), getString(R.string.cuidar_horas, c.margemMin / 60)) {
            Estilo.lista(this, getString(R.string.cuidar_margem), CuidarDeLonge.MARGENS.map { getString(R.string.cuidar_horas, it / 60) },
                listOf(CuidarDeLonge.MARGENS.indexOf(c.margemMin))) { i -> CuidarDeLongeTv.mudar(this) { it.copy(margemMin = CuidarDeLonge.MARGENS[i]) }; montar() }
        }

        val alvo = lista.getChildAt(if (foco >= 0) foco.coerceAtMost(lista.childCount - 1) else 0)
        alvo?.post { if (alvo.isFocusable) alvo.requestFocus() else lista.getChildAt(0)?.requestFocus() }
    }

    private fun diasTexto(dias: Int): String {
        if (dias == CuidarDeLonge.TODO_DIA) return getString(R.string.cuidar_todo_dia)
        val nomes = java.text.DateFormatSymbols.getInstance().shortWeekdays   // [1] = domingo
        return (0..6).filter { dias and (1 shl it) != 0 }.joinToString(", ") { nomes[it + 1] }
    }

    private fun pedirNome() {
        val campo = EditText(this).apply {
            filters = arrayOf(InputFilter.LengthFilter(CuidarDeLonge.MAX_NOME))
            hint = getString(R.string.cuidar_nome_dica); setText(CuidarDeLongeTv.config(context).nome); setSingleLine()
            textSize = Estilo.Leitura.ITEM + 2
        }
        CaixaTv(this).setTitle(getString(R.string.cuidar_nome)).setView(LinearLayout(this).apply {
            setPaddingRelative(48, 16, 48, 0); addView(campo)
        }).setPositiveButton(getString(R.string.cuidar_ok)) { _, _ ->
            CuidarDeLongeTv.mudar(this) { it.copy(nome = CuidarDeLonge.limparNome(campo.text.toString())) }; montar()
        }.setNegativeButton(getString(R.string.cuidar_cancelar), null).show()
        campo.requestFocus()
    }

    private fun escolherAparelhos() {
        val disp = CuidarDeLongeTv.aparelhos(this)
        if (disp.isEmpty()) {
            Estilo.lista(this, getString(R.string.cuidar_nenhum_aparelho), listOf(getString(R.string.cuidar_nenhum_aparelho_det))) { qrPainel() }
            return
        }
        val c = CuidarDeLongeTv.config(this)
        Estilo.lista(this, getString(R.string.cuidar_aparelhos), disp.map { it.second }, disp.indices.filter { disp[it].first in c.aparelhos }) { i ->
            val id = disp[i].first
            CuidarDeLongeTv.mudar(this) { it.copy(aparelhos = if (id in it.aparelhos) it.aparelhos - id else it.aparelhos + id) }
            montar()
        }
    }

    private fun testar() {
        val app = applicationContext
        Fundo.rodar("cuidar-teste") {
            val (aceitos, total) = CuidarDeLongeTv.testar(app)
            AvisoTv.rapido(app, getString(R.string.cuidar_titulo), getString(R.string.cuidar_teste_resultado, aceitos, total))
        }
    }

    private fun qrPainel() {
        if (!PainelArmazem.podeLigar(this) || !PainelWeb.ligado()) {
            AvisoTv.rapido(this, getString(R.string.cuidar_titulo), getString(R.string.cl_ligue_painel)); return
        }
        startActivity(Intent(this, EntrarPeloQrActivity::class.java)
            .putExtra(EntrarPeloQrActivity.EXTRA_DESTINO, DestinoPainel.CUIDAR.name)
            .putExtra(EntrarPeloQrActivity.EXTRA_TITULO, getString(R.string.cuidar_titulo)))
    }
}

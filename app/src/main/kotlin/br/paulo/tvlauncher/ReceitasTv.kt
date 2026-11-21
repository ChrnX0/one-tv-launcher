package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.PinNaTela
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.confirmar
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.ExtrasTv
import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.settings.Receitas
import br.paulo.tvlauncher.settings.TamanhoTexto

/**
 * Receitas prontas, a parte do Android (degrau 4, 17/11/2026): ler e gravar os ajustes que já existem, a foto de antes
 * (prefs "receitas", fora do backup simples como as outras prefs de modo) e o "Desfazer". Regras em [Receitas].
 */
object ReceitasTv {
    private const val PREFS = "receitas"
    private const val FOTO = "foto"
    private const val ATIVA = "ativa"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Os valores de agora de todos os ajustes. */
    fun ler(ctx: Context): Map<Receitas.Ajuste, Int> {
        fun b(x: Boolean) = if (x) 1 else 0
        fun e(c: Extras.Chave) = b(Extras.ligado(ctx, c))
        return Receitas.Ajuste.values().associateWith { a ->
            when (a) {
                Receitas.Ajuste.HOSPEDE -> b(ModoHospede.ativo(ctx))
                Receitas.Ajuste.VISITA -> b(ModoVisita.ativo(ctx))
                Receitas.Ajuste.MODO_SIMPLES -> e(Extras.Chave.MODO_SIMPLES)
                Receitas.Ajuste.INFANTIL -> b(PlayerArmazem.parental(ctx).infantil)
                Receitas.Ajuste.HORA_DORMIR -> e(Extras.Chave.HORA_DORMIR)
                Receitas.Ajuste.TAMANHO_TEXTO -> TamanhoTexto.atual(ctx)
                Receitas.Ajuste.LER_FOCO -> e(Extras.Chave.LER_FOCO)
                Receitas.Ajuste.SOM_NAVEGACAO -> e(Extras.Chave.SOM_NAVEGACAO)
                Receitas.Ajuste.CARD_JOGOS -> e(Extras.Chave.CARD_JOGOS)
                Receitas.Ajuste.MODO_JOGO -> e(Extras.Chave.MODO_JOGO)
                Receitas.Ajuste.CONTROLE_ABRE_JOGOS -> e(Extras.Chave.CONTROLE_ABRE_JOGOS)
            }
        }
    }

    /** Grava uma lista de mudanças, na ordem, com o efeito colateral de cada ajuste (o mesmo da tela Extras). */
    private fun gravar(ctx: Context, mudancas: List<Pair<Receitas.Ajuste, Int>>) {
        val app = ctx.applicationContext
        fun chave(c: Extras.Chave, v: Int) { if (Extras.ligado(app, c) != (v == 1)) ExtrasTv.alternar(app, c) }
        for ((a, v) in mudancas) when (a) {
            Receitas.Ajuste.HOSPEDE -> if (v == 1) ModoHospede.ligar(app) else ModoHospede.desligar(app)
            Receitas.Ajuste.VISITA -> ModoVisita.definir(app, v == 1)
            Receitas.Ajuste.MODO_SIMPLES -> chave(Extras.Chave.MODO_SIMPLES, v)
            Receitas.Ajuste.INFANTIL -> PlayerArmazem.parental(app).let { r ->
                if (r.infantil != (v == 1)) PlayerArmazem.definirParental(app, r.copy(infantil = v == 1))
            }
            Receitas.Ajuste.HORA_DORMIR -> chave(Extras.Chave.HORA_DORMIR, v)
            Receitas.Ajuste.TAMANHO_TEXTO -> TamanhoTexto.definir(app, v)
            Receitas.Ajuste.LER_FOCO -> chave(Extras.Chave.LER_FOCO, v)
            Receitas.Ajuste.SOM_NAVEGACAO -> chave(Extras.Chave.SOM_NAVEGACAO, v)
            Receitas.Ajuste.CARD_JOGOS -> chave(Extras.Chave.CARD_JOGOS, v)
            Receitas.Ajuste.MODO_JOGO -> chave(Extras.Chave.MODO_JOGO, v)
            Receitas.Ajuste.CONTROLE_ABRE_JOGOS -> chave(Extras.Chave.CONTROLE_ABRE_JOGOS, v)
        }
    }

    fun permite(ctx: Context): (Planos.Recurso) -> Boolean = { Planos.permite(ctx, it) }

    /** A receita aplicada por último (para o "Desfazer"), ou null. */
    fun ativa(ctx: Context): Receitas.Receita? = Receitas.dePalavra(prefs(ctx).getString(ATIVA, null))
    fun temDesfazer(ctx: Context) = Receitas.desfazer(prefs(ctx).getString(FOTO, null), ler(ctx)).isNotEmpty()

    /** O que aplicar [r] mudaria agora. */
    fun mudancas(ctx: Context, r: Receitas.Receita) = Receitas.mudancas(r, ler(ctx), permite(ctx))

    /** Aplica [r] (quem chama já mostrou os avisos e, se preciso, pediu o PIN). Devolve o que mudou. */
    @Synchronized fun aplicar(ctx: Context, r: Receitas.Receita): List<Pair<Receitas.Ajuste, Int>> {
        val agora = ler(ctx)
        val m = Receitas.mudancas(r, agora, permite(ctx))
        prefs(ctx).edit().putString(FOTO, Receitas.guardar(prefs(ctx).getString(FOTO, null), agora)).putString(ATIVA, r.name).commit()
        gravar(ctx, m)
        return m
    }

    /** O que desfazer mudaria agora (vazio = nada). */
    fun mudancasDesfazer(ctx: Context) = Receitas.desfazer(prefs(ctx).getString(FOTO, null), ler(ctx))

    /** Volta tudo à foto de antes e esquece a foto. */
    @Synchronized fun desfazer(ctx: Context): List<Pair<Receitas.Ajuste, Int>> {
        val m = mudancasDesfazer(ctx)
        gravar(ctx, m)
        prefs(ctx).edit().remove(FOTO).remove(ATIVA).commit()
        return m
    }

    fun nome(ctx: Context, r: Receitas.Receita) = ctx.getString(when (r) {
        Receitas.Receita.VO -> R.string.rec_vo
        Receitas.Receita.CRIANCAS -> R.string.rec_criancas
        Receitas.Receita.GAMER -> R.string.rec_gamer
        Receitas.Receita.ALUGUEL -> R.string.rec_aluguel
    })

    fun explicacao(ctx: Context, r: Receitas.Receita) = ctx.getString(when (r) {
        Receitas.Receita.VO -> R.string.rec_vo_det
        Receitas.Receita.CRIANCAS -> R.string.rec_criancas_det
        Receitas.Receita.GAMER -> R.string.rec_gamer_det
        Receitas.Receita.ALUGUEL -> R.string.rec_aluguel_det
    })

    fun rotulo(ctx: Context, a: Receitas.Ajuste) = ctx.getString(when (a) {
        Receitas.Ajuste.HOSPEDE -> R.string.rec_aj_hospede
        Receitas.Ajuste.VISITA -> R.string.rec_aj_visita
        Receitas.Ajuste.MODO_SIMPLES -> R.string.rec_aj_simples
        Receitas.Ajuste.INFANTIL -> R.string.rec_aj_infantil
        Receitas.Ajuste.HORA_DORMIR -> R.string.rec_aj_dormir
        Receitas.Ajuste.TAMANHO_TEXTO -> R.string.rec_aj_texto
        Receitas.Ajuste.LER_FOCO -> R.string.rec_aj_ler_foco
        Receitas.Ajuste.SOM_NAVEGACAO -> R.string.rec_aj_som
        Receitas.Ajuste.CARD_JOGOS -> R.string.rec_aj_jogos
        Receitas.Ajuste.MODO_JOGO -> R.string.rec_aj_modo_jogo
        Receitas.Ajuste.CONTROLE_ABRE_JOGOS -> R.string.rec_aj_controle
    })

    /** "Modo simples: ligado", "Tamanho do texto: 120%". */
    fun linha(ctx: Context, a: Receitas.Ajuste, v: Int): String = ctx.getString(R.string.rec_linha, rotulo(ctx, a),
        if (!a.binario) "$v%" else ctx.getString(if (v == 1) R.string.rec_ligado else R.string.rec_desligado))

    /** Texto para adb/painel: "VO: MODO_SIMPLES=1, ...". */
    fun estado(ctx: Context): String = "ativa=${ativa(ctx)?.name ?: "-"} agora=${Receitas.paraTexto(ler(ctx))} " +
        "desfazer=${Receitas.paraTexto(mudancasDesfazer(ctx).toMap())}"
}

/**
 * "Para quem é esta TV?": 4 linhas grandes (uma por receita) e, se houver, "Desfazer". OK numa receita mostra antes o
 * que vai mudar e os avisos; confirmar aplica e mostra o resumo com "Desfazer". Abre do assistente inicial, das
 * Configurações (Sistema) e por adb (`EXTRAS --es o receitas_tela`).
 */
class ReceitasActivity : Activity() {
    companion object {
        /** Para o teste de tela: abre direto na confirmação desta receita (nome do enum). */
        const val EXTRA_CONFIRMAR = "confirmar"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val r = Receitas.dePalavra(intent.getStringExtra(EXTRA_CONFIRMAR))
        if (r != null) confirmar(r) else escolher()
    }

    /** true = na lista; false = confirmação ou resumo (VOLTAR volta à lista). */
    private var naLista = true

    private fun escolher() {
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.rec_titulo), getString(R.string.rec_subtitulo))
        val ativa = ReceitasTv.ativa(this)
        var primeira: android.view.View? = null
        for (r in Receitas.Receita.values()) {
            val linha = Estilo.linhaOpcao(this, ReceitasTv.nome(this, r), ReceitasTv.explicacao(this, r),
                if (r == ativa) Estilo.valor(this, "✓", seta = false) else null).apply {
                setOnClickListener { confirmar(r) }
            }
            coluna.addView(linha, Estilo.lpLinha(this))
            if (primeira == null) primeira = linha
        }
        if (ReceitasTv.temDesfazer(this)) coluna.addView(Estilo.linhaOpcao(this, getString(R.string.rec_desfazer),
            getString(R.string.rec_desfazer_det, ativa?.let { ReceitasTv.nome(this, it) } ?: "")).apply {
            setOnClickListener { desfazer() }
        }, Estilo.lpLinha(this))
        setContentView(raiz)
        naLista = true
        primeira?.requestFocus()
    }

    /** Antes de aplicar: o que muda e os avisos. Nada muda = já está assim. */
    private fun confirmar(r: Receitas.Receita) {
        val temPin = PainelArmazem.temPin(this)
        val m = ReceitasTv.mudancas(this, r)
        val avisos = Receitas.avisos(r, ReceitasTv.ler(this), temPin, ReceitasTv.permite(this))
        val texto = StringBuilder()
        if (m.isEmpty()) texto.append(getString(R.string.rec_ja_esta))
        else m.forEach { (a, v) -> texto.append("• ").append(ReceitasTv.linha(this, a, v)).append('\n') }
        if (Receitas.Aviso.PRENDE in avisos) texto.append('\n').append(getString(R.string.rec_aviso_prende))
        if (Receitas.Aviso.SEM_PIN in avisos) texto.append('\n').append(getString(R.string.rec_aviso_sem_pin))
        if (Receitas.Aviso.PLANO in avisos) texto.append('\n').append(getString(R.string.rec_aviso_plano,
            Receitas.bloqueadosPeloPlano(r, ReceitasTv.permite(this)).joinToString(", ") { ReceitasTv.rotulo(this, it) }))
        val voltar = Estilo.botao(this, getString(R.string.rec_voltar), primario = false).apply { setOnClickListener { escolher() } }
        val aplicar = Estilo.botao(this, getString(R.string.rec_aplicar)).apply {
            setOnClickListener {
                val feito = { ReceitasTv.aplicar(this@ReceitasActivity, r); resumo(r) }
                if (Receitas.pedePin(m, temPin)) PinNaTela.pedir(this@ReceitasActivity, getString(R.string.rec_pin)) { feito() } else feito()
            }
        }
        val botoes = if (m.isEmpty()) listOf(voltar) else listOf(voltar, aplicar)
        naLista = false
        setContentView(Estilo.dialogo(this, null, ReceitasTv.nome(this, r), texto.toString().trim(), botoes, larguraDp = 720))
        (if (m.isEmpty()) voltar else aplicar).let { b -> b.post { b.requestFocus() } }   // depois do layout
    }

    private fun resumo(r: Receitas.Receita) {
        val desfazer = Estilo.botao(this, getString(R.string.rec_desfazer), primario = false).apply { setOnClickListener { desfazer() } }
        val ok = Estilo.botao(this, getString(R.string.rec_pronto)).apply { setOnClickListener { finish() } }
        naLista = false
        setContentView(Estilo.dialogo(this, null, getString(R.string.rec_aplicada), getString(R.string.rec_aplicada_det),
            listOf(desfazer, ok), larguraDp = 720))
        ok.post { ok.requestFocus() }
    }

    private fun desfazer() {
        val m = ReceitasTv.mudancasDesfazer(this)
        val feito = { ReceitasTv.desfazer(this); AvisoTv.rapido(applicationContext, getString(R.string.rec_titulo), getString(R.string.rec_desfeita)); escolher() }
        if (Receitas.pedePin(m, PainelArmazem.temPin(this))) PinNaTela.pedir(this, getString(R.string.rec_pin)) { feito() } else feito()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // VOLTAR na confirmação/resumo volta à lista; na lista, fecha.
        if (keyCode == KeyEvent.KEYCODE_BACK && naLista == false) { escolher(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

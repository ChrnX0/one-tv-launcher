package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Assistente de primeira abertura (05/11/2026, fila longa do brainstorm). Aparece UMA vez, na primeira HOME de quem
 * instalou o launcher sem Device Owner (instalação "comum", ver Recursos.kt). Numa TV já com Device Owner (a Sala) não
 * aparece nunca: ali a configuração já foi feita à mão.
 *
 * Degrau 2 (16/11/2026): antes das linhas, a pergunta "como esta TV vai ser usada?" (UsoTv: Simples, Completo, Para
 * quem cuida). Depois, uma tela só, com as linhas que servem para a escolha: ligar as permissões que dá para pedir na hora, abrir o serviço de teclas
 * (acessibilidade), ver a lista completa (Configurações do launcher) e começar. BACK também conta como "começar":
 * ninguém fica preso aqui.
 */
class BoasVindasActivity : Activity() {
    companion object {
        private const val CHAVE = "boas_vindas_vista"
        private const val PEDIDO = 42

        /** Mostra o assistente se for a primeira vez e o launcher não for Device Owner. Barato: uma leitura de prefs. */
        fun talvezMostrar(ctx: Context) {
            val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            if (prefs.getBoolean(CHAVE, false)) return
            if (PrivilegioDO.somosDonos(ctx)) { prefs.edit().putBoolean(CHAVE, true).apply(); return }
            ctx.startActivity(Intent(ctx, BoasVindasActivity::class.java))
        }

        fun marcarVista(ctx: Context) =
            ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean(CHAVE, true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        marcarVista(this)   // já na abertura: se o app cair aqui, não reabre em laço
        montar()
    }

    override fun onRestart() { super.onRestart(); montar() }   // voltou das Configurações do Android

    @Deprecated("Activity simples, sem AndroidX")
    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) { montar() }

    /**
     * Degrau 2 (UsoTv): antes de tudo, a pergunta "como esta TV vai ser usada?". Três linhas grandes; a escolha some
     * com o que não serve (as abas técnicas das Configurações, os passos de shell/dono deste assistente).
     */
    private fun montarEscolha() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        // T3 (17/11/2026): UMA pergunta. A resposta escolhe o menu (UsoTv) e a receita juntos (PrimeiraPergunta).
        raiz.addView(Estilo.tituloTela(this, getString(R.string.rec_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.pp_det)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        fun opcao(r: PrimeiraPergunta.Resposta, titulo: Int, det: Int) = lista.addView(linha(getString(titulo), getString(det)) {
            UsoTv.escolher(this, r.uso)
            // Com receita: a confirmação dela (o que muda, PIN, Desfazer); ao voltar, onRestart monta o resto do assistente.
            val rec = r.receita
            if (rec == null) montar()
            else { montar(); abrir(Intent(this, ReceitasActivity::class.java).putExtra(ReceitasActivity.EXTRA_CONFIRMAR, rec.name)) }
        })
        opcao(PrimeiraPergunta.Resposta.CASA, R.string.pp_casa, R.string.uso_completo_det)
        opcao(PrimeiraPergunta.Resposta.VO, R.string.rec_vo, R.string.pp_vo_det)
        opcao(PrimeiraPergunta.Resposta.CRIANCAS, R.string.rec_criancas, R.string.rec_criancas_det)
        opcao(PrimeiraPergunta.Resposta.GAMER, R.string.rec_gamer, R.string.rec_gamer_det)
        opcao(PrimeiraPergunta.Resposta.ALUGUEL, R.string.rec_aluguel, R.string.rec_aluguel_det)
        opcao(PrimeiraPergunta.Resposta.ESSENCIAL, R.string.uso_simples, R.string.uso_simples_det)
        setContentView(raiz)
        lista.getChildAt(0)?.requestFocus()
    }

    private fun montar() {
        if (UsoTv.escolhido(this) == null) { montarEscolha(); return }
        val uso = UsoTv.atual(this)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.bv_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.bv_texto, Recursos.resumo(this@BoasVindasActivity))),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

        val faltam = Recursos.perigosasFaltando(this)
        val comecar = linha(getString(R.string.bv_comecar), getString(R.string.bv_comecar_det)) { finish() }
        // Ideia 97 (14/11/2026): sem PIN, a primeira linha (e a que já vem com o foco) é configurar tudo pelo celular.
        if (!PainelArmazem.temPin(this)) lista.addView(linha(getString(R.string.pc_titulo), getString(R.string.pc_linha_det)) {
            abrir(Intent(this, EntrarPeloQrActivity::class.java))
        })
        if (faltam.isNotEmpty()) lista.addView(linha(getString(R.string.bv_perm),
            getString(R.string.bv_perm_det)) { requestPermissions(faltam, PEDIDO) })
        if (AutorizaShizukuService.instancia == null) lista.addView(linha(getString(R.string.bv_teclas),
            getString(R.string.bv_teclas_det)) { abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        // Passo a passo do "modo completo" (13/11/2026): teclas (acessibilidade) → opções do desenvolvedor → parear o shell
        // → dono do aparelho. Cada linha só aparece quando a de antes está feita, para a pessoa seguir sem pensar.
        // Para quem cuida (UsoTv.CUIDA): o acesso de longe e a tela fácil para quem mora lá, logo depois do celular.
        if (uso == UsoTv.CUIDA) {
            lista.addView(linha(getString(R.string.uso_cuidar_longe), getString(R.string.uso_cuidar_longe_det)) {
                abrir(Intent(this, CuidarDeLongeActivity::class.java))
            })
            if (!ModoSimples.ligado(this)) lista.addView(linha(getString(R.string.uso_cartoes), getString(R.string.uso_cartoes_det)) {
                Extras.definir(this, Extras.Chave.MODO_SIMPLES, true); montar()
            })
        }
        if (UsoTv.ofereceTecnico(uso) && AutorizaShizukuService.instancia != null && !SaudeAcesso.shellEsperado(this)) {
            if (!opcoesDevLigadas()) lista.addView(linha(getString(R.string.bv_dev), getString(R.string.bv_dev_det)) {
                abrir(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            }) else lista.addView(linha(getString(R.string.bv_parear), getString(R.string.bv_parear_det)) {
                PareamentoNaTv.comecar(this)
            })
        }
        if (UsoTv.ofereceTecnico(uso) && SaudeAcesso.shellEsperado(this) && !PrivilegioDO.somosDonos(this))
            lista.addView(linha(getString(R.string.bv_dono), getString(R.string.bv_dono_det)) {
                VirarDono.perguntar(this, { t -> AvisoTv.rapido(this, getString(R.string.bv_titulo), t, 4) }) { if (!isFinishing && !isDestroyed) montar() }
            })
        // As receitas já vêm da pergunta única (T3); trocar de receita fica em Configurações e em "Para quem é esta TV?" abaixo.
        if (uso != UsoTv.SIMPLES) lista.addView(linha(getString(R.string.bv_claude), getString(R.string.bv_claude_det)) {
            abrir(Intent(this, ConectarClaudeActivity::class.java))
        })
        // Voltar à pergunta (escolheu errado): a mesma troca também fica em Configurações > Sistema.
        lista.addView(linha(getString(R.string.rec_titulo), getString(R.string.uso_trocar_det, nomeUso(uso))) {
            UsoTv.limpar(this); montar()
        })
        lista.addView(linha(getString(R.string.bv_tudo), getString(R.string.cfg_titulo)) {
            abrir(Intent(this, ConfigLauncherActivity::class.java))
        })
        lista.addView(comecar)
        setContentView(raiz)
        (lista.getChildAt(0) ?: comecar).requestFocus()
    }

    // A resposta de agora como a pessoa a escolheu: a receita ativa, se houver; senão o nome da opção de menu.
    private fun nomeUso(u: UsoTv): String = ReceitasTv.ativa(this)?.let { ReceitasTv.nome(this, it) } ?: getString(when (u) {
        UsoTv.SIMPLES -> R.string.uso_simples
        UsoTv.COMPLETO -> R.string.pp_casa
        UsoTv.CUIDA -> R.string.uso_cuida
    })

    private fun opcoesDevLigadas() =
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun abrir(i: Intent) = try { startActivity(i) } catch (e: Exception) {
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- peças (mesmo estilo das Configurações)

    // A linha de opção do sistema de design (título + explicação, foco padrão), a mesma das Configurações.
    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit) = Estilo.linhaOpcao(this, titulo, detalhe).apply {
        setOnClickListener { aoClicar() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    /** Superfície padrão do sistema de design (Estilo.superficie); o foco é aplicado por Estilo.focarLinha. */
    private fun vidro(focado: Boolean): GradientDrawable = Estilo.superficie(this, focado)
}

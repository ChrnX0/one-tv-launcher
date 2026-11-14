package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context

/**
 * O lado Android do [PerfilCrianca] (ideia 11): a marca de criança por conta, a troca de perfil com PIN e o liga/volta do
 * modo infantil e da hora de dormir. Usa só as APIs que já existem: PlayerArmazem.parental/definirParental (modo
 * infantil) e ExtrasTv.alternar (hora de dormir, com os mesmos efeitos de ligar pelas Configurações).
 *
 * Guarda tudo nas preferências "launcher" (vão nos dois backups): "perfis_crianca" = as contas marcadas (chave de
 * [PerfilDados.sanitizar]) e "crianca_antes" = como estavam o modo infantil e a hora de dormir antes de entrar.
 * Quem chama é sempre o processo do launcher (painel rápido): o ":player" não lê nada disto.
 */
object PerfilCriancaTv {
    private const val PREFS = "launcher"
    private const val CHAVE_CRIANCAS = "perfis_crianca"
    private const val CHAVE_ANTES = "crianca_antes"
    /** As mesmas tentativas do PIN das Configurações (TentativasPin): errar aqui também conta lá, e vice-versa. */
    private const val PREFS_PIN = "config_pin"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun criancas(ctx: Context): Set<String> = HashSet(prefs(ctx).getStringSet(CHAVE_CRIANCAS, emptySet()) ?: emptySet())

    fun eCrianca(ctx: Context, conta: String?): Boolean = conta != null && PerfilDados.sanitizar(conta) in criancas(ctx)

    private fun agora(ctx: Context) =
        PerfilCrianca.Estado(PlayerArmazem.parental(ctx).infantil, Extras.ligado(ctx, Extras.Chave.HORA_DORMIR))

    private fun aplicar(ctx: Context, e: PerfilCrianca.Estado) {
        val r = PlayerArmazem.parental(ctx)
        if (r.infantil != e.infantil) PlayerArmazem.definirParental(ctx, r.copy(infantil = e.infantil))
        if (Extras.ligado(ctx, Extras.Chave.HORA_DORMIR) != e.dormir) ExtrasTv.alternar(ctx, Extras.Chave.HORA_DORMIR)
    }

    /** Entrou num perfil de criança: guarda o estado de agora (se ainda não guardado) e liga os dois. */
    private fun entrar(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putString(CHAVE_ANTES, PerfilCrianca.guardar(p.getString(CHAVE_ANTES, null), agora(ctx))).commit()
        aplicar(ctx, PerfilCrianca.LIGADO)
    }

    /** Saiu do perfil de criança (já com o PIN): volta ao estado guardado e esquece o guardado. */
    private fun sair(ctx: Context) {
        val p = prefs(ctx)
        aplicar(ctx, PerfilCrianca.aoSair(p.getString(CHAVE_ANTES, null), agora(ctx)))
        p.edit().remove(CHAVE_ANTES).commit()
    }

    /**
     * Troca o perfil ativo para [para] (vindo de [de]). Saindo de um perfil de criança pede o PIN dos pais; sem PIN certo
     * não troca. [aoFim] recebe true se trocou. Mostra o aviso curto da troca.
     */
    fun trocar(act: Activity, de: String?, para: String, aoFim: (Boolean) -> Unit) {
        val criancas = criancas(act)
        val t = PerfilCrianca.decidir(de?.let(PerfilDados::sanitizar), PerfilDados.sanitizar(para), criancas, PainelArmazem.temPin(act))
        val efetuar = {
            Perfil.definirAtiva(act, para)
            if (t.restaurar) sair(act)
            if (t.ligar) entrar(act)
            val nome = PerfilCrianca.nomeCurto(para)
            AvisoTv.rapido(act, act.getString(R.string.perfil_aviso_titulo, nome),
                act.getString(if (para.let(PerfilDados::sanitizar) in criancas) R.string.perfil_aviso_crianca else R.string.perfil_aviso_texto), 5)
            aoFim(true)
        }
        if (!t.pedePin) { efetuar(); return }
        pedirPinPais(act, act.getString(R.string.perfil_pin_sair)) { certo -> if (certo) efetuar() else aoFim(false) }
    }

    /**
     * Liga/desliga a marca de criança de [conta] (pede o PIN dos pais; sem PIN definido, só avisa como definir). Se a
     * conta é a ativa, aplica na hora (marcar = entrar; desmarcar = sair). [aoFim] recebe a marca nova (ou a de antes).
     */
    fun alternarMarca(act: Activity, conta: String, ativa: String?, aoFim: (Boolean) -> Unit) {
        val chave = PerfilDados.sanitizar(conta)
        if (!PerfilCrianca.podeMarcar(PainelArmazem.temPin(act))) {
            AvisoTv.rapido(act, act.getString(R.string.perfil_crianca), act.getString(R.string.perfil_crianca_sem_pin), 6)
            aoFim(chave in criancas(act)); return
        }
        pedirPinPais(act, act.getString(R.string.perfil_pin_marcar)) { certo ->
            if (!certo) { aoFim(chave in criancas(act)); return@pedirPinPais }
            val novas = PerfilCrianca.alternar(criancas(act), chave)
            prefs(act).edit().putStringSet(CHAVE_CRIANCAS, novas).commit()
            val marcada = chave in novas
            if (conta == ativa) { if (marcada) entrar(act) else sair(act) }
            aoFim(marcada)
        }
    }

    /**
     * O PIN do painel (o dos pais; o das crianças não vale aqui), com as mesmas tentativas das Configurações
     * (TentativasPin, preferências "config_pin"): do 5º erro em diante, espera crescente.
     */
    private fun pedirPinPais(act: Activity, titulo: String, resultado: (Boolean) -> Unit) {
        val p = act.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
        fun ler() = TentativasPin.Estado(p.getInt("erros", 0), p.getLong("ate", 0), p.getLong("ultimo", 0))
        fun gravar(e: TentativasPin.Estado) = p.edit().putInt("erros", e.erros).putLong("ate", e.bloqueadoAte).putLong("ultimo", e.ultimoErro).apply()
        val espera = TentativasPin.espera(ler(), System.currentTimeMillis())
        if (espera > 0) {
            AvisoTv.rapido(act, titulo, act.getString(R.string.player_pin_espere, TentativasPin.minutos(espera)), 5)
            resultado(false); return
        }
        PinTv.pedir(act, titulo, familia = false) { certo ->
            if (certo) gravar(TentativasPin.acertou())
            else {
                gravar(TentativasPin.errou(ler(), System.currentTimeMillis()))
                AvisoTv.rapido(act, titulo, act.getString(R.string.player_pin_errado), 4)
            }
            resultado(certo)
        }
    }
}

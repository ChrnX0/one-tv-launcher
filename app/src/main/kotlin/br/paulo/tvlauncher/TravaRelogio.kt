package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log

/**
 * Regra pura da trava do relógio (sem nada do Android, testada em TravaRelogioTest). Separada para dar para testar no
 * computador quando trava, quando destrava e o que volta ao destravar.
 */
object TravaRelogioRegra {
    enum class Acao { TRAVAR, DESTRAVAR, NADA }

    /**
     * O que fazer agora.
     * - [horaDormirLigada]: a chave da hora de dormir (não importa se é dia ou noite: a criança mudaria o relógio de dia
     *   para burlar a noite, então a trava vale o dia inteiro enquanto a chave estiver ligada).
     * - [restricaoNossa]: a restrição "não mudar data e hora" posta pelo NOSSO Device Owner já está valendo.
     * - [horaAutomatica]: a hora automática (pela internet) está ligada.
     * Sem Device Owner não dá para fazer nada. Desligada, só desfaz o que nós mesmos pusemos.
     */
    fun acao(somosDonos: Boolean, horaDormirLigada: Boolean, restricaoNossa: Boolean, horaAutomatica: Boolean): Acao = when {
        !somosDonos -> Acao.NADA
        horaDormirLigada && (!restricaoNossa || !horaAutomatica) -> Acao.TRAVAR   // falta algo (ou alguém desfez): põe de novo
        !horaDormirLigada && restricaoNossa -> Acao.DESTRAVAR
        else -> Acao.NADA
    }

    /**
     * Ao destravar: desligar a hora automática de novo? Só se ela estava DESLIGADA antes de travarmos ([antes] = false).
     * null (não guardamos nada) ou true (já estava ligada) = deixa como está: hora automática ligada é o normal da TV.
     */
    fun desligarHoraAutomaticaAoDestravar(antes: Boolean?): Boolean = antes == false
}

/**
 * Hora de dormir à prova de criança (10/11/2026): enquanto a hora de dormir estiver LIGADA (chave
 * [Extras.Chave.HORA_DORMIR]), o relógio da TV não pode ser mudado. Sem isso, era só abrir as Configurações da TV (que
 * ficam liberadas na hora de dormir, decisão do dono) e atrasar o relógio para "sair" do horário.
 *
 * Como: pelo Device Owner, direto no DevicePolicyManager (não precisa de shell):
 * - hora automática ligada (setAutoTimeEnabled, Android 11+; o antigo setAutoTimeRequired está obsoleto), para a hora
 *   vir da internet e não do controle;
 * - restrição DISALLOW_CONFIG_DATE_TIME: some a opção de mudar data, hora e fuso nas Configurações. O fuso fica
 *   travado por ela; não ligamos o fuso automático de propósito (no Chromecast não há operadora para dar o fuso, e
 *   ligar poderia trocar o fuso certo que já está lá).
 * Ao desligar a hora de dormir, tira a restrição e devolve a hora automática como estava antes.
 *
 * Quem chama: [ExtrasDO.aplicar] (no onCreate, via PrivilegioDO), [PainelExtras.aoMudar] (chave mudou na TV, no
 * painel ou pelo adb) e [HoraMudouReceiver] (relógio ou fuso mudou). Idempotente: chamar de novo não faz nada.
 */
object TravaRelogio {
    private const val TAG = "TvLauncher.Relogio"
    private const val ARQUIVO = "trava_relogio"
    private const val CHAVE_ANTES = "hora_auto_antes"   // a hora automática estava ligada antes de travarmos?

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /** A restrição posta pelo NOSSO admin (getUserRestrictions(admin) só mostra as nossas). */
    private fun restricaoNossa(ctx: Context): Boolean = try {
        dpm(ctx).getUserRestrictions(admin(ctx)).getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, false)
    } catch (e: Exception) { false }

    private fun horaAutomatica(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dpm(ctx).getAutoTimeEnabled(admin(ctx))
        else android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AUTO_TIME, 1) == 1
    } catch (e: Exception) { true }   // não deu para ler: supõe ligada (não força escrita à toa)

    /** Está travado agora? (para o "estado" do adb). */
    fun travado(ctx: Context): Boolean = restricaoNossa(ctx)

    /**
     * Põe ou tira a trava conforme a chave da hora de dormir. Rodar fora da thread da tela (chama o DO).
     * @Synchronized: onCreate, aoMudar e HoraMudouReceiver podem chamar ao mesmo tempo (ligar a hora automática dispara
     * TIME_SET); uma chamada por vez, para o "antes" guardado não sair trocado.
     */
    @Synchronized
    fun ajustar(ctx: Context) {
        val donos = PrivilegioDO.somosDonos(ctx)
        // Tempo de tela por dia (ideia 33) também: mudar a data da TV zeraria a cota.
        val ligada = Extras.ligado(ctx, Extras.Chave.HORA_DORMIR) || Extras.ligado(ctx, Extras.Chave.TEMPO_TELA)
        val restricao = if (donos) restricaoNossa(ctx) else false
        val auto = if (donos) horaAutomatica(ctx) else true
        when (TravaRelogioRegra.acao(donos, ligada, restricao, auto)) {
            TravaRelogioRegra.Acao.TRAVAR -> travar(ctx, restricao, auto)
            TravaRelogioRegra.Acao.DESTRAVAR -> destravar(ctx)
            TravaRelogioRegra.Acao.NADA -> {}
        }
    }

    private fun travar(ctx: Context, restricaoJa: Boolean, autoAgora: Boolean) {
        try {
            // Guarda como a hora automática estava só na 1ª vez (se a trava já existia, o "antes" já está guardado).
            if (!restricaoJa && !prefs(ctx).contains(CHAVE_ANTES)) prefs(ctx).edit().putBoolean(CHAVE_ANTES, autoAgora).apply()
            if (!autoAgora && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dpm(ctx).setAutoTimeEnabled(admin(ctx), true)
            if (!restricaoJa) dpm(ctx).addUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_DATE_TIME)
            if (restricaoJa) Log.i(TAG, "trava reforçada (alguém desligou a hora automática; ligada de novo)")
            else Log.i(TAG, "relógio travado (hora automática ligada, data e hora fora das Configurações)")
        } catch (e: Exception) { Log.w(TAG, "travar falhou: $e") }
    }

    private fun destravar(ctx: Context) {
        try {
            dpm(ctx).clearUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_DATE_TIME)
            val antes = if (prefs(ctx).contains(CHAVE_ANTES)) prefs(ctx).getBoolean(CHAVE_ANTES, true) else null
            if (TravaRelogioRegra.desligarHoraAutomaticaAoDestravar(antes) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                dpm(ctx).setAutoTimeEnabled(admin(ctx), false)
            prefs(ctx).edit().remove(CHAVE_ANTES).apply()
            Log.i(TAG, "relógio destravado")
        } catch (e: Exception) { Log.w(TAG, "destravar falhou: $e") }
    }

    /**
     * O relógio ou o fuso mudou (HoraMudouReceiver): confere a trava e, se agora é hora de dormir, fecha na hora o app
     * da frente que não pode (antes só fechava quando outra janela abrisse). Ex.: a hora automática corrigiu um relógio
     * atrasado e caiu dentro do horário.
     */
    fun aoMudarHora(ctx: Context) {
        val app = ctx.applicationContext
        Fundo.rodar {
            ajustar(app)
            Handler(Looper.getMainLooper()).post {
                val servico = AutorizaShizukuService.instancia ?: return@post
                // Só a janela de verdade: o pacoteNaFrente guardado pode ser de um app já fechado (fecharia à toa).
                val pacote = try { servico.rootInActiveWindow?.packageName } catch (e: Exception) { null }
                HoraDormirTv.janela(servico, pacote)   // sem janela, fora do horário ou app liberado: não faz nada
            }
        }
    }
}

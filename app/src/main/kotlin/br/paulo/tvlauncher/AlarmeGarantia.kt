package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * "Alarme à prova de falha" (10/11/2026), a parte que mexe na TV. As regras (testadas) moram em AlarmePerdido.kt.
 *
 * 1. Alarme perdido: o Despertador anota aqui o próximo toque a cada agendamento ([anotar]). Antes de agendar de novo,
 *    [conferir] olha se esse toque passou sem tocar: até 15 min de atraso, toca já; depois disso, mostra o aviso
 *    "o despertador das 6:30 não tocou porque a TV estava desligada" e desliga os alarmes de uma vez só que perderam
 *    a vez. Quem chama: Despertador.agendar (no boot, quando o relógio muda, ao abrir a HOME, ao salvar um alarme).
 * 2. Volume garantido: [subirVolume] ao começar a tocar e [devolverVolume] ao parar (AlarmeTocandoActivity).
 *
 * O estado fica nas prefs "despertador_estado", FORA do backup de propósito: um backup antigo restaurado traria um
 * "próximo toque" do passado e geraria um aviso falso de alarme perdido.
 */
object AlarmeGarantia {
    private const val TAG = "TvLauncher.Alarme"
    private const val PREFS = "despertador_estado"
    /** O volume mínimo fica nas prefs do despertador (essas vão no backup: é uma escolha do dono). */
    private const val PREFS_DESPERTADOR = "despertador"
    const val CHAVE_VOLUME = "volume_minimo"
    const val EXTRA_PREVISTO = "alarme_previsto_em"
    /** A tela foi aberta pela conferência, que já decidiu tocar (dentro da tolerância): a tela não confere o atraso. */
    const val EXTRA_JA_CONFERIDO = "alarme_ja_conferido"
    /** Com o "Bom dia" ligado, o volume só volta depois desse tempo (a fala da previsão não pode sair muda). */
    const val ESPERA_BOM_DIA_MS = 90_000L
    /** Aviso que não conseguiu aparecer (acessibilidade fora): tenta de novo nas próximas conferências por até 24 h. */
    private const val AVISO_VALIDADE_MS = 24 * 60 * 60_000L

    private val principal = Handler(Looper.getMainLooper())
    private var conferindo = false   // conferir() pode desligar alarmes, o que chama agendar() de novo: evita a volta
    @Volatile private var avisando = false   // já há um aviso tentando de 15 em 15 s: a conferência não começa outro

    // ---------------------------------------------------------------- alarme perdido

    /** O Despertador acabou de agendar [id] para [em] (0 = nada agendado). */
    fun anotar(ctx: Context, id: Int, em: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("devido_em", em).putInt("devido_id", id).apply()
    }

    /** A tela do alarme começou a tocar o toque previsto para [previsto]: está resolvido (não é "perdido"). */
    fun marcarTocou(ctx: Context, previsto: Long) {
        if (previsto <= 0) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong("tocou_em", maxOf(previsto, p.getLong("tocou_em", 0)))
            .putLong("resolvido_ate", maxOf(previsto, p.getLong("resolvido_ate", 0))).apply()
    }

    /** Este toque já tocou antes? (o Android pode entregar o mesmo pedido duas vezes: nós e o AlarmManager.) */
    fun jaTocou(ctx: Context, previsto: Long): Boolean =
        previsto > 0 && previsto <= ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("tocou_em", 0)

    /** O toque chegou tarde demais (o relógio pulou para frente com o alarme agendado)? Aí não toca: vira aviso. */
    fun atrasado(previsto: Long, agora: Long = System.currentTimeMillis()) =
        previsto > 0 && agora - previsto > AlarmePerdido.TOLERANCIA_MS

    /**
     * Confere se o toque anotado passou sem tocar e resolve: toca já, ou avisa. Chamado no começo de
     * Despertador.agendar, ANTES de o próximo toque substituir o anotado.
     */
    @Synchronized
    fun conferir(ctx: Context) {
        if (conferindo) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        reenviarPendente(app, p)
        val agora = System.currentTimeMillis()
        val devidoEm = p.getLong("devido_em", 0)
        val itens = Despertador.listar(app).map { AlarmePerdido.Item(it.id, it.hora, it.minuto, it.dias, it.ativo) }
        val av = AlarmePerdido.avaliar(itens, devidoEm, p.getInt("devido_id", 0), p.getLong("resolvido_ate", 0), agora)
        if (devidoEm in 1..agora) p.edit().putLong("resolvido_ate", maxOf(devidoEm, p.getLong("resolvido_ate", 0))).apply()
        if (av.vazia) return
        conferindo = true
        try {
            av.tocarAgora?.let { t ->
                // O Android perdeu o agendamento (a TV reiniciou na hora) ou o pedido ainda não chegou: abre a tela já.
                // Se o AlarmManager também entregar, a tela vê que é o mesmo toque (jaTocou) e não toca duas vezes.
                Log.i(TAG, "toque das ${Despertador.horaTexto(Despertador.Alarme(t.id, t.hora, t.minuto, 0, "", true))} atrasado ${(agora - t.em) / 1000}s: tocando agora")
                try {
                    app.startActivity(Intent(app, AlarmeTocandoActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(Despertador.EXTRA_ID, t.id).putExtra(EXTRA_PREVISTO, t.em).putExtra(EXTRA_JA_CONFERIDO, true))
                } catch (e: Exception) { Log.w(TAG, "não abri a tela do alarme: $e") }
            }
            if (av.desligar.isNotEmpty()) Despertador.desligarSemAgendar(app, av.desligar)
            if (av.perdidos.isNotEmpty()) {
                Log.i(TAG, "alarmes perdidos: ${av.perdidos.joinToString { AlarmePerdido.horaFalada(it.hora, it.minuto) }}")
                avisar(app, textoAviso(app, av.perdidos))
            }
        } finally { conferindo = false }
    }

    /**
     * A tela do alarme recebeu um toque tarde demais (o relógio pulou para frente com o alarme agendado) e não tocou.
     * Avisa (se a conferência ainda não avisou deste toque), desliga o alarme se era de uma vez só e agenda o próximo.
     */
    fun desistiuAtrasado(ctx: Context, id: Int, previsto: Long) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = Despertador.listar(app).firstOrNull { it.id == id }
        if (a != null && previsto > p.getLong("resolvido_ate", 0)) {
            p.edit().putLong("resolvido_ate", previsto).apply()
            if (a.dias == 0 && a.ativo) Despertador.desligarSemAgendar(app, setOf(a.id))
            if (System.currentTimeMillis() - previsto <= AlarmePerdido.JANELA_MS)
                avisar(app, textoAviso(app, listOf(AlarmePerdido.Toque(a.id, a.hora, a.minuto, previsto))))
        }
        Despertador.agendar(app)
    }

    /** "O despertador das 6:30 não tocou porque a TV estava desligada." (um ou vários) */
    fun textoAviso(ctx: Context, perdidos: List<AlarmePerdido.Toque>): String {
        val horas = AlarmePerdido.juntarHoras(perdidos, ctx.getString(R.string.al_perdido_e))
        val varios = perdidos.map { AlarmePerdido.horaFalada(it.hora, it.minuto) }.distinct().size > 1
        return ctx.getString(if (varios) R.string.al_perdido_varios else R.string.al_perdido_um, horas)
    }

    /**
     * Mostra o aviso por cima de qualquer tela (AvisoTv). Logo depois do boot o serviço de acessibilidade pode ainda
     * não estar ligado: aí tenta de novo a cada 15 s por até 3 min. Tela apagada: o AvisoTv guarda e mostra ao acender.
     */
    private fun avisar(app: Context, texto: String) {
        // Guarda antes de tentar: se a acessibilidade não subir nos 3 min, a próxima conferência (ex.: abrir a HOME)
        // tenta de novo, em vez de o aviso sumir para sempre (o toque já está marcado como resolvido).
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("aviso_pendente", texto).putLong("aviso_em", System.currentTimeMillis()).apply()
        avisando = true
        runCatching { Avisos.enviar(app, Avisos.Tipo.ALARME_PERDIDO, app.getString(R.string.al_titulo), texto) }   // e no celular
        tentarAviso(app, texto, 0)
    }

    private fun tentarAviso(app: Context, texto: String, tentativa: Int) {
        val r = try { AvisoTv.mostrar(app, app.getString(R.string.al_titulo), texto, 60, som = true, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO) }
            catch (e: Exception) { Log.w(TAG, "aviso de alarme perdido: $e"); AvisoTv.Resultado.SEM_ACESSIBILIDADE }
        if (r == AvisoTv.Resultado.SEM_ACESSIBILIDADE && tentativa < 12) {
            principal.postDelayed({ tentarAviso(app, texto, tentativa + 1) }, 15_000)
            return
        }
        avisando = false
        if (r != AvisoTv.Resultado.SEM_ACESSIBILIDADE) {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Só apaga se ninguém guardou um aviso mais novo enquanto este tentava.
            if (p.getString("aviso_pendente", null) == texto) p.edit().remove("aviso_pendente").remove("aviso_em").apply()
        }
        Log.i(TAG, "aviso de alarme perdido: $r")
    }

    /** Um aviso que ficou sem aparecer (acessibilidade demorou mais de 3 min no boot): tenta de novo, por até 24 h. */
    private fun reenviarPendente(app: Context, p: android.content.SharedPreferences) {
        if (avisando) return
        val texto = p.getString("aviso_pendente", null) ?: return
        if (System.currentTimeMillis() - p.getLong("aviso_em", 0) > AVISO_VALIDADE_MS) {
            p.edit().remove("aviso_pendente").remove("aviso_em").apply(); return
        }
        avisando = true
        tentarAviso(app, texto, 12)   // uma tentativa só: a próxima conferência tenta de novo
    }

    // ---------------------------------------------------------------- volume garantido

    /** O que o alarme mudou no volume, para devolver depois. */
    class Volume(val original: Int, val definido: Int?, val tirouMudo: Boolean, val lg: VolumeLg.Mudanca? = null)

    fun volumeMinimo(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_DESPERTADOR, Context.MODE_PRIVATE).getInt(CHAVE_VOLUME, VolumeAlarme.PADRAO)

    fun definirVolumeMinimo(ctx: Context, pct: Int) =
        ctx.getSharedPreferences(PREFS_DESPERTADOR, Context.MODE_PRIVATE).edit().putInt(CHAVE_VOLUME, pct).apply()

    /**
     * Ao começar a tocar: tira do mudo (se estiver) e sobe o volume da mídia até o mínimo escolhido. A rampa do alarme
     * continua valendo: ela mexe no volume do PRÓPRIO som (5% a 100%), por cima deste volume da TV.
     * null = não mexeu em nada (mínimo desligado, ou volume fixo: aí quem manda é a TV pelo HDMI-CEC).
     */
    fun subirVolume(ctx: Context): Volume? {
        val pct = volumeMinimo(ctx)
        if (pct <= 0) return null
        // LG pareada (ideia 49): o volume do Android não chega na TV pelo HDMI; sobe o da própria LG, em segundo plano.
        // Só com a chave própria ligada (desligada por padrão: ver VolumeLg.alarmeLigado) e com teto de 30 na LG.
        val lg = if (VolumeLg.configurado(ctx) && VolumeLg.alarmeLigado(ctx)) VolumeLg.Mudanca().also { m ->
            val app = ctx.applicationContext
            Fundo.rodar("Alarme-lg") { VolumeLg.subirAlarme(app, pct, m) }
        } else null
        val am = ctx.getSystemService(AudioManager::class.java) ?: return lg?.let { Volume(-1, null, false, it) }
        return try {
            val s = AudioManager.STREAM_MUSIC   // o som do alarme sai pela mídia (ver SomAlarme)
            // Tira do mudo ANTES de olhar o volume fixo: com saída fixa (HDMI-CEC) o mudo ainda vale a pena desfazer.
            // Não verificado na TV: se a Sala tem volume fixo, conferir com `dumpsys audio` antes do merge.
            val mudo = am.isStreamMute(s)
            if (mudo) am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            if (am.isVolumeFixed) {
                Log.i(TAG, "volume fixo nesta saída: não dá para subir${if (mudo) " (tirou do mudo)" else ""}")
                return if (mudo || lg != null) Volume(am.getStreamVolume(s), null, mudo, lg) else null
            }
            val original = am.getStreamVolume(s)
            val alvo = VolumeAlarme.alvo(original, am.getStreamMaxVolume(s), pct)
            if (alvo != null) am.setStreamVolume(s, alvo, 0)
            Log.i(TAG, "volume do alarme: $original -> ${alvo ?: original}${if (mudo) " (tirou do mudo)" else ""}")
            if (alvo == null && !mudo && lg == null) null else Volume(original, alvo, mudo, lg)
        } catch (e: Exception) { Log.w(TAG, "não mexi no volume: $e"); lg?.let { Volume(-1, null, false, it) } }
    }

    /**
     * Ao parar: devolve o volume (se ninguém mexeu durante o alarme) e o mudo, como estavam. [depoisMs] > 0 = devolve
     * só depois desse tempo (o "Bom dia" falando); a regra "ninguém mexeu" é conferida na hora de devolver.
     */
    fun devolverVolume(ctx: Context, v: Volume?, depoisMs: Long = 0) {
        if (v == null) return
        if (depoisMs > 0) {
            val app = ctx.applicationContext
            principal.postDelayed({ devolverVolume(app, v) }, depoisMs)
            return
        }
        v.lg?.let { m -> val app = ctx.applicationContext; Fundo.rodar("Alarme-lg") { VolumeLg.devolverAlarme(app, m) } }
        if (v.original < 0) return   // só a LG mexeu
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            val s = AudioManager.STREAM_MUSIC
            VolumeAlarme.devolver(am.getStreamVolume(s), v.definido, v.original)?.let { am.setStreamVolume(s, it, 0) }
            if (v.tirouMudo) am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) { Log.w(TAG, "não devolvi o volume: $e") }
    }
}

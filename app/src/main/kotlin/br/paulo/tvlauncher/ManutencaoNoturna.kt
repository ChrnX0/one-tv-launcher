package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Manutenção noturna (10/11/2026). De madrugada, com a TV parada, o launcher roda sozinho as checagens e consertos
 * SEGUROS que já existem e guarda um relatório curto. De manhã, na primeira vez que a TV acende com a HOME na frente,
 * se algo foi consertado ou falhou, mostra 1-2 linhas (AvisoTv).
 *
 * Os passos (cada um isolado: um que falha não impede os outros):
 *  1. acesso: o shell próprio responde? E a autocura do GuardiaoShizuku (sobreposição, WRITE_SECURE_SETTINGS...).
 *  2. permissoes: as permissões fixas do Device Owner (Config.PERMISSOES_FIXAS) continuam concedidas? Falta uma =
 *     PrivilegioDO.conceder. Não chamamos o PrivilegioDO.aplicar inteiro de propósito: ele também mexe no diário de
 *     DNS (ExtrasDO) e na VPN, e a regra é não tocar em rede de madrugada.
 *  3. limpeza: restos de download e temporários do PRÓPRIO app (ver [ManutencaoRegras.apagavel]). Nada de outro app.
 *  4. agendamentos: o despertador e as rotinas estão agendados no Android? Faltando, agenda de novo.
 *  5. espaco: menos de 1 GB livre = limpa o cache de todos os apps (Caches.limpar, só cache; logins ficam).
 *
 * Quando: alarme às [HORA] (AlarmManager, acorda o aparelho). TV em uso (tela acesa com um app na frente, ou alguém
 * mexeu no controle há menos de 1 h) = tenta de novo 1 h depois; passou das 5h59 = fica para a noite seguinte.
 */
object ManutencaoNoturna {
    private const val TAG = "TvLauncher.Manutencao"
    private const val PREFS = "manutencao"
    const val HORA = 4
    private const val ESPACO_MINIMO = 1L shl 30            // 1 GB: abaixo disso, limpa o cache dos apps
    private const val ESPACO_CRITICO = 500L shl 20          // 500 MB: abaixo disso depois da limpeza = avisa
    private const val MAX_ESPERA_MS = 3 * 60_000L           // a manutenção inteira não segura o aparelho acordado mais que isso

    private val principal = Handler(Looper.getMainLooper())
    private var ouvindoTela = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ManutencaoReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** (Re)agenda a próxima. Idempotente: chamar no boot, ao ligar o serviço de acessibilidade e quando o fuso muda. */
    fun agendar(ctx: Context, quando: Long? = null) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val agora = System.currentTimeMillis()
        val normal = ManutencaoRegras.proxima(agora, HORA, TimeZone.getDefault())
        // Adiada (TV em uso às 4h) para daqui a 1 h: um reagendar comum (boot, acessibilidade religando) não pode trocar
        // as 5h pelas 4h de amanhã e perder a noite. O adiamento vale enquanto estiver no futuro e antes da hora normal.
        val adiado = prefs(ctx).getLong("adiado_para", 0L)
        val alvo = quando ?: if (adiado in (agora + 1)..normal) adiado else normal
        if (quando != null) prefs(ctx).edit().putLong("adiado_para", if (quando < normal) quando else 0L).apply()
        // Inexato de propósito (setAndAllowWhileIdle): 4h ou 4h10 tanto faz, e o Android junta com outros despertares.
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alvo, pendente(ctx)) } catch (e: Exception) { Log.w(TAG, "não agendei: $e") }
    }

    /** O alarme tocou. Fora da thread da tela, segurando o aparelho acordado até [MAX_ESPERA_MS]. */
    fun aoTocar(ctx: Context) {
        val app = ctx.applicationContext
        val trava = try {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlauncher:manutencao")
                .apply { acquire(MAX_ESPERA_MS) }
        } catch (e: Exception) { null }
        Fundo.rodar("Manutencao") {
            try {
                val c = Calendar.getInstance()
                // Revisão 10/11/2026: "só a HOME" = a NOSSA MainActivity. Antes passava qualquer tela do nosso pacote (o
                // player de TV ao vivo, filmes e séries) e "não sei o que está na frente" (logo depois de um reinício).
                val decisao = ManutencaoRegras.decidir(c.get(Calendar.HOUR_OF_DAY), telaAcesa(app), Rotinas.minutosSemTecla(),
                    SaudeAcesso.naHome(app))
                Log.i(TAG, "alarme: $decisao")
                when (decisao) {
                    ManutencaoRegras.Decisao.RODAR -> { prefs(app).edit().remove("adiado_para").apply(); rodar(app); agendar(app) }
                    ManutencaoRegras.Decisao.ADIAR -> {
                        agendar(app, System.currentTimeMillis() + 60 * 60_000L)
                        // TV acesa e em uso às 4h (ideia 3, 12/11/2026): pode ser alguém que dormiu com ela ligada.
                        Avisos.enviar(app, Avisos.Tipo.TV_MADRUGADA, txt(R.string.mn_madrugada),
                            txt(R.string.mn_madrugada_det, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
                    }
                    ManutencaoRegras.Decisao.DESISTIR -> { prefs(app).edit().remove("adiado_para").apply(); agendar(app) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "manutenção falhou", e); agendar(app)
            } finally { try { trava?.release() } catch (_: Exception) {} }
        }
    }

    /** Roda todos os passos agora e guarda o relatório. Também pelo adb (ComandosExtras "manutencao"). */
    fun rodar(ctx: Context): ManutencaoRegras.Relatorio {
        val app = ctx.applicationContext
        val passos = listOf(
            passo("acesso") { acesso(app) },
            passo("permissoes") { permissoes(app) },
            passo("limpeza") { limpeza(app) },
            passo("agendamentos") { agendamentos(app) },
            passo("espaco") { espaco(app) },
        )
        val r = ManutencaoRegras.Relatorio(System.currentTimeMillis(), passos)
        prefs(app).edit().putString("ultimo", ManutencaoRegras.paraJson(r).toString()).apply()
        Log.i(TAG, "relatório: " + passos.joinToString { "${it.nome}=${it.resultado}" })
        return r
    }

    private fun passo(nome: String, corpo: () -> Pair<ManutencaoRegras.Resultado, String>): ManutencaoRegras.Passo = try {
        val (r, d) = corpo(); ManutencaoRegras.Passo(nome, r, d)
    } catch (e: Exception) { ManutencaoRegras.Passo(nome, ManutencaoRegras.Resultado.FALHOU, e.javaClass.simpleName) }

    private fun acesso(app: Context): Pair<ManutencaoRegras.Resultado, String> {
        // Shell nunca pareado (ex.: logo depois de um reset) é configuração por fazer, não queda: não vira "Não consegui".
        if (!SaudeAcesso.shellEsperado(app)) return ManutencaoRegras.Resultado.PULADO to "shell nunca pareado"
        val curou = GuardiaoShizuku.verificarECurar(app) ?: return ManutencaoRegras.Resultado.FALHOU to "shell sem resposta"
        return if (curou.isEmpty()) ManutencaoRegras.Resultado.OK to "" else ManutencaoRegras.Resultado.CONSERTADO to curou.joinToString()
    }

    private fun permissoes(app: Context): Pair<ManutencaoRegras.Resultado, String> {
        if (!PrivilegioDO.somosDonos(app)) return ManutencaoRegras.Resultado.PULADO to "não é Device Owner"
        val faltando = Config.PERMISSOES_FIXAS.filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltando.isEmpty()) return ManutencaoRegras.Resultado.OK to ""
        val falhou = faltando.filterNot { PrivilegioDO.conceder(app, app.packageName, it) }
        val nomes = { l: List<String> -> l.joinToString { it.substringAfterLast('.') } }
        return if (falhou.isEmpty()) ManutencaoRegras.Resultado.CONSERTADO to nomes(faltando)
        else ManutencaoRegras.Resultado.FALHOU to nomes(falhou)
    }

    private fun limpeza(app: Context): Pair<ManutencaoRegras.Resultado, String> {
        val agora = System.currentTimeMillis()
        var n = 0; var bytes = 0L
        fun varrer(raiz: File, prefixo: String, dir: File, nivel: Int) {
            if (nivel > 3) return
            for (f in dir.listFiles() ?: return) {
                val rel = prefixo + f.relativeTo(raiz).path.replace(File.separatorChar, '/')
                if (f.isDirectory) varrer(raiz, prefixo, f, nivel + 1)
                else if (ManutencaoRegras.apagavel(rel, agora - f.lastModified())) {
                    val t = f.length()
                    if (f.delete()) { n++; bytes += t }
                }
            }
        }
        varrer(app.cacheDir, "cache/", app.cacheDir, 0)
        for (sub in listOf("fotos-protetor", "backups")) File(app.filesDir, sub).let { if (it.isDirectory) varrer(app.filesDir, "files/", it, 1) }
        // Limpeza é rotina, não conserto: fica como OK (não aparece no resumo da manhã), com o número no detalhe.
        return ManutencaoRegras.Resultado.OK to if (n == 0) "" else "$n arquivos, ${ConsumoRede.texto(bytes)}"
    }

    private fun agendamentos(app: Context): Pair<ManutencaoRegras.Resultado, String> {
        val am = app.getSystemService(AlarmManager::class.java)
        val agora = System.currentTimeMillis()
        val refeitos = ArrayList<String>()
        // Despertador: usa setAlarmClock, então o Android diz qual é o próximo "alarme de relógio" agendado.
        // O Android só diz o PRÓXIMO alarme de relógio de todos: um timer nosso (showIntent nulo) ou o de outro app antes
        // do despertador esconde o nosso. Nesse caso não dá para saber, e não conta como conserto (ver alarmeFaltando).
        val esperado = Despertador.proximo(app)?.second
        val prox = am?.nextAlarmClock
        val nosso = prox?.showIntent?.creatorPackage == app.packageName
        if (ManutencaoRegras.alarmeFaltando(esperado, prox?.triggerTime, nosso)) { Despertador.agendar(app); refeitos += "despertador" }
        // Rotinas: o Android não diz se um alarme comum está agendado (o PendingIntent continua existindo depois de tocar),
        // então agenda de novo sempre (idempotente: mesmo PendingIntent, mesma hora) sem contar como conserto.
        if (Rotinas.proxima(app, agora) != null) Rotinas.agendar(app)
        // Lembretes da grade (12/11/2026): refaz os do "Meu time" com o guia guardado e agenda o próximo aviso.
        runCatching { LembretesTv.varrer(app) }
        return if (refeitos.isEmpty()) ManutencaoRegras.Resultado.OK to "" else ManutencaoRegras.Resultado.CONSERTADO to refeitos.joinToString()
    }

    private fun espaco(app: Context): Pair<ManutencaoRegras.Resultado, String> {
        fun livre() = StatFs(Environment.getDataDirectory().path).availableBytes
        val antes = livre()
        if (antes >= ESPACO_MINIMO) return ManutencaoRegras.Resultado.OK to ConsumoRede.texto(antes)
        val saida = Caches.limpar(app)
        val depois = livre()
        // Caches.limpar devolve "sem shell…" / "falhou: …" quando não limpou: só é conserto se liberou espaço de fato.
        return if (depois < ESPACO_CRITICO || !saida.startsWith("liberado") || depois <= antes)
            ManutencaoRegras.Resultado.FALHOU to "$saida (${ConsumoRede.texto(depois)} livres)"
        else ManutencaoRegras.Resultado.CONSERTADO to saida
    }

    private fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    // ---------------------------------------------------------------- resumo da manhã

    /** Liga o "tela acendeu" (SCREEN_ON só chega a receiver registrado em código). Uma vez por processo. */
    fun vigiar(ctx: Context) {
        if (ouvindoTela) return
        ouvindoTela = true
        val app = ctx.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            // 4 s: a imagem volta na TV e a HOME aparece antes do aviso.
            override fun onReceive(c: Context, i: Intent) { principal.postDelayed({ talvezMostrarResumo(app) }, 4000) }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    /** Mostra o resumo da última noite, se tem algo a dizer e a hora é boa (tela acesa, nossa HOME na frente). */
    fun talvezMostrarResumo(ctx: Context) {
        val app = ctx.applicationContext
        val rel = ManutencaoRegras.deJson(prefs(app).getString("ultimo", null)) ?: return
        val texto = ManutencaoRegras.resumo(rel, { rotulo(app, it) }, app.getString(R.string.mnt_consertei), app.getString(R.string.mnt_falhou))
        if (!ManutencaoRegras.deveMostrarResumo(rel, System.currentTimeMillis(), texto != null, telaAcesa(app), SaudeAcesso.naHome(app))) return
        val r = AvisoTv.mostrar(app, app.getString(R.string.mnt_titulo), texto!!, 15, som = false, guardarSeApagada = false)
        if (r == AvisoTv.Resultado.MOSTRADO) marcarMostrado(app, rel)
    }

    private fun marcarMostrado(app: Context, rel: ManutencaoRegras.Relatorio) =
        prefs(app).edit().putString("ultimo", ManutencaoRegras.paraJson(rel.copy(mostrado = true)).toString()).apply()

    private fun rotulo(ctx: Context, nome: String) = when (nome) {
        "acesso" -> ctx.getString(R.string.mnt_acesso)
        "permissoes" -> ctx.getString(R.string.mnt_permissoes)
        "agendamentos" -> ctx.getString(R.string.mnt_agendamentos)
        "espaco" -> ctx.getString(R.string.mnt_espaco)
        else -> nome
    }

    /** O relatório da última noite em JSON (para o painel e o MCP), ou {} se ainda não rodou. */
    fun json(ctx: Context): JSONObject =
        ManutencaoRegras.deJson(prefs(ctx).getString("ultimo", null))?.let { ManutencaoRegras.paraJson(it) } ?: JSONObject()
}

/** Recebe o alarme da manutenção (não exportado: só o PendingIntent do próprio launcher chega aqui). */
class ManutencaoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = ManutencaoNoturna.aoTocar(ctx)
}

/** As regras da manutenção, sem Android (teste JVM em ManutencaoNoturnaTest). */
object ManutencaoRegras {
    enum class Resultado { OK, CONSERTADO, FALHOU, PULADO }
    data class Passo(val nome: String, val resultado: Resultado, val detalhe: String = "")
    data class Relatorio(val em: Long, val passos: List<Passo>, val mostrado: Boolean = false)
    enum class Decisao { RODAR, ADIAR, DESISTIR }

    /** O resumo fica valendo até 20 h depois da manutenção (TV desligada o dia todo = o resumo de ontem já não serve). */
    const val VALIDADE_RESUMO_MS = 20 * 60 * 60_000L
    private const val HORA = 3_600_000L
    private const val DIA = 24 * HORA

    /** Próxima vez da [hora] cheia depois de [agora], no fuso [tz]. */
    fun proxima(agora: Long, hora: Int, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = agora; set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= agora) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /**
     * Roda agora? Só de madrugada (3h a 5h59). Tela apagada = roda. Tela acesa sem ninguém (1 h sem tecla e nada além
     * da HOME na frente: TV esquecida ligada) = roda. Senão alguém está vendo: tenta de novo em 1 h, ou desiste às 5h.
     */
    fun decidir(hora: Int, telaAcesa: Boolean, minutosSemTecla: Long, soHomeNaFrente: Boolean): Decisao = when {
        hora !in 3..5 -> Decisao.DESISTIR
        !telaAcesa -> Decisao.RODAR
        minutosSemTecla >= 60 && soHomeNaFrente -> Decisao.RODAR
        hora < 5 -> Decisao.ADIAR
        else -> Decisao.DESISTIR
    }

    /**
     * Pode apagar este arquivo do próprio app? [rel] = "cache/..." ou "files/...", [idadeMs] = desde a última mudança.
     * Só restos conhecidos e com idade: um download em andamento é novo e fica.
     */
    fun apagavel(rel: String, idadeMs: Long): Boolean {
        val nome = rel.substringAfterLast('/')
        return when {
            idadeMs < 0 -> false
            rel.startsWith("cache/") -> when {
                // APK baixado pela loja/painel (createTempFile) que ficou para trás: a instalação já acabou há muito.
                (nome.startsWith("loja") || nome.startsWith("painel-instalar")) && nome.endsWith(".apk") -> idadeMs > 6 * HORA
                nome == "atualizacao.apk" -> idadeMs > 2 * DIA      // o Atualizador apaga depois de instalar; sobra = falhou
                rel.startsWith("cache/logos/") -> idadeMs > 30 * DIA   // logos de canais: baixa de novo se precisar
                else -> idadeMs > 14 * DIA                           // qualquer outro cache velho
            }
            rel.startsWith("files/fotos-protetor/") -> nome.startsWith(".envio") && nome.endsWith(".tmp") && idadeMs > HORA
            rel.startsWith("files/backups/") -> nome.endsWith(".tmp") && idadeMs > HORA
            else -> false
        }
    }

    /**
     * O despertador deveria tocar em [esperado]; o próximo alarme de relógio do Android é [proximo] (null = nenhum), e
     * [nosso] diz se é do despertador (showIntent do nosso pacote). Falta se não há nenhum, ou se o próximo vem DEPOIS
     * do esperado (o nosso teria vindo antes), ou se é o nosso com outra hora (1 min de folga). Um de outro (timer,
     * outro app) ANTES do esperado esconde o nosso: não dá para saber, então não falta.
     */
    fun alarmeFaltando(esperado: Long?, proximo: Long?, nosso: Boolean): Boolean = when {
        esperado == null -> false
        proximo == null -> true
        proximo > esperado + 60_000 -> true
        nosso -> kotlin.math.abs(proximo - esperado) > 60_000
        else -> false
    }

    /**
     * 1-2 linhas para a manhã: "Consertei: X, Y." e "Não consegui: Z." Null = nada a dizer (tudo OK ou só limpeza).
     */
    fun resumo(r: Relatorio, rotulo: (String) -> String, consertei: String, falhou: String): String? {
        val c = r.passos.filter { it.resultado == Resultado.CONSERTADO }.map { rotulo(it.nome) }
        val f = r.passos.filter { it.resultado == Resultado.FALHOU }.map { rotulo(it.nome) }
        val linhas = listOfNotNull(
            c.takeIf { it.isNotEmpty() }?.let { "$consertei ${it.joinToString(", ")}." },
            f.takeIf { it.isNotEmpty() }?.let { "$falhou ${it.joinToString(", ")}." },
        )
        return linhas.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Mostrar agora? Uma vez só, dentro da validade, com algo a dizer, tela acesa e a HOME na frente. */
    fun deveMostrarResumo(r: Relatorio?, agora: Long, temTexto: Boolean, telaAcesa: Boolean, naHome: Boolean): Boolean =
        r != null && !r.mostrado && temTexto && telaAcesa && naHome && agora - r.em in 0..VALIDADE_RESUMO_MS

    fun paraJson(r: Relatorio): JSONObject = JSONObject().put("em", r.em).put("mostrado", r.mostrado).put("passos", JSONArray().apply {
        r.passos.forEach { put(JSONObject().put("nome", it.nome).put("resultado", it.resultado.name.lowercase()).put("detalhe", it.detalhe)) }
    })

    /** Lê o que [paraJson] gravou. Texto estragado ou de outra versão = null (só perde o resumo de uma noite). */
    fun deJson(texto: String?): Relatorio? = try {
        val o = JSONObject(texto ?: return null)
        val arr = o.getJSONArray("passos")
        Relatorio(o.getLong("em"), (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Passo(p.getString("nome"), Resultado.valueOf(p.getString("resultado").uppercase()), p.optString("detalhe"))
        }, o.optBoolean("mostrado"))
    } catch (e: Exception) { null }
}

package br.paulo.tvlauncher.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.backup.BackupCompleto
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fala com a LG pela rede (ideia 49; regras em [LgNetcast]). Tudo aqui BLOQUEIA (rede): chame fora da thread da tela
 * ([Fundo.rodar]). Só age depois de pareada; sem pareamento, [configurado] é false e quem chama segue com o volume
 * do Android, como antes.
 *
 * Dados do dono, só nesta TV: prefs "volume_lg" (IP, uuid da TV e o código de pareamento). Fora do backup completo de
 * propósito ([BackupCompleto.FORA_DE_PROPOSITO]): o código vale só para esta LG, e um backup restaurado em outra casa
 * apontaria para uma TV que não é a dela. Parear de novo leva um minuto ("lg_pedir_codigo" + "lg_parear").
 *
 * O IP da LG pode mudar (DHCP): se ela não responde, [reencontrar] procura na mesma rede /24 a TV com o MESMO uuid
 * (no máximo uma busca a cada 10 min).
 */
object VolumeLg {
    private const val TAG = "TvLauncher.VolumeLg"
    private const val PREFS = "volume_lg"
    private const val K_IP = "ip"
    private const val K_UUID = "uuid"
    private const val K_CODIGO = "codigo"
    private const val TEMPO_MS = 2000
    private const val ENTRE_TECLAS_MS = 300L
    private const val K_ALARME = "alarme"
    private const val K_DESLIGAR = "desligar_junto"
    private const val K_ACORDAR = "acordar_junto"
    private const val K_ENTRADA = "entrada_chromecast"
    private const val BUSCA_A_CADA_MS = 10 * 60_000L

    private val trava = Any()   // uma conversa com a TV por vez (duas rotinas apertando teclas juntas erram a conta)
    @Volatile private var ultimaBusca = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configurado(ctx: Context): Boolean = prefs(ctx).let { it.getString(K_IP, null) != null && it.getString(K_CODIGO, null) != null }
    fun ip(ctx: Context): String? = prefs(ctx).getString(K_IP, null)

    fun esquecer(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /**
     * O despertador mexe no volume da LG? Desligado por padrão de 14/11 (o primeiro teste acordou a casa) a 16/11; LIGADO
     * desde que a LG sobe aos poucos até um volume fixo de no máximo 30 ([subirAlarme]). Desliga por EXTRAS lg_alarme.
     */
    fun alarmeLigado(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ALARME, true)
    fun definirAlarme(ctx: Context, ligado: Boolean) { prefs(ctx).edit().putBoolean(K_ALARME, ligado).apply() }

    /**
     * O timer de sono desliga a LG pela rede (15/11/2026)? LIGADO por padrão: a LG ignora o standby do CEC, então sem
     * isto o Chromecast dorme e a LG fica acesa no protetor dela. Muda por comando: EXTRAS lg_desligar --ez ligado.
     */
    fun desligarJunto(ctx: Context): Boolean = prefs(ctx).getBoolean(K_DESLIGAR, true)
    fun definirDesligarJunto(ctx: Context, ligado: Boolean) { prefs(ctx).edit().putBoolean(K_DESLIGAR, ligado).apply() }

    /**
     * A LG ligou pelo controle dela e está na entrada do Chromecast: acordar o Chromecast ([AcordarComLg], 15/11/2026)?
     * LIGADO por padrão: a LG não acorda o Chromecast ao ligar, e a tela fica preta. Muda por EXTRAS lg_acordar --ez ligado.
     */
    fun acordarJunto(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ACORDAR, true)
    fun definirAcordarJunto(ctx: Context, ligado: Boolean) { prefs(ctx).edit().putBoolean(K_ACORDAR, ligado).apply() }

    /** A entrada da LG em que o Chromecast já foi reconhecido (ex.: "HDMI1"), ou null. */
    fun entradaAprendida(ctx: Context): String? = prefs(ctx).getString(K_ENTRADA, null)
    fun aprenderEntrada(ctx: Context, nome: String) { prefs(ctx).edit().putString(K_ENTRADA, nome).apply() }

    /** A entrada que a LG mostra agora, ou null (não pareada, desligada ou fora da rede). */
    fun entrada(ctx: Context): LgNetcast.Entrada? = synchronized(trava) {
        comAuth(ctx) { ip -> pedir(ip, LgNetcast.CAMINHO_ENTRADA, null) }?.let { LgNetcast.entrada(it) }
    }

    /**
     * A LG está na rede (porta do ROAP aceita conexão)? Em standby ela sai da rede. Barato: só abre e fecha o socket,
     * sem pareamento e sem a trava (não espera uma conversa longa de volume).
     */
    fun naRede(ctx: Context, tempoMs: Int = 1500): Boolean {
        val ip = ip(ctx)?.takeIf { LgNetcast.ipLocal(it) } ?: return false
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, LgNetcast.PORTA), tempoMs) }
            true
        } catch (_: Exception) { false }
    }

    /** Aperta a tecla de energia da LG (desliga). false = não respondeu (já desligada, fora da rede ou não pareada). */
    fun desligar(ctx: Context): Boolean = synchronized(trava) { apertar(ctx, LgNetcast.TECLA_ENERGIA, 1) }

    // ---------- pareamento (comandos do adb, ver ComandosExtras) ----------

    /** Acha LGs na rede /24 de [baseIp] (ex.: o IP de qualquer aparelho da casa). Devolve "ip nome modelo" por linha. */
    fun procurar(baseIp: String): List<Pair<String, LgNetcast.Modelo>> {
        if (!LgNetcast.ipLocal(baseIp)) return emptyList()
        val prefixo = baseIp.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(32)
        val achados = java.util.Collections.synchronizedList(mutableListOf<Pair<String, LgNetcast.Modelo>>())
        for (n in 1..254) pool.execute {
            val ip = "$prefixo.$n"
            pedir(ip, LgNetcast.CAMINHO_MODELO, null, 600)?.second?.let { LgNetcast.modelo(it) }?.let { achados += ip to it }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return achados.sortedBy { it.first.substringAfterLast('.').toInt() }
    }

    /** Pede à LG em [ip] que mostre o código de pareamento na tela dela. */
    fun pedirCodigo(ip: String): String {
        if (!LgNetcast.ipLocal(ip)) return "IP inválido (só rede local)"
        val r = pedir(ip, LgNetcast.CAMINHO_AUTH, LgNetcast.corpoPedirCodigo()) ?: return "a TV não respondeu em $ip:${LgNetcast.PORTA}"
        return if (LgNetcast.erro(r.second) == 200) "ok: o código deve aparecer na tela da LG" else "a TV respondeu ${r.first}"
    }

    /** Pareia com o [codigo] da tela e guarda IP, uuid e código. */
    fun parear(ctx: Context, ip: String, codigo: String): String {
        if (!LgNetcast.ipLocal(ip)) return "IP inválido (só rede local)"
        if (!LgNetcast.codigoValido(codigo)) return "código inválido (só números)"
        val r = pedir(ip, LgNetcast.CAMINHO_AUTH, LgNetcast.corpoParear(codigo)) ?: return "a TV não respondeu em $ip"
        if (LgNetcast.erro(r.second) != 200) return "código recusado (resposta ${r.first})"
        val uuid = pedir(ip, LgNetcast.CAMINHO_MODELO, null)?.second?.let { LgNetcast.modelo(it)?.uuid }
        prefs(ctx).edit().putString(K_IP, ip).putString(K_CODIGO, codigo).putString(K_UUID, uuid).apply()
        val v = nivel(ctx)
        Log.i(TAG, "pareada em $ip (volume ${v?.nivel})")
        return "pareada; volume da TV agora: ${v?.nivel ?: "?"}${if (v?.mudo == true) " (mudo)" else ""}"
    }

    fun estado(ctx: Context): String {
        if (!configurado(ctx)) return "não pareada (use lg_procurar, lg_pedir_codigo e lg_parear)"
        val v = nivel(ctx)
        return "LG em ${ip(ctx)}: " + if (v == null) "não respondeu (desligada?)" else "volume ${v.nivel}/${v.maximo}${if (v.mudo) ", mudo" else ""}"
    }

    // ---------- volume ----------

    /** Volume da LG agora, ou null (não pareada, desligada ou fora da rede). */
    fun nivel(ctx: Context): LgNetcast.Volume? = synchronized(trava) { lerNivel(ctx) }

    /** Aperta [tecla] [vezes] vezes (teclas de [LgNetcast]). false = a TV não aceitou. */
    fun tecla(ctx: Context, tecla: Int, vezes: Int = 1): Boolean = synchronized(trava) { apertar(ctx, tecla, vezes) }

    /** Leva o volume a [alvo] (0..máximo da TV). */
    fun irPara(ctx: Context, alvo: Int): Boolean = synchronized(trava) { ajustar(ctx, alvo, soDescer = false) != null }

    /** Só desce, nunca sobe: se o volume passa de [teto], baixa até ele. Devolve o nível antes (null = sem resposta). */
    fun baixarAte(ctx: Context, teto: Int): Int? = synchronized(trava) {
        val v = lerNivel(ctx) ?: return null
        if (v.nivel > teto) ajustar(ctx, teto, soDescer = true)
        v.nivel
    }

    /**
     * Despertador: tira do mudo, põe a LG em [LgNetcast.inicioRampaAlarme] e sobe 1 ponto por vez até [alvo] em
     * [rampaSeg] segundos (no mínimo 90 s, ver LgNetcast.rampaLg). A LG pode estar ligando agora (o CEC acabou de acordá-la e a
     * rede dela demora mais de 37 s): tenta por até [esperaMs]. Se alguém mexer no volume durante a subida, para de
     * subir. Anota o que fez em [m].
     */
    fun subirAlarme(ctx: Context, alvo: Int, rampaSeg: Int, m: Mudanca, esperaMs: Long = 120_000) {
        val fim = SystemClock.elapsedRealtime() + esperaMs
        val ate = LgNetcast.alvoFixoAlarme(alvo)
        val rampa = LgNetcast.rampaLg(rampaSeg)
        try { esperarLg(ctx, ate, rampa, m, fim) } finally { if (m.prontaEm == 0L) m.prontaEm = System.currentTimeMillis() }
        val inicio = m.definido ?: return
        if (m.cancelado) return
        subirAte(ctx, inicio, ate, rampa, m)
    }

    /** Espera a LG responder (consultando a cada [ESPERA_LG_PASSO_MS]) e a põe no começo da rampa. Marca [Mudanca.prontaEm]. */
    private fun esperarLg(ctx: Context, ate: Int, rampa: Int, m: Mudanca, fim: Long) {
        while (true) {
            if (m.cancelado) return
            val comecou = synchronized(trava) {
                // Reconferido DENTRO da trava: o alarme pode ter parado (devolverAlarme) enquanto esperávamos por ela.
                if (m.cancelado) return
                val v = lerNivel(ctx) ?: return@synchronized false
                if (v.mudo && apertar(ctx, LgNetcast.TECLA_MUDO, 1)) m.tirouMudo = true
                m.original = v.nivel
                m.definido = ajustar(ctx, LgNetcast.inicioRampaAlarme(ate), soDescer = false)
                m.prontaEm = System.currentTimeMillis()
                Log.i(TAG, "alarme: volume ${v.nivel} -> ${m.definido}, sobe até $ate em ${rampa}s${if (m.tirouMudo) " (tirou do mudo)" else ""}")
                true
            }
            if (comecou) return
            if (SystemClock.elapsedRealtime() > fim) { Log.i(TAG, "alarme: a LG não respondeu a tempo"); return }
            // 1 s (era 5 s): a LG volta no volume da véspera e toca nele até a gente baixar; quanto antes, menor o susto.
            SystemClock.sleep(ESPERA_LG_PASSO_MS)
        }
    }

    private fun subirAte(ctx: Context, inicio: Int, ate: Int, rampa: Int, m: Mudanca) {
        val passo = LgNetcast.intervaloRampaMs(rampa, inicio, ate)
        var esperado = inicio
        while (esperado < ate && !m.cancelado) {
            SystemClock.sleep(passo)
            if (m.cancelado) return
            val parar = synchronized(trava) {
                if (m.cancelado) return@synchronized true
                val v = lerNivel(ctx) ?: return@synchronized true
                if (LgNetcast.mexeramNaRampa(v.nivel, esperado)) { Log.i(TAG, "alarme: mexeram no volume (${v.nivel}), paro de subir"); m.definido = v.nivel; return@synchronized true }
                if (v.nivel >= ate) { m.definido = v.nivel; esperado = ate; return@synchronized false }
                apertar(ctx, LgNetcast.TECLA_SUBIR, 1)
                SystemClock.sleep(ENTRE_TECLAS_MS)
                val depois = lerNivel(ctx)?.nivel ?: return@synchronized true
                // A LG às vezes perde ou dobra uma tecla: o que vale é o lido. Passou do alvo? Volta ao alvo.
                m.definido = if (depois > ate) ajustar(ctx, ate, soDescer = true) else depois
                esperado = m.definido ?: return@synchronized true
                false
            }
            if (parar) return
        }
        Log.i(TAG, "alarme: subida terminou em ${m.definido}")
    }

    /** Despertador parou: devolve volume e mudo, se ninguém mexeu durante o alarme (mesma regra do Android). */
    fun devolverAlarme(ctx: Context, m: Mudanca) = synchronized(trava) {
        m.cancelado = true
        val v = lerNivel(ctx) ?: return@synchronized
        LgNetcast.devolverAlarme(v.nivel, m.definido, m.original)?.let { ajustar(ctx, it, soDescer = false) }
        Log.i(TAG, "alarme parou: volume ${v.nivel}, antes ${m.original}, pusemos ${m.definido}")
        if (m.tirouMudo && !v.mudo) apertar(ctx, LgNetcast.TECLA_MUDO, 1)
    }

    /** O que o despertador mudou na LG (preenchido em segundo plano por [subirAlarme]). */
    class Mudanca {
        @Volatile var original = -1
        @Volatile var definido: Int? = null
        @Volatile var tirouMudo = false
        @Volatile var cancelado = false
        /** Hora (relógio) em que a LG entrou no começo da rampa ou em que desistimos dela; 0 = ainda esperando. */
        @Volatile var prontaEm = 0L
    }

    private const val ESPERA_LG_PASSO_MS = 1000L

    // ---------- conversa com a TV (chamar com a trava) ----------

    private fun lerNivel(ctx: Context): LgNetcast.Volume? =
        comAuth(ctx) { ip -> pedir(ip, LgNetcast.CAMINHO_VOLUME, null) }?.let { LgNetcast.volume(it) }

    /**
     * Leva o volume a [alvo] conferindo: poucas teclas por rodada e lê de novo, até acertar (a LG acelera em rajada,
     * então contar teclas não basta). [soDescer] = nunca aperta "subir". Devolve o volume lido no fim (null = sem resposta).
     */
    private fun ajustar(ctx: Context, alvo: Int, soDescer: Boolean): Int? {
        var v = lerNivel(ctx) ?: return null
        repeat(40) {
            var r = LgNetcast.rodada(v.nivel, alvo, v.maximo)
            if (soDescer) r = minOf(r, 0)
            if (r == 0) return v.nivel
            if (!mover(ctx, r)) return v.nivel
            SystemClock.sleep(ENTRE_TECLAS_MS)
            v = lerNivel(ctx) ?: return null
        }
        return v.nivel
    }

    private fun mover(ctx: Context, teclas: Int): Boolean = when {
        teclas > 0 -> apertar(ctx, LgNetcast.TECLA_SUBIR, teclas)
        teclas < 0 -> apertar(ctx, LgNetcast.TECLA_DESCER, -teclas)
        else -> true
    }

    private fun apertar(ctx: Context, tecla: Int, vezes: Int): Boolean {
        repeat(vezes.coerceIn(0, LgNetcast.MAX_TECLAS)) { i ->
            if (i > 0) SystemClock.sleep(ENTRE_TECLAS_MS)   // a LG perde teclas em rajada
            comAuth(ctx) { ip -> pedir(ip, LgNetcast.CAMINHO_COMANDO, LgNetcast.corpoTecla(tecla)) } ?: return false
        }
        return true
    }

    /**
     * Faz o pedido; se a LG disser 401 (a sessão caiu; não verificado quando a LG esquece), pareia de novo com o
     * código guardado e repete uma vez. Se não responder, procura a TV pelo uuid (o IP pode ter mudado) e repete.
     * Devolve o corpo só quando o ROAP disse 200.
     */
    private fun comAuth(ctx: Context, chamada: (String) -> Pair<Int, String>?): String? {
        val p = prefs(ctx)
        var ip = p.getString(K_IP, null) ?: return null
        val codigo = p.getString(K_CODIGO, null) ?: return null
        var r = chamada(ip)
        if (r == null) {
            ip = reencontrar(ctx) ?: return null
            r = chamada(ip) ?: return null
        }
        if (r.first == 401) {
            val a = pedir(ip, LgNetcast.CAMINHO_AUTH, LgNetcast.corpoParear(codigo)) ?: return null
            if (LgNetcast.erro(a.second) != 200) { Log.w(TAG, "a LG recusou o código guardado (parear de novo)"); return null }
            r = chamada(ip) ?: return null
        }
        return r.second.takeIf { r.first == 200 && LgNetcast.erro(it) == 200 }
    }

    /** A LG mudou de IP? Procura o mesmo uuid na rede /24 do IP antigo (no máximo a cada 10 min). */
    private fun reencontrar(ctx: Context): String? {
        val p = prefs(ctx)
        val uuid = p.getString(K_UUID, null) ?: return null
        val antigo = p.getString(K_IP, null) ?: return null
        val agora = SystemClock.elapsedRealtime()
        if (ultimaBusca != 0L && agora - ultimaBusca < BUSCA_A_CADA_MS) return null
        ultimaBusca = agora
        val novo = procurar(antigo).firstOrNull { it.second.uuid == uuid }?.first ?: return null
        if (novo != antigo) { Log.i(TAG, "LG mudou de IP: $antigo -> $novo"); p.edit().putString(K_IP, novo).apply() }
        return novo
    }

    /** HTTP simples na porta da LG. null = sem conexão; senão (código HTTP, corpo). */
    private fun pedir(ip: String, caminho: String, corpo: String?, tempoMs: Int = TEMPO_MS): Pair<Int, String>? {
        if (!LgNetcast.ipLocal(ip)) return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("http://$ip:${LgNetcast.PORTA}$caminho").openConnection() as HttpURLConnection).apply {
                connectTimeout = tempoMs; readTimeout = tempoMs; useCaches = false
                instanceFollowRedirects = false
                // O cabeçalho "UDAP/2.0" só vale no caminho UDAP: no ROAP a LG responde 400 (verificado na Sala, 14/11/2026).
                if (LgNetcast.agenteUdap(caminho)) setRequestProperty("User-Agent", "UDAP/2.0")
                if (corpo != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "${LgNetcast.TIPO}; charset=utf-8")
                    outputStream.use { it.write(corpo.toByteArray()) }
                }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.use { s ->
                // Lê no máximo 16 KB: a varredura bate na 8080 de 254 aparelhos, e uma câmera que manda vídeo enchia a memória.
                val buf = ByteArray(16 * 1024); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }.orEmpty()
            codigo to texto
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
}

package br.paulo.tvlauncher

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
    private const val ENTRE_TECLAS_MS = 150L
    private const val BUSCA_A_CADA_MS = 10 * 60_000L

    private val trava = Any()   // uma conversa com a TV por vez (duas rotinas apertando teclas juntas erram a conta)
    @Volatile private var ultimaBusca = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configurado(ctx: Context): Boolean = prefs(ctx).let { it.getString(K_IP, null) != null && it.getString(K_CODIGO, null) != null }
    fun ip(ctx: Context): String? = prefs(ctx).getString(K_IP, null)

    fun esquecer(ctx: Context) { prefs(ctx).edit().clear().apply() }

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
    fun irPara(ctx: Context, alvo: Int): Boolean = synchronized(trava) {
        val v = lerNivel(ctx) ?: return false
        mover(ctx, LgNetcast.teclas(v.nivel, alvo, v.maximo))
    }

    /** Só desce, nunca sobe: se o volume passa de [teto], baixa até ele. Devolve o nível antes (null = sem resposta). */
    fun baixarAte(ctx: Context, teto: Int): Int? = synchronized(trava) {
        val v = lerNivel(ctx) ?: return null
        mover(ctx, LgNetcast.descerAte(v.nivel, teto, v.maximo))
        v.nivel
    }

    /**
     * Despertador: tira do mudo e sobe até o mínimo [pct] (regra de [VolumeAlarme]). A LG pode estar ligando agora
     * (o CEC acabou de acordá-la e o Wi-Fi dela demora): tenta por até [esperaMs]. Anota o que fez em [m].
     */
    fun subirAlarme(ctx: Context, pct: Int, m: Mudanca, esperaMs: Long = 60_000) {
        val fim = SystemClock.elapsedRealtime() + esperaMs
        while (true) {
            val feito = synchronized(trava) {
                val v = lerNivel(ctx) ?: return@synchronized false
                if (v.mudo && apertar(ctx, LgNetcast.TECLA_MUDO, 1)) m.tirouMudo = true
                val alvo = VolumeAlarme.alvo(v.nivel, v.maximo, pct)
                m.original = v.nivel
                if (alvo != null && mover(ctx, LgNetcast.teclas(v.nivel, alvo, v.maximo))) m.definido = alvo
                Log.i(TAG, "alarme: volume ${v.nivel} -> ${alvo ?: v.nivel}${if (m.tirouMudo) " (tirou do mudo)" else ""}")
                true
            }
            if (feito || m.cancelado || SystemClock.elapsedRealtime() > fim) return
            SystemClock.sleep(5000)
        }
    }

    /** Despertador parou: devolve volume e mudo, se ninguém mexeu durante o alarme (mesma regra do Android). */
    fun devolverAlarme(ctx: Context, m: Mudanca) = synchronized(trava) {
        m.cancelado = true
        val v = lerNivel(ctx) ?: return@synchronized
        VolumeAlarme.devolver(v.nivel, m.definido, m.original)?.let { mover(ctx, LgNetcast.teclas(v.nivel, it, v.maximo)) }
        if (m.tirouMudo && !v.mudo) apertar(ctx, LgNetcast.TECLA_MUDO, 1)
    }

    /** O que o despertador mudou na LG (preenchido em segundo plano por [subirAlarme]). */
    class Mudanca {
        @Volatile var original = -1
        @Volatile var definido: Int? = null
        @Volatile var tirouMudo = false
        @Volatile var cancelado = false
    }

    // ---------- conversa com a TV (chamar com a trava) ----------

    private fun lerNivel(ctx: Context): LgNetcast.Volume? =
        comAuth(ctx) { ip -> pedir(ip, LgNetcast.CAMINHO_VOLUME, null) }?.let { LgNetcast.volume(it) }

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
                setRequestProperty("User-Agent", "UDAP/2.0")
                if (corpo != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "${LgNetcast.TIPO}; charset=utf-8")
                    outputStream.use { it.write(corpo.toByteArray()) }
                }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.use { s ->
                s.readBytes().take(16 * 1024).toByteArray().toString(Charsets.UTF_8)
            }.orEmpty()
            codigo to texto
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
}

package br.paulo.tvlauncher.player

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.Avisos
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.backup.DriveBackup
import br.paulo.tvlauncher.backup.DriveRegras
import br.paulo.tvlauncher.backup.Nuvem
import br.paulo.tvlauncher.backup.NuvemBackup
import br.paulo.tvlauncher.backup.RetornoNuvem
import br.paulo.tvlauncher.home.abrir
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * A regra pura da cópia das gravações para a nuvem (testes em RecordingCloudCopyTest).
 *
 * Por que copiar DEPOIS e não gravar direto na nuvem (decisão do dono, 20/11/2026): a gravação não pode perder pedaço
 * quando a internet de envio da casa oscila, e a TV não tem espaço para segurar o atraso. Gravando no pendrive/rede e
 * copiando no fim, a cópia pode demorar o quanto for preciso e recomeçar se cair.
 */
object CloudCopyRules {
    /** Pedaço de envio: 10 MiB é múltiplo de 320 KiB (OneDrive) e de 256 KiB (Google Drive). Cabe na memória da TV. */
    const val PEDACO = 10L * 1024 * 1024
    /** Box: abaixo disso não há envio em partes (o próprio Box recusa); vai num envio só. */
    const val BOX_MINIMO_PARTES = 20L * 1024 * 1024
    const val MAX_TENTATIVAS = 5
    const val PASTA = "OneTV-gravacoes"

    /** Os pedaços de um arquivo de [total] bytes: (início, fim inclusive). */
    fun pedacos(total: Long, tamanho: Long = PEDACO): List<LongRange> {
        require(tamanho > 0)
        if (total <= 0) return emptyList()
        return (0 until total step tamanho).map { it..minOf(it + tamanho, total) - 1 }
    }

    fun contentRange(r: LongRange, total: Long) = "bytes ${r.first}-${r.last}/$total"

    /** Google Drive, resposta 308 ("continue"): o cabeçalho Range diz até onde chegou; sem ele, nada chegou. */
    fun proximoDoDrive(range: String?): Long =
        range?.substringAfterLast('-')?.trim()?.toLongOrNull()?.plus(1) ?: 0L

    fun argDropboxInicio(): String = JSONObject().put("close", false).toString()
    fun argDropboxAnexar(sessao: String, offset: Long): String =
        JSONObject().put("cursor", JSONObject().put("session_id", sessao).put("offset", offset)).put("close", false).toString()
    fun argDropboxFim(sessao: String, total: Long, nome: String): String = JSONObject()
        .put("cursor", JSONObject().put("session_id", sessao).put("offset", total))
        .put("commit", JSONObject().put("path", "/$PASTA/$nome").put("mode", "overwrite").put("mute", true)).toString()

    fun oneDriveSessao(graph: String, nome: String) =
        "$graph/me/drive/special/approot:/$PASTA/${URLEncoder.encode(nome, "UTF-8").replace("+", "%20")}:/createUploadSession"

    /** O "Digest" do Box: SHA-1 em base64. */
    fun digestBox(sha1: ByteArray) = "sha=" + java.util.Base64.getEncoder().encodeToString(sha1)

    // ---------------------------------------------------------------- fila (sobrevive a reiniciar a TV)

    /** Uma parte de gravação a copiar: de onde ([destino] = GravacaoDestino.Opcao.id), qual arquivo e para onde. */
    data class Copia(val destino: String, val arquivo: String, val alvo: String, val apagar: Boolean, val tentativas: Int = 0)

    fun paraJson(l: List<Copia>): String = JSONArray().apply {
        l.forEach { put(JSONObject().put("d", it.destino).put("a", it.arquivo).put("n", it.alvo).put("x", it.apagar).put("t", it.tentativas)) }
    }.toString()

    fun deJson(s: String?): List<Copia> {
        val a = runCatching { JSONArray(s ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Copia(o.optString("d"), o.optString("a"), o.optString("n"), o.optBoolean("x"), o.optInt("t"))
                .takeIf { it.destino.isNotEmpty() && it.alvo.isNotEmpty() && nomeValido(it.arquivo) }
        }
    }

    /** Só nomes simples de arquivo de gravação (nada de caminho). */
    fun nomeValido(n: String) = n.isNotEmpty() && n.length <= 200 && '/' !in n && '\\' !in n && n != "." && n != ".." &&
        n.endsWith(GravacaoRegras.EXTENSAO)

    /** Depois de uma tentativa: falhou e ainda pode tentar = volta para o fim da fila com +1; senão sai. */
    fun depois(c: Copia, ok: Boolean): Copia? = if (ok || c.tentativas + 1 >= MAX_TENTATIVAS) null else c.copy(tentativas = c.tentativas + 1)
}

/**
 * Copiar a gravação para a nuvem ao terminar (pedido do dono, 20/11/2026): Google Drive (a conta do backup, com
 * drive.file), OneDrive (pasta do app, em Aplicativos), Dropbox e Box (contas do "Na rede"; Dropbox e Box só escrevem
 * se o app do dono tem a permissão de escrita marcada). Pasta "OneTV-gravacoes". Envio em pedaços de 10 MiB, cada um
 * com até 3 tentativas; a parte inteira volta para a fila se cair, até 5 vezes. "Apagar daqui depois" só apaga a
 * cópia local quando a da nuvem terminou.
 *
 * Roda no processo :player, dentro do GravacaoServico (primeiro plano, com trava de Wi-Fi), nunca na thread da tela.
 */
object RecordingCloudCopy {
    private const val TAG = "TvLauncher.Gravacao"
    private const val PREFS = "gravacao"
    private const val ALVO = "nuvem_alvo"
    private const val APAGAR = "nuvem_apagar"
    private const val FILA = "gravacoes_copias.json"
    const val DRIVE = "gdrive"
    private const val CONTA = "conta:"
    private val TIPOS = setOf(ContaMidia.ONEDRIVE, ContaMidia.DROPBOX, ContaMidia.BOX)

    class Alvo(val id: String, val nome: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun alvo(ctx: Context): String = prefs(ctx).getString(ALVO, "").orEmpty()
    fun apagarDepois(ctx: Context): Boolean = prefs(ctx).getBoolean(APAGAR, false)
    fun definir(ctx: Context, alvo: String) = prefs(ctx).edit().putString(ALVO, alvo).apply()
    fun definirApagar(ctx: Context, sim: Boolean) = prefs(ctx).edit().putBoolean(APAGAR, sim).apply()

    /** As nuvens em que dá para copiar agora. Lê disco: fora da tela. */
    fun alvos(ctx: Context): List<Alvo> {
        val l = ArrayList<Alvo>()
        DriveBackup.email(ctx)?.let { l += Alvo(DRIVE, "Google Drive · $it") }
        MidiaArmazem.contas(ctx).filter { it.tipo in TIPOS }.forEach { l += Alvo(CONTA + it.id, it.nome) }
        return l
    }

    fun nomeDoAlvo(ctx: Context, id: String): String? = alvos(ctx).firstOrNull { it.id == id }?.nome

    // ---------------------------------------------------------------- fila

    private fun arquivoFila(ctx: Context) = java.io.File(ctx.applicationContext.filesDir, FILA)
    fun fila(ctx: Context): List<CloudCopyRules.Copia> = synchronized(this) {
        CloudCopyRules.deJson(arquivoFila(ctx).takeIf { it.exists() }?.readText())
    }
    private fun salvar(ctx: Context, l: List<CloudCopyRules.Copia>) = synchronized(this) {
        if (l.isEmpty()) arquivoFila(ctx).delete() else PlayerCarga.gravarAtomico(arquivoFila(ctx), CloudCopyRules.paraJson(l))
    }

    /** Uma gravação terminou em [destino]: põe as partes na fila, se há nuvem escolhida. */
    fun enfileirar(ctx: Context, destino: String, partes: List<String>) {
        val alvo = alvo(ctx).ifEmpty { return }
        val apagar = apagarDepois(ctx)
        synchronized(this) {
            val atual = fila(ctx)
            val novas = partes.filter { CloudCopyRules.nomeValido(it) }.map { CloudCopyRules.Copia(destino, it, alvo, apagar) }
                .filter { n -> atual.none { it.destino == n.destino && it.arquivo == n.arquivo } }
            salvar(ctx, atual + novas)
        }
    }

    /**
     * Copia tudo o que está na fila, uma parte por vez (chamar numa thread de fundo; bloqueia). Para quando [parar]
     * fica true (uma gravação nova começou: ela tem prioridade na rede). Devolve quantas partes ficaram na fila.
     */
    fun processar(ctx: Context, parar: () -> Boolean, aoComecar: (String) -> Unit): Int {
        val app = ctx.applicationContext
        val feitas = HashMap<String, Int>()   // nome da nuvem -> partes copiadas (para um aviso só no fim)
        while (!parar()) {
            val c = fila(app).firstOrNull() ?: break
            val nomeAlvo = nomeDoAlvo(app, c.alvo)
            val r = if (nomeAlvo == null) Result.failure(IOException("a conta da nuvem não existe mais")) else {
                aoComecar(nomeAlvo)
                runCatching { copiar(app, c) }
            }
            r.exceptionOrNull()?.let { Log.w(TAG, "cópia de ${c.arquivo} para a nuvem falhou (${c.tentativas + 1}): ${it.javaClass.simpleName} ${it.message?.take(80)}") }
            val ok = r.isSuccess
            if (ok) {
                feitas[nomeAlvo!!] = (feitas[nomeAlvo] ?: 0) + 1
                if (c.apagar) runCatching { GravacaoDestino.abrir(app, c.destino)?.takeIf { it.id == c.destino }?.use { it.apagar(c.arquivo) } }
            }
            // A gravação foi apagada antes de copiar (20/11/2026: o teste viu 5 tentativas à toa): sai da fila sem aviso.
            val sumiu = r.exceptionOrNull() is java.io.FileNotFoundException
            val prox = if (sumiu) null else CloudCopyRules.depois(c, ok)
            synchronized(this) {
                val resto = fila(app).filter { !(it.destino == c.destino && it.arquivo == c.arquivo) }
                salvar(app, if (prox != null) resto + prox else resto)
            }
            if (!ok && prox == null && !sumiu) Avisos.enviar(app, Avisos.Tipo.GRAVACAO, app.getString(R.string.grv_nao_copiou, nomeAlvo ?: "?"),
                GravacaoRegras.lerNome(c.arquivo).second)
            if (!ok && prox != null && fila(app).all { it.tentativas > 0 }) break   // tudo já falhou uma vez: tenta mais tarde
        }
        for ((nome, n) in feitas) Avisos.enviar(app, Avisos.Tipo.GRAVACAO, app.getString(R.string.grv_copiada, nome),
            app.getString(R.string.grv_partes_copiadas, n))
        return fila(app).size
    }

    private fun copiar(ctx: Context, c: CloudCopyRules.Copia) {
        val d = GravacaoDestino.abrir(ctx, c.destino)?.takeIf { it.id == c.destino } ?: throw IOException("o lugar da gravação não está ligado")
        d.use {
            val total = d.tamanho(c.arquivo)
            if (total <= 0) throw IOException("arquivo vazio")
            d.ler(c.arquivo).use { e ->
                when {
                    c.alvo == DRIVE -> drive(ctx, c.arquivo, total, e)
                    c.alvo.startsWith(CONTA) -> {
                        val conta = MidiaArmazem.conta(ctx, c.alvo.removePrefix(CONTA)) ?: throw IOException("conta sumiu")
                        when (conta.tipo) {
                            ContaMidia.ONEDRIVE -> oneDrive(conta, c.arquivo, total, e)
                            ContaMidia.DROPBOX -> dropbox(conta, c.arquivo, total, e)
                            ContaMidia.BOX -> box(conta, c.arquivo, total, e)
                            else -> throw IOException("não é nuvem")
                        }
                    }
                    else -> throw IOException("alvo desconhecido")
                }
            }
        }
        Log.i(TAG, "copiada para a nuvem: ${c.arquivo}")
    }

    // ---------------------------------------------------------------- HTTP

    private class Resp(val codigo: Int, val corpo: String, val cabecalhos: Map<String, List<String>>) {
        fun cab(n: String) = cabecalhos.entries.firstOrNull { it.key.equals(n, true) }?.value?.firstOrNull()
    }

    private fun http(url: String, metodo: String, cab: Map<String, String>, corpo: ByteArray?, tam: Int = corpo?.size ?: 0): Resp {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo; connectTimeout = 15_000; readTimeout = 120_000; useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", Http.USER_AGENT)
            cab.forEach { (k, v) -> setRequestProperty(k, v) }
            if (corpo != null) { doOutput = true; setFixedLengthStreamingMode(tam) }
            else if (metodo == "POST" || metodo == "PUT") { doOutput = true; setFixedLengthStreamingMode(0) }
        }
        try {
            if (corpo != null) c.outputStream.use { it.write(corpo, 0, tam) } else if (c.doOutput) c.outputStream.close()
            val cod = c.responseCode
            val texto = (if (cod in 200..299) c.inputStream else c.errorStream)?.use { s ->
                val b = s.readBytes(); if (b.size > 1024 * 1024) "" else String(b, Charsets.UTF_8)
            }.orEmpty()
            return Resp(cod, texto, c.headerFields.filterKeys { it != null })
        } finally { c.disconnect() }
    }

    /** Lê o próximo pedaço inteiro (ou o que sobrar no fim). */
    private fun lerPedaco(e: InputStream, buf: ByteArray, quer: Int): Int {
        var n = 0
        while (n < quer) { val r = e.read(buf, n, quer - n); if (r < 0) break; n += r }
        return n
    }

    /** Envia um pedaço com até 3 tentativas (rede da casa oscilando); [envio] devolve true quando aceito. */
    private fun comTentativas(envio: () -> Boolean) {
        var erro: Exception? = null
        repeat(3) { i ->
            try { if (envio()) return } catch (x: IOException) { erro = x }
            Thread.sleep(2_000L shl i)
        }
        throw erro ?: IOException("a nuvem recusou o pedaço")
    }

    private fun falha(r: Resp, onde: String): Nothing =
        throw IOException(if (r.codigo == 401 || r.codigo == 403) "$onde: sem permissão de escrever (HTTP ${r.codigo})" else "$onde: HTTP ${r.codigo}")

    // ---------------------------------------------------------------- OneDrive (pasta do app)

    private fun oneDrive(c: ContaMidia, nome: String, total: Long, e: InputStream) {
        val s = http(CloudCopyRules.oneDriveSessao(Nuvem.GRAPH, nome), "POST",
            mapOf("Authorization" to "Bearer ${Nuvem.acesso(c)}", "Content-Type" to "application/json"),
            JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")).toString().toByteArray())
        if (s.codigo !in 200..299) falha(s, "OneDrive")
        val url = JSONObject(s.corpo).getString("uploadUrl")
        require(url.startsWith("https://")) { "endereço de envio estranho" }
        val buf = ByteArray(CloudCopyRules.PEDACO.toInt())
        for (r in CloudCopyRules.pedacos(total)) {
            val n = lerPedaco(e, buf, (r.last - r.first + 1).toInt())
            if (n.toLong() != r.last - r.first + 1) throw IOException("o arquivo encolheu")
            // O endereço da sessão já autoriza: o token NÃO vai junto (a Microsoft pede assim).
            comTentativas {
                val p = http(url, "PUT", mapOf("Content-Range" to CloudCopyRules.contentRange(r, total)), buf, n)
                if (p.codigo in 200..299) true else if (p.codigo in 500..599) false else falha(p, "OneDrive")
            }
        }
    }

    // ---------------------------------------------------------------- Dropbox (/OneTV-gravacoes)

    private const val DBX = "https://content.dropboxapi.com/2/files/upload_session"

    private fun dropbox(c: ContaMidia, nome: String, total: Long, e: InputStream) {
        fun cab(arg: String) = mapOf("Authorization" to "Bearer ${Nuvem.acesso(c)}", "Content-Type" to "application/octet-stream",
            "Dropbox-API-Arg" to arg)
        val ini = http("$DBX/start", "POST", cab(CloudCopyRules.argDropboxInicio()), ByteArray(0))
        if (ini.codigo !in 200..299) falha(ini, "Dropbox")
        val sessao = JSONObject(ini.corpo).getString("session_id")
        val buf = ByteArray(CloudCopyRules.PEDACO.toInt())
        for (r in CloudCopyRules.pedacos(total)) {
            val n = lerPedaco(e, buf, (r.last - r.first + 1).toInt())
            if (n.toLong() != r.last - r.first + 1) throw IOException("o arquivo encolheu")
            comTentativas {
                val p = http("$DBX/append_v2", "POST", cab(CloudCopyRules.argDropboxAnexar(sessao, r.first)), buf, n)
                if (p.codigo in 200..299) true else if (p.codigo == 429 || p.codigo in 500..599) false else falha(p, "Dropbox")
            }
        }
        val fim = http("$DBX/finish", "POST", cab(CloudCopyRules.argDropboxFim(sessao, total, nome)), ByteArray(0))
        if (fim.codigo !in 200..299) falha(fim, "Dropbox")
    }

    // ---------------------------------------------------------------- Box (pasta OneTV-gravacoes na raiz)

    private const val BOX_UP = "https://upload.box.com/api/2.0/files"

    private fun pastaBox(c: ContaMidia): String {
        val raiz = Nuvem.lerListaBox(Nuvem.conferir(Nuvem.get(Nuvem.boxItens("0", 0), Nuvem.acesso(c)))).first
        raiz.firstOrNull { it.item.pasta && it.item.nome == CloudCopyRules.PASTA }?.let { return it.id }
        val r = Nuvem.postarJson("${Nuvem.BOX_API}/folders", Nuvem.acesso(c),
            JSONObject().put("name", CloudCopyRules.PASTA).put("parent", JSONObject().put("id", "0")).toString())
        if (r.codigo == 409) runCatching { JSONObject(r.corpo).getJSONObject("context_info").getJSONArray("conflicts")
            .getJSONObject(0).getString("id") }.getOrNull()?.let { return it }
        return JSONObject(Nuvem.conferir(r)).getString("id")
    }

    private fun box(c: ContaMidia, nome: String, total: Long, e: InputStream) {
        val pasta = pastaBox(c)
        if (total < CloudCopyRules.BOX_MINIMO_PARTES) {
            val bytes = e.readBytes()
            val fronteira = "onetv" + RetornoNuvem.novoNumero()
            val corpo = NuvemBackup.corpoBox(fronteira, JSONObject().put("name", nome).put("parent", JSONObject().put("id", pasta)).toString(), nome, bytes)
            val r = http("$BOX_UP/content", "POST", mapOf("Authorization" to "Bearer ${Nuvem.acesso(c)}",
                "Content-Type" to "multipart/form-data; boundary=$fronteira"), corpo)
            if (r.codigo !in 200..299 && r.codigo != 409) falha(r, "Box")
            return
        }
        val auth = { mapOf("Authorization" to "Bearer ${Nuvem.acesso(c)}") }
        val s = http("$BOX_UP/upload_sessions", "POST", auth() + ("Content-Type" to "application/json"),
            JSONObject().put("folder_id", pasta).put("file_size", total).put("file_name", nome).toString().toByteArray())
        if (s.codigo == 409) return   // já existe lá (copiada antes)
        if (s.codigo !in 200..299) falha(s, "Box")
        val js = JSONObject(s.corpo)
        val parte = js.getLong("part_size")
        val fim = js.getJSONObject("session_endpoints")
        val urlParte = fim.getString("upload_part"); val urlCommit = fim.getString("commit")
        require(urlParte.startsWith("https://") && urlCommit.startsWith("https://"))
        val todo = MessageDigest.getInstance("SHA-1")
        val partes = JSONArray()
        val buf = ByteArray(parte.toInt())
        for (r in CloudCopyRules.pedacos(total, parte)) {
            val n = lerPedaco(e, buf, (r.last - r.first + 1).toInt())
            if (n.toLong() != r.last - r.first + 1) throw IOException("o arquivo encolheu")
            todo.update(buf, 0, n)
            val sha = MessageDigest.getInstance("SHA-1").apply { update(buf, 0, n) }.digest()
            comTentativas {
                val p = http(urlParte, "PUT", auth() + mapOf("Content-Type" to "application/octet-stream",
                    "Digest" to CloudCopyRules.digestBox(sha), "Content-Range" to CloudCopyRules.contentRange(r, total)), buf, n)
                if (p.codigo in 200..299) { partes.put(JSONObject(p.corpo).getJSONObject("part")); true }
                else if (p.codigo in 500..599) false else falha(p, "Box")
            }
        }
        val digest = CloudCopyRules.digestBox(todo.digest())
        val corpo = JSONObject().put("parts", partes).toString().toByteArray()
        repeat(10) {
            val r = http(urlCommit, "POST", auth() + mapOf("Content-Type" to "application/json", "Digest" to digest), corpo)
            if (r.codigo == 201 || r.codigo == 200) return
            if (r.codigo != 202) falha(r, "Box")
            Thread.sleep(((r.cab("Retry-After")?.toLongOrNull() ?: 5L).coerceIn(1, 60)) * 1000)   // 202 = ainda juntando
        }
        throw IOException("o Box não terminou de juntar as partes")
    }

    // ---------------------------------------------------------------- Google Drive (conta do backup, drive.file)

    private fun drive(ctx: Context, nome: String, total: Long, e: InputStream) {
        val token = DriveBackup.acesso(ctx)
        val pasta = DriveBackup.pasta(ctx, token, CloudCopyRules.PASTA, "drive_pasta_gravacoes")
        val ini = http("${DriveRegras.UPLOAD}?uploadType=resumable", "POST", mapOf("Authorization" to "Bearer $token",
            "Content-Type" to "application/json; charset=UTF-8", "X-Upload-Content-Type" to "video/mp2t",
            "X-Upload-Content-Length" to total.toString()),
            JSONObject().put("name", nome).put("parents", JSONArray().put(pasta)).toString().toByteArray())
        if (ini.codigo !in 200..299) falha(ini, "Drive")
        val url = ini.cab("Location") ?: throw IOException("o Drive não abriu o envio")
        require(url.startsWith("https://"))
        val buf = ByteArray(CloudCopyRules.PEDACO.toInt())
        for (r in CloudCopyRules.pedacos(total)) {
            val n = lerPedaco(e, buf, (r.last - r.first + 1).toInt())
            if (n.toLong() != r.last - r.first + 1) throw IOException("o arquivo encolheu")
            comTentativas {
                val p = http(url, "PUT", mapOf("Content-Range" to CloudCopyRules.contentRange(r, total)), buf, n)
                when {
                    p.codigo == 200 || p.codigo == 201 -> true
                    // 308 = recebido; confere que chegou até o fim deste pedaço (senão manda de novo).
                    p.codigo == 308 -> CloudCopyRules.proximoDoDrive(p.cab("Range")) == r.last + 1
                    p.codigo in 500..599 -> false
                    else -> falha(p, "Drive")
                }
            }
        }
    }
}

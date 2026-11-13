package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Destino "Google Drive" do backup noturno (13/11/2026, pedido do dono: "com a pasta visível no Drive").
 *
 * Como funciona, em português:
 *  1. A pessoa conecta a conta UMA vez: Configurações > Backup automático > Conta do Google Drive. É o mesmo login por
 *     código do [LoginGoogle] (google.com/device no celular), pedindo também a permissão `drive.file`.
 *  2. O Google devolve um "refresh token": uma chave que deixa a TV pedir acesso de novo sozinha, de madrugada, sem
 *     celular. Ela fica cifrada pelo Android Keystore nas prefs "backup_noturno", que ficam FORA de todo backup
 *     (BackupCompleto.FORA_DE_PROPOSITO): um arquivo de backup nunca leva acesso ao Drive de ninguém.
 *  3. Toda noite o backup (já trancado com a senha do dono pelo CofreBackup) sobe para a pasta "OneTV-backup" do Drive,
 *     com a mesma regra de 7 cópias da TV, do NAS e do pendrive. O Google só guarda o arquivo cifrado.
 *
 * HTTP puro (HttpURLConnection), sem biblioteca do Google: as 5 chamadas do Drive cabem aqui.
 * O cliente OAuth (id e segredo) é o que o dono cadastrou no painel, aba Claude; nunca vem no código.
 */
object DriveBackup {
    private const val TAG = "TvLauncher.DriveBackup"
    private const val PREFS = "backup_noturno"   // as mesmas do BackupNoturno: já estão fora do backup completo
    private val aleatorio = SecureRandom()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- a conta

    fun conectado(ctx: Context): Boolean = prefs(ctx).contains("drive_token_dados")
    fun email(ctx: Context): String? = prefs(ctx).getString("drive_email", null)?.takeIf { conectado(ctx) }

    /** Guarda a conta que acabou de entrar (chamado pelo LoginGoogle). False se o Keystore falhou. */
    fun guardarConta(ctx: Context, email: String, refresh: String): Boolean = try {
        val (dados, iv) = PlayerArmazem.cifrar(refresh)
        prefs(ctx).edit().putString("drive_email", email).putString("drive_token_dados", dados)
            .putString("drive_token_iv", iv).remove("drive_pasta").commit()
    } catch (e: Exception) { Log.w(TAG, "não consegui cifrar o token: ${e.javaClass.simpleName}"); false }

    private fun refresh(ctx: Context): String? {
        val p = prefs(ctx)
        val dados = p.getString("drive_token_dados", null) ?: return null
        val iv = p.getString("drive_token_iv", null) ?: return null
        return runCatching { PlayerArmazem.decifrar(dados, iv) }.getOrNull()
    }

    /** Desconecta: avisa o Google (o acesso some também do lado de lá) e apaga daqui. Rede: fora da thread da tela. */
    fun desconectar(ctx: Context) {
        refresh(ctx)?.let { runCatching { LoginGoogle.revogar(it) } }
        esquecer(ctx)
    }

    private fun esquecer(ctx: Context) {
        val p = prefs(ctx)
        p.edit().remove("drive_email").remove("drive_token_dados").remove("drive_token_iv").remove("drive_pasta").apply {
            // Sem conta, o destino Drive não tem como funcionar: volta a "só na TV" (a tela mostra).
            if (p.getString("destino", null) == BackupNoturnoRegras.DRIVE) putString("destino", BackupNoturnoRegras.SO_TV)
        }.commit()
    }

    /** Um access token novo (vale ~1 h). Lança IllegalStateException com texto para a tela/registro. */
    private fun acesso(ctx: Context): String {
        val r = refresh(ctx) ?: run {
            if (conectado(ctx)) esquecer(ctx)   // Keystore perdeu a chave: a conta guardada não abre mais
            throw IllegalStateException("conecte a conta do Google Drive de novo")
        }
        val resposta = LoginGoogle.renovar(ctx, r)
        return when (DriveRegras.renovacao(resposta)) {
            DriveRegras.Renovacao.OK -> resposta.getString("access_token")
            DriveRegras.Renovacao.ENTRAR_DE_NOVO -> {
                esquecer(ctx)
                throw IllegalStateException("o Google tirou o acesso ao Drive; conecte a conta de novo")
            }
            DriveRegras.Renovacao.TENTAR_DEPOIS -> throw IllegalStateException("o Google não respondeu (${resposta.optString("error").take(40)})")
        }
    }

    // ---------------------------------------------------------------- o Drive

    /** Sobe a cópia [nome] e apaga as que passarem de 7. Chamado pelo BackupNoturno (thread da fila dele). */
    fun enviar(ctx: Context, nome: String, bytes: ByteArray) {
        val token = acesso(ctx)
        val pasta = pastaId(ctx, token)
        val fronteira = "onetv" + ByteArray(12).also { aleatorio.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val (codigo, _) = pedir("POST", "${DriveRegras.UPLOAD}?uploadType=multipart&fields=id", token,
            DriveRegras.corpoMultipart(fronteira, nome, pasta, bytes), "multipart/related; boundary=$fronteira")
        if (codigo !in 200..299) throw IllegalStateException("o Drive recusou o envio ($codigo)")
        // A rotação vem depois do envio: se ela falhar, a cópia nova já está lá (sobra uma a mais, nunca uma a menos).
        for (id in DriveRegras.quaisApagar(listar(token, pasta)))
            runCatching { pedir("DELETE", "${DriveRegras.API}/$id", token) }
    }

    /** As cópias no Drive, da mais nova para a mais velha. */
    fun listar(ctx: Context): List<DriveRegras.Copia> {
        val token = acesso(ctx)
        return listar(token, pastaId(ctx, token))
    }

    /** O conteúdo de uma cópia (o envelope cifrado, para o BackupCompleto.importar). */
    fun baixar(ctx: Context, id: String): String {
        if (!DriveRegras.idValido(id)) throw IllegalArgumentException("cópia inválida")
        val (codigo, texto) = pedir("GET", "${DriveRegras.API}/$id?alt=media", acesso(ctx),
            limite = BackupCompleto.LIMITE_BYTES * 2)
        if (codigo !in 200..299) throw IllegalStateException("o Drive não entregou a cópia ($codigo)")
        return texto
    }

    private fun listar(token: String, pasta: String): List<DriveRegras.Copia> {
        val (codigo, texto) = pedir("GET", "${DriveRegras.API}?pageSize=100&fields=files(id,name,size,modifiedTime)&q=" +
            enc(DriveRegras.consultaCopias(pasta)), token)
        if (codigo !in 200..299) throw IllegalStateException("o Drive não listou a pasta ($codigo)")
        return DriveRegras.copias(texto)
    }

    /**
     * O id da pasta "OneTV-backup": o guardado, se ainda existe; senão procura (só as criadas pelo launcher aparecem);
     * senão cria. Se a pessoa apagar a pasta no Drive, a próxima noite cria outra.
     */
    private fun pastaId(ctx: Context, token: String): String {
        prefs(ctx).getString("drive_pasta", null)?.takeIf { DriveRegras.idValido(it) }?.let { id ->
            val (codigo, texto) = pedir("GET", "${DriveRegras.API}/$id?fields=id,trashed", token)
            val naLixeira = runCatching { org.json.JSONObject(texto).optBoolean("trashed") }.getOrDefault(true)
            if (codigo in 200..299 && !naLixeira) return id
        }
        val (c1, t1) = pedir("GET", "${DriveRegras.API}?pageSize=10&fields=files(id)&q=" + enc(DriveRegras.consultaPasta()), token)
        if (c1 !in 200..299) throw IllegalStateException("o Drive recusou (${c1}); a API do Drive está ligada no Google Cloud?")
        val achada = org.json.JSONObject(t1).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { DriveRegras.idValido(it) }
        val id = achada ?: run {
            val meta = org.json.JSONObject().put("name", DriveRegras.PASTA).put("mimeType", DriveRegras.TIPO_PASTA).toString()
            val (c2, t2) = pedir("POST", "${DriveRegras.API}?fields=id", token, meta.toByteArray(), "application/json; charset=UTF-8")
            if (c2 !in 200..299) throw IllegalStateException("não consegui criar a pasta no Drive ($c2)")
            org.json.JSONObject(t2).optString("id").takeIf { DriveRegras.idValido(it) } ?: throw IllegalStateException("o Drive não devolveu a pasta")
        }
        prefs(ctx).edit().putString("drive_pasta", id).apply()
        return id
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Uma chamada HTTPS ao Drive. Devolve (código, corpo). O token nunca vai para o log. */
    private fun pedir(metodo: String, url: String, token: String, corpo: ByteArray? = null, tipo: String? = null,
                      limite: Int = 256 * 1024): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo; connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (corpo != null) { doOutput = true; setRequestProperty("Content-Type", tipo ?: "application/octet-stream"); setFixedLengthStreamingMode(corpo.size) }
        }
        try {
            if (corpo != null) c.outputStream.use { it.write(corpo) }
            val codigo = c.responseCode
            val fluxo = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = fluxo?.use { ler(it, limite) } ?: ""
            return codigo to texto
        } finally { c.disconnect() }
    }

    /** Lê no máximo [limite] bytes: uma resposta maior que isso é recusada (nada de encher a memória da TV). */
    private fun ler(entrada: java.io.InputStream, limite: Int): String {
        val saida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = entrada.read(buf)
            if (n < 0) break
            saida.write(buf, 0, n)
            if (saida.size() > limite) throw IllegalStateException("resposta do Drive grande demais")
        }
        return saida.toString("UTF-8")
    }
}

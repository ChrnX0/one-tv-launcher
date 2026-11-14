package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fotos do protetor vindas de uma pasta da rede (ideia 27, 14/11/2026): a pasta de um PC ou NAS (compartilhamento SMB)
 * ou do Nextcloud/WebDAV que já está em "Na rede". Antes só havia o envio manual pelo painel (FotosProtetor.kt).
 * Não é Google Fotos (decisão de 04/11/2026: exigiria conta e API do Google).
 *
 * Como funciona: em "Na rede", dentro de uma pasta com fotos, a linha "Usar estas fotos no protetor" grava a origem
 * (conta + caminho, SEM senha: a senha continua só no MidiaArmazem, cifrada). [sincronizar] copia até
 * [FotosProtetor.MAXIMO_FOTOS] fotos para uma pasta privada do launcher, já reduzidas para a tela (1920 px, JPEG 85,
 * ~300 KB cada), e apaga as que sumiram da pasta. O protetor mostra as cópias, então funciona mesmo com o PC desligado.
 * Sincroniza ao escolher a pasta e, depois, quando o protetor abre e a última cópia tem mais de [INTERVALO_MS].
 *
 * A origem fica num arquivo (não SharedPreferences): quem escolhe é o processo ":player" (tela Na rede) e quem lê é o
 * processo principal (protetor); SharedPreferences não é seguro entre processos.
 */
object FotosRede {
    private const val TAG = "TvLauncher.FotosRede"
    const val INTERVALO_MS = 6 * 60 * 60_000L
    private const val MAX_BYTES = 15 * 1024 * 1024   // o mesmo teto do envio pelo painel

    /** De onde vêm as fotos. [tipo] = ContaMidia.SMB ou WEBDAV; [caminho] = id da pasta na listagem daquela conta. */
    data class Origem(val conta: String, val tipo: String, val caminho: String, val nome: String)

    val TIPOS_ACEITOS = setOf(ContaMidia.SMB, ContaMidia.WEBDAV)

    fun pasta(ctx: Context) = File(ctx.noBackupFilesDir, "fotos-rede")
    private fun arquivoOrigem(ctx: Context) = File(ctx.noBackupFilesDir, "fotos-rede-origem.json")

    // ---------------------------------------------------------------- origem (só texto; testes em FotosRedeTest)

    fun paraJson(o: Origem, ultima: Long = 0L): String = JSONObject().put("conta", o.conta).put("tipo", o.tipo)
        .put("caminho", o.caminho).put("nome", o.nome).put("ultima", ultima).toString()

    fun deJson(t: String?): Pair<Origem, Long>? = runCatching {
        val j = JSONObject(t ?: return null)
        val o = Origem(j.getString("conta"), j.getString("tipo"), j.optString("caminho"), j.optString("nome"))
        if (o.conta.isEmpty() || o.tipo !in TIPOS_ACEITOS) null else o to j.optLong("ultima", 0L)
    }.getOrNull()

    fun origem(ctx: Context): Origem? = deJson(runCatching { arquivoOrigem(ctx).readText() }.getOrNull())?.first

    private fun ultima(ctx: Context): Long = deJson(runCatching { arquivoOrigem(ctx).readText() }.getOrNull())?.second ?: 0L

    /** Escolhe a pasta (e copia as fotos na hora, fora da tela) ou, com null, para de usar e apaga as cópias. */
    fun escolher(ctx: Context, o: Origem?) {
        val app = ctx.applicationContext
        if (o == null) {
            arquivoOrigem(app).delete()
            Fundo.rodar { synchronized(this) { pasta(app).listFiles()?.forEach { it.delete() } } }
            return
        }
        arquivoOrigem(app).writeText(paraJson(o))
        Fundo.rodar { sincronizar(app) }
    }

    /** O protetor abriu: se a cópia estiver velha, renova em segundo plano (as fotos novas entram no próximo protetor). */
    fun talvezSincronizar(ctx: Context, agora: Long = System.currentTimeMillis()) {
        if (precisa(origem(ctx) != null, ultima(ctx), agora)) Fundo.rodar { sincronizar(ctx.applicationContext) }
    }

    fun precisa(temOrigem: Boolean, ultima: Long, agora: Long): Boolean = temOrigem && (agora - ultima >= INTERVALO_MS || agora < ultima)

    /** As cópias locais, para o protetor (junto das enviadas pelo painel). */
    fun listar(ctx: Context): List<File> =
        if (origem(ctx) == null) emptyList()
        else pasta(ctx).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    // ---------------------------------------------------------------- regras puras

    /** As fotos de uma listagem, por nome, no máximo [max]. */
    fun fotosDa(entradas: List<Upnp.Entrada>, max: Int = FotosProtetor.MAXIMO_FOTOS): List<Upnp.Entrada> =
        entradas.filter { !it.pasta && it.tipo == Upnp.Tipo.FOTO }.sortedBy { it.titulo.lowercase() }.take(max)

    /** Nome local estável para o arquivo remoto (o mesmo caminho dá o mesmo nome; nada de "/" nem ".."). */
    fun nomeLocal(caminho: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(caminho.toByteArray(Charsets.UTF_8))
        return h.take(12).joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /** O que baixar (remotos sem cópia) e o que apagar (cópias cujo remoto sumiu). */
    fun plano(remotos: List<String>, locais: Set<String>): Pair<List<String>, List<String>> {
        val porNome = remotos.associateBy { nomeLocal(it) }
        return remotos.filter { nomeLocal(it) !in locais } to locais.filter { it !in porNome }
    }

    // ---------------------------------------------------------------- cópia (rede: fora da thread da tela)

    /** Lista a pasta, baixa as fotos novas, apaga as que sumiram. Erro de rede: fica com as cópias de antes. */
    fun sincronizar(ctx: Context) = synchronized(this) {
        val o = origem(ctx) ?: return@synchronized
        val c = MidiaArmazem.conta(ctx, o.conta)
        if (c == null) { Log.i(TAG, "a conta da pasta foi removida: parando"); escolher(ctx, null); return@synchronized }
        val fotos = try {
            fotosDa(when (o.tipo) { ContaMidia.SMB -> Smb.listar(c, o.caminho); else -> WebDav.listar(c, o.caminho) })
        } catch (e: Exception) { Log.w(TAG, "listar: $e"); return@synchronized }
        val dir = pasta(ctx).apply { mkdirs() }
        val locais = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val (baixar, apagar) = plano(fotos.map { it.id }, locais)
        apagar.forEach { File(dir, it).delete() }
        var ok = 0
        for (caminho in baixar) {
            if (origem(ctx) != o) return@synchronized   // trocaram a pasta no meio: a próxima cópia cuida
            try {
                val bytes = when (o.tipo) {
                    ContaMidia.SMB -> Smb.lerTudo(c, caminho, MAX_BYTES)
                    else -> baixarDav(c, caminho)
                } ?: continue
                val bmp = reduzir(bytes) ?: continue
                val tmp = File(dir, ".baixando.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                tmp.renameTo(File(dir, nomeLocal(caminho)))
                ok++
            } catch (e: Throwable) { Log.w(TAG, "foto: $e") }   // OutOfMemoryError incluso: pula essa foto
        }
        arquivoOrigem(ctx).writeText(paraJson(o, System.currentTimeMillis()))
        Log.i(TAG, "pasta sincronizada: ${fotos.size} fotos, $ok novas, ${apagar.size} apagadas")
    }

    private fun baixarDav(c: ContaMidia, caminho: String): ByteArray? {
        val url = WebDav.urlTocar(c, caminho) ?: return null
        val h = URL(url).openConnection() as HttpURLConnection
        h.connectTimeout = 6000; h.readTimeout = 20000; h.instanceFollowRedirects = false   // a senha não segue redirecionamento
        WebDav.cabecalhoAuth(c).forEach { (k, v) -> h.setRequestProperty(k, v) }
        try {
            if (h.responseCode !in 200..299 || h.contentLengthLong > MAX_BYTES) return null
            h.inputStream.use { ent ->
                val saida = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ent.read(buf); if (n < 0) break
                    if (saida.size() + n > MAX_BYTES) return null
                    saida.write(buf, 0, n)
                }
                return saida.toByteArray()
            }
        } finally { h.disconnect() }
    }

    /** Reduz para a tela (o mesmo cálculo do FotosProtetor) e gira pelo EXIF (lido de um arquivo temporário). */
    private fun reduzir(bytes: ByteArray): Bitmap? {
        val tmp = File.createTempFile("foto", ".bin")
        return try {
            tmp.writeBytes(bytes)
            FotosProtetor.carregar(tmp, 1920, 1080)
        } finally { tmp.delete() }
    }
}

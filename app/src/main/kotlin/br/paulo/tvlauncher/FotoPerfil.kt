package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * De onde vem a foto da bolinha do perfil (13/11/2026, pedido do dono: "por que não aparece a foto da conta, igual
 * aparece no launcher do Google?").
 *
 * O launcher do Google pega a foto por dentro do Google Play Services, numa porta que só apps assinados pelo Google
 * abrem (medido na TV: o provedor "com.google.android.gms.people" recusa quem é de fora). Então a foto chega aqui por
 * dois caminhos que são nossos:
 *
 *  A. Login Google ([LoginGoogle]): quando alguém entra com a conta, o Google já responde com o endereço da foto
 *     (campo "picture"). Baixamos e guardamos. Não precisa de permissão nova nem de outro login.
 *  B. Painel web: a pessoa escolhe uma imagem no celular e envia.
 *
 * Nos dois casos o resultado é o mesmo arquivo que o [Perfil] já sabia ler: perfil-<usuario>.jpg na pasta do app.
 * A imagem recebida NUNCA é gravada como veio: é aberta, recortada num quadrado de [LADO] px e salva de novo como JPEG.
 * Isso garante que o arquivo é mesmo uma imagem e joga fora dados escondidos nela (ex.: o lugar onde a foto foi tirada).
 *
 * A foto fica só na TV: não entra no backup de configurações nem em nenhum log.
 */
object FotoPerfil {
    private const val TAG = "TvLauncher.FotoPerfil"
    private const val PREFS = "foto_perfil"

    /** Maior arquivo aceito (foto de celular costuma ter de 2 a 6 MB). */
    const val LIMITE_BYTES = 8L * 1024 * 1024
    /** Lado, em pixels, da foto guardada. A maior bolinha tem 56 dp (112 px numa TV 1080p): 256 sobra. */
    const val LADO = 256
    /** Maior largura/altura que aceitamos abrir (uma imagem "bomba" de 50000 px estouraria a memória da TV). */
    private const val MAIOR_LADO_ORIGINAL = 12_000

    // ------------------------------------------------------------ partes puras (teste JVM em FotoPerfilTest)

    /**
     * Só baixamos foto de endereço https do próprio Google (*.googleusercontent.com). O endereço vem da resposta do
     * Google, mas conferir aqui impede que um erro (ou alguém no meio) faça a TV buscar um endereço da rede de casa.
     */
    fun urlDoGoogle(url: String): Boolean = try {
        val u = URI(url)
        val host = u.host?.lowercase() ?: ""
        u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) &&
            (host == "googleusercontent.com" || host.endsWith(".googleusercontent.com"))
    } catch (e: Exception) { false }

    /**
     * O Google manda a foto pequena ("...=s96-c": 96 px, recortada). Trocando o final pedimos no tamanho que queremos.
     * Endereço em outro formato volta como veio.
     */
    fun urlNoTamanho(url: String, lado: Int = LADO): String {
        val fim = Regex("""=s\d+(-[a-z0-9-]+)?$""")
        return when {
            fim.containsMatchIn(url) -> url.replace(fim, "=s$lado-c")
            '=' !in url.substringAfterLast('/') && '?' !in url -> "$url=s$lado-c"
            else -> url
        }
    }

    /** "Fulano.Silva@gmail.com" -> "perfil-fulano.silva.jpg". Null se o nome tiver algo que não serve para arquivo. */
    fun nomeArquivo(conta: String): String? {
        val u = Perfil.usuario(conta).lowercase()
        val ok = u.length in 1..64 && u.all { it in 'a'..'z' || it in '0'..'9' || it in "._+-" } && !u.startsWith(".")
        return if (ok && '@' in conta) "perfil-$u.jpg" else null
    }

    /** Os primeiros bytes dizem o tipo de verdade (a extensão e o Content-Type podem mentir). JPEG, PNG ou WebP. */
    fun pareceImagem(b: ByteArray): Boolean {
        fun em(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        val jpeg = em(0) == 0xFF && em(1) == 0xD8 && em(2) == 0xFF
        val png = em(0) == 0x89 && em(1) == 0x50 && em(2) == 0x4E && em(3) == 0x47
        val webp = em(0) == 'R'.code && em(1) == 'I'.code && em(2) == 'F'.code && em(3) == 'F'.code &&
            em(8) == 'W'.code && em(9) == 'E'.code && em(10) == 'B'.code && em(11) == 'P'.code
        return jpeg || png || webp
    }

    /** Quanto reduzir ao abrir (1, 2, 4, 8...): o menor lado ainda fica com pelo menos 2x o [alvo]. */
    fun amostra(largura: Int, altura: Int, alvo: Int = LADO): Int {
        var a = 1
        while (minOf(largura, altura) / (a * 2) >= alvo * 2) a *= 2
        return a
    }

    // ------------------------------------------------------------ estado

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A foto desta conta foi enviada à mão (painel web)? Se sim, um login Google comum não passa por cima dela. */
    fun manual(ctx: Context, conta: String): Boolean = prefs(ctx).getBoolean("manual|" + conta.lowercase(), false)

    private fun marcarManual(ctx: Context, conta: String, sim: Boolean) {
        prefs(ctx).edit().apply { if (sim) putBoolean("manual|" + conta.lowercase(), true) else remove("manual|" + conta.lowercase()) }.apply()
    }

    /** O arquivo da foto desta conta, se existir (o mesmo que o [Perfil] lê). */
    fun arquivo(ctx: Context, conta: String): File? {
        val pasta = ctx.getExternalFilesDir(null) ?: return null
        return File(pasta, nomeArquivo(conta) ?: return null).takeIf { it.isFile }
    }

    fun remover(ctx: Context, conta: String): Boolean {
        val pasta = ctx.getExternalFilesDir(null) ?: return false
        val base = (nomeArquivo(conta) ?: return false).removeSuffix(".jpg")
        // Apaga também .jpeg/.png de mesmo nome (fotos antigas postas por adb), senão a bolinha continuaria com elas.
        val apagou = listOf("jpg", "jpeg", "png").map { File(pasta, "$base.$it").delete() }.any { it }
        marcarManual(ctx, conta, false)
        return apagou
    }

    // ------------------------------------------------------------ receber e gravar

    /**
     * Recebe uma imagem (do painel web ou do Google) e a guarda como a foto de [conta].
     * Devolve null se deu certo, ou o motivo (texto para mostrar à pessoa) se não deu.
     */
    fun receber(ctx: Context, conta: String, tamanho: Long, corpo: InputStream, manual: Boolean): String? {
        val nome = nomeArquivo(conta) ?: return "conta inválida"
        if (tamanho > LIMITE_BYTES) return "foto maior que 8 MB"
        val pasta = ctx.getExternalFilesDir(null) ?: return "a TV está sem a pasta do launcher agora"
        pasta.mkdirs()
        // Temporário começando com "." e com nome único: dois envios juntos não escrevem no mesmo arquivo.
        val tmp = File.createTempFile(".perfil", ".tmp", pasta)
        try {
            // Copia no máximo LIMITE_BYTES + 1: se passar, sabemos que é grande demais sem encher o disco.
            var total = 0L
            val cabeca = ByteArray(12); var naCabeca = 0
            tmp.outputStream().use { saida ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = corpo.read(buf); if (n < 0) break
                    if (naCabeca < cabeca.size) {   // guarda os 12 primeiros bytes para conferir o tipo
                        val c = minOf(n, cabeca.size - naCabeca); System.arraycopy(buf, 0, cabeca, naCabeca, c); naCabeca += c
                    }
                    total += n
                    if (total > LIMITE_BYTES) return "foto maior que 8 MB"
                    saida.write(buf, 0, n)
                }
            }
            if (total == 0L) return "a foto chegou vazia"
            if (!pareceImagem(cabeca.copyOf(naCabeca))) return "o arquivo não é JPG, PNG nem WebP"
            val foto = quadrado(tmp) ?: return "a TV não conseguiu abrir essa imagem"
            // Grava de novo como JPEG (qualidade 90) num segundo temporário e só então troca o arquivo de verdade:
            // se a TV desligar no meio, a foto antiga continua inteira.
            val pronto = File.createTempFile(".perfil", ".jpg.tmp", pasta)
            try {
                pronto.outputStream().use { foto.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                synchronized(this) {
                    val destino = File(pasta, nome)
                    if (!pronto.renameTo(destino)) return "não consegui gravar a foto na TV"
                    val base = nome.removeSuffix(".jpg")
                    File(pasta, "$base.jpeg").delete(); File(pasta, "$base.png").delete()   // versões antigas por adb
                    destino.setLastModified(System.currentTimeMillis())   // o Perfil usa a data para saber que mudou
                }
            } finally { pronto.delete(); foto.recycle() }
            marcarManual(ctx, conta, manual)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "receber falhou: ${e.javaClass.simpleName}")
            return "não consegui gravar a foto na TV"
        } finally { tmp.delete() }
    }

    /** Abre a imagem já reduzida, recorta o quadrado do meio e ajusta para [LADO] px. Null se não for imagem válida. */
    private fun quadrado(f: File): Bitmap? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }   // 1º: só o tamanho, sem gastar RAM
        BitmapFactory.decodeFile(f.path, limites)
        val l = limites.outWidth; val a = limites.outHeight
        if (l <= 0 || a <= 0 || l > MAIOR_LADO_ORIGINAL || a > MAIOR_LADO_ORIGINAL) return null
        val original = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = amostra(l, a) }) ?: return null
        val menor = minOf(original.width, original.height)
        val corte = Bitmap.createBitmap(original, (original.width - menor) / 2, (original.height - menor) / 2, menor, menor)
        val final = Bitmap.createScaledBitmap(corte, LADO, LADO, true)
        if (corte !== original && corte !== final) corte.recycle()
        if (original !== final) original.recycle()
        return final
    }

    /**
     * Caminho A: baixa a foto que o Google informou no login e guarda. [forcar] = a pessoa pediu "Buscar foto da conta"
     * (aí vale mesmo por cima de uma foto enviada à mão). Devolve true se a foto ficou gravada.
     * Chamar fora da thread da tela (usa a rede).
     */
    fun doGoogle(ctx: Context, conta: String, url: String, forcar: Boolean): Boolean {
        if (url.isBlank() || !urlDoGoogle(url)) return false
        if (!forcar && manual(ctx, conta)) return false
        var c: HttpURLConnection? = null
        return try {
            c = (URL(urlNoTamanho(url)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 8_000
                instanceFollowRedirects = false   // um redirecionamento poderia levar a outro endereço: não seguimos
            }
            if (c.responseCode != 200) { Log.w(TAG, "foto do Google: HTTP ${c.responseCode}"); return false }
            val motivo = c.inputStream.use { receber(ctx, conta, c.contentLengthLong.coerceAtLeast(0), it, manual = false) }
            if (motivo != null) Log.w(TAG, "foto do Google recusada: $motivo")
            motivo == null
        } catch (e: Exception) {
            Log.w(TAG, "foto do Google falhou: ${e.javaClass.simpleName}")
            false
        } finally { c?.disconnect() }
    }
}

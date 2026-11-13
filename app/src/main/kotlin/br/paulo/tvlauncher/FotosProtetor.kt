package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Fotos do protetor de tela (05/11/2026, fila longa do brainstorm). As fotos chegam pelo painel no navegador (aba
 * Fotos) e ficam numa pasta PRIVADA do launcher (filesDir/fotos-protetor): não precisam do "acesso a todos os
 * arquivos" e nenhum outro app as vê. Com pelo menos uma foto, o ProtetorTela passa a mostrá-las por trás do relógio,
 * trocando a cada [TROCA_MS]. Sem fotos, o protetor fica como sempre foi.
 *
 * Por que não o Google Fotos: exigiria conta e API do Google (decisão do brainstorm de 04/11/2026).
 *
 * Memória: a TV tem pouca RAM. Cada foto é decodificada já reduzida para o tamanho da tela (inSampleSize), em RGB_565
 * (metade dos bytes do ARGB), e só duas ficam carregadas por vez (a atual e a que entra no esmaecimento).
 */
object FotosProtetor {
    const val LIMITE_BYTES = 15L * 1024 * 1024   // uma foto de celular tem 2 a 6 MB; isto barra vídeo/lixo
    const val MAXIMO_FOTOS = 200
    const val TROCA_MS = 60_000L
    private val EXTENSOES = setOf("jpg", "jpeg", "png", "webp")

    fun pasta(ctx: Context) = File(ctx.filesDir, "fotos-protetor")

    /** Nome aceito: só letras, números, espaço, ponto, hífen e sublinhado, com extensão de imagem. Nada de "/" ou "..". */
    fun nomeValido(nome: String): Boolean =
        nome.length in 5..100 && Regex("""[\p{L}\p{N} ._-]+""").matches(nome) && !nome.startsWith(".") &&
            nome.substringAfterLast('.', "").lowercase() in EXTENSOES

    fun listar(ctx: Context): List<File> =
        pasta(ctx).listFiles { f -> f.isFile && nomeValido(f.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun arquivo(ctx: Context, nome: String): File? = if (nomeValido(nome)) File(pasta(ctx), nome).takeIf { it.isFile } else null

    /**
     * Recebe uma foto do painel. Grava num temporário, confere que é mesmo uma imagem (o Android consegue ler as medidas)
     * e só então dá o nome final. Devolve null se deu certo, ou o motivo em português.
     */
    fun receber(ctx: Context, nome: String, tamanho: Long, corpo: InputStream): String? {
        if (!nomeValido(nome)) return "nome inválido (use letras, números, espaço, . - _ e termine em .jpg, .png ou .webp)"
        if (tamanho <= 0 || tamanho > LIMITE_BYTES) return "foto vazia ou maior que 15 MB"
        if (listar(ctx).size >= MAXIMO_FOTOS) return "já tem $MAXIMO_FOTOS fotos; apague algumas antes"
        val dir = pasta(ctx).apply { mkdirs() }
        // Restos de envios que caíram no meio (processo morto antes do finally): apaga os de mais de 1 h.
        dir.listFiles { f -> f.name.startsWith(".envio") && f.name.endsWith(".tmp") &&
            System.currentTimeMillis() - f.lastModified() > 3_600_000L }?.forEach { it.delete() }
        // Temporário com nome único: dois envios ao mesmo tempo (duas abas) não escrevem no mesmo arquivo. Começa com
        // "." de propósito, então listar() o ignora.
        val tmp = File.createTempFile(".envio", ".tmp", dir)
        try {
            tmp.outputStream().use { saida -> corpo.copyTo(saida) }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return "o arquivo não é uma imagem que a TV consiga abrir"
            // Mesmo nome não apaga a foto que já está lá (duas "IMG_0001.jpg" de celulares diferentes): ganha "-2".
            // Escolher o nome e renomear juntos, sob trava: dois envios com o mesmo nome não pegam o mesmo "livre".
            synchronized(this) {
                val destino = File(dir, nomeLivre(nome) { File(dir, it).exists() })
                if (!tmp.renameTo(destino)) return "não consegui gravar a foto na TV"
            }
            return null
        } finally { tmp.delete() }
    }

    /**
     * [nome] se estiver livre; senão "nome-2.jpg", "nome-3.jpg"... (hífen e não parênteses: [nomeValido] só aceita
     * letras, números, espaço, . - _). Se nada couber em 100 letras, devolve o próprio [nome].
     */
    fun nomeLivre(nome: String, existe: (String) -> Boolean): String {
        if (!existe(nome)) return nome
        val base = nome.substringBeforeLast('.'); val ext = nome.substringAfterLast('.')
        for (n in 2..MAXIMO_FOTOS + 1) {
            val outro = "$base-$n.$ext"
            if (outro.length <= 100 && !existe(outro)) return outro
        }
        return nome
    }

    fun apagar(ctx: Context, nome: String): Boolean = arquivo(ctx, nome)?.delete() == true

    /** Fator de redução (potência de 2) para a foto caber em [largura]x[altura] sem desperdício. */
    fun reducao(larguraFoto: Int, alturaFoto: Int, largura: Int, altura: Int): Int {
        var n = 1
        while (larguraFoto / (n * 2) >= largura && alturaFoto / (n * 2) >= altura) n *= 2
        return n
    }

    /** Graus a girar para a foto ficar em pé, pela tag Orientation do EXIF (1 = normal; 6 = 90; 3 = 180; 8 = 270). */
    fun graus(orientacao: Int): Int = when (orientacao) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    /** Orientações do EXIF que pedem espelhar a foto (de lado a lado) depois de girar. */
    fun espelha(orientacao: Int): Boolean = orientacao == 2 || orientacao == 4 || orientacao == 5 || orientacao == 7

    /** Em 5 a 8 a foto está deitada no arquivo: largura e altura trocam depois de girar. */
    fun deitada(orientacao: Int): Boolean = orientacao in 5..8

    /**
     * A foto reduzida para a tela, ou null se não abrir. O BitmapFactory ignora a orientação do EXIF, e foto em retrato
     * de celular costuma vir gravada deitada com "gire 90°" no EXIF: por isso lemos a tag e giramos aqui.
     */
    fun carregar(f: File, largura: Int, altura: Int): Bitmap? = try {
        val orientacao = try {
            ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }   // PNG/sem EXIF: segue normal
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        // Deitada: a medida que vai para a largura da tela é a altura do arquivo.
        val (lf, af) = if (deitada(orientacao)) o.outHeight to o.outWidth else o.outWidth to o.outHeight
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = reducao(lf, af, largura, altura)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
        val g = graus(orientacao)
        if (bmp == null || (g == 0 && !espelha(orientacao))) bmp
        else {
            // Mesma ordem da ExifInterface do AndroidX: gira primeiro, espelha depois.
            val m = Matrix().apply { postRotate(g.toFloat()); if (espelha(orientacao)) postScale(-1f, 1f) }
            val girada = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (girada !== bmp) bmp.recycle()   // libera a deitada: a TV tem pouca RAM
            girada
        }
    } catch (e: Throwable) { null }   // OutOfMemoryError incluso: sem a foto, o protetor segue só com o relógio
}

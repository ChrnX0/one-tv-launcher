package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Recado com foto (10/11/2026, ideia M; li no PiPup, https://github.com/rogro82/pipup: "Exposes an HTTP endpoint to
 * display notifications on your TV", com imagem). O [AvisoTv] mostra o cartão com a foto embaixo do texto.
 *
 * Chega pelo painel (envio de arquivo) ou pelo adb (`--es arquivo /sdcard/Download/foto.jpg`). Regras, para não
 * travar a TV com uma imagem enorme ou um arquivo que não é imagem:
 *  - só JPEG, PNG ou WebP, conferidos pelos primeiros bytes ([tipo]), não pela extensão;
 *  - no máximo [MAX_BYTES] (4 MB);
 *  - decodificada já reduzida, com no máximo [MAX_LADO] px no lado maior ([amostra]).
 */
object RecadoImagem {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_LADO = 960

    enum class Tipo { JPEG, PNG, WEBP }

    /** Tipo pelos "números mágicos" do começo do arquivo. Puro. */
    fun tipo(b: ByteArray): Tipo? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Tipo.JPEG
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> Tipo.PNG
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> Tipo.WEBP
        else -> null
    }

    /** Fator de redução (potência de 2) para o lado maior caber em [MAX_LADO]. Puro. */
    fun amostra(largura: Int, altura: Int, maxLado: Int = MAX_LADO): Int {
        var a = 1
        while (maxOf(largura, altura) / a > maxLado) a *= 2
        return a
    }

    /** Bytes -> imagem pronta para a tela, ou null se não serve. Fora da thread da tela. */
    fun decodificar(b: ByteArray): Bitmap? {
        if (b.size > MAX_BYTES || tipo(b) == null) return null
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, limites)
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return null
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply {
            inSampleSize = amostra(limites.outWidth, limites.outHeight)
        })
    }

    /** Mostra o recado com foto. [texto] passa pela mesma limpeza do recado comum. */
    fun mostrar(ctx: Context, texto: String?, bytes: ByteArray, segundos: Int?): AvisoTv.Resultado? {
        val t = Recado.limpar(texto ?: "📷") ?: return null
        val bmp = decodificar(bytes) ?: return null
        return AvisoTv.mostrar(ctx, ctx.getString(R.string.pg_recado), t, Recado.duracao(segundos), som = true, guardarSeApagada = false, imagem = bmp, fica = AvisoRegras.Fica.ATENCAO)
    }
}

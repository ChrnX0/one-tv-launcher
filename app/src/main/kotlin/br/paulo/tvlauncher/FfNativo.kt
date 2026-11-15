package br.paulo.tvlauncher

import android.util.Log
import java.nio.ByteBuffer

/**
 * Ponte para a cola nativa do player (15/11/2026, arquivos .cc em nativo/cola): FFmpeg (LGPL-2.1) e libass, no lugar da NextLib.
 *
 * As bibliotecas vêm prontas em nativo/libs (nativo/compilar.sh); o Android as extrai junto com o APK. Carregar na
 * ordem das dependências evita depender do carregador do Android 10 achar cada uma sozinho.
 * Se algo falhar ao carregar (APK sem as libs, arquitetura estranha), [disponivel] fica false e o player segue só com
 * o que o chip da TV sabe tocar: nada quebra.
 */
object FfNativo {
    private const val TAG = "FfNativo"

    /** Códigos devolvidos pelas funções nativas (iguais aos de nativo/cola/comum.h). */
    const val ERRO_DADOS = -1
    const val ERRO_OUTRO = -2

    val disponivel: Boolean by lazy {
        try {
            for (lib in listOf("avutil", "swresample", "swscale", "avcodec", "avformat", "fribidi", "ass", "onetvplayer"))
                System.loadLibrary(lib)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "bibliotecas do FFmpeg/libass não carregaram: ${e.message}")
            false
        }
    }

    /** Versão do FFmpeg compilado (aparece nas informações técnicas do player). */
    val versaoFfmpeg: String by lazy { if (disponivel) runCatching { versao() }.getOrDefault("?") else "-" }

    /** O FFmpeg compilado tem este decodificador? (lista em nativo/compilar.sh) */
    fun tem(codec: String): Boolean = disponivel && runCatching { temDecodificador(codec) }.getOrDefault(false)

    // ------------------------------------------------------------- áudio (audio.cc)
    @JvmStatic external fun audioAbrir(codec: String, extra: ByteArray?, taxa: Int, canais: Int, pcmFloat: Boolean,
                                       bloco: Int, bitrate: Int, bits: Int): Long
    @JvmStatic external fun audioDecodificar(ctx: Long, entrada: ByteBuffer, tamanho: Int): Int
    @JvmStatic external fun audioCopiar(ctx: Long, saida: ByteBuffer)
    @JvmStatic external fun audioCanais(ctx: Long): Int
    @JvmStatic external fun audioTaxa(ctx: Long): Int
    @JvmStatic external fun audioLimpar(ctx: Long)
    @JvmStatic external fun audioFechar(ctx: Long)
    @JvmStatic external fun temDecodificador(nome: String): Boolean
    @JvmStatic external fun versao(): String

    // ------------------------------------------------------------- vídeo (video.cc)
    @JvmStatic external fun videoAbrir(codec: String, extra: ByteArray?, largura: Int, altura: Int, threads: Int): Long
    @JvmStatic external fun videoDecodificar(ctx: Long, entrada: ByteBuffer, tamanho: Int, tempoUs: Long): Int
    @JvmStatic external fun videoLargura(ctx: Long): Int
    @JvmStatic external fun videoAltura(ctx: Long): Int
    @JvmStatic external fun videoTempo(ctx: Long): Long
    @JvmStatic external fun videoCores(ctx: Long): Int
    @JvmStatic external fun videoCopiar(ctx: Long, destino: ByteBuffer, passoY: Int, passoUV: Int): Boolean
    @JvmStatic external fun videoPular(ctx: Long)
    @JvmStatic external fun videoLimpar(ctx: Long)
    @JvmStatic external fun videoFechar(ctx: Long)
    @JvmStatic external fun videoDesenhar(surface: android.view.Surface, dados: ByteBuffer, largura: Int, altura: Int, passoY: Int, passoUV: Int): Boolean

    // ------------------------------------------------------------- legenda ASS (legenda_ass.cc)
    @JvmStatic external fun assAbrir(configFontes: String?, fontePadrao: String?): Long
    @JvmStatic external fun assFonte(ctx: Long, nome: String, dados: ByteArray)
    @JvmStatic external fun assFaixa(ctx: Long, cabecalho: ByteArray?): Long
    @JvmStatic external fun assFaixaArquivo(ctx: Long, arquivo: ByteArray): Long
    @JvmStatic external fun assFala(faixa: Long, dados: ByteArray, inicioMs: Long, duracaoMs: Long)
    @JvmStatic external fun assTamanho(ctx: Long, largura: Int, altura: Int, videoL: Int, videoA: Int)
    @JvmStatic external fun assEscala(ctx: Long, fator: Double)
    @JvmStatic external fun assDesenhar(ctx: Long, faixa: Long, ms: Long, destino: ByteBuffer, forcar: Boolean): Int
    @JvmStatic external fun assFecharFaixa(faixa: Long)
    @JvmStatic external fun assFechar(ctx: Long)

    // ------------------------------------------------------------- arquivo (arquivo.cc)
    @JvmStatic external fun arquivoSondar(inicio: ByteArray): String?
    @JvmStatic external fun arquivoAbrir(leitor: LeitorNativo, infoCompleta: Boolean): Long
    @JvmStatic external fun arquivoFechar(ctx: Long)
    @JvmStatic external fun arquivoDuracaoUs(ctx: Long): Long
    @JvmStatic external fun arquivoFaixas(ctx: Long): Int
    @JvmStatic external fun arquivoFaixaInfo(ctx: Long, i: Int): LongArray?
    @JvmStatic external fun arquivoFaixaCodec(ctx: Long, i: Int): String?
    @JvmStatic external fun arquivoFaixaExtra(ctx: Long, i: Int): ByteArray?
    @JvmStatic external fun arquivoFaixaMeta(ctx: Long, i: Int, chave: String): String?
    @JvmStatic external fun arquivoCapitulos(ctx: Long): LongArray
    @JvmStatic external fun arquivoCapituloTitulo(ctx: Long, i: Int): String?
    @JvmStatic external fun arquivoLerPacote(ctx: Long): Int
    @JvmStatic external fun arquivoPacoteFaixa(ctx: Long): Int
    @JvmStatic external fun arquivoPacoteChave(ctx: Long): Boolean
    @JvmStatic external fun arquivoPacoteTempoUs(ctx: Long): Long
    @JvmStatic external fun arquivoPacoteDuracaoUs(ctx: Long): Long
    @JvmStatic external fun arquivoPacoteCopiar(ctx: Long, destino: ByteArray)
    @JvmStatic external fun arquivoBuscar(ctx: Long, tempoUs: Long): Boolean
    @JvmStatic external fun arquivoPontoBusca(ctx: Long, tempoUs: Long): LongArray?
    @JvmStatic external fun arquivoMiniatura(ctx: Long, tempoUs: Long, destino: ByteBuffer, largura: Int, altura: Int): Boolean
}

/**
 * Quem entrega bytes ao libavformat: posição livre (ele pula para o índice no fim do AVI, para os anexos do MKV...).
 * Chamado da thread nativa que está lendo; pode bloquear (rede).
 */
interface LeitorNativo {
    /** Lê até [n] bytes a partir de [posicao] em [destino]. Devolve quantos leu, ou -1 no fim. */
    fun ler(posicao: Long, destino: ByteArray, n: Int): Int
    /** Tamanho total em bytes, ou -1 se não se sabe (aí o arquivo é lido só para frente). */
    fun tamanho(): Long
}

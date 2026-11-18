// Áudio pelo FFmpeg (15/11/2026): Dolby AC-3/E-AC-3, DTS, TrueHD, MP2, WMA, RealAudio... tudo o que o chip da TV
// não decodifica. Entra um pacote comprimido, sai PCM intercalado (16 bits ou float) com os mesmos canais do arquivo,
// na ordem do Android (FL FR FC LFE ...). O downmix para estéreo (e o "diálogo mais alto") é feito depois, no Kotlin,
// para valer também para o áudio decodificado pelo chip.
#include "comum.h"
#include <vector>
#include <cstring>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

namespace {
struct Audio {
  AVCodecContext* ctx = nullptr;
  AVPacket* pacote = nullptr;
  AVFrame* quadro = nullptr;
  SwrContext* swr = nullptr;
  AVSampleFormat saidaFormato = AV_SAMPLE_FMT_S16;
  // Formato de entrada do swr atual: se o arquivo mudar no meio (acontece em TV a cabo gravada), refaz.
  int swrTaxa = 0;
  AVSampleFormat swrFormato = AV_SAMPLE_FMT_NONE;
  AVChannelLayout swrLayout{};
  std::vector<uint8_t> pendente;   // PCM pronto, à espera do Kotlin copiar
  int canais = 0, taxa = 0;
};

AVCodecContext* abrirContexto(const char* nome, JNIEnv* env, jbyteArray extra, int taxa, int canais,
                              int bloco, long bitrate, int bits) {
  const AVCodec* codec = avcodec_find_decoder_by_name(nome);
  if (!codec) { LOGE("sem decodificador de áudio '%s'", nome); return nullptr; }
  AVCodecContext* c = avcodec_alloc_context3(codec);
  if (!c) return nullptr;
  c->request_sample_fmt = AV_SAMPLE_FMT_S16;
  if (extra) {
    jsize n = env->GetArrayLength(extra);
    c->extradata = static_cast<uint8_t*>(av_mallocz(n + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!c->extradata) { avcodec_free_context(&c); return nullptr; }
    c->extradata_size = n;
    env->GetByteArrayRegion(extra, 0, n, reinterpret_cast<jbyte*>(c->extradata));
  }
  // Formatos "crus" (RealAudio, ADPCM, PCM) não dizem taxa/canais no pacote: vêm do contêiner.
  if (taxa > 0) c->sample_rate = taxa;
  if (canais > 0) av_channel_layout_default(&c->ch_layout, canais);
  // WMA, RealAudio (cook), ADPCM: o tamanho do bloco e a taxa vêm do contêiner; sem eles o avcodec_open2 recusa.
  if (bloco > 0) c->block_align = bloco;
  if (bitrate > 0) c->bit_rate = bitrate;
  if (bits > 0) c->bits_per_coded_sample = bits;
  if (avcodec_open2(c, codec, nullptr) < 0) { LOGE("avcodec_open2 falhou (%s)", nome); avcodec_free_context(&c); return nullptr; }
  return c;
}

bool prepararSwr(Audio* a, const AVFrame* q) {
  if (a->swr && q->sample_rate == a->swrTaxa && q->format == a->swrFormato &&
      av_channel_layout_compare(&q->ch_layout, &a->swrLayout) == 0) return true;
  swr_free(&a->swr);
  av_channel_layout_uninit(&a->swrLayout);
  AVChannelLayout saida{};
  // A SAÍDA fica a do primeiro quadro: o Kotlin abre o AudioTrack uma vez com esses canais e taxa. Se a entrada
  // muda no meio (5.1 vira 2.0 no comercial de uma gravação), o swr mistura e reamostra para o formato de antes;
  // antes mudava a saída e o AudioTrack de 6 canais recebia PCM de 2 (som embaralhado e acelerado).
  int n = a->canais > 0 ? a->canais : q->ch_layout.nb_channels;
  if (n > 8) n = 8;   // o AudioTrack do Android vai até 7.1
  if (n < 1) n = 2;
  int taxaSaida = a->taxa > 0 ? a->taxa : q->sample_rate;
  av_channel_layout_default(&saida, n);
  if (swr_alloc_set_opts2(&a->swr, &saida, a->saidaFormato, taxaSaida,
                          &q->ch_layout, static_cast<AVSampleFormat>(q->format), q->sample_rate, 0, nullptr) < 0 ||
      swr_init(a->swr) < 0) {
    LOGE("swr_init falhou");
    swr_free(&a->swr);
    av_channel_layout_uninit(&saida);
    return false;
  }
  av_channel_layout_copy(&a->swrLayout, &q->ch_layout);
  a->swrTaxa = q->sample_rate;
  a->swrFormato = static_cast<AVSampleFormat>(q->format);
  a->canais = n;
  a->taxa = taxaSaida;
  av_channel_layout_uninit(&saida);
  return true;
}

// Converte o quadro e põe no fim de `pendente`.
bool guardar(Audio* a) {
  if (!prepararSwr(a, a->quadro)) return false;
  int bytesAmostra = av_get_bytes_per_sample(a->saidaFormato) * a->canais;
  int max = swr_get_out_samples(a->swr, a->quadro->nb_samples);
  size_t antes = a->pendente.size();
  a->pendente.resize(antes + static_cast<size_t>(max) * bytesAmostra);
  uint8_t* destino = a->pendente.data() + antes;
  int feitas = swr_convert(a->swr, &destino, max, const_cast<const uint8_t**>(a->quadro->extended_data), a->quadro->nb_samples);
  if (feitas < 0) { a->pendente.resize(antes); return false; }
  a->pendente.resize(antes + static_cast<size_t>(feitas) * bytesAmostra);
  return true;
}
}  // namespace

NATIVO(jlong, audioAbrir)(JNIEnv* env, jclass, jstring codec, jbyteArray extra, jint taxa, jint canais, jboolean pcmFloat,
                          jint bloco, jint bitrate, jint bits) {
  const char* nome = codec ? env->GetStringUTFChars(codec, nullptr) : nullptr;
  if (!nome) return 0;
  auto* a = new Audio();
  a->saidaFormato = pcmFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
  a->ctx = abrirContexto(nome, env, extra, taxa, canais, bloco, bitrate, bits);
  env->ReleaseStringUTFChars(codec, nome);
  a->pacote = av_packet_alloc();
  a->quadro = av_frame_alloc();
  if (!a->ctx || !a->pacote || !a->quadro) {
    avcodec_free_context(&a->ctx); av_packet_free(&a->pacote); av_frame_free(&a->quadro);
    delete a;
    return 0;
  }
  return para(a);
}

// Decodifica um pacote. Devolve quantos bytes de PCM ficaram prontos (pegar com audioCopiar), ou ERRO_*.
NATIVO(jint, audioDecodificar)(JNIEnv* env, jclass, jlong p, jobject entrada, jint tamanho) {
  Audio* a = de<Audio>(p);
  a->pendente.clear();
  auto* dados = static_cast<uint8_t*>(env->GetDirectBufferAddress(entrada));
  if (!dados) return ERRO_OUTRO;
  a->pacote->data = dados;
  a->pacote->size = tamanho;
  // Colhe tudo o que o decodificador já tem. 0 = ok, senão ERRO_*.
  auto colher = [a]() -> int {
    for (;;) {
      int r = avcodec_receive_frame(a->ctx, a->quadro);
      if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return 0;
      if (r == AVERROR_INVALIDDATA) return ERRO_DADOS;
      if (r < 0) { LOGE("receive_frame áudio: %d", r); return ERRO_OUTRO; }
      bool ok = guardar(a);
      av_frame_unref(a->quadro);
      if (!ok) return ERRO_OUTRO;
    }
  };
  int r = avcodec_send_packet(a->ctx, a->pacote);
  if (r == AVERROR(EAGAIN)) {
    // Cheio: esvazia e manda de novo (antes o pacote se perdia, um buraco no som).
    int e = colher();
    if (e != 0) return e;
    r = avcodec_send_packet(a->ctx, a->pacote);
  }
  if (r == AVERROR_INVALIDDATA) return ERRO_DADOS;
  if (r < 0 && r != AVERROR(EAGAIN)) { LOGE("send_packet áudio: %d", r); return ERRO_DADOS; }
  int e = colher();
  if (e != 0) return e;
  return static_cast<jint>(a->pendente.size());
}

NATIVO(void, audioCopiar)(JNIEnv* env, jclass, jlong p, jobject saida) {
  Audio* a = de<Audio>(p);
  auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(saida));
  jlong cap = env->GetDirectBufferCapacity(saida);
  size_t n = a->pendente.size();
  if (d && cap >= static_cast<jlong>(n)) memcpy(d, a->pendente.data(), n);
}

NATIVO(jint, audioCanais)(JNIEnv*, jclass, jlong p) { return de<Audio>(p)->canais; }
NATIVO(jint, audioTaxa)(JNIEnv*, jclass, jlong p) { return de<Audio>(p)->taxa; }

NATIVO(void, audioLimpar)(JNIEnv*, jclass, jlong p) {
  Audio* a = de<Audio>(p);
  avcodec_flush_buffers(a->ctx);
  a->pendente.clear();
}

NATIVO(void, audioFechar)(JNIEnv*, jclass, jlong p) {
  Audio* a = de<Audio>(p);
  if (!a) return;
  avcodec_free_context(&a->ctx);
  av_packet_free(&a->pacote);
  av_frame_free(&a->quadro);
  swr_free(&a->swr);
  av_channel_layout_uninit(&a->swrLayout);
  delete a;
}

// Diz se o FFmpeg compilado tem este decodificador (o Kotlin usa para responder "sei tocar este formato?").
NATIVO(jboolean, temDecodificador)(JNIEnv* env, jclass, jstring nome) {
  const char* n = nome ? env->GetStringUTFChars(nome, nullptr) : nullptr;
  if (!n) return JNI_FALSE;
  bool tem = avcodec_find_decoder_by_name(n) != nullptr;
  env->ReleaseStringUTFChars(nome, n);
  return tem;
}

NATIVO(jstring, versao)(JNIEnv* env, jclass) { return env->NewStringUTF(av_version_info()); }
